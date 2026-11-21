package com.example.offlineime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Hosts the settings home and builds the selected section on demand. */
public final class SettingsActivity extends Activity {
    private SharedPreferences preferences;
    private SettingsNavigator navigator;
    private SettingsSetupPage setupPage;
    private SettingsAppearancePage appearancePage;
    private SettingsCandidatePage candidatePage;
    private QuickPhraseSettingsPage quickPhrasePage;
    private KeyboardSkinPreview inputLayoutPreview;
    private UiTokens.Palette palette;
    private RoundedCardPopup choicePopup;
    private boolean recreatePending;
    SettingsRows rows;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        if (key == null || "appearance_mode".equals(key) || "dark".equals(key)) {
            scheduleRecreate();
            return;
        }
        if (appearancePage != null) appearancePage.refreshPreview(null, 0);
        if (candidatePage != null) candidatePage.refreshPreview(null, 0);
        if (inputLayoutPreview != null) inputLayoutPreview.refresh(null, 0);
    };

    SharedPreferences preferences() { return preferences; }
    UiTokens.Palette palette() { return palette; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = SettingsRepository.preferences(this);
        palette = UiTokens.palette(this);
        rows = new SettingsRows(this);
        setupPage = new SettingsSetupPage(this);
        setTitle(R.string.settings_title);
        configureSystemBars();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(dp(64));
        toolbar.setPaddingRelative(dp(8), dp(4), dp(SettingsUi.PAGE_INSET), dp(4));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_settings_back);
        back.setImageTintList(ColorStateList.valueOf(palette.textPrimary));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.accentSoft), null,
                roundRect(Color.WHITE, 24)));
        back.setContentDescription(getString(R.string.settings_back));
        back.setOnClickListener(v -> {
            if (!navigator.back()) finish();
        });
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)));

        TextView toolbarTitle = text(getString(R.string.settings_title), UiTokens.TITLE_SP, palette.textPrimary);
        toolbarTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toolbar.addView(toolbarTitle, new LinearLayout.LayoutParams(0, -2, 1));

        TextView offlineBadge = text(getString(R.string.settings_offline), UiTokens.CAPTION_SP, palette.accent);
        offlineBadge.setGravity(Gravity.CENTER);
        offlineBadge.setMinimumHeight(dp(28));
        offlineBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        offlineBadge.setBackground(roundRect(palette.accentSoft, 14));
        toolbar.addView(offlineBadge, new LinearLayout.LayoutParams(-2, -2));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        FrameLayout pageHost = new FrameLayout(this);
        FrameLayout pageFrame = new FrameLayout(this);
        android.content.res.Configuration config = getResources().getConfiguration();
        int maxContent = config.screenWidthDp > 720 ? dp(720) : -1;
        pageFrame.addView(pageHost, new FrameLayout.LayoutParams(maxContent, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(pageFrame, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout home = buildHome();
        pageHost.addView(home, new FrameLayout.LayoutParams(-1, -2));
        navigator = new SettingsNavigator(this, scroll, pageHost, home, toolbarTitle, offlineBadge);
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

        setContentView(root);
        root.requestApplyInsets();
        navigator.restore(state);
        preferences.registerOnSharedPreferenceChangeListener(settingsListener);
    }

    private LinearLayout buildHome() {
        LinearLayout content = page();
        TextView appTitle = text(getString(R.string.settings_app_title), 27, palette.textPrimary);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        appTitle.setPadding(dp(2), dp(8), 0, dp(16));
        content.addView(appTitle, new LinearLayout.LayoutParams(-1, -2));
        setupPage.addHomeSetupCard(content);
        addGroupLabel(content, getString(R.string.settings_keyboard_group));
        LinearLayout inputGroup = addSettingsGroup(content);
        addCategoryRow(inputGroup, SettingsSections.APPEARANCE, true, false);
        addCategoryRow(inputGroup, SettingsSections.CANDIDATE, false, false);
        addCategoryRow(inputGroup, SettingsSections.INPUT, false, false);
        addCategoryRow(inputGroup, SettingsSections.LAYOUT, false, true);
        addGroupLabel(content, getString(R.string.settings_data_group));
        LinearLayout dataGroup = addSettingsGroup(content);
        addCategoryRow(dataGroup, SettingsSections.DICTIONARY, true, false);
        addCategoryRow(dataGroup, SettingsSections.PRIVACY, false, false);
        addCategoryRow(dataGroup, SettingsSections.CLIPBOARD, false, false);
        addCategoryRow(dataGroup, SettingsSections.QUICK_PHRASES, false, false);
        addCategoryRow(dataGroup, SettingsSections.ABOUT, false, true);
        TextView setup = text(getString(R.string.setting_choice_value,
                getString(R.string.settings_setup_entry)), UiTokens.CAPTION_SP, palette.textSecondary);
        setup.setPadding(dp(4), 0, dp(4), 0);
        setup.setMinimumHeight(dp(UiTokens.MIN_TOUCH));
        setup.setOnClickListener(v -> openSection(SettingsSections.SETUP));
        setupPage.setHomeSetupLink(setup);
        content.addView(setup, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout footer = new LinearLayout(this);
        footer.addView(text(getString(R.string.settings_local_storage), UiTokens.CAPTION_SP, palette.textSecondary),
                new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(text(BuildConfig.VERSION_NAME, UiTokens.CAPTION_SP, palette.textSecondary));
        footer.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(SettingsUi.PAGE_INSET), dp(12), dp(SettingsUi.PAGE_INSET), dp(32));
        page.setBackgroundColor(palette.background);
        return page;
    }

    void openSection(SettingsSections section) {
        navigator.open(section);
    }

    LinearLayout buildSection(SettingsSections section) {
        LinearLayout page = page();
        if (section == SettingsSections.SETUP) {
            page.addView(setupPage.createSetupDetails(), new LinearLayout.LayoutParams(-1, -2));
            return page;
        }
        TextView summary = text(getString(section.summary), UiTokens.BODY_SP, palette.textSecondary);
        summary.setPadding(dp(4), dp(4), dp(4), dp(8));
        page.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        switch (section) {
            case APPEARANCE:
                appearancePage = new SettingsAppearancePage(this);
                appearancePage.build(controls);
                break;
            case CANDIDATE:
                candidatePage = new SettingsCandidatePage(this);
                candidatePage.build(controls);
                break;
            case INPUT:
            case LAYOUT:
                inputLayoutPreview = new KeyboardSkinPreview(this);
                inputLayoutPreview.setShowsLayoutOptions(section == SettingsSections.LAYOUT);
                inputLayoutPreview.setTag("setting-subsection");
                LinearLayout.LayoutParams preview = new LinearLayout.LayoutParams(-1, dp(156));
                preview.bottomMargin = dp(8);
                controls.addView(inputLayoutPreview, preview);
                SettingsInputLayoutPage inputLayout = new SettingsInputLayoutPage(this);
                if (section == SettingsSections.INPUT) inputLayout.buildInputSection(controls);
                else inputLayout.buildLayoutSection(controls);
                break;
            case DICTIONARY: new SettingsDataPages(this).buildDictionarySection(controls); break;
            case PRIVACY: new SettingsDataPages(this).buildPrivacySection(controls); break;
            case CLIPBOARD: new SettingsDataPages(this).buildClipboardSection(controls); break;
            case QUICK_PHRASES:
                addSubheading(controls, getString(R.string.settings_phrases_list));
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setTag("setting-custom-content");
                list.setPadding(dp(SettingsUi.CARD_INSET), dp(SettingsUi.CARD_INSET),
                        dp(SettingsUi.CARD_INSET), dp(SettingsUi.CARD_INSET));
                controls.addView(list, new LinearLayout.LayoutParams(-1, -2));
                quickPhrasePage = new QuickPhraseSettingsPage(this, list, palette);
                quickPhrasePage.refresh();
                break;
            case ABOUT: new SettingsDataPages(this).buildAboutSection(controls); break;
            default: throw new IllegalArgumentException(section.name());
        }

        LinearLayout card = null;
        boolean previousRow = false;
        while (controls.getChildCount() > 0) {
            View child = controls.getChildAt(0);
            styleSecondaryControl(child);
            controls.removeViewAt(0);
            if ("setting-subsection".equals(child.getTag())) {
                page.addView(child);
                card = null;
                previousRow = false;
            } else {
                if (card == null) card = addSettingsGroup(page);
                boolean row = "setting-choice".equals(child.getTag());
                if (row && previousRow) addDivider(card, SettingsUi.CARD_INSET);
                card.addView(child);
                previousRow = row;
            }
        }
        String[] resetKeys = section.resetKeys();
        if (resetKeys.length > 0) addReset(page, section, resetKeys);
        flattenButtonShadows(page);
        return page;
    }

    private void addReset(LinearLayout page, SettingsSections section, String[] keys) {
        Button reset = new Button(this);
        reset.setText(R.string.settings_reset_section);
        SettingsUi.styleActionButton(reset, this, palette, false, false);
        reset.setOnClickListener(v -> RoundedCardPopup.showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.settings_reset_title)
                .setMessage(getString(R.string.settings_reset_message, getString(section.title)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset_confirm, (dialog, which) -> {
                    SharedPreferences.Editor editor = preferences.edit();
                    for (String key : keys) editor.remove(key);
                    if (section == SettingsSections.LAYOUT) {
                        for (int i = 0; i < ToolbarConfig.DEFAULTS.length; i++)
                            editor.remove("toolbar_item_" + i);
                    }
                    editor.apply();
                    scheduleRecreate();
                }).create()));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        page.addView(reset, params);
    }

    void previewNumber(String key, int value) {
        if (appearancePage != null) appearancePage.refreshPreview(key, value);
        if (candidatePage != null) candidatePage.refreshPreview(key, value);
    }

    void scheduleRecreate() {
        if (recreatePending) return;
        recreatePending = true;
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) recreate();
        });
    }

    void closeSection() {
        if (quickPhrasePage != null) quickPhrasePage.close();
        quickPhrasePage = null;
        appearancePage = null;
        candidatePage = null;
        inputLayoutPreview = null;
        setupPage.releaseDetails();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (navigator != null) navigator.save(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (quickPhrasePage != null) quickPhrasePage.refresh();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && setupPage != null) setupPage.refresh();
    }

    private void addGroupLabel(LinearLayout parent, String title) {
        TextView label = text(title, 14, palette.textSecondary);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setPadding(dp(4), dp(6), dp(4), dp(8));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    void addSubheading(LinearLayout parent, String title) {
        TextView label = text(title, 14, palette.textSecondary);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setTag("setting-subsection");
        label.setPadding(dp(4), dp(16), dp(4), dp(8));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout addSettingsGroup(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(roundRect(palette.surface, SettingsUi.CARD_RADIUS));
        group.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(group, params);
        return group;
    }

    private void addDivider(LinearLayout group, int inset) {
        View divider = new View(this);
        divider.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        params.setMargins(dp(inset), 0, dp(SettingsUi.CARD_INSET), 0);
        group.addView(divider, params);
    }

    void bindPalette(View view, Runnable binding) {
        binding.run();
    }

    private void addCategoryRow(LinearLayout parent, SettingsSections section, boolean first, boolean last) {
        String title = getString(section.title);
        String summary = getString(section.summary);
        if (parent.getChildCount() > 0) {
            addDivider(parent, 68);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(SettingsUi.CARD_INSET), dp(10), dp(SettingsUi.CARD_INSET), dp(10));
        row.setMinimumHeight(dp(72));
        row.setBackground(pressableCornerRect(palette.surface, palette.accentSoft,
                SettingsUi.CARD_RADIUS, first, last));
        FrameLayout iconTile = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(section.icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iconTile.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        bindPalette(iconTile, () -> { iconTile.setBackground(roundRect(palette.accentSoft, 12)); icon.setColorFilter(palette.accent); });
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.rightMargin = dp(12);
        row.addView(iconTile, iconParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 16, palette.textPrimary);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = text(summary, 13, palette.textSecondary);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(2);
        labels.addView(detail, detailParams);
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
        row.setOnClickListener(v -> openSection(section));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void styleSecondaryControl(View view) {
        if ("setting-custom-content".equals(view.getTag())) return;
        if (view instanceof Switch) {
            SettingsUi.styleCapsuleSwitch((Switch) view, this, palette);
        } else if (view instanceof Button) {
            Button button = (Button) view;
            Object action = button.getTag();
            boolean danger = "action-danger".equals(action);
            boolean secondary = "action-secondary".equals(action);
            boolean primary = "action-primary".equals(action);
            bindPalette(button, () -> {
                if (danger || secondary || primary) {
                    SettingsUi.styleActionButton(button, this, palette, primary, danger);
                } else {
                    button.setAllCaps(false);
                    button.setTextSize(15);
                    button.setTextColor(palette.accent);
                    button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    button.setPadding(0, 0, 0, 0);
                    button.setMinHeight(dp(48));
                    button.setBackground(pressableRoundRect(Color.TRANSPARENT,
                            palette.accentSoft, SettingsUi.CONTROL_RADIUS));
                }
            });
            if (button.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
                params.height = -2;
                params.leftMargin = dp(SettingsUi.CARD_INSET);
                params.rightMargin = dp(SettingsUi.CARD_INSET);
                params.topMargin = dp(danger ? 16 : 4);
                params.bottomMargin = dp(secondary ? 16 : 4);
                button.setLayoutParams(params);
            }
        } else if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setTextColor(palette.textPrimary);
            field.setHintTextColor(palette.textSecondary);
            field.setPadding(dp(SettingsUi.CARD_INSET), dp(8),
                    dp(SettingsUi.CARD_INSET), dp(8));
            bindPalette(field, () -> field.setBackgroundTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{palette.accent, palette.divider})));
        } else if (view instanceof TextView) {
            TextView label = (TextView) view;
            Object parentTag = label.getParent() instanceof View ? ((View) label.getParent()).getTag() : null;
            if (!"setting-choice".equals(parentTag)
                    && label.getPaddingLeft() == 0 && label.getPaddingRight() == 0) {
                label.setPadding(dp(SettingsUi.CARD_INSET), Math.max(dp(8), label.getPaddingTop()),
                        dp(SettingsUi.CARD_INSET), Math.max(dp(8), label.getPaddingBottom()));
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

    void showChoicePopup(View anchor, RoundedCardPopup menu) {
        if (choicePopup != null) choicePopup.dismiss();
        choicePopup = menu;
        menu.onDismiss(() -> { if (choicePopup == menu) choicePopup = null; });
        menu.show(anchor);
    }

    boolean dismissChoicePopup() {
        if (choicePopup == null || !choicePopup.isShowing()) return false;
        choicePopup.dismiss();
        return true;
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (navigator == null || !navigator.back()) super.onBackPressed();
    }

    @Override protected void onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (navigator != null) navigator.close();
        dismissChoicePopup();
        closeSection();
        super.onDestroy();
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    android.graphics.drawable.StateListDrawable pressableRoundRect(
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

    int darken(int color) {
        return Color.rgb((int) (Color.red(color) * 0.82f),
                (int) (Color.green(color) * 0.82f),
                (int) (Color.blue(color) * 0.82f));
    }

    GradientDrawable roundRect(int color, int radiusDp) {
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
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.background);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(palette.divider);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = 0;
        if (!UiTokens.isDark(this)) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
