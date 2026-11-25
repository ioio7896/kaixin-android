package com.example.offlineime;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Bundled five-stroke index covering simplified and traditional Han characters.
 * Delegates to the memory-mapped Rust dictionary when available (zero Java heap),
 * with lazy regex-free Java fallback.
 */
final class StrokeInputDictionary {
    private static final String TAG = "OfflineImeStroke";
    private static volatile StrokeInputDictionary instance;

    private final AssetManager assets;
    private final boolean useNative;
    private volatile NavigableMap<String, String> sequences;
    private volatile Map<Integer, Integer> simplifiedRank;
    private volatile Map<Integer, Integer> traditionalRank;

    static StrokeInputDictionary get(Context context) {
        StrokeInputDictionary ready = instance;
        if (ready != null) return ready;
        synchronized (StrokeInputDictionary.class) {
            if (instance == null) instance = new StrokeInputDictionary(context.getApplicationContext().getAssets());
            return instance;
        }
    }

    private StrokeInputDictionary(AssetManager assets) {
        this.assets = assets;
        this.useNative = true;
    }

    StrokeInputDictionary(NavigableMap<String, String> source,
                          Map<Integer, Integer> simplified,
                          Map<Integer, Integer> traditional) {
        this.assets = null;
        this.useNative = false;
        NavigableMap<String, String> seq = new TreeMap<>();
        if (source != null) seq.putAll(source);
        Map<Integer, Integer> simp = new HashMap<>();
        if (simplified != null) simp.putAll(simplified);
        Map<Integer, Integer> trad = new HashMap<>();
        if (traditional != null) trad.putAll(traditional);
        this.sequences = seq;
        this.simplifiedRank = simp;
        this.traditionalRank = trad;
    }

