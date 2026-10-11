package com.example.offlineime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.GridLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** 无外部依赖的键盘 View，便于输入法进程快速启动。 */
public final class KeyboardView extends LinearLayout {
    private static final int HEADER_HEIGHT_DP = 48;
    private static final int CANDIDATE_HEIGHT_DP = HEADER_HEIGHT_DP;
    private static final int PATH_HEIGHT_DP = 24;
    private static final int TOOL_BUTTON_SIZE_DP = 48;
    private static final String ENGLISH_SWIPE_LETTERS = "qwertyuiopasdfghjklzxcvbnm";
    private static final String ENGLISH_SWIPE_SYMBOLS = "1234567890@#$%&-+()!?'\"/,.";
    public interface Callback {
        void onKey(String key);
        void onTouchKey(String key, TouchCorrectionModel.Sample sample);
        void onCandidate(int index);
        void onCandidateEntry(PinyinDictionary.Entry entry);
        void onCandidatePage(int delta);
        void onCandidateExpand();
        void onMode(OfflineImeService.Mode mode);
    }

    private final Callback callback;
    private final ArrayList<KeyButton> letterButtons = new ArrayList<>();
    private boolean dark;
    private int candidatePage;
    private ToneGenerator toneGenerator;
    private final Handler interactionHandler = new Handler(Looper.getMainLooper());
    private KeyboardTheme theme;
    /** Visibility changes inside this fixed header never resize the key rows. */
    private final FrameLayout header = new FrameLayout(getContext());
    private View expandedHeaderTitle;
    private final LinearLayout toolbarRow = new LinearLayout(getContext());
    private final LinearLayout candidateBar = new LinearLayout(getContext());
    
    private final HorizontalScrollView candidateScroll;
    private final LinearLayout candidates = new LinearLayout(getContext());
    private final TextView expandCandidates = new TextView(getContext());
    private final HorizontalScrollView pathScroll;
    private final LinearLayout pathRow = new LinearLayout(getContext());
    private final TextView selectedPinyinPath = new TextView(getContext());
    private final LinearLayout paths = new LinearLayout(getContext());
    private final ArrayList<PathChipSlot> pathChipPool = new ArrayList<>();
    private ScrollView ninePathScroll;
    private LinearLayout ninePathPanel;
    private String renderedNinePathInput;
    private int renderedNineFocusStart = -1;
    private OfflineImeService.Mode renderedPathMode;
    private String renderedPathInput = "";
    private int pathTouches;
    private RoundedCardPopup activeMenu;
    private ScrollView expandedCandidatesScroll;
    private List<PinyinDictionary.Entry> expandedCandidateWords = java.util.Collections.emptyList();
    private String expandedCandidateKey = "";
    private boolean expandedCandidatesTouched;
    private boolean expandedPageRefreshPending;
    private boolean candidateListShown;
    private final TextView composing = new TextView(getContext());
    private final ArrayList<Integer> visibleCandidateIndexes = new ArrayList<>(15);
    private final ArrayList<CandidateSlot> candidatePool = new ArrayList<>();
    private int nextCandidateStripIndex;
    private boolean bindingCandidateStrip;
    private boolean candidateStripAppendPosted;
    private final ArrayList<NinePathSlot> ninePathChoicePool = new ArrayList<>();
    private HandwritingView activeHandwritingView;
    private int baseLeftPadding;
    private int baseTopPadding;
    private int baseRightPadding;
    private int baseBottomPadding;
    private int preferredMinHeight;
    private boolean floatingLandscape;
    private String activeToolbarAction = "";
    private OfflineImeService.Mode toolbarKeyboardMode = OfflineImeService.Mode.PINYIN;
    private String renderedKeyboardKey;
    private String renderedCandidateInput;
    private List<PinyinDictionary.Entry> renderedCandidateWords = java.util.Collections.emptyList();
    private OfflineImeService.Mode renderedCandidateMode;
    private boolean renderedCandidateReady;
    private long renderedCandidateStyleRevision = -1;
    private int renderedCandidatePage = -1;
    private String renderedCandidateSurfaceKey = "";
    private String toolbarRenderKey;
    private final android.content.SharedPreferences renderSettings;
    private long styleRevision;
    private long renderedWordStatusStyle = -1;
    private String renderedWordStatus = "";
    private String renderedSurfaceKey = "";
    private boolean settingsListenerRegistered;
    private int candidateTouches;
    private boolean candidateRefreshPending;
    private boolean keyLayoutRefreshPending;
    private OfflineImeService.Mode renderedKeyboardMode;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener renderSettingsListener = (prefs, name) -> {
        if (name == null || name.startsWith("toolbar") || name.equals("theme") || name.equals("dark")
                || name.equals("high_contrast") || name.startsWith("key_") || name.equals("keyboard_height")
                || name.startsWith("one_hand") || name.startsWith("floating") || name.equals("split_keyboard") || name.equals("appearance_mode") || name.equals("privacy_mode")
                || name.startsWith("candidate_") || name.equals("expanded_candidate_density")
                || name.equals("pinyin_scheme")) styleRevision++;
    };

    private static final class CandidateSlot {
        final TextView view;
        PinyinDictionary.Entry entry, pressedEntry;
        int candidateIndex = -1;
        String word = "";
        String description = "";
        String styledWord = "";
        int styledForeground;
        int styledText;
        int styledKey;
        int styledAccent;
        int styledFunction;
        int styledRadius;
        int styledFont;
        int styledHeight;
        int gap = -1;
        boolean styledFirst;
        boolean hasStyle;

        CandidateSlot(TextView view) { this.view = view; }
    }

    private static final class NinePathSlot {
        final TextView view;
        String label = "";
        String action = "", pressedAction;
        String description = "";
        int textColor;
        int backgroundColor;
        boolean selected;
        boolean hasStyle;
        boolean quickSymbol;

        NinePathSlot(TextView view) { this.view = view; }
    }

    private static final class PathChipSlot {
        final TextView view;
        String label = "";
        String action = "";
        String pressedAction;
        String description = "";
        int textColor;

        PathChipSlot(TextView view) { this.view = view; }
    }

