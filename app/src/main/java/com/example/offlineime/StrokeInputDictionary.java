package com.example.offlineime;

import android.content.Context;
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

/** Bundled five-stroke index covering simplified and traditional Han characters. */
final class StrokeInputDictionary {
    private static final String TAG = "OfflineImeStroke";
    private static volatile StrokeInputDictionary instance;

    private final NavigableMap<String, String> sequences = new TreeMap<>();
    private final Map<Integer, Integer> simplifiedRank = new HashMap<>();
    private final Map<Integer, Integer> traditionalRank = new HashMap<>();

    static StrokeInputDictionary get(Context context) {
        StrokeInputDictionary ready = instance;
        if (ready != null) return ready;
        synchronized (StrokeInputDictionary.class) {
            if (instance == null) instance = new StrokeInputDictionary(context.getApplicationContext());
            return instance;
        }
    }

    private StrokeInputDictionary(Context context) {
        loadSequences(context);
        loadRanking(context, "stroke/ranking-simplified.txt", simplifiedRank);
        loadRanking(context, "stroke/ranking-traditional.txt", traditionalRank);
    }

    StrokeInputDictionary(NavigableMap<String, String> source,
                          Map<Integer, Integer> simplified,
                          Map<Integer, Integer> traditional) {
        if (source != null) sequences.putAll(source);
        if (simplified != null) simplifiedRank.putAll(simplified);
        if (traditional != null) traditionalRank.putAll(traditional);
    }

    List<PinyinDictionary.Entry> suggest(Context context, String strokes, int limit) {
        if (strokes == null || strokes.isEmpty() || !strokes.matches("[1-5]+"))
            return Collections.emptyList();
        boolean traditional = "traditional".equals(context.getSharedPreferences(
                "settings", Context.MODE_PRIVATE).getString("output_script", "simplified"));
        return suggest(strokes, traditional, limit);
    }

    List<PinyinDictionary.Entry> suggest(String strokes, boolean traditional, int limit) {
        if (strokes == null || strokes.isEmpty() || !strokes.matches("[1-5]+") || limit <= 0)
            return Collections.emptyList();
        Map<Integer, Integer> rank = traditional ? traditionalRank : simplifiedRank;
        LinkedHashSet<Integer> exact = new LinkedHashSet<>();
        LinkedHashSet<Integer> prefix = new LinkedHashSet<>();
        addCharacters(sequences.get(strokes), exact);
        NavigableMap<String, String> range = sequences.subMap(strokes, true, strokes + '\uffff', true);
        for (Map.Entry<String, String> row : range.entrySet()) {
            if (!row.getKey().startsWith(strokes)) break;
            if (!row.getKey().equals(strokes)) addCharacters(row.getValue(), prefix);
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

    private void loadSequences(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("stroke/sequence-characters.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\\t", 2);
                if (parts.length == 2 && parts[0].matches("[1-5]+")) sequences.put(parts[0], parts[1]);
            }
        } catch (Exception error) {
            Log.e(TAG, "stroke sequence load failed", error);
        }
    }

    private static void loadRanking(Context context, String asset, Map<Integer, Integer> output) {
        int position = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(asset), StandardCharsets.UTF_8))) {
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