    static boolean isConcreteStrokeSequence(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '1' || c > '5') return false;
        }
        return true;
    }

    static boolean isValidStrokeSequence(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '1' || c > '5') && c != '?') return false;
        }
        return true;
    }

    private static boolean matchesWildcardPrefix(String candidate, String pattern) {
        if (candidate.length() < pattern.length()) return false;
        for (int i = 0; i < pattern.length(); i++) {
            char p = pattern.charAt(i);
            if (p != '?' && candidate.charAt(i) != p) return false;
        }
        return true;
    }

    List<PinyinDictionary.Entry> suggest(Context context, String strokes, int limit) {
        if (!isValidStrokeSequence(strokes))
            return Collections.emptyList();
        boolean traditional = "traditional".equals(context.getSharedPreferences(
                "settings", Context.MODE_PRIVATE).getString("output_script", "simplified"));
        return suggest(strokes, traditional, limit);
    }

    List<PinyinDictionary.Entry> suggest(String strokes, boolean traditional, int limit) {
        if (!isValidStrokeSequence(strokes) || limit <= 0)
            return Collections.emptyList();
        if (useNative) {
            List<PinyinDictionary.Entry> nativeResult =
                    PinyinDictionary.suggestStrokeNative(strokes, traditional, limit);
            if (nativeResult != null) return nativeResult;
        }
        int firstWildcard = strokes.indexOf('?');
        NavigableMap<String, String> seqMap = ensureSequences();
        Map<Integer, Integer> rank = ensureRanking(traditional);
        LinkedHashSet<Integer> exact = new LinkedHashSet<>();
        LinkedHashSet<Integer> prefix = new LinkedHashSet<>();
        if (firstWildcard < 0) {
            addCharacters(seqMap.get(strokes), exact);
            NavigableMap<String, String> range = seqMap.subMap(strokes, true, strokes + '\uffff', true);
            for (Map.Entry<String, String> row : range.entrySet()) {
                if (!row.getKey().startsWith(strokes)) break;
                if (!row.getKey().equals(strokes)) addCharacters(row.getValue(), prefix);
            }
        } else {
            String literalPrefix = strokes.substring(0, firstWildcard);
            Map<String, String> scan = literalPrefix.isEmpty()
                    ? seqMap : seqMap.subMap(literalPrefix, true, literalPrefix + '\uffff', true);
            for (Map.Entry<String, String> row : scan.entrySet()) {
                String candidate = row.getKey();
                if (!literalPrefix.isEmpty() && !candidate.startsWith(literalPrefix)) break;
                if (!matchesWildcardPrefix(candidate, strokes)) continue;
                if (candidate.length() == strokes.length()) addCharacters(row.getValue(), exact);
                else addCharacters(row.getValue(), prefix);
            }
        }
        prefix.removeAll(exact);
        Comparator<Integer> byFrequency = Comparator
                .comparingInt((Integer cp) -> rank.getOrDefault(cp, 100_000 + cp))
                .thenComparingInt(Integer::intValue);
        ArrayList<Integer> exactList = new ArrayList<>(exact);
        ArrayList<Integer> prefixList = new ArrayList<>(prefix);
        exactList.sort(byFrequency);
        prefixList.sort(byFrequency);
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>(Math.min(limit, 128));
        addEntries(result, exactList, strokes, true, rank, limit);
        addEntries(result, prefixList, strokes, false, rank, limit);
        return result;
    }

    List<String> suggestCharacters(Context context, String strokes, int limit) {
        ArrayList<String> result = new ArrayList<>();
        for (PinyinDictionary.Entry entry : suggest(context, strokes, limit)) result.add(entry.word);
        return result;
    }

    private NavigableMap<String, String> ensureSequences() {
        NavigableMap<String, String> ready = sequences;
        if (ready != null) return ready;
        synchronized (this) {
            if (sequences != null) return sequences;
            NavigableMap<String, String> map = new TreeMap<>();
            if (assets != null) loadSequences(assets, map);
            sequences = map;
            return map;
        }
    }

    private Map<Integer, Integer> ensureRanking(boolean traditional) {
        Map<Integer, Integer> ready = traditional ? traditionalRank : simplifiedRank;
        if (ready != null) return ready;
        synchronized (this) {
            ready = traditional ? traditionalRank : simplifiedRank;
            if (ready != null) return ready;
            Map<Integer, Integer> map = new HashMap<>();
            if (assets != null) {
                loadRanking(assets, traditional ? "stroke/ranking-traditional.txt"
                        : "stroke/ranking-simplified.txt", map);
            }
            if (traditional) traditionalRank = map;
            else simplifiedRank = map;
            return map;
        }
    }

    private static void addEntries(List<PinyinDictionary.Entry> output, List<Integer> values,
                                   String strokes, boolean exact, Map<Integer, Integer> rank, int limit) {
        for (int cp : values) {
            if (output.size() >= limit) break;
            int frequency = rank.getOrDefault(cp, 100_000);
            int score = (exact ? 2_000_000 : 1_000_000) - Math.min(900_000, frequency);
            output.add(new PinyinDictionary.Entry(new String(Character.toChars(cp)), score,
                    exact ? PinyinDictionary.TIER_EXACT_FULL : PinyinDictionary.TIER_PREFIX)
                    .withInput(strokes.length(), strokes));
        }
    }

    private static void addCharacters(String text, LinkedHashSet<Integer> output) {
        if (text == null) return;
        text.codePoints().forEach(output::add);
    }

    private static void loadSequences(AssetManager assets, NavigableMap<String, String> output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("stroke/sequence-characters.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab + 1 >= line.length()) continue;
                String key = line.substring(0, tab);
                if (isConcreteStrokeSequence(key)) output.put(key, line.substring(tab + 1));
            }
        } catch (Exception error) {
            Log.e(TAG, "stroke sequence load failed", error);
        }
    }

    private static void loadRanking(AssetManager assets, String asset, Map<Integer, Integer> output) {
        int position = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int[] points = line.codePoints().toArray();
                for (int cp : points) if (!Character.isWhitespace(cp) && !output.containsKey(cp))
                    output.put(cp, position++);
            }
        } catch (Exception error) {
            Log.e(TAG, "stroke ranking load failed: " + asset, error);
        }
    }
}
