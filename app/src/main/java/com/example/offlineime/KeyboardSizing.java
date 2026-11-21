package com.example.offlineime;

import android.content.SharedPreferences;

/** The settings slider and IME must agree on the same default and range. */
final class KeyboardSizing {
    static final int DEFAULT_HEIGHT = 260;
    static final int MIN_HEIGHT = 240;
    static final int MAX_HEIGHT = 560;
    static int height(SharedPreferences preferences) {
        int value = SettingsRepository.integer(preferences, "key_height", DEFAULT_HEIGHT);
        return Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, value));
    }
}
