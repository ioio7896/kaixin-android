package com.example.offlineime;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Encrypted clipboard history with pinned items kept separately from recent items. */
final class ClipboardHistoryStore {
    private static final String TAG = "OfflineImeClipboard";
    private static final String PREFS = "clipboard_history";
    private static final String KEY_ALIAS = "offline_ime_clipboard";
    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int DEFAULT_MAX_AGE_DAYS = 7;
    static final int MAX_TEXT_CHARS = 8192;
    private static final int MAX_TEXT_BYTES = 16 * 1024;
    private static final int MAX_TOTAL_BYTES = 256 * 1024;
    private static final int MAX_PINNED = 30;
    private static volatile ClipboardHistoryStore active;
    private final Context context;
    private List<Item> cachedItems;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "offline-clipboard-writer");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pendingWrite;
    private boolean suspended;
    synchronized void releaseMemory() {
        if (pendingWrite == null || pendingWrite.isDone()) cachedItems = null;
    }
    static void flushBeforeGlobalClear() {
        ClipboardHistoryStore store = active;
        if (store != null) {
            synchronized (store) {
                store.suspended = true;
                store.flushPendingWrites();
            }
        }
    }
    synchronized void resumeAfterGlobalClear() {
        cachedItems = null;
        suspended = false;
    }
    synchronized void close() {
        flushPendingWrites();
        writer.shutdown();
        if (active == this) active = null;
    }
    private static ArrayList<Item> copyItems(List<Item> values) {
        ArrayList<Item> result = new ArrayList<>();
        for (Item item : values) result.add(new Item(item.text, item.timestamp, item.pinned));
        return result;
    }

    static final class Entry {
        final String text;
        final long timestamp;
        final boolean pinned;

        Entry(String text, long timestamp, boolean pinned) {
            this.text = text;
            this.timestamp = timestamp;
            this.pinned = pinned;
        }
    }

    private static final class Item {
        final String text;
        long timestamp;
        boolean pinned;

        Item(String text, long timestamp, boolean pinned) {
            this.text = text;
            this.timestamp = timestamp;
            this.pinned = pinned;
        }
    }

    ClipboardHistoryStore(Context context) {
        this.context = context.getApplicationContext();
        active = this;
    }

    private void migratePlaintextHistory() {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.contains("blob") || !preferences.contains("item_0")) return;
        ArrayList<Item> old = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < recentLimit(); i++) {
            String text = preferences.getString("item_" + i, null);
            if (text != null) old.add(new Item(text, preferences.getLong("time_" + i, now),
                    preferences.getBoolean("fixed_" + text.hashCode(), false)));
        }
        preferences.edit().clear().commit();
        writeItems(old);
        flushPendingWrites();
    }

    synchronized void add(String text) {
        if (suspended || !canStore(text)) return;
        List<Item> items = readItems();
        Item existing = find(items, text);
        if (existing != null) items.remove(existing);
        items.add(new Item(text, System.currentTimeMillis(), existing != null && existing.pinned));
        writeItems(items);
    }

    synchronized void addIfAbsent(String text) {
        if (suspended || !canStore(text)) return;
        List<Item> items = readItems();
        if (find(items, text) != null) return;
        items.add(new Item(text, System.currentTimeMillis(), false));
        writeItems(items);
    }

    static boolean canStore(String text) {
        return text != null && !text.trim().isEmpty() && text.length() <= MAX_TEXT_CHARS
                && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_TEXT_BYTES;
    }

    synchronized List<Entry> listEntries() {
        List<Item> items = readItems();
        ArrayList<Item> valid = normalize(items);
        if (valid.size() != items.size()) writeItems(valid);
        ArrayList<Entry> result = new ArrayList<>();
        for (Item item : valid) result.add(new Entry(item.text, item.timestamp, item.pinned));
        return result;
    }

    synchronized List<Entry> snapshot() {
        return listEntries();
    }

    synchronized void restore(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        LinkedHashMap<String, Item> combined = new LinkedHashMap<>();
        for (Item item : readItems()) combined.put(id(item.text), item);
        for (Entry entry : entries) {
            if (entry == null || entry.text == null) continue;
            String key = id(entry.text);
            Item current = combined.get(key);
            if (current == null) combined.put(key, new Item(entry.text, entry.timestamp, entry.pinned));
            else {
                current.timestamp = Math.max(current.timestamp, entry.timestamp);
                current.pinned |= entry.pinned;
            }
        }
        writeItems(new ArrayList<>(combined.values()));
    }

    synchronized void togglePinned(String text) {
        List<Item> items = readItems();
        Item item = find(items, text);
        if (item != null) {
            item.pinned = !item.pinned;
            writeItems(items);
        }
    }

    synchronized void remove(String text) {
        List<Item> items = readItems();
        items.removeIf(item -> item.text.equals(text));
        writeItems(items);
    }

    synchronized void removeAll(java.util.Collection<String> texts) {
        if (texts == null || texts.isEmpty()) return;
        List<Item> items = readItems();
        items.removeIf(item -> texts.contains(item.text));
        writeItems(items);
    }

    synchronized void clear() {
        writeItems(Collections.emptyList());
        flushPendingWrites();
    }

    private List<Item> readItems() {
        // Legacy migration is needed only when clipboard history is used.
        migratePlaintextHistory();
        if (cachedItems != null) return copyItems(cachedItems);
        ArrayList<Item> result = new ArrayList<>();
        String raw = SecureLocalStore.read(context, PREFS, KEY_ALIAS);
        if (raw.isEmpty()) { cachedItems = new ArrayList<>(); return result; }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                result.add(new Item(value.getString("text"), value.getLong("time"), value.optBoolean("pinned")));
            }
        } catch (Exception error) {
            Log.e(TAG, "history parse failed: " + error.getClass().getSimpleName());
        }
        cachedItems = copyItems(result);
        return result;
    }

    private ArrayList<Item> normalize(List<Item> items) {
        ArrayList<Entry> input = new ArrayList<>(items.size());
        for (Item item : items) input.add(new Entry(item.text, item.timestamp, item.pinned));
        List<Entry> kept = applyPolicy(input, recentLimit(), maxAgeDays(), System.currentTimeMillis());
        ArrayList<Item> result = new ArrayList<>(kept.size());
        for (Entry entry : kept) result.add(new Item(entry.text, entry.timestamp, entry.pinned));
        return result;
    }

    /** Pure retention policy used by storage and deterministic JVM regression tests. */
    static List<Entry> applyPolicy(List<Entry> entries, int recentLimit, int maxAgeDays, long now) {
        int limit = Math.max(0, recentLimit);
        long cutoff = maxAgeDays <= 0 ? Long.MIN_VALUE
                : now - maxAgeDays * 24L * 60 * 60 * 1000;
        ArrayList<Entry> pinned = new ArrayList<>();
        ArrayList<Entry> recent = new ArrayList<>();
        if (entries != null) for (Entry entry : entries) {
            if (entry == null || !canStore(entry.text)) continue;
            if (entry.pinned) pinned.add(entry);
            else if (entry.timestamp >= cutoff) recent.add(entry);
        }
        Comparator<Entry> newestFirst = (left, right) -> Long.compare(right.timestamp, left.timestamp);
        Collections.sort(pinned, newestFirst);
        Collections.sort(recent, newestFirst);
        if (pinned.size() > MAX_PINNED) pinned.subList(MAX_PINNED, pinned.size()).clear();
        if (recent.size() > limit) recent.subList(limit, recent.size()).clear();
        ArrayList<Entry> kept = new ArrayList<>();
        int bytes = 0;
        for (Entry entry : pinned) {
            int size = entry.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size <= MAX_TOTAL_BYTES) { kept.add(entry); bytes += size; }
        }
        for (Entry entry : recent) {
            int size = entry.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size <= MAX_TOTAL_BYTES) { kept.add(entry); bytes += size; }
        }
        return kept;
    }

    private int recentLimit() {
        String value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("clipboard_limit", String.valueOf(DEFAULT_RECENT_LIMIT));
        try { return Math.max(5, Math.min(100, Integer.parseInt(value))); }
        catch (NumberFormatException ignored) { return DEFAULT_RECENT_LIMIT; }
    }

    private int maxAgeDays() {
        String value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("clipboard_retention_days", String.valueOf(DEFAULT_MAX_AGE_DAYS));
        try { return Math.max(0, Math.min(365, Integer.parseInt(value))); }
        catch (NumberFormatException ignored) { return DEFAULT_MAX_AGE_DAYS; }
    }

    private synchronized void writeItems(List<Item> items) {
        ArrayList<Item> normalized = normalize(items);
        cachedItems = copyItems(normalized);
        if (pendingWrite != null) pendingWrite.cancel(false);
        List<Item> snapshot = copyItems(normalized);
        pendingWrite = writer.schedule(() -> persist(snapshot), 150, TimeUnit.MILLISECONDS);
    }

    private void persist(List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id(item.text));
                value.put("text", item.text);
                value.put("time", item.timestamp);
                value.put("pinned", item.pinned);
                array.put(value);
            } catch (Exception error) {
                Log.e(TAG, "history encode failed: " + error.getClass().getSimpleName());
            }
        }
        SecureLocalStore.write(context, PREFS, KEY_ALIAS, array.toString());
    }

    private synchronized void flushPendingWrites() {
        if (pendingWrite == null) return;
        pendingWrite.cancel(false);
        List<Item> snapshot = copyItems(cachedItems);
        try { writer.submit(() -> persist(snapshot)).get(3, TimeUnit.SECONDS); }
        catch (Exception error) { Log.e(TAG, "history flush failed: " + error.getClass().getSimpleName()); }
        pendingWrite = null;
    }

    private static Item find(List<Item> items, String text) {
        for (Item item : items) if (item.text.equals(text)) return item;
        return null;
    }

    static String id(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
