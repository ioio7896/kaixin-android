package com.example.offlineime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.BiPredicate;

/** Access-ordered cache bounded by retained bytes as well as entry count. */
final class ByteBudgetCache<V> {
    interface Weight<V> { int bytes(V value); }
    // One lock orders all cache accounting; values never call into other caches under it.
    private static final Object LOCK = new Object();
    private static final ArrayList<WeakReference<ByteBudgetCache<?>>> caches = new ArrayList<>();
    private static int totalBudget = 24 * 1024 * 1024;
    private static final class Item<T> {
        final T value;
        final int cost;
        Item(T value, int cost) { this.value = value; this.cost = cost; }
    }
    private final LinkedHashMap<String, Item<V>> values = new LinkedHashMap<>(64, .75f, true);
    private final Weight<V> weight;
    private final int budget, maxEntries;
    private int bytes;
    private final int retention;
    private long revision;
    ByteBudgetCache(int budget, int maxEntries, Weight<V> weight) {
        this(budget, maxEntries, weight, 1);
    }
    ByteBudgetCache(int budget, int maxEntries, Weight<V> weight, int retention) {
        this.budget = budget; this.maxEntries = maxEntries; this.weight = weight;
        this.retention = retention;
        synchronized (LOCK) { caches.add(new WeakReference<>(this)); }
    }
    static void configure(android.content.Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (manager == null) return;
        synchronized (LOCK) {
            int mib = manager.isLowRamDevice() ? 8 : Math.max(8, Math.min(24, manager.getMemoryClass() / 8));
            totalBudget = mib * 1024 * 1024;
            trimAllLocked(totalBudget);
        }
    }
    static void trimMemory(boolean severe) {
        synchronized (LOCK) { trimAllLocked(severe ? 0 : totalBudget / 2); }
    }
    V get(String key) {
        synchronized (LOCK) { Item<V> item = values.get(key); return item == null ? null : item.value; }
    }
    long revision() { synchronized (LOCK) { return revision; } }
    void put(String key, V value) { putIfUnchanged(key, value, -1); }
    void putIfUnchanged(String key, V value, long expectedRevision) {
        int cost = cost(key, value);
        synchronized (LOCK) {
            if (expectedRevision >= 0 && expectedRevision != revision) return;
            Item<V> old = values.remove(key);
            if (old != null) bytes -= old.cost;
            if (cost > budget || cost > totalBudget) return;
            values.put(key, new Item<>(value, cost)); bytes += cost;
            while (bytes > budget || values.size() > maxEntries) evictOldest();
            trimAllLocked(totalBudget);
        }
    }
    void removeIf(BiPredicate<String, V> predicate) {
        synchronized (LOCK) {
            revision++;
            java.util.Iterator<Map.Entry<String, Item<V>>> iterator = values.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Item<V>> entry = iterator.next();
                if (predicate.test(entry.getKey(), entry.getValue().value)) {
                    bytes -= entry.getValue().cost; iterator.remove();
                }
            }
        }
    }
    void clear() { synchronized (LOCK) { values.clear(); bytes = 0; revision++; } }
    void evictAll() { clear(); }
    private void evictOldest() {
        java.util.Iterator<Item<V>> iterator = values.values().iterator();
        if (iterator.hasNext()) { bytes -= iterator.next().cost; iterator.remove(); }
    }
    private static void trimAllLocked(int target) {
        int total = 0;
        for (int i = caches.size() - 1; i >= 0; i--) {
            ByteBudgetCache<?> cache = caches.get(i).get();
            if (cache == null) caches.remove(i); else total += cache.bytes;
        }
        while (total > target) {
            ByteBudgetCache<?> victim = null;
            for (WeakReference<ByteBudgetCache<?>> reference : caches) {
                ByteBudgetCache<?> cache = reference.get();
                if (cache != null && !cache.values.isEmpty() && (victim == null
                        || cache.retention < victim.retention
                        || cache.retention == victim.retention && cache.bytes > victim.bytes)) victim = cache;
            }
            if (victim == null) break;
            int before = victim.bytes; victim.evictOldest(); total -= before - victim.bytes;
        }
    }
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
