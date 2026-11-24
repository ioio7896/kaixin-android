package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Centralizes offline voice dictation preferences and in-memory hotword preparation. */
final class VoiceSettings {
    static final String PREF_PAUSE_SENSITIVITY = "voice_pause_sensitivity";
    static final String PREF_AUTO_PUNCTUATION = "voice_auto_punctuation";
    static final String PREF_SPOKEN_PUNCTUATION = "voice_spoken_punctuation";
    static final String PREF_ITN_NUMBERS = "voice_itn_numbers";
    static final String PREF_COMMIT_MODE = "voice_commit_mode";
    static final String PREF_SILENCE_TIMEOUT = "voice_silence_timeout";
    static final String PREF_CUSTOM_HOTWORDS = "voice_custom_hotwords";

    static final String[] KEYS = {
            PREF_PAUSE_SENSITIVITY,
            PREF_AUTO_PUNCTUATION,
            PREF_SPOKEN_PUNCTUATION,
            PREF_ITN_NUMBERS,
            PREF_COMMIT_MODE,
            PREF_SILENCE_TIMEOUT,
            PREF_CUSTOM_HOTWORDS
    };

    private static final int MAX_HOTWORDS = 200;

    private VoiceSettings() {}

    static float pauseSeconds(SharedPreferences prefs) {
        String mode = prefs.getString(PREF_PAUSE_SENSITIVITY, "standard");
        if ("fast".equals(mode)) return 0.8f;
        if ("slow".equals(mode)) return 2.2f;
        return 1.4f;
    }

    static boolean autoPunctuation(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_AUTO_PUNCTUATION, true);
    }

    static boolean spokenPunctuation(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SPOKEN_PUNCTUATION, false);
    }

    static boolean smartNumbers(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ITN_NUMBERS, true);
    }

    static boolean segmentCommitMode(SharedPreferences prefs) {
        return "segment".equals(prefs.getString(PREF_COMMIT_MODE, "confirm"));
    }

    static int silenceTimeoutSeconds(SharedPreferences prefs) {
        String raw = prefs.getString(PREF_SILENCE_TIMEOUT, "10");
        if ("0".equals(raw)) return 0;
        if ("6".equals(raw)) return 6;
        return 10;
    }

    static boolean useCustomHotwords(SharedPreferences prefs) {
        return !prefs.getBoolean("privacy_mode", false)
                && prefs.getBoolean(PREF_CUSTOM_HOTWORDS, true);
    }

    static VoiceInputController.Config loadConfig(Context context, SharedPreferences prefs,
                                                  boolean forceConfirmMode) {
        String hotwords = useCustomHotwords(prefs) ? buildHotwordsText(context) : "";
        return new VoiceInputController.Config(
                pauseSeconds(prefs),
                autoPunctuation(prefs),
                spokenPunctuation(prefs),
                smartNumbers(prefs),
                silenceTimeoutSeconds(prefs),
                !forceConfirmMode && segmentCommitMode(prefs),
                hotwords,
                1.5f
        );
    }

    /** Formats custom user words as in-memory sherpa-ncnn hotword lines; never written to disk. */
    static String buildHotwordsText(Context context) {
        Map<String, CustomWordStore.Word> map;
        try {
            map = CustomWordStore.load(context);
        } catch (RuntimeException ignored) {
            return "";
        }
        if (map == null || map.isEmpty()) return "";
        List<CustomWordStore.Word> words = new ArrayList<>(map.values());
        Collections.sort(words, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return Long.compare(b.createdAt, a.createdAt);
        });
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (CustomWordStore.Word entry : words) {
            if (count >= MAX_HOTWORDS) break;
            String formatted = formatHotwordLine(entry.text, entry.pinned ? 2.0f : 1.5f);
            if (formatted == null) continue;
            if (out.length() > 0) out.append('\n');
            out.append(formatted);
            count++;
        }
        return out.toString();
    }

    private static String formatHotwordLine(String word, float score) {
        if (word == null) return null;
        String trimmed = word.trim();
        int len = trimmed.length();
        if (len < 2 || len > 12) return null;
        StringBuilder line = new StringBuilder(len * 2 + 8);
        for (int i = 0; i < len; i++) {
            char ch = trimmed.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                if (line.length() > 0) line.append(' ');
                line.append(ch);
            } else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                if (line.length() > 0) line.append(' ');
                line.append(Character.toUpperCase(ch));
            } else {
                return null;
            }
        }
        if (line.length() == 0) return null;
        line.append(String.format(Locale.US, " :%.1f", score));
        return line.toString();
    }
}
