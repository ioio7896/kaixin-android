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
    private final ScheduledExecutorService writer = PersonalDataIO.EXECUTOR;
    private ScheduledFuture<?> pendingWrite;
    private boolean dirty;
    private boolean legacyCleanupPending;
    private long writeRevision;
    private boolean suspended;
    synchronized void releaseMemory() {
        if (!dirty) cachedItems = null;
    }
    static void flushBeforeGlobalClear() {
        ClipboardHistoryStore store = active;
        if (store != null) {
            synchronized (store) {
                store.suspended = true;
                // Explicit deletion supersedes pending writes. This runs on the same IO lane.
                if (store.pendingWrite != null) store.pendingWrite.cancel(false);
                store.pendingWrite = null;
                store.dirty = false;
                store.legacyCleanupPending = false;
                store.writeRevision++;
                store.cachedItems = null;
            }
        }
    }
    synchronized void resumeAfterGlobalClear() {
        cachedItems = null;
        suspended = false;
    }
    synchronized void close() {
        flushPendingWrites();
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
        if (!legacyCleanupPending && !preferences.contains("item_0")) return;
        SecureLocalStore.ReadResult encrypted = SecureLocalStore.readResult(context, PREFS, KEY_ALIAS);
        if (!encrypted.succeeded()) {
            throw new IllegalStateException("剪贴板历史读取失败，原数据已保留；请重新打开页面重试");
        }
        if (encrypted.status == SecureLocalStore.ReadStatus.EMPTY) {
            ArrayList<Item> old = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < recentLimit(); i++) {
                String text = preferences.getString("item_" + i, null);
                if (text != null) old.add(new Item(text, preferences.getLong("time_" + i, now),
                        preferences.getBoolean("fixed_" + text.hashCode(), false)));
            }
            writeItems(old);
            flushPendingWrites();
        } else {
            // A previous process may have stopped after encryption but before
            // cleanup. Verify that copy before discarding the legacy records.
            decodeItems(encrypted.value);
        }
        android.content.SharedPreferences.Editor cleanup = preferences.edit();
        for (String key : preferences.getAll().keySet())
            if (key.startsWith("item_") || key.startsWith("time_") || key.startsWith("fixed_")) cleanup.remove(key);
        // Migration already runs on PersonalDataIO. Report cleanup failure rather
        // than silently leaving a plaintext copy after the encrypted write.
        legacyCleanupPending = !cleanup.commit();
        if (legacyCleanupPending)
            throw new IllegalStateException("剪贴板已加密保存，但旧记录清理失败，请重试");
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
        String raw = SecureLocalStore.read(context, PREFS, KEY_ALIAS);
        if (raw.isEmpty()) {
            SecureLocalStore.markReadable(PREFS);
            cachedItems = new ArrayList<>(); return new ArrayList<>();
        }
        ArrayList<Item> result = decodeItems(raw);
        SecureLocalStore.markReadable(PREFS);
        cachedItems = copyItems(result);
        return result;
    }

    private ArrayList<Item> decodeItems(String raw) {
        ArrayList<Item> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                result.add(new Item(value.getString("text"), value.getLong("time"), value.optBoolean("pinned")));
            }
        } catch (Exception error) {
            SecureLocalStore.markUnreadable(PREFS);
            Log.e(TAG, "history parse failed: " + error.getClass().getSimpleName());
            throw new IllegalStateException("剪贴板历史读取失败，原数据已保留；请重新打开页面重试");
        }
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
        if (suspended) return;
        ArrayList<Item> normalized = normalize(items);
        cachedItems = copyItems(normalized);
        dirty = true;
        long revision = ++writeRevision;
        if (pendingWrite != null) pendingWrite.cancel(false);
        List<Item> snapshot = copyItems(normalized);
        pendingWrite = writer.schedule(() -> {
            synchronized (this) {
                if (suspended || revision != writeRevision) return;
                try {
                    persist(snapshot);
                    dirty = false;
                } catch (RuntimeException error) {
                    Log.e(TAG, "history write failed: " + error.getClass().getSimpleName());
                } finally { pendingWrite = null; }
            }
        }, 150, TimeUnit.MILLISECONDS);
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
        if (!SecureLocalStore.write(context, PREFS, KEY_ALIAS, array.toString()))
            throw new IllegalStateException("剪贴板保存失败，已有数据已保留");
    }

    private synchronized void flushPendingWrites() {
        if (pendingWrite != null) pendingWrite.cancel(false);
        pendingWrite = null;
        if (!dirty || suspended || cachedItems == null) return;
        persist(copyItems(cachedItems));
        dirty = false;
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
