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
    private boolean customImeWindowLayout;
    private int originalImeWindowWidth;
    private int originalImeWindowHeight;
    private int originalImeWindowGravity;
    private int originalImeWindowX;
    private int originalImeWindowY;
    private android.graphics.drawable.Drawable originalImeWindowBackground;
    private boolean candidateCommitInProgress;
    private final ComposingSessionController composingSession = new ComposingSessionController();
    private Mode mode = Mode.PINYIN;
    private Mode modeBeforeSymbols = Mode.PINYIN;
    private Mode modeBeforeEnglish = Mode.PINYIN;
    private boolean shift;
    private boolean capsLock;
    private long lastShiftTapUptime;
    private boolean englishWordMode = true;
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
    private boolean hardwareSoftExpanded;
    private HandwritingView stylusOverlayView;
    private final HardwareKeyboardController hardwareKeyboard = new HardwareKeyboardController(
            new HardwareKeyboardController.Host() {
                @Override public boolean isEnabled() {
                    return settingsPreferences != null && settingsPreferences.getBoolean("hw_keyboard_enabled", true);
                }
                @Override public boolean isChineseMode() {
                    return mode == Mode.PINYIN || mode == Mode.NINE || mode == Mode.STROKE;
                }
                @Override public boolean isComposing() { return !composingSession.isEmpty(); }
                @Override public boolean isPasswordOrUri() {
                    return editorSession.sensitive || "email".equals(editorSession.layout)
                            || "uri".equals(editorSession.layout) || editorSession.numeric;
                }
                @Override public void onKey(String key) { OfflineImeService.this.onKey(key); }
                @Override public void toggleChineseEnglish() { OfflineImeService.this.onKey("中英"); }
                @Override public void clearComposing() { OfflineImeService.this.clearComposing(true); }
                @Override public void commitText(String text) { OfflineImeService.this.commit(text); }
                @Override public void pageCandidates(int delta) { OfflineImeService.this.onCandidatePage(delta); }
                @Override public boolean selectCandidateOnPage(int indexInPage) {
                    int pageSize = CandidatePaging.pageSize(
                            SettingsRepository.integer(settingsPreferences, "candidate_count", 6));
                    List<PinyinDictionary.Entry> values = candidates();
                    int index = candidatePage * pageSize + indexInPage;
                    if (index < 0 || index >= values.size()) return false;
                    onCandidate(index);
                    return true;
                }
                @Override public void ensureCandidateBarVisible() {
                    if (!isInputViewShown() && android.os.Build.VERSION.SDK_INT >= 28) requestShowSelf(0);
                }
            });
    private List<ClipboardHistoryStore.Entry> clipboardClearBackup;
    private final java.util.LinkedHashSet<String> clipboardSelection = new java.util.LinkedHashSet<>();
    private boolean clipboardMultiSelect;
    private Runnable clipboardUndoExpiry;
    private ClipboardManager clipboardManager;
    private boolean clipboardListenerRegistered;
    private long lastClipboardTimestamp;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChangedListener =
            this::captureClipboardWhileActive;
    private static final Pattern LABELED_ONE_TIME_CODE = Pattern.compile(
            "(?i)(验证码|动态码|校验码|一次性密码|otp|one[- ]?time code).{0,16}\\b\\d{4,8}\\b");
    private boolean handwritingShown, handwritingFullscreen;
    private static final long VOICE_DRAFT_TTL_MS = 180_000L;
    private boolean voiceShown, voiceFinishing, voicePaused, voiceHoldMode;
    private boolean voiceSegmentCommitMode, voiceSegmentCommitInProgress, voiceCommitAndSendPending, voiceLowSignalShown;
    private int voiceCommittedSegments;
    private float voiceAudioLevel;
    private long voiceElapsedMs;
    private String voiceStatus = "", voicePreview = "";
    private String voiceDraftText = "";
    private long voiceDraftSavedAt;
    private long voiceEditorGeneration;
    private int voiceSelectionStart = -1, voiceSelectionEnd = -1;
    private VoiceInputController voiceInput;
    private boolean emojiShown;
    private String emojiSearchQuery = "";
    private boolean panelSearchRestoresEmoji;
    private Mode emojiSearchReturnMode;
    private boolean symbolsShown;
    private boolean layoutChooserShown;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean userDataReceiverRegistered;
    private final android.content.BroadcastReceiver userDataClearedReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            userDataEpoch++;
            selectionEdit.clearAll();
            synchronized (OfflineImeService.this.deferredLearning) { OfflineImeService.this.deferredLearning.clear(); }
            wordCreationController.clearAllUserData();
            if (dictionary != null) dictionary.discardClearedUserData();
            reloadBlockedWordsCache();
            if (clipboardHistory != null) clipboardHistory.resumeAfterGlobalClear();
            ClipboardManager manager = getClipboardManager();
            ClipDescription currentClip = manager == null ? null : manager.getPrimaryClipDescription();
            if (currentClip != null) rememberClipboardTimestamp(currentClip.getTimestamp());
            composingSession.clearInputOnly();
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
    private Future<?> contextPrewarmTask;
    private boolean prewarmScheduled;
    private boolean prewarmFinished;
    private ClipboardController clipboardHistory;
    private QuickPhraseRepository quickPhrases;
    private final Runnable quickPhraseObserver = this::onQuickPhrasesChanged;
    private void onQuickPhrasesChanged() { if (!destroyed && clipboardShown) refresh(); }
    private boolean storageWarningShown;
    private void notifyStorageReadFailure() {
        if (!SecureLocalStore.hasReadErrors()) { storageWarningShown = false; return; }
        if (!storageWarningShown && !destroyed) {
            storageWarningShown = true;
            toastWord("部分本地数据读取失败，原数据已保留，已暂停覆盖；请重新打开相关页面重试");
        }
    }
    private String previousCommittedWord = "";
    private String previousPreviousCommittedWord = "";
    private long lastChineseCommitAt;
    private Runnable nextWordPredictionExpiry;
    private volatile long userDataEpoch;
    private final SelectionEditController selectionEdit = new SelectionEditController();
    private final WordCreationController wordCreationController = new WordCreationController(new WordCreationController.Host() {
        @Override public Context context() { return OfflineImeService.this; }
        @Override public Handler mainHandler() { return mainHandler; }
        @Override public ComposingSessionController composing() { return composingSession; }
        @Override public PinyinDictionary dictionary() { return dictionary; }
        @Override public KeyboardView keyboardView() { return keyboardView; }
        @Override public HandwritingView handwritingInput() { return OfflineImeService.this.handwritingInput(); }
        @Override public InputConnection currentInputConnection() { return getCurrentInputConnection(); }
        @Override public Mode mode() { return mode; }
        @Override public void setMode(Mode next) { mode = next; }
        @Override public Mode restoreLastChineseMode() { return OfflineImeService.this.restoreLastChineseMode(); }
        @Override public void rememberKeyboardMode(Mode selected) { OfflineImeService.this.rememberKeyboardMode(selected); }
        @Override public boolean isShuangpin() { return OfflineImeService.this.isShuangpin(); }
        @Override public boolean isDarkMode() { return OfflineImeService.this.isDarkMode(); }
        @Override public boolean isDestroyed() { return destroyed; }
        @Override public boolean isSensitiveEditor() { return editorSession.sensitive; }
        @Override public boolean canSaveExplicitWord() { return OfflineImeService.this.canSaveExplicitWord(); }
        @Override public boolean learningAllowed() { return OfflineImeService.this.learningAllowed(); }
        @Override public boolean canLearn() { return OfflineImeService.this.canLearn(); }
        @Override public long editorGeneration() { return editorSession.generation; }
        @Override public String currentCandidateKey() { return OfflineImeService.this.currentCandidateKey(); }
        @Override public List<PinyinPathEditor.Segment> pinyinSegments() { return OfflineImeService.this.pinyinSegments(); }
        @Override public boolean clearComposing(boolean commitBest) { return OfflineImeService.this.clearComposing(commitBest); }
        @Override public boolean commitText(CharSequence text) { return commit(text); }
        @Override public void hidePanels() { OfflineImeService.this.hidePanels(); }
        @Override public void closeHandwriting() { OfflineImeService.this.closeHandwriting(); }
        @Override public void invalidateCandidates() { OfflineImeService.this.invalidateCandidates(); }
        @Override public void resetCandidatePaging() { candidateExpanded = false; candidatePage = 0; }
        @Override public void updateComposing() { OfflineImeService.this.updateComposing(); }
        @Override public void refresh() { OfflineImeService.this.refresh(); }
        @Override public void expandCandidates() { onCandidateExpand(); }
        @Override public void dispatchKey(String key) { onKey(key); }
        @Override public void rememberWord(String word) { OfflineImeService.this.rememberWord(word); }
        @Override public void queueAssembledWordLearning(String code, String word) {
            queueWordLearning(code, word, "*", PinyinDictionary.TIER_EXACT_FULL, true);
        }
        @Override public void toast(String message) { toastWord(message); }
    });
    boolean isShuangpin() { return mode == Mode.PINYIN && settingsPreferences != null
            && "xiaohe".equals(settingsPreferences.getString("pinyin_scheme", "full")); }
    boolean isNameMode() { return wordCreationController.isNameMode(); }
    private final EditorSession editorSession = new EditorSession();
    private final CandidateSession candidateSession = new CandidateSession();
    private CandidateInputSnapshot candidateInputSnapshot;
    private boolean refreshScheduled;
    private final Runnable renderRefreshRunnable = this::renderRefreshNow;
    private final OrderedKeyInput keyInputOrder = new OrderedKeyInput();
    OrderedKeyInput keyInputOrder() { return keyInputOrder; }
    private KeyPreview keyPreview;
    KeyPreview keyPreview() {
        if (settingsPreferences != null && !settingsPreferences.getBoolean("key_preview", true)) return null;
        if (keyPreview == null) keyPreview = new KeyPreview(this);
        return keyPreview;
    }
    String candidateInputVersion() { return currentCandidateKey(); }
    private long settingsRevision;
    private android.content.SharedPreferences settingsPreferences;
    private boolean privacyMode;
    private volatile boolean outputTraditional;
    private volatile boolean emojiCandidatesEnabled = true;
    private volatile boolean spellingCorrectionEnabled;
    private volatile boolean touchCorrectionEnabled = true;
    private volatile java.util.Set<String> blockedWords = java.util.Collections.emptySet();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (preferences, name) -> {
        refreshCachedPreferences();
        ImePerformanceMonitor.refreshLoggingEnabled(this);
        if (name == null || "blocked_words_revision".equals(name)) reloadBlockedWordsCache();
        if ("key_preview".equals(name) && keyPreview != null && !preferences.getBoolean(name, true))
            keyPreview.dismiss();
        if ("pinyin_scheme".equals(name)) {
            composingSession.resetForSchemeChange(getCurrentInputConnection());
        }
        if (name == null || "privacy_mode".equals(name)) {
            privacyMode = preferences.getBoolean("privacy_mode", false);
            selectionEdit.clearAll();
            userDataEpoch++;
            synchronized (OfflineImeService.this.deferredLearning) { OfflineImeService.this.deferredLearning.clear(); }
            if (clipboardHistory != null) clipboardHistory.releaseMemory(); previousCommittedWord = previousPreviousCommittedWord = "";
            lastChineseCommitAt = 0; stopClipboardListener();
        }
        if ("clipboard_enabled".equals(name) || "clipboard_capture_while_typing".equals(name)
                || "privacy_mode".equals(name)) {
            stopClipboardListener();
            mainHandler.post(this::startClipboardListenerIfNeeded);
        }
        if ("learn_words".equals(name) && !preferences.getBoolean("learn_words", true)) {
            synchronized (OfflineImeService.this.deferredLearning) { OfflineImeService.this.deferredLearning.clear(); }
        }
        settingsRevision++;
        invalidateCandidates();
        if (keyboardView != null) {
            keyboardView.setDark(isDarkMode());
            mainHandler.post(() -> { updateInputViewLayout(); refresh(); });
        }
    };

    private void refreshCachedPreferences() {
        if (settingsPreferences == null) return;
        outputTraditional = "traditional".equals(settingsPreferences.getString("output_script", "simplified"));
        emojiCandidatesEnabled = settingsPreferences.getBoolean("emoji_candidates", true);
        spellingCorrectionEnabled = settingsPreferences.getBoolean("spelling_correction", false);
        touchCorrectionEnabled = settingsPreferences.getBoolean("touch_correction", true);
    }

    private void reloadBlockedWordsCache() {
        android.content.SharedPreferences prefs = getSharedPreferences("blocked_words", MODE_PRIVATE);
        java.util.Set<String> set = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
            if (Boolean.TRUE.equals(item.getValue()) && item.getKey() != null && !item.getKey().isEmpty()) {
                set.add(item.getKey());
            }
        }
        blockedWords = java.util.Collections.unmodifiableSet(set);
        if (dictionary != null && dictionary.isLoaded()) {
            dictionary.setBlockedWords(blockedWords);
        }
    }
    private volatile boolean destroyed;

    String activePinyinPath() {
        return composingSession.activePinyinPath(isShuangpin(), pinyinSegments());
    }
    String selectedPinyinPath() {
        return composingSession.selectedPinyinPath(mode, isShuangpin(), editorSession.sensitive, pinyinSegments());
    }
    String currentPinyinPath() {
        return composingSession.currentPinyinPath(mode, isShuangpin(), editorSession.sensitive, pinyinSegments());
    }
    List<PinyinPathEditor.Segment> pinyinSegments() {
        return composingSession.pinyinSegments(mode, isShuangpin(), editorSession.sensitive,
                candidateSession, currentCandidateKey(), dictionary);
    }
    PinyinPathEditor.Segment focusedPinyinSegment() {
        return composingSession.focusedPinyinSegment(pinyinSegments());
    }
    private void advancePinyinSelection(int selectedStart, int selectedEnd) {
        composingSession.advancePinyinSelection(selectedStart, selectedEnd);
    }
    boolean pinyinSegmentFocused(PinyinPathEditor.Segment segment) {
        PinyinPathEditor.Segment focused = focusedPinyinSegment();
        return focused != null && focused.start == segment.start;
    }
    boolean pinyinPathLocked() {
        return composingSession.isPathLocked(mode, isShuangpin());
    }
    boolean pinyinCandidatesReady() { return currentCandidateKey().equals(candidateSession.candidateSelectableKey); }
    boolean candidateSurfaceReady() { return currentCandidateKey().equals(candidateSession.candidateSelectableKey); }
    boolean candidateFirstFramePending() {
        if (destroyed || editorSession.sensitive || candidateSession.candidatePendingKey.isEmpty()) return false;
        String key = currentCandidateKey();
        return key.equals(candidateSession.candidatePendingKey)
                && !key.equals(candidateSession.candidateSelectableKey);
    }
    boolean pinyinCandidatesEmpty() { return candidateSession.candidateResults.isEmpty(); }
    boolean nineKeyMode() { return mode == Mode.NINE; }
    boolean compactNineLayout() {
        return (mode == Mode.NINE || mode == Mode.SYMBOLS || mode == Mode.STROKE) && !handwritingShown;
    }
    void refreshPinyinPaths() { if (!destroyed) refresh(); }
    String pinyinDisplayReading(PinyinPathEditor.Segment segment) {
        return composingSession.displayReading(segment, isShuangpin(), mode == Mode.NINE);
    }
    boolean pinyinReadingSelected(PinyinPathEditor.Segment segment, String reading) {
        return composingSession.readingSelected(segment, reading, isShuangpin());
    }
    List<String> pinyinSyllableChoices(PinyinPathEditor.Segment segment) {
        return composingSession.syllableChoices(segment, isShuangpin(), mode == Mode.NINE,
                editorSession.sensitive, dictionary);
    }

    List<PinyinPathEditor.Segment> ninePinyinChoiceSegments() {
        return composingSession.ninePinyinChoiceSegments(focusedPinyinSegment(), isShuangpin(), dictionary);
    }
    private PinyinPathEditor.Query pathQuery() {
        return composingSession.pathQuery(mode, isShuangpin());
    }
    private boolean candidateExpanded;
    private String deferredCommitKey = "", deferredCommitAction = "";
    private long contextRevision;
    private int candidatePage;

    @Override public void onCreate() {
        super.onCreate();
        ByteBudgetCache.configure(this);
        keyInputOrder.setOnIdle(() -> { if (keyboardView != null) keyboardView.onKeyInputIdle(); });
        settingsPreferences = SettingsRepository.preferences(this);
        privacyMode = settingsPreferences.getBoolean("privacy_mode", false);
        refreshCachedPreferences();
        ImePerformanceMonitor.refreshLoggingEnabled(this);
        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsListener);
        dictionary = new PinyinDictionary(getAssets(), this);
        reloadBlockedWordsCache();
        clipboardHistory = new ClipboardController(this, () -> { if (!destroyed && clipboardShown) refresh(); }, this::toastWord);
        lastClipboardTimestamp = getSharedPreferences("clipboard_capture_state", MODE_PRIVATE)
                .getLong("last_timestamp", 0);
        startClipboardListenerIfNeeded();
        quickPhrases = QuickPhraseRepository.get(this);
        quickPhrases.observe(quickPhraseObserver);
        loaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "offline-lexicon-loader");
            t.setDaemon(true);
            return t;
        });
        candidateSession.queries = new CandidateQueryCoordinator();
        candidateSession.queries.setNativeCanceler(() -> dictionary == null ? 0L : dictionary.cancelQueries());
        contextRevision = settingsPreferences.getLong("context_revision", 0);
        // A replacement service must load its personal data after the previous service saves it.
        loaderTask = CustomWordStore.IO.submit(() -> dictionary.load(() -> mainHandler.post(this::onDictionaryReady)));
        loaderExecutor.execute(() -> EmojiCatalog.get(this).prewarm());
        registerUserDataReceiver();
        logImeLifecycle("service_created");
    }

    @Override public View onCreateInputView() {
        stopVoiceInput();
        cancelRenderRefresh();
        if (keyboardView == null || !handwritingShown) {
            if (keyboardView != null) keyboardView.releaseAudio();
            keyboardView = new KeyboardView(this);
        } else if (keyboardView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) keyboardView.getParent()).removeView(keyboardView);
        }
        keyboardView.setDark(isDarkMode());
        updateInputViewLayout();
        // Supply key rows and a non-zero height before the framework measures it.
        if (handwritingShown) keyboardView.showHandwriting();
        else keyboardView.refresh(mode, shift, "", java.util.Collections.emptyList());
        logImeLifecycle("create_input_view");
        return keyboardView;
    }

    private void cancelRenderRefresh() {
        if (keyboardView != null) keyboardView.removeCallbacks(renderRefreshRunnable);
        refreshScheduled = false;
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ByteBudgetCache.trimMemory(level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
            if (clipboardHistory != null) clipboardHistory.releaseMemory();
            if (voiceInput != null) voiceInput.trimMemory();
        }
    }

    void onKeyboardViewDetached(KeyboardView view) {
        if (view == keyboardView) { stopVoiceInput(); cancelRenderRefresh(); }
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
        startClipboardListenerIfNeeded();
        logImeLifecycle("window_shown");
    }

    @Override public void onWindowHidden() {
        stopVoiceInput();
        keyInputOrder.cancelAll();
        if (keyboardView != null) keyboardView.cancelKeyTouches();
        HandwritingView pad = handwritingInput();
        if (pad != null) pad.cancelPendingConfirmation();
        super.onWindowHidden();
        logImeLifecycle("window_hidden");
    }

    boolean isHardwareCompactMode() {
        if (hardwareSoftExpanded || handwritingShown) return false;
        android.content.SharedPreferences p = settingsPreferences != null
                ? settingsPreferences : getSharedPreferences("settings", MODE_PRIVATE);
        return KeyboardPlacement.hardwareCompactBar(this, p);
    }

    @Override public boolean onEvaluateInputViewShown() {
        boolean base = super.onEvaluateInputViewShown();
        if (base) return true;
        android.content.SharedPreferences p = settingsPreferences != null
                ? settingsPreferences : getSharedPreferences("settings", MODE_PRIVATE);
        return KeyboardPlacement.hardwareKeyboardConnected(this)
                && p.getBoolean("hw_keyboard_enabled", true);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!KeyboardPlacement.hardwareKeyboardConnected(this)) hardwareSoftExpanded = false;
        if (keyboardView != null) keyboardView.setDark(isDarkMode());
        updateInputViewLayout();
        if (keyboardView != null) refresh();
    }

    private void updateInputViewLayout() {
        if (keyboardView == null) return;
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        boolean hwCompact = isHardwareCompactMode();
        boolean floating = !handwritingShown && !hwCompact && KeyboardPlacement.floating(this, p);
        int screen = KeyboardPlacement.availableWidthDp(this);
        int screenHeight = KeyboardPlacement.availableHeightDp(this);
        int width = handwritingShown ? screen : hwCompact ? Math.min(720, screen) : KeyboardPlacement.width(this, p);
        String side = handwritingShown || hwCompact ? "off" : KeyboardPlacement.side(this, p);
        keyboardView.setHardwareCompactMode(hwCompact);
        keyboardView.setHandwritingFullscreen(handwritingShown && handwritingFullscreen);
        keyboardView.setFloatingLandscape(floating || width < screen);
        keyboardView.requestLayout();
        android.app.Dialog dialog = getWindow();
        android.view.Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        boolean resized = handwritingShown || hwCompact || floating || KeyboardPlacement.landscape(this)
                || !"off".equals(side) || width < screen;
        if (resized) {
            if (!originalImeWindowLayoutSaved) {
                originalImeWindowWidth = params.width; originalImeWindowHeight = params.height;
                originalImeWindowGravity = params.gravity; originalImeWindowX = params.x; originalImeWindowY = params.y;
                originalImeWindowBackground = window.getDecorView().getBackground(); originalImeWindowLayoutSaved = true;
            }
            if (handwritingShown) {
                params.width = WindowManager.LayoutParams.MATCH_PARENT;
                params.height = handwritingFullscreen ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowManager.LayoutParams.WRAP_CONTENT;
                params.gravity = Gravity.BOTTOM;
                params.x = 0;
                params.y = 0;
            } else {
                float density = getResources().getDisplayMetrics().density;
                params.width = Math.round(width * density);
                params.height = originalImeWindowHeight;
                params.gravity = Gravity.BOTTOM | Gravity.START;
                String axis = KeyboardPlacement.axis(this);
                int x = floating ? p.getInt("floating_x_" + axis, (screen - width) / 2)
                        : "left".equals(side) ? 0 : "right".equals(side) ? screen - width : (screen - width) / 2;
                int maxY = Math.max(0, screenHeight - KeyboardPlacement.height(this, p) - 32);
                int physicalX = Math.max(0, Math.min(Math.max(0, screen - width), x));
                int physicalY = floating ? Math.max(0, Math.min(maxY, p.getInt("floating_y_" + axis, 48))) : 0;
                if (floating && (physicalX != x || physicalY != p.getInt("floating_y_" + axis, 48))) {
                    p.edit().putInt("floating_x_" + axis, physicalX).putInt("floating_y_" + axis, physicalY).apply();
                }
                boolean rtl = window.getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                params.x = Math.round((rtl ? Math.max(0, screen - width) - physicalX : physicalX) * density);
                params.y = Math.round(physicalY * density);
            }
            window.setAttributes(params);
            if (!customImeWindowLayout)
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            customImeWindowLayout = true;
        } else if (customImeWindowLayout && originalImeWindowLayoutSaved) {
            params.width = originalImeWindowWidth; params.height = originalImeWindowHeight;
            params.gravity = originalImeWindowGravity; params.x = originalImeWindowX; params.y = originalImeWindowY;
            window.setAttributes(params); window.setBackgroundDrawable(originalImeWindowBackground);
            customImeWindowLayout = false;
        }
    }

    void moveFloatingKeyboard(float dx, float dy, boolean save) {
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (handwritingShown || !KeyboardPlacement.floating(this, p)
                || getWindow() == null || getWindow().getWindow() == null) return;
        android.view.Window window = getWindow().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        float density = getResources().getDisplayMetrics().density;
        int screenW = KeyboardPlacement.availableWidthDp(this);
        int screenH = KeyboardPlacement.availableHeightDp(this);
        int maxX = Math.max(0, Math.round(screenW * density) - params.width);
        int maxY = Math.max(0, Math.round((screenH - KeyboardPlacement.height(this, p) - 32) * density));
        params.x = Math.max(0, Math.min(maxX, params.x + Math.round(dx)));
        params.y = Math.max(0, Math.min(maxY, params.y - Math.round(dy)));
        window.setAttributes(params);
        if (save) {
            int yDp = Math.round(params.y / density);
            if (yDp <= 6 && dy > 0) {
                snapFloatingToDock();
                return;
            }
            p.edit().putInt("floating_x_" + KeyboardPlacement.axis(this), Math.round(params.x / density))
                    .putInt("floating_y_" + KeyboardPlacement.axis(this), yDp).apply();
        }
    }

    void resizeFloatingKeyboard(float dwPx, float dhPx, boolean save) {
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (handwritingShown || !KeyboardPlacement.floating(this, p)) return;
        float density = Math.max(0.5f, getResources().getDisplayMetrics().density);
        int screenW = KeyboardPlacement.availableWidthDp(this);
        int screenH = KeyboardPlacement.availableHeightDp(this);
        int curW = KeyboardPlacement.width(this, p);
        int curH = KeyboardPlacement.height(this, p);
        int nextW = Math.max(Math.min(280, screenW), Math.min(screenW, curW + Math.round(dwPx / density)));
        int nextH = Math.max(200, Math.min(Math.min(560, Math.round(screenH * 0.68f)), curH + Math.round(dhPx / density)));
        String axis = KeyboardPlacement.axis(this);
        p.edit().putString("tablet_preset_" + axis, "floating")
                .putInt("floating_width_" + axis, nextW)
                .putInt("floating_height_" + axis, nextH).apply();
        if (save) updateInputViewLayout();
    }

    void snapFloatingToDock() {
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        String axis = KeyboardPlacement.axis(this);
        p.edit().putString("tablet_preset_" + axis, "center").putBoolean("floating", false).apply();
        toastWord("已停靠到底部");
    }

    @Override public void onPrepareStylusHandwriting() {
        super.onPrepareStylusHandwriting();
        if (android.os.Build.VERSION.SDK_INT >= 33 && stylusOverlayView == null) {
            stylusOverlayView = new HandwritingView(this, false, new HandwritingView.Callback() {
                @Override public void onChoices(List<String> candidates, String draft) {
                    if (candidates != null && !candidates.isEmpty() && stylusOverlayView != null
                            && !stylusOverlayView.isContinuous()) {
                        commit(candidates.get(0));
                        stylusOverlayView.clear();
                    }
                }
                @Override public void onInkChanged(boolean hasInk) { }
                @Override public void onStatus(String message) { }
                @Override public void onConfirm(String value) {
                    if (value != null && !value.isEmpty()) commit(value);
                    if (stylusOverlayView != null) stylusOverlayView.clear();
                }
            });
            stylusOverlayView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    @Override public boolean onStartStylusHandwriting() {
        if (android.os.Build.VERSION.SDK_INT < 33) return false;
        android.view.Window stylusWindow = getStylusHandwritingWindow();
        if (stylusWindow == null) return false;
        if (stylusOverlayView == null) onPrepareStylusHandwriting();
        if (stylusOverlayView == null) return false;
        stylusOverlayView.applyTheme(KeyboardTheme.from(this, isDarkMode()));
        stylusOverlayView.setBackgroundColor(Color.TRANSPARENT);
        stylusWindow.setContentView(stylusOverlayView);
        return true;
    }

    @Override public void onFinishStylusHandwriting() {
        if (stylusOverlayView != null) {
            stylusOverlayView.confirm();
            stylusOverlayView.clear();
        }
        super.onFinishStylusHandwriting();
    }

    @Override public void onComputeInsets(android.inputmethodservice.InputMethodService.Insets out) {
        super.onComputeInsets(out);
        if (keyboardView == null) return;
        if (handwritingShown) {
            if (handwritingFullscreen && isInputViewShown()) {
                // Keep the editor's layout behind the full-screen writing surface.
                out.contentTopInsets = keyboardView.getRootView().getHeight();
            }
        } else if (!isHardwareCompactMode() && KeyboardPlacement.floating(this, getSharedPreferences("settings", MODE_PRIVATE))) {
            int[] position = new int[2]; keyboardView.getLocationInWindow(position);
            int bottom = position[1] + keyboardView.getHeight();
            out.contentTopInsets = bottom; out.visibleTopInsets = bottom;
            out.touchableInsets = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            out.touchableRegion.set(position[0], position[1], position[0] + keyboardView.getWidth(), bottom);
        }
    }

    @Override public boolean onEvaluateFullscreenMode() {
        // Keep the host editor visible when the phone rotates to landscape.
        return false;
    }

    private static boolean sameEditor(EditorInfo left, EditorInfo right) { return EditorSession.sameEditor(left, right); }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        stopVoiceInput();
        hardwareKeyboard.reset();
        selectionEdit.clearTransientState();
        if (restarting && sameEditor(editorSession.info, info) && (!composingSession.isEmpty() || handwritingShown)
                && info.initialSelStart == editorSession.selectionStart && info.initialSelEnd == editorSession.selectionEnd
                && !clipboardSearchDialogOpen) {
            super.onStartInput(info, true); editorSession.info = info; return;
        }
        selectionEdit.clearUndo();
        keyInputOrder.cancelAll();
        if (keyboardView != null) keyboardView.cancelKeyTouches();
        long revision = getSharedPreferences("settings", MODE_PRIVATE).getLong("context_revision", 0);
        if (revision != contextRevision) {
            contextRevision = revision;
            invalidateCandidates();
        }
        stopClipboardListener();
        super.onStartInput(info, restarting);
        logImeLifecycle("start_input restarting=" + restarting);
        editorSession.info = info;
        wordCreationController.clearOnStartInput();
        editorSession.generation++;
        composingSession.pendingTouch = null;
        composingSession.hostHasComposingText = false;
        editorSession.selectionStart = info == null ? -1 : info.initialSelStart;
        editorSession.selectionEnd = info == null ? -1 : info.initialSelEnd;
        composingSession.clearAll();
        previousCommittedWord = "";
        previousPreviousCommittedWord = "";
        lastChineseCommitAt = 0;
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = null;
        int cls = info == null ? 0 : (info.inputType & InputType.TYPE_MASK_CLASS);
        editorSession.layout = classifyEditorLayout(info);
        editorSession.numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;
        editorSession.forcedLayout = editorSession.numeric || "email".equals(editorSession.layout)
                || "uri".equals(editorSession.layout);
        invalidateCandidates();
        editorSession.sensitive = isSensitiveEditor(info);
        boolean clipboardSearchEditor = clipboardSearchDialogOpen && info != null
                && getPackageName().equals(info.packageName) && "offlineime-panel-search".equals(info.privateImeOptions);
        if (clipboardSearchEditor) {
            // Internal keyword search may compose Chinese, but never learns or captures clipboard.
            editorSession.sensitive = false; mode = restoreLastChineseMode(); editorSession.numeric = false; editorSession.forcedLayout = false;
            return;
        }
        if (clipboardSearchDialogOpen && panelSearchRestoresEmoji && clipboardSearchRestorePanel
                && sameEditor(info, clipboardSearchReturnEditorInfo)) {
            if (emojiSearchReturnMode != null) mode = emojiSearchReturnMode;
            return;
        }
        if (clipboardSearchResumePending && clipboardSearchRestorePanel && sameEditor(info, clipboardSearchReturnEditorInfo)) {
            clipboardSearchResumePending = false;
            clipboardSearchRestorePanel = false;
            clipboardSearchReturnEditorInfo = null;
            clipboardShown = !panelSearchRestoresEmoji; emojiShown = panelSearchRestoresEmoji;
            if (emojiShown && emojiSearchReturnMode != null) mode = emojiSearchReturnMode;
            panelSearchRestoresEmoji = false;
            clipboardEnablePromptShown = false;
            return;
        }
        clipboardSearchResumePending = false; clipboardSearchRestorePanel = false; clipboardSearchReturnEditorInfo = null;
        panelSearchRestoresEmoji = false;
        emojiSearchQuery = "";
        englishWordMode = getSharedPreferences(KEYBOARD_STATE_PREFERENCES, MODE_PRIVATE)
                .getBoolean(ENGLISH_WORD_MODE, true);
        mode = editorSession.numeric ? Mode.SYMBOLS
                : "email".equals(editorSession.layout) || "uri".equals(editorSession.layout) ? Mode.ENGLISH
                : cls == InputType.TYPE_CLASS_TEXT ? restoreKeyboardMode() : Mode.ENGLISH;
        if (isOwnWordEditor(info) && "offlineime-word-code".equals(info.privateImeOptions)) {
            mode = Mode.ENGLISH; englishWordMode = false; editorSession.forcedLayout = true;
        }
        toolsShown = false;
        clipboardShown = false;
        clipboardEnablePromptShown = false;
        clipboardClearConfirmShown = false;
        closeHandwriting();
        emojiShown = false;
        symbolsShown = false;
        layoutChooserShown = false;
        candidateExpanded = false;
        clipboardMultiSelect = false;
        clipboardSelection.clear();
        selectionEdit.resetSelectionMode();
        // 数字输入框临时使用符号布局；普通文本框恢复上次主动选择的键盘。
        modeBeforeSymbols = mode == Mode.SYMBOLS ? restoreBeforeNumericMode() : mode;
        modeBeforeEnglish = mode == Mode.ENGLISH ? restoreLastChineseMode() : mode;
        shift = false;
        capsLock = false;
        lastShiftTapUptime = 0;
        candidatePage = 0;
        startClipboardListenerIfNeeded();
    }

    String editorLayoutKind() { return editorSession.layout; }
    boolean hasEditorSelection() { return editorSession.selectionStart >= 0 && editorSession.selectionEnd >= 0 && editorSession.selectionStart != editorSession.selectionEnd; }
    boolean selectionActive() { return selectionEdit.isSelectionMode(); }
    boolean englishWordMode() { return englishWordMode; }
    boolean capsLockEnabled() { return capsLock; }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        ImePerformanceMonitor.inputStarted();
        super.onStartInputView(info, restarting);
        editorSession.info = info;
        updateInputViewLayout();
        if (!clipboardSearchDialogOpen) {
            startClipboardListenerIfNeeded();
            // Some hosts report the view visible only after this lifecycle callback.
            mainHandler.post(this::startClipboardListenerIfNeeded);
        }
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
        selectionEdit.clearTransientState();
        keyInputOrder.cancelAll();
        if (keyboardView != null) keyboardView.cancelKeyTouches();
        stopVoiceInput();
        HandwritingView pad = handwritingInput();
        if (pad != null) pad.cancelPendingConfirmation();
        cancelRenderRefresh();
        super.onFinishInputView(finishingInput);
        logImeLifecycle("finish_input_view finishing=" + finishingInput);
    }

    @Override public void onFinishInput() {
        flushPersonalDataSave();
        stopVoiceInput();
        if (voiceInput != null) voiceInput.scheduleQuickRelease();
        selectionEdit.clearAll();
        cancelRenderRefresh();
        keyInputOrder.cancelAll();
        stopClipboardListener();
        if (clipboardSearchDialogOpen || clipboardSearchResumePending) {
            super.onFinishInput();
            return;
        }
        wordCreationController.clearOnFinishInput(); editorSession.generation++;
        invalidateCandidates();
        composingSession.pathEditor.clear();
        hidePanels();
        composingSession.clearInputOnly();
        previousCommittedWord = "";
        previousPreviousCommittedWord = "";
        lastChineseCommitAt = 0;
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = null;
        editorSession.info = null;
        editorSession.sensitive = false;
        editorSession.selectionStart = -1;
        editorSession.selectionEnd = -1;
        super.onFinishInput();
        startClipboardListenerIfNeeded();
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (keyPreview != null) keyPreview.dismiss();
        if (voiceInput != null) { voiceInput.close(); voiceInput = null; }
        clearVoiceDraft();
        stopVoiceInput(false);
        cancelRenderRefresh();
        keyInputOrder.cancelAll();
        cancelCandidateWork();
        if (settingsPreferences != null) settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        stopClipboardListener();
        if (clipboardHistory != null) clipboardHistory.close();
        if (quickPhrases != null) quickPhrases.removeObserver(quickPhraseObserver);
        if (clipboardUndoExpiry != null) mainHandler.removeCallbacks(clipboardUndoExpiry);
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        clipboardClearBackup = null;
        if (loaderTask != null) loaderTask.cancel(true);
        if (prewarmTask != null) prewarmTask.cancel(true);
        if (contextPrewarmTask != null) contextPrewarmTask.cancel(true);
        if (loaderExecutor != null) loaderExecutor.shutdownNow();
        candidateSession.close();
        flushPersonalDataSave();
        if (dictionary != null) CustomWordStore.IO.execute(dictionary::close);
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
        if (dictionary != null) dictionary.setBlockedWords(blockedWords);
        flushDeferredLearning();
        notifyStorageReadFailure();
        // Keep the small bundled first frame visible until the full candidate
        // query replaces it. Old entries are disabled while that query runs.
        if (!composingSession.isEmpty() && !candidateSession.candidateResults.isEmpty()
                && candidateSession.candidateResultKey.equals(currentCandidateKey())) {
            candidateSession.candidateCache.evictAll();
            composingSession.clearSegmentsCache();
            cancelCandidateWork();
            candidateSession.candidatePendingKey = candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = "";
        } else {
            invalidateCandidates();
        }
        if (keyboardView != null) refresh();
        scheduleDictionaryPrewarm();
    }

    private void scheduleDictionaryPrewarm() { }

    private void updateDictionaryPrewarmForInput() { }

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
        selectionEdit.onUpdateSelection(newSelStart, newSelEnd);
        HandwritingView pad = handwritingInput();
        if (pad != null && editorSession.selectionStart >= 0
                && (newSelStart != editorSession.selectionStart || newSelEnd != editorSession.selectionEnd)) {
            pad.cancelPendingConfirmation();
        }
        if (voiceSegmentCommitInProgress) {
            voiceSelectionStart = newSelStart;
            voiceSelectionEnd = newSelEnd;
            editorSession.selectionStart = newSelStart;
            editorSession.selectionEnd = newSelEnd;
            return;
        }
        if (voiceShown && voiceSelectionStart >= 0 && voiceSelectionEnd >= 0
                && (newSelStart != voiceSelectionStart || newSelEnd != voiceSelectionEnd)) {
            boolean hadPreview = !voicePreview.trim().isEmpty();
            stopVoiceInput(true);
            toastWord(hadPreview && !editorSession.sensitive && !isPrivacyMode()
                    ? "光标已移动，已暂存已识别文字" : "光标已移动，语音输入已取消");
            refresh();
        }
        if (candidateCommitInProgress) {
            editorSession.selectionStart = newSelStart;
            editorSession.selectionEnd = newSelEnd;
            return;
        }
        if (wordCreationController.isActive()) { editorSession.selectionStart = newSelStart; editorSession.selectionEnd = newSelEnd; return; }
        if (!composingSession.isEmpty()) {
            boolean selectionMoved = editorSession.selectionStart >= 0
                    && (newSelStart != editorSession.selectionStart || newSelEnd != editorSession.selectionEnd);
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
        editorSession.selectionStart = newSelStart;
        editorSession.selectionEnd = newSelEnd;
        if (toolsShown) refresh();
    }

    @Override public void onTouchKey(String key, TouchCorrectionModel.Sample sample) {
        composingSession.pendingTouch = sample;
        try { onKey(key); } finally { composingSession.pendingTouch = null; }
    }

    @Override public void onKey(String key) {
        if (key == null || key.isEmpty()) return;
        selectionEdit.onKeyBegin(key);
        if ("语音".equals(key)) { beginVoiceInput(false); return; }
        if ("语音按住开始".equals(key)) { if (!voiceShown) beginVoiceInput(true); return; }
        if ("语音按住取消提示".equals(key)) {
            if (voiceShown && voiceHoldMode && !voiceFinishing) {
                voiceStatus = "松开取消本次语音输入"; refresh();
            }
            return;
        }
        if ("语音按住恢复提示".equals(key)) {
            if (voiceShown && voiceHoldMode && !voiceFinishing) {
                voiceStatus = "松开立即上屏 · 上滑取消"; refresh();
            }
            return;
        }
        if ("语音取消".equals(key)) {
            boolean saveDraft = !voiceHoldMode && voicePreview.trim().length() >= 4;
            stopVoiceInput(saveDraft); refresh(); return;
        }
        if ("语音清空".equals(key)) {
            if (voiceShown && !voiceFinishing && voiceInput != null) {
                voicePreview = "";
                voiceInput.clearRecognized();
                refresh();
            }
            return;
        }
        if ("语音退格".equals(key)) {
            if (voiceShown && !voiceFinishing && voiceInput != null) {
                voiceInput.backspaceRecognized();
            }
            return;
        }
        if ("语音暂停".equals(key)) {
            if (voiceShown && !voiceFinishing && voiceInput != null) {
                voicePaused = !voicePaused;
                voiceInput.setPaused(voicePaused);
                voiceStatus = voicePaused ? "已暂停录音 · 点击继续" : defaultVoiceListeningStatus();
                refresh();
            }
            return;
        }
        if ("语音完成".equals(key)) { finishVoiceInput(false); return; }
        if ("语音完成并发送".equals(key)) { finishVoiceInput(true); return; }
        if ("语音恢复草稿".equals(key)) {
            String draft = activeVoiceDraftPreview();
            clearVoiceDraft();
            if (!draft.isEmpty()) {
                stopVoiceInput(false);
                clearWordContext();
                if (commit(convertGeneratedText(draft))) toastWord("已恢复上次识别文字");
                else toastWord("无法输入识别结果，请重试");
            }
            refresh();
            return;
        }
        if ("语音清除草稿".equals(key)) { clearVoiceDraft(); refresh(); return; }
        if ("隐私模式".equals(key)) {
            android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
            boolean enabled = !p.getBoolean("privacy_mode", false);
            p.edit().putBoolean("privacy_mode", enabled).apply();
            if (enabled) clearVoiceDraft();
            toastWord(enabled ? "隐私模式已开启" : "隐私模式已关闭"); return;
        }
        if ("原文上屏".equals(key)) {
            if (composingSession.isEmpty() || !commit(composingSession.raw())) return;
            composingSession.clearAll(); updateComposing(); return;
        }
        if (!"⇧".equals(key)) lastShiftTapUptime = 0;
        if (wordCreationController.handleKey(key)) return;
        deferredCommitKey = ""; deferredCommitAction = "";
        if ("重输".equals(key) || "中英".equals(key) || "字".equals(key) || "9键".equals(key) || "ABC".equals(key) || "123".equals(key)) wordCreationController.clearAssembly();
        if ("人名模式".equals(key)) {
            wordCreationController.toggleNameMode();
            return;
        }
        if (key.startsWith("编辑音节:")) {
            String[] parts = key.substring("编辑音节:".length()).split(":", 2);
            if (parts.length != 2 || !parts[0].equals(composingSession.raw())) return;
            try {
                int start = Integer.parseInt(parts[1]);
                for (PinyinPathEditor.Segment segment : pinyinSegments()) if (segment.start == start) {
                    composingSession.pathEditor.focus(start); composingSession.cursor = segment.end;
                    refresh(); break;
                }
            } catch (NumberFormatException ignored) { }
            return;
        }
        if (key.startsWith("选择音节:")) {
            String[] parts = key.substring("选择音节:".length()).split(":", 4);
            if (parts.length != 4 || !parts[0].equals(composingSession.raw())) return;
            try {
                int start = Integer.parseInt(parts[1]), end = Integer.parseInt(parts[2]);
                if (start < 0 || end > composingSession.length() || start >= end) return;
                if (isShuangpin()) {
                    if (composingSession.shuangpin.select(parts[0], start, end, parts[3])) {
                        advancePinyinSelection(start, end);
                        candidatePage = 0; updateComposing();
                    }
                    return;
                }
                String spelling = mode == Mode.NINE ? PinyinDictionary.toT9(parts[3]) : parts[3];
                if (!spelling.equals(composingSession.buffer.substring(start, end))) return;
                if (composingSession.pathEditor.select(parts[0], mode == Mode.NINE,
                        start, end, parts[3])) {
                    advancePinyinSelection(start, end);
                    // Choosing a reading changes the lookup, not the insertion
                    // point. Keep appending at the end unless the user moved
                    // the cursor explicitly through the syllable editor.
                    composingSession.lockedPinyinPath = "";
                    composingSession.cursor = Math.max(0, Math.min(composingSession.length(), composingSession.cursor));
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
                composingSession.deleteSegment(segment, mode == Mode.NINE);
                updateComposing();
            }
            return;
        }
        if (key.startsWith("定位拼音:")) {
            try { composingSession.cursor = Math.max(0, Math.min(composingSession.length(), Integer.parseInt(key.substring(5)))); } catch (NumberFormatException ignored) { }
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
        if ((mode == Mode.PINYIN || mode == Mode.NINE)
                && ("'".equals(key) || "分词".equals(key) || "分隔".equals(key))) {
            insertPinyinSeparator();
            return;
        }
        boolean openingNumbers = "123".equals(key) || "符".equals(key);
        if (openingNumbers || "中英".equals(key) || "26键".equals(key) || "ABC".equals(key)
                || "字".equals(key) || "9键".equals(key) || "笔画".equals(key)) {
            if (!composingSession.isEmpty()) {
                boolean finished = openingNumbers && mode == Mode.ENGLISH
                        ? finishEnglishText() : clearComposing(true);
                if (!finished) return;
            }
            hidePanels();
        }
        if (key.startsWith("符号分类:")) {
            if (symbolsShown && keyboardView != null) keyboardView.showSymbols(key.substring(5));
            return;
        }
        if (key.startsWith("符号输入:")) {
            if (symbolsShown) { String symbol = key.substring(5);
                if (!clearComposing(true) || !commitPunctuationOrText(symbol, true)) return;
                if (mayRememberEdit()) {
                    android.content.SharedPreferences p = getSharedPreferences("symbol_recent", MODE_PRIVATE);
                    java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>(); values.add(symbol);
                    String saved = p.getString("items", ""); if (!saved.isEmpty()) java.util.Collections.addAll(values, saved.split("\n"));
                    List<String> recent = new ArrayList<>(values);
                    p.edit().putString("items", String.join("\n", recent.subList(0, Math.min(30, recent.size())))).apply();
                }
                symbolsShown = false; refresh();
            } return;
        }
        if (key.startsWith("粘贴历史")) { pasteHistoryItem(key.substring("粘贴历史".length())); return; }
        if (key.startsWith("手写字:")) {
            HandwritingView pad = handwritingInput();
            if (pad != null) {
                if (commit(convertGeneratedText(key.substring(4)))) pad.clear();
                else toastWord("上屏失败，笔迹已保留，请重试");
            }
            return;
        }
        if (key.startsWith("表情分类:")) {
            emojiSearchQuery = "";
            if (emojiShown && keyboardView != null) keyboardView.selectEmojiCategory(key.substring(5));
            return;
        }
        if (key.startsWith("表情样式:")) {
            if (emojiShown && keyboardView != null) {
                emojiSearchQuery = "";
                keyboardView.setEmojiMarkers("wechat".equals(key.substring(5)));
            }
            return;
        }
        if (key.startsWith("收藏表情:") || key.startsWith("表情前移:") || key.startsWith("表情后移:")) {
            if (!emojiShown || !mayRememberEdit()) return;
            String glyph = key.substring(5);
            android.content.SharedPreferences prefs = getSharedPreferences("emoji", MODE_PRIVATE);
            if (key.startsWith("收藏表情:")) EmojiPreferences.toggleFavorite(prefs, glyph);
            else EmojiPreferences.moveFavorite(prefs, glyph, key.startsWith("表情前移:") ? -1 : 1);
            if (keyboardView != null) keyboardView.refreshEmojiItems();
            return;
        }
        if (key.startsWith("表情:") || key.startsWith("表情肤色:")) {
            boolean skinTone = key.startsWith("表情肤色:");
            String glyph = key.substring(skinTone ? 5 : 3);
            if (WeChatEmojiCatalog.contains(glyph) && !settingsPreferences.getBoolean("emoji_legacy_markers", false)) return;
            if (emojiShown && EmojiCatalog.visible(glyph) && clearComposing(true) && commit(glyph)) {
                if (skinTone && mayRememberEdit())
                    EmojiPreferences.saveTone(getSharedPreferences("emoji", MODE_PRIVATE), EmojiCatalog.get(this), glyph);
                rememberEmoji(glyph);
                if (keyboardView != null && emojiShown) keyboardView.refreshEmojiItems();
            }
            return;
        }
        if (key.startsWith("加入词:")) {
            if (!editorSession.sensitive && canLearn()
                    && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("learn_long_press", true)) {
                dictionary.addCustomWord(learningCode(), key.substring(3),
                        mode == Mode.ENGLISH ? "en" : "*");
                refresh();
            }
            return;
        }
        if (key.startsWith("选择完整路径:")) {
            String[] parts = key.substring("选择完整路径:".length()).split(":", 2);
            if (parts.length == 2 && parts[0].equals(composingSession.raw())) onKey("选择路径:" + parts[1]);
            return;
        }
        if ("解锁当前音节".equals(key)) {
            PinyinPathEditor.Segment focus = focusedPinyinSegment();
            if (focus != null) { if (isShuangpin()) composingSession.shuangpin.unlock(focus.start); else composingSession.pathEditor.unlock(focus.start);
                composingSession.lockedPinyinPath = ""; invalidateCandidates(); refresh(); }
            return;
        }
        if (key.startsWith("选择路径:")) {
            String selected = key.substring(5);
            if (isShuangpin()) {
                if ("自动".equals(selected)) { composingSession.shuangpin.clear(); candidatePage = 0; refresh(); }
                return;
            }
            if ("自动".equals(selected)) {
                composingSession.pathEditor.automatic(); composingSession.lockedPinyinPath = ""; composingSession.cursor = composingSession.length();
            } else {
                composingSession.pathEditor.automatic();
                List<List<String>> chosen = java.util.Collections.singletonList(
                        java.util.Arrays.asList(selected.split("[·']")));
                for (PinyinPathEditor.Segment segment : composingSession.pathEditor.segments(
                        composingSession.raw(), mode == Mode.NINE, ignored -> chosen))
                    composingSession.pathEditor.select(composingSession.raw(), mode == Mode.NINE,
                            segment.start, segment.end, segment.reading);
                composingSession.pathEditor.focus(-1);
                composingSession.lockedPinyinPath = "";
            }
            if (!"自动".equals(selected) && canLearn()) {
                android.content.SharedPreferences usage = getSharedPreferences("pinyin_path_usage", MODE_PRIVATE);
                usage.edit().putInt(selected, Math.min(10000, usage.getInt(selected, 0) + 1))
                        .putLong("time_" + selected, System.currentTimeMillis()).apply();
            }
            candidatePage = 0; refresh(); return;
        }
        if (key.startsWith("屏蔽词:")) {
            String word = key.substring(4);
            getSharedPreferences("blocked_words", MODE_PRIVATE).edit().putBoolean(word, true).apply();
            reloadBlockedWordsCache();
            invalidateCandidates();
            refresh();
            return;
        }
        if (key.startsWith("长按数字:")) { if (clearComposing(true)) commit(key.substring(5)); return; }
        if (key.startsWith("固定:")) { toggleClipboardPin(key.substring(3)); return; }
        if (key.startsWith("删除剪贴板:")) { removeClipboard(key.substring(6)); return; }
        if (key.startsWith("编辑快捷短语:")) {
            QuickPhraseStore.Entry phrase = quickPhrases.find(key.substring("编辑快捷短语:".length()));
            if (phrase != null) showQuickPhraseEditor(phrase.id, phrase.text);
            else toastWord("短语已被删除");
            return;
        }
        if (key.startsWith("输入快捷短语:")) {
            QuickPhraseStore.Entry phrase = quickPhrases.find(key.substring("输入快捷短语:".length()));
            if (phrase != null) { if (clearComposing(true) && commit(phrase.text)) { clipboardShown = false; refresh(); } }
            else { toastWord("短语已被删除"); refresh(); }
            return;
        }
        if (key.startsWith("插入日期时间:")) {
            String id = key.substring("插入日期时间:".length());
            if (!DateTimeInsertion.label(id).isEmpty() && clearComposing(true)) {
                String value = DateTimeInsertion.format(id);
                if (value != null) commit(value);
            }
            return;
        }
        if (key.startsWith("删除快捷短语:")) {
            quickPhrases.remove(key.substring("删除快捷短语:".length()), error -> {
                if (!destroyed && !error.isEmpty()) toastWord(error);
            });
            refresh(); return;
        }
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
            if ("phrases".equals(clipboardFilter)) { showQuickPhrases(); return; }
            clipboardMultiSelect = false;
            clipboardSelection.clear();
            clipboardEnablePromptShown = !getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("clipboard_enabled", true);
            if (!clipboardEnablePromptShown) captureClipboardOnDemand();
            refresh();
            return;
        }
        switch (key) {
            case "分词":
                if (!composingSession.isEmpty()) onCandidateExpand();
                return;
            case "收起键盘":
                // Persist synchronously before Android may stop the IME process.
                // Phone/email/URI layouts are selected automatically and must
                // not replace the user's explicit keyboard choice.
                if (!editorSession.forcedLayout) rememberKeyboardMode(mode);
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
            case "回车手写":
            case "回车":
                HandwritingView confirmPad = handwritingInput();
                if (confirmPad == null || !confirmPad.confirm()) enter();
                return;
            case "英文输入模式":
                if (mode != Mode.ENGLISH) return;
                // Preserve the letters already entered instead of replacing
                // them with a suggested word when switching to direct input.
                if (!finishEnglishText()) return;
                InputConnection englishConnection = getCurrentInputConnection();
                if (englishConnection != null) englishConnection.finishComposingText();
                composingSession.hostHasComposingText = false;
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
                composingSession.clearInputOnly();
                refresh();
                return;
            case "123":
            case "符":
                if (mode == Mode.SYMBOLS) { refresh(); return; }
                modeBeforeSymbols = mode;
                mode = Mode.SYMBOLS;
                if (!editorSession.forcedLayout) rememberKeyboardMode(mode);
                composingSession.clearInputOnly();
                refresh();
                return;
            case "符号":
                boolean openSymbols = !symbolsShown;
                hidePanels(); symbolsShown = openSymbols;
                if (keyboardView != null) { if (symbolsShown) keyboardView.showSymbols(); else refresh(); }
                return;
            case "返回符号": symbolsShown = false; refresh(); return;
            case "重输": composingSession.clearAll();
                candidatePage = 0; candidateExpanded = false;
                updateComposing(); return;
            case "26键": mode = Mode.PINYIN; rememberKeyboardMode(mode); layoutChooserShown = false; composingSession.clearInputOnly(); refresh(); return;
            case "笔画": mode = Mode.STROKE; rememberKeyboardMode(mode); layoutChooserShown = false; composingSession.clearInputOnly(); refresh(); return;
            case "标点": if (!clearComposing(true)) return; modeBeforeSymbols = mode; mode = Mode.SYMBOLS; refresh(); return;
            case "ABC": modeBeforeEnglish = mode == Mode.ENGLISH ? restoreLastChineseMode() : mode; mode = Mode.ENGLISH; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composingSession.clearInputOnly(); refresh(); return;
            case "字": mode = Mode.PINYIN; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composingSession.clearInputOnly(); refresh(); return;
            case "9键": mode = Mode.NINE; rememberKeyboardMode(mode); toolsShown = false; clipboardShown = false; layoutChooserShown = false; composingSession.clearInputOnly(); refresh(); return;
            case "键盘布局":
                boolean openLayouts = !layoutChooserShown; hidePanels(); layoutChooserShown = openLayouts;
                if (keyboardView != null) { if (layoutChooserShown) keyboardView.showLayoutChooser(mode); else refresh(); }
                return;
            case "手写":
                boolean openHandwriting = !handwritingShown;
                if (openHandwriting && !wordCreationController.isActive() && !clearComposing(true)) return;
                hidePanels();
                if (openHandwriting) {
                    handwritingShown = true;
                    updateInputViewLayout();
                }
                refresh();
                return;
            case "切换手写屏幕":
                if (handwritingShown && keyboardView != null) {
                    handwritingFullscreen = !handwritingFullscreen;
                    updateInputViewLayout();
                    keyboardView.showHandwriting();
                    keyboardView.showWordCreationStatus(wordCreationStatus());
                }
                return;
            case "返回手写":
                closeHandwriting();
                refresh();
                return;
            case "手写删除":
                HandwritingView erasePad = handwritingInput();
                if (erasePad != null) {
                    if (erasePad.hasInk()) erasePad.undoLastStroke();
                    else if (!wordCreationController.isActive()) delete();
                }
                return;
            case "清空手写":
                HandwritingView clearPad = handwritingInput();
                if (clearPad != null) clearPad.clear();
                return;
            case "😊":
                boolean openEmoji = !emojiShown; hidePanels(); emojiShown = openEmoji;
                emojiSearchQuery = "";
                if (keyboardView != null) { if (emojiShown) keyboardView.showEmojiPanel(emojiSearchQuery); else refresh(); }
                return;
            case "搜索表情":
                if (emojiShown) showPanelSearchDialog(true);
                return;
            case "清除表情搜索":
                emojiSearchQuery = "";
                if (emojiShown && keyboardView != null) keyboardView.showEmojiPanel(emojiSearchQuery);
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
            case "清除剪贴板搜索":
                clipboardSearchQuery = "";
                refresh();
                return;
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
                if (closeTopPanel()) return;
                if (mode == Mode.SYMBOLS) {
                    mode = modeBeforeSymbols;
                    if (!editorSession.forcedLayout) rememberKeyboardMode(mode);
                    composingSession.clearInputOnly();
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
            case "Tab":
                if (!clearComposing(true)) return;
                sendKey(KeyEvent.KEYCODE_TAB);
                return;
            case "CapsLock":
                capsLock = !capsLock;
                shift = capsLock;
                lastShiftTapUptime = 0;
                refresh();
                return;
            case "展开软键盘":
                hardwareSoftExpanded = !hardwareSoftExpanded;
                updateInputViewLayout();
                refresh();
                return;
            case "停靠键盘":
                snapFloatingToDock();
                return;
            case "←": if (!composingSession.isEmpty()) { composingSession.cursor = Math.max(0, Math.min(composingSession.length(), composingSession.cursor) - 1); refresh(); } else moveCaretBy(-1); return;
            case "→": if (!composingSession.isEmpty()) { composingSession.cursor = Math.min(composingSession.length(), composingSession.cursor + 1); refresh(); } else moveCaretBy(1); return;
            case "↑": if (!clearComposing(true)) return; sendKey(KeyEvent.KEYCODE_DPAD_UP); return;
            case "↓": if (!clearComposing(true)) return; sendKey(KeyEvent.KEYCODE_DPAD_DOWN); return;
            case "行首": if (!clearComposing(true)) return; sendKey(KeyEvent.KEYCODE_MOVE_HOME); return;
            case "行尾": if (!clearComposing(true)) return; sendKey(KeyEvent.KEYCODE_MOVE_END); return;
            case "选择": toggleSelectionMode(); return;
            case "复制": contextAction(android.R.id.copy, KeyEvent.KEYCODE_C); return;
            case "粘贴": contextAction(android.R.id.paste, KeyEvent.KEYCODE_V); return;
            case "剪切": contextAction(android.R.id.cut, KeyEvent.KEYCODE_X); return;
            case "全选": contextAction(android.R.id.selectAll, KeyEvent.KEYCODE_A); return;
            case "撤销":
                if (!selectionEdit.applyUndo(getCurrentInputConnection(), editorSession.generation, false))
                    modifiedKey(KeyEvent.KEYCODE_Z);
                else { composingSession.hostHasComposingText = false; clearComposing(false); previousCommittedWord = previousPreviousCommittedWord = ""; invalidateCandidates(); refresh(); }
                return;
            case "重做":
                if (!selectionEdit.applyUndo(getCurrentInputConnection(), editorSession.generation, true)) modifiedKey(KeyEvent.KEYCODE_Y);
                return;
            case "剪贴板":
                if (clipboardShown && !"phrases".equals(clipboardFilter)) {
                    clipboardShown = false; clipboardEnablePromptShown = false; refresh();
                } else {
                    clipboardFilter = "all";
                    showClipboard();
                }
                return;
            case "快捷短语":
                if (clipboardShown && "phrases".equals(clipboardFilter)) {
                    clipboardShown = false; refresh();
                } else showQuickPhrases();
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
                if (openTools && !clearComposing(true)) return;
                hidePanels(); toolsShown = openTools;
                if (keyboardView != null) { if (toolsShown) keyboardView.showTools(); else refresh(); }
                return;
        }
        if (mode == Mode.STROKE && key.length() == 1
                && ((key.charAt(0) >= '1' && key.charAt(0) <= '5') || key.charAt(0) == '?')) {
            if (composingSession.length() >= 30) return;
            composingSession.buffer.append(key);
            composingSession.cursor = composingSession.length();
            candidatePage = 0;
            updateComposing();
        } else if (mode == Mode.NINE && key.length() == 1 && key.charAt(0) == '0') {
            space();
        } else if (mode == Mode.NINE && key.length() == 1 && key.charAt(0) == '1') {
            if (clearComposing(true)) commit("，");
        } else if (mode == Mode.NINE && key.length() == 1 && key.charAt(0) >= '2' && key.charAt(0) <= '9') {
            if (composingSession.insertNineDigit(key)) {
                candidatePage = 0;
                updateComposing();
            }
        } else if (mode == Mode.PINYIN && isLetter(key)) {
            insertPinyin(key);
        } else if (mode == Mode.ENGLISH && isLetter(key)) {
            String letter = shift ? key.toUpperCase(Locale.ROOT) : key.toLowerCase(Locale.ROOT);
            if (englishWordMode) {
                composingSession.buffer.append(letter);
                composingSession.cursor = composingSession.length();
            } else {
                commit(letter);
            }
            if (!capsLock) shift = false;
            candidatePage = 0;
            if (englishWordMode) updateComposing(); else refresh();
        } else {
            if (clearComposing(true)) {
                commitPunctuationOrText(key, false);
                if (mode == Mode.ENGLISH) refresh();
            }
        }
    }

    private void insertPinyin(String key) {
        if (composingSession.insertPinyin(key)) {
            candidatePage = 0;
            updateComposing();
        }
    }

    private void insertPinyinSeparator() {
        if (composingSession.insertPinyinSeparator(mode, isShuangpin())) {
            candidatePage = 0;
            updateComposing();
        }
    }

    int pinyinCursor() { return composingSession.clampedCursor(); }
    String pinyinRawInput() { return composingSession.raw(); }

    private void deleteSyllable() {
        if (composingSession.deleteSyllable(mode, isShuangpin(), pinyinSegments(), dictionary)) {
            updateComposing();
        }
    }

    @Override public void onCandidate(int index) {
        List<PinyinDictionary.Entry> list = candidates();
        if (index >= 0 && index < list.size()) onCandidateEntry(list.get(index));
    }

    @Override public void onCandidateEntry(PinyinDictionary.Entry selected) {
        if (mode == Mode.ENGLISH && !englishWordMode) return;
        if (wordCreationController.isSaving()) return;
        if (selected == null || isBlocked(selected)) return;
        if (!selected.inputVersion.isEmpty() && !selected.inputVersion.equals(currentCandidateKey())) return;
        if (mode == Mode.PINYIN && !selected.queryText.isEmpty() && !selected.queryText.equals(composingSession.lockedPinyinPath)
                && !composingSession.raw().startsWith(selected.queryText)) return;
        if (wordCreationController.isActive()) { wordCreationController.selectCandidate(selected); return; }
        selectionEdit.clearTransientState();
        String code = learningCode();
        int consumed = (mode == Mode.PINYIN || mode == Mode.NINE) && composingSession.lockedPinyinPath.isEmpty() && selected.consumedLength > 0
                ? Math.min(composingSession.length(), selected.consumedLength) : composingSession.length();
        boolean emojiCandidate = selected.emojiSuggestion;
        InputConnection batch = getCurrentInputConnection();
        EditorUndo.Snapshot undoBefore = mayRememberEdit() ? EditorUndo.read(batch) : null;
        String replacedComposition = composingSession.hostHasComposingText ? composingSession.raw() : null;
        boolean committed = false;
        selectionEdit.setUndoCandidateCommit(true);
        candidateCommitInProgress = true;
        if (mode == Mode.NINE && editorSession.selectionStart >= 0) {
            int committedSelection = editorSession.selectionStart + selected.word.length();
            editorSession.selectionStart = committedSelection;
            editorSession.selectionEnd = committedSelection;
        }
        if (batch != null) batch.beginBatchEdit();
        try {
        if (!commit(selected.word)) {
            if (mode == Mode.NINE && editorSession.selectionStart >= 0) { editorSession.selectionStart -= selected.word.length(); editorSession.selectionEnd = editorSession.selectionStart; }
            return;
        }
        committed = true;
        if (emojiCandidate) { wordCreationController.clearAssembly(); rememberEmoji(selected.word); }
        else {
            wordCreationController.trackOrdinaryWordSelection(selected, consumed);
            learnConfirmedCandidate(selected, code.substring(0, Math.min(code.length(), consumed)));
            if (selected.wordSegments.isEmpty()) rememberWord(selected.originalWord);
            else for (String part : selected.wordSegments) rememberWord(part);
        }
        composingSession.consumePrefix(consumed, mode, isShuangpin());
        candidateExpanded = false; candidatePage = 0;
        updateComposing();
        } finally {
            if (batch != null) batch.endBatchEdit();
            candidateCommitInProgress = false;
            selectionEdit.setUndoCandidateCommit(false);
            if (committed && undoBefore != null && composingSession.isEmpty()) {
                selectionEdit.recordCandidateCommitUndo(undoBefore, batch, selected.word,
                        replacedComposition, editorSession.generation);
            }
        }
    }

    @Override public void onCandidatePage(int delta) {
        int size = CandidatePaging.pageSize(SettingsRepository.integer(settingsPreferences, "candidate_count", 6));
        List<PinyinDictionary.Entry> values = candidates();
        int pages = CandidatePaging.pageCount(values.size(), size);
        candidatePage = Math.max(0, Math.min(pages - 1, candidatePage + delta));
        refresh();
    }

    @Override public void onDismissNextWordPrediction() {
        if (!composingSession.isEmpty() || !shouldShowNextWordPredictions(mode)) return;
        lastChineseCommitAt = 0;
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = null;
        candidatePage = 0;
        invalidateCandidates();
        refresh();
    }

    @Override public void onCandidateExpand() {
        hidePanels();
        candidateExpanded = true;
        candidatePage = 0;
        if (keyboardView != null) keyboardView.showCandidatePage(
                mode, composingSession.raw(), candidates(), candidatePage);
    }

    @Override public void onMode(Mode requested) {
        if (!wordCreationController.isActive() && !composingSession.isEmpty() && !clearComposing(true)) return;
        wordCreationController.clearAssembly();
        if (wordCreationController.isActive()) { wordCreationController.switchMode(requested); return; }
        hidePanels();
        if (requested == Mode.ENGLISH && mode != Mode.ENGLISH) modeBeforeEnglish = mode;
        mode = requested;
        wordCreationController.clearNameMode();
        rememberKeyboardMode(mode);
        composingSession.clearInputOnly();
        refresh();
    }

    private void updateComposing() {
        updateDictionaryPrewarmForInput();
        // T9 digits and five-stroke codes are internal lookup paths. Keep them out of the host
        // field; only the candidate row should expose the result.
        composingSession.syncHostComposing(getCurrentInputConnection(),
                wordCreationController.isActive() || mode == Mode.NINE || mode == Mode.STROKE);
        refresh();
    }

    private List<PinyinDictionary.Entry> candidates() {
        if (destroyed || dictionary == null || editorSession.sensitive
                || mode == Mode.ENGLISH && !englishWordMode) return java.util.Collections.emptyList();
        final boolean creatingWord = isChoosingWordParts();
        final String creationStrokePrefix = wordCreationController.session.strokePrefix;
        if (lastChineseCommitAt > 0 && System.currentTimeMillis() - lastChineseCommitAt > NEXT_WORD_PREDICTION_WINDOW_MS)
            clearWordContext();
        final Mode queryMode = mode;
        final String rawInput = composingSession.raw();
        final CandidateInputSnapshot inputSnapshot = candidateInputSnapshot();
        final String key = inputSnapshot.key;
        if (!key.equals(candidateSession.measuredCandidateKey)) {
            candidateSession.measuredCandidateKey = key; candidateSession.measuredCandidateAt = ImePerformanceMonitor.start(); candidateSession.measuredCandidateDrawn = false;
        }
        // Repaints and repeated readiness checks must not redo parsing or launch another generation.
        if (key.equals(candidateSession.candidatePendingKey)
                || key.equals(candidateSession.candidateResultKey) && key.equals(candidateSession.candidateCompleteKey)) return candidateSession.candidateResults;
        composingSession.touchCorrection.sync(rawInput);
        if (isShuangpin()) composingSession.shuangpin.sync(rawInput);
        final PinyinPathEditor.Query constraint = inputSnapshot.constraint;
        final boolean emptyQuery = composingSession.isEmpty();
        final boolean nextWordPrediction = !creatingWord && emptyQuery && shouldShowNextWordPredictions(queryMode);

        if (nextWordPrediction) {
            cancelCandidateWork();
            List<PinyinDictionary.Entry> predictions = dictionary.predict(
                    previousPreviousCommittedWord, previousCommittedWord, 3);
            candidateSession.candidateResults = bindCandidateVersion(displayCandidates(queryMode,
                    filterBlocked(filterChinesePredictions(predictions)), rawInput), key);
            candidateSession.candidateResultKey = candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = key;
            candidateSession.candidatePendingKey = "";
            candidateSession.candidateDisplayMode = queryMode;
            candidateSession.candidateDisplayInput = rawInput;
            return candidateSession.candidateResults;
        }

        if (emptyQuery) {
            cancelCandidateWork();
            candidateSession.candidateResultKey = candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = key;
            candidateSession.candidatePendingKey = "";
            candidateSession.candidateResults = java.util.Collections.emptyList();
            candidateSession.candidateDisplayMode = queryMode;
            candidateSession.candidateDisplayInput = rawInput;
            return candidateSession.candidateResults;
        }

        CandidateSession.Frame cachedFrame = candidateSession.candidateCache.get(key);
        if (cachedFrame != null) {
            cancelCandidateWork();
            candidateSession.candidateResults = cachedFrame.entries;
            candidateSession.candidateResultKey = candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = key;
            candidateSession.candidatePendingKey = "";
            candidateSession.candidateDisplayMode = queryMode;
            candidateSession.candidateDisplayInput = rawInput;
            return cachedFrame.entries;
        }

        // The reading path already follows the new input. Never display words
        // from the previous query beside it while this query is still pending.
        candidateSession.candidateResults = java.util.Collections.emptyList();
        candidateSession.candidateResultKey = "";
        candidateSession.candidateFirstFrameKey = "";
        candidateSession.candidateDisplayMode = queryMode;
        candidateSession.candidateDisplayInput = rawInput;
        candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = "";
        // The engine maps its lexicon within milliseconds; onDictionaryReady refreshes.
        if (!dictionary.isLoaded()) return candidateSession.candidateResults;

        final CandidateLookup lookup = candidateLookup(queryMode, rawInput, constraint, creatingWord, wordCreationController.isNameMode());
        final int fullLimit = fullLimitFor(lookup);
        if (candidateSession.queries == null) {
            candidateSession.candidateResults = bindCandidateVersion(
                    displayCandidates(queryMode, runLookup(lookup, creationStrokePrefix, fullLimit).entries, rawInput, lookup.nativeTraditional), key);
            candidateSession.candidateResultKey = candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = key;
            candidateSession.candidatePendingKey = "";
            return candidateSession.candidateResults;
        }

        if (!key.equals(candidateSession.candidatePendingKey)) {
            cancelCandidateWork();
            candidateSession.candidatePendingKey = key;
            CandidateQueryCoordinator.Ticket ticket = candidateSession.queries.next();
            android.os.CancellationSignal cancellation = ticket.cancellation;
            long requestedAt = candidateSession.measuredCandidateAt;
            final int firstLimit = lookup.creating ? fullLimit : Math.min(24, fullLimit);
            // English and stroke queries already scan and rank all matches before applying the limit.
            final boolean rankOnce = queryMode == Mode.ENGLISH || queryMode == Mode.STROKE;
            candidateSession.queries.submit(ticket, () -> {
                QueryWork.begin(cancellation, ticket.cancelGen);
                try {
                    ImePerformanceMonitor.stage(this, "queue_wait", requestedAt, 0, queryMode == Mode.NINE);
                    // A changed/deleted personal dictionary must be reloaded before any frame becomes selectable.
                    dictionary.reloadManagedWords();
                    QueryWork.check();
                    long started = ImePerformanceMonitor.start();
                    PinyinDictionary.LookupResult result = runLookup(lookup, creationStrokePrefix,
                            rankOnce ? fullLimit : firstLimit);
                    List<PinyinDictionary.Entry> found = result.entries;
                    ImePerformanceMonitor.stage(this, "lookup", started, found.size(), queryMode == Mode.NINE);
                    QueryWork.check();
                    boolean needsSupplement = fullLimit > firstLimit
                            && (rankOnce ? found.size() > firstLimit : result.mayHaveMore);
                    List<PinyinDictionary.Entry> first = rankOnce && needsSupplement
                            ? found.subList(0, firstLimit) : found;
                    final List<PinyinDictionary.Entry> ready = bindCandidateVersion(
                            displayCandidates(queryMode, first, rawInput, lookup.nativeTraditional), key);
                    if (needsSupplement) {
                        ImePerformanceMonitor.fastCandidates(this, started, ready.size(), queryMode == Mode.NINE);
                    } else {
                        ImePerformanceMonitor.query(this, started, ready.size(), queryMode == Mode.NINE);
                        QueryWork.publishTimings(this, ready.size(), queryMode == Mode.NINE);
                    }
                    if (!needsSupplement || !ready.isEmpty()) {
                        publishCandidateFrame(ticket, key, rawInput, queryMode, ready, !needsSupplement, requestedAt);
                    }
                    if (needsSupplement && !destroyed && candidateSession.queries.isCurrent(ticket)) {
                        candidateSession.queries.submitSupplement(ticket, () -> {
                            QueryWork.begin(cancellation, ticket.cancelGen);
                            try {
                                QueryWork.check();
                                long fullStarted = ImePerformanceMonitor.start();
                                List<PinyinDictionary.Entry> all = rankOnce ? found
                                        : runLookup(lookup, creationStrokePrefix, fullLimit).entries;
                                QueryWork.check();
                                final List<PinyinDictionary.Entry> fullReady = bindCandidateVersion(
                                        displayCandidates(queryMode, all, rawInput, lookup.nativeTraditional), key);
                                ImePerformanceMonitor.query(this, fullStarted, fullReady.size(), queryMode == Mode.NINE);
                                QueryWork.publishTimings(this, fullReady.size(), queryMode == Mode.NINE);
                                publishCandidateFrame(ticket, key, rawInput, queryMode, fullReady, true, requestedAt);
                            } catch (android.os.OperationCanceledException ignored) {
                                // Superseded by the next key.
                            } finally { QueryWork.end(); }
                        });
                    }
                } catch (android.os.OperationCanceledException ignored) {
                    // The next key supersedes this query.
                } finally { QueryWork.end(); }
            });
        }
        return candidateSession.candidateResults;
    }

    /** Everything one native lookup needs, captured on the main thread. */
    private static final class CandidateLookup {
        final Mode mode;
        final String raw, constraint, previous2, previous;
        final int flags;
        final boolean creating, unavailable, nativeTraditional;
        final String[] touches;
        CandidateLookup(Mode mode, String raw, String constraint, String previous2, String previous,
                        int flags, boolean creating, boolean unavailable, boolean nativeTraditional, String[] touches) {
            this.mode = mode; this.raw = raw; this.constraint = constraint;
            this.previous2 = previous2; this.previous = previous; this.flags = flags;
            this.creating = creating; this.unavailable = unavailable;
            this.nativeTraditional = nativeTraditional;
            this.touches = touches;
        }
    }

    private CandidateLookup candidateLookup(Mode queryMode, String rawInput,
            PinyinPathEditor.Query constraint, boolean creatingWord, boolean names) {
        String encoded = "";
        boolean unavailable = false;
        if (queryMode == Mode.PINYIN && isShuangpin()) {
            encoded = composingSession.shuangpin.constraint(rawInput);
            unavailable = encoded.isEmpty();
        } else if (constraint != null && (queryMode == Mode.PINYIN || queryMode == Mode.NINE)) {
            encoded = constraint.encoded();
        }
        boolean context = !editorSession.sensitive && (queryMode == Mode.PINYIN || queryMode == Mode.NINE);
        int flags = dictionary.flags(queryMode, creatingWord, names);
        boolean nativeTraditional = outputTraditional && (queryMode == Mode.PINYIN || queryMode == Mode.NINE);
        if (nativeTraditional) flags |= NativeEngine.FLAG_TRADITIONAL;
        String[] touches = null;
        if (context && queryMode == Mode.PINYIN && !isShuangpin() && !creatingWord && constraint == null) {
            if (spellingCorrectionEnabled)
                flags |= NativeEngine.FLAG_SPELLING_CORRECTION;
            if (touchCorrectionEnabled) {
                List<TouchCorrectionModel.Variant> variants = composingSession.touchCorrection.variants(rawInput);
                if (!variants.isEmpty()) {
                    touches = new String[variants.size()];
                    for (int i = 0; i < variants.size(); i++) {
                        TouchCorrectionModel.Variant variant = variants.get(i);
                        touches[i] = variant.penalty + ":" + variant.input;
                    }
                }
            }
        }
        return new CandidateLookup(queryMode, rawInput, encoded,
                context ? previousPreviousCommittedWord : "", context ? previousCommittedWord : "",
                flags, creatingWord, unavailable, nativeTraditional, touches);
    }

    private static int fullLimitFor(CandidateLookup lookup) {
        if (lookup.mode == Mode.STROKE) return 256;
        return lookup.creating ? 512 : isSingleSyllablePinyinLookup(lookup) ? 1024 : 256;
    }

    private static boolean isSingleSyllablePinyinLookup(CandidateLookup lookup) {
        if (lookup.mode != Mode.PINYIN || lookup.raw == null || lookup.raw.isEmpty()) return false;
        if (lookup.constraint == null || lookup.constraint.isEmpty()) {
            return PinyinPathEditor.isCompleteReading(lookup.raw.toLowerCase(java.util.Locale.ROOT));
        }
        return lookup.constraint.indexOf(NativeEngine.UNIT) < 0
                && (lookup.constraint.startsWith("=") || lookup.constraint.startsWith("|"));
    }

    /** Runs on a candidate worker lane or, without one, synchronously. */
    private List<PinyinDictionary.Entry> runLookup(CandidateLookup lookup, String creationStrokes) {
        return runLookup(lookup, creationStrokes, fullLimitFor(lookup)).entries;
    }

    private PinyinDictionary.LookupResult runLookup(CandidateLookup lookup, String creationStrokes, int limit) {
        if (lookup.mode == Mode.STROKE) {
            List<PinyinDictionary.Entry> strokes = StrokeInputDictionary.get(this).suggest(lookup.raw, outputTraditional, limit);
            return new PinyinDictionary.LookupResult(filterBlocked(strokes), false);
        }
        if (lookup.unavailable || lookup.mode == Mode.SYMBOLS) {
            return new PinyinDictionary.LookupResult(java.util.Collections.emptyList(), false);
        }
        PinyinDictionary.LookupResult result = dictionary.lookup(lookup.mode, lookup.raw, lookup.constraint,
                lookup.previous2, lookup.previous, lookup.flags, limit, lookup.touches);
        List<PinyinDictionary.Entry> found = filterBlocked(result.entries);
        if (lookup.creating) found = wordCreationController.filterCreationStrokes(found, creationStrokes);
        // Nine-key alias matches have no reading and can expand to any saved text.
        else if (lookup.mode == Mode.NINE) found.removeIf(entry -> !entry.pronunciation.isEmpty() && !containsHan(entry.word));
        return new PinyinDictionary.LookupResult(found, result.mayHaveMore);
    }

    private void publishCandidateFrame(CandidateQueryCoordinator.Ticket ticket, String key, String raw,
            Mode queryMode, List<PinyinDictionary.Entry> entries, boolean complete, long requestedAt) {
        mainHandler.post(() -> {
            if (destroyed || ticket.cancellation.isCanceled() || !candidateSession.queries.isCurrent(ticket)
                    || !key.equals(currentCandidateKey())) return;
            if (!complete && key.equals(candidateSession.candidateCompleteKey)) return;
            boolean firstSelectable = !key.equals(candidateSession.candidateSelectableKey);
            List<PinyinDictionary.Entry> published = entries;
            candidateSession.candidateResults = published; candidateSession.candidateResultKey = candidateSession.candidateSelectableKey = key;
            candidateSession.candidateDisplayMode = queryMode; candidateSession.candidateDisplayInput = raw;
            if (!complete && firstSelectable && !entries.isEmpty()) {
                candidateSession.candidateFirstFrameKey = key;
                candidateSession.candidateFirstFrameAt = android.os.SystemClock.uptimeMillis();
            }
            if (complete) {
                candidateSession.candidateCompleteKey = key; candidateSession.candidatePendingKey = "";
                candidateSession.candidateCache.put(key, new CandidateSession.Frame(raw, isShuangpin(), published));
                ImePerformanceMonitor.candidateFrameCompleted(raw);
            }
            composingSession.clearSegmentsCache();
            refresh();
            if (complete || firstSelectable)
                ImePerformanceMonitor.stage(this, complete ? "candidate_ready" : "first_selectable",
                        requestedAt, entries.size(), queryMode == Mode.NINE);
            if (key.equals(deferredCommitKey)) {
                String action = deferredCommitAction; deferredCommitKey = ""; deferredCommitAction = "";
                if ("space".equals(action)) space(); else if ("enter".equals(action)) enter();
            }
        });
    }
    void onCandidateFrameDrawn(List<PinyinDictionary.Entry> entries) {
        if (candidateSession.measuredCandidateDrawn || entries == null || entries.isEmpty() || candidateSession.measuredCandidateAt == 0) return;
        String version = entries.get(0).inputVersion;
        if (!version.equals(candidateSession.measuredCandidateKey) || !version.equals(candidateSession.candidateSelectableKey)) return;
        candidateSession.measuredCandidateDrawn = true;
        ImePerformanceMonitor.stage(this, "first_visible", candidateSession.measuredCandidateAt, entries.size(), mode == Mode.NINE);
    }

    private static final class CandidateInputSnapshot {
        final long editorGeneration, settingsRevision, pathRevision, touchRevision, doubleRevision;
        final long lastCommit, draftRevision, assemblyRevision;
        final int assemblySize;
        final Mode mode;
        final String raw, locked, previous, previousPrevious, usageKey, key;
        final boolean nextPrediction, doublePinyin, names, englishWord, draft, expanded;
        final PinyinPathEditor.Query constraint;

        CandidateInputSnapshot(OfflineImeService service, String raw,
                               PinyinPathEditor.Query constraint, String usageKey, String key,
                               boolean nextPrediction, boolean doublePinyin) {
            editorGeneration = service.editorSession.generation; settingsRevision = service.settingsRevision;
            pathRevision = service.composingSession.pathEditor.revision(); touchRevision = service.composingSession.touchCorrection.revision();
            doubleRevision = service.composingSession.shuangpin.revision(); mode = service.mode;
            this.raw = raw; this.constraint = constraint; this.usageKey = usageKey; this.key = key;
            locked = service.composingSession.lockedPinyinPath; previous = service.previousCommittedWord;
            previousPrevious = service.previousPreviousCommittedWord; lastCommit = service.lastChineseCommitAt;
            this.nextPrediction = nextPrediction; this.doublePinyin = doublePinyin;
            names = service.wordCreationController.isNameMode(); englishWord = service.englishWordMode; draft = service.wordCreationController.session.active;
            draftRevision = service.wordCreationController.session.revision; assemblySize = service.wordCreationController.ordinaryWordAssembly.parts.size();
            assemblyRevision = service.wordCreationController.ordinaryWordAssembly.revision;
            expanded = service.isChoosingWordParts() && service.candidateExpanded;
        }

        boolean matches(OfflineImeService service, boolean next, boolean doubleMode) {
            return editorGeneration == service.editorSession.generation && settingsRevision == service.settingsRevision
                    && pathRevision == service.composingSession.pathEditor.revision() && touchRevision == service.composingSession.touchCorrection.revision()
                    && doubleRevision == service.composingSession.shuangpin.revision() && mode == service.mode
                    && raw.contentEquals(service.composingSession.buffer) && locked.equals(service.composingSession.lockedPinyinPath)
                    && previous.equals(service.previousCommittedWord)
                    && previousPrevious.equals(service.previousPreviousCommittedWord)
                    && lastCommit == service.lastChineseCommitAt && nextPrediction == next && doublePinyin == doubleMode
                    && names == service.wordCreationController.isNameMode() && englishWord == service.englishWordMode
                    && draft == service.wordCreationController.session.active && draftRevision == service.wordCreationController.session.revision
                    && assemblySize == service.wordCreationController.ordinaryWordAssembly.parts.size()
                    && assemblyRevision == service.wordCreationController.ordinaryWordAssembly.revision
                    && expanded == (service.isChoosingWordParts() && service.candidateExpanded);
        }
    }

    private CandidateInputSnapshot candidateInputSnapshot() {
        boolean nextPrediction = composingSession.isEmpty() && shouldShowNextWordPredictions(mode);
        boolean doublePinyin = isShuangpin();
        CandidateInputSnapshot cached = candidateInputSnapshot;
        if (cached != null && cached.matches(this, nextPrediction, doublePinyin)) return cached;
        String raw = composingSession.raw();
        composingSession.touchCorrection.sync(raw);
        PinyinPathEditor.Query constraint = pathQuery();
        String query = constraint != null ? constraint.key
                : (mode == Mode.PINYIN || mode == Mode.NINE) && !composingSession.lockedPinyinPath.isEmpty()
                ? composingSession.lockedPinyinPath : raw;
        String usageKey = personalUsageKey(mode, constraint, raw);
        String key = editorSession.generation + ":" + settingsRevision + ":" + mode.name() + ':' + raw + ':' + query + ':' + usageKey
                + ':' + previousPreviousCommittedWord + ':' + previousCommittedWord + ':'
                + lastChineseCommitAt + ':' + nextPrediction + ":names=" + wordCreationController.isNameMode() + ":touch=" + composingSession.touchCorrection.signature()
                + ":double=" + (doublePinyin ? composingSession.shuangpin.signature(raw) : "")
                + ":englishWord=" + englishWordMode + ":draft=" + wordCreationController.session.active + ":" + wordCreationController.session.revision
                + ":wordParts=" + wordCreationController.ordinaryWordAssembly.parts.size() + ":" + wordCreationController.ordinaryWordAssembly.revision
                + ":expanded=" + (isChoosingWordParts() && candidateExpanded);
        return candidateInputSnapshot = new CandidateInputSnapshot(this, raw, constraint, usageKey, key,
                nextPrediction, doublePinyin);
    }

    private String currentCandidateKey() { return candidateInputSnapshot().key; }

    private static boolean expiredWordPriority(List<PinyinDictionary.Entry> entries, long now) {
        for (PinyinDictionary.Entry entry : entries)
            if (entry.newWordPriorityUntil > 0 && entry.newWordPriorityUntil <= now
                    || entry.recentWordPriorityUntil > 0 && entry.recentWordPriorityUntil <= now) return true;
        return false;
    }

    private List<PinyinDictionary.Entry> filterBlocked(List<PinyinDictionary.Entry> source) {
        java.util.Set<String> blocked = blockedWords;
        if (blocked.isEmpty() || source.isEmpty()) return source;
        java.util.ArrayList<PinyinDictionary.Entry> out = new java.util.ArrayList<>(source.size());
        for (PinyinDictionary.Entry entry : source) if (!isBlocked(blocked, entry)) out.add(entry);
        return out;
    }

    private boolean isBlocked(PinyinDictionary.Entry entry) {
        return isBlocked(blockedWords, entry);
    }

    private static boolean isBlocked(java.util.Set<String> blocked, PinyinDictionary.Entry entry) {
        if (blocked.isEmpty()) return false;
        return blocked.contains(entry.originalWord)
                || (!java.util.Objects.equals(entry.originalWord, entry.word) && blocked.contains(entry.word));
    }

    private List<PinyinDictionary.Entry> filterChinesePredictions(List<PinyinDictionary.Entry> source) {
        java.util.ArrayList<PinyinDictionary.Entry> out = new java.util.ArrayList<>();
        if (source == null) return out;
        for (PinyinDictionary.Entry entry : source) if (containsHan(entry.word)) out.add(entry);
        return out;
    }

    private List<PinyinDictionary.Entry> displayCandidates(
            Mode queryMode, List<PinyinDictionary.Entry> entries, String rawQuery) {
        return displayCandidates(queryMode, entries, rawQuery, false);
    }

    private List<PinyinDictionary.Entry> displayCandidates(
            Mode queryMode, List<PinyinDictionary.Entry> entries, String rawQuery, boolean nativeTraditionalDone) {
        List<PinyinDictionary.Entry> cased = queryMode == Mode.ENGLISH
                ? preserveEnglishCase(entries, rawQuery) : entries;
        boolean traditional = outputTraditional && !nativeTraditionalDone;
        boolean mixedCase = queryMode == Mode.PINYIN && rawQuery != null
                && !rawQuery.isEmpty() && Character.isUpperCase(rawQuery.charAt(0));
        PinyinDictionary.Entry leader = entries.isEmpty() ? null : entries.get(0);
        if (!traditional && !mixedCase) return withEmojiCandidates(queryMode, rawQuery, cased, leader);
        String[] replacements = null;
        if (traditional) {
            ArrayList<String> hanWords = new ArrayList<>();
            for (PinyinDictionary.Entry entry : cased) {
                QueryWork.check();
                if (containsHan(entry.word)) hanWords.add(entry.word);
            }
            if (!hanWords.isEmpty()) {
                replacements = HanConverter.get(this).toTraditionalBatch(hanWords.toArray(new String[0]));
            }
        }
        if (replacements == null && !mixedCase) return withEmojiCandidates(queryMode, rawQuery, cased, leader);
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>(cased.size());
        int hanIndex = 0;
        for (PinyinDictionary.Entry entry : cased) {
            QueryWork.check();
            String word = entry.word;
            if (replacements != null && containsHan(word)) {
                String replacement = replacements[hanIndex++];
                if (replacement != null) word = replacement;
            } else if (mixedCase && word.matches("[A-Za-z][A-Za-z'.-]*")) {
                word = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
            }
            result.add(entry.withDisplayWord(word));
        }
        return withEmojiCandidates(queryMode, rawQuery, result, leader);
    }

    private List<PinyinDictionary.Entry> withEmojiCandidates(Mode queryMode, String query,
            List<PinyinDictionary.Entry> words, PinyinDictionary.Entry leader) {
        if ((queryMode != Mode.PINYIN && queryMode != Mode.NINE) || query == null || query.isEmpty()
                || leader == null || !containsHan(leader.originalWord)
                || !emojiCandidatesEnabled
                || editorSession.sensitive || clipboardSearchDialogOpen || wordCreationController.isActive()) return words;
        String[] offers = dictionary.emojiSuggestions(leader.originalWord);
        for (int i = leader.wordSegments.size() - 1; (offers == null || offers.length == 0) && i >= 0; i--)
            offers = dictionary.emojiSuggestions(leader.wordSegments.get(i));
        if (offers == null || offers.length == 0) return words;
        EmojiCatalog catalog = EmojiCatalog.get(this);
        android.content.SharedPreferences prefs = getSharedPreferences("emoji", MODE_PRIVATE);
        List<String> visible = new ArrayList<>(java.util.Arrays.asList(offers));
        visible.removeIf(glyph -> !EmojiCatalog.visible(glyph));
        if (visible.isEmpty()) return words;
        boolean personalize = mayRememberEdit();
        if (personalize) {
            java.util.Map<String, Integer> recent = new java.util.HashMap<>();
            for (String glyph : EmojiPreferences.recent(prefs))
                recent.putIfAbsent(catalog.base(glyph), recent.size());
            visible.sort(java.util.Comparator.comparingInt(glyph -> recent.getOrDefault(glyph, Integer.MAX_VALUE)));
        }
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>(words);
        int first = Math.min(2, words.size()), insert = first;
        for (String glyph : visible) {
            String value = personalize ? EmojiPreferences.preferred(prefs, catalog, glyph) : glyph;
            if (result.stream().anyMatch(entry -> value.equals(entry.word))) continue;
            PinyinDictionary.Entry item = new PinyinDictionary.Entry(value, 0, PinyinDictionary.TIER_COMPOSED);
            item.emojiSuggestion = true;
            result.add(insert++, item);
            if (insert - first == 2) break;
        }
        return result;
    }

    private String learningCode() {
        if (isShuangpin()) {
            List<ShuangpinInput.Path> paths = composingSession.shuangpin.paths(composingSession.raw());
            if (!paths.isEmpty()) return paths.get(0).query;
        }
        if (!composingSession.lockedPinyinPath.isEmpty()) return composingSession.lockedPinyinPath;
        return composingSession.raw();
    }

    private String personalUsageKey(Mode forMode) {
        if (forMode == Mode.ENGLISH) return "en";
        if (forMode == Mode.STROKE) return "stroke";
        return personalUsageKey(forMode, pathQuery(), composingSession.raw());
    }

    private String personalUsageKey(Mode forMode, PinyinPathEditor.Query constraint, String raw) {
        if (forMode == Mode.ENGLISH) return "en";
        if (forMode == Mode.STROKE) return "stroke";
        if (constraint != null) return (forMode == Mode.NINE ? "9:" : "26:") + "syllables:" + constraint.key;
        if (forMode == Mode.NINE) {
            return composingSession.lockedPinyinPath.isEmpty()
                    ? "9:digits:" + raw
                    : "9:path:" + composingSession.lockedPinyinPath;
        }
        if (forMode == Mode.PINYIN) {
            if (isShuangpin()) return "26:xiaohe";
            return composingSession.lockedPinyinPath.isEmpty()
                    ? "26:auto:" + raw
                    : "26:path:" + composingSession.lockedPinyinPath;
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
            result.add(entry.withDisplayWord(word));
        }
        return result;
    }

    private boolean shouldShowNextWordPredictions(Mode forMode) {
        return (forMode == Mode.PINYIN || forMode == Mode.NINE)
                && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("next_word_prediction", true)
                && dictionary != null && dictionary.isUserDataReady() && lastChineseCommitAt > 0
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
            if (!destroyed && composingSession.isEmpty() && !shouldShowNextWordPredictions(mode)) {
                invalidateCandidates();
                refresh();
            }
        };
        mainHandler.postDelayed(nextWordPredictionExpiry, NEXT_WORD_PREDICTION_WINDOW_MS + 50L);
    }

    private void rememberWord(String word) {
        if (!canLearn() || word == null || word.isEmpty()) return;
        if (!word.matches("[\\p{IsHan}]+")) { clearWordContext(); return; }
        if (lastChineseCommitAt > 0 && System.currentTimeMillis() - lastChineseCommitAt > NEXT_WORD_PREDICTION_WINDOW_MS)
            clearWordContext();
        if (!previousCommittedWord.isEmpty() && dictionary != null) {
            dictionary.learnContext(previousPreviousCommittedWord, previousCommittedWord, word);
            schedulePersonalDataSave();
        }
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

    private void clearWordContext() {
        previousCommittedWord = previousPreviousCommittedWord = "";
        lastChineseCommitAt = 0;
        if (nextWordPredictionExpiry != null) mainHandler.removeCallbacks(nextWordPredictionExpiry);
        nextWordPredictionExpiry = null;
    }

    private void learnConfirmedCandidate(PinyinDictionary.Entry selected, String fallbackCode) {
        if (mode == Mode.STROKE || !learningAllowed()) return;
        String reading = selected.pronunciation.isEmpty() ? fallbackCode : selected.pronunciation;
        if (reading.isEmpty() || selected.matchTier == PinyinDictionary.TIER_FUZZY && selected.pronunciation.isEmpty()) return;
        boolean assembled = selected.matchTier == PinyinDictionary.TIER_COMPOSED && selected.wordSegments.size() > 1;
        if (selected.matchTier == PinyinDictionary.TIER_COMPOSED && !assembled) return;
        if (assembled) {
            try { reading = CustomWordStore.reading(reading, selected.originalWord); }
            catch (IllegalArgumentException invalid) { return; }
        }
        queueWordLearning(reading, selected.originalWord, personalUsageKey(mode),
                selected.matchTier == PinyinDictionary.TIER_FUZZY || assembled
                        ? PinyinDictionary.TIER_EXACT_FULL : selected.matchTier, assembled);
    }

    KeyButton.DeleteAction beginDeleteRun() {
        return selectionEdit.beginDeleteRun(() -> editorSession.generation, () -> onKey("⌫"));
    }

    private void delete() {
        if (wordCreationController.isActive() && composingSession.isEmpty()) {
            wordCreationController.undoSelection();
            return;
        }
        if (!composingSession.isEmpty()) {
            if (composingSession.deleteBackward(mode)) {
                if (composingSession.isEmpty()) wordCreationController.clearAssembly();
                updateComposing();
            }
        } else {
            selectionEdit.deleteFromEditor(getCurrentInputConnection(), mayRememberEdit(),
                    hasEditorSelection(), editorSession.generation,
                    () -> composingSession.hostHasComposingText = false,
                    this::afterEditorDelete);
        }
    }

    private void deleteWord() {
        if (!composingSession.isEmpty()) {
            composingSession.clearInputOnly();
            updateComposing();
            return;
        }
        selectionEdit.deleteWordFromEditor(getCurrentInputConnection(), mayRememberEdit(),
                editorSession.generation,
                () -> composingSession.hostHasComposingText = false,
                this::afterEditorDelete);
    }

    boolean mayRememberEdit() {
        return !editorSession.sensitive && !clipboardSearchDialogOpen && !privacyMode;
    }

    private void afterEditorDelete() {
        previousCommittedWord = previousPreviousCommittedWord = "";
        lastChineseCommitAt = 0;
        invalidateCandidates(); refresh();
    }

    private boolean finishEnglishText() {
        return composingSession.isEmpty() || (commit(composingSession.raw()) && clearComposing(false));
    }

    private void space() {
        space(true);
    }

    private void space(boolean allowLiteralSpace) {
        if (wordCreationController.isActive() && composingSession.isEmpty()) { wordCreationController.showCreationMenu(); return; }
        if (composingSession.isEmpty()) { commit(" "); return; }
        if (deferCandidateCommit(allowLiteralSpace ? "space" : "enter")) return;
        List<PinyinDictionary.Entry> list = candidatesForCommit();
        if (list.isEmpty() && mode == Mode.NINE) { toastWord("暂无候选，请继续输入或修改拼音"); return; }
        if (list.isEmpty()) {
            if (!commit(composingSession.raw())) return;
            composingSession.clearAll(); refresh();
        } else {
            int pendingLength = composingSession.length();
            onCandidateEntry(list.get(0));
            if (composingSession.length() == pendingLength) return;
        }
        if (allowLiteralSpace && !wordCreationController.isActive()
                && "space".equals(getSharedPreferences("settings", MODE_PRIVATE).getString("space_behavior", "select"))) {
            commit(" "); updateComposing();
        }
    }

    private void enter() {
        // Confirm the visible composition first. Sending requires a subsequent Enter.
        if (!wordCreationController.isActive() && !composingSession.isEmpty()) { space(false); return; }
        if (wordCreationController.isActive()) {
            if (!composingSession.isEmpty()) space(false);
            else wordCreationController.showCreationMenu();
            return;
        }
        if (!clearComposing(true)) return;
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        int action = editorSession.info == null ? EditorInfo.IME_ACTION_NONE
                : (editorSession.info.imeOptions & EditorInfo.IME_MASK_ACTION);
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
                && (editorSession.info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            if (!connection.performEditorAction(action)) connection.commitText("\n", 1);
        } else {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private boolean deferCandidateCommit(String action) {
        if (candidateSession.queries == null || dictionary == null || !dictionary.isLoaded() || editorSession.sensitive) return false;
        String key = currentCandidateKey();
        if (key.equals(candidateSession.candidateSelectableKey) && key.equals(candidateSession.candidateResultKey)) return false;
        candidates();
        if (key.equals(candidateSession.candidateSelectableKey) && key.equals(candidateSession.candidateResultKey)) return false;
        deferredCommitKey = key; deferredCommitAction = action;
        return true;
    }

    String editorActionLabel() {
        if (!composingSession.isEmpty()) return "确认";
        if (editorSession.info != null && (editorSession.info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "换行";
        int action = editorSession.info == null ? EditorInfo.IME_ACTION_NONE
                : (editorSession.info.imeOptions & EditorInfo.IME_MASK_ACTION);
        switch (action) {
            case EditorInfo.IME_ACTION_GO: return "前往";
            case EditorInfo.IME_ACTION_SEARCH: return "搜索";
            case EditorInfo.IME_ACTION_SEND: return "发送";
            case EditorInfo.IME_ACTION_NEXT: return "下一项";
            case EditorInfo.IME_ACTION_DONE: return "完成";
            case EditorInfo.IME_ACTION_PREVIOUS: return "上一项";
            default: return "换行";
        }
    }

    private boolean clearComposing(boolean commitBest) {
        wordCreationController.clearAssembly();
        if (wordCreationController.isActive() && commitBest) { wordCreationController.showCreationMenu(); return true; }
        if (composingSession.isEmpty()) return true;
        if (commitBest) {
            List<PinyinDictionary.Entry> list = candidatesForCommit();
            if (list.isEmpty() && mode == Mode.NINE) {
                toastWord("暂无候选，请继续输入或修改拼音");
                return false;
            }
            String word = list.isEmpty() ? composingSession.raw() : list.get(0).word;
            if (!list.isEmpty() && mode == Mode.PINYIN && composingSession.lockedPinyinPath.isEmpty()
                    && list.get(0).consumedLength > 0 && list.get(0).consumedLength < composingSession.length())
                word += composingSession.buffer.substring(list.get(0).consumedLength);
            if (!commit(word)) return false;
            if (!list.isEmpty() && word.equals(list.get(0).word)) {
                PinyinDictionary.Entry selected = list.get(0);
                learnConfirmedCandidate(selected, learningCode());
                if (selected.wordSegments.isEmpty()) rememberWord(selected.originalWord);
                else for (String part : selected.wordSegments) rememberWord(part);
            } else clearWordContext();
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.finishComposingText();
        composingSession.hostHasComposingText = false;
        composingSession.clearInputOnly();
        composingSession.cursor = 0;
        candidateExpanded = false;
        refresh();
        return true;
    }

    private boolean commit(CharSequence text) {
        return commit(text, 0);
    }

    private boolean commit(CharSequence text, int trailingPairChars) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null && text != null && text.length() > 0) {
            selectionEdit.clearTransientState();
            EditorUndo.Snapshot snapshot = !selectionEdit.isUndoCandidateCommit() && text.length() > 1 && mayRememberEdit()
                    ? EditorUndo.read(connection) : null;
            boolean committed = connection.commitText(text.toString(), 1);
            if (!committed) { selectionEdit.clearUndo(); return false; }
            if (trailingPairChars > 0) {
                int position = snapshot == null ? -1
                        : snapshot.offset + Math.min(snapshot.start, snapshot.end) + text.length() - trailingPairChars;
                if (position < 0 || !connection.setSelection(position, position)) sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
            }
            if (!text.toString().matches("[\\p{IsHan}]+")) clearWordContext();
            if (snapshot != null) selectionEdit.recordCommitUndo(snapshot, connection, editorSession.generation);
            composingSession.hostHasComposingText = false;
            return true;
        }
        return false;
    }

    private boolean commitPunctuationOrText(String key, boolean fromSymbolPicker) {
        String value = key;
        if (!fromSymbolPicker && mode == Mode.PINYIN) {
            if ("?".equals(key)) value = "？";
            else if ("!".equals(key)) value = "！";
        }
        if (!editorSession.sensitive && !editorSession.forcedLayout
                && settingsPreferences.getBoolean("paired_punctuation", true)
                && (fromSymbolPicker || !"'".equals(value))) {
            Mode textMode = mode == Mode.SYMBOLS ? modeBeforeSymbols : mode;
            String pair = SymbolCatalog.pair(value, !fromSymbolPicker && textMode != Mode.ENGLISH);
            if (pair != null) return commit(pair, 1);
        }
        return commit(value);
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
        if (editorSession.sensitive) {
            android.widget.Toast.makeText(this, "密码输入中不可使用剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.SharedPreferences settings = getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (settings.getBoolean("privacy_mode", false)) {
            android.widget.Toast.makeText(this, "请先关闭隐私模式再使用剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if ("phrases".equals(clipboardFilter)) clipboardFilter = "all";
        if (!settings.getBoolean("clipboard_enabled", true)) {
            hidePanels();
            clipboardShown = true;
            clipboardEnablePromptShown = true;
            if (keyboardView != null) keyboardView.showClipboardEnablePrompt();
            else refresh();
            return;
        }
        hidePanels();
        clipboardShown = true;
        clipboardEnablePromptShown = false;
        clipboardHistory.retryLoad();
        startClipboardListenerIfNeeded();
        captureClipboardOnDemand();
        refresh();
    }

    private void showQuickPhrases() {
        if (editorSession.sensitive) {
            toastWord("密码输入中不可使用快捷短语");
            return;
        }
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false)) {
            toastWord("请先关闭隐私模式再使用快捷短语");
            return;
        }
        hidePanels();
        clipboardFilter = "phrases";
        clipboardShown = true;
        clipboardEnablePromptShown = false;
        quickPhrases.reload();
        refresh();
    }

    private List<PinyinDictionary.Entry> candidatesForCommit() {
        if (isChoosingWordParts()) return candidates();
        if (dictionary == null || editorSession.sensitive
                || mode == Mode.ENGLISH && !englishWordMode) return java.util.Collections.emptyList();
        String key = currentCandidateKey();
        if (key.equals(candidateSession.candidateSelectableKey) && key.equals(candidateSession.candidateResultKey)) {
            return candidateSession.candidateResults;
        }
        if (!dictionary.isLoaded()) return java.util.Collections.emptyList();
        String raw = composingSession.raw();
        CandidateLookup lookup = candidateLookup(mode, raw, pathQuery(), false, wordCreationController.isNameMode());
        return displayCandidates(mode, runLookup(lookup, ""), raw, lookup.nativeTraditional);
    }

    private void captureClipboardOnDemand() {
        captureCurrentClipboard(true, false);
    }

    private void captureClipboardWhileActive() {
        if (!clipboardListenerRegistered || !canUseClipboard()) return;
        captureCurrentClipboard(false, true);
    }

    private void captureCurrentClipboard(boolean showSensitiveNotice, boolean newCopy) {
        try {
            ClipboardManager cm = getClipboardManager();
            if (cm == null || !cm.hasPrimaryClip() || editorSession.sensitive || !canUseClipboard()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            ClipDescription description = clip.getDescription();
            long timestamp = description == null ? 0 : description.getTimestamp();
            if (timestamp != 0 && timestamp == lastClipboardTimestamp) return;
            CharSequence value = clip.getItemAt(0).getText();
            if (value == null || value.length() == 0 || value.length() > ClipboardHistoryStore.MAX_TEXT_CHARS) return;
            String text = value.toString();
            if (!ClipboardHistoryStore.canStore(text)) return;
            if (isSensitiveClipboard(clip, text)) {
                if (showSensitiveNotice) android.widget.Toast.makeText(this,
                        "检测到敏感剪贴板内容，已跳过保存", android.widget.Toast.LENGTH_SHORT).show();
                rememberClipboardTimestamp(timestamp);
                return;
            }
            if (newCopy) clipboardHistory.add(text);
            else clipboardHistory.addIfAbsent(text);
            rememberClipboardTimestamp(timestamp);
            if (clipboardShown && !clipboardEnablePromptShown) refresh();
        } catch (RuntimeException error) {
            android.util.Log.w("OfflineImeClipboard", "clipboard read failed: " + error.getClass().getSimpleName());
            if (showSensitiveNotice) android.widget.Toast.makeText(this, "暂时无法读取系统剪贴板", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void rememberClipboardTimestamp(long timestamp) {
        if (timestamp == 0) return;
        lastClipboardTimestamp = timestamp;
        getSharedPreferences("clipboard_capture_state", MODE_PRIVATE).edit()
                .putLong("last_timestamp", timestamp).apply();
    }

    private ClipboardManager getClipboardManager() {
        if (clipboardManager == null) clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        return clipboardManager;
    }

    private void startClipboardListenerIfNeeded() {
        if (clipboardSearchDialogOpen || clipboardSearchResumePending) return;
        android.content.SharedPreferences settings = getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean enabled = settings.getBoolean("clipboard_enabled", true)
                && settings.getBoolean("clipboard_capture_while_typing", true)
                && !settings.getBoolean("privacy_mode", false) && !editorSession.sensitive;
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

    private void showClipboardSearchDialog() { showPanelSearchDialog(false); }

    private void showPanelSearchDialog(boolean emoji) {
        if (clipboardSearchDialogOpen) return;
        panelSearchRestoresEmoji = emoji;
        emojiSearchReturnMode = emoji ? mode : null;
        clipboardSearchDialogOpen = true;
        clipboardSearchRestorePanel = emoji ? emojiShown : clipboardShown;
        clipboardSearchResumePending = false;
        clipboardSearchReturnEditorInfo = editorSession.info;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setPrivateImeOptions("offlineime-panel-search");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setHint(emoji ? "输入中文、英文或表情" : "输入要查找的文字");
        input.setText(emoji ? emojiSearchQuery : clipboardSearchQuery);
        if (emoji) input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        input.setSelection(input.length());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(emoji ? "搜索表情" : "搜索剪贴板")
                .setView(input)
                .setPositiveButton("搜索", (d, which) -> {
                    if (emoji) emojiSearchQuery = input.getText().toString().trim();
                    else {
                        clipboardSearchQuery = input.getText().toString().trim();
                        clipboardFilter = "all";
                    }
                })
                .setNeutralButton("清除搜索", (d, which) -> {
                    if (emoji) emojiSearchQuery = "";
                    else { clipboardSearchQuery = ""; clipboardFilter = "all"; }
                })
                .setNegativeButton("取消", null)
                .create();
        attachPanelDialog(dialog);
        dialog.setOnDismissListener(ignored -> restorePanelAfterDialog());
        try {
            dialog.show();
        } catch (RuntimeException error) {
            clipboardSearchDialogOpen = false;
            clipboardSearchRestorePanel = false;
            clipboardSearchReturnEditorInfo = null;
            panelSearchRestoresEmoji = false;
            android.util.Log.w("OfflineImeClipboard", "search dialog could not be shown: " + error.getClass().getSimpleName());
            refresh();
            return;
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        }
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
    }

    private void showQuickPhraseEditor(String id, String initialText) {
        if (id == null || !clipboardShown || editorSession.sensitive
                || getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false)) return;
        panelSearchRestoresEmoji = false;
        clipboardSearchDialogOpen = true;
        clipboardSearchRestorePanel = true;
        clipboardSearchResumePending = false;
        clipboardSearchReturnEditorInfo = editorSession.info;
        EditText input = new EditText(this);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPrivateImeOptions("offlineime-panel-search");
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setHint("输入常用文字");
        input.setText(initialText == null ? "" : initialText);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        input.setSelection(input.length());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("编辑快捷短语")
                .setView(input)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        attachPanelDialog(dialog);
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.setOnDismissListener(ignored -> restorePanelAfterDialog());
        try {
            dialog.show();
        } catch (RuntimeException error) {
            clipboardSearchDialogOpen = false;
            clipboardSearchRestorePanel = false;
            clipboardSearchReturnEditorInfo = null;
            android.util.Log.w("OfflineImeClipboard", "phrase dialog could not be shown: " + error.getClass().getSimpleName());
            refresh();
            return;
        }
        android.widget.Button save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(v -> {
            String value = input.getText().toString(); save.setEnabled(false);
            quickPhrases.save(id, value, error -> {
                if (destroyed || !dialog.isShowing()) return;
                save.setEnabled(true);
                if (!error.isEmpty()) { input.setError(error); return; }
                clipboardFilter = "phrases";
                dialog.dismiss();
            });
        });
        input.requestFocus();
        // An attached IME dialog can gain window focus after show() returns.
        // Request the keyboard when both the dialog and its editor are focused.
        input.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && dialog.isShowing()) showPhraseEditorKeyboard(input);
        });
        showPhraseEditorKeyboard(input);
    }

    private void showPhraseEditorKeyboard(EditText input) {
        input.post(() -> {
            if (!input.hasWindowFocus() || !input.hasFocus()) return;
            android.view.inputmethod.InputMethodManager manager =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(input,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void attachPanelDialog(android.app.AlertDialog dialog) {
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
            android.util.Log.w("OfflineImeClipboard", "panel dialog attachment failed: " + error.getClass().getSimpleName());
        }
    }

    private void restorePanelAfterDialog() {
        clipboardSearchDialogOpen = false;
        if (!clipboardSearchRestorePanel) {
            clipboardSearchReturnEditorInfo = null;
            panelSearchRestoresEmoji = false;
            startClipboardListenerIfNeeded();
            return;
        }
        clipboardShown = !panelSearchRestoresEmoji; emojiShown = panelSearchRestoresEmoji;
        clipboardEnablePromptShown = false;
        clipboardSearchResumePending = true;
        mainHandler.postDelayed(() -> {
            if (!clipboardSearchResumePending) return;
            clipboardSearchResumePending = false;
            clipboardSearchRestorePanel = false;
            panelSearchRestoresEmoji = false;
            EditorInfo restoreInfo = clipboardSearchReturnEditorInfo;
            clipboardSearchReturnEditorInfo = null;
            if (restoreInfo != null && (editorSession.info == null || getPackageName().equals(editorSession.info.packageName))) {
                if (emojiShown && emojiSearchReturnMode != null) mode = emojiSearchReturnMode;
                editorSession.info = restoreInfo;
                editorSession.sensitive = isSensitiveEditor(restoreInfo);
                int cls = restoreInfo.inputType & InputType.TYPE_MASK_CLASS;
                editorSession.numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                        || cls == InputType.TYPE_CLASS_DATETIME;
                editorSession.layout = classifyEditorLayout(restoreInfo);
                editorSession.forcedLayout = editorSession.numeric || "email".equals(editorSession.layout)
                        || "uri".equals(editorSession.layout);
            }
            startClipboardListenerIfNeeded();
            refresh();
        }, 250L);
    }

    private void clearClipboardWithUndo() {
        clipboardHistory.clearWithUndo(before -> {
            if (destroyed) return;
            clipboardClearBackup = before;
            clipboardClearConfirmShown = false;
            clipboardSearchQuery = "";
            clipboardFilter = "all";
            if (clipboardUndoExpiry != null) mainHandler.removeCallbacks(clipboardUndoExpiry);
            clipboardUndoExpiry = () -> {
                clipboardClearBackup = null; clipboardUndoExpiry = null;
                if (clipboardShown) refresh();
            };
            mainHandler.postDelayed(clipboardUndoExpiry, 10_000L);
            toastWord("已清空剪贴板，可在键盘上撤销");
            refresh();
        });
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

    private void pasteHistoryItem(String value) { if (value != null && clearComposing(true) && commit(value)) { clipboardShown = false; refresh(); } }


    private void toastWord(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); }

    private boolean canSaveExplicitWord() {
        return !editorSession.sensitive && !clipboardSearchDialogOpen && !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false);
    }

    boolean isChoosingWordParts() {
        return wordCreationController.isChoosingWordParts();
    }

    String wordCreationStatus() {
        return wordCreationController.status(composingSession.raw());
    }

    boolean hasWordSaveActions() {
        return wordCreationController.hasSaveActions();
    }

    private static final class PendingLearning {
        final PinyinDictionary.Selection selection;
        final long epoch, storeRevision;
        PendingLearning(PinyinDictionary.Selection selection, long epoch, long storeRevision) {
            this.selection = selection; this.epoch = epoch; this.storeRevision = storeRevision;
        }
    }
    private final java.util.ArrayDeque<PendingLearning> deferredLearning = new java.util.ArrayDeque<>();
    private long learningSaveEpoch = -1;
    private boolean personalDataSaveScheduled;
    private final Runnable personalDataSave = this::flushPersonalDataSave;

    private void queueWordLearning(String code, String word, String usage, int tier) {
        queueWordLearning(code, word, usage, tier, false);
    }

    private void queueWordLearning(String code, String word, String usage, int tier, boolean assembled) {
        queueWordLearning(code, word, usage, tier, assembled, false);
    }

    private void queueWordLearning(String code, String word, String usage, int tier,
                                   boolean assembled, boolean previouslyAllowed) {
        if (!learningEnabled() || !previouslyAllowed && !learningAllowed()) return;
        long storeRevision = settingsPreferences.getLong("user_words_revision", 0);
        PinyinDictionary.Selection selection = new PinyinDictionary.Selection(code, word, usage, null, tier, assembled);
        if (!dictionary.isUserDataReady()) {
            synchronized (deferredLearning) {
                if (deferredLearning.size() >= 256) deferredLearning.removeFirst();
                deferredLearning.addLast(new PendingLearning(selection, userDataEpoch, storeRevision));
            }
            return;
        }
        if (!dictionary.learn(code, word, "en".equals(usage))) return;
        // Learned words change ranking for every cached frame.
        candidateSession.candidateCache.evictAll();
        cancelCandidateWork();
        candidateSession.candidatePendingKey = candidateSession.candidateCompleteKey = candidateSession.candidateSelectableKey = candidateSession.candidateResultKey = "";
        schedulePersonalDataSave();
        if (previouslyAllowed) refresh();
    }

    /** Batch learning during a typing session, with a maximum wait of 20 seconds. */
    private void schedulePersonalDataSave() {
        if (personalDataSaveScheduled && learningSaveEpoch == userDataEpoch) return;
        learningSaveEpoch = userDataEpoch;
        personalDataSaveScheduled = true;
        mainHandler.removeCallbacks(personalDataSave);
        mainHandler.postDelayed(personalDataSave, 20_000L);
    }

    private void flushPersonalDataSave() {
        mainHandler.removeCallbacks(personalDataSave);
        long epoch = learningSaveEpoch;
        personalDataSaveScheduled = false;
        if (epoch != userDataEpoch || dictionary == null) return;
        PinyinDictionary target = dictionary;
        CustomWordStore.IO.execute(() -> {
            if (epoch == userDataEpoch) target.persistLearnedWords();
        });
    }

    private void flushDeferredLearning() {
        if (destroyed || dictionary == null || !dictionary.isUserDataReady()) return;
        ArrayList<PendingLearning> deferred;
        synchronized (deferredLearning) {
            deferred = new ArrayList<>(deferredLearning);
            deferredLearning.clear();
        }
        if (!learningEnabled()) return;
        for (PendingLearning pending : deferred) {
            if (pending.epoch != userDataEpoch) continue;
            PinyinDictionary.Selection selection = pending.selection;
            queueWordLearning(selection.code, selection.word, selection.usage, selection.tier, selection.assembled, true);
        }
    }

    void offerSaveCandidate(PinyinDictionary.Entry entry) {
        wordCreationController.offerSaveCandidate(entry);
    }

    void editCandidateWord(PinyinDictionary.Entry entry) {
        wordCreationController.editCandidateWord(entry);
    }

    private boolean isOwnWordEditor(EditorInfo info) {
        return info != null && getPackageName().equals(info.packageName) && info.privateImeOptions != null
                && info.privateImeOptions.startsWith("offlineime-word-");
    }

    private boolean learningEnabled() {
        return dictionary != null && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("learn_words", true)
                && !getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false);
    }
    private boolean learningAllowed() {
        return learningEnabled() && !editorSession.sensitive && !clipboardSearchDialogOpen && !isOwnWordEditor(editorSession.info);
    }
    private boolean canLearn() {
        // Never persist a partially loaded/rebuilt user-word index.
        return learningAllowed() && dictionary.isUserDataReady();
    }
    private boolean canUseClipboard() { return !editorSession.sensitive && !clipboardSearchDialogOpen && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("clipboard_enabled", true) && !getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false); }

    static String classifyEditorLayout(EditorInfo info) { return EditorSession.classifyEditorLayout(info); }
    static String classifyEditorLayout(int type) { return EditorSession.classifyEditorLayout(type); }
    static boolean isSensitiveEditor(EditorInfo info) { return EditorSession.isSensitiveEditor(info); }
    static boolean isSensitiveInput(int type, int options) { return EditorSession.isSensitiveInput(type, options); }

    private void sendKey(int code) {
        selectionEdit.sendKey(getCurrentInputConnection(), code);
    }

    private void moveCaretBy(int delta) {
        if (delta == 0) return;
        if (!composingSession.isEmpty()) {
            composingSession.cursor = Math.max(0, Math.min(composingSession.length(), composingSession.cursor + delta));
            refresh();
            return;
        }
        selectionEdit.moveCaretBy(getCurrentInputConnection(), delta, mayRememberEdit(),
                editorSession.selectionStart, editorSession.selectionEnd);
    }

    private void toggleSelectionMode() {
        if (!clearComposing(true)) return;
        Boolean active = selectionEdit.toggleSelectionMode(getCurrentInputConnection(),
                editorSession.selectionStart, editorSession.selectionEnd);
        if (active != null) {
            android.widget.Toast.makeText(this, active ? "选择模式已开启" : "选择模式已关闭",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String convertGeneratedText(String value) {
        if (value == null || !"traditional".equals(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("output_script", "simplified"))) return value;
        return HanConverter.get(this).toTraditional(value);
    }

    boolean isHandwritingWordCreation() { return wordCreationController.isActive(); }

    private void modifiedKey(int code) {
        selectionEdit.modifiedKey(getCurrentInputConnection(), code);
    }

    private void contextAction(int actionId, int fallbackKey) {
        if (!clearComposing(true)) return;
        selectionEdit.contextAction(getCurrentInputConnection(), actionId, fallbackKey,
                () -> composingSession.hostHasComposingText = false);
    }

    private boolean isLetter(String value) {
        return value.length() == 1 && ((value.charAt(0) >= 'a' && value.charAt(0) <= 'z')
                || (value.charAt(0) >= 'A' && value.charAt(0) <= 'Z'));
    }

    private boolean backKeyHandled;

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0
                && !clipboardSearchDialogOpen && closeTopPanel()) {
            backKeyHandled = true;
            return true;
        }
        if (hardwareKeyboard.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && backKeyHandled) {
            backKeyHandled = false;
            return true;
        }
        if (hardwareKeyboard.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean closeTopPanel() {
        if (keyboardView != null && keyboardView.dismissActiveMenu()) return true;
        if (clipboardShown && clipboardClearConfirmShown) {
            clipboardClearConfirmShown = false; refresh(); return true;
        }
        if (clipboardShown && clipboardMultiSelect) {
            clipboardMultiSelect = false; clipboardSelection.clear(); refresh(); return true;
        }
        if (clipboardShown && !clipboardSearchQuery.isEmpty()) {
            clipboardSearchQuery = ""; refresh(); return true;
        }
        if (candidateExpanded || layoutChooserShown || emojiShown || symbolsShown || voiceShown
                || clipboardShown || handwritingShown || toolsShown) {
            hidePanels(); refresh(); return true;
        }
        if (mode == Mode.SYMBOLS) {
            mode = modeBeforeSymbols;
            if (!editorSession.forcedLayout) rememberKeyboardMode(mode);
            refresh();
            return true;
        }
        return false;
    }

    private boolean isPrivacyMode() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false);
    }

    private void clearVoiceDraft() {
        voiceDraftText = "";
        voiceDraftSavedAt = 0L;
    }

    private String activeVoiceDraftPreview() {
        if (voiceDraftText.isEmpty() || editorSession.sensitive || isPrivacyMode()) return "";
        if (android.os.SystemClock.uptimeMillis() - voiceDraftSavedAt > VOICE_DRAFT_TTL_MS) {
            clearVoiceDraft();
            return "";
        }
        return voiceDraftText;
    }

    private String voiceQuickSendLabel() {
        if (editorSession.info == null
                || (editorSession.info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "";
        int action = editorSession.info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_SEND) return "发送";
        if (action == EditorInfo.IME_ACTION_SEARCH) return "搜索";
        return "";
    }

    private String defaultVoiceListeningStatus() {
        if (voiceHoldMode) return "松开立即上屏 · 上滑取消";
        return voiceSegmentCommitMode ? "正在听 · 停顿自动分段上屏" : "正在听 · 按停顿断句，完成后上屏";
    }

    private void finishVoiceInput(boolean andSend) {
        if (voiceShown && !voiceFinishing && voiceInput != null) {
            voiceFinishing = true;
            voicePaused = false;
            voiceCommitAndSendPending = andSend;
            voiceStatus = "正在完成识别…";
            voiceInput.finish();
            refresh();
        }
    }

    private void beginVoiceInput(boolean holdMode) {
        if (voiceShown) { stopVoiceInput(false); refresh(); return; }
        if (editorSession.info == null || getCurrentInputConnection() == null
                || editorSession.sensitive || editorSession.forcedLayout
                || clipboardSearchDialogOpen || wordCreationController.isActive()) {
            toastWord("当前输入框不支持语音输入");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(new android.content.Intent(this, VoicePermissionActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!composingSession.isEmpty() && !clearComposing(true)) return;
        hidePanels();
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        VoiceInputController.Config config = VoiceSettings.loadConfig(this, prefs, holdMode);
        voiceShown = true;
        voiceFinishing = false;
        voicePaused = false;
        voiceHoldMode = holdMode;
        voiceSegmentCommitMode = config.segmentCommitMode;
        voiceCommitAndSendPending = false;
        voiceLowSignalShown = false;
        voiceCommittedSegments = 0;
        voiceAudioLevel = 0f;
        voiceElapsedMs = 0L;
        voiceStatus = holdMode ? "正在启动录音 · 松开即上屏" : "正在准备离线语音识别…";
        voicePreview = "";
        voiceEditorGeneration = editorSession.generation;
        voiceSelectionStart = editorSession.selectionStart;
        voiceSelectionEnd = editorSession.selectionEnd;
        if (voiceInput == null) {
            voiceInput = new VoiceInputController(this, new VoiceInputController.Listener() {
                @Override public void onVoiceListening() {
                    if (!voiceShown || voiceFinishing) return;
                    if (!voiceHoldMode && keyboardView != null) {
                        KeyFeedback.get(OfflineImeService.this).press(keyboardView);
                    }
                    voiceStatus = defaultVoiceListeningStatus();
                    refresh();
                }
                @Override public void onVoiceAudioState(float level, long elapsedMs, boolean paused,
                                                        boolean lowSignalWarning) {
                    if (!voiceShown) return;
                    voiceAudioLevel = level;
                    voiceElapsedMs = elapsedMs;
                    if (keyboardView != null) {
                        keyboardView.updateVoiceAudio(level, elapsedMs, !voiceFinishing && !paused);
                    }
                    if (!voiceFinishing && !paused && lowSignalWarning != voiceLowSignalShown) {
                        voiceLowSignalShown = lowSignalWarning;
                        voiceStatus = lowSignalWarning
                                ? "未检测到麦克风声音，请检查是否被占用"
                                : defaultVoiceListeningStatus();
                        refresh();
                    }
                }
                @Override public void onVoicePartial(String text) {
                    if (!voiceShown) return;
                    String display = convertGeneratedText(text == null ? "" : text);
                    voicePreview = display;
                    if (!display.isEmpty() && voiceLowSignalShown) {
                        voiceLowSignalShown = false;
                        if (!voiceFinishing && !voicePaused) voiceStatus = defaultVoiceListeningStatus();
                    }
                    refresh();
                }
                @Override public void onVoiceSegmentCommit(String segment) {
                    if (!voiceShown || editorSession.generation != voiceEditorGeneration
                            || editorSession.sensitive || getCurrentInputConnection() == null) {
                        stopVoiceInput(false); refresh(); return;
                    }
                    String value = segment == null ? "" : segment.trim();
                    if (value.isEmpty()) return;
                    clearWordContext();
                    voiceSegmentCommitInProgress = true;
                    try {
                        if (commit(convertGeneratedText(value))) {
                            voiceCommittedSegments++;
                            clearVoiceDraft();
                        }
                    } finally {
                        voiceSegmentCommitInProgress = false;
                    }
                    voicePreview = "";
                    refresh();
                }
                @Override public void onVoiceDraining(String message) {
                    if (!voiceShown) return;
                    voiceFinishing = true; voiceStatus = message; refresh();
                }
                @Override public void onVoiceComplete(String text, String warning) {
                    if (!voiceShown || editorSession.generation != voiceEditorGeneration
                            || editorSession.sensitive || getCurrentInputConnection() == null) {
                        stopVoiceInput(false); refresh(); return;
                    }
                    boolean shouldSend = voiceCommitAndSendPending;
                    int priorSegments = voiceCommittedSegments;
                    String value = text == null ? "" : text.trim();
                    stopVoiceInput(false);
                    if (!value.isEmpty()) {
                        clearWordContext();
                        if (!commit(convertGeneratedText(value))) {
                            toastWord("无法输入识别结果，请重试");
                        } else {
                            clearVoiceDraft();
                            if (!warning.isEmpty()) toastWord(warning + "，已输入已识别文字");
                            if (shouldSend) enter();
                        }
                    } else if (priorSegments > 0) {
                        clearVoiceDraft();
                        if (!warning.isEmpty()) toastWord(warning);
                        if (shouldSend) enter();
                    } else {
                        toastWord(warning.isEmpty() ? "没有识别到文字，请重试" : warning);
                    }
                    refresh();
                }
                @Override public void onVoiceError(String message) {
                    if (!voiceShown) return;
                    stopVoiceInput(false); toastWord(message); refresh();
                }
            });
        }
        voiceInput.start(config);
        refresh();
    }

    private void stopVoiceInput() {
        stopVoiceInput(true);
    }

    private void stopVoiceInput(boolean preserveDraft) {
        if (preserveDraft && voiceShown && !voicePreview.trim().isEmpty()
                && !editorSession.sensitive && !isPrivacyMode()) {
            voiceDraftText = voicePreview.trim();
            voiceDraftSavedAt = android.os.SystemClock.uptimeMillis();
        }
        if (voiceInput != null) voiceInput.cancel();
        voiceShown = false;
        voiceFinishing = false;
        voicePaused = false;
        voiceHoldMode = false;
        voiceSegmentCommitMode = false;
        voiceCommitAndSendPending = false;
        voiceLowSignalShown = false;
        voiceCommittedSegments = 0;
        voiceAudioLevel = 0f;
        voiceElapsedMs = 0L;
        voiceStatus = voicePreview = "";
        voiceSelectionStart = voiceSelectionEnd = -1;
    }

    private HandwritingView handwritingInput() {
        return handwritingShown && keyboardView != null ? keyboardView.handwritingInput() : null;
    }

    private void closeHandwriting() {
        if (!handwritingShown) return;
        handwritingShown = false;
        handwritingFullscreen = false;
        if (keyboardView != null) {
            keyboardView.closeHandwritingPanel();
            updateInputViewLayout();
        }
    }

    private void hidePanels() {
        stopVoiceInput();
        closeHandwriting();
        toolsShown = clipboardShown = emojiShown = symbolsShown
                = layoutChooserShown = candidateExpanded = false;
        clipboardEnablePromptShown = false;
        clipboardClearConfirmShown = false;
        clipboardMultiSelect = false;
        clipboardSelection.clear();
    }

    private void invalidateCandidates() {
        composingSession.clearSegmentsCache();
        candidateInputSnapshot = null;
        candidateSession.invalidate();
    }

    private void cancelCandidateWork() { candidateSession.cancel(); }

    private List<PinyinDictionary.Entry> bindCandidateVersion(List<PinyinDictionary.Entry> source, String key) {
        if (source.isEmpty()) return java.util.Collections.emptyList();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (!key.equals(entry.inputVersion)) entry.inputVersion = key;
        }
        return java.util.Collections.unmodifiableList(source);
    }

    private void refresh() {
        if (destroyed) return;
        if (keyboardView == null) return;
        // Start the latest query immediately, then coalesce all visual work into
        // one traversal for the next display frame.
        if (clipboardSearchDialogOpen || (!toolsShown && !clipboardShown
                && !handwritingShown && !voiceShown && !emojiShown && !symbolsShown && !layoutChooserShown)) {
            candidates();
        }
        if (refreshScheduled) return;
        refreshScheduled = true;
        keyboardView.postOnAnimation(renderRefreshRunnable);
    }

    private String lastPlacementGeometry = "";
    private void renderRefreshNow() {
        long renderStarted = ImePerformanceMonitor.start();
        try {
        refreshScheduled = false;
        if (destroyed) return;
        if (keyboardView != null) {
            android.content.SharedPreferences placementPrefs = getSharedPreferences("settings", MODE_PRIVATE);
            String placementGeometry = KeyboardPlacement.width(this, placementPrefs) + ":"
                    + KeyboardPlacement.side(this, placementPrefs) + ":" + KeyboardPlacement.floating(this, placementPrefs)
                    + ":" + KeyboardPlacement.height(this, placementPrefs)
                    + ":" + handwritingShown + ":" + handwritingFullscreen;
            if (!placementGeometry.equals(lastPlacementGeometry)) {
                lastPlacementGeometry = placementGeometry;
                updateInputViewLayout();
            }
            if (clipboardSearchDialogOpen) keyboardView.refresh(mode, shift, composingSession.raw(), candidates());
            else if (toolsShown) keyboardView.showTools();
            else if (clipboardShown && clipboardEnablePromptShown) keyboardView.showClipboardEnablePrompt();
            else if (clipboardShown && "phrases".equals(clipboardFilter)) keyboardView.showQuickPhrases(quickPhrases.list(), quickPhrases.status());
            else if (clipboardShown) keyboardView.showClipboardHistory(readClipboardHistory(), clipboardFilter,
                    clipboardSearchQuery, clipboardClearConfirmShown, clipboardClearBackup != null,
                    clipboardMultiSelect, new java.util.LinkedHashSet<>(clipboardSelection));
            else if (handwritingShown) keyboardView.showHandwriting();
            else if (voiceShown) keyboardView.showVoice(voiceStatus, voicePreview, voiceFinishing,
                    voicePaused, voiceAudioLevel, voiceElapsedMs, voiceSegmentCommitMode,
                    activeVoiceDraftPreview(), voiceQuickSendLabel());
            // The emoji view retains its rows and scroll position across candidate refreshes.
            // Category changes render explicitly through onKey.
            else if (emojiShown) keyboardView.showEmojiPanel(emojiSearchQuery);
            else if (symbolsShown) return;
            else if (layoutChooserShown) keyboardView.showLayoutChooser(mode);
            else if (candidateExpanded) {
                List<PinyinDictionary.Entry> values = candidates();
                // Keep the expanded surface intact until candidates and paths
                // for the same input can be presented in one frame.
                if (composingSession.isEmpty() || candidateSurfaceReady())
                    keyboardView.showCandidatePage(mode, composingSession.raw(), values, candidatePage);
            } else {
                List<PinyinDictionary.Entry> values = candidates();
                keyboardView.setCandidatePage(candidatePage);
                keyboardView.refresh(mode, shift, composingSession.raw(), values);
            }
            keyboardView.showWordCreationStatus(wordCreationStatus());
        }
        } finally {
            ImePerformanceMonitor.stage(this, "ui_bind", renderStarted, candidateSession.candidateResults.size(), mode == Mode.NINE);
        }
    }

    private void rememberEmoji(String emoji) {
        if (!mayRememberEdit() || emoji == null || emoji.isEmpty()) return;
        EmojiPreferences.remember(getSharedPreferences("emoji", MODE_PRIVATE), emoji);
        candidateSession.candidateCache.evictAll();
    }

    private boolean isDarkMode() {
        return KeyboardTheme.resolveDark(this);
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

    private Mode restoreBeforeNumericMode() {
        Mode fallback = restoreKeyboardMode();
        if (fallback == Mode.SYMBOLS) fallback = restoreLastChineseMode();
        return savedMode("before_numeric_mode", fallback.name(), true, false);
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
        Mode beforeNumeric = selected == Mode.SYMBOLS ? modeBeforeSymbols : selected;
        if (beforeNumeric != Mode.SYMBOLS) editor.putString("before_numeric_mode", beforeNumeric.name());
        if (selected == Mode.PINYIN || selected == Mode.NINE || selected == Mode.STROKE)
            editor.putString(LAST_CHINESE_MODE, selected.name());
        // Update memory immediately; Android drains pending preference writes
        // during component lifecycle transitions without blocking this callback.
        editor.apply();
    }
}
