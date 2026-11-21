package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Shared settings storage and compatibility readers for existing installations. */
final class SettingsRepository {
    private SettingsRepository() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int integer(SharedPreferences preferences, String key, int fallback) {
        try {
            return preferences.getInt(key, fallback);
        } catch (ClassCastException legacy) {
            return integer(preferences.getAll(), key, fallback);
        }
    }

    static int integer(Map<String, ?> values, String key, int fallback) {
        Object stored = values.get(key);
        if (stored instanceof Integer) return (Integer) stored;
        if (stored instanceof String) {
            try { return Integer.parseInt((String) stored); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    static boolean bool(Map<String, ?> values, String key, boolean fallback) {
        Object stored = values.get(key);
        return stored instanceof Boolean ? (Boolean) stored : fallback;
    }

    static String string(Map<String, ?> values, String key, String fallback) {
        Object stored = values.get(key);
        return stored instanceof String ? (String) stored : fallback;
    }
}
