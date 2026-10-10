package com.example.offlineime;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 最小但完整的离线 IME：所有候选均来自本地词库，不申请联网权限。 */
public class OfflineImeService extends InputMethodService implements KeyboardView.Callback {
    enum Mode { PINYIN, NINE, STROKE, ENGLISH, SYMBOLS }
    private static final long NEXT_WORD_PREDICTION_WINDOW_MS = 10_000L;
    private static final String KEYBOARD_STATE_PREFERENCES = "keyboard_state";
    private static final String LAST_KEYBOARD_MODE = "last_keyboard_mode";
    private static final String LAST_CHINESE_MODE = "last_chinese_mode";
    private static final String ENGLISH_WORD_MODE = "english_word_mode";

    private PinyinDictionary dictionary;
    private KeyboardView keyboardView;
    private boolean originalImeWindowLayoutSaved;
    private boolean floatingImeWindow;
    private int originalImeWindowWidth;
    private int originalImeWindowHeight;
    private int originalImeWindowGravity;
    private int originalImeWindowX;
    private int originalImeWindowY;
    private android.graphics.drawable.Drawable originalImeWindowBackground;
    private EditorInfo editorInfo;
    private int lastEditorSelectionStart = -1;
    private int lastEditorSelectionEnd = -1;
    private boolean hostHasComposingText;
    private final StringBuilder composing = new StringBuilder();
    private Mode mode = Mode.PINYIN;
    private Mode modeBeforeSymbols = Mode.PINYIN;
    private Mode modeBeforeEnglish = Mode.PINYIN;
    private boolean shift;
    private boolean capsLock;
    private long lastShiftTapUptime;
    private boolean englishWordMode = true;
    private boolean numericEditor;
    private boolean editorForcesKeyboardLayout;
    private String editorLayoutKind = "text";
    private boolean secureEditor;
    private boolean toolsShown;
    private boolean clipboardShown;
    private boolean clipboardEnablePromptShown;
    private boolean clipboardClearConfirmShown;
    private String clipboardFilter = "all";
    private String clipboardSearchQuery = "";
    private boolean clipboardSearchDialogOpen;
    private boolean clipboardSearchRestorePanel;
    private boolean clipboardSearchResumePending;
    private EditorInfo clipboardSearchReturnEditorInfo;
    private List<ClipboardHistoryStore.Entry> clipboardClearBackup;
    private final java.util.LinkedHashSet<String> clipboardSelection = new java.util.LinkedHashSet<>();
    private boolean clipboardMultiSelect;
    private Runnable clipboardUndoExpiry;
    private ClipboardManager clipboardManager;
    private boolean clipboardListenerRegistered;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChangedListener =
            () -> OfflineImeService.this.mainHandler.post(this::captureClipboardWhileActive);
    private static final Pattern LABELED_ONE_TIME_CODE = Pattern.compile(
            "(?i)(验证码|动态码|校验码|一次性密码|otp|one[- ]?time code).{0,16}\\b\\d{4,8}\\b");
    private boolean handwritingShown;
    private boolean emojiShown;
    private boolean symbolsShown;
    private boolean layoutChooserShown;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean userDataReceiverRegistered;
    private final android.content.BroadcastReceiver userDataClearedReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            userDataEpoch++; ordinaryWordAssembly.clear(); ordinaryWordOffer = null;
            wordCreation.clear(); lastCustomWordChange = null; lastCustomWordNotice = "";
            if (dictionary != null) dictionary.clearUserWords();
            if (contextModel != null) contextModel.clear();
            composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
            lockedPinyinPath = "";
            previousCommittedWord = "";
            previousPreviousCommittedWord = "";
            lastChineseCommitAt = 0;
            if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
            nextWordPredictionExpiry = null;
            candidatePage = 0;
            hidePanels();
            invalidateCandidates();
            refresh();
        }
    };
    private ExecutorService loaderExecutor;
    private Future<?> loaderTask;
    private Future<?> prewarmTask;
    private boolean prewarmScheduled;
    private boolean prewarmFinished;
    private final Runnable dictionaryPrewarmRunnable = this::startDictionaryPrewarmIfIdle;
    private ClipboardHistoryStore clipboardHistory;
    private volatile UserContextModel contextModel;
    private String previousCommittedWord = "";
    private String previousPreviousCommittedWord = "";
    private long lastChineseCommitAt;
    private Runnable nextWordPredictionExpiry;
    private CandidateQueryCoordinator candidateQueries;
    private final TouchCorrectionModel touchCorrection = new TouchCorrectionModel();
    private TouchCorrectionModel.Sample pendingTouch;
    private boolean nameMode;
    private final WordCreationSession wordCreation = new WordCreationSession();
    private volatile long wordEditorSession;
    private volatile long userDataEpoch;
    private final WordCreationSession ordinaryWordAssembly = new WordCreationSession();
    private CustomWordStore.Word ordinaryWordOffer;
    private CustomWordStore.Change lastCustomWordChange;
    private String lastCustomWordNotice = "";
    private Runnable customWordNoticeExpiry;
    private String wordCreationCategory = "普通词";
    private final ShuangpinInput shuangpin = new ShuangpinInput();
    boolean isShuangpin() { return mode == Mode.PINYIN && settingsPreferences != null
            && "xiaohe".equals(settingsPreferences.getString("pinyin_scheme", "full")); }
    boolean isNameMode() { return nameMode; }
    private volatile String candidateResultKey = "";
    private volatile String candidatePendingKey = "";
    private volatile String candidateCompleteKey = "";
    private volatile String candidateSelectableKey = "";
    private String measuredCandidateKey = "";
    private long measuredCandidateAt;
    private boolean measuredCandidateDrawn;
    private volatile List<PinyinDictionary.Entry> candidateResults = java.util.Collections.emptyList();
    private Mode candidateDisplayMode;
    private String candidateDisplayInput = "";
    private final ByteBudgetCache<List<PinyinDictionary.Entry>> candidateCache = new ByteBudgetCache<>(4 * 1024 * 1024, 512, ByteBudgetCache::entries);
    private boolean refreshScheduled;
    private final Runnable renderRefreshRunnable = this::renderRefreshNow;
    private final OrderedKeyInput keyInputOrder = new OrderedKeyInput();
    OrderedKeyInput keyInputOrder() { return keyInputOrder; }
    String candidateInputVersion() { return currentCandidateKey(); }
    private long settingsRevision;
    private android.content.SharedPreferences settingsPreferences;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (preferences, name) -> {
        if ("pinyin_scheme".equals(name)) {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null && hostHasComposingText) { connection.setComposingText("", 1); connection.finishComposingText(); }
            hostHasComposingText = false; composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
            composingCursor = 0; OfflineImeService.this.pathEditor.clear(); lockedPinyinPath = "";
        }
        settingsRevision++;
        invalidateCandidates();
        if (keyboardView != null) {
            keyboardView.setDark(isDarkMode());
            mainHandler.post(this::refresh);
        }
    };
    private String segmentsCacheKey = "";
    private List<PinyinPathEditor.Segment> segmentsCache = java.util.Collections.emptyList();
    private final PinyinPathEditor pathEditor = new PinyinPathEditor();
    private boolean destroyed;
    private boolean selectionMode;
    private int selectionAnchor = -1;
    private int selectionExtent = -1;

    List<List<String>> pinyinPaths(String input) {
        if (isShuangpin()) {
            List<List<String>> paths = new ArrayList<>();
            for (ShuangpinInput.Path path : shuangpin.paths(input)) paths.add(path.readings);
            return paths;
        }
        if (dictionary == null) return java.util.Collections.emptyList();
        return mode == Mode.NINE ? dictionary.cachedT9Paths(input) : dictionary.paths(input);
    }
    String activePinyinPath() {
        if (isShuangpin()) {
            List<String> readings = new ArrayList<>();
            for (PinyinPathEditor.Segment segment : pinyinSegments()) readings.add(segment.reading);
            return String.join("·", readings);
        }
        if (!pathEditor.hasLocks()) return lockedPinyinPath.replace("'", "·");
        List<String> readings = new ArrayList<>();
        for (PinyinPathEditor.Segment segment : pinyinSegments()) readings.add(segment.reading);
        return String.join("·", readings);
    }
    String selectedPinyinPath() {
        if (secureEditor || composing.length() == 0
                || mode != Mode.PINYIN && mode != Mode.NINE) return "";
        List<String> readings = new ArrayList<>();
        if (isShuangpin()) {
            for (PinyinPathEditor.Segment segment : pinyinSegments())
                if (segment.locked) readings.add(segment.reading);
        } else {
            PinyinPathEditor.Query query = pathQuery();
            if (query != null) for (PinyinPathEditor.Part part : query.parts)
                if (part.locked) readings.add(part.text);
        }
        return String.join("·", readings);
    }
    String currentPinyinPath() {
        if (secureEditor || composing.length() == 0
                || mode != Mode.PINYIN && mode != Mode.NINE) return "";
        List<String> readings = new ArrayList<>();
        for (PinyinPathEditor.Segment segment : pinyinSegments()) {
            String reading = pinyinDisplayReading(segment);
            if (!reading.isEmpty() && reading.matches("[a-z/]+")) readings.add(reading);
        }
        if (!readings.isEmpty()) return String.join("·", readings);
        // In 26-key mode the typed letters remain useful even before a valid
        // syllable has been resolved. Never present T9 digits as a reading.
        return mode == Mode.PINYIN ? composing.toString().replace('\'', '·') : "";
    }
    List<PinyinPathEditor.Segment> pinyinSegments() {
        if (isShuangpin() && !secureEditor) return shuangpin.segments(composing.toString(),
                candidateResults.isEmpty() ? "" : candidateResults.get(0).pronunciation);
        if (secureEditor || mode != Mode.NINE && mode != Mode.PINYIN) return java.util.Collections.emptyList();
        PinyinPathEditor.Query constraint = pathQuery();
        String reading = candidateResults.isEmpty() ? "" : candidateResults.get(0).pronunciation;
        String key = mode + ":" + composing + ':' + (constraint == null ? "" : constraint.key)
                + ':' + candidateResultKey + ':' + candidateCompleteKey + ':' + reading;
        if (key.equals(segmentsCacheKey)) return segmentsCache;
        List<PinyinPathEditor.Segment> valuesForInput = pathEditor.segments(composing.toString(), mode == Mode.NINE, fragment -> {
            List<List<String>> values = pinyinPaths(fragment);
            if (!fragment.equals(composing.toString()) || !candidateResultKey.equals(currentCandidateKey()))
                return values;
            if (!candidateResults.isEmpty()) {
                PinyinDictionary.Entry entry = candidateResults.get(0);
                if (!entry.pronunciation.matches("[a-z]+(?:[ ']+[a-z]+)*")) return values;
                List<String> candidatePath = java.util.Arrays.asList(entry.pronunciation.split("[ ']+"));
                for (String syllable : candidatePath)
                    if (!PinyinPathEditor.isCompleteReading(syllable)) return values;
                List<List<String>> preferred = new ArrayList<>();
                preferred.add(candidatePath);
                preferred.addAll(values); return preferred;
            }
            return values;
        });
        segmentsCache = valuesForInput;
        segmentsCacheKey = key;
        return valuesForInput;
    }
    PinyinPathEditor.Segment focusedPinyinSegment() {
        List<PinyinPathEditor.Segment> segments = pinyinSegments();
        for (PinyinPathEditor.Segment segment : segments)
            if (segment.start == pathEditor.focus()) return segment;
        // Continue from the first unconfirmed syllable when input or its
        // segmentation changes. Explicit editing still takes precedence.
        for (PinyinPathEditor.Segment segment : segments)
            if (!segment.locked) return segment;
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }
    private void advancePinyinSelection(int selectedStart, int selectedEnd) {
        int nextStart = selectedEnd;
        while (nextStart < composing.length() && composing.charAt(nextStart) == '\'') nextStart++;
        // Store the raw boundary so asynchronous candidate updates can resolve
        // the remaining syllables without reusing the selected one's focus.
        pathEditor.focus(nextStart < composing.length() ? nextStart : selectedStart);
    }
    boolean pinyinSegmentFocused(PinyinPathEditor.Segment segment) {
        PinyinPathEditor.Segment focused = focusedPinyinSegment();
        return focused != null && focused.start == segment.start;
    }
    boolean pinyinPathLocked() {
        if (isShuangpin()) return !shuangpin.signature(composing.toString()).isEmpty();
        pathQuery(); return pathEditor.hasLocks();
    }
    boolean pinyinCandidatesReady() { return currentCandidateKey().equals(candidateSelectableKey); }
    boolean candidateSurfaceReady() { return currentCandidateKey().equals(candidateSelectableKey); }
    boolean pinyinCandidatesEmpty() { return candidateResults.isEmpty(); }
    boolean nineKeyMode() { return mode == Mode.NINE; }
    void refreshPinyinPaths() { if (!destroyed) refresh(); }
    String pinyinDisplayReading(PinyinPathEditor.Segment segment) {
        if (isShuangpin()) return segment.reading;
        if (segment.prefix) {
            List<String> entered = PinyinPathEditor.enteredReadings(
                    composing.substring(segment.start, segment.end), mode == Mode.NINE, segment.reading);
            if (!entered.isEmpty()) return String.join("/", entered);
        }
        return segment.reading.substring(0, Math.min(segment.typedLetters, segment.reading.length()));
    }
    boolean pinyinReadingSelected(PinyinPathEditor.Segment segment, String reading) {
        if (isShuangpin()) return segment != null && shuangpin.selected(segment.start, reading);
        return segment != null && segment.locked && (segment.prefix
                ? reading.startsWith(segment.reading) : reading.equals(segment.reading));
    }
    List<String> pinyinSyllableChoices(PinyinPathEditor.Segment segment) {
        if (dictionary == null || segment == null || secureEditor) return java.util.Collections.emptyList();
        String raw = composing.substring(segment.start, segment.end);
        if (isShuangpin()) return ShuangpinInput.choices(raw);
        List<String> readings = dictionary.syllableChoices(raw, mode == Mode.NINE);
        String currentSpelling = mode == Mode.NINE ? PinyinDictionary.toT9(segment.reading) : segment.reading;
        if (segment.locked && currentSpelling.equals(raw) && !readings.contains(segment.reading))
            readings.add(segment.reading);
        // Explicitly selected letters and complete readings precede other legal prefixes.
        readings.sort(java.util.Comparator.comparingInt((String reading) -> pinyinReadingSelected(segment, reading) ? 0 : 1)
                .thenComparingInt(reading -> PinyinPathEditor.isCompleteReading(reading) ? 0 : 1)
                .thenComparing(java.util.Comparator.comparingInt(String::length).reversed())
                .thenComparing(java.util.Comparator.naturalOrder()));
        return readings;
    }

    List<PinyinPathEditor.Segment> ninePinyinChoiceSegments() {
        PinyinPathEditor.Segment focus = focusedPinyinSegment();
        return pathEditor.alternativeSegments(composing.toString(), true, focus, this::pinyinPaths);
    }
    private PinyinPathEditor.Query pathQuery() {
        if (isShuangpin()) { shuangpin.sync(composing.toString()); return null; }
        if (mode != Mode.PINYIN && mode != Mode.NINE) { pathEditor.clear(); return null; }
        pathEditor.sync(composing.toString(), mode == Mode.NINE);
        return pathEditor.hasLocks() ? pathEditor.query(composing.toString(), mode == Mode.NINE) : null;
    }
    private boolean candidateExpanded;
    private String lockedPinyinPath = "";
    private int composingCursor;
    private String deferredCommitKey = "", deferredCommitAction = "";
    private long contextRevision;
    private int candidatePage;

    @Override public void onCreate() {
        super.onCreate();
        keyInputOrder.setOnIdle(() -> { if (keyboardView != null) keyboardView.onKeyInputIdle(); });
        settingsPreferences = getSharedPreferences("settings", MODE_PRIVATE);
        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsListener);
        dictionary = new PinyinDictionary(getAssets(), this);
        clipboardHistory = new ClipboardHistoryStore(this);
        loaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "offline-lexicon-loader");
            t.setDaemon(true);
            return t;
        });
        candidateQueries = new CandidateQueryCoordinator();
        contextRevision = settingsPreferences.getLong("context_revision", 0);
        loadContextModel();
        loaderTask = loaderExecutor.submit(() -> dictionary.load(() -> mainHandler.post(this::onDictionaryReady)));
        registerUserDataReceiver();
        logImeLifecycle("service_created");
    }

    @Override public View onCreateInputView() {
        cancelRenderRefresh();
        if (keyboardView != null) keyboardView.releaseAudio();
        keyboardView = new KeyboardView(this, this);
        keyboardView.setDark(isDarkMode());
        updateInputViewLayout();
        // Supply key rows and a non-zero height before the framework measures it.
        keyboardView.refresh(mode, shift, "", java.util.Collections.emptyList());
        logImeLifecycle("create_input_view");
        return keyboardView;
    }

    private void loadContextModel() {
        if (contextModel != null) contextModel.cancelDeferredLoad();
        final UserContextModel model = new UserContextModel(this, true);
        contextModel = model;
        dictionary.setSentenceContext(model);
        loaderExecutor.submit(() -> {
            model.loadDeferred();
            mainHandler.post(() -> {
                if (destroyed || contextModel != model) return;
                invalidateCandidates();
                refresh();
            });
        });
    }

    private void cancelRenderRefresh() {
        if (keyboardView != null) keyboardView.removeCallbacks(renderRefreshRunnable);
        refreshScheduled = false;
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            candidateCache.clear();
            if (dictionary != null) dictionary.trimQueryCaches();
        }
    }

    void onKeyboardViewDetached(KeyboardView view) {
        if (view == keyboardView) cancelRenderRefresh();
    }

    private void logImeLifecycle(String event) {
        if (!BuildConfig.DEBUG) return;
        android.content.res.Configuration config = getResources().getConfiguration();
        android.util.Log.d("OfflineImeLifecycle", event
                + " requested=" + isShowInputRequested() + " shown=" + isInputViewShown()
                + " attached=" + (keyboardView != null && keyboardView.isAttachedToWindow())
                + " height=" + (keyboardView == null ? 0 : keyboardView.getHeight())
                + " keyboard=" + config.keyboard + " hardKeyboardHidden=" + config.hardKeyboardHidden);
    }

    @Override public boolean onShowInputRequested(int flags, boolean configChange) {
        boolean accepted = super.onShowInputRequested(flags, configChange);
        logImeLifecycle("show_request accepted=" + accepted + " flags=" + flags
                + " configChange=" + configChange);
        return accepted;
    }

    @Override public void onWindowShown() {
        super.onWindowShown();
        logImeLifecycle("window_shown");
    }

    @Override public void onWindowHidden() {
        super.onWindowHidden();
        logImeLifecycle("window_hidden");
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateInputViewLayout();
        if (keyboardView != null) refresh();
    }

    private void updateInputViewLayout() {
        if (keyboardView == null) return;
        android.content.res.Configuration configuration = getResources().getConfiguration();
        boolean landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || configuration.screenWidthDp > configuration.screenHeightDp;
        keyboardView.setFloatingLandscape(landscape);
        WindowManager.LayoutParams params;
        android.app.Dialog imeDialog = getWindow();
        android.view.Window imeWindow = imeDialog == null ? null : imeDialog.getWindow();
        if (imeWindow == null) return;
        params = imeWindow.getAttributes();
        if (landscape) {
            if (!originalImeWindowLayoutSaved) {
                originalImeWindowWidth = params.width;
                originalImeWindowHeight = params.height;
                originalImeWindowGravity = params.gravity;
                originalImeWindowX = params.x;
                originalImeWindowY = params.y;
                originalImeWindowBackground = imeWindow.getDecorView().getBackground();
                originalImeWindowLayoutSaved = true;
            }
            int usableWidthDp = Math.max(240, configuration.screenWidthDp - 32);
            int floatingWidthDp = Math.min(780, usableWidthDp);
            params.width = Math.round(floatingWidthDp * getResources().getDisplayMetrics().density);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.x = 0;
            params.y = 0;
            imeWindow.setAttributes(params);
            imeWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            floatingImeWindow = true;
        } else if (floatingImeWindow && originalImeWindowLayoutSaved) {
            params.width = originalImeWindowWidth;
            params.height = originalImeWindowHeight;
            params.gravity = originalImeWindowGravity;
            params.x = originalImeWindowX;
            params.y = originalImeWindowY;
            imeWindow.setAttributes(params);
            imeWindow.setBackgroundDrawable(originalImeWindowBackground);
            floatingImeWindow = false;
        }
    }

    @Override public boolean onEvaluateFullscreenMode() {
        // Keep the host editor visible when the phone rotates to landscape.
        return false;
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        keyInputOrder.cancelAll();
        long revision = getSharedPreferences("settings", MODE_PRIVATE).getLong("context_revision", 0);
        if (revision != contextRevision) {
            contextRevision = revision;
            loadContextModel();
        }
        stopClipboardListener();
        super.onStartInput(info, restarting);
        logImeLifecycle("start_input restarting=" + restarting);
        editorInfo = info;
        ordinaryWordAssembly.clear(); ordinaryWordOffer = null;
        wordCreation.clear(); wordEditorSession++; lastCustomWordChange = null; lastCustomWordNotice = "";
        nameMode = false; touchCorrection.clear(); pendingTouch = null;
        hostHasComposingText = false;
        lastEditorSelectionStart = info == null ? -1 : info.initialSelStart;
        lastEditorSelectionEnd = info == null ? -1 : info.initialSelEnd;
        composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        pathEditor.clear();
        composingCursor = 0;
        lockedPinyinPath = "";
        previousCommittedWord = "";
        previousPreviousCommittedWord = "";
        lastChineseCommitAt = 0;
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = null;
        int cls = info == null ? 0 : (info.inputType & InputType.TYPE_MASK_CLASS);
        editorLayoutKind = classifyEditorLayout(info);
        numericEditor = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;
        editorForcesKeyboardLayout = numericEditor || "email".equals(editorLayoutKind)
                || "uri".equals(editorLayoutKind);
        invalidateCandidates();
        secureEditor = isSensitiveEditor(info);
        boolean clipboardSearchEditor = clipboardSearchDialogOpen && info != null
                && getPackageName().equals(info.packageName);
        if (clipboardSearchEditor) return;
        if (clipboardSearchResumePending && clipboardSearchRestorePanel) {
            clipboardSearchResumePending = false;
            clipboardSearchRestorePanel = false;
            clipboardSearchReturnEditorInfo = null;
            clipboardShown = true;
            clipboardEnablePromptShown = false;
            return;
        }
        englishWordMode = getSharedPreferences(KEYBOARD_STATE_PREFERENCES, MODE_PRIVATE)
                .getBoolean(ENGLISH_WORD_MODE, true);
        mode = numericEditor ? Mode.SYMBOLS
                : "email".equals(editorLayoutKind) || "uri".equals(editorLayoutKind) ? Mode.ENGLISH
                : cls == InputType.TYPE_CLASS_TEXT ? restoreKeyboardMode() : Mode.ENGLISH;
        if (isOwnWordEditor(info) && "offlineime-word-code".equals(info.privateImeOptions)) {
            mode = Mode.ENGLISH; englishWordMode = false; editorForcesKeyboardLayout = true;
        }
        toolsShown = false;
        clipboardShown = false;
        clipboardEnablePromptShown = false;
        clipboardClearConfirmShown = false;
        handwritingShown = false;
        emojiShown = false;
        symbolsShown = false;
        layoutChooserShown = false;
        candidateExpanded = false;
        clipboardMultiSelect = false;
        clipboardSelection.clear();
        selectionMode = false;
        selectionAnchor = selectionExtent = -1;
        // 数字输入框临时使用符号布局；普通文本框恢复上次主动选择的键盘。
        modeBeforeSymbols = mode == Mode.SYMBOLS ? restoreLastChineseMode() : mode;
        modeBeforeEnglish = mode == Mode.ENGLISH ? restoreLastChineseMode() : mode;
        shift = false;
        capsLock = false;
        lastShiftTapUptime = 0;
        candidatePage = 0;
    }

    String editorLayoutKind() { return editorLayoutKind; }
    boolean englishWordMode() { return englishWordMode; }
    boolean capsLockEnabled() { return capsLock; }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        ImePerformanceMonitor.inputStarted();
        super.onStartInputView(info, restarting);
        editorInfo = info;
        updateInputViewLayout();
        if (!clipboardSearchDialogOpen) startClipboardListenerIfNeeded();
        if (keyboardView != null) {
            keyboardView.setDark(isDarkMode());
            // A hidden/detached view may still have a pending frame callback.
            // Render this session now, even if that callback never ran.
            cancelRenderRefresh();
            renderRefreshNow();
        }
        logImeLifecycle("start_input_view restarting=" + restarting);
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        cancelRenderRefresh();
        stopClipboardListener();
        super.onFinishInputView(finishingInput);
        logImeLifecycle("finish_input_view finishing=" + finishingInput);
    }

    @Override public void onFinishInput() {
        cancelRenderRefresh();
        keyInputOrder.cancelAll();
        stopClipboardListener();
        if (clipboardSearchDialogOpen || clipboardSearchResumePending) {
            super.onFinishInput();
            return;
        }
        wordCreation.clear(); wordEditorSession++;
        invalidateCandidates();
        pathEditor.clear();
        hidePanels();
        composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = "";
        previousCommittedWord = "";
        previousPreviousCommittedWord = "";
        lastChineseCommitAt = 0;
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = null;
        editorInfo = null;
        lastEditorSelectionStart = -1;
        lastEditorSelectionEnd = -1;
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        destroyed = true;
        cancelRenderRefresh();
        if (contextModel != null) contextModel.cancelDeferredLoad();
        keyInputOrder.cancelAll();
        cancelCandidateWork();
        if (settingsPreferences != null) settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        stopClipboardListener();
        if (clipboardUndoExpiry != null) mainHandler.removeCallbacks(clipboardUndoExpiry);
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        clipboardClearBackup = null;
        if (loaderTask != null) loaderTask.cancel(true);
        if (prewarmTask != null) prewarmTask.cancel(true);
        if (loaderExecutor != null) loaderExecutor.shutdownNow();
        if (candidateQueries != null) candidateQueries.close();
        if (dictionary != null) dictionary.close();
        if (userDataReceiverRegistered) {
            unregisterReceiver(userDataClearedReceiver);
            userDataReceiverRegistered = false;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (keyboardView != null) keyboardView.releaseAudio();
        logImeLifecycle("service_destroyed");
        super.onDestroy();
    }

    private void onDictionaryReady() {
        if (destroyed) return;
        // Keep the small bundled first frame visible until the full candidate
        // query replaces it. Old entries are disabled while that query runs.
        if (composing.length() > 0 && !candidateResults.isEmpty()
                && candidateResultKey.equals(currentCandidateKey())) {
            candidateCache.evictAll();
            segmentsCacheKey = "";
            cancelCandidateWork();
            candidatePendingKey = candidateCompleteKey = candidateSelectableKey = "";
        } else {
            invalidateCandidates();
        }
        if (keyboardView != null) refresh();
        scheduleDictionaryPrewarm();
    }

    private void scheduleDictionaryPrewarm() {
        if (destroyed || prewarmFinished || prewarmScheduled || dictionary == null
                || !dictionary.isLoaded() || loaderExecutor == null) return;
        prewarmScheduled = true;
        mainHandler.postDelayed(dictionaryPrewarmRunnable, 1200L);
    }

    private void startDictionaryPrewarmIfIdle() {
        prewarmScheduled = false;
        if (destroyed || prewarmFinished || loaderExecutor == null || loaderExecutor.isShutdown()) return;
        if (composing.length() > 0 || !candidatePendingKey.isEmpty() || keyInputOrder.hasPending()) {
            scheduleDictionaryPrewarm();
            return;
        }
        prewarmTask = loaderExecutor.submit(() -> {
            try {
                dictionary.prewarmCommonQueries();
                if (!Thread.currentThread().isInterrupted())
                    mainHandler.post(() -> prewarmFinished = true);
            } catch (android.os.OperationCanceledException ignored) {
                // A real key press always takes priority over speculative warming.
            }
        });
    }

    private void updateDictionaryPrewarmForInput() {
        if (composing.length() > 0) {
            if (prewarmScheduled) mainHandler.removeCallbacks(dictionaryPrewarmRunnable);
            prewarmScheduled = false;
            if (prewarmTask != null && !prewarmTask.isDone()) prewarmTask.cancel(true);
            prewarmTask = null;
        } else {
            scheduleDictionaryPrewarm();
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUserDataReceiver() {
        android.content.IntentFilter filter = new android.content.IntentFilter(UserDataEvents.LOCAL_DATA_CLEARED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(userDataClearedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // Before exported-state flags existed, a signature permission kept this receiver private.
            registerReceiver(userDataClearedReceiver, filter, UserDataEvents.INTERNAL_PERMISSION, null);
        }
        userDataReceiverRegistered = true;
    }

    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
                                             int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        if (wordCreation.active) { lastEditorSelectionStart = newSelStart; lastEditorSelectionEnd = newSelEnd; return; }
        if (!composing.toString().isEmpty()) {
            boolean selectionMoved = lastEditorSelectionStart >= 0
                    && (newSelStart != lastEditorSelectionStart || newSelEnd != lastEditorSelectionEnd);
            if (mode == Mode.NINE || mode == Mode.STROKE) {
                // 九键数字和笔画编码只留在本地组合区，宿主选择区必须保持不动。
                if (selectionMoved) clearComposing(false);
            } else {
                boolean caretAtCompositionEnd = candidatesStart >= 0
                        && candidatesEnd >= candidatesStart
                        && newSelStart == candidatesEnd && newSelEnd == candidatesEnd;
                if (!caretAtCompositionEnd) clearComposing(false);
            }
        }
        lastEditorSelectionStart = newSelStart;
        lastEditorSelectionEnd = newSelEnd;
    }

    @Override public void onTouchKey(String key, TouchCorrectionModel.Sample sample) {
        pendingTouch = sample;
        try { onKey(key); } finally { pendingTouch = null; }
    }

    @Override public void onKey(String key) {
        if (key == null || key.isEmpty()) return;
        if (!"⇧".equals(key)) lastShiftTapUptime = 0;
        if (handleWordCreationKey(key)) return;
        deferredCommitKey = ""; deferredCommitAction = "";
        if ("重输".equals(key) || "中英".equals(key) || "字".equals(key) || "9键".equals(key) || "ABC".equals(key) || "123".equals(key)) ordinaryWordAssembly.clear();
        if ("人名模式".equals(key)) {
            if (secureEditor || mode != Mode.PINYIN && mode != Mode.NINE) return;
            nameMode = !nameMode; hidePanels(); invalidateCandidates(); refresh();
            if (keyboardView != null) keyboardView.announceForAccessibility(nameMode ? "人名候选已开启" : "已恢复普通候选");
            android.widget.Toast.makeText(this, nameMode ? "人名候选已开启，本输入框有效" : "已恢复普通候选", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.startsWith("编辑音节:")) {
            String[] parts = key.substring("编辑音节:".length()).split(":", 2);
            if (parts.length != 2 || !parts[0].equals(composing.toString())) return;
            try {
                int start = Integer.parseInt(parts[1]);
                for (PinyinPathEditor.Segment segment : pinyinSegments()) if (segment.start == start) {
                    pathEditor.focus(start); composingCursor = segment.end;
                    refresh(); break;
                }
            } catch (NumberFormatException ignored) { }
            return;
        }
        if (key.startsWith("选择音节:")) {
            String[] parts = key.substring("选择音节:".length()).split(":", 4);
            if (parts.length != 4 || !parts[0].equals(composing.toString())) return;
            try {
                int start = Integer.parseInt(parts[1]), end = Integer.parseInt(parts[2]);
                if (start < 0 || end > composing.length() || start >= end) return;
                if (isShuangpin()) {
                    if (shuangpin.select(parts[0], start, end, parts[3])) {
                        advancePinyinSelection(start, end);
                        candidatePage = 0; updateComposing();
                    }
                    return;
                }
                String spelling = mode == Mode.NINE ? PinyinDictionary.toT9(parts[3]) : parts[3];
                if (!spelling.equals(composing.substring(start, end))) return;
                if (pathEditor.select(parts[0], mode == Mode.NINE,
                        start, end, parts[3])) {
                    advancePinyinSelection(start, end);
                    // Choosing a reading changes the lookup, not the insertion
                    // point. Keep appending at the end unless the user moved
                    // the cursor explicitly through the syllable editor.
                    lockedPinyinPath = "";
                    composingCursor = Math.max(0, Math.min(composing.length(), composingCursor));
                    if (canLearn()) {
                        android.content.SharedPreferences usage = getSharedPreferences("pinyin_path_usage", MODE_PRIVATE);
                        String usageKey = "syllable:" + parts[3];
                        usage.edit().putInt(usageKey, Math.min(1000, usage.getInt(usageKey, 0) + 1)).apply();
                    }
                    candidatePage = 0; updateComposing();
                }
            } catch (NumberFormatException ignored) { }
            return;
        }
        if ("删除当前音节".equals(key)) {
            PinyinPathEditor.Segment segment = focusedPinyinSegment();
            if (segment != null) {
                pathEditor.replace(composing.toString(), segment.start, segment.end, "", mode == Mode.NINE);
                touchCorrection.delete(composing.toString(), segment.start, segment.end);
                composing.delete(segment.start, segment.end); composingCursor = segment.start;
                lockedPinyinPath = ""; updateComposing();
            }
            return;
        }
        if (key.startsWith("定位拼音:")) {
            try { composingCursor = Math.max(0, Math.min(composing.length(), Integer.parseInt(key.substring(5)))); } catch (NumberFormatException ignored) { }
            refresh(); return;
        }
        if (key.startsWith("词偏好:")) {
            String[] parts = key.substring(4).split(":", 2);
            if (parts.length == 2 && dictionary != null && dictionary.isUserDataReady() && canSaveExplicitWord()) {
                String code = learningCode(); PinyinDictionary target = dictionary;
                CustomWordStore.IO.execute(() -> { target.reloadManagedWords(); target.adjustPreference(code, parts[1], parts[0]);
                    mainHandler.post(() -> { invalidateCandidates(); refresh(); }); });
            }
            return;
        }
        if ("删除音节".equals(key)) { deleteSyllable(); return; }
        if (mode == Mode.PINYIN && ("'".equals(key) || "分词".equals(key))) {
            if (composing.length() > 0) insertPinyin("'");
            return;
        }
        if ("中英".equals(key) || "123".equals(key) || "符".equals(key)
                || "26键".equals(key) || "ABC".equals(key) || "字".equals(key)
                || "9键".equals(key) || "笔画".equals(key)) hidePanels();
        if (key.startsWith("符号分类:")) {
            if (symbolsShown && keyboardView != null) keyboardView.showSymbols(key.substring(5));
            return;
        }
        if (key.startsWith("符号输入:")) { if (symbolsShown) commit(key.substring(5)); return; }
        if (key.startsWith("粘贴历史")) { pasteHistoryItem(key.substring("粘贴历史".length())); return; }
        if (key.startsWith("手写字:")) { commit(convertGeneratedText(key.substring(4))); return; }
        if (key.startsWith("表情分类:")) { if (keyboardView != null) keyboardView.showEmoji(key.substring(5)); return; }
        if (key.startsWith("表情样式:")) {
            if (emojiShown && keyboardView != null) keyboardView.setEmojiStyle(key.substring(5));
            return;
        }
        if (key.startsWith("收藏表情:")) {
            String emoji = key.substring(5);
            android.content.SharedPreferences p = getSharedPreferences("emoji", MODE_PRIVATE);
            p.edit().putBoolean("favorite_" + emoji, !p.getBoolean("favorite_" + emoji, false)).apply();
            if (keyboardView != null && emojiShown) keyboardView.showEmoji("收藏");
            return;
        }
        if (key.startsWith("表情:")) { String emoji = key.substring(3); commit(emoji); rememberEmoji(emoji); return; }
        if (key.startsWith("加入词:")) {
            if (!secureEditor && canLearn()
                    && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("learn_long_press", true)) {
                dictionary.addCustomWord(learningCode(), key.substring(3),
                        mode == Mode.ENGLISH ? "en" : "*");
                refresh();
            }
            return;
        }
        if (key.startsWith("选择路径:")) {
            String selected = key.substring(5);
            if (isShuangpin()) {
                if ("自动".equals(selected)) { shuangpin.clear(); candidatePage = 0; refresh(); }
                return;
            }
            if ("自动".equals(selected)) {
                pathEditor.automatic(); lockedPinyinPath = ""; composingCursor = composing.length();
            } else {
                pathEditor.automatic();
                List<List<String>> chosen = java.util.Collections.singletonList(
                        java.util.Arrays.asList(selected.split("[·']")));
                for (PinyinPathEditor.Segment segment : pathEditor.segments(
                        composing.toString(), mode == Mode.NINE, ignored -> chosen))
                    pathEditor.select(composing.toString(), mode == Mode.NINE,
                            segment.start, segment.end, segment.reading);
                pathEditor.focus(-1);
                lockedPinyinPath = "";
            }
            if (!"自动".equals(selected) && !secureEditor) {
                android.content.SharedPreferences usage = getSharedPreferences("pinyin_path_usage", MODE_PRIVATE);
                usage.edit().putInt(selected, Math.min(10000, usage.getInt(selected, 0) + 1))
                        .putLong("time_" + selected, System.currentTimeMillis()).apply();
            }
            if (dictionary != null) dictionary.clearPinyinPathCache();
            candidatePage = 0; refresh(); return;
        }
        if (key.startsWith("屏蔽词:")) { getSharedPreferences("blocked_words", MODE_PRIVATE).edit().putBoolean(key.substring(4), true).apply(); refresh(); return; }
        if (key.startsWith("长按数字:")) { clearComposing(true); commit(key.substring(5)); return; }
        if (key.startsWith("固定:")) { toggleClipboardPin(key.substring(3)); return; }
        if (key.startsWith("删除剪贴板:")) { removeClipboard(key.substring(6)); return; }
        if (key.startsWith("选择剪贴板:")) {
            String value = key.substring("选择剪贴板:".length());
            if (!clipboardSelection.add(value)) clipboardSelection.remove(value);
            clipboardMultiSelect = true;
            refresh();
            return;
        }
        if (key.startsWith("移动光标:")) {
            try { moveCaretBy(Integer.parseInt(key.substring("移动光标:".length()))); }
            catch (NumberFormatException ignored) { }
            return;
        }
        if (key.startsWith("剪贴板筛选:")) {
            clipboardFilter = key.substring("剪贴板筛选:".length());
            refresh();
            return;
        }
        switch (key) {
            case "分词":
                if (composing.length() > 0) onCandidateExpand();
                return;
            case "收起键盘":
                // Persist synchronously before Android may stop the IME process.
                // Phone/email/URI layouts are selected automatically and must
                // not replace the user's explicit keyboard choice.
                if (!editorForcesKeyboardLayout) rememberKeyboardMode(mode);
                requestHideSelf(0);
                return;
            case "设置":
                startActivity(new android.content.Intent(this, SettingsActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            case "⌫":
            case "删除": delete(); return;
            case "空格": space(); return;
            case "0\n空格": space(); return;
            case "回车": enter(); return;
            case "英文输入模式":
                if (mode != Mode.ENGLISH) return;
                // Preserve the letters already entered instead of replacing
                // them with a suggested word when switching to direct input.
                if (composing.length() > 0) {
                    commit(composing.toString());
                    clearComposing(false);
                }
                InputConnection englishConnection = getCurrentInputConnection();
                if (englishConnection != null) englishConnection.finishComposingText();
                hostHasComposingText = false;
                englishWordMode = !englishWordMode;
                getSharedPreferences(KEYBOARD_STATE_PREFERENCES, MODE_PRIVATE).edit()
                        .putBoolean(ENGLISH_WORD_MODE, englishWordMode).apply();
                hidePanels();
                candidatePage = 0;
                invalidateCandidates();
                refresh();
                return;
            case "中英":
                toolsShown = false;
                clipboardShown = false;
                if (mode == Mode.ENGLISH) {
                    mode = modeBeforeEnglish == Mode.ENGLISH ? Mode.PINYIN : modeBeforeEnglish;
                } else {
                    modeBeforeEnglish = mode;
                    mode = Mode.ENGLISH;
                }
                rememberKeyboardMode(mode);
                composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = "";
                refresh();
                return;
            case "123":
                if (mode == Mode.SYMBOLS) { refresh(); return; }
                modeBeforeSymbols = mode; mode = Mode.SYMBOLS; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; refresh(); return;
            case "符":
                modeBeforeSymbols = mode; mode = Mode.SYMBOLS; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; refresh(); return;
            case "符号":
                boolean openSymbols = !symbolsShown;
                if (openSymbols) clearComposing(true);
                hidePanels(); symbolsShown = openSymbols;
                if (keyboardView != null) { if (symbolsShown) keyboardView.showSymbols(); else refresh(); }
                return;
            case "返回符号": symbolsShown = false; refresh(); return;
            case "重输": composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; updateComposing(); return;
            case "26键": mode = Mode.PINYIN; rememberKeyboardMode(mode); composing.setLength(0); touchCorrection.clear(); shuangpin.clear(); lockedPinyinPath = ""; refresh(); return;
            case "笔画": mode = Mode.STROKE; rememberKeyboardMode(mode); composing.setLength(0); touchCorrection.clear(); shuangpin.clear(); lockedPinyinPath = ""; refresh(); return;
            case "标点": clearComposing(true); modeBeforeSymbols = mode; mode = Mode.SYMBOLS; refresh(); return;
            case "ABC": modeBeforeEnglish = mode == Mode.ENGLISH ? restoreLastChineseMode() : mode; mode = Mode.ENGLISH; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; refresh(); return;
            case "字": mode = Mode.PINYIN; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; refresh(); return;
            case "9键": mode = Mode.NINE; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; refresh(); return;
            case "键盘布局":
                boolean openLayouts = !layoutChooserShown; hidePanels(); layoutChooserShown = openLayouts;
                if (keyboardView != null) { if (layoutChooserShown) keyboardView.showLayoutChooser(mode); else refresh(); }
                return;
            case "手写":
                boolean openHandwriting = !handwritingShown; hidePanels(); handwritingShown = openHandwriting;
                if (keyboardView != null) { if (handwritingShown) keyboardView.showHandwriting(); else refresh(); }
                return;
            case "返回手写": handwritingShown = false; refresh(); return;
            case "撤销笔画": if (keyboardView != null) keyboardView.undoHandwriting(); return;
            case "清空手写": if (keyboardView != null) keyboardView.clearHandwriting(); return;
            case "😊":
                boolean openEmoji = !emojiShown; hidePanels(); emojiShown = openEmoji;
                if (keyboardView != null) { if (emojiShown) keyboardView.showEmoji(); else refresh(); }
                return;
            case "返回表情": emojiShown = false; refresh(); return;
            case "返回剪贴板":
                if (clipboardMultiSelect) {
                    clipboardMultiSelect = false;
                    clipboardSelection.clear();
                } else {
                    clipboardShown = false;
                    clipboardEnablePromptShown = false;
                }
                refresh();
                return;
            case "清空":
            case "清空剪贴板": clipboardClearConfirmShown = true; refresh(); return;
            case "确认清空剪贴板": clearClipboardWithUndo(); return;
            case "取消清空剪贴板": clipboardClearConfirmShown = false; refresh(); return;
            case "撤销清空剪贴板": undoClipboardClear(); return;
            case "搜索剪贴板": showClipboardSearchDialog(); return;
            case "启用剪贴板历史":
                getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("clipboard_enabled", true).apply();
                clipboardEnablePromptShown = false;
                showClipboard();
                return;
            case "暂不开启剪贴板":
                clipboardEnablePromptShown = false;
                clipboardShown = false;
                refresh();
                return;
            case "返回":
                if (candidateExpanded || layoutChooserShown || emojiShown || symbolsShown
                        || clipboardShown || handwritingShown || toolsShown) {
                    hidePanels(); refresh(); return;
                }
                if (mode == Mode.SYMBOLS) {
                    mode = modeBeforeSymbols;
                    rememberKeyboardMode(mode);
                    composing.setLength(0); touchCorrection.clear(); shuangpin.clear(); lockedPinyinPath = "";
                }
                refresh(); return;
            case "⇧":
                if (capsLock) {
                    capsLock = false;
                    shift = false;
                    lastShiftTapUptime = 0;
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (lastShiftTapUptime > 0 && now - lastShiftTapUptime
                            <= android.view.ViewConfiguration.getDoubleTapTimeout()) {
                        capsLock = true;
                        shift = true;
                        lastShiftTapUptime = 0;
                    } else {
                        shift = !shift;
                        lastShiftTapUptime = now;
                    }
                }
                refresh(); return;
            case "←": if (composing.length() > 0) { composingCursor = Math.max(0, Math.min(composing.length(), composingCursor) - 1); refresh(); } else if (selectionMode) moveCaretBy(-1); else sendKey(KeyEvent.KEYCODE_DPAD_LEFT); return;
            case "→": if (composing.length() > 0) { composingCursor = Math.min(composing.length(), composingCursor + 1); refresh(); } else if (selectionMode) moveCaretBy(1); else sendKey(KeyEvent.KEYCODE_DPAD_RIGHT); return;
            case "↑": clearComposing(true); sendKey(KeyEvent.KEYCODE_DPAD_UP); return;
            case "↓": clearComposing(true); sendKey(KeyEvent.KEYCODE_DPAD_DOWN); return;
            case "行首": clearComposing(true); sendKey(KeyEvent.KEYCODE_MOVE_HOME); return;
            case "行尾": clearComposing(true); sendKey(KeyEvent.KEYCODE_MOVE_END); return;
            case "选择": toggleSelectionMode(); return;
            case "复制": contextAction(android.R.id.copy, KeyEvent.KEYCODE_C); return;
            case "粘贴": contextAction(android.R.id.paste, KeyEvent.KEYCODE_V); return;
            case "剪切": contextAction(android.R.id.cut, KeyEvent.KEYCODE_X); return;
            case "全选": contextAction(android.R.id.selectAll, KeyEvent.KEYCODE_A); return;
            case "撤销": modifiedKey(KeyEvent.KEYCODE_Z); return;
            case "重做": modifiedKey(KeyEvent.KEYCODE_Y); return;
            case "剪贴板":
                if (clipboardShown) { clipboardShown = false; clipboardEnablePromptShown = false; refresh(); }
                else showClipboard();
                return;
            case "剪贴板多选": clipboardMultiSelect = true; clipboardSelection.clear(); refresh(); return;
            case "退出剪贴板多选": clipboardMultiSelect = false; clipboardSelection.clear(); refresh(); return;
            case "删除所选剪贴板":
                clipboardHistory.removeAll(clipboardSelection);
                clipboardSelection.clear();
                clipboardMultiSelect = false;
                refresh();
                return;
            case "⌫词": deleteWord(); return;
            case "工具":
                boolean openTools = !toolsShown;
                if (openTools) clearComposing(true);
                hidePanels(); toolsShown = openTools;
                if (keyboardView != null) { if (toolsShown) keyboardView.showTools(); else refresh(); }
                return;
        }
        if (mode == Mode.STROKE && key.length() == 1 && key.charAt(0) >= '1' && key.charAt(0) <= '5') {
            if (composing.length() >= 30) return;
            composing.append(key);
            composingCursor = composing.length();
            candidatePage = 0;
            updateComposing();
        } else if (mode == Mode.NINE && key.length() == 1 && key.charAt(0) == '0') {
            space();
        } else if (mode == Mode.NINE && key.length() == 1 && key.charAt(0) == '1') {
            clearComposing(true); commit("，");
        } else if (mode == Mode.NINE && key.length() == 1 && key.charAt(0) >= '2' && key.charAt(0) <= '9') {
            if (composing.length() >= 192) return;
            lockedPinyinPath = "";
            composingCursor = Math.max(0, Math.min(composing.length(), composingCursor));
            pathEditor.replace(composing.toString(), composingCursor, composingCursor, key, true);
            composing.insert(composingCursor, key); composingCursor += key.length();
            candidatePage = 0;
            updateComposing();
        } else if (mode == Mode.PINYIN && isLetter(key)) {
            insertPinyin(key);
        } else if (mode == Mode.ENGLISH && isLetter(key)) {
            String letter = shift ? key.toUpperCase(Locale.ROOT) : key.toLowerCase(Locale.ROOT);
            if (englishWordMode) {
                composing.append(letter);
                composingCursor = composing.length();
            } else {
                commit(letter);
            }
            if (!capsLock) shift = false;
            candidatePage = 0;
            if (englishWordMode) updateComposing(); else refresh();
        } else {
            clearComposing(true);
            commitPunctuationOrText(key);
        }
    }

    private void insertPinyin(String key) {
        String value = PinyinInput.normalize(key);
        if ("'".equals(key)) value = "'";
        if (composing.length() >= 192 || value.isEmpty()) return;
        composingCursor = Math.max(0, Math.min(composing.length(), composingCursor));
        if (composing.length() == 0) composingCursor = 0;
        if ("'".equals(value) && (composingCursor == 0 || composing.charAt(composingCursor - 1) == '\'')) return;
        touchCorrection.insert(composing.toString(), composingCursor, value, pendingTouch);
        pathEditor.replace(composing.toString(), composingCursor, composingCursor, value, false);
        composing.insert(composingCursor, value); composingCursor += value.length();
        lockedPinyinPath = ""; candidatePage = 0; updateComposing();
    }

    int pinyinCursor() { return Math.min(composing.length(), composingCursor); }
    String pinyinRawInput() { return composing.toString(); }

    private void deleteSyllable() {
        if (composing.length() == 0) return;
        int end = Math.min(composing.length(), composingCursor);
        if (end == 0) return;
        int start = -1;
        for (PinyinPathEditor.Segment segment : pinyinSegments())
            if (segment.start < end && segment.end >= end) { start = segment.start; break; }
        if (start < 0) start = composing.lastIndexOf("'", Math.max(0, end - 2));
        if (start < 0) {
            List<List<String>> paths = pinyinPaths(composing.substring(0, end));
            start = paths.isEmpty() ? end - 1 : end - paths.get(0).get(paths.get(0).size() - 1).length();
        }
        start = Math.max(0, start);
        pathEditor.replace(composing.toString(), start, end, "", mode == Mode.NINE);
        touchCorrection.delete(composing.toString(), start, end);
        composing.delete(start, end); composingCursor = start;
        lockedPinyinPath = ""; updateComposing();
    }

    @Override public void onCandidate(int index) {
        List<PinyinDictionary.Entry> list = candidates();
        if (index >= 0 && index < list.size()) onCandidateEntry(list.get(index));
    }

    @Override public void onCandidateEntry(PinyinDictionary.Entry selected) {
        if (mode == Mode.ENGLISH && !englishWordMode) return;
        if (wordCreation.saving) return;
        if (selected == null || getSharedPreferences("blocked_words", MODE_PRIVATE).getBoolean(selected.word, false)) return;
        if (!selected.inputVersion.isEmpty() && !selected.inputVersion.equals(currentCandidateKey())) return;
        if (mode == Mode.PINYIN && !selected.queryText.isEmpty() && !selected.queryText.equals(lockedPinyinPath)
                && !composing.toString().startsWith(selected.queryText)) return;
        if (wordCreation.active) { selectWordCreationCandidate(selected); return; }
        String code = learningCode();
        int consumed = (mode == Mode.PINYIN || mode == Mode.NINE) && lockedPinyinPath.isEmpty() && selected.consumedLength > 0
                ? Math.min(composing.length(), selected.consumedLength) : composing.length();
        trackOrdinaryWordSelection(selected, consumed);
        InputConnection batch = getCurrentInputConnection();
        if (batch != null) batch.beginBatchEdit();
        try {
        commit(selected.word);
        if (mode != Mode.STROKE && canLearn() && !code.isEmpty()) queueWordLearning(
                selected.pronunciation.isEmpty() ? code.substring(0, Math.min(code.length(), consumed)) : selected.pronunciation,
                selected.word, personalUsageKey(mode), selected.matchTier);
        if (selected.wordSegments.isEmpty()) rememberWord(selected.word);
        else for (String part : selected.wordSegments) rememberWord(part);
        while (consumed < composing.length() && composing.charAt(consumed) == '\'') consumed++;
        pathEditor.replace(composing.toString(), 0, consumed, "", mode == Mode.NINE);
        touchCorrection.delete(composing.toString(), 0, consumed);
        if (isShuangpin()) shuangpin.consumePrefix(composing.toString(), consumed);
        composing.delete(0, consumed);
        if (!isShuangpin()) shuangpin.clear();
        composingCursor = composing.length(); lockedPinyinPath = "";
        candidateExpanded = false; candidatePage = 0;
        updateComposing();
        } finally { if (batch != null) batch.endBatchEdit(); }
    }

    @Override public void onCandidatePage(int delta) {
        int size = Math.max(1, getIntPreference(
                getSharedPreferences("settings", Context.MODE_PRIVATE), "candidate_count", 6));
        List<PinyinDictionary.Entry> values = candidates();
        int frequent = 0;
        for (PinyinDictionary.Entry entry : values)
            if (isChoosingWordParts() || CandidateRanker.isPriority(entry)) frequent++;
        int lessFrequent = values.size() - frequent;
        int pages = Math.max(1, (frequent + size - 1) / size
                + (lessFrequent + size - 1) / size);
        candidatePage = Math.max(0, Math.min(pages - 1, candidatePage + delta));
        refresh();
    }

    @Override public void onCandidateExpand() {
        hidePanels();
        candidateExpanded = true;
        candidatePage = 0;
        if (keyboardView != null) keyboardView.showCandidatePage(
                mode, composing.toString(), candidates(), candidatePage);
    }

    @Override public void onMode(Mode requested) {
        ordinaryWordAssembly.clear();
        if (wordCreation.active) { switchWordCreationMode(requested); return; }
        hidePanels();
        if (requested == Mode.ENGLISH && mode != Mode.ENGLISH) modeBeforeEnglish = mode;
        mode = requested;
        nameMode = false; touchCorrection.clear();
        rememberKeyboardMode(mode);
        composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = "";
        refresh();
    }

    private void updateComposing() {
        updateDictionaryPrewarmForInput();
        InputConnection connection = getCurrentInputConnection();
        // InputConnection may consume this asynchronously. Never share the mutable buffer.
        String text = composing.toString();
        // T9 digits and five-stroke codes are internal lookup paths. Keep them out of the host
        // field; only the candidate row should expose the result.
        if (connection != null) {
            connection.beginBatchEdit();
            try {
                if (wordCreation.active || mode == Mode.NINE || mode == Mode.STROKE || text.isEmpty()) {
                    if (hostHasComposingText) connection.commitText("", 1);
                    connection.finishComposingText();
                    hostHasComposingText = false;
                } else {
                    connection.setComposingText(text, 1);
                    hostHasComposingText = true;
                }
            } finally {
                connection.endBatchEdit();
            }
        }
        refresh();
    }

    private List<PinyinDictionary.Entry> candidates() {
        if (destroyed || dictionary == null || secureEditor
                || mode == Mode.ENGLISH && !englishWordMode) return java.util.Collections.emptyList();
        final boolean creatingWord = isChoosingWordParts();
        final boolean expanded = candidateExpanded;
        final String creationStrokePrefix = wordCreation.strokePrefix;
        final Mode queryMode = mode;
        final String rawInput = composing.toString();
        final String key = currentCandidateKey();
        if (!key.equals(measuredCandidateKey)) {
            measuredCandidateKey = key; measuredCandidateAt = ImePerformanceMonitor.start(); measuredCandidateDrawn = false;
        }
        // Repaints and repeated readiness checks must not redo parsing or launch another generation.
        if (key.equals(candidatePendingKey)
                || key.equals(candidateResultKey) && key.equals(candidateCompleteKey)) return candidateResults;
        touchCorrection.sync(rawInput);
        if (isShuangpin()) shuangpin.sync(rawInput);
        final boolean useNames = nameMode;
        final List<ShuangpinInput.Path> doublePaths = isShuangpin()
                ? shuangpin.paths(rawInput) : null;
        final List<TouchCorrectionModel.Variant> touchVariants = queryMode == Mode.PINYIN && !useNames && doublePaths == null
                && settingsPreferences.getBoolean("touch_correction", true)
                && settingsPreferences.getBoolean("spelling_correction", true)
                ? touchCorrection.variants(rawInput) : java.util.Collections.emptyList();
        final PinyinPathEditor.Query constraint = pathQuery();
        final boolean locked = (queryMode == Mode.PINYIN || queryMode == Mode.NINE)
                && (constraint != null || !lockedPinyinPath.isEmpty());
        final boolean emptyQuery = composing.length() == 0;
        final String query = constraint != null ? constraint.key : locked
                ? lockedPinyinPath : composing.toString();
        final boolean nextWordPrediction = !creatingWord && emptyQuery && shouldShowNextWordPredictions(queryMode);
        final String lookupCode = learningCode();
        final String usageKey = personalUsageKey(queryMode);
        final String previous = previousCommittedWord;
        final String previousPrevious = previousPreviousCommittedWord;
        final boolean preferSingleCharacter = creatingWord || queryMode == Mode.NINE && !emptyQuery
                && (constraint != null
                    ? constraint.parts.size() == 1 && constraint.parts.get(0).locked
                    : locked ? lockedPinyinPath.matches("[a-z]+") : rawInput.matches("[2-9]"));
        if (key.equals(candidateResultKey)
                && (key.equals(candidatePendingKey) || key.equals(candidateCompleteKey))) {
            return candidateResults;
        }

        if (nextWordPrediction) {
            cancelCandidateWork();
            List<PinyinDictionary.Entry> predictions = contextModel == null
                    ? java.util.Collections.emptyList()
                    : contextModel.predict(previousPrevious, previous, 3);
            candidateResults = bindCandidateVersion(displayCandidates(queryMode,
                    filterBlocked(filterChinesePredictions(predictions)), query), key);
            candidateResultKey = candidateCompleteKey = candidateSelectableKey = key;
            candidatePendingKey = "";
            candidateDisplayMode = queryMode;
            candidateDisplayInput = rawInput;
            return candidateResults;
        }

        if (emptyQuery) {
            cancelCandidateWork();
            candidateResultKey = candidateCompleteKey = candidateSelectableKey = key;
            candidatePendingKey = "";
            candidateResults = java.util.Collections.emptyList();
            candidateDisplayMode = queryMode;
            candidateDisplayInput = rawInput;
            return candidateResults;
        }

        List<PinyinDictionary.Entry> cached = candidateCache.get(key);
        if (cached != null) {
            cancelCandidateWork();
            candidateResults = cached;
            candidateResultKey = candidateCompleteKey = candidateSelectableKey = key;
            candidatePendingKey = "";
            candidateDisplayMode = queryMode;
            candidateDisplayInput = rawInput;
            return cached;
        }

        // Keep one complete previous frame while the newest query is pending.
        // Its entries keep the old input version, so they cannot be selected.
        // This removes the empty frame that caused the candidate bar to flash.
        boolean keepPreviousFrame = candidateDisplayMode == queryMode
                && !candidateDisplayInput.isEmpty() && !candidateResults.isEmpty();
        if (!keepPreviousFrame) {
            candidateResults = java.util.Collections.emptyList();
            candidateResultKey = "";
            candidateDisplayMode = queryMode;
            candidateDisplayInput = rawInput;
        }
        candidateCompleteKey = candidateSelectableKey = "";
        if (!dictionary.isLoaded()) {
            if (candidateQueries != null && !creatingWord && doublePaths == null
                    && (queryMode == Mode.PINYIN || queryMode == Mode.NINE)
                    && constraint == null && !locked && !key.equals(candidatePendingKey)) {
                cancelCandidateWork();
                candidatePendingKey = key;
                CandidateQueryCoordinator.Ticket ticket = candidateQueries.next();
                long requestedAt = measuredCandidateAt;
                candidateQueries.submit(ticket, () -> {
                    QueryWork.begin(ticket.cancellation);
                    try {
                        List<PinyinDictionary.Entry> common = dictionary.suggestBootstrap(
                                rawInput, queryMode == Mode.NINE);
                        QueryWork.check();
                        if (!common.isEmpty()) {
                            List<PinyinDictionary.Entry> ready = bindCandidateVersion(
                                    displayCandidates(queryMode, filterBlocked(common), query), key);
                            if (!ready.isEmpty()) publishCandidateFrame(
                                    ticket, key, rawInput, queryMode, ready, false, requestedAt);
                        }
                    } catch (android.os.OperationCanceledException ignored) {
                        // A newer key or the fully loaded lexicon superseded this frame.
                    } finally { QueryWork.end(); }
                });
            }
            return candidateResults;
        }

        if (candidateQueries == null) {
            List<PinyinDictionary.Entry> full = creatingWord
                    ? dictionary.creationCandidates(rawInput, queryMode == Mode.NINE, constraint, doublePaths)
                    : lookupCandidates(queryMode, constraint, locked, query, false);
            if (creatingWord) full = filterCreationStrokes(full, creationStrokePrefix);
            List<PinyinDictionary.Entry> ranked = creatingWord
                    ? CandidateRanker.rankTop(filterBlocked(full), true, 512) : rankForContext(
                    filterForInputMode(filterBlocked(full), queryMode),
                    lookupCode, usageKey, previousPrevious, previous, preferSingleCharacter);
            candidateResults = bindCandidateVersion(displayCandidates(queryMode, ranked, query), key);
            candidateResultKey = candidateCompleteKey = candidateSelectableKey = key;
            candidatePendingKey = "";
            candidateDisplayMode = queryMode;
            candidateDisplayInput = rawInput;
            return candidateResults;
        }

        if (!key.equals(candidatePendingKey)) {
            cancelCandidateWork();
            candidatePendingKey = key;
            CandidateQueryCoordinator.Ticket ticket = candidateQueries.next();
            android.os.CancellationSignal cancellation = ticket.cancellation;
            long requestedAt = measuredCandidateAt;
            candidateQueries.submit(ticket, () -> {
                QueryWork.begin(cancellation);
                try {
                QueryWork.firstScreen(true);
                QueryWork.check();
                ImePerformanceMonitor.stage(this, "queue_wait", requestedAt, 0, queryMode == Mode.NINE);
                // A changed/deleted personal dictionary must be reloaded before any frame becomes selectable.
                if (dictionary.isUserDataReady()) {
                    long fastStarted = ImePerformanceMonitor.start();
                    List<PinyinDictionary.Entry> screen = creatingWord
                            ? dictionary.creationCandidates(rawInput, queryMode == Mode.NINE, constraint, doublePaths, true)
                            : doublePaths == null ? lookupCandidates(queryMode, constraint, locked, query, false)
                            : lookupShuangpin(doublePaths, rawInput, false);
                    if (creatingWord) screen = filterCreationStrokes(screen, creationStrokePrefix);
                    List<PinyinDictionary.Entry> ranked = creatingWord
                            ? filterBlocked(screen)
                            : rankForContext(filterForInputMode(filterBlocked(screen), queryMode),
                                    lookupCode, usageKey, previousPrevious, previous, preferSingleCharacter);
                    final List<PinyinDictionary.Entry> ready = bindCandidateVersion(displayCandidates(queryMode, ranked, query), key);
                    ImePerformanceMonitor.fastCandidates(this, fastStarted, ready.size(), queryMode == Mode.NINE);
                    if (!ready.isEmpty()) publishCandidateFrame(ticket, key, rawInput, queryMode, ready, false, requestedAt);
                }
                QueryWork.check();
                candidateQueries.submitSupplement(ticket, () -> {
                QueryWork.begin(cancellation);
                try {
                QueryWork.firstScreen(creatingWord && !expanded);
                dictionary.reloadManagedWords();
                QueryWork.check();
                long started = ImePerformanceMonitor.start();
                List<PinyinDictionary.Entry> full;
                full = creatingWord ? dictionary.creationCandidates(rawInput, queryMode == Mode.NINE, constraint, doublePaths)
                        : doublePaths == null ? lookupCandidates(queryMode, constraint, locked, query, false)
                        : lookupShuangpin(doublePaths, rawInput, useNames);
                if (!creatingWord && !locked && !touchVariants.isEmpty()) full = addTouchCandidates(full, touchVariants, rawInput);
                if (!creatingWord && useNames && doublePaths == null && (queryMode == Mode.PINYIN || queryMode == Mode.NINE))
                    full = NameCandidateEngine.suggest(full, constraint, rawInput, settingsPreferences);
                if (creatingWord) full = filterCreationStrokes(full, creationStrokePrefix);
                ImePerformanceMonitor.stage(this, "lookup", started, full.size(), queryMode == Mode.NINE);
                QueryWork.check();
                long rankingStarted = ImePerformanceMonitor.start();
                List<PinyinDictionary.Entry> ranked = creatingWord
                        ? filterBlocked(full) : rankForContext(
                        filterForInputMode(filterBlocked(full), queryMode),
                        lookupCode, usageKey, previousPrevious, previous, preferSingleCharacter);
                ImePerformanceMonitor.stage(this, "ranking", rankingStarted, ranked.size(), queryMode == Mode.NINE);
                QueryWork.check();
                final List<PinyinDictionary.Entry> filtered = bindCandidateVersion(
                        displayCandidates(queryMode, ranked, query), key);
                ImePerformanceMonitor.query(this, started, filtered.size(), queryMode == Mode.NINE);
                QueryWork.publishTimings(this, filtered.size(), queryMode == Mode.NINE);
                // Publish before optional path preparation: keys and selected readings already identify this frame.
                publishCandidateFrame(ticket, key, rawInput, queryMode, filtered, true, requestedAt);
                if (queryMode == Mode.NINE) {
                    dictionary.t9Paths(rawInput);
                    if (constraint != null) for (PinyinPathEditor.Part part : constraint.parts) {
                        QueryWork.check();
                        if (!part.locked) dictionary.t9Paths(part.text);
                    }
                }
                QueryWork.check();
                mainHandler.post(() -> {
                    if (destroyed || cancellation.isCanceled() || !candidateQueries.isCurrent(ticket)
                            || !key.equals(currentCandidateKey())) return;
                    segmentsCacheKey = ""; refresh();
                });
                } catch (android.os.OperationCanceledException ignored) {
                    // Superseded input intentionally abandons every remaining stage.
                } finally { QueryWork.end(); }
                });
                } catch (android.os.OperationCanceledException ignored) {
                    // The next key supersedes both the quick and supplementary work.
                } finally { QueryWork.end(); }
            });
        }
        return candidateResults;
    }

    private void publishCandidateFrame(CandidateQueryCoordinator.Ticket ticket, String key, String raw,
            Mode queryMode, List<PinyinDictionary.Entry> entries, boolean complete, long requestedAt) {
        mainHandler.post(() -> {
            if (destroyed || ticket.cancellation.isCanceled() || !candidateQueries.isCurrent(ticket)
                    || !key.equals(currentCandidateKey())) return;
            if (!complete && key.equals(candidateCompleteKey)) return;
            candidateResults = entries; candidateResultKey = candidateSelectableKey = key;
            candidateDisplayMode = queryMode; candidateDisplayInput = raw;
            if (complete) { candidateCompleteKey = key; candidatePendingKey = ""; candidateCache.put(key, entries); }
            refresh();
            ImePerformanceMonitor.stage(this, complete ? "candidate_ready" : "first_selectable", requestedAt, entries.size(), queryMode == Mode.NINE);
            if (key.equals(deferredCommitKey)) {
                String action = deferredCommitAction; deferredCommitKey = ""; deferredCommitAction = "";
                if ("space".equals(action)) space(); else if ("enter".equals(action)) enter();
            }
        });
    }
    void onCandidateFrameDrawn(List<PinyinDictionary.Entry> entries) {
        if (measuredCandidateDrawn || entries == null || entries.isEmpty() || measuredCandidateAt == 0) return;
        String version = entries.get(0).inputVersion;
        if (!version.equals(measuredCandidateKey) || !version.equals(candidateSelectableKey)) return;
        measuredCandidateDrawn = true;
        ImePerformanceMonitor.stage(this, "first_visible", measuredCandidateAt, entries.size(), mode == Mode.NINE);
    }

    private String currentCandidateKey() {
        touchCorrection.sync(composing.toString());
        PinyinPathEditor.Query constraint = pathQuery();
        String query = constraint != null ? constraint.key
                : (mode == Mode.PINYIN || mode == Mode.NINE) && !lockedPinyinPath.isEmpty()
                ? lockedPinyinPath : composing.toString();
        boolean nextWordPrediction = composing.length() == 0 && shouldShowNextWordPredictions(mode);
        return settingsRevision + ":" + mode.name() + ':' + composing + ':' + query + ':' + personalUsageKey(mode)
                + ':' + previousPreviousCommittedWord + ':' + previousCommittedWord + ':'
                + lastChineseCommitAt + ':' + nextWordPrediction + ":names=" + nameMode + ":touch=" + touchCorrection.signature()
                + ":double=" + (isShuangpin() ? shuangpin.signature(composing.toString()) : "")
                + ":englishWord=" + englishWordMode + ":draft=" + wordCreation.active + ":" + wordCreation.revision
                + ":wordParts=" + ordinaryWordAssembly.parts.size() + ":" + ordinaryWordAssembly.revision
                + ":expanded=" + (isChoosingWordParts() && candidateExpanded);
    }

    private List<PinyinDictionary.Entry> filterBlocked(List<PinyinDictionary.Entry> source) {
        java.util.ArrayList<PinyinDictionary.Entry> out = new java.util.ArrayList<>();
        android.content.SharedPreferences blocked = getSharedPreferences("blocked_words", MODE_PRIVATE);
        for (PinyinDictionary.Entry entry : source) if (!blocked.getBoolean(entry.word, false)) out.add(entry);
        return out;
    }

    private List<PinyinDictionary.Entry> filterChinesePredictions(List<PinyinDictionary.Entry> source) {
        java.util.ArrayList<PinyinDictionary.Entry> out = new java.util.ArrayList<>();
        if (source == null) return out;
        for (PinyinDictionary.Entry entry : source) if (containsHan(entry.word)) out.add(entry);
        return out;
    }

    private List<PinyinDictionary.Entry> filterForInputMode(
            List<PinyinDictionary.Entry> source, Mode queryMode) {
        if (queryMode == Mode.STROKE)
            return filterChinesePredictions(source);
        if (queryMode == Mode.NINE) {
            boolean mixed = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("mixed_english", true);
            ArrayList<PinyinDictionary.Entry> filtered = new ArrayList<>();
            if (source != null) for (PinyinDictionary.Entry entry : source)
                if (containsHan(entry.word) || mixed && dictionary.isMixedTerm(entry.word)) filtered.add(entry);
            return filtered;
        }
        if (queryMode == Mode.PINYIN && !getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("mixed_english", true)) return filterChinesePredictions(source);
        return source;
    }

    private List<PinyinDictionary.Entry> lookupCandidates(Mode queryMode,
            PinyinPathEditor.Query constraint, boolean locked, String query, boolean forCommit) {
        if (queryMode == Mode.STROKE)
            return StrokeInputDictionary.get(this).suggest(this, query, 256);
        List<PinyinDictionary.Entry> values;
        if (constraint != null) values = dictionary.suggestConstrained(constraint);
        else if (queryMode == Mode.NINE && !locked) values = dictionary.suggestT9(query);
        else if (queryMode == Mode.ENGLISH) values = dictionary.suggestEnglish(query);
        else values = forCommit ? dictionary.suggestForCommit(query, false) : dictionary.suggest(query);
        if (queryMode != Mode.PINYIN && queryMode != Mode.NINE
                || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("mixed_english", true)
                || constraint != null || locked) return values;
        ArrayList<PinyinDictionary.Entry> combined = new ArrayList<>(values);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (PinyinDictionary.Entry entry : values) seen.add(entry.word.toLowerCase(Locale.ROOT));
        for (PinyinDictionary.Entry term : dictionary.suggestMixedTerms(query, queryMode == Mode.NINE))
            if (seen.add(term.word.toLowerCase(Locale.ROOT))) combined.add(term);
        if (queryMode != Mode.PINYIN || !query.matches("[A-Za-z][A-Za-z'.-]*")) return combined;
        int added = 0;
        for (PinyinDictionary.Entry english : dictionary.suggestEnglish(query)) {
            if (seen.add(english.word.toLowerCase(Locale.ROOT))) {
                combined.add(english);
                if (++added >= 12) break;
            }
        }
        return combined;
    }

    private List<PinyinDictionary.Entry> lookupShuangpin(List<ShuangpinInput.Path> paths,
            String raw, boolean names) {
        java.util.Map<String, PinyinDictionary.Entry> best = new java.util.LinkedHashMap<>();
        for (ShuangpinInput.Path path : paths) {
            QueryWork.check();
            List<PinyinDictionary.Entry> entries = dictionary.suggestShuangpin(path.query);
            if (names) entries = NameCandidateEngine.suggest(entries, null, path.query, settingsPreferences, true);
            for (PinyinDictionary.Entry entry : entries) {
                if (!containsHan(entry.word)) continue;
                PinyinDictionary.Entry copy = entry.withInput(path.consumed(entry.consumedLength), entry.pronunciation);
                copy.queryText = raw;
                PinyinDictionary.Entry previous = best.get(copy.word);
                if (previous == null || copy.rankingTier < previous.rankingTier
                        || copy.rankingTier == previous.rankingTier && copy.score > previous.score) best.put(copy.word, copy);
            }
        }
        return new ArrayList<>(best.values());
    }

    private List<PinyinDictionary.Entry> addTouchCandidates(List<PinyinDictionary.Entry> source,
            List<TouchCorrectionModel.Variant> variants, String raw) {
        ArrayList<PinyinDictionary.Entry> combined = new ArrayList<>(source);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (PinyinDictionary.Entry entry : source) seen.add(entry.word);
        for (TouchCorrectionModel.Variant variant : variants) {
            QueryWork.check();
            int count = 0;
            for (PinyinDictionary.Entry entry : dictionary.suggest(variant.input)) {
                if (entry.matchTier > PinyinDictionary.TIER_MIXED || entry.consumedLength != raw.length()
                        || !containsHan(entry.word) || !seen.add(entry.word)) continue;
                PinyinDictionary.Entry corrected = new PinyinDictionary.Entry(entry.word,
                        entry.score - variant.penalty, PinyinDictionary.TIER_FUZZY, false, entry.corpusScore, 28)
                        .withInput(raw.length(), entry.pronunciation);
                corrected.queryText = raw; corrected.wordSegments = entry.wordSegments;
                combined.add(corrected);
                if (++count >= 8) break;
            }
        }
        return combined;
    }

    private List<PinyinDictionary.Entry> displayCandidates(
            Mode queryMode, List<PinyinDictionary.Entry> entries, String rawQuery) {
        List<PinyinDictionary.Entry> cased = queryMode == Mode.ENGLISH
                ? preserveEnglishCase(entries, rawQuery) : entries;
        boolean traditional = "traditional".equals(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("output_script", "simplified"));
        boolean mixedCase = queryMode == Mode.PINYIN && rawQuery != null
                && !rawQuery.isEmpty() && Character.isUpperCase(rawQuery.charAt(0));
        if (!traditional && !mixedCase) return cased;
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>(cased.size());
        for (PinyinDictionary.Entry entry : cased) {
            String word = entry.word;
            if (traditional && containsHan(word)) word = HanConverter.get(this).toTraditional(word);
            else if (mixedCase && word.matches("[A-Za-z][A-Za-z'.-]*"))
                word = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
            PinyinDictionary.Entry copy = new PinyinDictionary.Entry(word, entry.score,
                    entry.matchTier, entry.pinned, entry.corpusScore, entry.rankingTier)
                    .withInput(entry.consumedLength, entry.pronunciation);
            copy.queryText = entry.queryText;
            copy.wordSegments = entry.wordSegments;
            result.add(copy);
        }
        return result;
    }

    private List<PinyinDictionary.Entry> rankForContext(List<PinyinDictionary.Entry> source,
            String lookupCode, String usageKey, String previousPrevious, String previous,
            boolean preferSingleCharacter) {
        long contextStarted = ImePerformanceMonitor.start();
        java.util.Map<String, Integer> bonuses = contextModel == null || secureEditor
                ? java.util.Collections.emptyMap() : contextModel.rankingScores(previousPrevious, previous, source);
        ImePerformanceMonitor.stage(this, "context_ranking", contextStarted, source.size(), mode == Mode.NINE);
        long personalStarted = ImePerformanceMonitor.start();
        List<PinyinDictionary.Entry> scored = dictionary.personalize(lookupCode, usageKey, source, preferSingleCharacter, bonuses);
        ImePerformanceMonitor.stage(this, "personalization", personalStarted, scored.size(), mode == Mode.NINE);
        return CandidateRanker.rankTop(scored, preferSingleCharacter, QueryWork.firstScreen() ? 24 : 128);
    }

    private String learningCode() {
        if (isShuangpin()) {
            List<ShuangpinInput.Path> paths = shuangpin.paths(composing.toString());
            if (!paths.isEmpty()) return paths.get(0).query;
        }
        if (!lockedPinyinPath.isEmpty()) return lockedPinyinPath;
        return composing.toString();
    }

    private String personalUsageKey(Mode forMode) {
        if (forMode == Mode.ENGLISH) return "en";
        if (forMode == Mode.STROKE) return "stroke";
        PinyinPathEditor.Query constraint = pathQuery();
        if (constraint != null) return (forMode == Mode.NINE ? "9:" : "26:") + "syllables:" + constraint.key;
        if (forMode == Mode.NINE) {
            return lockedPinyinPath.isEmpty()
                    ? "9:digits:" + composing.toString()
                    : "9:path:" + lockedPinyinPath;
        }
        if (forMode == Mode.PINYIN) {
            if (isShuangpin()) return "26:xiaohe";
            return lockedPinyinPath.isEmpty()
                    ? "26:auto:" + composing.toString()
                    : "26:path:" + lockedPinyinPath;
        }
        return "*";
    }

    private List<PinyinDictionary.Entry> preserveEnglishCase(
            List<PinyinDictionary.Entry> entries, String rawQuery) {
        if (entries == null || entries.isEmpty() || rawQuery == null || rawQuery.isEmpty()) return entries;
        String letters = rawQuery.replaceAll("[^A-Za-z]", "");
        if (letters.isEmpty()) return entries;
        boolean allCaps = letters.length() > 1
                && letters.equals(letters.toUpperCase(Locale.ROOT));
        boolean titleCase = Character.isUpperCase(letters.charAt(0))
                && (letters.length() == 1
                || letters.substring(1).equals(letters.substring(1).toLowerCase(Locale.ROOT)));
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>(entries.size());
        for (PinyinDictionary.Entry entry : entries) {
            String word = entry.word;
            if (allCaps) word = word.toUpperCase(Locale.ROOT);
            else if (titleCase && !word.isEmpty()) {
                word = word.substring(0, 1).toUpperCase(Locale.ROOT)
                        + word.substring(1).toLowerCase(Locale.ROOT);
            } else word = word.toLowerCase(Locale.ROOT);
            result.add(new PinyinDictionary.Entry(word, entry.score, entry.matchTier,
                    entry.pinned, entry.corpusScore, entry.rankingTier));
        }
        return result;
    }

    private boolean shouldShowNextWordPredictions(Mode forMode) {
        return (forMode == Mode.PINYIN || forMode == Mode.NINE)
                && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("next_word_prediction", true)
                && contextModel != null && lastChineseCommitAt > 0
                && System.currentTimeMillis() - lastChineseCommitAt <= NEXT_WORD_PREDICTION_WINDOW_MS
                && containsHan(previousCommittedWord);
    }

    private boolean containsHan(String value) {
        if (value == null) return false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private void scheduleNextWordPredictionExpiry() {
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = () -> {
            nextWordPredictionExpiry = null;
            if (!destroyed && composing.length() == 0 && !shouldShowNextWordPredictions(mode)) {
                invalidateCandidates();
                refresh();
            }
        };
        mainHandler.postDelayed(nextWordPredictionExpiry, NEXT_WORD_PREDICTION_WINDOW_MS + 50L);
    }

    private void rememberWord(String word) {
        if (!canLearn() || word == null || word.isEmpty()) return;
        if (!previousCommittedWord.isEmpty() && contextModel != null)
            contextModel.learn(previousPreviousCommittedWord, previousCommittedWord, word);
        previousPreviousCommittedWord = previousCommittedWord;
        previousCommittedWord = word;
        if (containsHan(word)) {
            lastChineseCommitAt = System.currentTimeMillis();
            scheduleNextWordPredictionExpiry();
        } else {
            lastChineseCommitAt = 0;
            if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
            nextWordPredictionExpiry = null;
        }
    }

    private void delete() {
        if (wordCreation.active && composing.length() == 0) { undoWordCreationSelection(); return; }
        if (composing.length() > 0) {
            int cursor = mode == Mode.PINYIN || mode == Mode.NINE
                    ? Math.min(composing.length(), composingCursor) : composing.length();
            if (cursor <= 0) return;
            int start = GraphemeUtils.previousClusterStart(composing, cursor);
            pathEditor.replace(composing.toString(), start, cursor, "", mode == Mode.NINE);
            touchCorrection.delete(composing.toString(), start, cursor);
            composing.delete(start, cursor); composingCursor = start; lockedPinyinPath = "";
            if (composing.length() == 0) ordinaryWordAssembly.clear();
            updateComposing();
        } else {
            InputConnection connection = getCurrentInputConnection();
            if (connection == null) return;
            connection.beginBatchEdit();
            try {
                // A host can retain an underline after our local composition was cleared.
                // Surrounding-text deletion skips that region until composition is finished.
                connection.finishComposingText();
                hostHasComposingText = false;
                CharSequence selected = connection.getSelectedText(0);
                if (selected != null && selected.length() > 0) {
                    connection.commitText("", 1);
                    resetSelectionMode();
                    return;
                }
                if (!connection.deleteSurroundingTextInCodePoints(1, 0)) connection.deleteSurroundingText(1, 0);
            } finally {
                connection.endBatchEdit();
            }
        }
    }

    private void deleteWord() {
        if (composing.length() > 0) { composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        lockedPinyinPath = ""; updateComposing(); return; }
        InputConnection c = getCurrentInputConnection();
        if (c == null) return;
        c.beginBatchEdit();
        try {
            c.finishComposingText();
            hostHasComposingText = false;
            CharSequence selected = c.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                // Surrounding text excludes the selection. Replace it instead.
                c.commitText("", 1);
                resetSelectionMode();
                return;
            }
            CharSequence before = c.getTextBeforeCursor(64, 0);
            if (before == null || before.length() == 0) {
                if (!c.deleteSurroundingTextInCodePoints(1, 0)) c.deleteSurroundingText(1, 0);
                return;
            }
            int i = before.length();
            while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
            while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
            c.deleteSurroundingText(before.length() - i, 0);
        } finally {
            c.endBatchEdit();
        }
    }

    private void space() {
        if (wordCreation.active && composing.length() == 0) { showWordCreationMenu(); return; }
        if (composing.length() == 0) { commit(" "); return; }
        if (deferCandidateCommit("space")) return;
        List<PinyinDictionary.Entry> list = candidatesForCommit();
        if (list.isEmpty()) { commit(composing.toString()); composing.setLength(0); touchCorrection.clear(); shuangpin.clear(); composingCursor = 0; lockedPinyinPath = ""; refresh(); }
        else onCandidateEntry(list.get(0));
        if (!wordCreation.active && "space".equals(getSharedPreferences("settings", MODE_PRIVATE).getString("space_behavior", "select"))) {
            commit(" "); updateComposing();
        }
    }

    private void enter() {
        if (wordCreation.active) { if (composing.length() > 0) space(); else showWordCreationMenu(); return; }
        if (composing.length() > 0 && deferCandidateCommit("enter")) return;
        if (composing.length() > 0) {
            List<PinyinDictionary.Entry> list = candidatesForCommit();
            if (!list.isEmpty() && list.get(0).consumedLength > 0 && list.get(0).consumedLength < composing.length()) {
                onCandidateEntry(list.get(0)); enter(); return;
            }
        }
        clearComposing(true);
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        int action = editorInfo == null ? EditorInfo.IME_ACTION_NONE
                : (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION);
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action);
        } else {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private boolean deferCandidateCommit(String action) {
        if (candidateQueries == null || dictionary == null || !dictionary.isLoaded() || secureEditor) return false;
        String key = currentCandidateKey();
        if (key.equals(candidateSelectableKey) && key.equals(candidateResultKey)) return false;
        candidates();
        if (key.equals(candidateSelectableKey) && key.equals(candidateResultKey)) return false;
        deferredCommitKey = key; deferredCommitAction = action;
        return true;
    }

    String editorActionLabel() {
        int action = editorInfo == null ? EditorInfo.IME_ACTION_NONE
                : (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION);
        switch (action) {
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            case EditorInfo.IME_ACTION_DONE: return "Done";
            case EditorInfo.IME_ACTION_PREVIOUS: return "Previous";
            default: return "Enter";
        }
    }

    private void clearComposing(boolean commitBest) {
        ordinaryWordAssembly.clear();
        if (wordCreation.active && commitBest) { showWordCreationMenu(); return; }
        if (composing.length() == 0) return;
        if (commitBest) {
            List<PinyinDictionary.Entry> list = candidatesForCommit();
            String word = list.isEmpty() ? composing.toString() : list.get(0).word;
            if (!list.isEmpty() && mode == Mode.PINYIN && lockedPinyinPath.isEmpty()
                    && list.get(0).consumedLength > 0 && list.get(0).consumedLength < composing.length())
                word += composing.substring(list.get(0).consumedLength);
            commit(word);
            if (!list.isEmpty() && mode != Mode.STROKE && canLearn() && !learningCode().isEmpty()) {
                queueWordLearning(list.get(0).pronunciation.isEmpty() ? learningCode() : list.get(0).pronunciation,
                        word, personalUsageKey(mode), list.get(0).matchTier);
            }
            rememberWord(word);
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.finishComposingText();
        hostHasComposingText = false;
        composing.setLength(0); touchCorrection.clear(); shuangpin.clear();
        composingCursor = 0;
        lockedPinyinPath = "";
        candidateExpanded = false;
        refresh();
    }

    private void commit(CharSequence text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null && text != null && text.length() > 0) {
            connection.commitText(text.toString(), 1);
            hostHasComposingText = false;
        }
    }

    private void commitPunctuationOrText(String key) {
        String value = key;
        if (mode == Mode.PINYIN) {
            if ("，".equals(key)) value = "，";
            else if ("。".equals(key)) value = "。";
            else if ("?".equals(key)) value = "？";
            else if ("!".equals(key)) value = "！";
        }
        if (mode == Mode.ENGLISH) commit(value);
        else if ("(".equals(value)) { commit("（"); commit("）"); sendKey(KeyEvent.KEYCODE_DPAD_LEFT); }
        else if ("[".equals(value)) { commit("【"); commit("】"); sendKey(KeyEvent.KEYCODE_DPAD_LEFT); }
        else if ("{".equals(value)) { commit("｛"); commit("｝"); sendKey(KeyEvent.KEYCODE_DPAD_LEFT); }
        else if ("\"".equals(value)) { commit("“"); commit("”"); sendKey(KeyEvent.KEYCODE_DPAD_LEFT); }
        else commit(value);
        if (mode == Mode.ENGLISH && !capsLock && (".".equals(key) || "!".equals(key) || "?".equals(key))) {
            shift = true;
            refresh();
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text != null) commit(text);
            }
        }
    }

    private void showClipboard() {
        if (secureEditor) {
            android.widget.Toast.makeText(this, "密码输入中不可使用剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.SharedPreferences settings = getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (settings.getBoolean("privacy_mode", false)) {
            android.widget.Toast.makeText(this, "请先关闭隐私模式再使用剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!settings.getBoolean("clipboard_enabled", false)) {
            hidePanels();
            clipboardShown = true;
            clipboardEnablePromptShown = true;
            if (keyboardView != null) keyboardView.showClipboardEnablePrompt();
            return;
        }
        hidePanels();
        clipboardShown = true;
        clipboardEnablePromptShown = false;
        startClipboardListenerIfNeeded();
        captureClipboardOnDemand();
        refresh();
    }

    private List<PinyinDictionary.Entry> candidatesForCommit() {
        if (isChoosingWordParts()) return candidates();
        if (dictionary == null || secureEditor
                || mode == Mode.ENGLISH && !englishWordMode) return java.util.Collections.emptyList();
        String key = currentCandidateKey();
        if (key.equals(candidateSelectableKey) && key.equals(candidateResultKey)) {
            return candidateResults;
        }
        PinyinPathEditor.Query constraint = pathQuery();
        boolean locked = (mode == Mode.PINYIN || mode == Mode.NINE)
                && (constraint != null || !lockedPinyinPath.isEmpty());
        String query = locked ? lockedPinyinPath : composing.toString();
        List<PinyinDictionary.Entry> fresh = mode == Mode.NINE && !locked
                ? dictionary.suggestForCommit(query, true)
                : lookupCandidates(mode, constraint, locked, query, true);
        List<PinyinDictionary.Entry> ranked = rankForContext(
                filterForInputMode(filterBlocked(fresh), mode), learningCode(),
                personalUsageKey(mode), previousPreviousCommittedWord, previousCommittedWord,
                mode == Mode.NINE && !locked && composing.length() <= 3);
        return displayCandidates(mode, ranked, query);
    }

    private void captureClipboardOnDemand() {
        captureCurrentClipboard(true);
    }

    private void captureClipboardWhileActive() {
        if (!clipboardListenerRegistered || !canUseClipboard()) return;
        captureCurrentClipboard(false);
    }

    private void captureCurrentClipboard(boolean showSensitiveNotice) {
        try {
            ClipboardManager cm = getClipboardManager();
            if (cm == null || !cm.hasPrimaryClip() || secureEditor || !canUseClipboard()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            if (value == null || value.length() == 0) return;
            String text = value.toString();
            if (isSensitiveClipboard(clip, text)) {
                if (showSensitiveNotice) android.widget.Toast.makeText(this,
                        "检测到敏感剪贴板内容，已跳过保存", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            clipboardHistory.add(text);
            if (clipboardShown && !clipboardEnablePromptShown) refresh();
        } catch (RuntimeException error) {
            android.util.Log.w("OfflineImeClipboard", "clipboard read failed: " + error.getClass().getSimpleName());
            if (showSensitiveNotice) android.widget.Toast.makeText(this, "暂时无法读取系统剪贴板", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private ClipboardManager getClipboardManager() {
        if (clipboardManager == null) clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        return clipboardManager;
    }

    private void startClipboardListenerIfNeeded() {
        if (!isInputViewShown() || clipboardSearchDialogOpen || clipboardSearchResumePending) return;
        android.content.SharedPreferences settings = getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean enabled = settings.getBoolean("clipboard_enabled", false)
                && settings.getBoolean("clipboard_capture_while_typing", false)
                && !settings.getBoolean("privacy_mode", false) && !secureEditor;
        if (!enabled || clipboardListenerRegistered) return;
        ClipboardManager manager = getClipboardManager();
        if (manager == null) return;
        try {
            manager.addPrimaryClipChangedListener(clipboardChangedListener);
            clipboardListenerRegistered = true;
        } catch (RuntimeException error) {
            android.util.Log.w("OfflineImeClipboard", "clipboard listener unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void stopClipboardListener() {
        if (!clipboardListenerRegistered) return;
        try {
            ClipboardManager manager = getClipboardManager();
            if (manager != null) manager.removePrimaryClipChangedListener(clipboardChangedListener);
        } catch (RuntimeException error) {
            android.util.Log.w("OfflineImeClipboard", "clipboard listener removal failed: " + error.getClass().getSimpleName());
        }
        clipboardListenerRegistered = false;
    }

    private boolean isSensitiveClipboard(ClipData clip, String text) {
        ClipDescription description = clip.getDescription();
        if (description != null) {
            try {
                android.os.PersistableBundle extras = description.getExtras();
                if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return true;
            } catch (RuntimeException ignored) { }
            CharSequence label = description.getLabel();
            if (label != null) {
                String normalizedLabel = label.toString().toLowerCase(Locale.ROOT);
                if (normalizedLabel.contains("password") || normalizedLabel.contains("passcode")
                        || normalizedLabel.contains("otp") || normalizedLabel.contains("密码")
                        || normalizedLabel.contains("验证码") || normalizedLabel.contains("一次性")) return true;
            }
        }
        return getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("sensitive_filter", true)
                && LABELED_ONE_TIME_CODE.matcher(text).find();
    }

    boolean hasClipboardHistory() {
        return canUseClipboard() && !clipboardHistory.listEntries().isEmpty();
    }

    private List<ClipboardHistoryStore.Entry> readClipboardHistory() {
        List<ClipboardHistoryStore.Entry> entries = clipboardHistory.listEntries();
        ArrayList<ClipboardHistoryStore.Entry> filtered = new ArrayList<>();
        String query = clipboardSearchQuery.trim().toLowerCase(Locale.ROOT);
        boolean filterCodes = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("sensitive_filter", true);
        for (ClipboardHistoryStore.Entry entry : entries) {
            if (filterCodes && LABELED_ONE_TIME_CODE.matcher(entry.text).find()) {
                clipboardHistory.remove(entry.text);
                continue;
            }
            if ("pinned".equals(clipboardFilter) && !entry.pinned) continue;
            if ("recent".equals(clipboardFilter) && entry.pinned) continue;
            if (!query.isEmpty() && !entry.text.toLowerCase(Locale.ROOT).contains(query)) continue;
            filtered.add(entry);
        }
        return filtered;
    }

    private void showClipboardSearchDialog() {
        clipboardSearchDialogOpen = true;
        clipboardSearchRestorePanel = clipboardShown;
        clipboardSearchResumePending = false;
        clipboardSearchReturnEditorInfo = editorInfo;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setHint("输入要查找的文字");
        input.setText(clipboardSearchQuery);
        input.setSelection(input.length());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("搜索剪贴板")
                .setView(input)
                .setPositiveButton("搜索", (d, which) -> {
                    clipboardSearchQuery = input.getText().toString().trim();
                    clipboardFilter = "all";
                    if (keyboardView != null) keyboardView.refresh(mode, shift, composing.toString(), candidates());
                })
                .setNeutralButton("清除搜索", (d, which) -> {
                    clipboardSearchQuery = "";
                    clipboardFilter = "all";
                    if (keyboardView != null) keyboardView.refresh(mode, shift, composing.toString(), candidates());
                })
                .setNegativeButton("取消", null)
                .create();
        try {
            android.view.Window window = dialog.getWindow();
            android.view.Window parent = getWindow() == null ? null : getWindow().getWindow();
            if (window != null && parent != null) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
                WindowManager.LayoutParams params = window.getAttributes();
                params.token = parent.getDecorView().getWindowToken();
                if (params.token == null) params.token = parent.getAttributes().token;
                window.setAttributes(params);
            }
        } catch (RuntimeException error) {
            android.util.Log.w("OfflineImeClipboard", "search dialog attachment failed: " + error.getClass().getSimpleName());
        }
        dialog.setOnDismissListener(ignored -> {
            clipboardSearchDialogOpen = false;
            if (!clipboardSearchRestorePanel) {
                clipboardSearchReturnEditorInfo = null;
                return;
            }
            clipboardShown = true;
            clipboardEnablePromptShown = false;
            clipboardSearchResumePending = true;
            mainHandler.postDelayed(() -> {
                if (!clipboardSearchResumePending) return;
                clipboardSearchResumePending = false;
                clipboardSearchRestorePanel = false;
                EditorInfo restoreInfo = clipboardSearchReturnEditorInfo;
                clipboardSearchReturnEditorInfo = null;
                if (restoreInfo != null && (editorInfo == null || getPackageName().equals(editorInfo.packageName))) {
                    editorInfo = restoreInfo;
                    secureEditor = isSensitiveEditor(restoreInfo);
                    int cls = restoreInfo.inputType & InputType.TYPE_MASK_CLASS;
                    numericEditor = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                            || cls == InputType.TYPE_CLASS_DATETIME;
                    editorLayoutKind = classifyEditorLayout(restoreInfo);
                    editorForcesKeyboardLayout = numericEditor || "email".equals(editorLayoutKind)
                            || "uri".equals(editorLayoutKind);
                }
                startClipboardListenerIfNeeded();
                refresh();
            }, 250L);
        });
        try {
            dialog.show();
        } catch (RuntimeException error) {
            clipboardSearchDialogOpen = false;
            clipboardSearchRestorePanel = false;
            clipboardSearchReturnEditorInfo = null;
            android.util.Log.w("OfflineImeClipboard", "search dialog could not be shown: " + error.getClass().getSimpleName());
            refresh();
            return;
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        }
    }

    private void clearClipboardWithUndo() {
        clipboardClearBackup = clipboardHistory.snapshot();
        clipboardHistory.clear();
        clipboardClearConfirmShown = false;
        clipboardSearchQuery = "";
        clipboardFilter = "all";
        if (clipboardUndoExpiry != null) mainHandler.removeCallbacks(clipboardUndoExpiry);
        clipboardUndoExpiry = () -> {
            clipboardClearBackup = null;
            clipboardUndoExpiry = null;
            if (clipboardShown) refresh();
        };
        mainHandler.postDelayed(clipboardUndoExpiry, 10_000L);
        android.widget.Toast.makeText(this, "已清空剪贴板，可在键盘上撤销", android.widget.Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void undoClipboardClear() {
        if (clipboardClearBackup == null) return;
        clipboardHistory.restore(clipboardClearBackup);
        clipboardClearBackup = null;
        if (clipboardUndoExpiry != null) mainHandler.removeCallbacks(clipboardUndoExpiry);
        clipboardUndoExpiry = null;
        clipboardShown = true;
        clipboardEnablePromptShown = false;
        refresh();
    }

    private void toggleClipboardPin(String value) {
        clipboardHistory.togglePinned(value);
        refresh();
    }

    private void removeClipboard(String value) {
        clipboardHistory.remove(value);
        refresh();
    }

    private void pasteHistoryItem(String value) { if (value != null) { commit(value); clipboardShown = false; refresh(); } }


    private void toastWord(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); }

    private boolean canSaveExplicitWord() {
        return !secureEditor && !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false);
    }

    private List<PinyinDictionary.Entry> filterCreationStrokes(List<PinyinDictionary.Entry> source, String strokes) {
        if (strokes.isEmpty()) return source;
        java.util.Set<String> letters = new java.util.HashSet<>(StrokeInputDictionary.get(this).suggestCharacters(this, strokes, 10000));
        List<PinyinDictionary.Entry> filtered = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry.codePointLength == 1 && letters.contains(entry.word)) filtered.add(entry);
        }
        return filtered;
    }

    private void showCreationStrokeMenu() {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(this, "按起笔笔画筛选单字", KeyboardTheme.from(this, isDarkMode()));
        String[] strokes = {"横（一）", "竖（丨）", "撇（丿）", "点／捺（丶）", "折（乙）"};
        for (int index = 0; index < strokes.length; index++) {
            String code = String.valueOf(index + 1);
            menu.item(strokes[index], null, null, false, false, () -> {
                if (wordCreation.strokePrefix.length() < 16) wordCreation.strokePrefix += code;
                wordCreation.revision++; invalidateCandidates(); refresh(); showCreationStrokeMenu();
            });
        }
        menu.item("撤销一笔", null, null, false, false, () -> {
            if (!wordCreation.strokePrefix.isEmpty()) wordCreation.strokePrefix = wordCreation.strokePrefix.substring(0, wordCreation.strokePrefix.length() - 1);
            wordCreation.revision++; invalidateCandidates(); refresh(); showCreationStrokeMenu();
        });
        menu.item("清除筛选", null, null, false, false, () -> {
            wordCreation.strokePrefix = ""; wordCreation.revision++; invalidateCandidates(); refresh();
        });
        menu.item("查看筛选后的候选", "已输入笔画：" + wordCreation.strokePrefix, null, false, false, () -> onCandidateExpand());
        keyboardView.showWordMenu(menu);
    }

    boolean isChoosingWordParts() {
        return (mode == Mode.PINYIN || mode == Mode.NINE)
                && (wordCreation.active || !ordinaryWordAssembly.parts.isEmpty());
    }
    String wordCreationStatus() {
        return wordCreation.active ? wordCreation.label(composing.toString())
                : composing.length() == 0 ? lastCustomWordNotice : "";
    }

    private void trackOrdinaryWordSelection(PinyinDictionary.Entry selected, int consumed) {
        if ((mode != Mode.PINYIN && mode != Mode.NINE) || !canSaveExplicitWord()) { ordinaryWordAssembly.clear(); return; }
        if (consumed < composing.length() || !ordinaryWordAssembly.parts.isEmpty()) {
            if (!ordinaryWordAssembly.add(selected.word, selected.pronunciation, "")) { ordinaryWordAssembly.clear(); return; }
            if (consumed == composing.length() && ordinaryWordAssembly.parts.size() > 1) {
                try {
                    ordinaryWordOffer = CustomWordStore.validate(ordinaryWordAssembly.word(), ordinaryWordAssembly.reading(), "", "普通词", false, 0);
                    lastCustomWordNotice = "记住组合：" + ordinaryWordOffer.text + " · 点击保存";
                    if (customWordNoticeExpiry != null) mainHandler.removeCallbacks(customWordNoticeExpiry);
                    customWordNoticeExpiry = () -> { ordinaryWordOffer = null; lastCustomWordNotice = ""; refresh(); };
                    mainHandler.postDelayed(customWordNoticeExpiry, 10000);
                } catch (IllegalArgumentException ignored) { ordinaryWordOffer = null; }
                ordinaryWordAssembly.clear();
            }
        }
    }

    private void queueWordLearning(String code, String word, String usage, int tier) {
        PinyinDictionary target = dictionary;
        long epoch = userDataEpoch;
        CustomWordStore.IO.execute(() -> {
            if (destroyed || epoch != userDataEpoch || getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false)) return;
            target.reloadManagedWords(); target.learnSelection(code, word, usage, null, tier);
            mainHandler.post(() -> { if (!destroyed) { invalidateCandidates(); refresh(); } });
        });
    }

    private boolean handleWordCreationKey(String key) {
        if ("造词".equals(key)) {
            if (!canSaveExplicitWord()) { toastWord("当前输入框或隐私模式不保存词语"); return true; }
            if (wordCreation.active) { showWordCreationMenu(); return true; }
            if (mode != Mode.PINYIN && mode != Mode.NINE) {
                clearComposing(true); mode = restoreLastChineseMode(); if (mode == Mode.STROKE) mode = Mode.PINYIN;
                pathEditor.clear(); shuangpin.clear(); composingCursor = 0;
            }
            hidePanels(); wordCreation.start(); wordCreationCategory = "普通词"; nameMode = false;
            InputConnection connection = getCurrentInputConnection();
            if (connection != null && hostHasComposingText) { connection.setComposingText("", 1); connection.finishComposingText(); }
            hostHasComposingText = false; invalidateCandidates(); updateComposing(); return true;
        }
        if ("造词菜单".equals(key)) { showWordCreationMenu(); return true; }
        if (!wordCreation.active) return false;
        if (!canSaveExplicitWord()) { wordCreation.clear(); clearComposing(false); toastWord("造词已结束，当前模式不保存词语"); return true; }
        if (wordCreation.saving) { toastWord("正在保存词语"); return true; }
        if ("字".equals(key) || "26键".equals(key) || "9键".equals(key)) {
            switchWordCreationMode("9键".equals(key) ? Mode.NINE : Mode.PINYIN); return true;
        }
        if (key.startsWith("手写字:")) { addHandwrittenCreationWord(key.substring(4)); return true; }
        if ("重输".equals(key)) { composing.setLength(0); pathEditor.clear(); shuangpin.clear(); touchCorrection.clear(); updateComposing(); return true; }
        if ("设置".equals(key) || "工具".equals(key) || "剪贴板".equals(key) || "😊".equals(key)
                || "ABC".equals(key) || "中英".equals(key) || "123".equals(key) || "符".equals(key)
                || "符号".equals(key) || "笔画".equals(key) || key.startsWith("长按数字:")
                || key.startsWith("直接输入:") || "标点".equals(key)) {
            toastWord("请先完成或退出造词"); return true;
        }
        // Punctuation must not leak an unfinished draft into the host field.
        if (key.length() == 1 && !key.matches("[a-zA-Z2-9']")) { toastWord("造词时请输入拼音；点击草稿可完成或退出"); return true; }
        return false;
    }

    private void selectWordCreationCandidate(PinyinDictionary.Entry selected) {
        if (selected.pronunciation.isEmpty()) { toastWord("这个候选没有完整读音，请逐字选择"); return; }
        int consumed = Math.min(composing.length(), selected.consumedLength > 0 ? selected.consumedLength : composing.length());
        String raw = composing.substring(0, consumed);
        boolean editing = wordCreation.editing >= 0;
        if (editing && consumed < composing.length()) { toastWord("请完整选择正在修改的片段"); return; }
        if (!wordCreation.add(selected.word, selected.pronunciation, raw)) { toastWord("读音未完整，请先选择完整音节"); return; }
        while (consumed < composing.length() && composing.charAt(consumed) == '\'') consumed++;
        pathEditor.replace(composing.toString(), 0, consumed, "", mode == Mode.NINE);
        if (isShuangpin()) shuangpin.consumePrefix(composing.toString(), consumed);
        touchCorrection.delete(composing.toString(), 0, consumed); composing.delete(0, consumed);
        if (editing) { composing.setLength(0); composing.append(wordCreation.remaining); wordCreation.remaining = ""; pathEditor.clear(); shuangpin.clear(); restoreRemainingCreationLocks(); }
        if (!editing && !isShuangpin()) shuangpin.clear(); composingCursor = composing.length(); lockedPinyinPath = "";
        candidateExpanded = false; candidatePage = 0; invalidateCandidates(); updateComposing();
    }

    private String encodeCreationReading(String reading, Mode target) {
        List<String> values = new ArrayList<>();
        for (String token : reading.split("[ ']+")) {
            String encoded = target == Mode.NINE ? PinyinDictionary.toT9(token) : isShuangpin() ? ShuangpinInput.encode(token) : token;
            if (encoded.isEmpty()) return ""; values.add(encoded);
        }
        return String.join(target == Mode.NINE || isShuangpin() ? "" : "'", values);
    }

    private void restoreCreationLocks(String reading) {
        if (isShuangpin()) {
            int at = 0; for (String token : reading.split("[ ']+")) { shuangpin.select(composing.toString(), at, at + 2, token); at += 2; }
        } else {
            int at = 0; for (String token : reading.split("[ ']+")) {
                while (at < composing.length() && composing.charAt(at) == '\'') at++;
                int end = at + (mode == Mode.NINE ? PinyinDictionary.toT9(token).length() : token.length());
                pathEditor.select(composing.toString(), mode == Mode.NINE, at, end, token); at = end;
            }
        }
    }

    private void restoreRemainingCreationLocks() {
        for (PinyinPathEditor.Segment segment : wordCreation.remainingLocks) {
            if (isShuangpin()) shuangpin.select(composing.toString(), segment.start, segment.end, segment.reading);
            else pathEditor.select(composing.toString(), mode == Mode.NINE, segment.start, segment.end, segment.reading);
        }
        wordCreation.remainingLocks.clear();
    }

    private void editCreationPart(int index, boolean preserveReading) {
        if (index < 0 || index >= wordCreation.parts.size() || wordCreation.saving) return;
        if (wordCreation.editing < 0) {
            wordCreation.remaining = composing.toString(); wordCreation.remainingLocks.clear();
            for (PinyinPathEditor.Segment segment : pinyinSegments()) if (segment.locked) wordCreation.remainingLocks.add(segment);
        }
        wordCreation.strokePrefix = "";
        wordCreation.editing = index; wordCreation.revision++;
        WordCreationSession.Part part = wordCreation.parts.get(index);
        composing.setLength(0); composing.append(encodeCreationReading(part.reading, mode));
        pathEditor.clear(); shuangpin.clear(); touchCorrection.clear(); lockedPinyinPath = "";
        if (preserveReading) restoreCreationLocks(part.reading);
        composingCursor = composing.length(); hidePanels(); invalidateCandidates(); updateComposing();
    }

    private void undoWordCreationSelection() {
        if (wordCreation.editing >= 0) {
            wordCreation.editing = -1; composing.setLength(0); composing.append(wordCreation.remaining); wordCreation.remaining = ""; pathEditor.clear(); shuangpin.clear(); restoreRemainingCreationLocks();
        } else if (!wordCreation.parts.isEmpty()) {
            WordCreationSession.Part part = wordCreation.parts.remove(wordCreation.parts.size() - 1);
            String encoded = encodeCreationReading(part.reading, mode);
            List<PinyinPathEditor.Segment> tailLocks = new ArrayList<>(pinyinSegments());
            String remaining = composing.toString(); composing.setLength(0); composing.append(encoded);
            if (!remaining.isEmpty()) { if (mode == Mode.PINYIN && !isShuangpin()) composing.append('\''); composing.append(remaining); }
            pathEditor.clear(); shuangpin.clear(); restoreCreationLocks(part.reading);
            int offset = encoded.length() + (!remaining.isEmpty() && mode == Mode.PINYIN && !isShuangpin() ? 1 : 0);
            for (PinyinPathEditor.Segment segment : tailLocks) if (segment.locked) {
                if (isShuangpin()) shuangpin.select(composing.toString(), offset + segment.start, offset + segment.end, segment.reading);
                else pathEditor.select(composing.toString(), mode == Mode.NINE, offset + segment.start, offset + segment.end, segment.reading);
            }
        } else { toastWord("还没有已选的字"); return; }
        wordCreation.revision++; touchCorrection.clear(); composingCursor = composing.length(); lockedPinyinPath = "";
        hidePanels(); invalidateCandidates(); updateComposing();
    }

    private void switchWordCreationMode(Mode target) {
        if (target != Mode.PINYIN && target != Mode.NINE) { toastWord("造词支持中文 26 键与 9 键"); return; }
        if (wordCreation.editing >= 0) { toastWord("请先完成当前片段的修改"); return; }
        List<String> readings = new ArrayList<>();
        for (PinyinPathEditor.Segment segment : pinyinSegments()) {
            if (segment.prefix || !PinyinPathEditor.isCompleteReading(segment.reading)) { toastWord("请先补全当前音节，再切换布局"); return; }
            readings.add(segment.reading);
        }
        if (composing.length() > 0 && readings.isEmpty()) { toastWord("请先明确当前拼音路径"); return; }
        mode = target; composing.setLength(0); composing.append(encodeCreationReading(String.join("'", readings), target));
        pathEditor.clear(); shuangpin.clear(); restoreCreationLocks(String.join("'", readings));
        composingCursor = composing.length(); wordCreation.revision++; rememberKeyboardMode(mode);
        hidePanels(); invalidateCandidates(); updateComposing();
    }

    private void showWordCreationMenu() {
        if (keyboardView == null) return;
        if (wordCreation.active && wordCreation.saving) { toastWord("正在保存词语"); return; }
        RoundedCardPopup menu = RoundedCardPopup.keyboard(this, wordCreation.active ? "造词草稿" : "保存结果",
                KeyboardTheme.from(this, isDarkMode()));
        if (!wordCreation.active) {
            CustomWordStore.Word offer = ordinaryWordOffer;
            if (offer != null) menu.item("记住刚输入的整词", offer.text, null, false, false,
                    () -> showSaveCandidateMenu(offer.text, String.join("'", offer.readings)));
            if (lastCustomWordChange != null) menu.item("撤销词库修改", "保留已上屏文字", null, false, false, this::undoLastCustomSave);
            keyboardView.showWordMenu(menu); return;
        }
        long revision = wordCreation.revision;
        for (int index = 0; index < wordCreation.parts.size(); index++) {
            int chosen = index; WordCreationSession.Part part = wordCreation.parts.get(index);
            menu.item(part.word, part.reading.replace('\'', ' '), null, wordCreation.editing == index, false, () -> {
                if (revision != wordCreation.revision) return;
                RoundedCardPopup edit = RoundedCardPopup.keyboard(this, part.word, KeyboardTheme.from(this, isDarkMode()));
                edit.item("重新选字", "保留这段读音", null, false, false, () -> editCreationPart(chosen, true));
                edit.item("修改读音", "可退格重输，仅修改这一段", null, false, false, () -> editCreationPart(chosen, false));
                edit.item("删除这段", "保留其他已选片段", null, false, true, () -> {
                    if (revision != wordCreation.revision) return;
                    if (wordCreation.editing == chosen) { composing.setLength(0); composing.append(wordCreation.remaining); wordCreation.remaining = ""; wordCreation.editing = -1; pathEditor.clear(); shuangpin.clear(); restoreRemainingCreationLocks(); }
                    else if (wordCreation.editing > chosen) wordCreation.editing--;
                    wordCreation.parts.remove(chosen); wordCreation.revision++; composingCursor = composing.length(); invalidateCandidates(); updateComposing();
                });
                keyboardView.showWordMenu(edit);
            });
        }
        boolean ready = composing.length() == 0 && wordCreation.editing < 0 && !wordCreation.parts.isEmpty();
        if (ready && !wordCreation.saving) {
            menu.divider().item("保存并上屏", wordCreation.reading().replace('\'', ' '), null, false, false, () -> saveCreationWord(false));
            menu.item("保存并固定后上屏", "仅匹配读音或别名时优先", null, false, false, () -> saveCreationWord(true));
            menu.item("只上屏", "不保存到词库", null, false, false, () -> finishCreationWithoutSaving());
        } else menu.item("继续输入并选字", "全部选完后可保存整词", null, false, false, () -> { hidePanels(); refresh(); });
        menu.item("词语分类：" + wordCreationCategory, null, null, false, false, () -> {
            RoundedCardPopup categories = RoundedCardPopup.keyboard(this, "词语分类", KeyboardTheme.from(this, isDarkMode()));
            for (String value : new String[]{"普通词", "人名", "地名", "专业术语", "昵称", "短语"})
                categories.item(value, null, null, value.equals(wordCreationCategory), false, () -> { wordCreationCategory = value; showWordCreationMenu(); });
            keyboardView.showWordMenu(categories);
        });
        menu.item(wordCreation.editing >= 0 ? "取消这段修改" : "撤销上次选字", null, null, false, false, this::undoWordCreationSelection);
        menu.item("笔画筛选" + (wordCreation.strokePrefix.isEmpty() ? "" : "：" + wordCreation.strokePrefix),
                "在当前拼音候选中按起笔笔画找字", null, false, false, this::showCreationStrokeMenu);
        menu.item("手写补字", "选择后确认读音，再回到草稿", null, false, false, () -> onKey("手写"));
        menu.divider().item("清空草稿", null, null, false, true, () -> {
            wordCreation.start(); composing.setLength(0); pathEditor.clear(); shuangpin.clear(); updateComposing();
        });
        menu.item("退出造词", "放弃本次草稿", null, false, true, () -> {
            wordCreation.clear(); clearComposing(false); hidePanels(); invalidateCandidates(); refresh();
        });
        keyboardView.showWordMenu(menu);
    }

    private void saveCreationWord(boolean pinned) {
        if (!wordCreation.active || wordCreation.saving || composing.length() > 0 || wordCreation.editing >= 0 || !canSaveExplicitWord()) return;
        CustomWordStore.Word value;
        try { value = CustomWordStore.validate(wordCreation.word(), wordCreation.reading(), "", wordCreationCategory, pinned, 0); }
        catch (IllegalArgumentException error) { toastWord(error.getMessage()); return; }
        wordCreation.saving = true; long session = wordEditorSession; long revision = wordCreation.revision; refresh();
        CustomWordStore.IO.execute(() -> {
            CustomWordStore.Change change = CustomWordStore.merge(getApplicationContext(), value);
            mainHandler.post(() -> {
                if (destroyed) return;
                if (session != wordEditorSession || revision != wordCreation.revision || !wordCreation.active) return;
                wordCreation.saving = false;
                if (!change.succeeded()) { toastWord(change.error); refresh(); return; }
                commit(value.text); rememberWord(value.text); wordCreation.clear(); composing.setLength(0);
                pathEditor.clear(); shuangpin.clear(); hidePanels(); invalidateCandidates();
                showCustomWordSaved(change, "已保存：" + value.text + " · " + value.readings.get(0).replace('\'', ' '));
                updateComposing();
            });
        });
    }

    private void finishCreationWithoutSaving() {
        if (wordCreation.saving || composing.length() > 0 || wordCreation.editing >= 0) return;
        commit(wordCreation.word()); wordCreation.clear(); pathEditor.clear(); shuangpin.clear(); hidePanels(); updateComposing();
    }

    private void showCustomWordSaved(CustomWordStore.Change change, String notice) {
        ordinaryWordOffer = null; lastCustomWordChange = change; lastCustomWordNotice = notice + " · 点击撤销"; toastWord(notice);
        if (customWordNoticeExpiry != null) mainHandler.removeCallbacks(customWordNoticeExpiry);
        customWordNoticeExpiry = () -> { lastCustomWordChange = null; lastCustomWordNotice = ""; refresh(); };
        mainHandler.postDelayed(customWordNoticeExpiry, 10000); refresh();
    }

    private void undoLastCustomSave() {
        CustomWordStore.Change change = lastCustomWordChange; if (change == null) return;
        lastCustomWordChange = null; lastCustomWordNotice = "";
        CustomWordStore.IO.execute(() -> {
            boolean undone;
            try { undone = CustomWordStore.undo(getApplicationContext(), change); } catch (RuntimeException failed) { undone = false; }
            boolean done = undone; mainHandler.post(() -> { toastWord(done ? "已撤销词库修改" : "词条已改变，未撤销"); invalidateCandidates(); refresh(); });
        });
    }

    void offerSaveCandidate(PinyinDictionary.Entry entry) {
        if (entry == null || !canSaveExplicitWord() || wordCreation.saving) return;
        if (!entry.inputVersion.isEmpty() && !entry.inputVersion.equals(currentCandidateKey())) return;
        showSaveCandidateMenu(entry.word, entry.pronunciation);
    }

    void editCandidateWord(PinyinDictionary.Entry entry) {
        if (entry == null || !canSaveExplicitWord() || wordCreation.active) return;
        if (!entry.inputVersion.isEmpty() && !entry.inputVersion.equals(currentCandidateKey())) return;
        startActivity(new android.content.Intent(this, CustomWordManagerActivity.class)
                .putExtra("word", entry.word).putExtra("reading", entry.pronunciation)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void showSaveCandidateMenu(String word, String reading) {
        if (!canSaveExplicitWord()) { toastWord("当前模式不保存词语"); return; }
        if (reading.isEmpty()) { toastWord("请在词库管理中补充完整读音"); return; }
        RoundedCardPopup menu = RoundedCardPopup.keyboard(this, "保存「" + word + "」", KeyboardTheme.from(this, isDarkMode()));
        for (boolean pinned : new boolean[]{false, true}) menu.item(pinned ? "保存并固定" : "保存到自定义词库",
                reading.replace('\'', ' '), null, false, false, () -> {
                    CustomWordStore.Word value;
                    try { value = CustomWordStore.validate(word, reading, "", "普通词", pinned, 0); }
                    catch (IllegalArgumentException error) { toastWord(error.getMessage()); return; }
                    CustomWordStore.IO.execute(() -> {
                        CustomWordStore.Change change = CustomWordStore.merge(getApplicationContext(), value);
                        mainHandler.post(() -> { if (change.succeeded()) { invalidateCandidates(); showCustomWordSaved(change, "已保存：" + word); } else toastWord(change.error); });
                    });
                });
        keyboardView.showWordMenu(menu);
    }

    private void addHandwrittenCreationWord(String word) {
        if (dictionary == null) return;
        long revision = wordCreation.revision; long session = wordEditorSession;
        CustomWordStore.IO.execute(() -> {
            List<String> readings = dictionary.wordReadings(word);
            mainHandler.post(() -> {
                if (!wordCreation.active || revision != wordCreation.revision || session != wordEditorSession) return;
                if (readings.isEmpty()) { toastWord("未收录这个字的读音，请用拼音选字或在词库管理中添加"); return; }
                List<PinyinPathEditor.Segment> segments = pinyinSegments();
                PinyinPathEditor.Segment first = segments.isEmpty() ? null : segments.get(0);
                RoundedCardPopup menu = RoundedCardPopup.keyboard(this, "确认「" + word + "」读音", KeyboardTheme.from(this, isDarkMode()));
                int choices = 0;
                for (String reading : readings) {
                    if (reading.indexOf('\'') >= 0 || !PinyinPathEditor.isCompleteReading(reading)) continue;
                    if (first != null && first.locked && !(first.prefix ? reading.startsWith(first.reading) : reading.equals(first.reading))) continue;
                    choices++;
                    menu.item(reading, null, null, false, false, () -> {
                        if (revision != wordCreation.revision) return;
                        int consumed = first == null ? 0 : first.end;
                        if (consumed > 0) {
                            String raw = composing.substring(0, consumed);
                            String encoded = mode == Mode.NINE ? PinyinDictionary.toT9(reading) : isShuangpin() ? ShuangpinInput.encode(reading) : reading;
                            if (!encoded.startsWith(raw.replace("'", ""))) { toastWord("这个读音与当前编码不匹配，请先修改拼音"); return; }
                        }
                        PinyinDictionary.Entry selected = new PinyinDictionary.Entry(word, 0).withInput(consumed, reading);
                        if (first == null) { if (wordCreation.add(word, reading, "")) { hidePanels(); invalidateCandidates(); refresh(); } }
                        else { handwritingShown = false; selectWordCreationCandidate(selected); }
                    });
                }
                if (choices == 0) toastWord("这个字与当前限定读音不匹配，请先修改拼音"); else keyboardView.showWordMenu(menu);
            });
        });
    }

    private boolean isOwnWordEditor(EditorInfo info) {
        return info != null && getPackageName().equals(info.packageName) && info.privateImeOptions != null
                && info.privateImeOptions.startsWith("offlineime-word-");
    }

    private boolean canLearn() {
        // Never persist a partially loaded/rebuilt user-word index.
        return dictionary != null && dictionary.isUserDataReady() && !secureEditor && !isOwnWordEditor(editorInfo)
                && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("learn_words", true)
                && !getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false);
    }
    private boolean canUseClipboard() { return !secureEditor && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("clipboard_enabled", false) && !getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false); }

    static String classifyEditorLayout(EditorInfo info) {
        return classifyEditorLayout(info == null ? InputType.TYPE_CLASS_TEXT : info.inputType);
    }

    static String classifyEditorLayout(int type) {
        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_PHONE) return "phone";
        if (cls == InputType.TYPE_CLASS_DATETIME) {
            if (variation == InputType.TYPE_DATETIME_VARIATION_DATE) return "date";
            if (variation == InputType.TYPE_DATETIME_VARIATION_TIME) return "time";
            return "datetime";
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            if ((type & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0) return "decimal";
            if ((type & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0) return "signed";
            return "number";
        }
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return "email";
            if (variation == InputType.TYPE_TEXT_VARIATION_URI) return "uri";
        }
        return "text";
    }

    static boolean isSensitiveEditor(EditorInfo info) {
        if (info == null) return false;
        return isSensitiveInput(info.inputType, info.imeOptions);
    }

    static boolean isSensitiveInput(int type, int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true;
        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    private void sendKey(int code) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
    }

    private void moveCaretBy(int delta) {
        if (delta == 0) return;
        if (composing.length() > 0) {
            composingCursor = Math.max(0, Math.min(composing.length(), composingCursor + delta));
            refresh();
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        android.view.inputmethod.ExtractedText extracted = connection.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (extracted == null || extracted.text == null) {
            int code = delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            for (int i = 0; i < Math.min(20, Math.abs(delta)); i++) sendKey(code);
            return;
        }
        int length = extracted.text.length();
        int start = Math.max(0, extracted.selectionStart);
        int end = Math.max(0, extracted.selectionEnd);
        if (selectionMode) {
            if (selectionAnchor < 0) selectionAnchor = start;
            if (selectionExtent < 0) selectionExtent = end;
            selectionExtent = Math.max(0, Math.min(length, selectionExtent + delta));
            connection.setSelection(Math.min(selectionAnchor, selectionExtent),
                    Math.max(selectionAnchor, selectionExtent));
        } else {
            int caret = delta < 0 ? Math.min(start, end) : Math.max(start, end);
            caret = Math.max(0, Math.min(length, caret + delta));
            connection.setSelection(caret, caret);
        }
    }

    private void toggleSelectionMode() {
        clearComposing(true);
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        android.view.inputmethod.ExtractedText extracted = connection.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (extracted == null) return;
        if (selectionMode) {
            selectionMode = false;
            int caret = Math.max(extracted.selectionStart, extracted.selectionEnd);
            connection.setSelection(caret, caret);
            selectionAnchor = selectionExtent = -1;
        } else {
            selectionMode = true;
            selectionAnchor = Math.max(0, extracted.selectionStart);
            selectionExtent = Math.max(0, extracted.selectionEnd);
        }
        android.widget.Toast.makeText(this, selectionMode ? "选择模式已开启" : "选择模式已关闭",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private String convertGeneratedText(String value) {
        if (value == null || !"traditional".equals(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("output_script", "simplified"))) return value;
        return HanConverter.get(this).toTraditional(value);
    }

    private void modifiedKey(int code) {
        InputConnection c = getCurrentInputConnection();
        if (c != null) {
            long now = System.currentTimeMillis();
            c.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON));
            c.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_CTRL_ON));
        }
    }

    private void contextAction(int actionId, int fallbackKey) {
        clearComposing(true);
        InputConnection c = getCurrentInputConnection();
        if (c == null) return;
        c.finishComposingText();
        hostHasComposingText = false;
        if (!c.performContextMenuAction(actionId)) modifiedKey(fallbackKey);
        if (actionId == android.R.id.cut || actionId == android.R.id.paste
                || actionId == android.R.id.selectAll) resetSelectionMode();
    }

    private void resetSelectionMode() {
        selectionMode = false;
        selectionAnchor = selectionExtent = -1;
    }

    private boolean isLetter(String value) {
        return value.length() == 1 && ((value.charAt(0) >= 'a' && value.charAt(0) <= 'z')
                || (value.charAt(0) >= 'A' && value.charAt(0) <= 'Z'));
    }

    private void hidePanels() {
        toolsShown = clipboardShown = handwritingShown = emojiShown = symbolsShown
                = layoutChooserShown = candidateExpanded = false;
        clipboardEnablePromptShown = false;
        clipboardClearConfirmShown = false;
        clipboardMultiSelect = false;
        clipboardSelection.clear();
    }

    private void invalidateCandidates() {
        candidateCache.evictAll();
        segmentsCacheKey = "";
        cancelCandidateWork();
        candidateResultKey = candidatePendingKey = candidateCompleteKey = candidateSelectableKey = "";
        candidateResults = java.util.Collections.emptyList();
        candidateDisplayMode = null;
        candidateDisplayInput = "";
    }

    private void cancelCandidateWork() {
        if (candidateQueries != null) candidateQueries.cancel();
    }

    private List<PinyinDictionary.Entry> bindCandidateVersion(List<PinyinDictionary.Entry> source, String key) {
        List<PinyinDictionary.Entry> bound = new ArrayList<>(source.size());
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            PinyinDictionary.Entry copy = entry.withInput(entry.consumedLength, entry.pronunciation);
            copy.inputVersion = key;
            bound.add(copy);
        }
        return java.util.Collections.unmodifiableList(bound);
    }

    private void refresh() {
        if (destroyed) return;
        if (keyboardView == null) return;
        // Start the latest query immediately, then coalesce all visual work into
        // one traversal for the next display frame.
        if (clipboardSearchDialogOpen || (!toolsShown && !clipboardShown
                && !handwritingShown && !emojiShown && !symbolsShown && !layoutChooserShown)) {
            candidates();
        }
        if (refreshScheduled) return;
        refreshScheduled = true;
        keyboardView.postOnAnimation(renderRefreshRunnable);
    }

    private void renderRefreshNow() {
        long renderStarted = ImePerformanceMonitor.start();
        try {
        refreshScheduled = false;
        if (destroyed) return;
        if (keyboardView != null) {
            if (clipboardSearchDialogOpen) keyboardView.refresh(mode, shift, composing.toString(), candidates());
            else if (toolsShown) keyboardView.showTools();
            else if (clipboardShown && clipboardEnablePromptShown) keyboardView.showClipboardEnablePrompt();
            else if (clipboardShown) keyboardView.showClipboardHistory(readClipboardHistory(), clipboardFilter,
                    clipboardSearchQuery, clipboardClearConfirmShown, clipboardClearBackup != null,
                    clipboardMultiSelect, new java.util.LinkedHashSet<>(clipboardSelection));
            else if (handwritingShown) keyboardView.showHandwriting();
            // Candidate completions must not rebuild a scrolling/touched emoji page.
            // Category changes render explicitly through onKey.
            else if (emojiShown) return;
            else if (symbolsShown) return;
            else if (layoutChooserShown) keyboardView.showLayoutChooser(mode);
            else if (candidateExpanded) {
                List<PinyinDictionary.Entry> values = candidates();
                // Keep the expanded surface intact until candidates and paths
                // for the same input can be presented in one frame.
                if (composing.length() == 0 || candidateSurfaceReady())
                    keyboardView.showCandidatePage(mode, composing.toString(), values, candidatePage);
            } else {
                List<PinyinDictionary.Entry> values = candidates();
                keyboardView.setCandidatePage(candidatePage);
                keyboardView.refresh(mode, shift, composing.toString(), values);
            }
            keyboardView.showWordCreationStatus(wordCreationStatus());
        }
        } finally {
            ImePerformanceMonitor.stage(this, "ui_bind", renderStarted, candidateResults.size(), mode == Mode.NINE);
        }
    }

    private void rememberEmoji(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        android.content.SharedPreferences p = getSharedPreferences("emoji", MODE_PRIVATE);
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String s : EmojiCatalog.recent(p)) if (!s.isEmpty() && !s.equals(emoji)) list.add(s);
        list.add(0, emoji); while (list.size() > 32) list.remove(list.size() - 1);
        p.edit().putString("recent_v2", new org.json.JSONArray(list).toString()).apply();
    }

    private boolean isDarkMode() {
        return getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("dark", false);
    }

    private Mode restoreKeyboardMode() {
        String fallback = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("default_layout", "qwerty").equals("nine") ? "NINE" : "PINYIN";
        return savedMode(LAST_KEYBOARD_MODE, fallback, true, true);
    }

    private Mode restoreLastChineseMode() {
        String fallback = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("default_layout", "qwerty").equals("nine") ? "NINE" : "PINYIN";
        Mode restored = savedMode(LAST_CHINESE_MODE, fallback, false, false);
        return restored == Mode.NINE ? Mode.NINE : restored == Mode.STROKE ? Mode.STROKE : Mode.PINYIN;
    }

    private Mode savedMode(String key, String fallback, boolean allowEnglish, boolean allowSymbols) {
        String saved = getSharedPreferences(KEYBOARD_STATE_PREFERENCES, MODE_PRIVATE)
                .getString(key, fallback);
        try {
            Mode restored = Mode.valueOf(saved);
            if (restored == Mode.PINYIN || restored == Mode.NINE || restored == Mode.STROKE
                    || allowEnglish && restored == Mode.ENGLISH
                    || allowSymbols && restored == Mode.SYMBOLS) return restored;
        } catch (IllegalArgumentException ignored) { }
        return "NINE".equals(fallback) ? Mode.NINE : Mode.PINYIN;
    }

    private void rememberKeyboardMode(Mode selected) {
        if (selected != Mode.PINYIN && selected != Mode.NINE
                && selected != Mode.STROKE && selected != Mode.ENGLISH
                && selected != Mode.SYMBOLS) return;
        android.content.SharedPreferences.Editor editor = getSharedPreferences(
                KEYBOARD_STATE_PREFERENCES, MODE_PRIVATE).edit()
                .putString(LAST_KEYBOARD_MODE, selected.name());
        if (selected == Mode.PINYIN || selected == Mode.NINE || selected == Mode.STROKE)
            editor.putString(LAST_CHINESE_MODE, selected.name());
        // Layout changes are infrequent. A synchronous commit guarantees the
        // value reaches disk before requestHideSelf can end the IME lifecycle.
        editor.commit();
    }

    private static int getIntPreference(android.content.SharedPreferences prefs, String key, int fallback) {
        try { return prefs.getInt(key, fallback); }
        catch (ClassCastException ignored) {
            String value = prefs.getString(key, null);
            if (value != null) try { return Integer.parseInt(value); } catch (NumberFormatException ignoredNumber) { }
            return fallback;
        }
    }
}
