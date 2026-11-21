package com.example.offlineime;

import android.content.Context;
import android.graphics.Paint;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Unicode emoji, search annotations and device-supported glyphs. */
final class EmojiCatalog {
    static final String[] CATEGORIES = {"最近", "收藏", "颜文字", "笑脸", "人物", "动物", "食物",
            "旅行", "活动", "物品", "符号", "旗帜"};
    private static final String[] DEFAULTS =
            "😀 😂 🥰 😍 😊 😎 🤔 🥺 😭 👍 🙏 👏 🎉 ❤️ ✨ 🔥 💪 🤝 🤗 😘 😅 🤣 😋 😴 🌹 🎂 ☕ 🍻 ✅ 💯".split(" ");
    private static final String[] KAOMOJI = new String[]{
                "(^_^)", "(≧▽≦)", "(＾▽＾)", "(*^▽^*)", "(｡•̀ᴗ-)✧", "(๑•̀ㅂ•́)و✧",
                "٩(๑❛ᴗ❛๑)۶", "ヽ(✿ﾟ▽ﾟ)ノ", "(●°u°●)​", "(｡･ω･｡)", "(◕‿◕)", "(•̀ᴗ•́)و",
                "(〃▽〃)", "(⁄ ⁄•⁄ω⁄•⁄ ⁄)", "(๑´ㅂ`๑)", "(｡♥‿♥｡)", "(づ｡◕‿‿◕｡)づ", "(つ≧▽≦)つ",
                "♡(ӦｖӦ｡)", "( ˘ ³˘)♥", "(｡•́‿•̀｡)", "(っ˘ω˘ς)", "(T_T)", "(╥﹏╥)",
                "(；ω；)", "(ಥ_ಥ)", "(｡•́︿•̀｡)", "(つω`｡)", "(ノ_<。)", "( ´•̥̥̥ω•̥̥̥` )",
                "(￣へ￣)", "(＃`Д´)", "(╬ Ò﹏Ó)", "(눈_눈)", "(¬_¬)", "(￣︿￣)",
                "(╯°□°）╯︵ ┻━┻", "┬─┬ノ( º _ ºノ)", "Σ(°△°|||)", "(⊙_⊙)", "(°ロ°) !", "(・・;)",
                "(・_・ヾ", "(・・?)", "¯\\_(ツ)_/¯", "┐(￣ヘ￣)┌", "(￣▽￣;)", "(￢_￢)",
                "(－_－) zzZ", "(∪｡∪)｡｡｡zzZ", "(￣o￣) . z Z", "(－ω－) zzZ", "ヾ(＾∇＾)", "(・ω・)ノ",
                "( ´ ▽ ` )ﾉ", "ヾ(￣▽￣)Bye~", "m(_ _)m", "(人´∀`)", "(o_ _)ﾉ彡☆", "(ง •̀_•́)ง",
                "ᕦ(ò_óˇ)ᕤ", "( •̀ ω •́ )✧", "(=^･ω･^=)", "ฅ^•ﻌ•^ฅ", "ʕ•ᴥ•ʔ", "／(･ × ･)＼",
                "U・ᴥ・U", "(•ө•)♡", "♪(^∇^*)", "ヽ(；▽；)ノ", "(๑• . •๑)", "(๑˃̵ᴗ˂̵)و"};
    private static final Set<String> TEXT_FACES = new HashSet<>(java.util.Arrays.asList(KAOMOJI));
    private static final ThreadLocal<Paint> GLYPH_PAINT = ThreadLocal.withInitial(Paint::new);
    private static final Map<String, Boolean> GLYPHS = new ConcurrentHashMap<>();
    private static volatile EmojiCatalog instance;

    static final class Item {
        final String category, glyph, name, searchable, base;
        Item(String[] row) {
            category = row[0]; glyph = row[1]; name = row[2]; base = row[6];
            searchable = (name + " | " + row[3] + " | " + row[4] + " | " + row[5] + " | " + category)
                    .toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Map<String, List<String>> groups = new HashMap<>(), tones = new HashMap<>();
    private final Map<String, List<String>> visibleGroups = new ConcurrentHashMap<>();

    static EmojiCatalog get(Context context) {
        EmojiCatalog value = instance;
        if (value != null) return value;
        synchronized (EmojiCatalog.class) {
            if (instance == null) instance = new EmojiCatalog(context.getApplicationContext());
            return instance;
        }
    }

    private EmojiCatalog(Context context) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                context.getAssets().open("emoji/catalog.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                String[] row = line.split("\\t", -1);
                if (row.length != 7) continue;
                Item item = new Item(row);
                items.put(key(item.glyph), item);
                if (item.base.equals(item.glyph)) {
                    groups.computeIfAbsent(item.category, ignored -> new ArrayList<>()).add(item.glyph);
                } else {
                    tones.computeIfAbsent(item.base, ignored -> new ArrayList<>()).add(item.glyph);
                }
            }
        } catch (IOException error) {
            Log.w("EmojiCatalog", "Bundled emoji catalog could not be read", error);
        }
    }

    static String key(String glyph) { return glyph.replace("\uFE0F", ""); }

    Iterable<Item> items() { return items.values(); }

    void prewarm() {
        for (String category : groups.keySet()) values(category);
    }

    static boolean visible(String glyph) {
        return !glyph.isEmpty() && (isTextFace(glyph)
                || GLYPHS.computeIfAbsent(glyph, value -> GLYPH_PAINT.get().hasGlyph(value)));
    }

    static boolean isTextFace(String glyph) {
        return TEXT_FACES.contains(glyph) || WeChatEmojiCatalog.contains(glyph);
    }

    List<String> values(String category) {
        if ("颜文字".equals(category)) return java.util.Arrays.asList(KAOMOJI);
        return visibleGroups.computeIfAbsent(category, group -> {
            List<String> all = "最近".equals(group) ? java.util.Arrays.asList(DEFAULTS) : groups.get(group);
            List<String> result = new ArrayList<>();
            if (all != null) for (String glyph : all) if (visible(glyph)) result.add(glyph);
            return java.util.Collections.unmodifiableList(result);
        });
    }

    String description(String glyph) {
        Item item = items.get(key(glyph));
        if (item != null) return item.name;
        String marker = WeChatEmojiCatalog.name(glyph);
        return marker == null ? glyph : marker;
    }

    String base(String glyph) {
        Item item = items.get(key(glyph));
        return item == null ? glyph : item.base;
    }

    boolean hasToneChoices(String glyph) { return tones.containsKey(glyph); }

    List<String> skinTones(String glyph) {
        String base = base(glyph);
        List<String> variants = tones.get(base);
        if (variants == null) return java.util.Collections.emptyList();
        List<String> result = new ArrayList<>();
        if (visible(base)) result.add(base);
        for (String variant : variants) if (visible(variant)) result.add(variant);
        return result.size() > 1 ? result : java.util.Collections.emptyList();
    }

    static String categoryLabel(String category) {
        switch (category) {
            case "笑脸": return "😀";
            case "人物": return "👤";
            case "动物": return "🐶";
            case "食物": return "🍔";
            case "旅行": return "🚗";
            case "活动": return "⚽";
            case "物品": return "💡";
            case "符号": return "♥";
            default: return category;
        }
    }
}
