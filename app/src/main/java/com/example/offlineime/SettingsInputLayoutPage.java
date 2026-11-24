package com.example.offlineime;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Locale;

/** Builds the "输入体验" and "键盘布局" settings sections. */
final class SettingsInputLayoutPage {
    private final SettingsActivity host;

    SettingsInputLayoutPage(SettingsActivity host) {
        this.host = host;
    }

    void buildInputSection(LinearLayout content) {
        host.addSubheading(content, host.getString(R.string.settings_input_language_scheme));
        host.rows.addChoice(content, host.getString(R.string.settings_pref_output_script), "output_script",
                host.getResources().getStringArray(R.array.settings_pref_output_script_options), new String[]{"simplified", "traditional"});
        host.rows.addSegmentedChoice(content, host.getString(R.string.settings_pref_default_layout), "default_layout",
                host.getResources().getStringArray(R.array.settings_pref_default_layout_options), new String[]{"qwerty", "nine"}, "qwerty");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_mixed_english), "mixed_english", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_paired_punctuation), "paired_punctuation", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_gesture_hints), "gesture_hints", true);
        host.rows.addSegmentedChoice(content, host.getString(R.string.settings_pref_pinyin_scheme), "pinyin_scheme",
                host.getResources().getStringArray(R.array.settings_pref_pinyin_scheme_options),
                new String[]{"full", "xiaohe"}, "full");
        host.addSubheading(content, host.getString(R.string.settings_input_nine_key));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_nine_key_swipe_numbers), "nine_key_swipe_numbers", true);
        host.rows.addChoice(content, host.getString(R.string.settings_pref_nine_key_labels), "nine_key_labels",
                host.getResources().getStringArray(R.array.settings_pref_nine_key_labels_options),
                new String[]{"letters_numbers", "letters"}, "letters_numbers");
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_nine_swipe),
                host.getString(R.string.settings_help_nine_swipe_detail));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_nine_short_words), "nine_short_words", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_nine_word_completion), "nine_word_completion", false);
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_nine_completion),
                host.getString(R.string.settings_help_nine_completion_detail));
        host.addSubheading(content, host.getString(R.string.settings_input_pinyin));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_full_pinyin), "full_pinyin", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_short_pinyin), "short_pinyin", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_mixed_pinyin), "mixed_pinyin", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_double_initials), "double_initials", true);
        host.addSubheading(content, host.getString(R.string.settings_input_prediction));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_next_word_prediction), "next_word_prediction", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_emoji_candidates), "emoji_candidates", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_emoji_legacy_markers), "emoji_legacy_markers", false);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_basic_context), "basic_context", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_sentence_prediction), "sentence_prediction", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_nine_sentence_prediction), "nine_sentence_prediction", false);
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_sentence_prediction),
                host.getString(R.string.settings_help_sentence_prediction_detail));
        host.addSubheading(content, host.getString(R.string.settings_input_correction));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_spelling_correction), "spelling_correction", false);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_touch_correction), "touch_correction", true);
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_spelling_correction),
                host.getString(R.string.settings_help_spelling_correction_detail));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_fuzzy_pinyin), "fuzzy_pinyin", false);
        for (String[] pair : new String[][]{{"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"n", "l"},
                {"h", "f"}, {"an", "ang"}, {"en", "eng"}, {"in", "ing"}, {"ian", "iang"}}) {
            host.rows.addSwitch(content, "模糊音 " + pair[0] + " / " + pair[1],
                    "fuzzy_" + pair[0] + "_" + pair[1], false);
        }
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_fuzzy_pinyin),
                host.getString(R.string.settings_help_fuzzy_pinyin_detail));
        host.addSubheading(content, host.getString(R.string.settings_input_handwriting));
        host.rows.addChoice(content, host.getString(R.string.settings_pref_handwriting_delay), "handwriting_delay",
                host.getResources().getStringArray(R.array.settings_pref_handwriting_delay_options),
                new String[]{"250", "400", "650", "900"}, "400");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_handwriting_continuous), "handwriting_continuous", false);
        host.rows.addChoice(content, host.getString(R.string.settings_pref_handwriting_line_delay), "handwriting_line_delay",
                host.getResources().getStringArray(R.array.settings_pref_handwriting_line_delay_options),
                new String[]{"650", "900", "1300", "1800"}, "900");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_handwriting_stylus_only), "handwriting_stylus_only", false);
        host.addSubheading(content, host.getString(R.string.settings_input_voice));
        host.rows.addChoice(content, host.getString(R.string.settings_pref_voice_pause_sensitivity),
                VoiceSettings.PREF_PAUSE_SENSITIVITY,
                host.getResources().getStringArray(R.array.settings_pref_voice_pause_sensitivity_options),
                new String[]{"fast", "standard", "slow"}, "standard");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_voice_auto_punctuation),
                VoiceSettings.PREF_AUTO_PUNCTUATION, true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_voice_spoken_punctuation),
                VoiceSettings.PREF_SPOKEN_PUNCTUATION, false);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_voice_itn_numbers),
                VoiceSettings.PREF_ITN_NUMBERS, true);
        host.rows.addChoice(content, host.getString(R.string.settings_pref_voice_commit_mode),
                VoiceSettings.PREF_COMMIT_MODE,
                host.getResources().getStringArray(R.array.settings_pref_voice_commit_mode_options),
                new String[]{"confirm", "segment"}, "confirm");
        host.rows.addChoice(content, host.getString(R.string.settings_pref_voice_silence_timeout),
                VoiceSettings.PREF_SILENCE_TIMEOUT,
                host.getResources().getStringArray(R.array.settings_pref_voice_silence_timeout_options),
                new String[]{"0", "6", "10"}, "10");
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_voice_custom_hotwords),
                VoiceSettings.PREF_CUSTOM_HOTWORDS, true);
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_voice_input),
                host.getString(R.string.settings_help_voice_input_detail));
        host.addSubheading(content, host.getString(R.string.settings_input_hardware_keyboard));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_hw_keyboard_enabled), "hw_keyboard_enabled", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_hw_keyboard_hide_soft), "hw_keyboard_hide_soft", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_auto_commit), "auto_commit", false);
        host.rows.addChoice(content, host.getString(R.string.settings_pref_space_behavior), "space_behavior",
                host.getResources().getStringArray(R.array.settings_pref_space_behavior_options), new String[]{"select", "space"});
        host.rows.addChoice(content, host.getString(R.string.settings_pref_candidate_count), "candidate_count",
                host.getResources().getStringArray(R.array.settings_pref_candidate_count_options), new String[]{"6", "9", "12", "15"});
    }

    void buildLayoutSection(LinearLayout content) {
        host.addSubheading(content, host.getString(R.string.settings_layout_display));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_toolbar), "toolbar", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_show_mode), "show_mode", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_show_composing), "show_composing", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_qwerty_number_row), "qwerty_number_row", false);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_qwerty_bottom_punctuation), "qwerty_bottom_punctuation", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_qwerty_swipe_symbols), "qwerty_swipe_symbols", true);
        host.addSubheading(content, host.getString(R.string.settings_layout_toolbar));
        addToolbarCustomization(content);
    }

    /** 工具栏项目可分别选择功能，模式切换统一放在“键盘布局”按钮中。 */
    private void addToolbarCustomization(LinearLayout parent) {
        SharedPreferences preferences = host.preferences();
        UiTokens.Palette palette = host.palette();
        ToolbarConfig.migrateFormerDefaults(preferences);
        TextView hint = host.text("工具栏入口（最多 7 个，从左到右）", 15, palette.textSecondary);
        hint.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(10),
                host.dp(SettingsUi.CARD_INSET), host.dp(2));
        parent.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        String[] labels = ToolbarConfig.LABELS;
        String[] values = ToolbarConfig.VALUES;
        String[] defaults = ToolbarConfig.DEFAULTS;
        // Keep the first occurrence of a legacy duplicate and clear its later slots.
        HashSet<String> used = new HashSet<>();
        SharedPreferences.Editor cleanup = preferences.edit();
        for (int i = 0; i < defaults.length; i++) {
            String key = "toolbar_item_" + i;
            String stored = preferences.getString(key, defaults[i]);
            String action = ToolbarConfig.normalize(stored, i);
            if (!action.isEmpty() && !used.add(action)) action = "";
            if (!action.equals(stored)) cleanup.putString(key, action);
        }
        cleanup.apply();
        for (int i = 0; i < defaults.length; i++) {
            final int slot = i;
            TextView choiceButton = host.rows.createChoiceButton("工具栏第" + (i + 1) + "项", "toolbar_item_" + i,
                    defaults[i], labels, values, choice -> {
                if (!toolbarChoiceAvailable(slot, choice)) {
                    Toast.makeText(host, "该功能已添加，请先将原位置设为不显示", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }, choice -> toolbarChoiceAvailable(slot, choice));
            LinearLayout line = new LinearLayout(host);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(host.dp(SettingsUi.CARD_INSET), 0, host.dp(SettingsUi.CARD_INSET), 0);
            line.setTag("setting-choice");
            line.setBackgroundColor(Color.TRANSPARENT);
            TextView slotLabel = host.text(String.format(Locale.ROOT, "%02d", i + 1), 14, palette.textSecondary);
            slotLabel.setGravity(Gravity.CENTER);
            line.addView(slotLabel, new LinearLayout.LayoutParams(host.dp(42), -2));
            choiceButton.setSingleLine(false);
            choiceButton.setFocusable(false);
            line.setMinimumHeight(host.dp(54));
            line.setFocusable(true);
            line.addView(choiceButton, new LinearLayout.LayoutParams(0, -2, 1));
            line.setOnClickListener(v -> choiceButton.performClick());
            line.setContentDescription("工具栏第" + (i + 1) + "项，" + choiceButton.getText());
            choiceButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, host.dp(2), 0, host.dp(2));
            parent.addView(line, params);
        }
        TextView note = host.text("每项可选择功能或“不显示”，相同功能只保留一个入口，最右侧固定为收起键盘。通过“键盘布局”切换模式；修改后重新唤起键盘生效。",
                13, palette.textSecondary);
        note.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(4),
                host.dp(SettingsUi.CARD_INSET), host.dp(8));
        parent.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private boolean toolbarChoiceAvailable(int slot, String choice) {
        if (choice.isEmpty()) return true;
        SharedPreferences preferences = host.preferences();
        String[] defaults = ToolbarConfig.DEFAULTS;
        for (int other = 0; other < defaults.length; other++) {
            if (other != slot && choice.equals(ToolbarConfig.normalize(
                    preferences.getString("toolbar_item_" + other, defaults[other]), other))) {
                return false;
            }
        }
        return true;
    }
}
