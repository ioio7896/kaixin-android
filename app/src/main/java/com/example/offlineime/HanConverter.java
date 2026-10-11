package com.example.offlineime;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Longest-match Simplified-to-Traditional conversion backed by bundled OpenCC data. */
final class HanConverter {
    private static final String TAG = "OfflineImeHan";
    private static volatile HanConverter instance;

    private final Map<String, String> conversions = new HashMap<>();
    private final Map<String, String> simplifications = new HashMap<>();
    private int longestKey = 1;

    static HanConverter get(Context context) {
        HanConverter ready = instance;
        if (ready != null) return ready;
        synchronized (HanConverter.class) {
            if (instance == null) instance = new HanConverter(context.getApplicationContext());
            return instance;
        }
    }

    private HanConverter(Context context) {
        load(context, "opencc/STPhrases.txt");
        load(context, "opencc/STCharacters.txt");
        load(context, "opencc/TSPhrases.txt", simplifications);
        load(context, "opencc/TSCharacters.txt", simplifications);
    }

    /** Testable constructor also used to verify longest-match behavior without Android I/O. */
    HanConverter(Map<String, String> mappings) {
        if (mappings == null) return;
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()
                    || entry.getValue() == null || entry.getValue().isEmpty()) continue;
            conversions.put(entry.getKey(), entry.getValue());
            longestKey = Math.max(longestKey, entry.getKey().length());
        }
    }

    String toTraditional(String text) {
        return convert(text, conversions);
    }

    String toSimplified(String text) {
        return convert(text, simplifications);
    }

    private String convert(String text, Map<String, String> mappings) {
        if (text == null || text.isEmpty() || mappings.isEmpty()) return text;
        StringBuilder output = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            String replacement = null;
            int consumed = 0;
            int max = Math.min(text.length(), offset + longestKey);
            for (int end = max; end > offset; end--) {
                if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) continue;
                String candidate = text.substring(offset, end);
                replacement = mappings.get(candidate);
                if (replacement != null) { consumed = end - offset; break; }
            }
            if (replacement == null) {
                int codePoint = text.codePointAt(offset);
                output.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            } else {
                output.append(replacement);
                offset += consumed;
            }
        }
        return output.toString();
    }

    private void load(Context context, String asset) {
        load(context, asset, conversions);
    }

    private void load(Context context, String asset, Map<String, String> mappings) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\\t", 2);
                if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) continue;
                String target = parts[1].split(" ", 2)[0];
                mappings.putIfAbsent(parts[0], target);
                longestKey = Math.max(longestKey, parts[0].length());
            }
        } catch (Exception error) {
            Log.e(TAG, "conversion data load failed: " + asset, error);
        }
    }
}
