package com.example.offlineime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import android.app.AlertDialog;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/** 设置页只读写本地 SharedPreferences，并提供系统启用输入法入口。 */
public final class SettingsActivity extends Activity {
    private android.content.SharedPreferences preferences;
    private final Map<String, View> sectionViews = new HashMap<>();
    private ScrollView settingsScroll;
    private LinearLayout mainContent;
    private LinearLayout detailsContent;
    private LinearLayout pageHost;
    private LinearLayout activeSectionPage;
    private TextView toolbarTitle;
    private UiTokens.Palette palette;
    private RoundedCardPopup choicePopup;
    private final java.util.List<View> activeSectionChildren = new java.util.ArrayList<>();
    private final java.util.List<Integer> activeSectionIndices = new java.util.ArrayList<>();
    private int mainScrollY;
    private TextView skinChoice;
    private KeyboardSkinPreview skinPreview;
    private final Map<View, Runnable> paletteBindings = new HashMap<>();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (prefs, key) -> {
        if (key == null || "theme".equals(key)) {
            palette = UiTokens.palette(this);
            for (Runnable binding : paletteBindings.values()) binding.run();
        }
        if (key == null || "theme".equals(key) || "dark".equals(key)
                || "high_contrast".equals(key) || "appearance_mode".equals(key) || key.startsWith("candidate_") || key.startsWith("key_")) refreshSkinPreview();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        palette = UiTokens.palette(this);
        setTitle("输入法设置");
        configureSystemBars();

        LinearLayout content = new LinearLayout(this);
        mainContent = content;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(UiTokens.SPACE_LG), dp(UiTokens.SPACE_MD),
                dp(UiTokens.SPACE_LG), dp(UiTokens.SPACE_XL));
        content.setBackgroundColor(palette.background);

