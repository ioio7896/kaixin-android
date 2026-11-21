package com.example.offlineime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Captures audio continuously while a separate worker recognizes bounded segments. */
final class VoiceInputController implements AutoCloseable {
    interface Listener {
        void onVoiceListening();
        void onVoicePartial(String text);
        void onVoiceDraining(String message);
        void onVoiceComplete(String text, String warning);
        void onVoiceError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLES_PER_BLOCK = 3200;
    // About one minute of PCM; never let a slow model grow memory indefinitely.
    private static final int MAX_QUEUED_BLOCKS = 300;
    // Bound the final IPC payload while allowing much longer than 30 seconds.
    private static final int MAX_TEXT_LENGTH = 20_000;
    // Keep the loaded NCNN voice model warm across consecutive utterances, then release when idle.
    private static final long IDLE_RELEASE_DELAY_MS = 60_000L;
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

    private static final class Session {
        final ArrayBlockingQueue<short[]> audio = new ArrayBlockingQueue<>(MAX_QUEUED_BLOCKS);
        volatile boolean canceled;
        volatile boolean finishing;
        volatile boolean captureDone;
        volatile boolean consumerDone;
        volatile String warning = "";
        AudioRecord recorder; // Accessed only under this session's monitor.

        synchronized void requestStop(boolean cancel) {
            if (cancel) canceled = true;
            else finishing = true;
            if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
            }
        }
    }

    VoiceInputController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        cancel();
        if (closed) return;
        main.removeCallbacks(idleReleaseTask);
        Session session = new Session();
        active = session;
        sessionGeneration.incrementAndGet();
        worker.execute(() -> run(session));
    }

    void finish() { if (active != null) active.requestStop(false); }

    void cancel() {
        Session session = active;
        active = null;
        if (session != null) {
            session.requestStop(true);
            scheduleIdleRelease();
        }
    }

    /** Releases the cached NCNN model when no recording session is active (e.g., onTrimMemory). */
    void trimMemory() {
        main.removeCallbacks(idleReleaseTask);
        if (!closed) releaseModelIfIdle();
    }

    private void scheduleIdleRelease() {
        main.removeCallbacks(idleReleaseTask);
        if (!closed) main.postDelayed(idleReleaseTask, IDLE_RELEASE_DELAY_MS);
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
        post(session, () -> listener.onVoiceDraining(warning + "，正在处理已录语音…"));
    }

    private void capture(Session session, AudioRecord recorder) {
        try {
            short[] buffer = new short[SAMPLES_PER_BLOCK];
            while (!session.canceled && !session.finishing) {
                int count = recorder.read(buffer, 0, buffer.length);
                if (session.canceled) break;
                if (count < 0) {
                    if (!session.finishing) interruptCapture(session, "麦克风读取中断");
                    break;
                }
                if (count == 0) continue;
                short[] block = Arrays.copyOf(buffer, count);
                if (!session.audio.offer(block)) {
                    interruptCapture(session, "设备处理较慢，已停止录音");
                    // Preserve the block already read before ending capture.
                    while (!session.canceled && !session.consumerDone
                            && !session.audio.offer(block, 100, TimeUnit.MILLISECONDS)) { }
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
                synchronized (session) {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        try { recorder.stop(); } catch (IllegalStateException ignored) { }
                    }
                    session.recorder = null;
                    recorder.release();
                }
            } finally { session.captureDone = true; }
        }
    }

    @SuppressLint("MissingPermission") // Checked immediately before opening the microphone.
    private void run(Session session) {
        AudioRecord recorder = null;
        Thread captureThread = null;
        boolean captureStarted = false;
        String bestEffort = "";
        try {
            if (session.canceled) return;
            OfflineVoiceModel model = acquireModel();
            if (session.canceled) return;
            if (session.finishing) { post(session, () -> listener.onVoiceComplete("", "")); return; }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("permission denied");
            model.beginRecording();
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IOException("麦克风不可用");
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, SAMPLE_RATE * 4));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("麦克风不可用");
            synchronized (session) {
                if (session.canceled) return;
                if (session.finishing) { post(session, () -> listener.onVoiceComplete("", "")); return; }
                session.recorder = recorder;
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
            StringBuilder completed = new StringBuilder();
            String previousPreview = "";
            float[] samples = new float[SAMPLES_PER_BLOCK];
            while (!session.canceled) {
                short[] block = session.audio.poll(100, TimeUnit.MILLISECONDS);
                if (block == null) {
                    if (session.captureDone && session.audio.isEmpty()) break;
                    continue;
                }
                for (int i = 0; i < block.length; i++) samples[i] = block[i] / 32768.0f;
                String current = model.accept(samples, block.length, false);
                int endpoint = model.endpointKind();
                if (endpoint != 0) {
                    model.resetSegment();
                    if (!current.isEmpty()) {
                        completed.append(current);
                        if (endpoint == 1) finishSentence(completed);
                    }
                    current = "";
                    if (completed.length() >= MAX_TEXT_LENGTH)
                        interruptCapture(session, "听写文字已达长度上限");
                }
                String preview = completed.toString() + current;
                bestEffort = preview;
                if (!preview.equals(previousPreview)) {
                    previousPreview = preview;
                    post(session, () -> listener.onVoicePartial(preview));
                }
            }
            if (!session.canceled) {
                String tail = model.accept(null, 0, true);
                completed.append(tail);
                finishSentence(completed);
                String text = completed.toString();
                String warning = session.warning;
                post(session, () -> listener.onVoiceComplete(text, warning));
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
                synchronized (session) {
                    session.recorder = null;
                    recorder.release();
                }
                session.captureDone = true;
            }
            if (closed) disposeCachedModel();
            else {
                if (cachedModel != null) cachedModel.endRecording();
                scheduleIdleRelease();
            }
        }
    }

    private void reportFailure(Session session, String partial, String message) {
        if (partial.isEmpty()) post(session, () -> listener.onVoiceError(message));
        else post(session, () -> listener.onVoiceComplete(partial, message));
    }

    private static void finishSentence(StringBuilder text) {
        int length = text.length();
        if (length == 0) return;
        char last = text.charAt(length - 1);
        if (last == '，' || last == '、') text.setCharAt(length - 1, '。');
        else if ("。？！；,.!?;".indexOf(last) < 0) text.append('。');
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
