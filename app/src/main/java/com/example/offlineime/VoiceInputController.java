package com.example.offlineime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Captures audio continuously while a separate worker recognizes bounded segments. */
final class VoiceInputController implements AutoCloseable {
    static final class Config {
        final float rule2SilenceSec;
        final boolean autoPunctuation;
        final boolean spokenPunctuation;
        final boolean smartNumbers;
        final int silenceTimeoutSec;
        final boolean segmentCommitMode;
        final String hotwordsText;
        final float hotwordsScore;

        Config(float rule2SilenceSec, boolean autoPunctuation, boolean spokenPunctuation,
               boolean smartNumbers, int silenceTimeoutSec, boolean segmentCommitMode,
               String hotwordsText, float hotwordsScore) {
            this.rule2SilenceSec = rule2SilenceSec;
            this.autoPunctuation = autoPunctuation;
            this.spokenPunctuation = spokenPunctuation;
            this.smartNumbers = smartNumbers;
            this.silenceTimeoutSec = silenceTimeoutSec;
            this.segmentCommitMode = segmentCommitMode;
            this.hotwordsText = hotwordsText == null ? "" : hotwordsText;
            this.hotwordsScore = hotwordsScore;
        }
    }

    interface Listener {
        void onVoiceListening();
        void onVoiceAudioState(float level, long elapsedMs, boolean paused, boolean lowSignalWarning);
        void onVoicePartial(String text);
        void onVoiceSegmentCommit(String segment);
        void onVoiceDraining(String message);
        void onVoiceComplete(String text, String warning);
        void onVoiceError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    // 100 ms blocks cut first-word latency in half compared with 200 ms blocks.
    private static final int SAMPLES_PER_BLOCK = 1600;
    // About one minute of PCM; never let a slow model grow memory indefinitely.
    private static final int MAX_QUEUED_BLOCKS = 600;
    private static final int BLOCK_POOL_SIZE = 16;
    // Bound the final IPC payload while allowing much longer than 30 seconds.
    private static final int MAX_TEXT_LENGTH = 20_000;
    // Keep the loaded NCNN voice model warm across consecutive utterances, then release when idle.
    private static final long IDLE_RELEASE_DELAY_MS = 60_000L;
    private static final long QUICK_RELEASE_DELAY_MS = 20_000L;
    private static final float SPEECH_RMS_THRESHOLD = 0.012f;
    private static final int SHORT_PAUSE_COMMA_MS = 520;
    private static final long LOW_SIGNAL_WARNING_MS = 4_000L;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable idleReleaseTask = this::releaseModelIfIdle;
    private volatile Session active;
    private final AtomicLong sessionGeneration = new AtomicLong();
    private volatile boolean closed;
    // Owned exclusively by the single worker thread.
    private OfflineVoiceModel cachedModel;

    private static final class AudioBlock {
        final short[] pcm = new short[SAMPLES_PER_BLOCK];
        int length;
    }

    private static final class Session {
        final Config config;
        final ArrayBlockingQueue<AudioBlock> audio = new ArrayBlockingQueue<>(MAX_QUEUED_BLOCKS);
        final ArrayBlockingQueue<AudioBlock> freeBlocks = new ArrayBlockingQueue<>(BLOCK_POOL_SIZE);
        final AtomicBoolean clearRequested = new AtomicBoolean();
        final AtomicInteger backspaceRequests = new AtomicInteger();
        volatile boolean canceled;
        volatile boolean finishing;
        volatile boolean paused;
        volatile boolean captureDone;
        volatile boolean consumerDone;
        volatile boolean hasRecognizedText;
        volatile boolean autoSilentStop;
        volatile int committedSegments;
        volatile String warning = "";
        AudioRecord recorder; // Accessed only under this session's monitor.
        NoiseSuppressor noiseSuppressor;
        AutomaticGainControl gainControl;

        Session(Config config) {
            this.config = config;
        }

