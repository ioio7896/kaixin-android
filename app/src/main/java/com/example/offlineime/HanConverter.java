package com.example.offlineime;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Longest-match Simplified-to-Traditional conversion.
 * Delegates to the memory-mapped Rust dictionary when available (zero Java heap),
 * and lazily loads only the requested direction on fallback.
 */
final class HanConverter {
    private static final String TAG = "OfflineImeHan";
    private static volatile HanConverter instance;

    private final AssetManager assets;
    private final boolean useNative;
    private volatile Map<String, String> conversions;
    private volatile Map<String, String> simplifications;
    private volatile int longestTraditionalKey = 1;
    private volatile int longestSimplifiedKey = 1;

    static HanConverter get(Context context) {
        HanConverter ready = instance;
        if (ready != null) return ready;
        synchronized (HanConverter.class) {
            if (instance == null) instance = new HanConverter(context.getApplicationContext().getAssets());
            return instance;
        }
    }

    private HanConverter(AssetManager assets) {
        this.assets = assets;
        this.useNative = true;
    }

    /** Testable constructor also used to verify longest-match behavior without Android I/O. */
    HanConverter(Map<String, String> mappings) {
        this.assets = null;
        this.useNative = false;
        Map<String, String> map = new HashMap<>();
        int maxLen = 1;
        if (mappings != null) {
            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()
                        || entry.getValue() == null || entry.getValue().isEmpty()) continue;
                map.put(entry.getKey(), entry.getValue());
                maxLen = Math.max(maxLen, entry.getKey().length());
            }
        }
        this.conversions = map;
        this.simplifications = new HashMap<>();
        this.longestTraditionalKey = maxLen;
    }

    String toTraditional(String text) {
        if (text == null || text.isEmpty()) return text;
        if (useNative) {
            String nativeResult = PinyinDictionary.convertHanNative(text, true);
            if (nativeResult != null) return nativeResult;
        }
        Map<String, String> map = ensureTraditionalMap();
        return convert(text, map, longestTraditionalKey);
    }

    /** Returns replacements in input order, with null for words that stay the same. */
    String[] toTraditionalBatch(String[] words) {
        QueryWork.check();
        if (useNative) {
            String[] converted = PinyinDictionary.convertHanBatchNative(words);
            QueryWork.check();
            if (converted != null && converted.length == words.length) return converted;
        }
        Map<String, String> map = ensureTraditionalMap();
        String[] converted = new String[words.length];
        for (int index = 0; index < words.length; index++) {
            QueryWork.check();
            String word = words[index];
            String value = convert(word, map, longestTraditionalKey);
            if (!word.equals(value)) converted[index] = value;
        }
        return converted;
    }

    String toSimplified(String text) {
        if (text == null || text.isEmpty()) return text;
        if (useNative) {
            String nativeResult = PinyinDictionary.convertHanNative(text, false);
            if (nativeResult != null) return nativeResult;
        }
        Map<String, String> map = ensureSimplifiedMap();
        return convert(text, map, longestSimplifiedKey);
    }

    private Map<String, String> ensureTraditionalMap() {
        Map<String, String> ready = conversions;
        if (ready != null) return ready;
        synchronized (this) {
            if (conversions != null) return conversions;
            Map<String, String> map = new HashMap<>();
            int maxLen = 1;
            if (assets != null) {
                maxLen = load(assets, "opencc/STPhrases.txt", map, maxLen);
                maxLen = load(assets, "opencc/STCharacters.txt", map, maxLen);
            }
            longestTraditionalKey = maxLen;
            conversions = map;
            return map;
        }
    }

    private Map<String, String> ensureSimplifiedMap() {
        Map<String, String> ready = simplifications;
        if (ready != null) return ready;
        synchronized (this) {
            if (simplifications != null) return simplifications;
            Map<String, String> map = new HashMap<>();
            int maxLen = 1;
            if (assets != null) {
                maxLen = load(assets, "opencc/TSPhrases.txt", map, maxLen);
                maxLen = load(assets, "opencc/TSCharacters.txt", map, maxLen);
            }
            longestSimplifiedKey = maxLen;
            simplifications = map;
            return map;
        }
    }

    private static String convert(String text, Map<String, String> mappings, int longestKey) {
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

    private static int load(AssetManager assets, String asset, Map<String, String> mappings, int longestKey) {
        int maxLen = longestKey;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab + 1 >= line.length()) continue;
                String key = line.substring(0, tab);
                int space = line.indexOf(' ', tab + 1);
                String target = space > tab ? line.substring(tab + 1, space) : line.substring(tab + 1);
                if (target.isEmpty()) continue;
                mappings.putIfAbsent(key, target);
                maxLen = Math.max(maxLen, key.length());
            }
        } catch (Exception error) {
            Log.e(TAG, "conversion data load failed: " + asset, error);
        }
        return maxLen;
    }
}
