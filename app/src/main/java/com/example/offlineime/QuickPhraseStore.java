package com.example.offlineime;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** User-authored phrases, stored separately from expiring clipboard history. */
final class QuickPhraseStore {
    private static final String TAG = "OfflineImeQuickPhrases";
    private static final String PREFS = "quick_phrases";
    private static final String KEY_ALIAS = "offline_ime_quick_phrases";
    private static final int MAX_PHRASES = 100;
    private static final int MAX_LENGTH = 1000;
    private final Context context;

    static final class Entry {
        final String id;
        final String text;
        Entry(String id, String text) { this.id = id; this.text = text; }
    }

    QuickPhraseStore(Context context) { this.context = context.getApplicationContext(); }

    synchronized List<Entry> list() {
        ArrayList<Entry> entries = new ArrayList<>();
        String raw = SecureLocalStore.read(context, PREFS, KEY_ALIAS);
        if (raw.isEmpty()) return entries;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                String id = value.getString("id");
                String text = value.getString("text");
                if (!id.isEmpty() && !text.trim().isEmpty()) entries.add(new Entry(id, text));
            }
        } catch (Exception error) {
            Log.e(TAG, "phrase parse failed: " + error.getClass().getSimpleName());
        }
        return entries;
    }

    synchronized Entry find(String id) {
        for (Entry entry : list()) if (entry.id.equals(id)) return entry;
        return null;
    }

    synchronized boolean save(String id, String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("短语不能为空");
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("短语最多 1000 字符");
        List<Entry> entries = list();
        int position = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.id.equals(id)) position = i;
            else if (entry.text.equals(text)) throw new IllegalArgumentException("这条短语已经存在");
        }
        if (id != null && position < 0) throw new IllegalArgumentException("短语已被删除");
        if (position < 0 && entries.size() >= MAX_PHRASES)
            throw new IllegalArgumentException("最多保存 100 条快捷短语");
        Entry updated = new Entry(position < 0 ? UUID.randomUUID().toString() : id, text);
        if (position < 0) entries.add(0, updated);
        else entries.set(position, updated);
        return write(entries);
    }

    synchronized boolean remove(String id) {
        List<Entry> entries = list();
        if (!entries.removeIf(entry -> entry.id.equals(id))) return false;
        return write(entries);
    }

    private boolean write(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject value = new JSONObject();
                value.put("id", entry.id);
                value.put("text", entry.text);
                array.put(value);
            }
        } catch (Exception error) {
            Log.e(TAG, "phrase encode failed: " + error.getClass().getSimpleName());
            return false;
        }
        return SecureLocalStore.write(context, PREFS, KEY_ALIAS, array.toString());
    }
}