    public KeyboardView(Context context, Callback callback) {
        super(context);
        renderSettings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        setMotionEventSplittingEnabled(true);
        this.callback = callback;
        setOrientation(VERTICAL);
        setPadding(dp(5), 0, dp(5), dp(5));
        baseLeftPadding = getPaddingLeft();
        baseTopPadding = getPaddingTop();
        baseRightPadding = getPaddingRight();
        baseBottomPadding = getPaddingBottom();
        // IME windows are edge-to-edge on some OEMs (notably Vivo). Keep content out
        // of navigation/gesture regions, but do not use the IME inset itself: inside
        // an IME window it can describe the whole keyboard and cause double padding.
        setOnApplyWindowInsetsListener((v, insets) -> {
            int safeLeft;
            int safeRight;
            int safeBottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                Insets mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures());
                Insets tappable = insets.getInsets(WindowInsets.Type.tappableElement());
                safeLeft = Math.max(nav.left, mandatory.left);
                safeRight = Math.max(nav.right, mandatory.right);
                safeBottom = Math.max(Math.max(nav.bottom, mandatory.bottom), tappable.bottom);
            } else {
                safeLeft = insets.getSystemWindowInsetLeft();
                safeRight = insets.getSystemWindowInsetRight();
                safeBottom = insets.getSystemWindowInsetBottom();
            }
            setPadding(baseLeftPadding + safeLeft, baseTopPadding,
                    baseRightPadding + safeRight, baseBottomPadding + safeBottom);
            return insets;
        });
        // Insets are applied explicitly above. Keeping this false prevents Android
        // from applying the same inset a second time on 3-button navigation.
        setFitsSystemWindows(false);
        post(this::requestApplyInsets);
        composing.setGravity(Gravity.CENTER_VERTICAL);
        composing.setTextSize(15);
        composing.setPadding(dp(12), 0, dp(12), 0);
        toolbarRow.setOrientation(HORIZONTAL);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarRow.setPadding(dp(8), 0, dp(8), 0);
        candidateBar.setOrientation(HORIZONTAL);
        candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        candidateScroll = new HorizontalScrollView(context) {
            private boolean tracking;
            @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
                super.onScrollChanged(x, y, oldX, oldY);
                scheduleCandidateStripAppend();
            }
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) { tracking = true; candidateTouches++; }
                try { return super.dispatchTouchEvent(event); }
                finally {
                    if (tracking && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                        tracking = false;
                        candidateTouches = Math.max(0, candidateTouches - 1);
                        if (candidateRefreshPending) {
                            candidateRefreshPending = false;
                            post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
                        }
                    }
                }
            }
        };
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setFillViewport(false);
        candidateScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> scheduleCandidateStripAppend());
        candidates.setOrientation(HORIZONTAL);
        candidates.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> scheduleCandidateStripAppend());
        candidateScroll.addView(candidates, new HorizontalScrollView.LayoutParams(-2, -1));
        candidateBar.addView(candidateScroll, new LayoutParams(0, -1, 1f));
        expandCandidates.setText("⌄");
        expandCandidates.setTextSize(24);
        expandCandidates.setGravity(Gravity.CENTER);
        expandCandidates.setMinWidth(dp(UiTokens.MIN_TOUCH));
        expandCandidates.setContentDescription("展开候选词");
        GradientDrawable expandBackground = new GradientDrawable();
        expandBackground.setColor(Color.TRANSPARENT);
        expandBackground.setCornerRadius(dp(UiTokens.RADIUS_CONTROL));
        expandCandidates.setBackground(expandBackground);
        expandCandidates.setOnClickListener(view -> callback.onCandidateExpand());
        candidateBar.addView(expandCandidates, new LayoutParams(dp(48), -1));
        int candidateHeight = candidateHeightDp();
        FrameLayout.LayoutParams candidateParams = new FrameLayout.LayoutParams(-1, dp(candidateHeight), Gravity.TOP);
        candidateParams.topMargin = dp(PATH_HEIGHT_DP);
        header.addView(candidateBar, candidateParams);
        pathScroll = new HorizontalScrollView(context) {
            private boolean tracking;
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) { tracking = true; pathTouches++; }
                try { return super.dispatchTouchEvent(event); }
                finally {
                    if (tracking && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                        tracking = false;
                        pathTouches = Math.max(0, pathTouches - 1);
                        post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
                    }
                }
            }
        };
        pathScroll.setHorizontalScrollBarEnabled(false);
        paths.setOrientation(HORIZONTAL);
        pathScroll.addView(paths, new LayoutParams(-1, dp(PATH_HEIGHT_DP)));
        pathRow.setOrientation(HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        selectedPinyinPath.setTextSize(13);
        selectedPinyinPath.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        selectedPinyinPath.setPadding(dp(12), 0, dp(8), 0);
        selectedPinyinPath.setSingleLine(true);
        selectedPinyinPath.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selectedPinyinPath.setMaxWidth(dp(180));
        selectedPinyinPath.setBackgroundColor(Color.TRANSPARENT);
        selectedPinyinPath.setAlpha(0.8f);
        selectedPinyinPath.setVisibility(GONE);
        selectedPinyinPath.setOnClickListener(view ->
                showPinyinEditMenu(view, selectedPinyinPath.getText().toString()));
        pathRow.addView(selectedPinyinPath, new LayoutParams(-2, -1));
        pathRow.addView(pathScroll, new LayoutParams(0, -1, 1f));
        header.addView(pathRow, new FrameLayout.LayoutParams(-1, dp(PATH_HEIGHT_DP), Gravity.TOP));
        header.addView(toolbarRow, new FrameLayout.LayoutParams(-1, dp(candidateHeight), Gravity.TOP));
        addView(header, new LayoutParams(-1, dp(candidateHeight + PATH_HEIGHT_DP)));
        addView(composing, new LayoutParams(-1, dp(30)));
        // The window can measure us before the first posted refresh runs.
        applyLayoutPreferences();
    }

    @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
        long started = ImePerformanceMonitor.start();
        super.dispatchDraw(canvas);
        ImePerformanceMonitor.stage(getContext(), "ui_draw", started, 0, renderedKeyboardMode == OfflineImeService.Mode.NINE);
        ((OfflineImeService) getContext()).onCandidateFrameDrawn(candidateListShown ? expandedCandidateWords : renderedCandidateWords);
        ImePerformanceMonitor.firstDraw(getContext());
    }

    public void setDark(boolean dark) { this.dark = dark; }
    public void setFloatingLandscape(boolean floating) {
        if (floatingLandscape == floating) return;
        floatingLandscape = floating;
        baseLeftPadding = dp(floating ? 8 : 5);
        baseRightPadding = dp(floating ? 8 : 5);
        baseBottomPadding = dp(floating ? 7 : 5);
        setPadding(baseLeftPadding, baseTopPadding, baseRightPadding, baseBottomPadding);
        updateSurfaceBackground();
        requestApplyInsets();
        requestLayout();
    }

    private void updateSurfaceBackground() {
        int color = theme == null ? backgroundColor() : theme.background;
        if (floatingLandscape) {
            GradientDrawable surface = roundRect(color, 22);
            surface.setStroke(dp(1), dark ? 0xff444b55 : 0x26303b49);
            setBackground(surface);
            setElevation(dp(8));
            setClipToOutline(true);
        } else {
            setBackgroundColor(color);
            setElevation(0f);
            setClipToOutline(false);
        }
    }
    public void setCandidatePage(int page) { this.candidatePage = Math.max(0, page); }

    public void refresh(OfflineImeService.Mode mode, boolean shift, String input,
                        List<PinyinDictionary.Entry> words) {
        if (candidateTouches > 0 && mode == toolbarKeyboardMode) {
            candidateRefreshPending = true;
            return;
        }
        candidateListShown = false;
        expandedCandidatesScroll = null;
        expandedCandidateWords = java.util.Collections.emptyList();
        expandedCandidateKey = "";
        expandedCandidatesTouched = false;
        expandedPageRefreshPending = false;
        applyLayoutPreferences();
        applyCandidateHeaderSizing();
        toolbarKeyboardMode = mode;
        activeToolbarAction = mode == OfflineImeService.Mode.NINE ? "9键"
                : mode == OfflineImeService.Mode.STROKE ? "笔画"
                : mode == OfflineImeService.Mode.ENGLISH ? "ABC"
                : mode == OfflineImeService.Mode.SYMBOLS ? "符" : "字";
        int fg = theme.text;
        android.content.SharedPreferences settings = renderSettings;
        boolean candidateInputChanged = !input.equals(renderedCandidateInput);
        boolean candidateSurfaceReady = ((OfflineImeService) getContext()).candidateSurfaceReady();
        boolean candidateSurfaceChanged = words != renderedCandidateWords
                || mode != renderedCandidateMode
                || candidateInputChanged
                || candidateSurfaceReady != renderedCandidateReady
                || styleRevision != renderedCandidateStyleRevision
                || candidatePage != renderedCandidatePage
                || !renderedSurfaceKey.equals(renderedCandidateSurfaceKey);
        renderedCandidateInput = input;
        renderPinyinPaths(mode, input);
        boolean typing = !input.isEmpty() || !words.isEmpty();
        // 拼音已在候选栏中体现；空闲时该不可见占位只负责稳定顶部高度。
        showWordCreationStatus(((OfflineImeService) getContext()).wordCreationStatus());
        if (candidateSurfaceChanged) {
        bindingCandidateStrip = true;
        int previousCount = visibleCandidateIndexes.size();
        visibleCandidateIndexes.clear();
        int pageSize = CandidateRanker.pageSize(getIntPreference(settings, "candidate_count", 6));
        int page = Math.min(candidatePage, CandidateRanker.pageCount(words.size(), pageSize) - 1);
        nextCandidateStripIndex = page * pageSize;
        for (CandidateSlot slot : candidatePool) slot.view.setVisibility(GONE);
        int initialCount = candidateInputChanged || candidatePage != renderedCandidatePage
                ? 15 : Math.max(15, previousCount);
        appendCandidateStripBatch(words, mode, candidateSurfaceReady, initialCount);
        if (expandCandidates.getCurrentTextColor() != fg) expandCandidates.setTextColor(fg);
        // The expanded page is useful even when a reading constraint leaves
        // fewer candidates than the horizontal strip can hold.
        expandCandidates.setVisibility(typing ? VISIBLE : GONE);
        expandCandidates.setEnabled(candidateSurfaceReady);
        expandCandidates.setContentDescription("展开候选词");
        candidateBar.setVisibility(typing ? VISIBLE : GONE);
        if ((candidateInputChanged || candidatePage != renderedCandidatePage) && candidateScroll.getScrollX() != 0)
            candidateScroll.scrollTo(0, 0);
        candidateScroll.setVisibility(visibleCandidateIndexes.isEmpty() ? INVISIBLE : VISIBLE);
        renderedCandidateWords = words;
        renderedCandidateMode = mode;
        renderedCandidateReady = candidateSurfaceReady;
        renderedCandidateStyleRevision = styleRevision;
        renderedCandidatePage = candidatePage;
        renderedCandidateSurfaceKey = renderedSurfaceKey;
        bindingCandidateStrip = false;
        scheduleCandidateStripAppend();
        }
        // 空候选时保持干净留白；离线隐私说明放在设置页，不占用键盘空间。
        if (!typing) addIconToolbar();
        // 候选栏直接占用工具栏的固定位置，两者高度一致，输入时键区不跳动。
        toolbarRow.setVisibility(!typing && (settings.getBoolean("toolbar", true) || settings.getBoolean("floating", false)) ? VISIBLE : GONE);
        // Candidate completions can arrive between DOWN and UP. Keep the same
        // key instances so an asynchronous repaint cannot cancel that gesture.
        String editorAction = ((OfflineImeService) getContext()).editorActionLabel();
        String keyboardKey = mode.name() + ':' + shift + ':'
                + ((OfflineImeService) getContext()).capsLockEnabled() + ':' + editorAction + ':' + renderedSurfaceKey
                + ':' + (mode == OfflineImeService.Mode.ENGLISH && ((OfflineImeService) getContext()).englishWordMode());
        if (keyboardKey.equals(renderedKeyboardKey)) return;
        if (mode == renderedKeyboardMode && ((OfflineImeService) getContext()).keyInputOrder().hasPending()) {
            keyLayoutRefreshPending = true;
            return;
        }
        removeRows();
        if (mode == OfflineImeService.Mode.PINYIN) {
            addQwertyStyle(shift, false);
        } else if (mode == OfflineImeService.Mode.NINE) {
            addNineStyle();
        } else if (mode == OfflineImeService.Mode.STROKE) {
            addStrokeStyle();
        } else if (mode == OfflineImeService.Mode.ENGLISH) {
            addQwertyStyle(shift, true);
        } else {
            addNumericStyle();
        }
        renderedKeyboardKey = keyboardKey;
        renderedKeyboardMode = mode;
        // A mode switch can create a new T9 path column after the paths were
        // first rendered above. Populate that newly attached column as well.
        renderPinyinPaths(mode, input);
    }

    private CandidateSlot createCandidateSlot() {
        TextView item = new TextView(getContext());
        CandidateSlot slot = new CandidateSlot(item);
        item.setOnClickListener(view -> {
            if (slot.candidateIndex >= 0) callback.onCandidateEntry(slot.pressedEntry == null ? slot.entry : slot.pressedEntry);
            slot.pressedEntry = null;
        });
        item.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) slot.pressedEntry = slot.entry;
            else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) slot.pressedEntry = null;
            return false;
        });
        item.setOnLongClickListener(view -> {
            if (slot.candidateIndex >= 0) showCandidateMenu(item, slot.pressedEntry == null ? slot.entry : slot.pressedEntry);
            return slot.candidateIndex >= 0;
        });
        return slot;
    }

    private void appendCandidateStripBatch(List<PinyinDictionary.Entry> words,
                                           OfflineImeService.Mode mode, boolean ready, int count) {
        boolean nextWordPrediction = renderedCandidateInput.isEmpty() && !words.isEmpty()
                && (mode == OfflineImeService.Mode.PINYIN || mode == OfflineImeService.Mode.NINE);
        int gap = getIntPreference(renderSettings, "key_gap", 2);
        int end = Math.min(words.size(), nextCandidateStripIndex + count);
        while (nextCandidateStripIndex < end) {
            int index = nextCandidateStripIndex++;
            int slotIndex = visibleCandidateIndexes.size();
            visibleCandidateIndexes.add(index);
            CandidateSlot slot;
            if (slotIndex < candidatePool.size()) slot = candidatePool.get(slotIndex);
            else {
                slot = createCandidateSlot();
                candidatePool.add(slot);
            }
            PinyinDictionary.Entry entry = words.get(index);
            String word = entry.word;
            boolean first = index == 0;
            String candidateType = mode == OfflineImeService.Mode.ENGLISH ? "英文联想词 "
                    : nextWordPrediction ? "下一词建议 " : "候选词 ";
            slot.entry = entry;
            bindCandidateSlot(slot, index, word,
                    (first ? "首选" : "") + candidateType + word, first, theme.text, gap);
            slot.view.setEnabled(ready);
        }
    }

    private void scheduleCandidateStripAppend() {
        if (bindingCandidateStrip || candidateStripAppendPosted || candidateListShown
                || renderedCandidateWords == null
                || nextCandidateStripIndex >= renderedCandidateWords.size()) return;
        candidateStripAppendPosted = true;
        candidateScroll.post(() -> {
            candidateStripAppendPosted = false;
            if (bindingCandidateStrip || candidateListShown || renderedCandidateWords == null
                    || candidateScroll.getVisibility() != VISIBLE
                    || candidateScroll.getWidth() == 0
                    || nextCandidateStripIndex >= renderedCandidateWords.size()) return;
            View content = candidateScroll.getChildAt(0);
            if (content == null || content.getWidth() - candidateScroll.getScrollX()
                    - candidateScroll.getWidth() > dp(96)) return;
            appendCandidateStripBatch(renderedCandidateWords, renderedCandidateMode,
                    renderedCandidateReady, 15);
        });
    }

    void showWordCreationStatus(String value) {
        if (value == null || value.isEmpty()) {
            if (composing.getVisibility() != GONE) composing.setVisibility(GONE);
            return;
        }
        if (value.equals(renderedWordStatus) && styleRevision == renderedWordStatusStyle) {
            if (composing.getVisibility() != VISIBLE) composing.setVisibility(VISIBLE);
            return;
        }
        renderedWordStatus = value; renderedWordStatusStyle = styleRevision;
        composing.setText(value); composing.setTextColor(theme.accent); composing.setTextSize(13);
        composing.setMaxLines(2); composing.setEllipsize(android.text.TextUtils.TruncateAt.END);
        composing.setBackground(roundRect(blendColor(theme.key, theme.accent, dark ? 0.18f : 0.07f), 10));
        composing.setLayoutParams(new LayoutParams(-1, dp(42)));
        composing.setContentDescription(value + "，点击打开造词操作");
        composing.setOnClickListener(view -> callback.onKey("造词菜单"));
        composing.setVisibility(VISIBLE);
    }

    void showWordMenu(RoundedCardPopup menu) {
        showCardMenu(composing.getVisibility() == VISIBLE ? composing : header, menu);
    }

    private void showCandidateMenu(View anchor, PinyinDictionary.Entry entry) {
        if (entry == null) return;
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), entry.word, theme);
        OfflineImeService service = (OfflineImeService) getContext();
        if (entry.matchTier == PinyinDictionary.TIER_FUZZY) menu.item("按读音匹配：" + entry.pronunciation.replace((char)39, ' '), "可在拼音路径菜单选择原文上屏", null, false, false, () -> { });
        menu.item("不再推荐", "屏蔽此词，可在设置中恢复", null, false, false, () -> callback.onKey("屏蔽词:" + entry.word));
        menu.item("保存为自定义词", entry.pronunciation.replace('\'', ' '), null, false, false,
                () -> service.offerSaveCandidate(entry));
        menu.item("编辑词条／读音", "读音、别名与固定状态分别设置", null, false, false,
                () -> service.editCandidateWord(entry));
        menu.item("固定候选", "优先显示这个词", null, false, false,
                () -> callback.onKey("词偏好:pin:" + entry.word));
        menu.item("降低优先级", null, null, false, false,
                () -> callback.onKey("词偏好:lower:" + entry.word));
        menu.divider().item("删除学习记录", null, null, false, true,
                () -> callback.onKey("词偏好:delete:" + entry.word));
        showCardMenu(anchor, menu);
    }

    private void showPinyinEditMenu(View anchor, String path) {
        OfflineImeService service = (OfflineImeService) getContext();
        String raw = service.pinyinRawInput();
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "编辑拼音", theme);
        for (PinyinPathEditor.Segment segment : service.pinyinSegments()) {
            menu.item(service.pinyinDisplayReading(segment), null, null,
                    service.pinyinSegmentFocused(segment), false, () -> {
                        if (raw.equals(service.pinyinRawInput()))
                            showSyllableChoiceMenu(anchor, raw, segment.start);
                    });
        }
        menu.divider();
        menu.item("原文上屏", "直接输入字母或九宫格数字", null, false, false, () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("原文上屏"); });
        if (service.pinyinPathLocked()) menu.item("取消读音限定", "保留已输入的拼音", null,
                false, false, () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("选择路径:自动"); });
        menu.item("只解锁当前音节", "其他已确认读音保持不变", null, false, false,
                () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("解锁当前音节"); });
        menu.item("删除当前音节", null, null, false, true,
                () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("删除当前音节"); });
        showCardMenu(anchor, menu);
    }

    private void showSyllableChoiceMenu(View anchor, String raw, int start) {
        OfflineImeService service = (OfflineImeService) getContext();
        if (!raw.equals(service.pinyinRawInput())) return;
        callback.onKey("编辑音节:" + raw + ":" + start);
        PinyinPathEditor.Segment segment = service.focusedPinyinSegment();
        if (segment == null || segment.start != start) return;
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "选择读音 · " + service.pinyinDisplayReading(segment), theme);
        for (String reading : service.pinyinSyllableChoices(segment)) {
            menu.item(reading, null, null, service.pinyinReadingSelected(segment, reading), false, () -> {
                if (raw.equals(service.pinyinRawInput()))
                    callback.onKey("选择音节:" + raw + ":" + segment.start + ":" + segment.end + ":" + reading);
            });
        }
        menu.divider();
        if (service.pinyinPathLocked()) menu.item("取消读音限定", null, null, false, false,
                () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("选择路径:自动"); });
        menu.item("只解锁此音节", null, null, false, false,
                () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("解锁当前音节"); });
        menu.item("删除此音节", null, null, false, true,
                () -> { if (raw.equals(service.pinyinRawInput())) callback.onKey("删除当前音节"); });
        showCardMenu(anchor, menu);
    }

    private void showCardMenu(View anchor, RoundedCardPopup menu) {
        if (activeMenu != null) activeMenu.dismiss();
        pathTouches = 0;
        activeMenu = menu;
        menu.onDismiss(() -> {
            if (activeMenu == menu) activeMenu = null;
            if (candidateListShown) post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
        });
        menu.show(anchor);
    }

    private void activatePathAction(View anchor, String action) {
        if (action == null || action.isEmpty()) return;
        if (action.startsWith("更多音节:")) {
            String[] parts = action.substring("更多音节:".length()).split(":", 2);
            if (parts.length == 2) {
                try { showSyllableChoiceMenu(anchor, parts[0], Integer.parseInt(parts[1])); }
                catch (NumberFormatException ignored) { }
            }
        } else if (action.startsWith("编辑音节:") && (candidateListShown
                || !((OfflineImeService) getContext()).nineKeyMode())) {
            String[] parts = action.substring("编辑音节:".length()).split(":", 2);
            if (parts.length == 2) {
                try { showSyllableChoiceMenu(anchor, parts[0], Integer.parseInt(parts[1])); }
                catch (NumberFormatException ignored) { }
            }
        } else callback.onKey(action);
    }

    private void trackPathTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) pathTouches++;
        else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pathTouches = Math.max(0, pathTouches - 1);
            post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
        }
    }

    private void bindCandidateSlot(CandidateSlot slot, int candidateIndex, String word,
                                   String description, boolean first, int foreground, int gap) {
        if (!slot.hasStyle) AccessibilitySupport.longClick(slot.view, "候选词操作");
        slot.candidateIndex = candidateIndex;
        if (slot.view.isAccessibilityFocused() && !word.equals(slot.word)) {
            slot.view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
            slot.view.post(() -> {
                if (slot.view.isAttachedToWindow() && slot.view.getVisibility() == VISIBLE)
                    slot.view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            });
        }
        slot.word = word;
        if (!description.equals(slot.description)) {
            slot.view.setContentDescription(description);
            slot.description = description;
        }
        if (!slot.hasStyle || !word.equals(slot.styledWord) || first != slot.styledFirst
                || foreground != slot.styledForeground || theme.text != slot.styledText
                || theme.key != slot.styledKey || theme.accent != slot.styledAccent
                || theme.function != slot.styledFunction
                || Math.round(theme.radius) != slot.styledRadius
                || candidateFontSp() != slot.styledFont || candidateHeightDp() != slot.styledHeight) {
            styleCandidate(slot.view, word, first, foreground, 0, false);
            slot.styledWord = word;
            slot.styledFirst = first;
            slot.styledForeground = foreground;
            slot.styledText = theme.text;
            slot.styledKey = theme.key;
            slot.styledAccent = theme.accent;
            slot.styledFunction = theme.function;
            slot.styledRadius = Math.round(theme.radius);
            slot.styledFont = candidateFontSp();
            slot.styledHeight = candidateHeightDp();
            slot.hasStyle = true;
        }
        if (slot.view.getVisibility() != VISIBLE) slot.view.setVisibility(VISIBLE);
        if (slot.gap != gap) {
            LayoutParams params = new LayoutParams(-2, -1);
            params.setMargins(dp(gap), dp(gap), dp(gap), dp(gap));
            slot.view.setLayoutParams(params);
            slot.gap = gap;
        }
        if (slot.view.getParent() == null) candidates.addView(slot.view);
    }

    private void addNumericStyle() {
        String kind = ((OfflineImeService) getContext()).editorLayoutKind();
        if ("number".equals(kind) || "signed".equals(kind) || "decimal".equals(kind) || "signed_decimal".equals(kind)) {
            LinearLayout grid = new LinearLayout(getContext()); grid.setOrientation(VERTICAL);
            addNumericGridRow(grid, new String[]{"1", "2", "3"}); addNumericGridRow(grid, new String[]{"4", "5", "6"});
            addNumericGridRow(grid, new String[]{"7", "8", "9"});
            addNumericGridRow(grid, new String[]{kind.startsWith("signed") ? "-" : "", "0", kind.contains("decimal") ? "." : ""});
            addView(grid, new LayoutParams(-1, 0, 4f));
            addBottomFixed(new String[]{"符号", "返回", "⌫", "回车"}, bottomKeyHeight());
            return;
        }
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(HORIZONTAL);
        LinearLayout left = new LinearLayout(getContext()); left.setOrientation(VERTICAL);
        String[] side = "phone".equals(kind) ? new String[]{"+", "*", "#", "-"}
                : ("date".equals(kind) || "time".equals(kind) || "datetime".equals(kind))
                ? new String[]{"/", "-", ":", "."}
                : new String[]{"-", "+", "%", "/"};
        for (String k : side) addVerticalKey(left, k, 1f);
        body.addView(left, new LayoutParams(0, -1, 1f));
        LinearLayout grid = new LinearLayout(getContext()); grid.setOrientation(VERTICAL);
        addNumericGridRow(grid, new String[]{"1","2","3"}); addNumericGridRow(grid, new String[]{"4","5","6"}); addNumericGridRow(grid, new String[]{"7","8","9"});
        body.addView(grid, new LayoutParams(0, -1, 4f));
        LinearLayout right = new LinearLayout(getContext()); right.setOrientation(VERTICAL);
        // 普通数字布局将 0 放在右侧，原底栏 0 的位置改为小数点。
        String auxiliary = "phone".equals(kind) ? "#"
                : ("date".equals(kind) || "datetime".equals(kind)) ? "/"
                : "time".equals(kind) ? ":" : ".";
        boolean decimalLayout = ".".equals(auxiliary);
        addVerticalKey(right, "⌫", 1f);
        addVerticalKey(right, decimalLayout ? "0" : auxiliary, 1f);
        body.addView(right, new LayoutParams(0, -1, 1f)); addView(body, new LayoutParams(-1, 0, 3f));
        addBottomFixed(new String[]{"返回", decimalLayout ? "." : "0", "空格", "中英", "回车"}, bottomKeyHeight());
    }

    private void addNumericGridRow(LinearLayout parent, String[] labels) {
        LinearLayout row = new KeyTouchRow(getContext()); row.setGravity(Gravity.CENTER);
        for (String label : labels) {
            KeyButton button = new KeyButton(getContext(), label, false, theme);
            button.setOnClickListener(v -> callback.onKey(label));
            if (label.isEmpty()) { button.setEnabled(false); button.setVisibility(INVISIBLE); }
            LayoutParams params = new LayoutParams(0, -1, 1f);
            params.setMargins(dp(nineKeyHorizontalGap()), dp(nineKeyVerticalGap()),
                    dp(nineKeyHorizontalGap()), dp(nineKeyVerticalGap()));
            row.addView(button, params);
        }
        parent.addView(row, new LayoutParams(-1, 0, 1f));
    }

    private String selectedSymbolCategory = "常用";
    private boolean symbolPanelShown;
    private ScrollView symbolContent;
    private LinearLayout symbolGrid;
    private final java.util.Map<String, TextView> symbolCategoryTabs = new java.util.LinkedHashMap<>();

    public void showSymbols() { showSymbols(selectedSymbolCategory); }

    public void showSymbols(String category) {
        selectedSymbolCategory = category;
        if (symbolPanelShown && symbolContent != null && symbolContent.getParent() == this) {
            updateSymbolCategory(category);
            return;
        }
        applyLayoutPreferences();
        activeToolbarAction = "符号";
        hideComposingHeader();
        removeRows();
        showSymbolCategories(category);

        symbolContent = new ScrollView(getContext());
        symbolContent.setFillViewport(false);
        symbolContent.setVerticalScrollBarEnabled(false);
        symbolGrid = new LinearLayout(getContext());
        symbolGrid.setOrientation(VERTICAL);
        symbolContent.addView(symbolGrid, new ScrollView.LayoutParams(-1, -2));
        addView(symbolContent, new LayoutParams(-1, 0, 1f));
        addBottomMappedFixed(new String[]{"返回", "123", "空格", "⌫"},
                new String[]{"返回符号", "123", "空格", "⌫"}, dp(48));
        symbolPanelShown = true;
        updateSymbolCategory(category);
    }

    private void updateSymbolCategory(String category) {
        for (java.util.Map.Entry<String, TextView> entry : symbolCategoryTabs.entrySet()) {
            boolean active = entry.getKey().equals(category);
            TextView tab = entry.getValue();
            tab.setSelected(active);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setContentDescription(entry.getKey() + "符号" + (active ? "，已选中" : ""));
            tab.setBackground(roundRect(active ? theme.function : Color.TRANSPARENT, 10));
        }
        if (symbolContent == null || symbolGrid == null) return;
        symbolGrid.removeAllViews();
        int columns = isLandscapeLayout() ? 10 : 6;
        String[] symbols = SymbolCatalog.values(category);
        if ("最近".equals(category)) {
            String saved = getContext().getSharedPreferences("symbol_recent", Context.MODE_PRIVATE).getString("items", "");
            symbols = saved.isEmpty() ? SymbolCatalog.values("常用") : saved.split("\n");
        }
        for (int start = 0; start < symbols.length; start += columns) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            for (int i = start; i < Math.min(start + columns, symbols.length); i++) {
                String symbol = symbols[i];
                TextView tile = new TextView(getContext());
                tile.setText(symbol);
                tile.setTextSize(symbol.codePointCount(0, symbol.length()) > 1 ? 18 : 23);
                tile.setTextColor(theme.text);
                tile.setGravity(Gravity.CENTER);
                tile.setSingleLine(true);
                tile.setContentDescription("输入符号 " + symbol);
                android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
                states.addState(new int[]{android.R.attr.state_pressed},
                        roundRect(blendColor(theme.key, theme.accent, 0.22f), 10));
                states.addState(new int[]{}, roundRect(theme.key, 10));
                tile.setBackground(states);
                tile.setOnClickListener(v -> callback.onKey("符号输入:" + symbol));
                LayoutParams cell = new LayoutParams(0, dp(48), 1f);
                cell.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(tile, cell);
            }
            for (int i = symbols.length - start; i < columns && start + columns >= symbols.length; i++)
                row.addView(new View(getContext()), new LayoutParams(0, dp(48), 1f));
            symbolGrid.addView(row, new LayoutParams(-1, dp(52)));
        }
        symbolContent.scrollTo(0, 0);
    }

    private void showSymbolCategories(String selected) {
        toolbarRenderKey = null;
        toolbarRow.removeAllViews();
        toolbarRow.setPadding(0, 0, 0, 0);
        toolbarRow.setBackgroundColor(theme.background);
        toolbarRow.setVisibility(VISIBLE);
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        symbolCategoryTabs.clear();
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (String category : SymbolCatalog.CATEGORIES) {
            TextView tab = new TextView(getContext());
            boolean active = category.equals(selected);
            tab.setText(category);
            tab.setTextSize(14);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setGravity(Gravity.CENTER);
            tab.setSelected(active);
            tab.setContentDescription(category + "符号" + (active ? "，已选中" : ""));
            tab.setBackground(roundRect(active ? theme.function : Color.TRANSPARENT, 10));
            tab.setOnClickListener(v -> callback.onKey("符号分类:" + category));
            LayoutParams params = new LayoutParams(dp(54), dp(40));
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(tab, params);
            symbolCategoryTabs.put(category, tab);
            if (active) tab.post(() -> scroll.scrollTo(Math.max(0, tab.getLeft() - dp(100)), 0));
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        toolbarRow.addView(scroll, new LayoutParams(-1, -1));
    }

    private void addIconToolbar() {
        android.content.SharedPreferences settings = renderSettings;
        boolean toolbarEnabled = settings.getBoolean("toolbar", true) || settings.getBoolean("floating", false);
        toolbarRow.setVisibility(toolbarEnabled ? VISIBLE : GONE);
        if (!toolbarEnabled) return;
        String cacheKey = renderedSurfaceKey + ':' + activeToolbarAction + ':' + toolbarKeyboardMode
                + ':' + ((OfflineImeService) getContext()).isNameMode();
        if (cacheKey.equals(toolbarRenderKey)) return;
        ArrayList<String> actions = new ArrayList<>();
        for (int i = 0; i < ToolbarConfig.DEFAULTS.length; i++) {
            String action = ToolbarConfig.normalize(settings.getString("toolbar_item_" + i,
                    ToolbarConfig.DEFAULTS[i]), i);
            if (!action.isEmpty() && !actions.contains(action)) actions.add(action);
        }
        toolbarRenderKey = cacheKey;
        toolbarRow.removeAllViews();
        toolbarRow.setPadding(dp(8), 0, dp(8), 0);
        toolbarRow.setBackgroundColor(theme.background);
        for (String action : actions) addToolbarAction(action);
        if (settings.getBoolean("floating", false) || !"off".equals(KeyboardPlacement.side(settings))) addPlacementHandle();
        if (settings.getBoolean("privacy_mode", false)) {
            TextView privacy = new TextView(getContext()); privacy.setText("隐私"); privacy.setTextColor(theme.accent);
            privacy.setGravity(Gravity.CENTER); privacy.setContentDescription("隐私模式已开启，点击关闭");
            privacy.setOnClickListener(v -> callback.onKey("隐私模式"));
            toolbarRow.addView(privacy, new LayoutParams(dp(48), -1));
        }
        // The dismiss control is always the last cell, including when all slots are empty.
        if (actions.isEmpty()) toolbarRow.addView(new View(getContext()), new LayoutParams(0, 1, 1f));
        addToolbarAction("收起键盘");
    }

    void cancelKeyTouches() { cancelTouchesIn(this); }
    private void cancelTouchesIn(View view) {
        if (view instanceof KeyButton) ((KeyButton) view).cancelTouch();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) cancelTouchesIn(group.getChildAt(i));
        }
    }
    void trimArtwork() { if (emojiStyleAssets != null) emojiStyleAssets.trim(); }

    private void addPlacementHandle() {
        TextView handle = new TextView(getContext()); handle.setText("↔"); handle.setTextSize(22);
        handle.setGravity(Gravity.CENTER); handle.setTextColor(theme.accent);
        handle.setContentDescription("键盘位置：浮动时拖动，点击调整尺寸或停靠");
        handle.setOnClickListener(v -> showPlacementMenu(handle));
        final float[] point = new float[4]; final boolean[] dragged = {false};
        handle.setOnTouchListener((v, event) -> {
            if (!renderSettings.getBoolean("floating", false)) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                point[0] = point[2] = event.getRawX(); point[1] = point[3] = event.getRawY(); dragged[0] = false; return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(event.getRawX() - point[2], event.getRawY() - point[3]) > dp(6)) dragged[0] = true;
                if (dragged[0]) ((OfflineImeService) getContext()).moveFloatingKeyboard(event.getRawX() - point[0], event.getRawY() - point[1], false);
                point[0] = event.getRawX(); point[1] = event.getRawY(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (dragged[0]) ((OfflineImeService) getContext()).moveFloatingKeyboard(0, 0, true);
                else if (event.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
                return true;
            }
            return true;
        });
        toolbarRow.addView(handle, new LayoutParams(dp(48), -1));
    }

    private void showPlacementMenu(View anchor) {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "键盘尺寸与位置", theme);
        menu.item("左手", null, null, false, false, () -> renderSettings.edit().putString("one_hand_side", "left").putBoolean("floating", false).apply());
        menu.item("右手", null, null, false, false, () -> renderSettings.edit().putString("one_hand_side", "right").putBoolean("floating", false).apply());
        menu.item("浮动键盘", "拖动工具栏箭头调整位置", null, false, false, () -> renderSettings.edit().putBoolean("floating", true).apply());
        menu.item("加宽", null, null, false, false, () -> resizeFloating(24, 0));
        menu.item("收窄", null, null, false, false, () -> resizeFloating(-24, 0));
        menu.item("增高", null, null, false, false, () -> resizeFloating(0, 24));
        menu.item("降低", null, null, false, false, () -> resizeFloating(0, -24));
        menu.item("恢复全宽并停靠", null, null, false, false, () -> renderSettings.edit().putString("one_hand_side", "off").putBoolean("one_hand", false).putBoolean("floating", false).apply());
        showCardMenu(anchor, menu);
    }
    private void resizeFloating(int width, int height) {
        renderSettings.edit().putBoolean("floating", true)
                .putInt("floating_width", Math.max(280, Math.min(getResources().getConfiguration().screenWidthDp, KeyboardPlacement.width(getContext(), renderSettings) + width)))
                .putInt("floating_height", Math.max(200, Math.min(560, KeyboardPlacement.height(getContext(), renderSettings) + height))).apply();
    }

    private void addToolbarAction(String action) {
        View button;
        OfflineImeService.Mode mode = modeForToolbar();
        boolean layout = "键盘布局".equals(action);
        boolean selected = action.equals(activeToolbarAction) || (layout && mode != null)
                || "人名模式".equals(action) && ((OfflineImeService) getContext()).isNameMode();
        if (layout) {
            TextView badge = new TextView(getContext());
            styleModeBadge(badge, toolbarKeyboardMode, true);
            badge.setTextColor(selected ? theme.accent : theme.text);
            badge.setContentDescription("键盘布局");
            button = badge;
        } else {
            android.widget.ImageButton icon = new android.widget.ImageButton(getContext());
            icon.setImageResource("人名模式".equals(action) ? R.drawable.ic_toolbar_keyboard : toolbarIcon(action));
            icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(8), dp(8), dp(8), dp(8));
            icon.setColorFilter(selected ? theme.accent : theme.text, android.graphics.PorterDuff.Mode.SRC_IN);
            button = icon;
        }
        button.setContentDescription("人名模式".equals(action) ? "人名候选，" + (selected ? "已开启，点击退出" : "点击开启") : action);
        AccessibilitySupport.selected(button, selected);
        button.setTooltipText(action);
        int softAccent = blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundRect(blendColor(theme.function, theme.accent, 0.18f), 12));
        states.addState(new int[]{}, roundRect(selected ? softAccent : Color.TRANSPARENT, 12));
        button.setBackground(states);
        button.setOnClickListener(v -> callback.onKey(action));
        FrameLayout cell = new FrameLayout(getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                FrameLayout.LayoutParams child = (FrameLayout.LayoutParams) getChildAt(0).getLayoutParams();
                child.width = Math.min(dp(40), Math.max(0, MeasureSpec.getSize(widthSpec) - dp(2)));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        // Equal cells keep the hit targets generous; the selected surface stays compact.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
        cell.addView(button, params);
        cell.setOnClickListener(v -> button.performClick());
        toolbarRow.addView(cell, "收起键盘".equals(action)
                ? new LayoutParams(dp(40), -1) : new LayoutParams(0, -1, 1f));
    }

    private OfflineImeService.Mode modeForToolbar() {
        if ("9键".equals(activeToolbarAction)) return OfflineImeService.Mode.NINE;
        if ("笔画".equals(activeToolbarAction)) return OfflineImeService.Mode.STROKE;
        if ("ABC".equals(activeToolbarAction)) return OfflineImeService.Mode.ENGLISH;
        if ("符".equals(activeToolbarAction)) return OfflineImeService.Mode.SYMBOLS;
        if ("字".equals(activeToolbarAction)) return OfflineImeService.Mode.PINYIN;
        return null;
    }

    private void addQwertyStyle(boolean shift, boolean english) {
        addLetterRow("qwertyuiop", shift, false, english);
        addLetterRow("asdfghjkl", shift, true, english);
        LinearLayout third = new KeyTouchRow(getContext());
        KeyButton shiftKey = addKey(third, "⇧", "⇧", 1.5f);
        ((LayoutParams) shiftKey.getLayoutParams()).leftMargin += dp(nineKeyHorizontalGap());
        shiftKey.setShiftState(shift, ((OfflineImeService) getContext()).capsLockEnabled(), theme);
        for (char letter : "zxcvbnm".toCharArray()) {
            if (letter == 'b' && KeyboardPlacement.split(getContext(), renderSettings)) third.addView(new View(getContext()), new LayoutParams(0, 1, 2f));
            addQwertyLetter(third, letter, shift, english);
        }
        KeyButton deleteKey = addKey(third, "⌫", "⌫", 1.5f);
        ((LayoutParams) deleteKey.getLayoutParams()).rightMargin += dp(nineKeyHorizontalGap());
        addView(third, new LayoutParams(-1, 0, 1f));
        addSogouBottomRow(english, true);
    }

    private void addStrokeStyle() {
        LinearLayout first = new KeyTouchRow(getContext());
        first.setGravity(Gravity.CENTER);
        addKey(first, "一\n横", "1", 1f);
        addKey(first, "丨\n竖", "2", 1f);
        addKey(first, "丿\n撇", "3", 1f);
        addView(first, new LayoutParams(-1, 0, 1f));
        LinearLayout second = new KeyTouchRow(getContext());
        second.setGravity(Gravity.CENTER);
        addKey(second, "丶\n点", "4", 1f);
        addKey(second, "乛\n折", "5", 1f);
        addKey(second, "⌫", "⌫", 1f);
        addView(second, new LayoutParams(-1, 0, 1f));
        LinearLayout hintRow = new LinearLayout(getContext());
        TextView hint = new TextView(getContext());
        hint.setText("按汉字笔顺输入：横、竖、撇、点、折");
        hint.setTextSize(13);
        hint.setTextColor(theme.text);
        hint.setGravity(Gravity.CENTER);
        hintRow.addView(hint, new LayoutParams(-1, -1));
        addView(hintRow, new LayoutParams(-1, 0, 0.48f));
        addSogouBottomRow(false);
    }

    private void addSogouBottomRow(boolean english) {
        addSogouBottomRow(english, false);
    }

    private void addSogouBottomRow(boolean english, boolean equalLetterRows) {
        LinearLayout bottom = new KeyTouchRow(getContext());
        bottom.setGravity(Gravity.CENTER);
        int horizontalGap = nineKeyHorizontalGap();
        int verticalGap = nineKeyVerticalGap();
        String editorKind = ((OfflineImeService) getContext()).editorLayoutKind();
        boolean compact = KeyboardPlacement.width(getContext(), renderSettings) < 340;
        addKey(bottom, compact ? "符" : "符号", "符号", english ? 10f : 12f, horizontalGap, verticalGap);
        if (english && "email".equals(editorKind))
            addKey(bottom, "@", "@", 12f, horizontalGap, verticalGap);
        else if (english && "uri".equals(editorKind))
            addKey(bottom, "/", "/", 12f, horizontalGap, verticalGap);
        else addKey(bottom, "123", "123", english ? 12f : 15f, horizontalGap, verticalGap);
        if (english) {
            boolean wordMode = ((OfflineImeService) getContext()).englishWordMode();
            KeyButton toggle = addKey(bottom, wordMode ? "词" : "字", "英文输入模式",
                    8f, horizontalGap, verticalGap);
            toggle.setContentDescription(wordMode ? "英文单词模式，点击切换为字母模式"
                    : "英文字母模式，点击切换为单词模式");
        }
        addKey(bottom, english ? "Space" : "空格", "空格", english ? 40f : 42f, horizontalGap, verticalGap);
        if (english && ("email".equals(editorKind) || "uri".equals(editorKind)))
            addKey(bottom, "email".equals(editorKind) ? "." : ".com",
                    "email".equals(editorKind) ? "." : ".com", 12f, horizontalGap, verticalGap);
        else addKey(bottom, "中/英", "中英", english ? 12f : 13f, horizontalGap, verticalGap);
        String action = ((OfflineImeService) getContext()).editorActionLabel();
        addKey(bottom, action, "回车", 18f, horizontalGap, verticalGap);
        addView(bottom, equalLetterRows ? new LayoutParams(-1, 0, 1f) : new LayoutParams(-1, bottomKeyHeight()));
    }

    private void addLetterRow(String letters, boolean upper, boolean inset, boolean english) {
        LinearLayout row = new KeyTouchRow(getContext());
        if (inset) addHalfKeyInset(row);
        for (int index = 0; index < letters.length(); index++) {
            if (index == (letters.length() + 1) / 2 && KeyboardPlacement.split(getContext(), renderSettings))
                row.addView(new View(getContext()), new LayoutParams(0, 1, 2f));
            char letter = letters.charAt(index);
            addQwertyLetter(row, letter, upper, english);
        }
        if (inset) addHalfKeyInset(row);
        addView(row, new LayoutParams(-1, 0, 1f));
    }

    private void addHalfKeyInset(LinearLayout row) {
        LayoutParams inset = new LayoutParams(0, 1, 0.5f);
        // Account for the two missing half-key gutters, keeping all letter cells
        // at the same width as the first row even with visible key margins.
        inset.leftMargin = dp(nineKeyHorizontalGap());
        row.addView(new View(getContext()), inset);
    }

    private void addQwertyLetter(LinearLayout row, char letter, boolean upper, boolean english) {
        String value = String.valueOf(letter);
        String display = upper ? value.toUpperCase(Locale.ROOT) : value;
        if (!english && ((OfflineImeService) getContext()).isShuangpin()) display = shuangpinHint(letter) + "\n" + display;
        String action = upper ? value.toUpperCase(Locale.ROOT) : value;
        KeyButton button = addKey(row, display, action, 1f);
        if (!english) {
            button.setInputLetter(letter);
            letterButtons.add(button);
            button.setTouchAction((x, y) -> {
                TouchCorrectionModel.Sample sample = touchSample(button, x, y);
                return () -> callback.onTouchKey(action, sample);
            });
        }
        if (english) {
            int index = ENGLISH_SWIPE_LETTERS.indexOf(letter);
            if (index >= 0) {
                String symbol = String.valueOf(ENGLISH_SWIPE_SYMBOLS.charAt(index));
                button.setSwipeUpSymbol(symbol, () -> callback.onKey(symbol));
            }
        }
    }

    private String shuangpinHint(char key) {
        String[] values = {"q:iu", "w:ei", "e:e", "r:uan", "t:ue", "y:un", "u:sh/u", "i:ch/i", "o:uo", "p:ie",
                "a:a", "s:ong", "d:ai", "f:en", "g:eng", "h:ang", "j:an", "k:ing", "l:iang", "z:ou",
                "x:ia", "c:ao", "v:zh/ui", "b:in", "n:iao", "m:ian"};
        for (String value : values) if (value.charAt(0) == key) return value.substring(2);
        return "";
    }

    private TouchCorrectionModel.Sample touchSample(KeyButton source, float x, float y) {
        ArrayList<TouchCorrectionModel.Alternative> alternatives = new ArrayList<>();
        float nx = x / Math.max(1, source.getWidth()), ny = y / Math.max(1, source.getHeight());
        if (nx < 0.28f || nx > 0.72f || ny < 0.22f || ny > 0.78f) {
            int[] origin = new int[2], location = new int[2]; source.getLocationInWindow(origin);
            float gx = origin[0] + x, gy = origin[1] + y;
            float baseX = nx - 0.5f, baseY = ny - 0.5f;
            float base = baseX * baseX + baseY * baseY;
            for (KeyButton neighbor : letterButtons) {
                if (neighbor == source || !neighbor.isAttachedToWindow() || neighbor.getWidth() <= 0) continue;
                neighbor.getLocationInWindow(location);
                float dx = (gx - location[0] - neighbor.getWidth() * 0.5f) / neighbor.getWidth();
                float dy = (gy - location[1] - neighbor.getHeight() * 0.5f) / Math.max(1, neighbor.getHeight());
                float cost = dx * dx + dy * dy - base;
                if (cost <= 0.85f && Math.abs(dx) < 1.25f && Math.abs(dy) < 1.2f)
                    alternatives.add(new TouchCorrectionModel.Alternative(neighbor.inputLetter(), Math.round(Math.max(0, cost) * 16) / 16f));
            }
            alternatives.sort(java.util.Comparator.comparingDouble(value -> value.cost));
            if (alternatives.size() > 2) alternatives.subList(2, alternatives.size()).clear();
        }
        return new TouchCorrectionModel.Sample(source.inputLetter(), alternatives);
    }

    private void addNineStyle() {
        // Sogou-style structure: symbol strip, 3x3 keys, three side controls,
        // then one full-width bottom row with the enter action at the far right.
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        ninePathScroll = createNinePathPanel();
        int pathWidth = Math.max(52, Math.min(76, KeyboardPlacement.width(getContext(), renderSettings) / 5));
        LayoutParams strip = new LayoutParams(dp(pathWidth), -1);
        // The sidebar uses the same outer gutters as the grid and bottom row.
        strip.setMargins(dp(nineKeyHorizontalGap()), 0, dp(nineKeyHorizontalGap()), 0);
        body.addView(ninePathScroll, strip);
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        addNineGridRow(grid, new String[]{"1\n@/.", "2\nABC", "3\nDEF"}, new String[]{"标点", "2", "3"}, 0);
        addNineGridRow(grid, new String[]{"4\nGHI", "5\nJKL", "6\nMNO"}, new String[]{"4", "5", "6"}, 0);
        addNineGridRow(grid, new String[]{"7\nPQRS", "8\nTUV", "9\nWXYZ"}, new String[]{"7", "8", "9"}, 0);
        body.addView(grid, new LayoutParams(0, -1, 78f));
        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(VERTICAL);
        addNineSideKey(right, "⌫", false);
        addNineSideKey(right, "重输", false);
        addNineSideKey(right, "分词", false);
        body.addView(right, new LayoutParams(0, -1, 22f));
        addView(body, new LayoutParams(-1, 0, 1f));
        addSogouBottomRow(false);
    }

    private ScrollView createNinePathPanel() {
        ScrollView scroll = new ScrollView(getContext()) {
            private boolean tracking;
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    tracking = true;
                    pathTouches++;
                }
                try { return super.dispatchTouchEvent(event); }
                finally {
                    if (tracking && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                        tracking = false;
                        pathTouches = Math.max(0, pathTouches - 1);
                        post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
                    }
                }
            }
        };
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollBarStyle(SCROLLBARS_INSIDE_OVERLAY);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL);
        panel.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        panel.setClipToOutline(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setCornerRadius(dp(Math.round(theme.radius)));
        panel.setBackground(background);
        ninePathPanel = panel;
        ninePathChoicePool.clear();
        renderedNinePathInput = null;
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        scroll.setContentDescription("拼音路径，上下滑动浏览，当前匹配的读音在前");
        return scroll;
    }

    private void addNineGridRow(LinearLayout parent, String[] labels, String[] actions, int height) {
        LinearLayout row = new KeyTouchRow(getContext()); row.setGravity(Gravity.CENTER);
        int horizontalGap = nineKeyHorizontalGap();
        int verticalGap = nineKeyVerticalGap();
        for (int i = 0; i < labels.length; i++) {
            KeyButton b = new KeyButton(getContext(), labels[i], false, theme);
            final String action = actions[i]; b.setOnClickListener(v -> callback.onKey(action));
            if (action.length() == 1 && Character.isDigit(action.charAt(0))) {
                b.setAlternateAction(() -> callback.onKey("长按数字:" + action));
            }
            LayoutParams lp = new LayoutParams(0, -1, 1f);
            lp.setMargins(dp(horizontalGap), dp(verticalGap), dp(horizontalGap), dp(verticalGap));
            row.addView(b, lp);
        }
        parent.addView(row, height == 0 ? new LayoutParams(-1, 0, 1f) : new LayoutParams(-1, height));
    }

    private int bottomKeyHeight() {
        return dp(isLandscapeLayout() ? 44 : 54);
    }

    private void addNineSideKey(LinearLayout column, String key, boolean emphasized) {
        KeyboardTheme current = theme == null ? KeyboardTheme.from(getContext(), dark) : theme;
        KeyButton button = new KeyButton(getContext(), key, true, current);
        if (emphasized) button.setEmphasized(current);
        button.setOnClickListener(v -> callback.onKey("清空".equals(key) ? "重输" : key));
        if ("⌫".equals(key)) enableRepeatDelete(button);
        int horizontalGap = nineKeyHorizontalGap();
        int verticalGap = nineKeyVerticalGap();
        LayoutParams params = new LayoutParams(-1, 0, 1f);
        params.setMargins(dp(horizontalGap), dp(verticalGap), dp(horizontalGap), dp(verticalGap));
        column.addView(button, params);
    }

    private void enableRepeatDelete(KeyButton button) {
        button.setRepeatDelete();
    }

    private void addVerticalKey(LinearLayout parent, String key, float weight) {
        KeyButton b = new KeyButton(getContext(), key, isFunctionKey(key), theme);
        b.setOnClickListener(v -> callback.onKey(key));
        if ("⌫".equals(key)) enableRepeatDelete(b);
        LayoutParams params = new LayoutParams(-1, 0, weight);
        params.setMargins(dp(nineKeyHorizontalGap()), dp(nineKeyVerticalGap()),
                dp(nineKeyHorizontalGap()), dp(nineKeyVerticalGap()));
        parent.addView(b, params);
    }

    private int toolbarIcon(String action) {
        if ("收起键盘".equals(action)) return R.drawable.ic_toolbar_hide;
        if ("设置".equals(action)) return R.drawable.ic_toolbar_settings;
        if ("符号".equals(action)) return R.drawable.ic_toolbar_symbols;
        if ("😊".equals(action)) return R.drawable.ic_toolbar_emoji;
        if ("剪贴板".equals(action)) return R.drawable.ic_toolbar_clipboard;
        if ("手写".equals(action)) return R.drawable.ic_toolbar_handwriting;
        if ("键盘布局".equals(action)) return R.drawable.ic_toolbar_keyboard;
        if ("字".equals(action)) return R.drawable.ic_toolbar_keyboard;
        if ("9键".equals(action)) return R.drawable.ic_toolbar_nine;
        if ("符".equals(action) || "ABC".equals(action)) return R.drawable.ic_toolbar_keyboard;
        return R.drawable.ic_toolbar_tools;
    }

    /** Layout cards keep their content height inside the fixed-height IME. */
    public void showLayoutChooser(OfflineImeService.Mode currentMode) {
        applyLayoutPreferences();
        activeToolbarAction = "键盘布局";
        addIconToolbar();
        hideComposingHeader();
        removeRows();
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout choices = new LinearLayout(getContext());
        choices.setOrientation(VERTICAL);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        boolean landscape = isLandscapeLayout();
        int columns = landscape ? 4 : 2;
        String[] titles = {"中文 26键", "中文 9键", "笔画输入", "English", "数字键盘"};
        String[] subtitles = {"全拼、简拼与整句输入", "九宫格拼音，适合单手",
                "横竖撇点折，支持简繁汉字", "英文拼写、联想与上划符号", "数字及常用运算符"};
        String[] actions = {"字", "9键", "笔画", "ABC", "符"};
        OfflineImeService.Mode[] modes = {OfflineImeService.Mode.PINYIN,
                OfflineImeService.Mode.NINE, OfflineImeService.Mode.STROKE, OfflineImeService.Mode.ENGLISH,
                OfflineImeService.Mode.SYMBOLS};
        LinearLayout row = null;
        for (int i = 0; i < titles.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                row.setBaselineAligned(false);
                choices.addView(row, new LayoutParams(-1, -2));
            }
            addLayoutChoice(row, titles[i], subtitles[i], actions[i], currentMode == modes[i]);
        }
        Button help = new Button(getContext()); help.setText("拼音路径与小鹤双拼帮助");
        help.setOnClickListener(v -> {
            RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "拼音输入提示", theme);
            TextView text = new TextView(getContext()); text.setTextSize(15); text.setTextColor(theme.text); text.setPadding(dp(12), dp(12), dp(12), dp(12));
            text.setText("拼音路径：长音节在前，同长度按 A–Z 排列。点击选择读音，长按可编辑、只解锁此音节或原文上屏。\n\n小鹤双拼：每个音节两个按键，键帽上方显示韵母。单字母零声母双击，如 a→aa；两字母零声母直接输入，如 ai；三字母零声母用首字母加韵母键，如 ang→ah。\n\n空格左右滑动移动光标，上滑切换选择。回车先确认拼音，再次点击才执行发送或换行。");
            ScrollView helpScroll = new ScrollView(getContext()); helpScroll.addView(text); menu.content(helpScroll); showCardMenu(help, menu);
        }); choices.addView(help);
        Button placement = new Button(getContext()); placement.setText("单手 · 浮动 · 尺寸");
        placement.setOnClickListener(v -> showPlacementMenu(placement)); choices.addView(placement);
        Button privacyToggle = new Button(getContext()); privacyToggle.setText(renderSettings.getBoolean("privacy_mode", false) ? "关闭隐私模式" : "开启隐私模式");
        privacyToggle.setOnClickListener(v -> callback.onKey("隐私模式")); choices.addView(privacyToggle);
        scroll.addView(choices, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
        addBottomMappedFixed(new String[]{"手写", "造词", ((OfflineImeService) getContext()).isNameMode() ? "退出人名" : "人名", "返回"},
                new String[]{"手写", "造词", "人名模式", "返回"}, bottomKeyHeight());
    }

    private void addLayoutChoice(LinearLayout parent, String title, String subtitle,
                                 String action, boolean selected) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setMinimumHeight(dp(76));
        int accent = theme == null ? 0xff3978d3 : theme.accent;
        int foreground = theme == null ? 0xff34383e : theme.text;
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? blendColor(theme.key, accent, dark ? 0.18f : 0.07f)
                : (theme == null ? 0xfff8fafc : theme.function));
        background.setCornerRadius(dp(UiTokens.RADIUS_CARD));
        background.setStroke(dp(selected ? 2 : 1), selected ? accent
                : (dark ? 0xff47515e : 0xffcbd3dc));
        card.setBackground(background);
        card.setContentDescription(title + "，" + subtitle + "，"
                + (selected ? "当前布局" : "点击切换"));
        card.setOnClickListener(view -> callback.onKey(selected ? "返回" : action));

        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(HORIZONTAL); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setBaselineAligned(false);
        TextView label = new TextView(getContext()); label.setText(title);
        label.setTextSize(14); label.setTextColor(foreground); label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        TextView status = new TextView(getContext()); status.setText(selected ? "✓" : "›");
        status.setTextSize(selected ? 18 : 20); status.setGravity(Gravity.CENTER);
        status.setIncludeFontPadding(false);
        status.setTextColor(selected ? accent : (dark ? 0xff8e99a8 : 0xff778291));
        titleRow.addView(label, new LayoutParams(0, -2, 1f));
        titleRow.addView(status, new LayoutParams(dp(24), -2));
        card.addView(titleRow, new LayoutParams(-1, -2));
        TextView description = new TextView(getContext());
        description.setText(subtitle);
        description.setTextSize(12);
        description.setTextColor(dark ? 0xffaeb7c4 : 0xff677180);
        description.setGravity(Gravity.CENTER_VERTICAL);
        description.setIncludeFontPadding(false);
        description.setMaxLines(2);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams descriptionParams = new LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(UiTokens.SPACE_XS);
        card.addView(description, descriptionParams);

        // Equal-width columns with natural content height prevent both horizontal
        // overflow and vertically clipped subtitles at low keyboard heights.
        LayoutParams params = new LayoutParams(0, -1, 1f);
        int gap = dp(UiTokens.SPACE_XS);
        params.setMargins(gap, gap, gap, gap);
        parent.addView(card, params);
    }

    private void renderPinyinPaths(OfflineImeService.Mode mode, String input) {
        OfflineImeService service = (OfflineImeService) getContext();
        boolean pinyinMode = mode == OfflineImeService.Mode.PINYIN
                || mode == OfflineImeService.Mode.NINE;
        // Show the current entered reading in the header's upper-left space,
        // including on the nine-key layout where choices sit beside the keys.
        String current = pinyinMode && !input.isEmpty() ? service.currentPinyinPath() : "";
        String headerLabel = current.isEmpty() ? "" : "拼音 " + current;
        if (!headerLabel.contentEquals(selectedPinyinPath.getText())) selectedPinyinPath.setText(headerLabel);
        selectedPinyinPath.setTextColor(theme.text);
        selectedPinyinPath.setContentDescription("当前拼音路径 " + current);
        selectedPinyinPath.setEnabled(service.candidateSurfaceReady());
        selectedPinyinPath.setVisibility(current.isEmpty() ? GONE : VISIBLE);
        if (!input.isEmpty() && pinyinMode && !service.candidateSurfaceReady()) {
            // Preserve the last complete path frame during rapid input. When
            // typing starts from idle or the layout changes, clear shortcuts
            // once and wait for the complete candidate/path snapshot.
            if (renderedPathInput.isEmpty() || renderedPathMode != mode) {
                hidePathChipsFrom(0);
                pathScroll.setVisibility(INVISIBLE);
                for (NinePathSlot slot : ninePathChoicePool) slot.view.setVisibility(GONE);
            }
            return;
        }
        renderedPathMode = mode;
        renderedPathInput = input;
        // Normal T9 input uses the left vertical path list. Horizontal choices
        // belong to the expanded candidate page; keep the fixed header height.
        if (mode == OfflineImeService.Mode.NINE && !candidateListShown) {
            hidePathChipsFrom(0);
            pathScroll.setVisibility(INVISIBLE);
            if (pathTouches == 0) renderNinePathChoices(input);
            return;
        }
        if (pathTouches > 0) return;
        int nextChip = 0;
        if (input.isEmpty()) {
            hidePathChipsFrom(nextChip);
            pathScroll.setVisibility(GONE);
            return;
        }
        if (mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE) {
            hidePathChipsFrom(nextChip);
            pathScroll.setVisibility(INVISIBLE);
            return;
        }
        if (mode == OfflineImeService.Mode.PINYIN) {
            String active = service.activePinyinPath();
            java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
            for (List<String> path : service.pinyinPaths(input)) {
                String label = String.join("·", path);
                if (!label.replace("·", "").equals(input.replace("'", ""))) continue;
                if (!labels.add(label)) continue;
                bindPathChoice(nextChip++, label, label.equals(active));
            }
            hidePathChipsFrom(nextChip);
            pathScroll.setVisibility(nextChip == 0 ? INVISIBLE : VISIBLE);
            pathScroll.setContentDescription("拼音路径，左右滑动，点击切换候选读音");
            return;
        }
        List<PinyinPathEditor.Segment> segments = service.pinyinSegments();
        if (candidateListShown) {
            PinyinPathEditor.Segment focused = service.focusedPinyinSegment();
            for (String reading : service.pinyinSyllableChoices(focused)) {
                List<String> syllables = new ArrayList<>();
                for (PinyinPathEditor.Segment segment : segments)
                    syllables.add(segment.start == focused.start ? reading : service.pinyinDisplayReading(segment));
                bindPathChip(nextChip++, String.join("·", syllables),
                        "选择音节:" + input + ":" + focused.start + ":" + focused.end + ":" + reading,
                        service.pinyinReadingSelected(focused, reading));
            }
            // Complete syllables and legal prefixes share the same selection
            // surface; every displayed letter comes from an entered key.
            if (!service.pinyinPathLocked()) {
                java.util.Set<String> offered = new java.util.LinkedHashSet<>();
                for (List<String> path : service.pinyinPaths(input)) {
                    String label = String.join("·", path);
                    if (path.size() < 2 || !PinyinDictionary.toT9(String.join("", path)).equals(input.replace("'", ""))
                            || !offered.add(label)) continue;
                    bindPathChoice(nextChip++, label, false);
                }
            }
            if (nextChip > 0) {
                hidePathChipsFrom(nextChip);
                pathScroll.setVisibility(VISIBLE);
                pathScroll.setContentDescription("拼音路径，左右滑动，点击切换候选读音");
                return;
            }
        }
        for (PinyinPathEditor.Segment segment : segments) {
            // No raw input fallback: T9 codes are never labels or descriptions.
            if (!segment.reading.matches("[a-z]+")) continue;
            String label = (segment.locked ? "✓ " : "") + service.pinyinDisplayReading(segment);
            bindPathChip(nextChip++, label,
                    "编辑音节:" + input + ":" + segment.start, service.pinyinSegmentFocused(segment));
        }
        hidePathChipsFrom(nextChip);
        pathScroll.setVisibility(nextChip == 0 ? INVISIBLE : VISIBLE);
    }

    private void hidePathChipsFrom(int index) {
        for (int i = index; i < pathChipPool.size(); i++)
            pathChipPool.get(i).view.setVisibility(GONE);
    }

    private void bindPathChip(int index, String label, String action, boolean selected) {
        while (pathChipPool.size() <= index) {
            TextView chip = new TextView(getContext());
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(PATH_HEIGHT_DP));
            chip.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.TRANSPARENT);
            background.setCornerRadius(dp(UiTokens.RADIUS_CONTROL));
            chip.setBackground(background);
            PathChipSlot slot = new PathChipSlot(chip);
            chip.setOnTouchListener((view, event) -> {
                trackPathTouch(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) slot.pressedAction = slot.action;
                else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) slot.pressedAction = null;
                return false;
            });
            chip.setOnClickListener(view -> {
                activatePathAction(chip, slot.pressedAction == null ? slot.action : slot.pressedAction);
                slot.pressedAction = null;
            });
            chip.setOnLongClickListener(view -> { showPinyinEditMenu(chip, slot.action); return true; });
            AccessibilitySupport.longClick(chip, "编辑其他音节");
            LayoutParams params = new LayoutParams(-2, -1);
            params.setMargins(dp(2), dp(1), dp(2), dp(1));
            paths.addView(chip, params);
            pathChipPool.add(slot);
        }
        PathChipSlot slot = pathChipPool.get(index);
        if (!label.equals(slot.label)) {
            slot.view.setText(label);
            slot.label = label;
        }
        slot.action = action;
        int textColor = selected ? theme.accent : theme.text;
        if (slot.textColor != textColor) {
            slot.view.setTextColor(textColor);
            slot.textColor = textColor;
        }
        AccessibilitySupport.selected(slot.view, selected);
        slot.view.setBackground(roundRect(selected ? blendColor(theme.key, theme.accent, 0.12f)
                : Color.TRANSPARENT, UiTokens.RADIUS_CONTROL));
        slot.view.setTypeface(android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        String description = (selected ? "当前拼音 " : "编辑拼音 ") + label;
        if (!description.equals(slot.description)) {
            slot.view.setContentDescription(description);
            slot.description = description;
        }
        if (slot.view.getVisibility() != VISIBLE) slot.view.setVisibility(VISIBLE);
    }

    private void bindPathChoice(int index, String label, boolean selected) {
        bindPathChip(index, label, "选择完整路径:" + ((OfflineImeService) getContext()).pinyinRawInput() + ":" + label, selected);
        PathChipSlot slot = pathChipPool.get(index);
        String description = (selected ? "当前拼音路径 " : "选择拼音路径 ") + label;
        if (!description.equals(slot.description)) {
            slot.view.setContentDescription(description);
            slot.description = description;
        }
    }

    private void renderNinePathChoices(String input) {
        if (ninePathPanel == null || ninePathScroll == null
                || ninePathScroll.getParent() == null) return;
        String value = input == null ? "" : input;
        setNinePathPanelFillViewport(value.isEmpty());
        OfflineImeService service = (OfflineImeService) getContext();
        PinyinPathEditor.Segment focus = service.focusedPinyinSegment();
        int slotIndex = 0;
        int focusStart = focus == null ? -1 : focus.start;
        if (value.isEmpty()) {
            String[] shortcuts = {"，", "。", "？", "！"};
            for (String symbol : shortcuts) {
                bindNineSymbolSlot(slotIndex++, symbol);
            }
        } else {
            java.util.Map<String, PinyinPathEditor.Segment> choices = new java.util.TreeMap<>(PinyinDisplayOrder.READINGS);
            for (PinyinPathEditor.Segment option : service.ninePinyinChoiceSegments())
                for (String reading : service.pinyinSyllableChoices(option)) choices.putIfAbsent(reading, option);
            for (java.util.Map.Entry<String, PinyinPathEditor.Segment> choice : choices.entrySet()) {
                String reading = choice.getKey(); PinyinPathEditor.Segment option = choice.getValue();
                boolean selected = service.pinyinReadingSelected(option, reading);
                bindNinePathSlot(slotIndex++, (selected ? "✓ " : "") + reading,
                        "选择音节:" + value + ":" + option.start + ":" + option.end + ":" + reading,
                        selected, "选择读音 " + reading + "，按长度和字母排序");
            }
        }
        for (int i = slotIndex; i < ninePathChoicePool.size(); i++)
            ninePathChoicePool.get(i).view.setVisibility(GONE);
        if (!value.equals(renderedNinePathInput) || focusStart != renderedNineFocusStart)
            if (ninePathScroll.getScrollY() != 0) ninePathScroll.scrollTo(0, 0);
        renderedNinePathInput = value;
        renderedNineFocusStart = focusStart;
    }

    private void bindNinePathSlot(int index, CharSequence label, String action, boolean selected, String description) {
        NinePathSlot slot = ensureNinePathSlot(index);
        slot.quickSymbol = false;
        slot.action = action;
        slot.view.setMinimumHeight(dp(40));
        setNinePathSlotSize(slot, -2, dp(2), 0f);
        slot.view.setSingleLine(true);
        slot.view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        slot.view.setIncludeFontPadding(false);
        slot.view.setTextSize(14);
        slot.view.setPadding(dp(4), dp(5), dp(4), dp(5));
        slot.view.setText(label); slot.label = label.toString();
        slot.view.setTypeface(android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        fitNinePathLabel(slot);
        int textColor = selected ? theme.accent : theme.text;
        int backgroundColor = selected ? blendColor(theme.key, theme.accent, 0.16f) : Color.TRANSPARENT;
        if (selected != slot.selected || textColor != slot.textColor) {
            slot.view.setTextColor(textColor);
            slot.textColor = textColor;
        }
        description = (selected ? "已选，" : "") + description;
        if (!description.equals(slot.description)) {
            slot.view.setContentDescription(description);
            slot.description = description;
        }
        if (!slot.hasStyle || backgroundColor != slot.backgroundColor) {
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed},
                    roundRect(blendColor(theme.key, theme.accent, dark ? 0.28f : 0.12f), UiTokens.RADIUS_CONTROL));
            states.addState(new int[]{}, roundRect(backgroundColor, UiTokens.RADIUS_CONTROL));
            slot.view.setBackground(states);
            slot.backgroundColor = backgroundColor;
            slot.hasStyle = true;
        }
        AccessibilitySupport.selected(slot.view, selected);
        slot.selected = selected;
        if (slot.view.getVisibility() != VISIBLE) slot.view.setVisibility(VISIBLE);
    }

    private void bindNineSymbolSlot(int index, String symbol) {
        NinePathSlot slot = ensureNinePathSlot(index);
        boolean styleChanged = !slot.hasStyle || !slot.quickSymbol
                || slot.backgroundColor != theme.function;
        slot.quickSymbol = true;
        AccessibilitySupport.selected(slot.view, false);
        slot.selected = false;
        if (!symbol.equals(slot.label)) {
            slot.view.setText(symbol);
            slot.label = symbol;
        }
        slot.view.setTextSize(16);
        slot.view.setMinimumHeight(0);
        slot.view.setTextColor(theme.text);
        slot.textColor = theme.text;
        slot.view.setPadding(dp(2), 0, dp(2), 0);
        slot.view.setSingleLine(true);
        slot.view.setIncludeFontPadding(false);
        // Idle shortcuts share the available sidebar height evenly. This keeps
        // the punctuation buttons easy to hit without leaving a large blank
        // band above and below them.
        setNinePathSlotSize(slot, 0, dp(nineKeyVerticalGap()), 1f);
        String description = "插入快捷符号 " + symbol;
        if (!description.equals(slot.description)) {
            slot.view.setContentDescription(description);
            slot.description = description;
        }
        if (styleChanged) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(theme.function);
            background.setCornerRadius(dp(Math.round(theme.radius)));
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed},
                    roundRect(blendColor(theme.function, theme.accent, dark ? 0.28f : 0.16f),
                            Math.round(theme.radius)));
            states.addState(new int[]{}, background);
            slot.view.setBackground(states);
            slot.backgroundColor = theme.function;
            slot.hasStyle = true;
        }
        if (slot.view.getVisibility() != VISIBLE) slot.view.setVisibility(VISIBLE);
    }

    private NinePathSlot ensureNinePathSlot(int index) {
        while (ninePathChoicePool.size() <= index) {
            TextView view = new TextView(getContext());
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(false);
            view.setPadding(dp(4), dp(3), dp(4), dp(3));
            NinePathSlot slot = new NinePathSlot(view);
            view.addOnLayoutChangeListener((changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft && !slot.quickSymbol) fitNinePathLabel(slot);
            });
            view.setOnTouchListener((pressed, event) -> {
                trackPathTouch(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                    slot.pressedAction = slot.quickSymbol ? slot.label : slot.action;
                else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) slot.pressedAction = null;
                return false;
            });
            view.setOnClickListener(clicked -> {
                activatePathAction(view, slot.pressedAction == null
                        ? slot.quickSymbol ? slot.label : slot.action : slot.pressedAction);
                slot.pressedAction = null;
            });
            view.setOnLongClickListener(pressed -> {
                if (slot.quickSymbol) return false;
                showPinyinEditMenu(view, slot.action); return true;
            });
            LayoutParams params = new LayoutParams(-1, dp(34));
            params.setMargins(dp(3), dp(2), dp(3), dp(2));
            ninePathPanel.addView(view, params);
            ninePathChoicePool.add(slot);
        }
        return ninePathChoicePool.get(index);
    }

    private void fitNinePathLabel(NinePathSlot slot) {
        int width = slot.view.getWidth() - slot.view.getPaddingLeft() - slot.view.getPaddingRight();
        if (width <= 0 || slot.quickSymbol) return;
        android.graphics.Paint measure = new android.graphics.Paint(slot.view.getPaint());
        measure.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        float measured = measure.measureText(slot.label);
        slot.view.setTextSize(measured > width ? Math.max(11, 14 * width / measured) : 14);
    }

    private void setNinePathPanelFillViewport(boolean fill) {
        ViewGroup.LayoutParams params = ninePathPanel.getLayoutParams();
        int height = fill ? -1 : -2;
        if (params != null && params.height != height) {
            params.height = height;
            ninePathPanel.setLayoutParams(params);
        }
    }

    private void setNinePathSlotSize(NinePathSlot slot, int height, int verticalMargin, float weight) {
        LayoutParams params = (LayoutParams) slot.view.getLayoutParams();
        int horizontalMargin = slot.quickSymbol ? 0 : dp(3);
        if (params.height != height || params.leftMargin != horizontalMargin
                || params.topMargin != verticalMargin || params.rightMargin != horizontalMargin
                || params.bottomMargin != verticalMargin || params.weight != weight) {
            params.height = height;
            params.weight = weight;
            params.setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin);
            slot.view.setLayoutParams(params);
        }
    }

    private void styleCandidate(TextView item, String word, boolean first, int foreground,
                                int number, boolean showNumber) {
        String marker = showNumber && number > 0 ? number + " " : "";
        SpannableString label = new SpannableString(marker + word);
        if (!marker.isEmpty()) {
            label.setSpan(new RelativeSizeSpan(0.62f), 0, marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(first ? theme.accent : blendColor(foreground, theme.key, 0.32f)),
                    0, marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        item.setText(label); item.setTextSize(candidateFontSp());
        item.setTextColor(first ? theme.accent : foreground);
        item.setGravity(Gravity.CENTER); item.setPadding(dp(7), 0, dp(7), 0);
        item.setMinWidth(dp(30));
        item.setMinHeight(dp(candidateHeightDp()));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(theme.function));
        GradientDrawable normal = roundRect(first ? blendColor(theme.key, theme.accent, 0.09f)
                : Color.TRANSPARENT, first ? 10 : 0);
        if (first) normal.setStroke(dp(1), blendColor(theme.accent, theme.key, 0.35f));
        states.addState(new int[]{}, normal);
        item.setBackground(states);
    }

    private static int blendColor(int base, int overlay, float amount) {
        int alpha = Math.round(255 * amount);
        int red = ((base >> 16) & 0xff) * (255 - alpha) / 255
                + ((overlay >> 16) & 0xff) * alpha / 255;
        int green = ((base >> 8) & 0xff) * (255 - alpha) / 255
                + ((overlay >> 8) & 0xff) * alpha / 255;
        int blue = (base & 0xff) * (255 - alpha) / 255
                + (overlay & 0xff) * alpha / 255;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    public void showCandidatePage(OfflineImeService.Mode mode, String input,
                                  List<PinyinDictionary.Entry> words, int requestedPage) {
        if (candidateListShown && (pathTouches > 0 || expandedCandidatesTouched || activeMenu != null)) {
            expandedPageRefreshPending = true;
            return;
        }
        expandedPageRefreshPending = false;
        OfflineImeService service = (OfflineImeService) getContext();
        String pageKey = mode.name() + ':' + input + ':' + service.activePinyinPath();
        boolean sameSelection = pageKey.equals(expandedCandidateKey);
        if (candidateListShown && sameSelection && words == expandedCandidateWords
                && expandedCandidatesScroll != null) {
            expandedPageRefreshPending = false;
            renderPinyinPaths(mode, input);
            return;
        }
        int previousScrollY = expandedCandidatesScroll == null || !sameSelection
                ? 0 : expandedCandidatesScroll.getScrollY();
        String focusedWord = expandedCandidatesScroll instanceof CandidatePanel
                ? ((CandidatePanel) expandedCandidatesScroll).focusedWord() : "";
        candidateListShown = true;
        applyLayoutPreferences();
        hideComposingHeader();
        // The expanded page is a focused candidate surface. Keeping the normal
        // tool strip here wastes one full row and makes the page feel nested.
        toolbarRow.setVisibility(GONE);
        candidateBar.setVisibility(GONE);
        composing.setVisibility(GONE);
        removeRows();
        expandedCandidateKey = pageKey;
        renderPinyinPaths(mode, input);
        if (!sameSelection) pathScroll.scrollTo(0, 0);

        int count = words == null ? 0 : words.size();
        ArrayList<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) order.add(i);

        LinearLayout header = new KeyTouchRow(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(getContext());
        heading.setText(mode == OfflineImeService.Mode.ENGLISH ? "English suggestions"
                : mode == OfflineImeService.Mode.STROKE ? "笔画候选"
                : input.isEmpty() && words != null && !words.isEmpty() ? "下一词建议" : "中文候选词");
        heading.setTextSize(13); heading.setTextColor(theme.text);
        heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(12), 0, dp(12), 0);
        heading.setContentDescription(heading.getText());
        header.addView(heading, new LayoutParams(0, -1, 1f));
        TextView countLabel = new TextView(getContext());
        countLabel.setText(count + " 个");
        countLabel.setTextSize(12); countLabel.setTextColor(theme.text); countLabel.setGravity(Gravity.CENTER);
        countLabel.setContentDescription("共" + count + "个候选词");
        header.addView(countLabel, new LayoutParams(dp(64), -1));
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(candidateHeightDp()), Gravity.TOP);
        titleParams.topMargin = dp(PATH_HEIGHT_DP);
        this.header.addView(header, titleParams);
        expandedHeaderTitle = header;

        expandedCandidateWords = words == null ? java.util.Collections.emptyList() : words;
        CandidatePanel panel = new CandidatePanel(getContext(), expandedCandidateWords, order,
                candidateFontSp(), renderSettings.getString("expanded_candidate_density", "standard"), new CandidatePanel.Host() {
            public void style(TextView view, String word, boolean first) {
                styleCandidate(view, word, first, theme.text, 0, false);
            }
            public void select(PinyinDictionary.Entry entry) { callback.onCandidateEntry(entry); }
            public void menu(View anchor, PinyinDictionary.Entry entry) { showCandidateMenu(anchor, entry); }
            public void touching(boolean active) {
                expandedCandidatesTouched = active;
                if (!active && expandedPageRefreshPending && activeMenu == null) service.refreshPinyinPaths();
            }
        });
        expandedCandidatesScroll = panel;
        addView(panel, new LayoutParams(-1, 0, 1f));
        addBottomMappedFixed(new String[]{"⌫", "空格", "返回"},
                new String[]{"⌫", "空格", "返回"}, bottomKeyHeight());
        panel.post(() -> {
            if (expandedCandidatesScroll == panel) {
                panel.scrollTo(0, previousScrollY);
                panel.restoreFocus(focusedWord);
            }
        });
    }

    public void showTools() {
        applyLayoutPreferences();
        activeToolbarAction = "工具";
        addIconToolbar();
        hideComposingHeader();
        removeRows();
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout directions = new LinearLayout(getContext());
        directions.setOrientation(LinearLayout.VERTICAL);
        directions.setContentDescription("方向键控制区");
        addEditorRow(directions, new String[]{"", "↑", ""});
        addEditorRow(directions, new String[]{"←", "光标", "→"});
        addEditorRow(directions, new String[]{"", "↓", ""});
        int directionGap = Math.max(0, getIntPreference(renderSettings, "key_gap", 2));
        EditorDirectionPad directionPad = new EditorDirectionPad(getContext(), directions,
                dp(3 * (54 + 2 * directionGap)));
        body.addView(directionPad, new LayoutParams(0, -1, 0.42f));

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setContentDescription("文本编辑操作区");
        addEditorRow(actions, new String[]{"选择", "全选", "剪贴板"});
        addEditorRow(actions, new String[]{"复制", "剪切", "粘贴"});
        addEditorRow(actions, new String[]{"行首", "行尾", "⌫词"});
        addEditorRow(actions, new String[]{"撤销", "重做", "删除"});
        LayoutParams actionsParams = new LayoutParams(0, -1, 0.58f);
        actionsParams.leftMargin = dp(UiTokens.SPACE_XS);
        body.addView(actions, actionsParams);
        addView(body, new LayoutParams(-1, 0, 4f));
        addBottom(new String[]{"返回"});
    }

    /** Keep the cross square and centered when height, rotation or insets change. */
    private static final class EditorDirectionPad extends FrameLayout {
        private final View cross;
        private final int maximumSide;

        EditorDirectionPad(Context context, View cross, int maximumSide) {
            super(context);
            this.cross = cross;
            this.maximumSide = maximumSide;
            addView(cross, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? maximumSide : MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? maximumSide : MeasureSpec.getSize(heightMeasureSpec);
            int side = Math.max(0, Math.min(maximumSide, Math.min(
                    width - getPaddingLeft() - getPaddingRight(),
                    height - getPaddingTop() - getPaddingBottom())));
            // Update the existing params during measurement without scheduling another layout.
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cross.getLayoutParams();
            params.width = side;
            params.height = side;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void addEditorRow(LinearLayout parent, String[] labels) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER);
        for (String label : labels) {
            if (label.isEmpty()) {
                row.addView(new View(getContext()), new LayoutParams(0, -1, 1f));
            } else if ("光标".equals(label)) {
                TextView title = new TextView(getContext());
                title.setText(label);
                title.setTextSize(11);
                title.setTextColor(blendColor(theme.text, theme.background, 0.4f));
                title.setGravity(Gravity.CENTER);
                title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(title, new LayoutParams(0, -1, 1f));
            } else {
                KeyButton button = addKey(row, label, label, 1f);
                OfflineImeService service = (OfflineImeService) getContext();
                if ("复制".equals(label) || "剪切".equals(label)) {
                    button.setEnabled(service.hasEditorSelection()); button.setAlpha(button.isEnabled() ? 1f : 0.45f);
                }
                if ("选择".equals(label)) {
                    AccessibilitySupport.selected(button, service.selectionActive());
                    button.setContentDescription(service.selectionActive() ? "选择模式已开启，点击退出" : "开启选择模式");
                }
            }
        }
        parent.addView(row, new LayoutParams(-1, 0, 1f));
    }

    private ClipboardPanel clipboardPanel() {
        applyLayoutPreferences(); activeToolbarAction = "剪贴板";
        addIconToolbar(); hideComposingHeader(); removeRows();
        ClipboardPanel panel = new ClipboardPanel(getContext(), callback, theme, dark, this::showCardMenu);
        addView(panel, new LayoutParams(-1, 0, 1f));
        return panel;
    }
    public void showClipboardHistory(List<ClipboardHistoryStore.Entry> values, String filter,
                                    String query, boolean confirmingClear, boolean undoAvailable,
                                    boolean multiSelect, Set<String> selected) {
        clipboardPanel().showClipboardHistory(values, filter, query, confirmingClear, undoAvailable, multiSelect, selected);
    }
    public void showClipboardEnablePrompt() { clipboardPanel().showClipboardEnablePrompt(); }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    public void showHandwriting() {
        applyLayoutPreferences();
        activeToolbarAction = "手写";
        addIconToolbar();
        hideComposingHeader();
        removeRows();
        LinearLayout suggestions = new LinearLayout(getContext());
        suggestions.setOrientation(HORIZONTAL);
        suggestions.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView suggestionScroll = new HorizontalScrollView(getContext());
        suggestionScroll.setHorizontalScrollBarEnabled(false);
        suggestionScroll.addView(suggestions, new HorizontalScrollView.LayoutParams(-2, -1));
        TextView handwritingStatus = new TextView(getContext());
        handwritingStatus.setText("扩展离线模型 · 请书写后停笔");
        handwritingStatus.setTextSize(11);
        handwritingStatus.setTextColor(theme.text);
        handwritingStatus.setPadding(dp(10), 0, dp(10), 0);
        handwritingStatus.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout modeBar = new LinearLayout(getContext());
        modeBar.setGravity(Gravity.CENTER_VERTICAL);
        modeBar.addView(handwritingStatus, new LayoutParams(0, -1, 1f));
        TextView modeToggle = new TextView(getContext());
        modeToggle.setTextSize(13); modeToggle.setTextColor(theme.text); modeToggle.setGravity(Gravity.CENTER);
        modeToggle.setPadding(dp(8), 0, dp(8), 0);
        modeBar.addView(modeToggle, new LayoutParams(-2, -1));
        TextView splitCharacter = new TextView(getContext());
        splitCharacter.setText("分字"); splitCharacter.setTextSize(13); splitCharacter.setTextColor(theme.text);
        splitCharacter.setGravity(Gravity.CENTER); splitCharacter.setPadding(dp(8), 0, dp(10), 0);
        splitCharacter.setContentDescription("标记当前字已写完，继续写下一字");
        modeBar.addView(splitCharacter, new LayoutParams(-2, -1));
        LinearLayout draftRow = new LinearLayout(getContext());
        draftRow.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView draftScroll = new HorizontalScrollView(getContext());
        draftScroll.setHorizontalScrollBarEnabled(false);
        draftScroll.addView(draftRow, new HorizontalScrollView.LayoutParams(-2, -1));
        draftScroll.setVisibility(GONE);
        final HandwritingView[] pad = new HandwritingView[1];
        HandwritingView canvas = new HandwritingView(getContext(), new HandwritingView.Callback() {
            public void onCandidates(List<String> values) {
                renderHandwritingCandidates(suggestions, values, pad);
                suggestionScroll.scrollTo(0, 0);
            }
            public void onClose() { callback.onKey("返回手写"); }
            public void onStatus(String message) { handwritingStatus.setText(message); }
            public void onDraft(String value) {
                renderHandwritingDraft(draftRow, value, pad);
                draftScroll.setVisibility(value.isEmpty() ? GONE : VISIBLE);
            }
        });
        pad[0] = canvas;
        activeHandwritingView = canvas;
        boolean creatingWord = ((OfflineImeService) getContext()).isHandwritingWordCreation();
        if (creatingWord) canvas.forceSingleCharacter();
        modeToggle.setText(canvas.isContinuous() ? "连写 ▾" : "单字 ▾");
        modeToggle.setContentDescription("切换单字和连续多字手写");
        modeToggle.setEnabled(!creatingWord);
        splitCharacter.setVisibility(canvas.isContinuous() ? VISIBLE : GONE);
        modeToggle.setOnClickListener(view -> {
            canvas.setContinuous(!canvas.isContinuous());
            modeToggle.setText(canvas.isContinuous() ? "连写 ▾" : "单字 ▾");
            splitCharacter.setVisibility(canvas.isContinuous() ? VISIBLE : GONE);
        });
        splitCharacter.setOnClickListener(view -> canvas.markCharacterBoundary());
        addView(suggestionScroll, new LayoutParams(-1, dp(42)));
        addView(modeBar, new LayoutParams(-1, dp(32)));
        addView(draftScroll, new LayoutParams(-1, dp(34)));
        addView(canvas, new LayoutParams(-1, 0, 3f));
        renderHandwritingCandidates(suggestions, new ArrayList<>(), pad);
        addBottomProportioned(new String[]{"撤销", "清空", "返回", "空格", "回车"},
                new String[]{"撤销笔画", "清空手写", "返回手写", "空格", "回车"},
                new float[]{22f, 18f, 18f, 22f, 20f}, bottomKeyHeight());
    }

    private void renderHandwritingCandidates(LinearLayout row, List<String> values, HandwritingView[] pad) {
        row.removeAllViews();
        if (values == null || values.isEmpty()) {
            TextView hint = new TextView(getContext());
            hint.setText(pad[0] != null && pad[0].hasStrokes() ? "书写中，停笔后显示候选"
                    : pad[0] != null && pad[0].isContinuous() ? "从左向右连续写字，停笔后点选整段结果" : "书写后暂停，点选识别结果");
            hint.setTextSize(13);
            hint.setTextColor(theme.text);
            hint.setGravity(Gravity.CENTER_VERTICAL);
            hint.setPadding(dp(10), 0, dp(10), 0);
            hint.setContentDescription("手写候选等待区");
            row.addView(hint, new LayoutParams(-1, -1));
            return;
        }
        for (String value : values) {
            TextView candidate = new TextView(getContext());
            candidate.setText(value);
            candidate.setTextSize(20);
            candidate.setTextColor(theme.text);
            candidate.setGravity(Gravity.CENTER);
            candidate.setPadding(dp(15), 0, dp(15), 0);
            candidate.setContentDescription(value);
            GradientDrawable background = new GradientDrawable();
            background.setColor(theme.key);
            background.setCornerRadius(dp(Math.round(theme.radius)));
            candidate.setBackground(background);
            LayoutParams params = new LayoutParams(-2, dp(38));
            params.setMargins(dp(3), dp(2), dp(3), dp(2));
            row.addView(candidate, params);
            candidate.setOnClickListener(view -> {
                if (pad[0] != null) pad[0].clear();
                callback.onKey("手写字:" + value);
            });
        }
    }

    public void undoHandwriting() {
        if (activeHandwritingView != null) activeHandwritingView.undoLastStroke();
    }

    private void renderHandwritingDraft(LinearLayout row, String value, HandwritingView[] pad) {
        row.removeAllViews();
        if (value.isEmpty()) return;
        TextView label = new TextView(getContext()); label.setText("改字：");
        label.setTextSize(12); label.setTextColor(theme.text); label.setPadding(dp(8), 0, dp(4), 0);
        row.addView(label, new LayoutParams(-2, -1));
        int[] points = value.codePoints().toArray();
        for (int index = 0; index < points.length; index++) {
            final int position = index;
            String character = new String(Character.toChars(points[index]));
            TextView button = new TextView(getContext()); button.setText(character);
            button.setTextSize(19); button.setTextColor(theme.text); button.setGravity(Gravity.CENTER);
            button.setPadding(dp(10), 0, dp(10), 0); button.setContentDescription("修改第 " + (index + 1) + " 个字 " + character);
            row.addView(button, new LayoutParams(-2, -1));
            button.setOnClickListener(view -> {
                if (pad[0] == null) return;
                List<String> choices = pad[0].alternativesFor(position);
                String expectedDraft = pad[0].getDraft();
                RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "修改第 " + (position + 1) + " 个字", theme);
                for (String choice : choices) menu.item(choice, null, null, false, false, () -> {
                    if (expectedDraft.equals(pad[0].getDraft())) pad[0].replaceDraftCharacter(position, choice);
                });
                menu.item("删除这个字", null, null, false, true, () -> {
                    if (expectedDraft.equals(pad[0].getDraft())) pad[0].replaceDraftCharacter(position, "");
                });
                menu.show(button);
            });
        }
    }

    public void clearHandwriting() {
        if (activeHandwritingView != null) activeHandwritingView.clear();
    }

    private String selectedEmojiCategory = "最近";
    private String selectedEmojiStyle = EmojiStyleAssets.SYSTEM;
    private boolean emojiPanelShown;
    private ScrollView emojiContent;
    private LinearLayout emojiGrid;
    private TextView emojiHint;
    private EmojiStyleAssets emojiStyleAssets;
    private final java.util.Map<String, TextView> emojiStyleTabs = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TextView> emojiCategoryTabs = new java.util.LinkedHashMap<>();
    public void showEmoji() {
        String savedStyle = getContext().getSharedPreferences("emoji", Context.MODE_PRIVATE)
                .getString("style", EmojiStyleAssets.SYSTEM);
        selectedEmojiStyle = EmojiStyleAssets.known(savedStyle) ? savedStyle : EmojiStyleAssets.SYSTEM;
        showEmoji(selectedEmojiCategory);
    }
    public void showEmoji(String category) {
        selectedEmojiCategory = category;
        if (emojiPanelShown && emojiContent != null && emojiContent.getParent() == this) {
            updateEmojiCategory(category);
            return;
        }
        applyLayoutPreferences();
        activeToolbarAction = "😊";
        hideComposingHeader();
        removeRows();
        showEmojiStylesInToolbar();
        addEmojiCategories(category);
        emojiHint = new TextView(getContext());
        emojiHint.setTextSize(11);
        emojiHint.setTextColor(theme.text);
        emojiHint.setAlpha(0.65f);
        emojiHint.setGravity(Gravity.CENTER_VERTICAL);
        emojiHint.setPadding(dp(12), 0, dp(12), 0);
        emojiHint.setVisibility(GONE);
        addView(emojiHint, new LayoutParams(-1, dp(22)));
        emojiGrid = new LinearLayout(getContext());
        emojiGrid.setOrientation(VERTICAL);
        emojiContent = new ScrollView(getContext());
        emojiContent.setFillViewport(false);
        emojiContent.addView(emojiGrid, new ScrollView.LayoutParams(-1, -2));
        addView(emojiContent, new LayoutParams(-1, 0, 3f));
        addBottom(new String[]{"搜索表情", "清除表情搜索", "返回"});
        getChildAt(getChildCount() - 1).setLayoutParams(new LayoutParams(-1, dp(48)));
        emojiPanelShown = true;
        updateEmojiCategory(category);
    }

    public void setEmojiStyle(String style) {
        if (!EmojiStyleAssets.known(style)) return;
        selectedEmojiStyle = style;
        getContext().getSharedPreferences("emoji", Context.MODE_PRIVATE)
                .edit().putString("style", style).apply();
        for (java.util.Map.Entry<String, TextView> entry : emojiStyleTabs.entrySet()) {
            boolean active = entry.getKey().equals(style);
            TextView tab = entry.getValue();
            tab.setSelected(active);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setBackground(roundRect(active ? theme.function : Color.TRANSPARENT, 12));
            tab.setContentDescription(EmojiStyleAssets.label(entry.getKey()) + "表情样式"
                    + (active ? "，已选中" : ""));
        }
        if (emojiPanelShown) updateEmojiCategory(selectedEmojiCategory, true);
    }

    private void updateEmojiCategory(String category) {
        updateEmojiCategory(category, false);
    }

    private void updateEmojiCategory(String category, boolean keepScroll) {
        int previousScroll = emojiContent == null ? 0 : emojiContent.getScrollY();
        for (java.util.Map.Entry<String, TextView> entry : emojiCategoryTabs.entrySet()) {
            boolean active = entry.getKey().equals(category);
            TextView tab = entry.getValue();
            tab.setSelected(active);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setBackground(roundRect(active ? theme.function : Color.TRANSPARENT, 12));
        }
        if (emojiHint == null || emojiGrid == null || emojiContent == null) return;
        boolean styled = !EmojiStyleAssets.SYSTEM.equals(selectedEmojiStyle);
        emojiHint.setVisibility(styled ? VISIBLE : GONE);
        if ("颜文字".equals(category) && styled) emojiHint.setText("颜文字不受样式设置影响");
        else if (styled) emojiHint.setText("样式仅改变本机预览 · 发送标准 Emoji · 缺图回退系统");
        String[] values = emojiValues(category);
        if (emojiStyleAssets != null) emojiStyleAssets.cancelPending();
        emojiGrid.removeAllViews();
        if (values.length == 0) {
            TextView empty = new TextView(getContext());
            empty.setText(((OfflineImeService) getContext()).emojiSearchQuery().isEmpty() ? "长按表情或颜文字可收藏、选择肤色" : "没有匹配的表情，请换个关键词");
            empty.setTextColor(theme.text);
            empty.setGravity(Gravity.CENTER);
            emojiGrid.addView(empty, new LayoutParams(-1, dp(90)));
        }
        // Keep long text wide even when it appears among recent/favorite emoji.
        for (int from = 0; from < values.length;) {
            boolean text = EmojiCatalog.isKaomoji(values[from]);
            int to = from + 1;
            int columns = text ? 2 : 8;
            while (to < values.length && to - from < columns
                    && EmojiCatalog.isKaomoji(values[to]) == text) to++;
            if (text) addKaomojiRow(emojiGrid, values, from, to);
            else addEmojiRow(emojiGrid, values, from, to);
            from = to;
        }
        emojiContent.scrollTo(0, keepScroll ? previousScroll : 0);
    }

    private void showEmojiStylesInToolbar() {
        toolbarRenderKey = null;
        toolbarRow.removeAllViews();
        toolbarRow.setPadding(0, 0, 0, 0);
        toolbarRow.setBackgroundColor(theme.background);
        toolbarRow.setVisibility(VISIBLE);
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        emojiStyleTabs.clear();
        for (String style : EmojiStyleAssets.STYLES) {
            TextView tab = new TextView(getContext());
            boolean active = style.equals(selectedEmojiStyle);
            tab.setText(EmojiStyleAssets.label(style));
            tab.setTextSize(14);
            tab.setGravity(Gravity.CENTER);
            tab.setSelected(active);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setContentDescription(EmojiStyleAssets.label(style) + "表情样式"
                    + (active ? "，已选中" : ""));
            tab.setBackground(roundRect(active ? theme.function : Color.TRANSPARENT, 12));
            tab.setOnClickListener(v -> callback.onKey("表情样式:" + style));
            LayoutParams lp = new LayoutParams(dp(style.equals(EmojiStyleAssets.SYSTEM) ? 58 : 90), dp(40));
            lp.setMargins(dp(2), dp(1), dp(2), dp(1));
            row.addView(tab, lp);
            emojiStyleTabs.put(style, tab);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        toolbarRow.addView(scroll, new LayoutParams(-1, -1));
    }

    private void addEmojiCategories(String selected) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        emojiCategoryTabs.clear();
        String[] categories = EmojiCatalog.CATEGORIES;
        for (int i = 0; i < categories.length; i++) {
            final String value = categories[i];
            boolean wordTab = "最近".equals(value) || "收藏".equals(value)
                    || "颜文字".equals(value);
            TextView tab = new TextView(getContext());
            tab.setText(EmojiCatalog.categoryLabel(value));
            tab.setTextSize(wordTab ? 14 : 20);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(value);
            tab.setSelected(value.equals(selected));
            if (value.equals(selected)) tab.post(() -> scroll.scrollTo(Math.max(0, tab.getLeft() - dp(100)), 0));
            tab.setPadding(dp(4), 0, dp(4), 0);
            tab.setTextColor(value.equals(selected) ? theme.accent : theme.text);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(value.equals(selected) ? theme.function : Color.TRANSPARENT);
            bg.setCornerRadius(dp(12));
            tab.setBackground(bg);
            tab.setOnClickListener(v -> callback.onKey("表情分类:" + value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp("颜文字".equals(value) ? 64 : 48), dp(40));
            lp.setMargins(dp(2), dp(1), dp(2), dp(1));
            row.addView(tab, lp);
            emojiCategoryTabs.put(value, tab);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        addView(scroll, new LayoutParams(-1, dp(42)));
    }

    private String[] emojiValues(String category) {
        String query = ((OfflineImeService) getContext()).emojiSearchQuery();
        if (!query.isEmpty()) return EmojiSearch.find(query);
        if ("最近".equals(category)) {
            String[] recent = EmojiCatalog.recent(getContext().getSharedPreferences("emoji", Context.MODE_PRIVATE));
            if (recent.length > 0) return recent;
        }
        if ("收藏".equals(category)) {
            java.util.ArrayList<String> favorites = new java.util.ArrayList<>();
            android.content.SharedPreferences p = getContext().getSharedPreferences("emoji", Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                if (entry.getKey().startsWith("favorite_") && Boolean.TRUE.equals(entry.getValue()))
                    favorites.add(entry.getKey().substring(9));
            }
            java.util.Collections.sort(favorites);
            return favorites.toArray(new String[0]);
        }
        return EmojiCatalog.values(category);
    }

    private void addEmojiRow(LinearLayout grid, String[] values, int from, int to) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER);
        if (emojiStyleAssets == null) emojiStyleAssets = new EmojiStyleAssets(getContext());
        for (int i = from; i < to; i++) {
            final String emoji = values[i];
            FrameLayout tile = new FrameLayout(getContext());
            TextView glyph = new TextView(getContext()); glyph.setText(emoji); glyph.setTextSize(25);
            glyph.setGravity(Gravity.CENTER); glyph.setTextColor(theme == null ? 0xff202124 : theme.text);
            glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
            emojiStyleAssets.load(selectedEmojiStyle, emoji, artwork -> {
                if (!tile.isAttachedToWindow()) return;
                ImageView picture = new ImageView(getContext()); picture.setImageBitmap(artwork);
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER); picture.setPadding(dp(3), dp(3), dp(3), dp(3));
                picture.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.removeAllViews(); tile.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            });
            View button = tile;
            button.setContentDescription(EmojiSearch.description(emoji));
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setOnClickListener(v -> callback.onKey("表情:" + emoji));
            button.setOnLongClickListener(v -> {
                RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), EmojiSearch.description(emoji), theme);
                for (String variant : EmojiSearch.skinTones(emoji)) menu.item(variant, null, null, variant.equals(emoji), false, () -> callback.onKey("表情:" + variant));
                if (!renderSettings.getBoolean("privacy_mode", false)) menu.item("收藏 / 取消收藏", null, null, false, false, () -> callback.onKey("收藏表情:" + emoji));
                showCardMenu(v, menu); return true;
            });
            AccessibilitySupport.longClick(button, "选择肤色或收藏表情");
            LayoutParams lp = new LayoutParams(0, -1, 1f);
            int gap = getIntPreference(getContext().getSharedPreferences("settings", Context.MODE_PRIVATE), "key_gap", 2);
            lp.setMargins(dp(gap), dp(gap), dp(gap), dp(gap));
            row.addView(button, lp);
        }
        for (int i = to - from; i < 8; i++) {
            row.addView(new View(getContext()), new LayoutParams(0, -1, 1f));
        }
        grid.addView(row, new LayoutParams(-1, dp(64)));
    }

    /** 颜文字使用独立的双列大卡片，避免长字符串被 Emoji 网格压缩或裁切。 */
    private void addKaomojiRow(LinearLayout grid, String[] values, int from, int to) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER);
        int gap = getIntPreference(getContext().getSharedPreferences("settings", Context.MODE_PRIVATE), "key_gap", 2);
        for (int i = from; i < to; i++) {
            final String kaomoji = values[i];
            TextView button = new TextView(getContext());
            button.setText(kaomoji);
            button.setTextSize(18);
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(false);
            button.setMaxLines(2);
            button.setContentDescription(kaomoji);
            button.setTextColor(theme == null ? 0xff202124 : theme.text);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setOnClickListener(v -> callback.onKey("表情:" + kaomoji));
            button.setOnLongClickListener(v -> { callback.onKey("收藏表情:" + kaomoji); return true; });
            LayoutParams lp = new LayoutParams(0, -1, 1f);
            lp.setMargins(dp(gap), dp(gap), dp(gap), dp(gap));
            row.addView(button, lp);
        }
        if (to - from < 2) row.addView(new View(getContext()), new LayoutParams(0, -1, 1f));
        grid.addView(row, new LayoutParams(-1, dp(56)));
    }

    private void removeRows() {
        if (emojiStyleAssets != null) emojiStyleAssets.cancelPending();
        symbolPanelShown = false;
        symbolContent = null;
        symbolGrid = null;
        symbolCategoryTabs.clear();
        emojiPanelShown = false;
        emojiContent = null;
        emojiGrid = null;
        emojiHint = null;
        emojiStyleTabs.clear();
        emojiCategoryTabs.clear();
        letterButtons.clear();
        keyLayoutRefreshPending = false;
        ((OfflineImeService) getContext()).keyInputOrder().cancelAll();
        candidateTouches = 0;
        candidateRefreshPending = false;
        pathTouches = 0;
        if (expandedHeaderTitle != null) { header.removeView(expandedHeaderTitle); expandedHeaderTitle = null; }
        renderedKeyboardKey = null;
        interactionHandler.removeCallbacksAndMessages(null);
        expandedCandidatesScroll = null;
        expandedCandidateWords = java.util.Collections.emptyList();
        renderedCandidateWords = null;
        renderedCandidateMode = null;
        renderedCandidateSurfaceKey = "";
        // Fixed header and hidden composing label precede every page's body.
        while (getChildCount() > 2) removeViewAt(2);
    }

    private void hideComposingHeader() {
        candidateBar.setVisibility(GONE);
        pathScroll.setVisibility(INVISIBLE);
        selectedPinyinPath.setVisibility(GONE);
        composing.setVisibility(GONE);
        // Panels do not use the reading-path row. Reclaim its height for the
        // canvas/content while preserving the toolbar and candidate hit area.
        ViewGroup.LayoutParams params = header.getLayoutParams();
        if (params != null && params.height != dp(candidateHeightDp())) {
            params.height = dp(candidateHeightDp());
            header.setLayoutParams(params);
        }
    }

    private void addRow(String[] keys, boolean upper) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER);
        for (String raw : keys) {
            String key = upper && raw.length() == 1 ? raw.toUpperCase(Locale.ROOT) : raw;
            addKey(row, key, 1f);
        }
        addView(row, new LayoutParams(-1, 0, 1f));
    }

    private void addNineRow(String[] labels, String[] actions) {
        LinearLayout row = new KeyTouchRow(getContext()); row.setGravity(Gravity.CENTER);
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        for (int i = 0; i < labels.length; i++) {
            KeyboardTheme current = theme == null ? KeyboardTheme.from(getContext(), dark) : theme;
            KeyButton button = new KeyButton(getContext(), labels[i], false, current);
            String action = actions[i]; button.setOnClickListener(v -> { callback.onKey(action); });
            if (action.length() == 1 && action.charAt(0) >= '0' && action.charAt(0) <= '9') button.setOnLongClickListener(v -> { callback.onKey("长按数字:" + action); return true; });
            if ("⌫".equals(action)) button.setOnLongClickListener(v -> { callback.onKey("⌫词"); return true; });
            int gap = getIntPreference(prefs, "key_gap", 2); LayoutParams p = new LayoutParams(0, -1, 1f); p.setMargins(dp(gap), dp(gap), dp(gap), dp(gap)); row.addView(button, p);
        }
        addView(row, new LayoutParams(-1, 0, 1f));
    }

    private void addBottom(String[] keys) {
        addBottomMappedFixed(keys, keys, bottomKeyHeight());
    }

    private void addBottomFixed(String[] keys, int height) {
        String[] actions = keys.clone();
        for (int i = 0; i < keys.length; i++) {
            if ("0".equals(keys[i])) actions[i] = "长按数字:0";
        }
        addBottomMappedFixed(keys, actions, height);
    }

    private void addBottomMappedFixed(String[] labels, String[] actions, int height) {
        float[] weights = new float[labels.length];
        for (int i = 0; i < weights.length; i++) {
            String action = actions[i];
            weights[i] = "空格".equals(action) ? 3f : "回车".equals(action) ? 1.4f
                    : "⌫".equals(action) ? 1.3f : 1f;
        }
        addBottomProportioned(labels, actions, weights, height);
    }

    private void addBottomProportioned(String[] labels, String[] actions, float[] weights, int height) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            KeyButton button = addKey(row, label, actions[i], weights[i]);
            if (!label.equals(actions[i]) && !"回车".equals(actions[i]))
                button.setContentDescription(actions[i]);
        }
        addView(row, new LayoutParams(-1, height));
    }

    private void addKey(LinearLayout row, String key, float weight) {
        addKey(row, key, key, weight);
    }

    private KeyButton addKey(LinearLayout row, String key, String action, float weight) {
        return addKey(row, key, action, weight, nineKeyHorizontalGap(), nineKeyVerticalGap());
    }

    private KeyButton addKey(LinearLayout row, String key, String action,
                             float weight, int gap) {
        return addKey(row, key, action, weight, gap, gap);
    }

    private KeyButton addKey(LinearLayout row, String key, String action,
                             float weight, int horizontalGap, int verticalGap) {
        if ("回车".equals(action)) key = ((OfflineImeService) getContext()).editorActionLabel();
        KeyboardTheme current = theme == null ? KeyboardTheme.from(getContext(), dark) : theme;
        boolean space = "空格".equals(key) || "Space".equals(key);
        KeyButton button = new KeyButton(getContext(), key, (!space && isFunctionKey(key)), current);
        if ("回车".equals(action)) {
            button.setActionIcon(R.drawable.ic_key_enter, key);
            button.setEmphasized(current);
        }
        button.setOnClickListener(v -> callback.onKey(action));
        if (space) button.setTrackpadActions(
                delta -> callback.onKey("移动光标:" + delta), () -> callback.onKey("选择"));
        if ("⌫".equals(key)) enableRepeatDelete(button);
        LayoutParams params = new LayoutParams(0, -1, weight);
        params.setMargins(dp(horizontalGap), dp(verticalGap), dp(horizontalGap), dp(verticalGap));
        row.addView(button, params);
        return button;
    }

    private int nineKeyHorizontalGap() {
        int requested = Math.max(0, Math.min(3, getIntPreference(renderSettings, "key_gap", 2)));
        return KeyboardPlacement.width(getContext(), renderSettings) < 340 ? Math.min(1, requested) : requested;
    }

    private int nineKeyVerticalGap() {
        return Math.max(0, Math.min(3, getIntPreference(renderSettings, "key_gap", 2)));
    }

    private int candidateFontSp() {
        return Math.max(14, Math.min(24, getIntPreference(renderSettings,
                "candidate_font_size", 17)));
    }

    private int candidateHeightDp() {
        int requested = Math.max(44, Math.min(64, getIntPreference(renderSettings, "candidate_bar_height", CANDIDATE_HEIGHT_DP)));
        int textHeight = Math.round(candidateFontSp() * getResources().getConfiguration().fontScale * 1.4f) + 12;
        return Math.max(requested, textHeight);
    }

    private void applyCandidateHeaderSizing() {
        int candidateHeight = candidateHeightDp();
        ViewGroup.LayoutParams candidateParams = candidateBar.getLayoutParams();
        if (candidateParams != null && candidateParams.height != dp(candidateHeight)) {
            candidateParams.height = dp(candidateHeight);
            candidateBar.setLayoutParams(candidateParams);
        }
        ViewGroup.LayoutParams toolbarParams = toolbarRow.getLayoutParams();
        if (toolbarParams != null && toolbarParams.height != dp(candidateHeight)) {
            toolbarParams.height = dp(candidateHeight);
            toolbarRow.setLayoutParams(toolbarParams);
        }
        ViewGroup.LayoutParams headerParams = header.getLayoutParams();
        if (headerParams != null && headerParams.height != dp(candidateHeight + PATH_HEIGHT_DP)) {
            headerParams.height = dp(candidateHeight + PATH_HEIGHT_DP);
            header.setLayoutParams(headerParams);
        }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    /** Accept both integer and legacy string preference values. */
    private static int getIntPreference(android.content.SharedPreferences prefs, String key, int fallback) {
        try { return prefs.getInt(key, fallback); }
        catch (ClassCastException ignored) {
            String value = prefs.getString(key, null);
            if (value != null) try { return Integer.parseInt(value); } catch (NumberFormatException ignoredNumber) { }
            return fallback;
        }
    }
    public void releaseAudio() {
        interactionHandler.removeCallbacksAndMessages(null);
        if (toneGenerator != null) { toneGenerator.release(); toneGenerator = null; }
    }

    void onKeyInputIdle() {
        if (keyLayoutRefreshPending) {
            keyLayoutRefreshPending = false;
            post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (emojiStyleAssets != null) { emojiStyleAssets.close(); emojiStyleAssets = null; }
        ((OfflineImeService) getContext()).onKeyboardViewDetached(this);
        if (activeMenu != null) activeMenu.dismiss();
        pathTouches = 0;
        candidateTouches = 0;
        candidateRefreshPending = false;
        interactionHandler.removeCallbacksAndMessages(null);
        if (settingsListenerRegistered) {
            renderSettings.unregisterOnSharedPreferenceChangeListener(renderSettingsListener);
            settingsListenerRegistered = false;
        }
        ((OfflineImeService) getContext()).keyInputOrder().cancelAll();
        super.onDetachedFromWindow();
    }

    private int backgroundColor() {
        return KeyboardTheme.from(getContext(), dark).background;
    }

    private boolean isFunctionKey(String key) { return key.length() > 1 || "符".equals(key) || "⌫".equals(key) || "⇧".equals(key); }
    private String modeLabel(OfflineImeService.Mode mode) {
        if (mode == OfflineImeService.Mode.NINE) return "中文 9键";
        if (mode == OfflineImeService.Mode.STROKE) return "笔画输入";
        if (mode == OfflineImeService.Mode.ENGLISH) return "English";
        if (mode == OfflineImeService.Mode.SYMBOLS) return "数字键盘";
        return "中文 26键";
    }

    private boolean isLandscapeLayout() {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        return configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || configuration.screenWidthDp > configuration.screenHeightDp;
    }

    private String modeBadgeLabel(OfflineImeService.Mode mode) {
        if (renderSettings.getBoolean("privacy_mode", false)) return "隐私";
        if (mode == OfflineImeService.Mode.NINE) return "中9";
        if (mode == OfflineImeService.Mode.STROKE) return "笔画";
        if (mode == OfflineImeService.Mode.ENGLISH) return "EN";
        if (mode == OfflineImeService.Mode.SYMBOLS) return "123";
        return "中26";
    }

    private String modeBadgeDescription(OfflineImeService.Mode mode) {
        if (renderSettings.getBoolean("privacy_mode", false)) return "隐私模式已开启，不学习输入，不读取剪贴板";
        if (mode == OfflineImeService.Mode.NINE) return "当前键盘：中文 9 键";
        if (mode == OfflineImeService.Mode.STROKE) return "当前键盘：五笔画输入";
        if (mode == OfflineImeService.Mode.ENGLISH) return "当前键盘：English QWERTY";
        if (mode == OfflineImeService.Mode.SYMBOLS) return "当前键盘：数字键盘";
        return "当前键盘：中文 26 键";
    }

    private void styleModeBadge(TextView badge, OfflineImeService.Mode mode, boolean toolbar) {
        badge.setText(modeBadgeLabel(mode));
        badge.setTextSize(toolbar ? 12 : 10);
        badge.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        badge.setTextColor(theme.accent);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setContentDescription(modeBadgeDescription(mode));
        GradientDrawable background = roundRect(blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f), toolbar ? 12 : 10);
        badge.setBackground(background);
    }

    private void applyLayoutPreferences() {
        android.content.res.Configuration current = getResources().getConfiguration();
        String surfaceKey = styleRevision + ":" + dark + ':' + floatingLandscape + ':' + current.orientation
                + ':' + current.screenWidthDp + ':' + current.screenHeightDp + ':' + current.fontScale
                + ':' + current.densityDpi + ':' + current.uiMode;
        if (surfaceKey.equals(renderedSurfaceKey)) return;
        renderedSurfaceKey = surfaceKey;
        theme = KeyboardTheme.from(getContext(), dark);
        android.content.SharedPreferences p = renderSettings;
        int height = KeyboardPlacement.height(getContext(), p);
        preferredMinHeight = dp(height);
        applyCandidateHeaderSizing();
        setMinimumHeight(preferredMinHeight);
        setScaleX(1f); setScaleY(1f); setTranslationX(0f);
        updateSurfaceBackground();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        styleRevision++;
        if (!settingsListenerRegistered) {
            renderSettings.registerOnSharedPreferenceChangeListener(renderSettingsListener);
            settingsListenerRegistered = true;
        }
        post(() -> ((OfflineImeService) getContext()).refreshPinyinPaths());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        setPivotY(height);
        boolean oneHand = !floatingLandscape && getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("one_hand", false);
        setPivotX(oneHand ? width : width / 2f);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Measure every page (tools, emoji, layout chooser and keyboard) at
        // the same target height. Otherwise LinearLayout recalculates its
        // wrap-content height from the number of rows and the IME visibly
        // jumps when a toolbar button is tapped.
        int target = Math.max(0, preferredMinHeight);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int available = MeasureSpec.getSize(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) target = available;
        else if (heightMode == MeasureSpec.AT_MOST && available > 0) target = Math.min(target, available);
        setMinimumHeight(target);
        int exactHeightSpec = MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, exactHeightSpec);
        setMeasuredDimension(getMeasuredWidth(), target);
    }
}
