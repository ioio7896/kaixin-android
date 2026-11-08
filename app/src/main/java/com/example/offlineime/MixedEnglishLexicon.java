package com.example.offlineime;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A small exact-code vocabulary for English and mixed-script words on the Chinese keyboard. */
final class MixedEnglishLexicon {
    private static final String ASSET = "en/kaixin_mixed_100.txt";
    private final Map<String, List<PinyinDictionary.Entry>> byCode;

    MixedEnglishLexicon(AssetManager assets) {
        Map<String, List<PinyinDictionary.Entry>> entries = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\t");
                if (columns.length != 3 || columns[0].isEmpty()) continue;
                int score;
                try { score = Integer.parseInt(columns[2]); }
                catch (NumberFormatException ignored) { continue; }
                for (String alias : columns[1].split(",")) {
                    String code = alias.trim().toLowerCase(Locale.ROOT);
                    if (!code.matches("[a-z]{2,24}")) continue;
                    entries.computeIfAbsent(code, ignored -> new ArrayList<>())
                            .add(new PinyinDictionary.Entry(columns[0], score,
                                    PinyinDictionary.TIER_EXACT_FULL).withInput(code.length(), ""));
                }
            }
        } catch (IOException error) {
            Log.e("MixedEnglishLexicon", "failed to load " + ASSET, error);
        }
        Map<String, List<PinyinDictionary.Entry>> frozen = new HashMap<>();
        for (Map.Entry<String, List<PinyinDictionary.Entry>> bucket : entries.entrySet()) {
            bucket.getValue().sort((first, second) -> Integer.compare(second.score, first.score));
            frozen.put(bucket.getKey(), Collections.unmodifiableList(bucket.getValue()));
        }
        byCode = Collections.unmodifiableMap(frozen);
    }

    List<PinyinDictionary.Entry> suggest(String raw) {
        if (raw == null) return Collections.emptyList();
        List<PinyinDictionary.Entry> entries = byCode.get(raw.toLowerCase(Locale.ROOT));
        return entries == null ? Collections.emptyList() : entries;
    }

    boolean contains(String raw, String word) {
        for (PinyinDictionary.Entry entry : suggest(raw))
            if (entry.word.equals(word)) return true;
        return false;
    }
}
