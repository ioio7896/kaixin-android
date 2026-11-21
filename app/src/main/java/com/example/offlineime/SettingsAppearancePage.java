package com.example.offlineime;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Builds the "外观反馈" settings section and manages live skin/candidate previews. */
final class SettingsAppearancePage {
    private final SettingsActivity host;
    private TextView skinChoice;
    private KeyboardSkinPreview skinPreview;
    private KeyboardSkinPreview sizingPreview;

    SettingsAppearancePage(SettingsActivity host) {
        this.host = host;
    }

    void build(LinearLayout content) {
        SharedPreferences preferences = host.preferences();
        addSkinControls(content);
        host.addSubheading(content, host.getString(R.string.settings_appearance_keyboard_style));
        host.rows.addSegmentedChoice(content, host.getString(R.string.settings_pref_appearance_mode), "appearance_mode",
                host.getResources().getStringArray(R.array.settings_pref_appearance_mode_options),
                new String[]{"system", "light", "dark"},
                preferences.getBoolean("dark", false) ? "dark" : "system");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_high_contrast), "high_contrast", false);
        host.rows.addChoice(content, host.getString(R.string.settings_pref_one_hand_side), "one_hand_side",
                host.getResources().getStringArray(R.array.settings_pref_one_hand_side_options),
                new String[]{"off", "left", "right"}, KeyboardPlacement.side(preferences));
        host.rows.addNumber(content, host.getString(R.string.settings_pref_one_hand_width), "one_hand_width", 70, 90, 80, 10, "%");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_split_keyboard), "split_keyboard", false);
        host.rows.addNumber(content, host.getString(R.string.settings_pref_split_gap), "split_gap", 1, 4, 2, 1, " 级");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_floating), "floating", false);
        host.rows.addNumber(content, host.getString(R.string.settings_pref_floating_width), "floating_width", 280,
                Math.max(280, host.getResources().getConfiguration().screenWidthDp), 340, 10, " dp");
        host.rows.addNumber(content, host.getString(R.string.settings_pref_floating_height), "floating_height", 200, 560, 300, 10, " dp");
        host.addSubheading(content, host.getString(R.string.settings_appearance_sizing));
        addSizingControls(content);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_key_preview), "key_preview", true);
        host.addSubheading(content, host.getString(R.string.settings_appearance_feedback));
        host.rows.addChoice(content, host.getString(R.string.settings_pref_haptic_strength), "haptic_strength",
                host.getResources().getStringArray(R.array.settings_pref_haptic_strength_options),
                new String[]{"off", "light", "standard", "strong"}, "standard");
        host.rows.addChoice(content, host.getString(R.string.settings_pref_sound_volume), "sound_volume",
                host.getResources().getStringArray(R.array.settings_pref_sound_volume_options),
                new String[]{"off", "low", "medium", "high"}, "off");
    }

    void refreshPreview(String key, int value) {
        if (skinPreview == null) return;
        SharedPreferences preferences = host.preferences();
        KeyboardTheme.Skin selected = KeyboardTheme.skin(preferences.getString("theme", "system"));
        skinChoice.setText(host.getString(R.string.setting_choice_value, selected.label));
        skinChoice.setContentDescription("键盘皮肤，当前" + selected.label + "，点击选择");
        if (skinChoice.getParent() instanceof View) {
            ((View) skinChoice.getParent()).setContentDescription(skinChoice.getContentDescription());
        }
        skinPreview.refresh(key, value);
        if (sizingPreview != null) sizingPreview.refresh(key, value);
    }

    private void addSkinControls(LinearLayout parent) {
        SharedPreferences preferences = host.preferences();
        UiTokens.Palette palette = host.palette();
        LinearLayout skinRow = new LinearLayout(host);
        skinRow.setGravity(Gravity.CENTER_VERTICAL);
        skinRow.setPadding(host.dp(SettingsUi.CARD_INSET), 0, host.dp(SettingsUi.CARD_INSET), 0);
        skinRow.setMinimumHeight(host.dp(SettingsUi.ROW_MIN_HEIGHT));
        skinRow.setTag("setting-choice");
        TextView label = host.text(host.getString(R.string.settings_appearance_skin), 15, palette.textPrimary);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        skinRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        skinChoice = host.text("", 16, palette.textPrimary);
        skinChoice.setTextSize(14);
        skinChoice.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        skinChoice.setMaxWidth(host.dp(160));
        skinChoice.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
        host.bindPalette(skinChoice, () -> skinChoice.setTextColor(host.palette().accent));
        skinChoice.setOnClickListener(v -> showSkinPicker());
        skinChoice.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        skinRow.addView(skinChoice, new LinearLayout.LayoutParams(-2, -2));
        skinRow.setOnClickListener(v -> showSkinPicker());
        skinRow.setFocusable(true);
        host.bindPalette(skinRow, () -> skinRow.setBackground(host.pressableRoundRect(
                Color.TRANSPARENT, host.palette().accentSoft, SettingsUi.CONTROL_RADIUS)));
        parent.addView(skinRow, new LinearLayout.LayoutParams(-1, -2));
        skinPreview = new KeyboardSkinPreview(host, KeyboardTheme.from(host, preferences.getBoolean("dark", false)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, host.dp(132));
        previewParams.setMargins(host.dp(SettingsUi.CARD_INSET), 0, host.dp(SettingsUi.CARD_INSET), 0);
        parent.addView(skinPreview, previewParams);
        TextView note = host.text(host.getString(R.string.settings_appearance_skin_summary), 13, palette.textSecondary);
        note.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(10),
                host.dp(SettingsUi.CARD_INSET), host.dp(14));
        parent.addView(note, new LinearLayout.LayoutParams(-1, -2));
        refreshPreview(null, 0);
    }

    private void showSkinPicker() {
        SharedPreferences preferences = host.preferences();
        UiTokens.Palette palette = host.palette();
        RoundedCardPopup menu = RoundedCardPopup.settings(host, host.getString(R.string.settings_appearance_skin)).width(360);
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        String selected = KeyboardTheme.skin(preferences.getString("theme", "system")).id;
        boolean dark = KeyboardTheme.resolveDark(host);
        for (int start = 0; start < KeyboardTheme.SKINS.size(); start += 2) {
            LinearLayout row = new LinearLayout(host);
            for (int column = 0; column < 2; column++) {
                int index = start + column;
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
                tileParams.setMargins(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
                if (index >= KeyboardTheme.SKINS.size()) {
                    row.addView(new View(host), tileParams);
                    continue;
                }
                KeyboardTheme.Skin skin = KeyboardTheme.SKINS.get(index);
                boolean checked = skin.id.equals(selected);
                LinearLayout tile = new LinearLayout(host);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(2));
                GradientDrawable border = host.roundRect(palette.surfaceMuted, UiTokens.RADIUS_CONTROL);
                border.setStroke(host.dp(checked ? 2 : 1), checked ? palette.accent : palette.divider);
                tile.setBackground(border);
                KeyboardSkinPreview preview = new KeyboardSkinPreview(host,
                        KeyboardTheme.forSkin(host, skin.id, dark, false));
                preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.addView(preview, new LinearLayout.LayoutParams(-1, host.dp(86)));
                TextView name = host.text(skin.label + (checked ? "  ✓" : ""), 13,
                        checked ? palette.accent : palette.textPrimary);
                name.setGravity(Gravity.CENTER);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                name.setMinimumHeight(host.dp(34));
                tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
                tile.setSelected(checked);
                tile.setFocusable(true);
                tile.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
                tile.setContentDescription(skin.label + (checked ? "，已选择" : "，点击使用"));
                tile.setOnClickListener(v -> {
                    preferences.edit().putString("theme", skin.id).apply();
                    refreshPreview(null, 0);
                    menu.dismiss();
                });
                row.addView(tile, tileParams);
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        menu.content(grid);
        host.showChoicePopup(skinChoice, menu);
    }

    private void addSizingControls(LinearLayout parent) {
        sizingPreview = new KeyboardSkinPreview(host);
        sizingPreview.setPreviewsHeight(true);
        LinearLayout.LayoutParams preview = new LinearLayout.LayoutParams(-1, -2);
        preview.setMargins(host.dp(SettingsUi.CARD_INSET), host.dp(8), host.dp(SettingsUi.CARD_INSET), host.dp(8));
        parent.addView(sizingPreview, preview);
        boolean tablet = host.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        host.rows.addNumber(parent, host.getString(R.string.settings_pref_key_height_landscape), "key_height_landscape", 180, 360, tablet ? 300 : 220, 10, " dp");
        host.rows.addNumber(parent, host.getString(R.string.settings_pref_key_height), "key_height", KeyboardSizing.MIN_HEIGHT,
                KeyboardSizing.MAX_HEIGHT, tablet ? 320 : KeyboardSizing.DEFAULT_HEIGHT, 10, " dp");
        host.rows.addExpandableHelp(parent, host.getString(R.string.settings_help_keyboard_height),
                host.getString(R.string.settings_help_keyboard_height_detail));
        host.rows.addNumber(parent, host.getString(R.string.settings_pref_key_font), "key_font", 14, 24, 20, 2, " sp");
        host.rows.addNumber(parent, host.getString(R.string.settings_pref_key_radius), "key_radius", 0, 16, 12, 2, " dp");
        host.rows.addNumber(parent, host.getString(R.string.settings_pref_key_gap), "key_gap", 0, 3, 2, 1, " dp");
    }
}