        TextView appTitle = text("开心输入法", 28, palette.textPrimary);
        appTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        appTitle.setPadding(dp(2), dp(8), 0, dp(4));
        content.addView(appTitle, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text("每一次输入，都合你的习惯", 13, palette.textSecondary);
        subtitle.setPadding(dp(2), 0, 0, dp(20));
        content.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout inputGroup = addSettingsGroup(content);
        addCategoryRow(inputGroup, "外观反馈", "皮肤、声音与键盘尺寸", true, false);
        addCategoryRow(inputGroup, "输入体验", "拼音、纠错与候选词", false, false);
        addCategoryRow(inputGroup, "键盘布局", "工具栏与显示选项", false, true);
        LinearLayout dataGroup = addSettingsGroup(content);
        addCategoryRow(dataGroup, "词库管理", "自定义词与学习记录", true, false);
        addCategoryRow(dataGroup, "隐私安全", "本地数据与隐私模式", false, false);
        addCategoryRow(dataGroup, "剪贴板", "历史记录与固定内容", false, false);
        addCategoryRow(dataGroup, "关于", "版本与词库来源", false, true);
        TextView setup = text("启用与切换输入法  ›", 13, palette.textSecondary);
        setup.setPadding(dp(4), 0, dp(4), 0);
        setup.setMinimumHeight(dp(48));
        setup.setOnClickListener(v -> openSection("启用输入法", "首次启用与切换输入法"));
        content.addView(setup, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout footer = new LinearLayout(this);
        footer.addView(text("输入内容保存在本机", 12, palette.textSecondary), new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(text(BuildConfig.VERSION_NAME, 12, palette.textSecondary));
        footer.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        addSection(content, "启用输入法");
        Button enable = new Button(this);
        enable.setText("在系统设置中启用输入法");
        enable.setTextColor(Color.WHITE);
        enable.setTextSize(15);
        enable.setAllCaps(false);
        enable.setBackground(pressableRoundRect(palette.accent, darken(palette.accent), 18));
        LinearLayout.LayoutParams enableParams = new LinearLayout.LayoutParams(-1, dp(56));
        enableParams.topMargin = dp(12);
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        content.addView(enable, enableParams);

        TextView howTo = text("启用后调用方法", 16, palette.textPrimary);
        howTo.setPadding(dp(4), dp(14), 0, dp(4));
        content.addView(howTo, new LinearLayout.LayoutParams(-1, dp(38)));
        TextView steps = text("1. 在系统列表打开“开心离线输入法”\n2. 在任意输入框点击键盘图标或长按空格\n3. 选择“开心离线输入法”即可调用", 14, palette.textSecondary);
        steps.setPadding(dp(4), 0, 0, dp(8));
        content.addView(steps, new LinearLayout.LayoutParams(-1, -2));
        Button picker = new Button(this);
        picker.setText("立即选择默认输入法"); picker.setAllCaps(false); picker.setTextSize(15);
        picker.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        content.addView(picker, new LinearLayout.LayoutParams(-1, dp(48)));

        addSection(content, "外观反馈");
        addSkinControls(content);
        addSubheading(content, "键盘样式");
        addChoice(content, "明暗模式", "appearance_mode", new String[]{"跟随系统", "浅色", "深色"}, new String[]{"system", "light", "dark"}, preferences.getBoolean("dark", false) ? "dark" : "system");
        addSwitch(content, "高对比度", "high_contrast", false);
        addChoice(content, "单手键盘", "one_hand_side", new String[]{"关闭", "左侧", "右侧"}, new String[]{"off", "left", "right"}, KeyboardPlacement.side(preferences));
        addSettingSlider(content, "单手宽度（百分比）", "one_hand_width", 70, 95, preferences.getInt("one_hand_width", 82));
        addSwitch(content, "大屏分离式键盘（宽屏时生效）", "split_keyboard", false);
        addSwitch(content, "浮动键盘（可拖动、调节尺寸）", "floating", false);
        addSettingSlider(content, "浮动宽度", "floating_width", 280, Math.max(280, getResources().getConfiguration().screenWidthDp), preferences.getInt("floating_width", 340));
        addSettingSlider(content, "浮动高度", "floating_height", 200, 560, preferences.getInt("floating_height", 300));
        addSubheading(content, "尺寸与按键");
        addAppearanceControls(content);
        addSubheading(content, "声音与震动");
        addChoice(content, "按键震动强度", "haptic_strength",
                new String[]{"关闭", "轻", "标准", "强"},
                new String[]{"off", "light", "standard", "strong"}, "standard");
        addChoice(content, "按键音量", "sound_volume",
                new String[]{"关闭", "低", "中", "高"},
                new String[]{"off", "low", "medium", "high"}, "off");

        addSection(content, "输入体验");
        addSubheading(content, "语言与方案");
        addChoice(content, "中文输出", "output_script", new String[]{"简体中文", "繁体中文"},
                new String[]{"simplified", "traditional"});
        addChoice(content, "默认中文键盘", "default_layout", new String[]{"26键拼音", "9键拼音"}, new String[]{"qwerty", "nine"});
        addSwitch(content, "中文键盘显示英文候选", "mixed_english", true);
        addSwitch(content, "中文成对标点", "paired_punctuation", true);
        addSwitch(content, "显示操作提示", "gesture_hints", true);
        addChoice(content, "26键拼音方案", "pinyin_scheme", new String[]{"全拼 / 简拼", "小鹤双拼"},
                new String[]{"full", "xiaohe"}, "full");
        addSubheading(content, "拼音与纠错");
        addSwitch(content, "全拼输入", "full_pinyin", true); addSwitch(content, "简拼输入", "short_pinyin", true);
        addSwitch(content, "全拼简拼混合输入", "mixed_pinyin", true);
        addSwitch(content, "双字母声母简拼（zh/ch/sh）", "double_initials", true);
        addSwitch(content, "拼写纠错（一次误触、漏字、多字或错序）", "spelling_correction", true);
        addSwitch(content, "26键触点辅助纠错（仅本次输入，不保存坐标）", "touch_correction", true);
        addSubheading(content, "模糊音");
        addSwitch(content, "启用模糊音", "fuzzy_pinyin", false);
        for (String[] pair : new String[][]{{"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"n", "l"}, {"h", "f"}, {"an", "ang"}, {"en", "eng"}, {"in", "ing"}, {"ian", "iang"}})
            addSwitch(content, "模糊音 " + pair[0] + " / " + pair[1], "fuzzy_" + pair[0] + "_" + pair[1], false);
        addSubheading(content, "联想与候选");
        addSwitch(content, "整句输入（最多 32 音节）", "sentence_prediction", true);
        addSwitch(content, "下一词联想", "next_word_prediction", true);
        addChoice(content, "手写停笔等待", "handwriting_delay", new String[]{"快速 250ms", "标准 400ms", "从容 650ms", "慢速 900ms"}, new String[]{"250", "400", "650", "900"}, "400");
        addSwitch(content, "默认使用连续多字手写", "handwriting_continuous", false);
        addChoice(content, "多字手写停笔等待", "handwriting_line_delay",
                new String[]{"快速 650ms", "标准 900ms", "从容 1300ms", "慢速 1800ms"},
                new String[]{"650", "900", "1300", "1800"}, "900");
        addSwitch(content, "自动提交首选词", "auto_commit", false);
        addChoice(content, "空格键行为", "space_behavior", new String[]{"选择首选词", "输入空格"}, new String[]{"select", "space"});
        addChoice(content, "候选词数量", "candidate_count", new String[]{"6", "9", "12", "15"}, new String[]{"6", "9", "12", "15"});
        addChoice(content, "候选字体", "candidate_font_size", new String[]{"小", "标准", "大", "特大"},
                new String[]{"15", "17", "19", "21"}, "17");
        addChoice(content, "候选栏高度", "candidate_bar_height", new String[]{"紧凑", "标准", "宽松", "加高"},
                new String[]{"44", "48", "52", "56"}, "48");
        addChoice(content, "展开候选页密度", "expanded_candidate_density",
                new String[]{"紧凑", "标准", "宽松"}, new String[]{"compact", "standard", "comfortable"}, "standard");
        addSection(content, "键盘布局");
        addSubheading(content, "显示选项");
        addSwitch(content, "显示工具栏", "toolbar", true); addSwitch(content, "显示模式文字", "show_mode", true);
        addSwitch(content, "显示拼音组合区", "show_composing", true);
        addSubheading(content, "工具栏入口");
        addToolbarCustomization(content);
        addSection(content, "词库管理");
        addSubheading(content, "词库学习");
        addSwitch(content, "用户词库学习", "learn_words", true); addSwitch(content, "允许长按加入词库", "learn_long_press", true); addSwitch(content, "屏蔽词过滤", "blocked_words", true);
        // 自定义词编辑器属于词库管理，紧跟在词库开关之后。
        addSubheading(content, "自定义词");
        addCustomWordControls(content);
        addSection(content, "隐私安全");
        addSubheading(content, "隐私保护");
        addSwitch(content, "隐私模式（禁止学习和剪贴板）", "privacy_mode", false); addSwitch(content, "敏感内容过滤", "sensitive_filter", true);
        addSwitch(content, "性能日志（仅记录耗时，不记录文本）", "performance_logging", false);
        addSubheading(content, "数据管理");
        Button clearAll = new Button(this);
        clearAll.setText("清除全部本地数据");
        clearAll.setTextColor(palette.danger);
        clearAll.setBackground(pressableRoundRect(palette.dangerSoft, palette.surfaceMuted, UiTokens.RADIUS_CONTROL));
        clearAll.setOnClickListener(v -> RoundedCardPopup.showDialog(new AlertDialog.Builder(this)
                .setTitle("确认清除？")
                .setMessage("主动保存的词、用户词库、上下文学习、拼音路径偏好、屏蔽词、表情记录和剪贴板历史将被删除。键盘外观设置会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    boolean cleared = clearLocalPersonalData();
                    Toast.makeText(this, cleared ? "本地学习数据和历史已清除" : "已清除数据，部分加密密钥未能删除",
                            Toast.LENGTH_SHORT).show();
                }).create()));
        content.addView(clearAll, new LinearLayout.LayoutParams(-1, dp(48)));
        Button reset = new Button(this); reset.setText("恢复默认设置"); reset.setOnClickListener(v -> RoundedCardPopup.showDialog(new AlertDialog.Builder(this).setTitle("恢复默认设置？").setNegativeButton("取消", null).setPositiveButton("恢复", (d,w) -> { preferences.edit().clear().apply(); getSharedPreferences("keyboard_state", MODE_PRIVATE).edit().clear().apply(); recreate(); }).create())); content.addView(reset, new LinearLayout.LayoutParams(-1, dp(48)));

        addSection(content, "剪贴板");
        addSubheading(content, "历史记录");
        addSwitch(content, "剪贴板历史", "clipboard_enabled", false);
        addSwitch(content, "键盘显示期间自动记录新复制文本", "clipboard_capture_while_typing", false);
        addChoice(content, "普通历史数量", "clipboard_limit",
                new String[]{"10 条", "20 条", "50 条", "100 条"},
                new String[]{"10", "20", "50", "100"});
        addChoice(content, "普通历史保留时间", "clipboard_retention_days",
                new String[]{"1 天", "7 天", "30 天", "永久"},
                new String[]{"1", "7", "30", "0"}, "7");
        addSubheading(content, "记录说明");
        TextView clipboardInfo = text("默认只在你打开剪贴板面板时读取系统当前文本。开启自动记录后，仅当本输入法正在显示、剪贴板历史已启用且不在隐私/密码输入时，才监听新复制的文本；离开键盘后立即停止。带有系统敏感标记、密码类标签或验证码特征的内容会跳过。\n\n固定内容独立保留；剪贴板页面支持固定分组、搜索、批量选择和删除。清空后可在键盘上撤销 10 秒。", 14, palette.textSecondary); content.addView(clipboardInfo, new LinearLayout.LayoutParams(-1, -2));
        TextView privacy = text("隐私说明\n\n本应用只在当前输入框中完成本地组合和候选。密码、支付和验证码输入不会写入用户词库。本版本没有网络权限、语音权限、联系人权限、存储权限和无障碍服务。", 14, palette.textSecondary);
        privacy.setPadding(0, dp(18), 0, 0);
        addSection(content, "关于");
        addSubheading(content, "版本信息");
        TextView about = text(getString(R.string.about_version_text, BuildConfig.VERSION_NAME), 14, palette.textSecondary); content.addView(about, new LinearLayout.LayoutParams(-1, -2));
        Button licenses = new Button(this); licenses.setText("词库与表情来源"); licenses.setOnClickListener(v -> showLexiconLicenses()); content.addView(licenses, new LinearLayout.LayoutParams(-1, dp(48)));
        addSubheading(content, "隐私说明");
        content.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
        // 分类控件先存放在隐藏容器，打开二级页面时移入当前页面。
        detailsContent = new LinearLayout(this);
        detailsContent.setOrientation(LinearLayout.VERTICAL);
        View firstDetail = sectionViews.get("启用输入法");
        int firstDetailIndex = firstDetail == null ? content.getChildCount() : content.indexOfChild(firstDetail);
        while (content.getChildCount() > firstDetailIndex) {
            View detail = content.getChildAt(firstDetailIndex);
            content.removeViewAt(firstDetailIndex);
            detailsContent.addView(detail);
        }
        detailsContent.setVisibility(View.GONE);

        // 分类导航固定在一级页面，返回时恢复此前的浏览位置。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);
        toolbarTitle = text("输入法设置", 17, palette.textPrimary);
        toolbarTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), 0, dp(16), 0);
        toolbar.setBackgroundColor(palette.background);
        Button back = new Button(this);
        back.setText("‹"); back.setTextSize(34); back.setTextColor(palette.textPrimary); back.setAllCaps(false);
        back.setGravity(Gravity.CENTER); back.setPadding(0, 0, 0, dp(4)); back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("返回"); back.setOnClickListener(v -> showMainPage());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));
        toolbar.addView(toolbarTitle, new LinearLayout.LayoutParams(0, dp(56), 1));
        TextView offlineBadge = text("完全离线", 12, palette.accent);
        offlineBadge.setGravity(Gravity.CENTER); offlineBadge.setBackground(roundRect(palette.accentSoft, UiTokens.RADIUS_CONTROL));
        bindPalette(offlineBadge, () -> { offlineBadge.setTextColor(palette.accent); offlineBadge.setBackground(roundRect(palette.accentSoft, 8)); });
        toolbar.addView(offlineBadge, new LinearLayout.LayoutParams(dp(72), dp(30)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setVerticalScrollBarEnabled(false);
        settingsScroll.setClipToPadding(false);
        settingsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        pageHost.addView(mainContent, new LinearLayout.LayoutParams(-1, -2));
        pageHost.addView(detailsContent, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout pageFrame = new FrameLayout(this);
        android.content.res.Configuration config = getResources().getConfiguration();
        boolean wideLayout = config.smallestScreenWidthDp >= 600 || config.screenWidthDp >= 840;
        int maxContent = wideLayout ? dp(720) : -1;
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(maxContent, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pageFrame.addView(pageHost, pageParams);
        settingsScroll.addView(pageFrame, new ScrollView.LayoutParams(-1, -2));
        root.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0, top = 0, right = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        flattenButtonShadows(root);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void addSkinControls(LinearLayout parent) {
        LinearLayout skinRow = new LinearLayout(this);
        skinRow.setGravity(Gravity.CENTER_VERTICAL);
        skinRow.setPadding(dp(12), 0, dp(12), 0);
        skinRow.setMinimumHeight(dp(56));
        skinRow.setTag("setting-choice");
        TextView label = text("键盘皮肤", 15, palette.textPrimary);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        skinRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        skinChoice = text("", 16, palette.textPrimary);
        skinChoice.setTextSize(14);
        skinChoice.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        skinChoice.setMaxWidth(dp(160));
        skinChoice.setSingleLine(true);
        skinChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bindPalette(skinChoice, () -> skinChoice.setTextColor(palette.accent));
        skinChoice.setOnClickListener(v -> showSkinPicker());
        skinChoice.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        skinRow.addView(skinChoice, new LinearLayout.LayoutParams(-2, dp(56)));
        skinRow.setOnClickListener(v -> showSkinPicker());
        skinRow.setFocusable(true);
        bindPalette(skinRow, () -> skinRow.setBackground(pressableRoundRect(Color.TRANSPARENT, palette.accentSoft, 12)));
        parent.addView(skinRow, new LinearLayout.LayoutParams(-1, -2));
        skinPreview = new KeyboardSkinPreview(this, KeyboardTheme.from(this, preferences.getBoolean("dark", false)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(132));
        previewParams.setMargins(dp(12), 0, dp(12), 0);
        parent.addView(skinPreview, previewParams);
        TextView note = text("配色、字形与间距实时预览；支持系统明暗模式。单手模式保持原字号。", 13, palette.textSecondary);
        note.setPadding(dp(12), dp(10), dp(12), dp(14));
        parent.addView(note, new LinearLayout.LayoutParams(-1, -2));
        refreshSkinPreview();
        preferences.registerOnSharedPreferenceChangeListener(appearanceListener);
    }

    private void refreshSkinPreview() {
        if (skinPreview == null) return;
        KeyboardTheme.Skin selected = KeyboardTheme.skin(preferences.getString("theme", "system"));
        skinChoice.setText(selected.label + "  ›");
        skinChoice.setContentDescription("键盘皮肤，当前" + selected.label + "，点击选择");
        if (skinChoice.getParent() instanceof View)
            ((View) skinChoice.getParent()).setContentDescription(skinChoice.getContentDescription());
        skinPreview.setTheme(KeyboardTheme.from(this, preferences.getBoolean("dark", false)));
        skinPreview.setContentDescription(selected.label + "键盘预览，键盘高度" + KeyboardSizing.height(preferences));
        android.view.ViewGroup.LayoutParams previewSize = skinPreview.getLayoutParams();
        if (previewSize != null) { previewSize.height = dp(Math.max(110, Math.min(220, KeyboardSizing.height(preferences) / 2))); skinPreview.setLayoutParams(previewSize); }
    }

    private void showSkinPicker() {
        RoundedCardPopup menu = RoundedCardPopup.settings(this, "键盘皮肤").width(360);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String selected = KeyboardTheme.skin(preferences.getString("theme", "system")).id;
        boolean dark = KeyboardTheme.resolveDark(this);
        for (int start = 0; start < KeyboardTheme.SKINS.size(); start += 2) {
            LinearLayout row = new LinearLayout(this);
            for (int column = 0; column < 2; column++) {
                int index = start + column;
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
                tileParams.setMargins(dp(3), dp(3), dp(3), dp(3));
                if (index >= KeyboardTheme.SKINS.size()) {
                    row.addView(new View(this), tileParams);
                    continue;
                }
                KeyboardTheme.Skin skin = KeyboardTheme.SKINS.get(index);
                boolean checked = skin.id.equals(selected);
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(dp(4), dp(4), dp(4), dp(2));
                GradientDrawable border = roundRect(palette.surfaceMuted, UiTokens.RADIUS_CONTROL);
                border.setStroke(dp(checked ? 2 : 1), checked ? palette.accent : palette.divider);
                tile.setBackground(border);
                KeyboardSkinPreview preview = new KeyboardSkinPreview(this, KeyboardTheme.forSkin(this, skin.id, dark, false));
                preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.addView(preview, new LinearLayout.LayoutParams(-1, dp(86)));
                TextView name = text(skin.label + (checked ? "  ✓" : ""), 13, checked ? palette.accent : palette.textPrimary);
                name.setGravity(Gravity.CENTER);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.addView(name, new LinearLayout.LayoutParams(-1, dp(34)));
                tile.setSelected(checked);
                tile.setFocusable(true);
                tile.setContentDescription(skin.label + (checked ? "，已选择" : "，点击使用"));
                tile.setOnClickListener(v -> {
                    preferences.edit().putString("theme", skin.id).apply();
                    refreshSkinPreview();
                    menu.dismiss();
                });
                row.addView(tile, tileParams);
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        menu.content(grid);
        showChoicePopup(skinChoice, menu);
    }

    private void addAppearanceControls(LinearLayout parent) {
        LinearLayout presets = new LinearLayout(this);
        String[] names = {"紧凑", "标准", "大字"};
        for (int i = 0; i < names.length; i++) {
            final int preset = i;
            Button button = new Button(this); button.setText(names[i]);
            button.setOnClickListener(v -> {
                preferences.edit().putInt("key_height", new int[]{250, 270, 340}[preset])
                        .putInt("key_font", new int[]{18, 20, 24}[preset]).putInt("key_gap", 2).putInt("key_radius", 12)
                        .putString("candidate_font_size", new String[]{"15", "17", "21"}[preset])
                        .putString("candidate_bar_height", new String[]{"44", "48", "56"}[preset])
                        .putString("expanded_candidate_density", preset == 2 ? "comfortable" : "standard").apply();
                recreate();
            }); presets.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        parent.addView(presets);
        addSettingSlider(parent, "横屏高度", "key_height_landscape", 180, 360, preferences.getInt("key_height_landscape", 240));
        addSettingSlider(parent, "键盘高度", "key_height", KeyboardSizing.MIN_HEIGHT,
                KeyboardSizing.MAX_HEIGHT, KeyboardSizing.height(preferences));
        addSettingSlider(parent, "按键字体大小", "key_font", 14, 24, preferences.getInt("key_font", 20));
        addSettingSlider(parent, "按键圆角", "key_radius", 0, 16, preferences.getInt("key_radius", 12));
        addSettingSlider(parent, "按键间距（单边）", "key_gap", 0, 3, Math.min(3, preferences.getInt("key_gap", 2)));
    }

    private void addSettingSlider(LinearLayout parent, String label, String key,
                                  int minimum, int maximum, int current) {
        LinearLayout heading = new LinearLayout(this);
        heading.setTag("setting-slider-label");
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), dp(12), dp(12), 0);
        heading.addView(text(label, 15, palette.textPrimary), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(Integer.toString(Math.max(minimum, Math.min(maximum, current))), 14, palette.accent);
        bindPalette(value, () -> value.setTextColor(palette.accent));
        heading.addView(value);
        Button reset = new Button(this); reset.setText("重置"); reset.setContentDescription("重置" + label);
        reset.setOnClickListener(v -> { preferences.edit().remove(key).apply(); recreate(); });
        heading.addView(reset, new LinearLayout.LayoutParams(dp(72), dp(48)));
        parent.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        SeekBar slider = new SeekBar(this);
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setMax(maximum - minimum);
        slider.setProgress(Math.max(0, Math.min(maximum - minimum, current - minimum)));
        slider.setContentDescription(label);
        styleSlider(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(Integer.toString(minimum + progress));
                if (fromUser) preferences.edit().putInt(key, minimum + progress).apply();
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void styleSlider(SeekBar slider) {
        bindPalette(slider, () -> {
            slider.setThumbTintList(ColorStateList.valueOf(palette.accent));
            slider.setProgressTintList(ColorStateList.valueOf(palette.accent));
            slider.setProgressBackgroundTintList(ColorStateList.valueOf(palette.divider));
        });
    }

    private void addCustomWordControls(LinearLayout parent) {
        TextView hint = text("键盘 → 布局 → 造词，可逐段选字并保存完整读音。保存与固定首选分别设置。", 14, palette.textSecondary);
        parent.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        for (String title : new String[]{"添加自定义词", "管理自定义词与学习词", "导入、导出与备份词库"}) {
            Button button = new Button(this); button.setText(title);
            button.setOnClickListener(v -> startActivity(new Intent(this, CustomWordManagerActivity.class)
                    .putExtra("add", "添加自定义词".equals(title)).putExtra("backup", title.startsWith("导入"))));
            parent.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        Button clearContext = new Button(this); clearContext.setText("清除联想记录");
        clearContext.setOnClickListener(v -> CustomWordStore.IO.execute(() -> {
            new UserContextModel(getApplicationContext()).clear();
            preferences.edit().putLong("context_revision", System.currentTimeMillis()).apply();
            runOnUiThread(() -> Toast.makeText(this, "联想记录已清除", Toast.LENGTH_SHORT).show());
        }));
        parent.addView(clearContext, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    /** 工具栏项目可分别选择功能，模式切换统一放在“键盘布局”按钮中。 */
    private void addToolbarCustomization(LinearLayout parent) {
        TextView hint = text("工具栏入口（最多 7 个，从左到右）", 15, palette.textSecondary);
        hint.setPadding(dp(4), dp(10), 0, dp(2));
        parent.addView(hint, new LinearLayout.LayoutParams(-1, dp(36)));
        String[] labels = ToolbarConfig.LABELS;
        String[] values = ToolbarConfig.VALUES;
        String[] defaults = ToolbarConfig.DEFAULTS;
        // Keep the first occurrence of a legacy duplicate and clear its later slots.
        java.util.HashSet<String> used = new java.util.HashSet<>();
        android.content.SharedPreferences.Editor cleanup = preferences.edit();
        for (int i = 0; i < defaults.length; i++) {
            String action = ToolbarConfig.normalize(preferences.getString("toolbar_item_" + i, defaults[i]), i);
            if (!action.isEmpty() && !used.add(action)) action = "";
            cleanup.putString("toolbar_item_" + i, action);
        }
        cleanup.apply();
        for (int i = 0; i < defaults.length; i++) {
            final int slot = i;
            TextView choiceButton = createChoiceButton("工具栏第" + (i + 1) + "项", "toolbar_item_" + i,
                    defaults[i], labels, values, choice -> {
                if (!choice.isEmpty()) {
                    for (int other = 0; other < defaults.length; other++) {
                        if (other != slot && choice.equals(preferences.getString("toolbar_item_" + other, defaults[other]))) {
                            Toast.makeText(this, "该功能已添加，请先将原位置设为不显示", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                    }
                }
                return true;
            });
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(12), 0, dp(8), 0);
            line.setTag("setting-choice");
            line.setBackgroundColor(Color.TRANSPARENT);
            TextView slotLabel = text(String.format(Locale.ROOT, "%02d", i + 1), 14, palette.textSecondary);
            slotLabel.setGravity(Gravity.CENTER);
            line.addView(slotLabel, new LinearLayout.LayoutParams(dp(42), dp(52)));
            line.addView(choiceButton, new LinearLayout.LayoutParams(0, dp(52), 1));
            line.setOnClickListener(v -> choiceButton.performClick());
            line.setContentDescription("工具栏第" + (i + 1) + "项，" + choiceButton.getText());
            choiceButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
            params.setMargins(0, dp(2), 0, dp(2));
            parent.addView(line, params);
        }
        TextView note = text("每项可选择功能或“不显示”，相同功能只保留一个入口，最右侧固定为收起键盘。通过“键盘布局”切换模式；修改后重新唤起键盘生效。", 13, palette.textSecondary);
        note.setPadding(dp(4), dp(4), dp(4), dp(8));
        parent.addView(note, new LinearLayout.LayoutParams(-1, -2));
        Button reset = new Button(this);
        reset.setText("恢复工具栏默认顺序");
        reset.setAllCaps(false);
        reset.setTextColor(palette.accent);
        reset.setBackground(pressableRoundRect(palette.accentSoft, palette.surfaceMuted, UiTokens.RADIUS_CONTROL));
        reset.setOnClickListener(v -> {
            android.content.SharedPreferences.Editor editor = preferences.edit();
            for (int i = 0; i < defaults.length; i++) editor.remove("toolbar_item_" + i);
            editor.apply();
            Toast.makeText(this, "已恢复默认顺序，重新唤起键盘后生效", Toast.LENGTH_SHORT).show();
            recreate();
        });
        parent.addView(reset, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addSection(LinearLayout parent, String title) {
        TextView v = text(title, 18, palette.textPrimary);
        v.setTextColor(palette.accent);
        v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        v.setBackground(roundRect(palette.surface, UiTokens.RADIUS_CONTROL));
        v.setTag(title);
        sectionViews.put(title, v);
        v.setPadding(dp(14), dp(10), dp(8), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(12);
        parent.addView(v, params);
    }

    private void addSubheading(LinearLayout parent, String title) {
        TextView label = text(title, 13, palette.textSecondary);
        label.setTag("setting-subsection");
        label.setPadding(dp(12), dp(4), dp(12), dp(8));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout addSettingsGroup(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(roundRect(palette.surface, UiTokens.RADIUS_CARD));
        group.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        parent.addView(group, params);
        return group;
    }

    private void addDivider(LinearLayout group, int inset) {
        View divider = new View(this);
        divider.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 1);
        params.setMargins(dp(inset), 0, dp(12), 0);
        group.addView(divider, params);
    }

    private boolean isSettingRow(View view) {
        return view instanceof Switch || "setting-choice".equals(view.getTag());
    }

    private void bindPalette(View view, Runnable binding) {
        paletteBindings.put(view, binding);
        binding.run();
    }

    private void styleSwitch(Switch control) {
        control.setTextColor(palette.textPrimary);
        control.setTextSize(15);
        control.setShowText(false);
        control.setSwitchPadding(dp(14));
        control.setPadding(dp(12), dp(10), dp(12), dp(10));
        control.setBackgroundColor(Color.TRANSPARENT);
        control.setMinimumHeight(dp(56));
        bindPalette(control, () -> {
            int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
            control.setThumbTintList(new ColorStateList(states, new int[]{palette.accent, palette.textSecondary}));
            control.setTrackTintList(new ColorStateList(states, new int[]{palette.accentSoft, palette.divider}));
        });
    }

    /** Open a category as a complete settings page; compact popups are reserved for value choices. */
    private void openSection(String category, String summary) {
        if (detailsContent == null || activeSectionPage != null) return;
        if (choicePopup != null) choicePopup.dismiss();
        mainScrollY = settingsScroll == null ? 0 : settingsScroll.getScrollY();
        activeSectionChildren.clear();
        activeSectionIndices.clear();
        boolean active = false;
        for (int i = 0; i < detailsContent.getChildCount(); i++) {
            View child = detailsContent.getChildAt(i);
            if (sectionViews.containsValue(child)) {
                if (active) break;
                active = child == sectionViews.get(category);
                continue;
            }
            if (active) {
                activeSectionChildren.add(child);
                activeSectionIndices.add(i);
            }
        }

        activeSectionPage = new LinearLayout(this);
        activeSectionPage.setOrientation(LinearLayout.VERTICAL);
        activeSectionPage.setPadding(dp(16), dp(12), dp(16), dp(28));
        activeSectionPage.setBackgroundColor(palette.background);
        TextView pageSummary = text(summary, 13, palette.textSecondary);
        pageSummary.setPadding(dp(4), 0, dp(4), dp(16));
        activeSectionPage.addView(pageSummary, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout sectionCard = null;
        boolean previousRow = false;
        for (View child : activeSectionChildren) {
            detailsContent.removeView(child);
            styleSecondaryControl(child);
            if ("setting-subsection".equals(child.getTag())) {
                activeSectionPage.addView(child);
                sectionCard = null;
                previousRow = false;
                continue;
            }
            if (sectionCard == null) sectionCard = addSettingsGroup(activeSectionPage);
            boolean settingRow = isSettingRow(child);
            if (settingRow && previousRow) addDivider(sectionCard, 12);
            sectionCard.addView(child);
            previousRow = settingRow;
        }

        mainContent.setVisibility(View.GONE);
        pageHost.addView(activeSectionPage, 0, new LinearLayout.LayoutParams(-1, -2));
        toolbarTitle.setText(category);
        settingsScroll.post(() -> settingsScroll.scrollTo(0, 0));
    }

    private void showMainPage() {
        if (choicePopup != null && choicePopup.isShowing()) {
            choicePopup.dismiss();
            return;
        }
        if (activeSectionPage == null) {
            finish();
            return;
        }
        for (int i = 0; i < activeSectionChildren.size(); i++) {
            View child = activeSectionChildren.get(i);
            if (child.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) child.getParent()).removeView(child);
            }
            int target = Math.min(activeSectionIndices.get(i), detailsContent.getChildCount());
            detailsContent.addView(child, target);
        }
        pageHost.removeView(activeSectionPage);
        activeSectionPage = null;
        activeSectionChildren.clear();
        activeSectionIndices.clear();
        mainContent.setVisibility(View.VISIBLE);
        toolbarTitle.setText("输入法设置");
        settingsScroll.post(() -> settingsScroll.scrollTo(0, mainScrollY));
    }

    private void addCategoryRow(LinearLayout parent, String title, String summary,
                                boolean first, boolean last) {
        if (parent.getChildCount() > 0) {
            addDivider(parent, 56);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(64));
        row.setBackground(pressableCornerRect(palette.surface, palette.surfaceMuted, 16, first, last));
        FrameLayout iconTile = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(categoryIcon(title));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iconTile.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        bindPalette(iconTile, () -> { iconTile.setBackground(roundRect(palette.accentSoft, 9)); icon.setColorFilter(palette.accent); });
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconParams.rightMargin = dp(12);
        row.addView(iconTile, iconParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 15, palette.textPrimary);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        labels.addView(name);
        TextView detail = text(summary, 12, palette.textSecondary);
        labels.addView(detail);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_settings_chevron);
        arrow.setColorFilter(palette.textSecondary);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        arrowParams.leftMargin = dp(8);
        row.addView(arrow, arrowParams);
        row.setContentDescription(title + "，" + summary);
        row.setFocusable(true);
        row.setOnClickListener(v -> openSection(title, summary));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private int categoryIcon(String category) {
        switch (category) {
            case "外观反馈": return R.drawable.ic_settings_palette;
            case "输入体验": return R.drawable.ic_settings_sliders;
            case "键盘布局": return R.drawable.ic_toolbar_keyboard;
            case "词库管理": return R.drawable.ic_settings_dictionary;
            case "隐私安全": return R.drawable.ic_settings_shield;
            case "剪贴板": return R.drawable.ic_toolbar_clipboard;
            default: return R.drawable.ic_settings_info;
        }
    }

    private void addSwitch(LinearLayout parent, String label, String key, boolean def) {
        Switch s = new Switch(this); s.setText(label); s.setTextSize(16); s.setTextColor(palette.textPrimary); s.setPadding(dp(12), 0, dp(8), 0);
        s.setMinHeight(dp(48)); s.setBackgroundColor(palette.surface); s.setChecked(preferences.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            // Privacy pauses collection; deleting saved history requires the explicit clear action.
        });
        styleSwitch(s);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        parent.addView(s, params);
    }

    private boolean clearLocalPersonalData() {
        boolean cleared = PinyinDictionary.clearStoredUserWords(this);
        cleared &= CustomWordStore.clear(this);
        cleared &= SecureLocalStore.clear(this, "context_model", "offline_ime_context");
        cleared &= SecureLocalStore.clear(this, "clipboard_history", "offline_ime_clipboard");
        for (String preferencesName : new String[]{"blocked_words", "pinyin_path_usage", "emoji", "symbol_recent", "gesture_tips"}) {
            cleared &= getSharedPreferences(preferencesName, MODE_PRIVATE).edit().clear().commit();
        }
        sendBroadcast(new Intent(UserDataEvents.LOCAL_DATA_CLEARED).setPackage(getPackageName()),
                UserDataEvents.INTERNAL_PERMISSION);
        return cleared;
    }

    private void showLexiconLicenses() {
        String notice = "内置词库来源\n"
                + "中文基础候选、生活词和扩展词来自项目 lexicon 中各文件头部所列来源；部分词条为项目整理。\n"
                + "英文词频词库基于 wordfreq 3.1.1（Apache License 2.0），由项目筛选并加入少量常用技术词。\n\n"
                + "Apache 许可摘要\n" + readBundledText("en/LICENSE.wordfreq.md")
                + "\n\n来源说明\n" + readBundledText("en/README.md")
                + "\n\n" + readBundledText("desktop-import-notice.md")
                + "\n\n" + readBundledText("category-expansion-notice.md")
                + "\n\n分类词库语料许可\n" + readBundledText("category-LICENSE.jieba.md")
                + "\n\n拼音生成许可\n" + readBundledText("category-LICENSE.pypinyin.md")
                + "\n\n单字读音数据许可\n" + readBundledText("zh/LICENSE.pinyin-data.md")
                + "\n中文词库各数据源的再分发条件须以对应来源和许可为准。公开发布前请核实全部词库的授权，并随应用提供适用的完整许可文本。"
                + "\n\nEmoji 面板样式\n" + readBundledText("emoji_styles/NOTICE.txt")
                + "\nTwemoji 图形许可\n" + readBundledText("emoji_styles/licenses/twemoji.txt")
                + "\nOpenMoji 图形许可\n" + readBundledText("emoji_styles/licenses/openmoji.txt")
                + "\nFluent Emoji 图形许可\n" + readBundledText("emoji_styles/licenses/fluent.txt")
                + "\n\n简繁转换数据\n" + readBundledText("opencc/NOTICE.txt")
                + "\n许可文本\n" + readBundledText("opencc/LICENSE.txt")
                + "\n\n笔画输入数据\n" + readBundledText("stroke/NOTICE.txt");
        TextView content = text(notice, 14, palette.textPrimary);
        content.setTextIsSelectable(true);
        content.setGravity(Gravity.TOP);
        content.setPadding(dp(18), dp(14), dp(18), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        RoundedCardPopup.showDialog(new AlertDialog.Builder(this).setTitle("词库与表情来源").setView(scroll)
                .setPositiveButton("关闭", null).create());
    }

    private String readBundledText(String path) {
        try (java.io.InputStream stream = getAssets().open(path);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            return "无法读取随包文件：" + path;
        }
    }

    private void styleSecondaryControl(View view) {
        if (view instanceof Switch) {
            styleSwitch((Switch) view);
        } else if (view instanceof Button) {
            Button button = (Button) view;
            int current = button.getCurrentTextColor();
            boolean danger = current == palette.danger, accent = current == palette.accent;
            button.setTextSize(15);
            button.setAllCaps(false);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setPadding(dp(12), dp(12), dp(12), dp(12));
            button.setMinimumHeight(dp(56));
            bindPalette(button, () -> {
                button.setTextColor(danger ? palette.danger : accent ? palette.accent : palette.textPrimary);
                button.setBackground(pressableRoundRect(Color.TRANSPARENT,
                        danger ? palette.dangerSoft : palette.accentSoft, 8));
            });
            if (button.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
                params.height = -2;
                params.topMargin = 0;
                params.bottomMargin = 0;
                button.setLayoutParams(params);
            }
        } else if (view instanceof SeekBar) {
            styleSlider((SeekBar) view);
        } else if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setTextColor(palette.textPrimary);
            field.setHintTextColor(palette.textSecondary);
            field.setPadding(dp(10), dp(8), dp(10), dp(8));
            bindPalette(field, () -> field.setBackgroundTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{palette.accent, palette.divider})));
        } else if (view instanceof TextView) {
            TextView label = (TextView) view;
            Object parentTag = label.getParent() instanceof View ? ((View) label.getParent()).getTag() : null;
            if (!"setting-choice".equals(parentTag) && !"setting-slider-label".equals(parentTag)
                    && label.getPaddingLeft() == 0 && label.getPaddingRight() == 0) {
                label.setPadding(dp(12), Math.max(dp(8), label.getPaddingTop()),
                        dp(12), Math.max(dp(8), label.getPaddingBottom()));
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleSecondaryControl(group.getChildAt(i));
        }
    }

    private void flattenButtonShadows(View view) {
        if (view instanceof Button) {
            // Material's default state animator raises even custom backgrounds,
            // causing overlapping shadows at adjacent rounded button corners.
            view.setStateListAnimator(null);
            view.setElevation(0f);
            view.setTranslationZ(0f);
            view.setBackgroundTintList(null);
            view.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(true);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) flattenButtonShadows(group.getChildAt(i));
        }
    }

    private void addChoice(LinearLayout parent, String label, String key, String[] labels, String[] values) {
        addChoice(parent, label, key, labels, values, values[0]);
    }

    private void addChoice(LinearLayout parent, String label, String key, String[] labels,
                           String[] values, String defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setTag("setting-choice");
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(4));
        row.setMinimumHeight(dp(56));
        bindPalette(row, () -> row.setBackground(pressableRoundRect(Color.TRANSPARENT, palette.accentSoft, 12)));
        TextView title = text(label, 15, palette.textPrimary);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = createChoiceButton(label, key, defaultValue, labels, values, ignored -> true);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setMaxWidth(dp(152));
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> value.performClick());
        row.setFocusable(true);
        updateChoiceButton(value, label, key, defaultValue, labels, values);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView createChoiceButton(String label, String key, String defaultValue,
                                       String[] labels, String[] values,
                                       java.util.function.Predicate<String> canSelect) {
        TextView button = text("", 14, palette.accent);
        button.setPadding(dp(4), dp(8), dp(2), dp(8));
        button.setMinimumHeight(dp(48));
        bindPalette(button, () -> button.setTextColor(palette.accent));
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        updateChoiceButton(button, label, key, defaultValue, labels, values);
        button.setOnClickListener(v -> {
            String selected = preferences.getString(key, defaultValue);
            RoundedCardPopup menu = RoundedCardPopup.settings(this, label);
            for (int i = 0; i < values.length; i++) {
                final int option = i;
                menu.item(labels[i], null, null, values[i].equals(selected), false, () -> {
                    if (!canSelect.test(values[option])) return;
                    preferences.edit().putString(key, values[option]).apply();
                    updateChoiceButton(button, label, key, defaultValue, labels, values);
                });
            }
            showChoicePopup(button, menu);
        });
        return button;
    }

    private void updateChoiceButton(TextView button, String label, String key, String defaultValue,
                                    String[] labels, String[] values) {
        String selected = preferences.getString(key, defaultValue);
        int position = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) { position = i; break; }
        button.setText(labels[position] + "  ›");
        button.setContentDescription(label + "，当前" + labels[position] + "，点击选择");
        if (button.getParent() instanceof View && "setting-choice".equals(((View) button.getParent()).getTag()))
            ((View) button.getParent()).setContentDescription(button.getContentDescription());
    }

    private void showChoicePopup(View anchor, RoundedCardPopup menu) {
        if (choicePopup != null) choicePopup.dismiss();
        choicePopup = menu;
        menu.onDismiss(() -> { if (choicePopup == menu) choicePopup = null; });
        menu.show(anchor);
    }

    @Override public void onBackPressed() { showMainPage(); }

    @Override protected void onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(appearanceListener);
        if (choicePopup != null) choicePopup.dismiss();
        super.onDestroy();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private android.graphics.drawable.StateListDrawable pressableRoundRect(
            int normalColor, int pressedColor, int radiusDp) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundRect(pressedColor, radiusDp));
        states.addState(new int[]{}, roundRect(normalColor, radiusDp));
        return states;
    }

    private android.graphics.drawable.StateListDrawable pressableCornerRect(
            int normalColor, int pressedColor, int radiusDp, boolean top, boolean bottom) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                cornerRect(pressedColor, radiusDp, top, bottom));
        states.addState(new int[]{}, cornerRect(normalColor, radiusDp, top, bottom));
        return states;
    }

    private int darken(int color) {
        return Color.rgb((int) (Color.red(color) * 0.82f),
                (int) (Color.green(color) * 0.82f),
                (int) (Color.blue(color) * 0.82f));
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable cornerRect(int color, int radiusDp, boolean top, boolean bottom) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float radius = dp(radiusDp);
        drawable.setCornerRadii(new float[]{
                top ? radius : 0, top ? radius : 0,
                top ? radius : 0, top ? radius : 0,
                bottom ? radius : 0, bottom ? radius : 0,
                bottom ? radius : 0, bottom ? radius : 0
        });
        return drawable;
    }

    private void configureSystemBars() {
        android.view.Window window = getWindow();
        window.setStatusBarColor(palette.surface);
        window.setNavigationBarColor(palette.background);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(palette.divider);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = 0;
        if (!UiTokens.isDark(this)) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
