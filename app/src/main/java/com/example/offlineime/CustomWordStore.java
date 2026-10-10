package com.example.offlineime;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit user words are independent of automatic learning and device input codes. */
final class CustomWordStore {
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ime-custom-words"); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });
    private static final String PREFS = "custom_words_v1", KEY = "offline_ime_custom_words";
    private static final Object LOCK = new Object();
    private static long cachedRevision = Long.MIN_VALUE;
    private static Map<String, Word> cache = Collections.emptyMap();
    static final class Word {
        final String text, category;
        final List<String> readings, aliases;
        final boolean pinned;
        final long createdAt;
        Word(String text, List<String> readings, List<String> aliases, String category, boolean pinned, long createdAt) {
            this.text = text; this.readings = Collections.unmodifiableList(new ArrayList<>(readings));
            this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases));
            this.category = category; this.pinned = pinned; this.createdAt = createdAt;
        }
        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("word", text).put("readings", new JSONArray(readings))
                    .put("aliases", new JSONArray(aliases)).put("category", category)
                    .put("pinned", pinned).put("createdAt", createdAt);
        }
    }
    static final class Change {
        final String word, error;
        final Word before, after;
        String renamedFrom = "";
        Word renamedBefore;
        Change(String word, Word before, Word after, String error) {
            this.word = word; this.before = before; this.after = after; this.error = error == null ? "词库操作失败，请重试" : error;
        }
        boolean succeeded() { return error.isEmpty(); }
    }
    static Map<String, Word> load(Context context) {
        synchronized (LOCK) {
            long revision = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("revision", 0);
            if (cachedRevision == revision) return cache;
            String value = SecureLocalStore.read(context, PREFS, KEY);
            Map<String, Word> result = new LinkedHashMap<>();
            if (!value.isEmpty()) {
                try {
                    JSONObject root = new JSONObject(value);
                    if (root.getInt("version") != 1) throw new IllegalArgumentException("unsupported version");
                    JSONArray words = root.getJSONArray("words");
                    for (int i = 0; i < words.length(); i++) {
                        Word word = decode(words.getJSONObject(i)); result.put(word.text, word);
                    }
                } catch (Exception invalid) {
                    // Never replace a damaged or future-format store with an empty file.
                    throw new IllegalStateException("自定义词库读取失败，原数据已保留");
                }
            } else if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("blob")) {
                throw new IllegalStateException("自定义词库解密失败，原数据已保留");
            }
            cache = Collections.unmodifiableMap(result); cachedRevision = revision; return cache;
        }
    }
    static String reading(String value, String word) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("u:", "v").replace('ü', 'v')
                .replace('’', '\'').replace('·', '\'').replaceAll("\\s+", "'");
        if (!normalized.matches("[a-z]+(?:'[a-z]+)*"))
            throw new IllegalArgumentException("请填写完整拼音，音节用空格或 ' 分隔");
        if (normalized.indexOf('\'') >= 0) {
            for (String token : normalized.split("'"))
                if (!PinyinPathEditor.isCompleteReading(token)) throw new IllegalArgumentException("音节「" + token + "」不完整或不合法");
            return normalized;
        }
        List<List<String>> paths = new PinyinLexer(Collections.emptyList()).splitAll(normalized, 32);
        int length = word.codePointCount(0, word.length());
        List<List<String>> matching = new ArrayList<>();
        for (List<String> path : paths) if (path.size() == length) matching.add(path);
        if (matching.size() == 1) return String.join("'", matching.get(0));
        if (matching.isEmpty() && paths.size() == 1) return String.join("'", paths.get(0));
        throw new IllegalArgumentException("拼音有多种切分，请用空格明确音节，例如 xi an");
    }
    static Word validate(String text, String readings, String aliases, String category, boolean pinned, long createdAt) {
        String word = text == null ? "" : text.trim();
        if (word.isEmpty() || word.codePointCount(0, word.length()) > 64 || word.matches("(?s).*[\\t\\r\\n].*"))
            throw new IllegalArgumentException("词语不能为空，最多 64 个字，不能包含换行");
        LinkedHashSet<String> pronunciations = new LinkedHashSet<>();
        for (String value : readings.split("[,，;；]")) if (!value.trim().isEmpty()) pronunciations.add(reading(value, word));
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String value : aliases.split("[,，;；]")) {
            String code = value.trim().toLowerCase(Locale.ROOT).replace('ü', 'v').replace("u:", "v");
            if (code.isEmpty()) continue;
            if (!code.matches("[a-z]{1,64}|[2-9]{1,64}")) throw new IllegalArgumentException("快捷别名只能包含英文字母或九键数字 2—9");
            codes.add(code);
        }
        if (pronunciations.isEmpty() && codes.isEmpty()) throw new IllegalArgumentException("请填写完整读音或快捷别名");
        if (pronunciations.size() > 16 || codes.size() > 16) throw new IllegalArgumentException("读音与别名各最多 16 个");
        String label = category == null ? "普通词" : category.trim();
        if (label.length() > 24) throw new IllegalArgumentException("分类最多 24 个字");
        return new Word(word, new ArrayList<>(pronunciations), new ArrayList<>(codes), label, pinned, createdAt);
    }
    static Change save(Context context, Word requested) {
        synchronized (LOCK) {
            if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false))
                return new Change(requested.text, null, null, "隐私模式下不保存词语");
            try {
                Map<String, Word> all = new LinkedHashMap<>(load(context));
                Word before = all.get(requested.text);
                Word after = new Word(requested.text, requested.readings, requested.aliases, requested.category,
                        requested.pinned, before == null ? System.currentTimeMillis() : before.createdAt);
                all.put(after.text, after);
                if (all.size() > 10000) throw new IllegalArgumentException("自定义词库已达到 10000 条上限");
                if (!write(context, all)) return new Change(after.text, before, null, "保存失败，请重试；原词条已保留");
                return new Change(after.text, before, after, "");
            } catch (RuntimeException error) { return new Change(requested.text, null, null, error.getMessage()); }
        }
    }
    static Change merge(Context context, Word requested) {
        synchronized (LOCK) {
            try {
                Word existing = load(context).get(requested.text);
                if (existing == null) return save(context, requested);
                LinkedHashSet<String> readings = new LinkedHashSet<>(existing.readings), aliases = new LinkedHashSet<>(existing.aliases);
                readings.addAll(requested.readings); aliases.addAll(requested.aliases);
                return save(context, validate(requested.text, String.join(",", readings), String.join(",", aliases),
                        existing.category, existing.pinned || requested.pinned, existing.createdAt));
            } catch (RuntimeException error) { return new Change(requested.text, null, null, error.getMessage()); }
        }
    }
    static Change rename(Context context, String original, Word requested) {
        synchronized (LOCK) {
            if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false))
                return new Change(requested.text, null, null, "隐私模式下不保存词语");
            try {
                Map<String, Word> all = new LinkedHashMap<>(load(context));
                Word old = all.remove(original), overwritten = all.get(requested.text);
                if (overwritten != null) return new Change(requested.text, null, null, "目标词语已存在，请先编辑或合并该词");
                all.put(requested.text, requested);
                if (!write(context, all)) return new Change(requested.text, null, null, "保存失败，原词条已保留");
                Change change = new Change(requested.text, null, requested, ""); change.renamedFrom = original; change.renamedBefore = old; return change;
            } catch (RuntimeException error) { return new Change(requested.text, null, null, error.getMessage()); }
        }
    }
    static Change remove(Context context, String word) {
        synchronized (LOCK) {
            try {
                Map<String, Word> all = new LinkedHashMap<>(load(context)); Word before = all.remove(word);
                return write(context, all) ? new Change(word, before, null, "")
                        : new Change(word, before, null, "删除失败，原词条已保留");
            } catch (RuntimeException error) { return new Change(word, null, null, error.getMessage()); }
        }
    }
    static boolean undo(Context context, Change change) {
        synchronized (LOCK) {
            Map<String, Word> all = new LinkedHashMap<>(load(context));
            if (!same(all.get(change.word), change.after)) return false;
            if (!change.renamedFrom.isEmpty() && all.containsKey(change.renamedFrom)) return false;
            if (change.before == null) all.remove(change.word); else all.put(change.word, change.before);
            if (change.renamedBefore != null) all.put(change.renamedFrom, change.renamedBefore);
            return write(context, all);
        }
    }
    static boolean clear(Context context) { synchronized (LOCK) { return write(context, Collections.emptyMap()); } }
    static String exportJson(Context context) {
        synchronized (LOCK) { return encode(load(context)); }
    }
    static List<Word> previewImport(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (root.getInt("version") != 1) throw new IllegalArgumentException("不支持的词库格式");
            JSONArray items = root.getJSONArray("words");
            if (items.length() > 10000) throw new IllegalArgumentException("导入最多 10000 条");
            List<Word> words = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) words.add(decode(items.getJSONObject(i)));
            return words;
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("词库文件格式不正确"); }
    }
    static boolean importWords(Context context, List<Word> imported) {
        synchronized (LOCK) {
            if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("privacy_mode", false))
                throw new IllegalStateException("隐私模式下不导入词语");
            Map<String, Word> all = new LinkedHashMap<>(load(context));
            for (Word word : imported) {
                Word old = all.get(word.text);
                if (old == null) all.put(word.text, word);
                else {
                    LinkedHashSet<String> readings = new LinkedHashSet<>(old.readings), aliases = new LinkedHashSet<>(old.aliases);
                    readings.addAll(word.readings); aliases.addAll(word.aliases);
                    all.put(word.text, validate(word.text, String.join(",", readings), String.join(",", aliases),
                            old.category, old.pinned || word.pinned, old.createdAt));
                }
            }
            if (all.size() > 10000) throw new IllegalArgumentException("合并后超过 10000 条上限");
            return write(context, all);
        }
    }
    private static Word decode(JSONObject json) throws org.json.JSONException {
        return validate(json.getString("word"), join(json.getJSONArray("readings")),
                join(json.optJSONArray("aliases")), json.optString("category", "普通词"),
                json.optBoolean("pinned", false), json.optLong("createdAt", System.currentTimeMillis()));
    }
    private static String join(JSONArray values) throws org.json.JSONException {
        List<String> result = new ArrayList<>();
        if (values != null) for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return String.join(",", result);
    }
    private static boolean same(Word first, Word second) {
        if (first == null || second == null) return first == second;
        try { return first.json().toString().equals(second.json().toString()); }
        catch (org.json.JSONException ignored) { return false; }
    }
    private static String encode(Map<String, Word> all) {
        try {
            JSONArray words = new JSONArray(); for (Word word : all.values()) words.put(word.json());
            return new JSONObject().put("version", 1).put("words", words).toString();
        } catch (org.json.JSONException error) { throw new IllegalStateException("词库编码失败"); }
    }
    private static boolean write(Context context, Map<String, Word> all) {
        String encoded = encode(all);
        if (!SecureLocalStore.write(context, PREFS, KEY, encoded)) return false;
        long revision = Math.max(System.currentTimeMillis(), cachedRevision + 1);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("revision", revision).apply();
        cache = Collections.unmodifiableMap(new LinkedHashMap<>(all)); cachedRevision = revision;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putLong("user_words_revision", revision).apply();
        return true;
    }
    private CustomWordStore() {}
}
