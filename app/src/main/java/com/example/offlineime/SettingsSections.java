package com.example.offlineime;

import android.content.Context;

/** Section names are stable saved-state identifiers. Labels are user-facing resources. */
enum SettingsSections {
    SETUP(R.string.settings_setup, 0, 0),
    APPEARANCE(R.string.settings_appearance, R.string.settings_appearance_summary, R.drawable.ic_settings_palette),
    CANDIDATE(R.string.settings_candidate, R.string.settings_candidate_summary, R.drawable.ic_settings_text),
    INPUT(R.string.settings_input, R.string.settings_input_summary, R.drawable.ic_settings_sliders),
    LAYOUT(R.string.settings_layout, R.string.settings_layout_summary, R.drawable.ic_settings_sliders),
    DICTIONARY(R.string.settings_dictionary, R.string.settings_dictionary_summary, R.drawable.ic_settings_dictionary),
    PRIVACY(R.string.settings_privacy, R.string.settings_privacy_summary, R.drawable.ic_settings_shield),
    CLIPBOARD(R.string.settings_clipboard, R.string.settings_clipboard_summary, R.drawable.ic_settings_dictionary),
    QUICK_PHRASES(R.string.settings_phrases, R.string.settings_phrases_summary, R.drawable.ic_settings_text),
    ABOUT(R.string.settings_about, R.string.settings_about_summary, R.drawable.ic_settings_info);

    final int title;
    final int summary;
    final int icon;

    SettingsSections(int title, int summary, int icon) {
        this.title = title;
        this.summary = summary;
        this.icon = icon;
    }

    static SettingsSections restore(Context context, String id, String legacyTitle) {
        for (SettingsSections section : values()) {
            if (section.name().equals(id)
                    || (id == null && context.getString(section.title).equals(legacyTitle))) return section;
        }
        return null;
    }

    /** “外观反馈”恢复默认时清除的键。 */
    static final String[] APPEARANCE_KEYS = {
            "theme", "dark", "appearance_mode", "high_contrast", "one_hand", "one_hand_side", "one_hand_width",
            "split_keyboard", "split_gap", "floating", "floating_width", "floating_height",
            "tablet_preset_portrait", "tablet_preset_landscape",
            "floating_width_portrait", "floating_width_landscape", "floating_height_portrait", "floating_height_landscape",
            "floating_x_portrait", "floating_x_landscape", "floating_y_portrait", "floating_y_landscape",
            "key_height_landscape", "key_height", "keyboard_height", "key_font", "key_radius", "key_gap", "key_preview",
            "haptic_strength", "sound_volume"
    };

    /** “输入体验”恢复默认时清除的键。 */
    static final String[] INPUT_KEYS = {
            "output_script", "default_layout", "mixed_english", "paired_punctuation", "gesture_hints",
            "pinyin_scheme", "nine_key_swipe_numbers", "nine_key_labels", "nine_short_words",
            "nine_word_completion", "full_pinyin", "short_pinyin", "mixed_pinyin", "double_initials",
            "next_word_prediction", "emoji_candidates", "emoji_legacy_markers", "basic_context",
            "sentence_prediction", "nine_sentence_prediction", "spelling_correction", "touch_correction",
            "fuzzy_pinyin", "fuzzy_zh_z", "fuzzy_ch_c", "fuzzy_sh_s", "fuzzy_n_l", "fuzzy_h_f",
            "fuzzy_an_ang", "fuzzy_en_eng", "fuzzy_in_ing", "fuzzy_ian_iang", "handwriting_delay",
            "handwriting_continuous", "handwriting_line_delay", "auto_commit", "space_behavior",
            "candidate_count"
    };

    /** “键盘布局”显示选项恢复默认时清除的键（工具栏入口另有独立的恢复按钮）。 */
    static final String[] LAYOUT_KEYS = {"toolbar", "show_mode", "show_composing"};

    String[] resetKeys() {
        switch (this) {
            case APPEARANCE: return APPEARANCE_KEYS;
            case CANDIDATE: return CandidateTextStyle.KEYS;
            case INPUT: return INPUT_KEYS;
            case LAYOUT: return LAYOUT_KEYS;
            case DICTIONARY: return new String[]{"learn_words", "learn_long_press", "blocked_words"};
            case CLIPBOARD:
                return new String[]{"clipboard_enabled", "clipboard_capture_while_typing",
                        "clipboard_limit", "clipboard_retention_days"};
            case PRIVACY: return new String[]{"privacy_mode", "sensitive_filter", "performance_logging"};
            default: return new String[0];
        }
    }
}
