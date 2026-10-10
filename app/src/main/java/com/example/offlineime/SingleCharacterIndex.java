package com.example.offlineime;

import android.database.Cursor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable primitive character/score pools; all spelling buckets share row IDs. */
final class SingleCharacterIndex {
    static final SingleCharacterIndex EMPTY = new SingleCharacterIndex(new int[0], new int[0], new short[0],
            new String[0], Collections.emptyMap());
    private final int[] characters, scores;
    private final short[] readings;
    private final String[] syllables;
    private final Map<String, int[]> buckets;
    private SingleCharacterIndex(int[] characters, int[] scores, short[] readings,
            String[] syllables, Map<String, int[]> buckets) {
        this.characters = characters; this.scores = scores; this.readings = readings;
        this.syllables = syllables; this.buckets = buckets;
    }
    static SingleCharacterIndex load(Cursor cursor) {
        List<Integer> chars = new ArrayList<>(), frequencies = new ArrayList<>();
        List<Short> readingIds = new ArrayList<>();
        Map<String, Short> readingPool = new LinkedHashMap<>();
        Map<String, List<Integer>> index = new HashMap<>();
        while (cursor.moveToNext()) {
            QueryWork.check();
            String word = cursor.getString(0), reading = cursor.getString(2);
            if (word.codePointCount(0, word.length()) != 1 || !PinyinPathEditor.isCompleteReading(reading)) continue;
            short readingId = readingPool.computeIfAbsent(reading, key -> (short) readingPool.size());
            int id = chars.size(); chars.add(word.codePointAt(0)); frequencies.add(cursor.getInt(1)); readingIds.add(readingId);
            String digits = PinyinDictionary.toT9(reading);
            bucket(index, "p:" + reading, id); bucket(index, "9:" + digits, id);
            // Prefix buckets support incomplete syllables and explicitly selected prefix readings.
            for (int end = 1; end <= reading.length(); end++) bucket(index, "p~" + reading.substring(0, end), id);
            for (int end = 1; end <= digits.length(); end++) bucket(index, "9~" + digits.substring(0, end), id);
        }
        int[] characters = new int[chars.size()], scores = new int[chars.size()];
        short[] readings = new short[chars.size()];
        for (int i = 0; i < chars.size(); i++) { characters[i] = chars.get(i); scores[i] = frequencies.get(i); readings[i] = readingIds.get(i); }
        Map<String, int[]> packed = new HashMap<>();
        for (Map.Entry<String, List<Integer>> bucket : index.entrySet()) {
            int[] ids = new int[bucket.getValue().size()];
            for (int i = 0; i < ids.length; i++) ids[i] = bucket.getValue().get(i);
            packed.put(bucket.getKey(), ids);
        }
        return new SingleCharacterIndex(characters, scores, readings,
                readingPool.keySet().toArray(new String[0]), Collections.unmodifiableMap(packed));
    }
    private static void bucket(Map<String, List<Integer>> out, String key, int id) {
        out.computeIfAbsent(key, ignored -> new ArrayList<>()).add(id);
    }
    List<PinyinDictionary.Entry> lookup(String input, boolean numeric, PinyinPathEditor.Query constraint,
            boolean full, boolean shortInput, boolean doubles, boolean prefix, int limit) {
        String plain = input.replace("'", "");
        if (plain.isEmpty() || plain.length() > 6 || limit <= 0) return Collections.emptyList();
        // Exact buckets avoid scanning all readings of an initial. Prefix buckets are frequency ordered too.
        boolean exactOnly = full && !shortInput && !prefix;
        int[] ids = buckets.get((numeric ? "9" : "p") + (exactOnly ? ":" : "~") + plain);
        if (ids == null) return Collections.emptyList();
        List<PinyinDictionary.Entry> result = new ArrayList<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int id : ids) {
            QueryWork.check();
            String reading = syllables[readings[id]];
            PinyinInput.Match match = constraint == null
                    ? PinyinInput.match(input, reading, full, shortInput, true, doubles, prefix)
                    : constraint.match(reading, full, shortInput, true, doubles, prefix);
            if (match == null || !seen.add(characters[id])) continue;
            result.add(new PinyinDictionary.Entry(new String(Character.toChars(characters[id])), scores[id], match.tier)
                    .withInput(input.length(), reading));
            if (result.size() >= limit) break;
        }
        return result;
    }
}
