package com.example.offlineime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.graphics.Insets;
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
        void onDismissNextWordPrediction();
        void onMode(OfflineImeService.Mode mode);
    }

    private final Callback callback;
    private final ArrayList<KeyButton> letterButtons = new ArrayList<>();
    private boolean dark;
    private int candidatePage;
    private ToneGenerator toneGenerator;
    private final Handler interactionHandler = new Handler(Looper.getMainLooper());
    private KeyboardTheme theme;
    /** Candidate words and toolbar share one fixed-height header row. */
    private final CandidateHeaderView header;
    private final LinearLayout toolbarRow;
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
    private long expandedCandidateStyleRevision = -1;
    private boolean expandedCandidatesTouched;
    private boolean expandedPageRefreshPending;
    private boolean candidateListShown;
    private final TextView composing = new TextView(getContext());
    private final ArrayList<NinePathSlot> ninePathChoicePool = new ArrayList<>();
    private HandwritingPanel handwritingPanel;
    private boolean handwritingFullscreen;
    private int baseLeftPadding;
    private int baseTopPadding;
    private int baseRightPadding;
    private int baseBottomPadding;
    private int preferredMinHeight;
    private View keyboardOverflowSpace;
    private EditorPanelView editorPanel;
    private VoicePanelView voicePanel;
    private boolean floatingLandscape;
    private String activeToolbarAction = "";
    private OfflineImeService.Mode toolbarKeyboardMode = OfflineImeService.Mode.PINYIN;
    private String renderedKeyboardKey;
    private final ArrayList<KeyButton> qwertyLetters = new ArrayList<>();
    private final ArrayList<KeyButton> editorActionKeys = new ArrayList<>();
    private KeyButton qwertyShift;
    private String toolbarRenderKey;
    private final android.content.SharedPreferences renderSettings;
    private long styleRevision;
    private CandidateTextStyle candidateTextStyle;
    private long candidateTextStyleRevision = -1;
    private long renderedWordStatusStyle = -1;
    private String renderedWordStatus = "";
    private String renderedSurfaceKey = "";
    private boolean settingsListenerRegistered;
    private boolean keyLayoutRefreshPending;
    private OfflineImeService.Mode renderedKeyboardMode;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener renderSettingsListener = (prefs, name) -> {
        if (name == null || name.startsWith("toolbar") || name.equals("theme") || name.equals("dark")
                || name.equals("high_contrast") || name.startsWith("key_") || name.equals("keyboard_height")
                || name.startsWith("one_hand") || name.startsWith("floating") || name.startsWith("tablet_preset_") || name.startsWith("split_") || name.equals("appearance_mode") || name.equals("privacy_mode")
                || CandidateTextStyle.affects(name) || name.equals("candidate_count")
                || name.equals("pinyin_scheme") || name.equals("emoji_legacy_markers")
                || name.equals("show_mode") || name.equals("show_composing")
                || name.startsWith("nine_key_")) styleRevision++;
    };

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

    public KeyboardView(Context context) {
        this(context, requireService(context));
    }

    static OfflineImeService requireService(Context context) {
        if (context instanceof OfflineImeService) return (OfflineImeService) context;
        throw new IllegalArgumentException("The keyboard must be hosted by OfflineImeService");
    }

    public KeyboardView(Context context, Callback callback) {
        super(context);
        renderSettings = SettingsRepository.preferences(context);
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
            int safeTop = 0;
            int safeRight;
            int safeBottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                Insets mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures());
                Insets tappable = insets.getInsets(WindowInsets.Type.tappableElement());
                safeLeft = Math.max(nav.left, mandatory.left);
                safeRight = Math.max(nav.right, mandatory.right);
                safeBottom = Math.max(Math.max(nav.bottom, mandatory.bottom), tappable.bottom);
                if (handwritingFullscreen) {
                    safeTop = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                }
            } else {
                safeLeft = insets.getSystemWindowInsetLeft();
                safeRight = insets.getSystemWindowInsetRight();
                safeBottom = insets.getSystemWindowInsetBottom();
                if (handwritingFullscreen) safeTop = insets.getSystemWindowInsetTop();
            }
            setPadding(baseLeftPadding + safeLeft, baseTopPadding + safeTop,
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
        header = new CandidateHeaderView(context, new CandidateHeaderView.Host() {
            @Override public Callback callback() { return callback; }
            @Override public KeyboardTheme theme() { return theme; }
            @Override public android.content.SharedPreferences settings() { return renderSettings; }
            @Override public CandidateTextStyle candidateStyle() { return KeyboardView.this.candidateStyle(); }
            @Override public int candidateHeightDp() { return KeyboardView.this.candidateHeightDp(); }
            @Override public int candidateGapDp() { return SettingsRepository.integer(renderSettings, "key_gap", 2); }
            @Override public int candidatePageSize() {
                return CandidatePaging.pageSize(SettingsRepository.integer(renderSettings, "candidate_count", 6));
            }
            @Override public boolean isCandidateListShown() { return candidateListShown; }
            @Override public void requestPendingRefresh() {
                ((OfflineImeService) getContext()).refreshPinyinPaths();
            }
        });
        toolbarRow = header.toolbarRow();
        int candidateHeight = candidateHeightDp();
        addView(header, new LayoutParams(-1, dp(candidateHeight)));
        addView(composing, new LayoutParams(-1, dp(30)));
        // The window can measure us before the first posted refresh runs.
        applyLayoutPreferences();
    }

    @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
        long started = ImePerformanceMonitor.start();
        super.dispatchDraw(canvas);
        ImePerformanceMonitor.stage(getContext(), "ui_draw", started, 0, renderedKeyboardMode == OfflineImeService.Mode.NINE);
        ((OfflineImeService) getContext()).onCandidateFrameDrawn(candidateListShown ? expandedCandidateWords : header.renderedCandidateWords());
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
        if (header.isCandidateTouched() && mode == toolbarKeyboardMode) {
            header.markCandidateRefreshPending();
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
        android.content.SharedPreferences settings = renderSettings;
        OfflineImeService service = (OfflineImeService) getContext();
        renderPinyinPaths(mode, input);
        boolean typing = !input.isEmpty() || !words.isEmpty();
        // Only explicitly entered word creation uses the separate draft row.
        showWordCreationStatus(service.wordCreationStatus());
        header.bindCandidates(mode, input, composingReading(input), words, candidatePage,
                service.candidateSurfaceReady(), service.candidateFirstFramePending(),
                styleRevision, renderedSurfaceKey);
        // 空候选时保持干净留白；离线隐私说明放在设置页，不占用键盘空间。
        if (!typing) addIconToolbar();
        // 候选栏直接占用工具栏的固定位置，两者高度一致，输入时键区不跳动。
        header.setToolbarVisibility(!typing && (settings.getBoolean("toolbar", true) || KeyboardPlacement.floating(getContext(), settings)) ? VISIBLE : GONE);
        // Candidate completions can arrive between DOWN and UP. Keep the same
        // key instances so an asynchronous repaint cannot cancel that gesture.
        String keyboardKey = mode.name() + ':' + renderedSurfaceKey + ':' + service.editorLayoutKind()
                + ':' + (mode == OfflineImeService.Mode.ENGLISH && service.englishWordMode());
        if (keyboardKey.equals(renderedKeyboardKey)) {
            updateKeyLabels(shift, service);
            return;
        }
        if (renderedKeyboardKey != null && service.keyInputOrder().hasPending()) {
            keyLayoutRefreshPending = true;
            return;
        }
        removeRows();
        if (mode == OfflineImeService.Mode.PINYIN) {
            addQwertyStyle(false);
        } else if (mode == OfflineImeService.Mode.NINE) {
            addNineStyle();
        } else if (mode == OfflineImeService.Mode.STROKE) {
            addStrokeStyle();
        } else if (mode == OfflineImeService.Mode.ENGLISH) {
            addQwertyStyle(true);
        } else {
            addNumericStyle();
        }
        renderedKeyboardKey = keyboardKey;
        renderedKeyboardMode = mode;
        updateKeyLabels(shift, service);
        // A mode switch can create a new T9 path column after the paths were
        // first rendered above. Populate that newly attached column as well.
        renderPinyinPaths(mode, input);
    }

    private void updateKeyLabels(boolean shift, OfflineImeService service) {
        if (qwertyShift != null) {
            qwertyShift.setShiftState(shift, service.capsLockEnabled(), theme);
            for (KeyButton letter : qwertyLetters) letter.setLetterUppercase(shift);
        }
        String action = service.editorActionLabel();
        for (KeyButton key : editorActionKeys) key.setActionCaption(action);
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

    private void addNumericStyle() {
        String kind = ((OfflineImeService) getContext()).editorLayoutKind();
        boolean phone = "phone".equals(kind);
        boolean signed = kind.startsWith("signed");
        boolean number = signed || "number".equals(kind) || "decimal".equals(kind);
        String secondAction = signed ? "-" : "符号";
        String thirdAction = number ? (signed ? "符号" : "") : "空格";
        String bottomLeft = "返回";
        String bottomRight = number && !kind.contains("decimal") ? "" : ".";
        if (phone) { bottomLeft = "*"; bottomRight = "#"; thirdAction = "返回"; }
        else if ("date".equals(kind)) { bottomRight = "/"; thirdAction = "-"; }
        else if ("time".equals(kind)) { bottomRight = ":"; thirdAction = ""; }
        else if ("datetime".equals(kind)) { bottomRight = ":"; secondAction = "/"; thirdAction = "-"; }

        // The four-column number grid keeps its geometry beside a narrow symbol strip.
        // In particular, 0 always shares the centre line of 2, 5 and 8.
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(HORIZONTAL);
        LinearLayout symbols = new LinearLayout(getContext()); symbols.setOrientation(VERTICAL);
        String[] sideSymbols = {"+", "-", "%", "/"};
        if (phone) sideSymbols = new String[]{"+", "-", "*", "#"};
        else if ("date".equals(kind)) sideSymbols = new String[]{"/", "-", ".", "符号"};
        else if ("time".equals(kind)) sideSymbols = new String[]{":", ".", "/", "符号"};
        else if ("datetime".equals(kind)) sideSymbols = new String[]{"/", "-", ":", "符号"};
        for (String symbol : sideSymbols) addVerticalKey(symbols, symbol, 1f);
        int symbolWidth = Math.max(44, Math.min(56, Math.round(KeyboardPlacement.width(getContext(), renderSettings) * 0.14f)));
        body.addView(symbols, new LayoutParams(dp(symbolWidth), -1));
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        addNumericGridRow(grid, new String[]{"1", "2", "3", "⌫"}, phone);
        addNumericGridRow(grid, new String[]{"4", "5", "6", secondAction}, phone);
        addNumericGridRow(grid, new String[]{"7", "8", "9", thirdAction}, phone);
        addNumericGridRow(grid, new String[]{bottomLeft, "0", bottomRight, "回车"}, phone);
        body.addView(grid, new LayoutParams(0, -1, 1f));
        addView(body, new LayoutParams(-1, 0, 1f));
    }

    private void addNumericGridRow(LinearLayout parent, String[] actions, boolean phone) {
        LinearLayout row = new KeyTouchRow(getContext()); row.setGravity(Gravity.CENTER);
        for (String action : actions) {
            String label = phone && "0".equals(action) ? "+\n0" : action;
            KeyButton button = addKey(row, label, action, 1f, nineKeyHorizontalGap(), nineKeyVerticalGap());
            if (action.isEmpty()) {
                button.setEnabled(false); button.setFocusable(false); button.setVisibility(INVISIBLE);
                button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            } else if (phone && "0".equals(action)) {
                button.setLongPressOnlyAction(() -> callback.onKey("+"));
                button.setContentDescription("数字 0，长按输入加号");
                AccessibilitySupport.longClick(button, "输入加号");
            } else if ("返回".equals(action)) button.setContentDescription("返回进入数字页前的键盘");
        }
        parent.addView(row, new LayoutParams(-1, 0, 1f));
    }

    private String selectedSymbolCategory = "常用";
    private boolean symbolPanelShown;
    private ScrollView symbolContent;
    private LinearLayout symbolGrid;
    private int savedSymbolScroll;
    private int savedEmojiPosition, savedEmojiTop;
    private final java.util.Map<String, TextView> symbolCategoryTabs = new java.util.LinkedHashMap<>();

    public void showSymbols() { showSymbols(selectedSymbolCategory); }

    public void showSymbols(String category) {
        if (!category.equals(selectedSymbolCategory)) savedSymbolScroll = 0;
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
        addPanelSwitches("符号");

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
        symbolContent.post(() -> { if (symbolContent != null) symbolContent.scrollTo(0, savedSymbolScroll); });
    }

    private void updateSymbolCategory(String category) {
        for (java.util.Map.Entry<String, TextView> entry : symbolCategoryTabs.entrySet()) {
            boolean active = entry.getKey().equals(category);
            TextView tab = entry.getValue();
            tab.setSelected(active);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setContentDescription(entry.getKey() + "符号" + (active ? "，已选中" : ""));
            tab.setBackground(active
                    ? roundRect(blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f), 10)
                    : null);
        }
        if (symbolContent == null || symbolGrid == null) return;
        symbolGrid.removeAllViews();
        int columns = Math.max(6, Math.min(14, KeyboardPlacement.width(getContext(), renderSettings) / 55));
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
            tab.setBackground(active
                    ? roundRect(blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f), 10)
                    : null);
            tab.setOnClickListener(v -> callback.onKey("符号分类:" + category));
            LayoutParams params = new LayoutParams(dp(58), dp(48));
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(tab, params);
            symbolCategoryTabs.put(category, tab);
            if (active) tab.post(() -> scroll.scrollTo(Math.max(0, tab.getLeft() - dp(100)), 0));
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        toolbarRow.addView(scroll, new LayoutParams(-1, -1));
    }

    private void addIconToolbar() {
        android.content.SharedPreferences settings = renderSettings;
        ToolbarConfig.migrateFormerDefaults(settings);
        boolean toolbarEnabled = settings.getBoolean("toolbar", true) || KeyboardPlacement.floating(getContext(), settings);
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
        if (handwritingPanel == null && (getResources().getConfiguration().screenWidthDp >= 600
                || KeyboardPlacement.floating(getContext(), settings)
                || !"off".equals(KeyboardPlacement.side(getContext(), settings)))) addPlacementHandle();
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
    private void addPlacementHandle() {
        TouchTrackingTextView handle = new TouchTrackingTextView(getContext());
        handle.setText("↔"); handle.setTextSize(22);
        handle.setGravity(Gravity.CENTER); handle.setTextColor(theme.accent);
        handle.setContentDescription("键盘位置：浮动时拖动，点击调整尺寸或停靠");
        handle.setOnClickListener(v -> showPlacementMenu(handle));
        final float[] point = new float[4]; final boolean[] dragged = {false};
        handle.setOnTouchListener((v, event) -> {
            if (!KeyboardPlacement.floating(getContext(), renderSettings)) return false;
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
        String preset = KeyboardPlacement.preset(getContext(), renderSettings);
        menu.item("居中", "紧凑停靠键盘", null, "center".equals(preset), false, () -> setPlacementPreset("center"));
        menu.item("分离", "宽屏双手输入", null, "split".equals(preset), false, () -> setPlacementPreset("split"));
        menu.item("左手", null, null, "left".equals(preset), false, () -> setPlacementPreset("left"));
        menu.item("右手", null, null, "right".equals(preset), false, () -> setPlacementPreset("right"));
        menu.item("浮动键盘", "拖动工具栏箭头调整位置", null, "floating".equals(preset), false, () -> setPlacementPreset("floating"));
        menu.item("加宽", null, null, false, false, () -> resizeFloating(24, 0));
        menu.item("收窄", null, null, false, false, () -> resizeFloating(-24, 0));
        menu.item("增高", null, null, false, false, () -> resizeFloating(0, 24));
        menu.item("降低", null, null, false, false, () -> resizeFloating(0, -24));
        menu.item("使用设置页布局", null, null, "legacy".equals(preset), false, () -> setPlacementPreset("legacy"));
        showCardMenu(anchor, menu);
    }
    private void setPlacementPreset(String preset) {
        renderSettings.edit().putString("tablet_preset_" + KeyboardPlacement.axis(getContext()), preset).apply();
    }
    private void resizeFloating(int width, int height) {
        String axis = KeyboardPlacement.axis(getContext());
        renderSettings.edit().putString("tablet_preset_" + axis, "floating")
                .putInt("floating_width_" + axis, Math.max(280, Math.min(getResources().getConfiguration().screenWidthDp, KeyboardPlacement.width(getContext(), renderSettings) + width)))
                .putInt("floating_height_" + axis, Math.max(200, Math.min(560, KeyboardPlacement.height(getContext(), renderSettings) + height))).apply();
    }

    private void addToolbarAction(String action) {
        View button;
        OfflineImeService.Mode mode = modeForToolbar();
        boolean layout = "键盘布局".equals(action);
        boolean selected = action.equals(activeToolbarAction) || (layout && mode != null)
                || "人名模式".equals(action) && ((OfflineImeService) getContext()).isNameMode();
        if (layout && renderSettings.getBoolean("show_mode", true)) {
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
        button.setContentDescription("人名模式".equals(action) ? "人名候选，" + (selected ? "已开启，点击退出" : "点击开启")
                : "工具".equals(action) ? "文本编辑" : action);
        AccessibilitySupport.selected(button, selected);
        button.setTooltipText("工具".equals(action) ? "文本编辑" : action);
        int softAccent = blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundRect(blendColor(theme.function, theme.accent, 0.18f), 12));
        states.addState(new int[]{}, roundRect(selected ? softAccent : Color.TRANSPARENT, 12));
        button.setBackground(states);
        button.setOnClickListener(v -> {
            if ("日期时间".equals(action)) showDateTimeMenu(button);
            else callback.onKey(action);
        });
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

    private void addQwertyStyle(boolean english) {
        addKeyboardOverflowSpace();
        addLetterRow("qwertyuiop", false, english);
        addLetterRow("asdfghjkl", true, english);
        LinearLayout third = new KeyTouchRow(getContext());
        third.setBaselineAligned(false);
        // All three rows share ten letter units (twelve in split mode).
        // Small outer insets keep the wider function keys visually balanced.
        addQwertyInset(third, 0.1f);
        qwertyShift = addKey(third, "⇧", "⇧", 1.4f,
                nineKeyHorizontalGap(), nineKeyVerticalGap());
        for (char letter : "zxcvbnm".toCharArray()) {
            if (letter == 'b' && KeyboardPlacement.split(getContext(), renderSettings)) third.addView(new View(getContext()), new LayoutParams(0, 1, KeyboardPlacement.splitGap(renderSettings)));
            addQwertyLetter(third, letter, english);
        }
        addKey(third, "⌫", "⌫", 1.4f,
                nineKeyHorizontalGap(), nineKeyVerticalGap());
        addQwertyInset(third, 0.1f);
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
        bottom.setBaselineAligned(false);
        int horizontalGap = nineKeyHorizontalGap();
        int verticalGap = nineKeyVerticalGap();
        String editorKind = ((OfflineImeService) getContext()).editorLayoutKind();
        String englishShortcut = english && "email".equals(editorKind) ? "@"
                : english && "uri".equals(editorKind) ? "/" : "";
        if (equalLetterRows && KeyboardPlacement.split(getContext(), renderSettings)) {
            addKey(bottom, "符号", "符号", 1.1f, horizontalGap, verticalGap);
            addKey(bottom, "123", "123", 1f, horizontalGap, verticalGap);
            if (!englishShortcut.isEmpty())
                addKey(bottom, englishShortcut, englishShortcut, 1f, horizontalGap, verticalGap);
            if (english) {
                boolean wordMode = ((OfflineImeService) getContext()).englishWordMode();
                addKey(bottom, wordMode ? "词" : "字", "英文输入模式", 0.8f, horizontalGap, verticalGap);
            }
            addKey(bottom, "⌫", "⌫", 1.1f, horizontalGap, verticalGap);
            addKey(bottom, english ? "Space" : "空格", "空格", 2.2f, horizontalGap, verticalGap);
            bottom.addView(new View(getContext()), new LayoutParams(0, 1, KeyboardPlacement.splitGap(renderSettings)));
            addKey(bottom, english ? "Space" : "空格", "空格", 2.2f, horizontalGap, verticalGap);
            addKey(bottom, "中/英", "中英", 1.1f, horizontalGap, verticalGap);
            addKey(bottom, "⌫", "⌫", 1.1f, horizontalGap, verticalGap);
            addKey(bottom, ((OfflineImeService) getContext()).editorActionLabel(), "回车", 1.2f, horizontalGap, verticalGap);
            addView(bottom, new LayoutParams(-1, 0, 1f));
            return;
        }
        if (!equalLetterRows && !english && ((OfflineImeService) getContext()).nineKeyMode()
                && "left".equals(KeyboardPlacement.side(getContext(), renderSettings))
                && getResources().getConfiguration().screenWidthDp >= 600) {
            addKey(bottom, ((OfflineImeService) getContext()).editorActionLabel(), "回车", 16f, horizontalGap, verticalGap);
            addKey(bottom, "中/英", "中英", 12f, horizontalGap, verticalGap);
            addKey(bottom, "空格", "空格", 46f, horizontalGap, verticalGap);
            addKey(bottom, "123", "123", 14f, horizontalGap, verticalGap);
            addKey(bottom, "符号", "符号", 12f, horizontalGap, verticalGap);
            addView(bottom, new LayoutParams(-1, nineBottomKeyHeight()));
            return;
        }
        boolean compact = KeyboardPlacement.width(getContext(), renderSettings) < 340;
        addKey(bottom, compact ? "符" : "符号", "符号", english ? 11f : 12f, horizontalGap, verticalGap);
        addKey(bottom, "123", "123", english ? 11f : equalLetterRows ? 12f : 14f, horizontalGap, verticalGap);
        if (!englishShortcut.isEmpty())
            addKey(bottom, englishShortcut, englishShortcut, 11f, horizontalGap, verticalGap);
        if (english) {
            boolean wordMode = ((OfflineImeService) getContext()).englishWordMode();
            KeyButton toggle = addKey(bottom, wordMode ? "词" : "字", "英文输入模式",
                    8f, horizontalGap, verticalGap);
            toggle.setContentDescription(wordMode ? "英文单词模式，点击切换为字母模式"
                    : "英文字母模式，点击切换为单词模式");
        }
        float spaceWeight = english ? (englishShortcut.isEmpty() ? 44f : 33f) : equalLetterRows ? 48f : 46f;
        addKey(bottom, english ? "Space" : "空格", "空格", spaceWeight, horizontalGap, verticalGap);
        if (english && "email".equals(editorKind))
            addKey(bottom, ".", ".", 11f, horizontalGap, verticalGap);
        else addKey(bottom, "中/英", "中英", english ? 11f : 12f, horizontalGap, verticalGap);
        String action = ((OfflineImeService) getContext()).editorActionLabel();
        addKey(bottom, action, "回车", english ? 15f : 16f, horizontalGap, verticalGap);
        boolean nine = !english && ((OfflineImeService) getContext()).nineKeyMode();
        addView(bottom, equalLetterRows ? new LayoutParams(-1, 0, 1f)
                : new LayoutParams(-1, nine ? nineBottomKeyHeight() : bottomKeyHeight()));
    }

    private void addLetterRow(String letters, boolean inset, boolean english) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setBaselineAligned(false);
        if (inset) addQwertyInset(row, 0.5f);
        for (int index = 0; index < letters.length(); index++) {
            if (index == (letters.length() + 1) / 2 && KeyboardPlacement.split(getContext(), renderSettings))
                row.addView(new View(getContext()), new LayoutParams(0, 1, KeyboardPlacement.splitGap(renderSettings)));
            char letter = letters.charAt(index);
            addQwertyLetter(row, letter, english);
        }
        if (inset) addQwertyInset(row, 0.5f);
        addView(row, new LayoutParams(-1, 0, 1f));
    }

    private void addQwertyInset(LinearLayout row, float weight) {
        LayoutParams inset = new LayoutParams(0, 1, weight);
        // Account for the missing outer gutters, keeping all letter cells
        // at the same width as the first row even with visible key margins.
        inset.leftMargin = dp(nineKeyHorizontalGap());
        row.addView(new View(getContext()), inset);
    }

    private void addQwertyLetter(LinearLayout row, char letter, boolean english) {
        String action = String.valueOf(letter);
        String display = action;
        if (!english && ((OfflineImeService) getContext()).isShuangpin()) display = shuangpinHint(letter) + "\n" + display;
        KeyButton button = addKey(row, display, action, 1f,
                nineKeyHorizontalGap(), nineKeyVerticalGap());
        qwertyLetters.add(button);
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
        addKeyboardOverflowSpace();
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        ninePathScroll = createNinePathPanel();
        // Keep the three letter columns wide on narrow and one-handed layouts.
        int keyboardWidth = KeyboardPlacement.width(getContext(), renderSettings);
        int pathWidth = Math.max(44, Math.min(60, Math.round(keyboardWidth * 0.125f)));
        int controlWidth = Math.max(48, Math.min(60, Math.round(keyboardWidth * 0.125f)));
        int sideGutter = nineKeyHorizontalGap();
        LayoutParams strip = new LayoutParams(dp(pathWidth), -1);
        strip.setMargins(dp(sideGutter), 0, dp(sideGutter), 0);
        boolean mirror = "left".equals(KeyboardPlacement.side(getContext(), renderSettings))
                && getResources().getConfiguration().screenWidthDp >= 600;
        if (!mirror) body.addView(ninePathScroll, strip);
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        addNineGridRow(grid, new String[]{"1\n@/.", "2\nABC", "3\nDEF"}, new String[]{"标点", "2", "3"}, 0);
        addNineGridRow(grid, new String[]{"4\nGHI", "5\nJKL", "6\nMNO"}, new String[]{"4", "5", "6"}, 0);
        addNineGridRow(grid, new String[]{"7\nPQRS", "8\nTUV", "9\nWXYZ"}, new String[]{"7", "8", "9"}, 0);
        LinearLayout right = new KeyTouchRow(getContext());
        right.setOrientation(VERTICAL);
        addNineSideKey(right, "⌫", "⌫", 1f);
        addNineSideKey(right, "清空拼音", "重输", 1f);
        addNineSideKey(right, "分隔", "分隔", 1f);
        if (mirror) body.addView(right, new LayoutParams(dp(controlWidth) + 2 * dp(sideGutter), -1));
        body.addView(grid, new LayoutParams(0, -1, 1f));
        if (mirror) body.addView(ninePathScroll, strip);
        else body.addView(right, new LayoutParams(dp(controlWidth) + 2 * dp(sideGutter), -1));
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
        boolean swipeNumbers = renderSettings.getBoolean("nine_key_swipe_numbers", true);
        boolean lettersOnly = "letters".equals(renderSettings.getString("nine_key_labels", "letters_numbers"));
        for (int i = 0; i < labels.length; i++) {
            KeyButton b = new KeyButton(getContext(), labels[i], false, theme);
            b.enableKeyPreview();
            final String action = actions[i]; b.setOnClickListener(v -> callback.onKey(action));
            // The first key opens punctuation on tap, but flicks/holds still enter 1.
            final String digit = labels[i].substring(0, labels[i].indexOf('\n'));
            Runnable enterDigit = () -> callback.onKey("长按数字:" + digit);
            if (swipeNumbers) b.setSwipeUpSymbol(digit, enterDigit);
            else b.setLongPressOnlyAction(enterDigit);
            b.setSecondaryLabelVisible(!lettersOnly);
            String description = "标点".equals(action) ? "标点" : labels[i].substring(labels[i].indexOf('\n') + 1);
            b.setContentDescription(description + (swipeNumbers ? "，上滑或长按输入数字 " : "，长按输入数字 ") + digit);
            LayoutParams lp = new LayoutParams(0, -1, 1f);
            lp.setMargins(dp(horizontalGap), dp(verticalGap), dp(horizontalGap), dp(verticalGap));
            row.addView(b, lp);
        }
        parent.addView(row, height == 0 ? new LayoutParams(-1, 0, 1f) : new LayoutParams(-1, height));
    }

    private int nineBottomKeyHeight() {
        int available = Math.max(0, KeyboardPlacement.height(getContext(), renderSettings) - candidateHeightDp());
        return dp(Math.max(44, Math.min(68, Math.round(available * 0.22f))));
    }

    private int bottomKeyHeight() {
        return dp(isLandscapeLayout() ? 44 : 54);
    }

    private void addNineSideKey(LinearLayout column, String label, String action, float weight) {
        KeyboardTheme current = theme == null ? KeyboardTheme.from(getContext(), dark) : theme;
        KeyButton button = new KeyButton(getContext(), label, !"重输".equals(action), current);
        button.setOnClickListener(v -> callback.onKey(action));
        if ("⌫".equals(action)) enableRepeatDelete(button);
        if ("重输".equals(action)) {
            button.setText("清空\n拼音"); button.setTextSize(12); button.setMaxLines(2);
            button.setContentDescription("清空尚未上屏的拼音，不删除已输入文字");
        } else if ("分隔".equals(action)) button.setContentDescription("在当前拼音位置插入分隔符");
        int horizontalGap = nineKeyHorizontalGap();
        int verticalGap = nineKeyVerticalGap();
        LayoutParams params = new LayoutParams(-1, 0, weight);
        params.setMargins(dp(horizontalGap), dp(verticalGap), dp(horizontalGap), dp(verticalGap));
        column.addView(button, params);
    }

    private void enableRepeatDelete(KeyButton button) {
        button.setRepeatDelete(((OfflineImeService) getContext())::beginDeleteRun);
    }

    private void addVerticalKey(LinearLayout parent, String key, float weight) {
        KeyButton b = new KeyButton(getContext(), key, isFunctionKey(key), theme);
        if (key.length() == 1 && !isFunctionKey(key)) b.enableKeyPreview();
        b.setOnClickListener(v -> callback.onKey(key));
        if (key.isEmpty()) {
            b.setEnabled(false); b.setFocusable(false); b.setVisibility(INVISIBLE);
            b.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if ("⌫".equals(key)) enableRepeatDelete(b);
        LayoutParams params = new LayoutParams(-1, 0, weight);
        params.setMargins(dp(nineKeyHorizontalGap()), dp(nineKeyVerticalGap()),
                dp(nineKeyHorizontalGap()), dp(nineKeyVerticalGap()));
        parent.addView(b, params);
    }

    private int toolbarIcon(String action) {
        if ("收起键盘".equals(action)) return R.drawable.ic_toolbar_hide;
        if ("快捷短语".equals(action)) return R.drawable.ic_toolbar_phrases;
        if ("日期时间".equals(action)) return R.drawable.ic_toolbar_calendar;
        if ("设置".equals(action)) return R.drawable.ic_toolbar_settings;
        if ("符号".equals(action)) return R.drawable.ic_toolbar_symbols;
        if ("😊".equals(action)) return R.drawable.ic_toolbar_emoji;
        if ("剪贴板".equals(action)) return R.drawable.ic_toolbar_clipboard;
        if ("手写".equals(action)) return R.drawable.ic_toolbar_handwriting;
        if ("语音".equals(action)) return R.drawable.ic_toolbar_voice;
        if ("键盘布局".equals(action)) return R.drawable.ic_toolbar_keyboard;
        if ("工具".equals(action)) return R.drawable.ic_toolbar_edit;
        if ("字".equals(action)) return R.drawable.ic_toolbar_keyboard;
        if ("9键".equals(action)) return R.drawable.ic_toolbar_nine;
        if ("符".equals(action) || "ABC".equals(action)) return R.drawable.ic_toolbar_keyboard;
        return R.drawable.ic_toolbar_tools;
    }

    private void showDateTimeMenu(View anchor) {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "插入日期时间", theme);
        for (String id : DateTimeInsertion.FORMATS) {
            String value = DateTimeInsertion.format(id);
            menu.item(DateTimeInsertion.label(id), value, null, false, false,
                    () -> callback.onKey("插入日期时间:" + id));
        }
        showCardMenu(anchor, menu);
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
        scroll.setScrollBarStyle(SCROLLBARS_INSIDE_OVERLAY);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setPadding(dp(2), dp(2), dp(4), dp(4));
        scroll.setContentDescription("键盘布局选项，可上下滑动查看更多");
        LinearLayout choices = new LinearLayout(getContext());
        choices.setOrientation(VERTICAL);
        choices.setGravity(Gravity.TOP);
        boolean landscape = isLandscapeLayout();
        int columns = landscape ? 5 : 3;
        String[] titles = {"中文 26键", "中文 9键", "数字键盘", "English", "笔画输入"};
        String[] subtitles = {"全拼 · 简拼", "九宫格拼音",
                "数字 · 运算", "拼写 · 联想", "横竖撇点折"};
        String[] actions = {"字", "9键", "符", "ABC", "笔画"};
        OfflineImeService.Mode[] modes = {OfflineImeService.Mode.PINYIN,
                OfflineImeService.Mode.NINE, OfflineImeService.Mode.SYMBOLS, OfflineImeService.Mode.ENGLISH,
                OfflineImeService.Mode.STROKE};
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
        LinearLayout utilities = new LinearLayout(getContext());
        utilities.setOrientation(HORIZONTAL);
        choices.addView(utilities, new LayoutParams(-1, dp(54)));
        TextView help = addLayoutChooserAction(utilities, "拼音帮助", false, 13);
        help.setOnClickListener(v -> {
            RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "拼音输入提示", theme);
            TextView text = new TextView(getContext()); text.setTextSize(15); text.setTextColor(theme.text); text.setPadding(dp(12), dp(12), dp(12), dp(12));
            text.setText("拼音路径：长音节在前，同长度按 A–Z 排列。点击选择读音，长按可编辑、只解锁此音节或原文上屏。\n\n小鹤双拼：每个音节两个按键，键帽上方显示韵母。单字母零声母双击，如 a→aa；两字母零声母直接输入，如 ai；三字母零声母用首字母加韵母键，如 ang→ah。\n\n空格左右滑动移动光标，上滑切换选择。回车先确认拼音，再次点击才执行发送或换行。");
            ScrollView helpScroll = new ScrollView(getContext()); helpScroll.addView(text); menu.content(helpScroll); showCardMenu(help, menu);
        });
        help.setContentDescription("拼音路径与小鹤双拼帮助");
        TextView placement = addLayoutChooserAction(utilities, "单手 · 浮动", false, 13);
        placement.setOnClickListener(v -> showPlacementMenu(placement));
        placement.setContentDescription("单手、浮动与键盘尺寸");
        boolean privacy = renderSettings.getBoolean("privacy_mode", false);
        TextView privacyToggle = addLayoutChooserAction(utilities, privacy ? "关闭隐私" : "开启隐私", privacy, 13);
        privacyToggle.setOnClickListener(v -> callback.onKey("隐私模式"));
        privacyToggle.setContentDescription(privacy ? "关闭隐私模式" : "开启隐私模式");
        scroll.addView(choices, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
        addLayoutChooserFooter();
    }

    private void addLayoutChooserFooter() {
        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(HORIZONTAL);
        int height = Math.max(dp(54), bottomKeyHeight());
        String[] labels = {"手写", "语音", ((OfflineImeService) getContext()).isNameMode() ? "退出人名" : "人名", "返回"};
        String[] actions = {"手写", "语音", "人名模式", "返回"};
        for (int i = 0; i < labels.length; i++) {
            String action = actions[i];
            TextView button = addLayoutChooserAction(footer, labels[i], false, 15);
            button.setOnClickListener(v -> callback.onKey(action));
            if ("人名模式".equals(action))
                button.setContentDescription("退出人名".equals(labels[i]) ? "退出人名模式" : "开启人名模式");
        }
        addView(footer, new LayoutParams(-1, height));
    }

    private TextView addLayoutChooserAction(LinearLayout row, String label, boolean selected, int textSize) {
        TextView action = new TextView(getContext());
        action.setText(label); action.setTextSize(textSize); action.setTextColor(theme.accent);
        action.setGravity(Gravity.CENTER); action.setIncludeFontPadding(false);
        action.setSingleLine(true); action.setEllipsize(android.text.TextUtils.TruncateAt.END);
        action.setMinHeight(dp(UiTokens.MIN_TOUCH));
        action.setBackground(layoutChooserBackground(selected));
        LayoutParams params = new LayoutParams(0, dp(UiTokens.MIN_TOUCH), 1f);
        int gap = dp(3);
        params.setMargins(gap, gap, gap, gap);
        row.addView(action, params);
        return action;
    }

    private android.graphics.drawable.StateListDrawable layoutChooserBackground(boolean selected) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, layoutChooserSurface(selected, true));
        states.addState(new int[]{}, layoutChooserSurface(selected, false));
        return states;
    }

    private GradientDrawable layoutChooserSurface(boolean selected, boolean pressed) {
        int surface = selected || pressed ? blendColor(theme.key, theme.accent,
                selected ? (dark ? 0.23f : 0.10f) : (dark ? 0.18f : 0.07f)) : theme.key;
        GradientDrawable background = roundRect(surface, UiTokens.RADIUS_CARD);
        background.setStroke(dp(selected ? 2 : 1), selected ? theme.accent
                : blendColor(theme.key, theme.accent, dark ? 0.55f : 0.28f));
        return background;
    }

    private void addLayoutChoice(LinearLayout parent, String title, String subtitle,
                                 String action, boolean selected) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(3), dp(10), dp(3));
        card.setMinimumHeight(dp(UiTokens.MIN_TOUCH));
        int accent = theme.accent;
        int foreground = theme.text;
        card.setBackground(layoutChooserBackground(selected));
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
        status.setTextColor(accent);
        titleRow.addView(label, new LayoutParams(0, -2, 1f));
        titleRow.addView(status, new LayoutParams(dp(24), -2));
        card.addView(titleRow, new LayoutParams(-1, -2));
        TextView description = new TextView(getContext());
        description.setText(subtitle);
        description.setTextSize(11);
        description.setTextColor(dark ? 0xffaeb7c4 : 0xff677180);
        description.setGravity(Gravity.CENTER_VERTICAL);
        description.setIncludeFontPadding(false);
        description.setSingleLine(true);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams descriptionParams = new LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(2);
        card.addView(description, descriptionParams);

        // Equal-width columns with natural content height prevent both horizontal
        // overflow and vertically clipped subtitles at low keyboard heights.
        LayoutParams params = new LayoutParams(0, -1, 1f);
        int gap = dp(3);
        params.setMargins(gap, gap, gap, gap);
        parent.addView(card, params);
    }

    private void renderPinyinPaths(OfflineImeService.Mode mode, String input) {
        // Nine-key choices belong to the keyboard's side panel; no extra header
        // row is added while composing, so key heights remain stable.
        if (mode != OfflineImeService.Mode.NINE || candidateListShown || pathTouches > 0) return;
        OfflineImeService service = (OfflineImeService) getContext();
        if (input.isEmpty() || service.candidateSurfaceReady()) {
            renderNinePathChoices(input);
            renderedPathMode = mode;
            renderedPathInput = input;
        } else if (renderedPathInput.isEmpty() || renderedPathMode != mode) {
            for (NinePathSlot slot : ninePathChoicePool) slot.view.setVisibility(GONE);
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
                bindNinePathSlot(slotIndex++, reading,
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
        slot.view.setPadding(dp(2), dp(5), dp(2), dp(5));
        slot.view.setText(label); slot.label = label.toString();
        slot.view.setTypeface(android.graphics.Typeface.DEFAULT,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        fitNinePathLabel(slot);
        int textColor = selected ? theme.accent : theme.text;
        int backgroundColor = selected ? blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f) : Color.TRANSPARENT;
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
            TouchTrackingTextView view = new TouchTrackingTextView(getContext());
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(false);
            view.setPadding(dp(4), dp(3), dp(4), dp(3));
            NinePathSlot slot = new NinePathSlot(view);
            view.addOnLayoutChangeListener((changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft && !slot.quickSymbol) fitNinePathLabel(slot);
            });
            view.setTouchObserver(event -> {
                trackPathTouch(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                    slot.pressedAction = slot.quickSymbol ? slot.label : slot.action;
                else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) slot.pressedAction = null;
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
        int horizontalMargin = slot.quickSymbol ? 0 : dp(2);
        if (params.height != height || params.leftMargin != horizontalMargin
                || params.topMargin != verticalMargin || params.rightMargin != horizontalMargin
                || params.bottomMargin != verticalMargin || params.weight != weight) {
            params.height = height;
            params.weight = weight;
            params.setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin);
            slot.view.setLayoutParams(params);
        }
    }

    private static int blendColor(int base, int overlay, float amount) {
        return CandidateHeaderView.blendColor(base, overlay, amount);
    }

    private String composingReading(String raw) {
        if (raw.isEmpty() || !renderSettings.getBoolean("show_composing", true)) return "";
        return ((OfflineImeService) getContext()).currentPinyinPath();
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
                && expandedCandidatesScroll != null && expandedCandidateStyleRevision == styleRevision) {
            expandedPageRefreshPending = false;
            renderPinyinPaths(mode, input);
            return;
        }
        int previousScrollY = expandedCandidatesScroll == null || !sameSelection
                ? 0 : expandedCandidatesScroll.getScrollY();
        String focusedWord = sameSelection && expandedCandidatesScroll instanceof CandidatePanel
                ? ((CandidatePanel) expandedCandidatesScroll).focusedWord() : "";
        candidateListShown = true;
        applyLayoutPreferences();
        hideComposingHeader();
        // The expanded page is a focused candidate surface. Keeping the normal
        // tool strip here wastes one full row and makes the page feel nested.
        header.setToolbarVisibility(GONE);
        header.hideCandidateBar();
        composing.setVisibility(GONE);
        removeRows();
        expandedCandidateKey = pageKey;
        expandedCandidateStyleRevision = styleRevision;
        renderPinyinPaths(mode, input);

        int count = words == null ? 0 : words.size();
        ArrayList<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) order.add(i);
        header.showExpandedTitle(mode, input, composingReading(input), count, theme, dp(candidateHeightDp()));

        expandedCandidateWords = words == null ? java.util.Collections.emptyList() : words;
        CandidateTextStyle pageStyle = candidateStyle();
        CandidatePanel panel = new CandidatePanel(getContext(), expandedCandidateWords, order,
                pageStyle, new CandidatePanel.Host() {
            public void style(TextView view, String word, int position) {
                CandidateHeaderView.applyCandidateStyle(view, word, position == 0, theme, pageStyle,
                        pageStyle.pageNumbers && position >= 0 ? position + 1 : 0, true);
            }
            public void select(PinyinDictionary.Entry entry) { callback.onCandidateEntry(entry); }
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
                panel.scrollToWord(focusedWord);
            }
        });
    }

    public void showTools() {
        applyLayoutPreferences();
        OfflineImeService service = (OfflineImeService) getContext();
        if (editorPanel != null && editorPanel.getParent() == this
                && editorPanel.styleRevision() == styleRevision) {
            editorPanel.updateActionStates(service.selectionActive(), service.hasEditorSelection());
            return;
        }
        activeToolbarAction = "工具";
        addIconToolbar();
        hideComposingHeader();
        removeRows();
        editorPanel = new EditorPanelView(getContext(), theme, callback, this::showCardMenu, styleRevision);
        addView(editorPanel, new LayoutParams(-1, 0, 1f));
        editorPanel.updateActionStates(service.selectionActive(), service.hasEditorSelection());
    }

    private ClipboardPanel clipboardPanel(boolean phrases) {
        applyLayoutPreferences(); activeToolbarAction = phrases ? "快捷短语" : "剪贴板";
        addIconToolbar(); hideComposingHeader(); removeRows();
        if (!phrases) addPanelSwitches("剪贴板");
        ClipboardPanel panel = new ClipboardPanel(getContext(), callback, theme, dark, this::showCardMenu);
        addView(panel, new LayoutParams(-1, 0, 1f));
        return panel;
    }
    public void showClipboardHistory(List<ClipboardHistoryStore.Entry> values, String filter,
                                    String query, boolean confirmingClear, boolean undoAvailable,
                                    boolean multiSelect, Set<String> selected) {
        clipboardPanel(false).showClipboardHistory(values, filter, query, confirmingClear, undoAvailable, multiSelect, selected);
    }
    public void showQuickPhrases(List<QuickPhraseStore.Entry> phrases, String status) {
        clipboardPanel(true).showQuickPhrases(phrases, status);
    }
    public void showClipboardEnablePrompt() { clipboardPanel(false).showClipboardEnablePrompt(); }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    public void showVoice(String status, String preview, boolean finishing) {
        applyLayoutPreferences();
        if (voicePanel == null || voicePanel.getParent() != this
                || voicePanel.styleRevision() != styleRevision) {
            activeToolbarAction = "语音";
            addIconToolbar();
            hideComposingHeader();
            removeRows();
            voicePanel = new VoicePanelView(getContext(), theme, callback, styleRevision,
                    bottomKeyHeight(), nineKeyHorizontalGap(), nineKeyVerticalGap());
            addView(voicePanel, new LayoutParams(-1, 0, 1f));
        }
        voicePanel.update(status, preview, finishing);
    }

    public void showHandwriting() {
        applyLayoutPreferences();
        activeToolbarAction = "手写";
        if (handwritingPanel == null) {
            hideComposingHeader();
            removeRows();
            handwritingPanel = new HandwritingPanel(((OfflineImeService) getContext()).isHandwritingWordCreation());
        }
        // On short landscape screens, the panel controls and return key leave
        // more room for ink than the general-purpose toolbar.
        boolean compact = handwritingTargetHeight() < dp(260);
        if (!compact) addIconToolbar();
        header.setVisibility(compact ? GONE : toolbarRow.getVisibility());
        handwritingPanel.bind(compact);
    }

    void setHandwritingFullscreen(boolean fullscreen) {
        if (handwritingFullscreen == fullscreen) return;
        handwritingFullscreen = fullscreen;
        requestApplyInsets();
        requestLayout();
    }

    private int handwritingTargetHeight() {
        int screenHeight = getResources().getConfiguration().screenHeightDp;
        return dp(handwritingFullscreen ? screenHeight : screenHeight / 2);
    }

    HandwritingView handwritingInput() {
        return handwritingPanel == null ? null : handwritingPanel.pad;
    }

    void closeHandwritingPanel() {
        if (handwritingPanel == null) return;
        dismissActiveMenu();
        removeRows();
    }

    private final class HandwritingPanel implements HandwritingView.Callback {
        final HandwritingView pad;
        final boolean creatingWord;
        final LinearLayout controls = new LinearLayout(getContext());
        final LinearLayout resultBar = new LinearLayout(getContext());
        final LinearLayout suggestions = new LinearLayout(getContext());
        final LinearLayout draftRow = new LinearLayout(getContext());
        final HorizontalScrollView candidateScroll = createStrip(suggestions);
        final HorizontalScrollView draftScroll = createStrip(draftRow);
        final LinearLayout bottom = new KeyTouchRow(getContext());
        final TextView status = new TextView(getContext());
        final TextView modeToggle, splitButton, sizeToggle, editToggle;
        final KeyButton eraseKey, enterKey;
        List<String> choices = java.util.Collections.emptyList();
        String draft = "";
        boolean editing;
        KeyboardTheme styledTheme;

        HandwritingPanel(boolean creatingWord) {
            this.creatingWord = creatingWord;
            pad = new HandwritingView(getContext(), creatingWord, this);
            status.setText(pad.idlePrompt());
            status.setTextSize(12);
            status.setSingleLine();
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(dp(8), 0, dp(4), 0);
            controls.addView(status, new LayoutParams(0, -1, 1f));
            modeToggle = addControl(controls, "", () -> {
                pad.toggleContinuous();
                updateControls();
            });
            modeToggle.setEnabled(!creatingWord);
            splitButton = addControl(controls, "分字", pad::markCharacterBoundary);
            splitButton.setContentDescription("标记当前字已写完，继续写下一个字");
            sizeToggle = addControl(controls, "", () -> callback.onKey("切换手写屏幕"));

            FrameLayout results = new FrameLayout(getContext());
            results.addView(candidateScroll, new FrameLayout.LayoutParams(-1, -1));
            results.addView(draftScroll, new FrameLayout.LayoutParams(-1, -1));
            resultBar.addView(results, new LayoutParams(0, -1, 1f));
            editToggle = addControl(resultBar, "改字", () -> {
                editing = !editing;
                updateResultTab();
            });

            bottom.setGravity(Gravity.CENTER);
            eraseKey = addKey(bottom, "撤一笔", "手写删除", 1.15f);
            addKey(bottom, "清空", "清空手写", 0.9f);
            addKey(bottom, "返回", "返回手写", 0.9f);
            addKey(bottom, "空格", "空格", 1.2f);
            enterKey = addKey(bottom, "确认", "回车手写", 1.05f);
            enterKey.setEmphasized(theme);

            addView(controls, new LayoutParams(-1, dp(38)));
            addView(resultBar, new LayoutParams(-1, dp(44)));
            addView(pad, new LayoutParams(-1, 0, 1f));
            addView(bottom, new LayoutParams(-1, bottomKeyHeight()));
        }

        void bind(boolean compact) {
            setRowHeight(controls, dp(compact ? 34 : 40));
            setRowHeight(resultBar, dp(compact ? 39 : 44));
            setRowHeight(bottom, compact ? dp(42) : bottomKeyHeight());
            if (styledTheme != theme) {
                styledTheme = theme;
                pad.applyTheme(theme);
                status.setTextColor(KeyboardTheme.readableText(theme.text, theme.background));
                int controlColor = KeyboardTheme.readableText(theme.accent, theme.background);
                modeToggle.setTextColor(controlColor);
                splitButton.setTextColor(controlColor);
                sizeToggle.setTextColor(controlColor);
                editToggle.setTextColor(controlColor);
                bindChoices();
            }
            updateControls();
            onInkChanged(pad.hasInk());
        }

        private void setRowHeight(View row, int height) {
            ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params.height == height) return;
            params.height = height;
            row.setLayoutParams(params);
        }

        private TextView addControl(LinearLayout row, String label, Runnable action) {
            TextView button = new TextView(getContext());
            button.setText(label);
            button.setSingleLine();
            button.setGravity(Gravity.CENTER);
            button.setTextSize(14);
            button.setMinWidth(dp(48));
            button.setPadding(dp(5), 0, dp(5), 0);
            button.setOnClickListener(view -> action.run());
            row.addView(button, new LayoutParams(-2, -1));
            return button;
        }

        private HorizontalScrollView createStrip(LinearLayout row) {
            row.setGravity(Gravity.CENTER_VERTICAL);
            HorizontalScrollView scroll = new HorizontalScrollView(getContext());
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
            return scroll;
        }

        private void updateControls() {
            modeToggle.setText(pad.isContinuous() ? "连写" : "单字");
            modeToggle.setContentDescription(pad.isContinuous() ? "连写模式，点击切换单字"
                    : "单字模式，点击切换连写");
            splitButton.setVisibility(pad.isContinuous() ? VISIBLE : GONE);
            sizeToggle.setText(handwritingFullscreen ? "半屏" : "全屏");
            sizeToggle.setContentDescription(handwritingFullscreen ? "切换到半屏手写" : "切换到全屏手写");
        }

        @Override public void onInkChanged(boolean hasInk) {
            eraseKey.setText(hasInk || creatingWord ? "撤一笔" : "退格");
            eraseKey.setContentDescription(hasInk || creatingWord ? "撤销上一笔" : "删除光标前的文字");
            eraseKey.setEnabled(hasInk || !creatingWord);
            String action = hasInk ? "确认" : ((OfflineImeService) getContext()).editorActionLabel();
            enterKey.setText(action);
            enterKey.setContentDescription(hasInk ? "确认手写候选" : action);
        }

        @Override public void onStatus(String message) { status.setText(message); }

        @Override public void onConfirm(String value) { callback.onKey("手写字:" + value); }

        @Override public void onChoices(List<String> values, String value) {
            choices = values;
            draft = value;
            if (draft.isEmpty()) editing = false;
            bindChoices();
            candidateScroll.scrollTo(0, 0);
        }

        private void bindChoices() {
            suggestions.removeAllViews();
            if (choices.isEmpty()) {
                TextView hint = new TextView(getContext());
                hint.setText("停笔后显示候选");
                hint.setTextSize(14);
                hint.setTextColor(KeyboardTheme.readableText(theme.text, theme.background));
                hint.setGravity(Gravity.CENTER_VERTICAL);
                hint.setPadding(dp(8), 0, dp(8), 0);
                suggestions.addView(hint, new LayoutParams(-2, -1));
            }
            for (String value : choices) {
                TextView candidate = addChoice(suggestions, value);
                candidate.setOnClickListener(view -> pad.choose(value));
            }
            draftRow.removeAllViews();
            int[] points = draft.codePoints().toArray();
            for (int i = 0; i < points.length; i++) {
                int position = i;
                String character = new String(Character.toChars(points[i]));
                TextView button = addChoice(draftRow, character);
                button.setContentDescription("修改第 " + (position + 1) + " 个字 " + character);
                button.setOnClickListener(view -> showCharacterChoices(button, position));
            }
            updateResultTab();
        }

        private TextView addChoice(LinearLayout row, String value) {
            TextView item = new TextView(getContext());
            item.setText(value);
            item.setTextSize(20);
            item.setTextColor(KeyboardTheme.readableText(theme.text, theme.key));
            item.setGravity(Gravity.CENTER);
            item.setMinWidth(dp(42));
            item.setSingleLine();
            item.setPadding(dp(12), 0, dp(12), 0);
            item.setBackground(roundRect(theme.key, Math.round(theme.radius)));
            LayoutParams params = new LayoutParams(-2, -1);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(item, params);
            return item;
        }

        private void updateResultTab() {
            editToggle.setText(editing ? "候选" : "改字");
            editToggle.setVisibility(draft.isEmpty() ? GONE : VISIBLE);
            candidateScroll.setVisibility(editing ? GONE : VISIBLE);
            draftScroll.setVisibility(editing ? VISIBLE : GONE);
        }

        private void showCharacterChoices(TextView anchor, int position) {
            int expectedRevision = pad.revision();
            RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(),
                    "修改第 " + (position + 1) + " 个字", theme);
            for (String choice : pad.alternativesFor(position)) {
                menu.item(choice, null, null, false, false,
                        () -> pad.replaceDraftCharacter(expectedRevision, position, choice));
            }
            menu.item("删除这个字", null, null, false, true,
                    () -> pad.replaceDraftCharacter(expectedRevision, position, ""));
            showCardMenu(anchor, menu);
        }
    }

    private String selectedEmojiCategory = "最近";
    private String shownEmojiQuery = "";
    private boolean emojiMarkers;
    private boolean emojiPanelShown;
    private long emojiPanelStyleRevision;
    private android.widget.ListView emojiContent;
    private EmojiGridAdapter emojiGrid;
    private FrameLayout emojiSearchBar;
    private TextView emojiSearchLabel, emojiEmpty;
    private View emojiCategories;
    private final java.util.Map<String, TextView> emojiCategoryTabs = new java.util.LinkedHashMap<>();

    private android.content.SharedPreferences emojiPreferences() {
        return getContext().getSharedPreferences("emoji", Context.MODE_PRIVATE);
    }

    void showEmojiPanel(String query) {
        boolean newQuery = !shownEmojiQuery.equals(query);
        if (emojiPanelShown && emojiContent != null && emojiContent.getParent() == this
                && emojiPanelStyleRevision == styleRevision) {
            if (newQuery) {
                shownEmojiQuery = query;
                updateEmojiItems(false);
            }
            return;
        }
        shownEmojiQuery = query;
        emojiMarkers = renderSettings.getBoolean("emoji_legacy_markers", false)
                && "wechat".equals(emojiPreferences().getString("style", "system"));
        applyLayoutPreferences();
        activeToolbarAction = "😊";
        hideComposingHeader();
        removeRows();
        showEmojiToolbar();
        addEmojiSearchBar();
        addEmojiCategories(selectedEmojiCategory);
        emojiEmpty = new TextView(getContext());
        emojiEmpty.setGravity(Gravity.CENTER);
        emojiEmpty.setTextColor(theme.text);
        emojiEmpty.setVisibility(GONE);
        addView(emojiEmpty, new LayoutParams(-1, 0, 1f));
        emojiContent = new android.widget.ListView(getContext());
        emojiContent.setDivider(null);
        emojiContent.setSelector(android.R.color.transparent);
        emojiContent.setItemsCanFocus(true);
        emojiContent.setVerticalScrollBarEnabled(false);
        emojiGrid = new EmojiGridAdapter(getContext(), EmojiCatalog.get(getContext()), emojiPreferences(),
                theme, glyph -> callback.onKey("表情:" + glyph), this::showEmojiMenu);
        emojiContent.setAdapter(emojiGrid);
        addView(emojiContent, new LayoutParams(-1, 0, 1f));
        emojiPanelShown = true;
        emojiPanelStyleRevision = styleRevision;
        updateEmojiItems(false);
        if (!newQuery && emojiGrid.getCount() > 0)
            emojiContent.setSelectionFromTop(Math.min(savedEmojiPosition, emojiGrid.getCount() - 1), savedEmojiTop);
    }

    void selectEmojiCategory(String category) {
        selectedEmojiCategory = category;
        shownEmojiQuery = "";
        updateEmojiItems(false);
    }

    void setEmojiMarkers(boolean selected) {
        if (selected && !renderSettings.getBoolean("emoji_legacy_markers", false)) return;
        emojiPreferences().edit().putString("style", selected ? "wechat" : "system").apply();
        emojiMarkers = selected;
        shownEmojiQuery = "";
        showEmojiToolbar();
        updateEmojiItems(false);
    }

    private void addEmojiSearchBar() {
        emojiSearchBar = new FrameLayout(getContext());
        emojiSearchLabel = new TextView(getContext());
        emojiSearchLabel.setSingleLine();
        emojiSearchLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        emojiSearchLabel.setGravity(Gravity.CENTER_VERTICAL);
        emojiSearchLabel.setPadding(dp(12), 0, dp(8), 0);
        emojiSearchLabel.setTextColor(theme.text);
        emojiSearchLabel.setBackground(roundRect(theme.key, 12));
        emojiSearchLabel.setOnClickListener(v -> callback.onKey("搜索表情"));
        FrameLayout.LayoutParams input = new FrameLayout.LayoutParams(-1, -1);
        input.setMargins(dp(4), dp(2), dp(44), dp(2));
        emojiSearchBar.addView(emojiSearchLabel, input);
        TextView clear = new TextView(getContext());
        clear.setText("×");
        clear.setTextSize(20);
        clear.setTextColor(theme.text);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("清除表情搜索");
        clear.setOnClickListener(v -> callback.onKey("清除表情搜索"));
        emojiSearchBar.addView(clear, new FrameLayout.LayoutParams(dp(42), -1, Gravity.END));
        addView(emojiSearchBar, new LayoutParams(-1, dp(38)));
    }

    void refreshEmojiItems() { updateEmojiItems(true); }

    private void updateEmojiItems(boolean keepScroll) {
        if (emojiContent == null || emojiGrid == null) return;
        int first = keepScroll ? emojiContent.getFirstVisiblePosition() : 0;
        int top = keepScroll && emojiContent.getChildCount() > 0 ? emojiContent.getChildAt(0).getTop() : 0;
        boolean searching = !shownEmojiQuery.isEmpty() && !emojiMarkers;
        for (var entry : emojiCategoryTabs.entrySet()) {
            boolean active = !searching && entry.getKey().equals(selectedEmojiCategory);
            TextView tab = entry.getValue();
            tab.setSelected(active);
            tab.setTextColor(active ? theme.accent : theme.text);
            tab.setBackground(active ? roundRect(blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f), 12) : null);
        }
        emojiCategories.setVisibility(emojiMarkers ? GONE : VISIBLE);
        emojiSearchBar.setVisibility(emojiMarkers ? GONE : VISIBLE);
        emojiSearchLabel.setText(searching ? shownEmojiQuery : "搜索表情");
        emojiSearchLabel.setContentDescription(searching ? "搜索表情，当前：" + shownEmojiQuery : "搜索表情");
        emojiSearchBar.getChildAt(1).setVisibility(searching ? VISIBLE : GONE);

        EmojiCatalog catalog = EmojiCatalog.get(getContext());
        List<String> values;
        if (emojiMarkers) {
            values = java.util.Arrays.asList(WeChatEmojiCatalog.values());
        } else if (searching) {
            boolean chinese = shownEmojiQuery.codePoints().anyMatch(code -> Character.UnicodeScript.of(code)
                    == Character.UnicodeScript.HAN);
            String query = chinese ? HanConverter.get(getContext()).toSimplified(shownEmojiQuery) : shownEmojiQuery;
            values = EmojiSearch.find(catalog, query);
        } else if ("最近".equals(selectedEmojiCategory) || "收藏".equals(selectedEmojiCategory)) {
            boolean recent = "最近".equals(selectedEmojiCategory);
            values = recent ? EmojiPreferences.recent(emojiPreferences()) : EmojiPreferences.favorites(emojiPreferences());
            boolean legacy = renderSettings.getBoolean("emoji_legacy_markers", false);
            values.removeIf(glyph -> (!legacy && WeChatEmojiCatalog.contains(glyph)) || !EmojiCatalog.visible(glyph));
            if (recent && values.isEmpty()) values = catalog.values(selectedEmojiCategory);
        } else {
            values = catalog.values(selectedEmojiCategory);
        }
        emojiGrid.submit(values, KeyboardPlacement.width(getContext(), renderSettings));
        emojiContent.setVisibility(values.isEmpty() ? GONE : VISIBLE);
        emojiEmpty.setVisibility(values.isEmpty() ? VISIBLE : GONE);
        emojiEmpty.setText(searching ? "没有找到表情" : "长按表情可收藏或选择肤色");
        if (!values.isEmpty()) emojiContent.setSelectionFromTop(Math.min(first, emojiGrid.getCount() - 1), top);
    }

    private void showEmojiMenu(View anchor, String glyph) {
        EmojiCatalog catalog = EmojiCatalog.get(getContext());
        List<String> tones = catalog.skinTones(glyph);
        boolean editable = ((OfflineImeService) getContext()).mayRememberEdit();
        if (tones.isEmpty() && !editable) return;
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), catalog.description(glyph), theme);
        String preferred = EmojiPreferences.preferred(emojiPreferences(), catalog, glyph);
        for (String tone : tones) {
            menu.item(tone, catalog.description(tone), null, tone.equals(preferred), false,
                    () -> callback.onKey("表情肤色:" + tone));
        }
        if (editable) {
            List<String> favorites = EmojiPreferences.favorites(emojiPreferences());
            int index = favorites.indexOf(glyph);
            menu.item(index < 0 ? "收藏" : "取消收藏", null, null, false, false,
                    () -> callback.onKey("收藏表情:" + glyph));
            if ("收藏".equals(selectedEmojiCategory) && shownEmojiQuery.isEmpty()) {
                if (index > 0) menu.item("向前移动", null, null, false, false,
                        () -> callback.onKey("表情前移:" + glyph));
                if (index >= 0 && index + 1 < favorites.size()) menu.item("向后移动", null, null, false, false,
                        () -> callback.onKey("表情后移:" + glyph));
            }
        }
        showCardMenu(anchor, menu);
    }

    private void showEmojiToolbar() {
        toolbarRenderKey = null;
        toolbarRow.removeAllViews();
        toolbarRow.setPadding(dp(4), 0, dp(4), 0);
        toolbarRow.setBackgroundColor(theme.background);
        header.setToolbarVisibility(VISIBLE);
        header.applyHeight(dp(48));
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String action : new String[]{"符号", "剪贴板"})
            addCompactPanelCell(row, action, action, false, 13, () -> callback.onKey(action));
        if (renderSettings.getBoolean("emoji_legacy_markers", false)) {
            addCompactPanelCell(row, "Emoji", "系统表情", !emojiMarkers, 13, () -> callback.onKey("表情样式:system"));
            addCompactPanelCell(row, "黄脸", "黄脸文字标记", emojiMarkers, 13, () -> callback.onKey("表情样式:wechat"));
        }
        addCompactPanelCell(row, "⌫", "退格", false, 22, () -> callback.onKey("⌫"));
        addCompactPanelCell(row, "返回", "返回键盘", false, 13, () -> callback.onKey("返回表情"));
        toolbarRow.addView(row, new LayoutParams(-1, -1));
    }

    private View addCompactPanelCell(LinearLayout row, String label, String description,
            boolean active, int textSize, Runnable action) {
        FrameLayout cell = new FrameLayout(getContext());
        cell.setContentDescription(description);
        cell.setOnClickListener(v -> action.run());
        TextView face = new TextView(getContext());
        face.setText(label);
        face.setTextSize(textSize);
        face.setGravity(Gravity.CENTER);
        face.setSingleLine(true);
        face.setEllipsize(android.text.TextUtils.TruncateAt.END);
        face.setMinWidth(dp(40));
        face.setPadding(dp(6), 0, dp(6), 0);
        face.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams faceParams = new FrameLayout.LayoutParams(-2, dp(36), Gravity.CENTER);
        faceParams.setMargins(dp(2), 0, dp(2), 0);
        cell.addView(face, faceParams);
        updateCompactPanelSelection(cell, active);
        row.addView(cell, new LayoutParams(0, dp(48), 1f));
        return cell;
    }

    private void updateCompactPanelSelection(View cell, boolean active) {
        AccessibilitySupport.selected(cell, active);
        if (!(cell instanceof FrameLayout)) return;
        View child = ((FrameLayout) cell).getChildAt(0);
        if (!(child instanceof TextView)) return;
        TextView face = (TextView) child;
        face.setTextColor(active ? theme.accent : theme.text);
        face.setBackground(active
                ? roundRect(blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f), 12)
                : null);
    }

    private void addEmojiCategories(String selected) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(20));
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
            tab.setBackground(value.equals(selected)
                    ? roundRect(blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f), 12)
                    : null);
            tab.setOnClickListener(v -> callback.onKey("表情分类:" + value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp("颜文字".equals(value) ? 76 : 52), dp(48));
            lp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(tab, lp);
            emojiCategoryTabs.put(value, tab);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        emojiCategories = scroll;
        addView(scroll, new LayoutParams(-1, dp(48)));
    }

    private void addPanelSwitches(String active) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] actions = {"😊", "符号", "剪贴板"};
        String[] labels = {"表情", "符号", "剪贴板"};
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            boolean selected = action.equals(active);
            addCompactPanelCell(row, labels[i],
                    labels[i] + (selected ? "，已打开，点击收起" : "，点击打开"),
                    selected, 14, () -> callback.onKey(action));
        }
        addView(row, new LayoutParams(-1, dp(48)));
    }

    boolean dismissActiveMenu() {
        if (activeMenu == null) return false;
        activeMenu.dismiss();
        return true;
    }

    private void removeRows() {
        if (handwritingPanel != null) {
            handwritingPanel = null;
            header.setVisibility(VISIBLE);
        }
        keyboardOverflowSpace = null;
        editorPanel = null;
        voicePanel = null;
        if (symbolPanelShown && symbolContent != null) savedSymbolScroll = symbolContent.getScrollY();
        if (emojiPanelShown && emojiContent != null) {
            savedEmojiPosition = emojiContent.getFirstVisiblePosition();
            savedEmojiTop = emojiContent.getChildCount() == 0 ? 0 : emojiContent.getChildAt(0).getTop();
        }
        symbolPanelShown = false;
        symbolContent = null;
        symbolGrid = null;
        symbolCategoryTabs.clear();
        emojiPanelShown = false;
        emojiContent = null;
        emojiGrid = null;
        emojiCategoryTabs.clear();
        letterButtons.clear();
        qwertyLetters.clear();
        editorActionKeys.clear();
        qwertyShift = null;
        keyLayoutRefreshPending = false;
        ((OfflineImeService) getContext()).keyInputOrder().cancelAll();
        pathTouches = 0;
        renderedKeyboardKey = null;
        interactionHandler.removeCallbacksAndMessages(null);
        expandedCandidatesScroll = null;
        expandedCandidateWords = java.util.Collections.emptyList();
        header.resetForRowsRemoved();
        // Fixed header and hidden composing label precede every page's body.
        while (getChildCount() > 2) removeViewAt(2);
    }

    private void hideComposingHeader() {
        header.hideCandidateBar();
        composing.setVisibility(GONE);
        applyCandidateHeaderSizing();
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
            if (action.length() == 1 && !isFunctionKey(action)) button.enableKeyPreview();
            if (action.length() == 1 && action.charAt(0) >= '0' && action.charAt(0) <= '9') button.setOnLongClickListener(v -> { callback.onKey("长按数字:" + action); return true; });
            if ("⌫".equals(action)) button.setOnLongClickListener(v -> { callback.onKey("⌫词"); return true; });
            int gap = SettingsRepository.integer(prefs, "key_gap", 2); LayoutParams p = new LayoutParams(0, -1, 1f); p.setMargins(dp(gap), dp(gap), dp(gap), dp(gap)); row.addView(button, p);
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
        boolean digit = action.length() == 1 && action.charAt(0) >= '0' && action.charAt(0) <= '9';
        KeyButton button = new KeyButton(getContext(), key, (!space && !digit && isFunctionKey(key)), current);
        if ((action.length() == 1 && !isFunctionKey(action)) || action.startsWith("长按数字:"))
            button.enableKeyPreview();
        if ("123".equals(action)) button.setContentDescription("切换数字键盘");
        if ("回车".equals(action)) {
            button.setActionIcon(R.drawable.ic_key_enter, key);
            button.setEmphasized(current);
            editorActionKeys.add(button);
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
        int requested = Math.max(0, Math.min(3, SettingsRepository.integer(renderSettings, "key_gap", 2)));
        return KeyboardPlacement.width(getContext(), renderSettings) < 340 ? Math.min(1, requested) : requested;
    }

    private int nineKeyVerticalGap() {
        return Math.max(0, Math.min(3, SettingsRepository.integer(renderSettings, "key_gap", 2)));
    }

    /** 候选字体设置随 styleRevision（任意 candidate_ 设置变化或重新附着）刷新。 */
    private CandidateTextStyle candidateStyle() {
        if (candidateTextStyle == null || candidateTextStyleRevision != styleRevision) {
            candidateTextStyle = CandidateTextStyle.read(renderSettings);
            candidateTextStyleRevision = styleRevision;
        }
        return candidateTextStyle;
    }

    private int candidateHeightDp() {
        return candidateStyle().barHeight(getContext());
    }

    private void applyCandidateHeaderSizing() {
        header.applyHeight(dp(candidateHeightDp()));
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

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
        ((OfflineImeService) getContext()).onKeyboardViewDetached(this);
        if (activeMenu != null) activeMenu.dismiss();
        pathTouches = 0;
        header.onDetachedCleanup();
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
        int height = KeyboardPlacement.height(getContext(), renderSettings);
        String surfaceKey = styleRevision + ":" + dark + ':' + floatingLandscape + ':' + current.orientation
                + ':' + current.screenWidthDp + ':' + current.screenHeightDp + ':' + current.fontScale
                + ':' + current.densityDpi + ':' + current.uiMode + ':' + height;
        if (surfaceKey.equals(renderedSurfaceKey)) return;
        renderedSurfaceKey = surfaceKey;
        theme = KeyboardTheme.from(getContext(), dark);
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
        // Measure typing layouts and regular panels at the same target height.
        // A height based on the number of rows makes the IME jump between pages.
        boolean writing = handwritingPanel != null;
        int target = writing ? handwritingTargetHeight() : Math.max(0, preferredMinHeight);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int available = MeasureSpec.getSize(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) target = available;
        else if (heightMode == MeasureSpec.AT_MOST && available > 0) {
            target = writing && handwritingFullscreen ? available : Math.min(target, available);
        }
        limitTypingRowHeight(target);
        setMinimumHeight(target);
        int exactHeightSpec = MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, exactHeightSpec);
        setMeasuredDimension(getMeasuredWidth(), target);
    }

    private void addKeyboardOverflowSpace() {
        keyboardOverflowSpace = new View(getContext());
        keyboardOverflowSpace.setVisibility(GONE);
        addView(keyboardOverflowSpace, new LayoutParams(-1, 0, 1f));
    }

    private void limitTypingRowHeight(int target) {
        if (keyboardOverflowSpace == null || keyboardOverflowSpace.getParent() != this) return;
        boolean nine = renderedKeyboardMode == OfflineImeService.Mode.NINE;
        int rowCount = nine ? 1 : 4;
        int start = indexOfChild(keyboardOverflowSpace) + 1;
        if (start < 1 || getChildCount() < start + rowCount) return;
        int fixed = getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            if (i >= start && i < start + rowCount) continue;
            View child = getChildAt(i);
            if (child == keyboardOverflowSpace || child.getVisibility() == GONE) continue;
            fixed += Math.max(0, ((LayoutParams) child.getLayoutParams()).height);
        }
        int rowHeight = dp(nine ? 96 : 100);
        int cappedTotal = rowHeight * (nine ? 3 : rowCount);
        boolean overflow = target - fixed > cappedTotal;
        keyboardOverflowSpace.setVisibility(overflow ? VISIBLE : GONE);
        for (int i = start; i < start + rowCount; i++) {
            LayoutParams params = (LayoutParams) getChildAt(i).getLayoutParams();
            int height = overflow ? (nine ? cappedTotal : rowHeight) : 0;
            float weight = overflow ? 0f : 1f;
            if (params.height != height || params.weight != weight) {
                params.height = height;
                params.weight = weight;
            }
        }
    }
}