        AudioBlock obtainBlock() {
            AudioBlock recycled = freeBlocks.poll();
            return recycled != null ? recycled : new AudioBlock();
        }

        void recycleBlock(AudioBlock block) {
            if (block != null) freeBlocks.offer(block);
        }

        synchronized void requestStop(boolean cancel) {
            if (cancel) canceled = true;
            else finishing = true;
            if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
            }
        }

        synchronized void releaseAudioEffectsAndRecorder(AudioRecord device) {
            if (noiseSuppressor != null) {
                try { noiseSuppressor.release(); } catch (RuntimeException ignored) { }
                noiseSuppressor = null;
            }
            if (gainControl != null) {
                try { gainControl.release(); } catch (RuntimeException ignored) { }
                gainControl = null;
            }
            if (device != null) {
                if (device.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    try { device.stop(); } catch (IllegalStateException ignored) { }
                }
                if (recorder == device) recorder = null;
                try { device.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    VoiceInputController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start(Config config) {
        cancel();
        if (closed) return;
        main.removeCallbacks(idleReleaseTask);
        Session session = new Session(config);
        active = session;
        sessionGeneration.incrementAndGet();
        worker.execute(() -> run(session));
    }

    void finish() { if (active != null) active.requestStop(false); }

    void setPaused(boolean paused) {
        Session session = active;
        if (session != null && !session.canceled && !session.finishing) {
            session.paused = paused;
        }
    }

    boolean isPaused() {
        Session session = active;
        return session != null && session.paused;
    }

    void clearRecognized() {
        Session session = active;
        if (session != null && !session.canceled && !session.finishing) {
            session.clearRequested.set(true);
        }
    }

    void backspaceRecognized() {
        Session session = active;
        if (session != null && !session.canceled && !session.finishing) {
            session.backspaceRequests.incrementAndGet();
        }
    }

    void cancel() {
        Session session = active;
        active = null;
        if (session != null) {
            session.requestStop(true);
            scheduleIdleRelease(IDLE_RELEASE_DELAY_MS);
        }
    }

    /** Shortens cached model retention when leaving an input field. */
    void scheduleQuickRelease() {
        if (active == null && !closed) scheduleIdleRelease(QUICK_RELEASE_DELAY_MS);
    }

    /** Releases the cached NCNN model when no recording session is active (e.g., onTrimMemory). */
    void trimMemory() {
        main.removeCallbacks(idleReleaseTask);
        if (!closed) releaseModelIfIdle();
    }

    private void scheduleIdleRelease(long delayMs) {
        main.removeCallbacks(idleReleaseTask);
        if (!closed) main.postDelayed(idleReleaseTask, delayMs);
    }

    private void releaseModelIfIdle() {
        if (closed) return;
        final long generation = sessionGeneration.get();
        try {
            worker.execute(() -> {
                if (sessionGeneration.get() == generation) disposeCachedModel();
            });
        } catch (RuntimeException ignored) { }
    }

    private OfflineVoiceModel acquireModel() throws IOException {
        if (cachedModel == null) cachedModel = new OfflineVoiceModel(context.getAssets());
        return cachedModel;
    }

    private void disposeCachedModel() {
        if (cachedModel != null) {
            cachedModel.close();
            cachedModel = null;
        }
    }

    private void post(Session session, Runnable callback) {
        main.post(() -> { if (!closed && active == session && !session.canceled) callback.run(); });
    }

    private void interruptCapture(Session session, String warning) {
        synchronized (session) {
            if (session.canceled || session.finishing) return;
            session.warning = warning;
            session.requestStop(false);
        }
        if (!warning.isEmpty()) {
            post(session, () -> listener.onVoiceDraining(warning + "，正在处理已录语音…"));
        }
    }

    private AudioFocusRequest requestTransientAudioFocus(AudioManager audioManager) {
        if (audioManager == null) return null;
        try {
            AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChange -> { })
                    .build();
            int granted = audioManager.requestAudioFocus(request);
            return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? request : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void abandonAudioFocus(AudioManager audioManager, AudioFocusRequest request) {
        if (audioManager == null || request == null) return;
        try {
            audioManager.abandonAudioFocusRequest(request);
        } catch (RuntimeException ignored) { }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord openRecorder() throws IOException {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("麦克风不可用");
        int bufferBytes = Math.max(minBuffer, SAMPLE_RATE * 4);
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int source : sources) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) return candidate;
            } catch (IllegalArgumentException ignored) { }
            if (candidate != null) {
                try { candidate.release(); } catch (RuntimeException ignored) { }
            }
        }
        throw new IOException("麦克风不可用");
    }

    private void attachAudioEffects(Session session, AudioRecord recorder) {
        int audioSessionId = recorder.getAudioSessionId();
        if (audioSessionId == AudioManager.ERROR) return;
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(audioSessionId);
                if (ns != null) {
                    ns.setEnabled(true);
                    session.noiseSuppressor = ns;
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl agc = AutomaticGainControl.create(audioSessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                    session.gainControl = agc;
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private void capture(Session session, AudioRecord recorder) {
        long elapsedMs = 0L;
        long zeroSignalMs = 0L;
        long continuousSilenceMs = 0L;
        long timeoutMs = session.config.silenceTimeoutSec > 0
                ? session.config.silenceTimeoutSec * 1000L : 0L;
        try {
            while (!session.canceled && !session.finishing) {
                AudioBlock block = session.obtainBlock();
                int count = recorder.read(block.pcm, 0, block.pcm.length);
                if (session.canceled) {
                    session.recycleBlock(block);
                    break;
                }
                if (count < 0) {
                    session.recycleBlock(block);
                    if (!session.finishing) interruptCapture(session, "麦克风读取中断");
                    break;
                }
                if (count == 0) {
                    session.recycleBlock(block);
                    continue;
                }
                if (session.paused) {
                    session.recycleBlock(block);
                    continuousSilenceMs = 0L;
                    final long reportElapsed = elapsedMs;
                    post(session, () -> listener.onVoiceAudioState(0f, reportElapsed, true, false));
                    continue;
                }
                block.length = count;
                long blockMs = (count * 1000L) / SAMPLE_RATE;
                elapsedMs += blockMs;
                double sumSq = 0.0;
                int maxAbs = 0;
                for (int i = 0; i < count; i++) {
                    int s = block.pcm[i];
                    int abs = s < 0 ? -s : s;
                    if (abs > maxAbs) maxAbs = abs;
                    sumSq += (double) s * s;
                }
                float rms = (float) Math.sqrt(sumSq / count) / 32768.0f;
                float visualLevel = Math.min(1f, (float) Math.pow(Math.min(1f, rms * 9.0f), 0.72));
                if (maxAbs == 0) zeroSignalMs += blockMs;
                else zeroSignalMs = 0L;
                if (rms >= SPEECH_RMS_THRESHOLD) continuousSilenceMs = 0L;
                else continuousSilenceMs += blockMs;

                final float levelOut = visualLevel;
                final long elapsedOut = elapsedMs;
                final boolean lowSignal = zeroSignalMs >= LOW_SIGNAL_WARNING_MS && !session.hasRecognizedText;
                post(session, () -> listener.onVoiceAudioState(levelOut, elapsedOut, false, lowSignal));

                if (!session.audio.offer(block)) {
                    interruptCapture(session, "设备处理较慢，已停止录音");
                    // Preserve the block already read before ending capture.
                    while (!session.canceled && !session.consumerDone
                            && !session.audio.offer(block, 100, TimeUnit.MILLISECONDS)) { }
                    break;
                }
                if (timeoutMs > 0 && continuousSilenceMs >= timeoutMs) {
                    session.autoSilentStop = true;
                    session.requestStop(false);
                    break;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            interruptCapture(session, "录音被中断");
        } catch (RuntimeException | OutOfMemoryError error) {
            interruptCapture(session, "麦克风不可用");
        } finally {
            try {
                session.releaseAudioEffectsAndRecorder(recorder);
            } finally {
                session.captureDone = true;
            }
        }
    }

    @SuppressLint("MissingPermission") // Checked immediately before opening the microphone.
    private void run(Session session) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest focusRequest = null;
        AudioRecord recorder = null;
        Thread captureThread = null;
        boolean captureStarted = false;
        String bestEffort = "";
        try {
            if (session.canceled) return;
            if (session.finishing) { post(session, () -> listener.onVoiceComplete("", "")); return; }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("permission denied");

            // Request transient audio focus and open the microphone BEFORE loading the model so
            // opening syllables are buffered immediately instead of dropped during cold start.
            focusRequest = requestTransientAudioFocus(audioManager);
            recorder = openRecorder();
            synchronized (session) {
                if (session.canceled) return;
                if (session.finishing) { post(session, () -> listener.onVoiceComplete("", "")); return; }
                session.recorder = recorder;
                attachAudioEffects(session, recorder);
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IOException("麦克风未能启动");
            }
            AudioRecord captureDevice = recorder;
            captureThread = new Thread(() -> capture(session, captureDevice), "offline-voice-capture");
            captureThread.setDaemon(true);
            captureThread.start();
            captureStarted = true;
            post(session, listener::onVoiceListening);

            OfflineVoiceModel model = acquireModel();
            if (session.canceled) return;
            model.beginRecording(session.config.rule2SilenceSec,
                    session.config.hotwordsText, session.config.hotwordsScore);

            StringBuilder completed = new StringBuilder();
            List<Integer> clauseBreaks = new ArrayList<>();
            String previousPreview = "";
            String currentText = "";
            boolean editedTail = false;
            float[] samples = new float[SAMPLES_PER_BLOCK];

            while (!session.canceled) {
                AudioBlock block = session.audio.poll(60, TimeUnit.MILLISECONDS);
                boolean inputDrained = block == null && session.captureDone && session.audio.isEmpty();
                if (session.clearRequested.getAndSet(false)) {
                    completed.setLength(0);
                    clauseBreaks.clear();
                    currentText = "";
                    editedTail = false;
                    session.hasRecognizedText = session.committedSegments > 0;
                    model.resetSegment();
                    bestEffort = "";
                    previousPreview = "";
                    post(session, () -> listener.onVoicePartial(""));
                }
                int backspaces = session.backspaceRequests.getAndSet(0);
                if (backspaces > 0 && (completed.length() > 0 || !currentText.isEmpty())) {
                    if (!currentText.isEmpty()) {
                        // Keep the formatted preview before resetting the decoder's partial hypothesis.
                        completed.append(currentText);
                        currentText = "";
                        clauseBreaks.clear();
                        model.resetSegment();
                    }
                    int end = completed.length();
                    while (backspaces-- > 0 && end > 0) {
                        end = GraphemeUtils.previousClusterStart(completed, end);
                    }
                    completed.setLength(end);
                    editedTail = true;
                    String updated = completed.toString();
                    session.hasRecognizedText = !updated.isEmpty() || session.committedSegments > 0;
                    bestEffort = updated;
                    previousPreview = updated;
                    post(session, () -> listener.onVoicePartial(updated));
                }

                if (block == null) {
                    if (inputDrained) break;
                    continue;
                }
                int count = block.length;
                for (int i = 0; i < count; i++) samples[i] = block.pcm[i] / 32768.0f;
                session.recycleBlock(block);

                String currentRaw = model.accept(samples, count, false);
                if (session.config.autoPunctuation && currentRaw.length() >= 4
                        && model.trailingSilenceMs() >= SHORT_PAUSE_COMMA_MS) {
                    int len = currentRaw.length();
                    if (clauseBreaks.isEmpty() || clauseBreaks.get(clauseBreaks.size() - 1) < len) {
                        clauseBreaks.add(len);
                    }
                }
                int endpoint = model.endpointKind();
                if (endpoint != 0) {
                    model.resetSegment();
                    String segment = formatSegment(currentRaw, clauseBreaks, session.config, endpoint == 1);
                    if (!segment.isEmpty()) {
                        completed.append(segment);
                        editedTail = false;
                        session.hasRecognizedText = true;
                    }
                    if (session.config.segmentCommitMode && completed.length() > 0) {
                        String committedSegment = completed.toString();
                        completed.setLength(0);
                        session.committedSegments++;
                        post(session, () -> listener.onVoiceSegmentCommit(committedSegment));
                    }
                    currentText = "";
                    clauseBreaks.clear();
                    if (completed.length() >= MAX_TEXT_LENGTH)
                        interruptCapture(session, "听写文字已达长度上限");
                } else {
                    currentText = formatSegment(currentRaw, clauseBreaks, session.config, false);
                }
                String preview = completed + currentText;
                if (!preview.isEmpty()) session.hasRecognizedText = true;
                bestEffort = preview;
                if (!preview.equals(previousPreview)) {
                    previousPreview = preview;
                    post(session, () -> listener.onVoicePartial(preview));
                }
            }
            if (!session.canceled) {
                String tailRaw = model.accept(null, 0, true);
                String tail = formatSegment(tailRaw, clauseBreaks, session.config, true);
                if (!tail.isEmpty()) session.hasRecognizedText = true;
                completed.append(tail);
                if (!session.config.segmentCommitMode && tail.isEmpty() && !editedTail) {
                    VoiceTextPostProcessor.finishSentence(completed, session.config.autoPunctuation);
                }
                String finalText = completed.toString();
                String warning = session.warning;
                if (warning.isEmpty() && finalText.isEmpty() && session.autoSilentStop
                        && session.committedSegments == 0) {
                    warning = "未检测到说话，已自动停止";
                }
                final String outText = finalText;
                final String outWarning = warning;
                post(session, () -> listener.onVoiceComplete(outText, outWarning));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            reportFailure(session, bestEffort, "语音输入已中断");
        } catch (SecurityException error) {
            reportFailure(session, bestEffort, "麦克风权限不可用，请在系统设置中允许");
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            disposeCachedModel();
            reportFailure(session, bestEffort, "语音识别中断");
        } finally {
            session.consumerDone = true;
            session.requestStop(false);
            if (captureStarted && captureThread != null) {
                try { captureThread.join(3000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            } else if (recorder != null) {
                session.releaseAudioEffectsAndRecorder(recorder);
                session.captureDone = true;
            }
            abandonAudioFocus(audioManager, focusRequest);
            if (closed) disposeCachedModel();
            else {
                if (cachedModel != null) cachedModel.endRecording();
                scheduleIdleRelease(IDLE_RELEASE_DELAY_MS);
            }
        }
    }

    private static String formatSegment(String raw, List<Integer> clauseBreaks,
                                        Config config, boolean endSentence) {
        if (raw == null || raw.isEmpty()) return "";
        String withCommas = VoiceTextPostProcessor.insertClauseCommas(raw, clauseBreaks, config.autoPunctuation);
        String processed = VoiceTextPostProcessor.process(withCommas, config.spokenPunctuation, config.smartNumbers);
        if (!endSentence) return processed;
        StringBuilder sb = new StringBuilder(processed);
        VoiceTextPostProcessor.finishSentence(sb, config.autoPunctuation);
        return sb.toString();
    }

    private void reportFailure(Session session, String partial, String message) {
        if (partial.isEmpty()) post(session, () -> listener.onVoiceError(message));
        else post(session, () -> listener.onVoiceComplete(partial, message));
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacks(idleReleaseTask);
        cancel();
        try {
            worker.execute(this::disposeCachedModel);
        } catch (RuntimeException ignored) { }
        worker.shutdown();
    }
}
