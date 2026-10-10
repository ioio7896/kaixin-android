package com.example.offlineime;

import java.util.LinkedHashMap;
import java.util.Map;

/** Access-ordered cache bounded by retained bytes as well as entry count. */
final class ByteBudgetCache<V> {
    interface Weight<V> { int bytes(V value); }
    private final LinkedHashMap<String, V> values = new LinkedHashMap<>(64, .75f, true);
    private final Weight<V> weight;
    private final int budget, maxEntries;
    private int bytes;
    ByteBudgetCache(int budget, int maxEntries, Weight<V> weight) {
        this.budget = budget; this.maxEntries = maxEntries; this.weight = weight;
    }
    synchronized V get(String key) { return values.get(key); }
    synchronized void put(String key, V value) {
        V old = values.remove(key);
        if (old != null) bytes -= cost(key, old);
        int cost = cost(key, value);
        if (cost > budget) return;
        values.put(key, value); bytes += cost;
        while (bytes > budget || values.size() > maxEntries) {
            Map.Entry<String, V> first = values.entrySet().iterator().next();
            bytes -= cost(first.getKey(), first.getValue()); values.remove(first.getKey());
        }
    }
    synchronized void clear() { values.clear(); bytes = 0; }
    void evictAll() { clear(); }
    private int cost(String key, V value) { return 80 + key.length() * 2 + weight.bytes(value); }
    static int entries(java.util.List<PinyinDictionary.Entry> entries) {
        int size = 32 + entries.size() * 8;
        for (PinyinDictionary.Entry entry : entries) {
            size += 160 + (entry.word.length() + entry.pronunciation.length()
                    + entry.queryText.length() + entry.inputVersion.length()) * 2;
            for (String segment : entry.wordSegments) size += 40 + segment.length() * 2;
        }
        return size;
    }
    static int paths(java.util.List<java.util.List<String>> paths) {
        int size = 32;
        for (java.util.List<String> path : paths) {
            size += 32 + path.size() * 8;
            for (String token : path) size += 40 + token.length() * 2;
        }
        return size;
    }
}
