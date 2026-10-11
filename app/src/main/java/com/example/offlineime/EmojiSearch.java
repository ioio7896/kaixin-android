package com.example.offlineime;

import java.util.LinkedHashSet;
import java.util.Locale;

/** Offline keywords, category names, Unicode names and literal glyph lookup. */
final class EmojiSearch {
    private static final String[][] TERMS = {
        {"开心 高兴 笑 哈哈 smile happy", "😀 😃 😄 😁 😆 😂 🤣 😊 😇 🙂"},
        {"伤心 难过 哭 crying sad", "😢 😭 😥 😞 😔 🥺"},
        {"喜欢 爱 心 heart love", "❤️ 🥰 😍 😘 💕 💖 💗 💘 💝"},
        {"生气 愤怒 angry", "😠 😡 🤬 💢"}, {"赞 棒 好 同意 thumb like", "👍 👏 💯 ✅"},
        {"谢谢 感谢 拜托 prayer thanks", "🙏 🤝 💐"}, {"抱抱 拥抱 hug", "🤗 🫂"},
        {"思考 疑问 question think", "🤔 🧐 ❓"}, {"睡觉 困 sleep", "😴 💤 🥱"},
        {"生日 蛋糕 birthday cake", "🎂 🎉 🎁 🎈"}, {"庆祝 恭喜 新年 party", "🎉 🎊 🧧 🎆"},
        {"狗 dog", "🐶 🐕"}, {"猫 cat", "🐱 🐈"}, {"熊猫 panda", "🐼"},
        {"花 玫瑰 flower rose", "🌹 🌸 🌷 💐 🌻"}, {"咖啡 coffee", "☕"},
        {"吃饭 饭 food", "🍚 🍜 🍱 🍽️"}, {"水果 fruit", "🍎 🍊 🍌 🍉 🍇 🍓"},
        {"啤酒 干杯 beer", "🍺 🍻 🥂"}, {"工作 电脑 computer", "💻 🖥️ ⌨️"},
        {"钱 红包 money", "💰 💵 💴 🧧"}, {"电话 手机 phone", "📱 📞 ☎️"},
        {"天气 太阳 雨 weather", "☀️ ☁️ 🌧️ 🌈 ☔"}, {"火 热 fire", "🔥"},
        {"加油 力量 strong", "💪 ✊ 🤜"}, {"再见 招手 wave", "👋"},
        {"胜利 剪刀 victory", "✌️"}, {"好的 确认 ok", "👌 ✅"},
        {"中国 国旗 china flag", "🇨🇳"}, {"家庭 家人 family", "👨‍👩‍👧‍👦 👪"}
    };
    private EmojiSearch() { }
    static String[] find(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        if (q.isEmpty()) return EmojiCatalog.values("最近");
        for (String[] term : TERMS) {
            boolean matches = term[0].contains(q);
            if (matches) for (String value : term[1].split(" ")) found.add(value);
        }
        for (String category : EmojiCatalog.CATEGORIES) for (String value : EmojiCatalog.values(category)) {
            String name = Character.getName(value.codePointAt(0));
            if (category.contains(q) || value.contains(q) || name != null && name.toLowerCase(Locale.ROOT).contains(q)) found.add(value);
            if (found.size() >= 160) return found.toArray(new String[0]);
        }
        return found.toArray(new String[0]);
    }
    static String description(String emoji) {
        for (String[] term : TERMS) for (String value : term[1].split(" "))
            if (emoji.equals(value)) return term[0].split(" ")[0] + " " + emoji;
        return emoji;
    }
    static String[] skinTones(String emoji) {
        String bases = "👍 👎 👋 🤚 🖐 ✋ 🖖 👌 🤏 ✌ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝ ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🙏 💪";
        String plain = emoji.replace("\uFE0F", "").replaceAll("[\\x{1F3FB}-\\x{1F3FF}]", "");
        boolean supported = false;
        for (String base : bases.split(" ")) if (base.equals(plain)) supported = true;
        if (!supported) return new String[0];
        String[] values = new String[6]; values[0] = plain;
        for (int i = 1; i <= 5; i++) values[i] = plain + new String(Character.toChars(0x1F3FA + i));
        return values;
    }
}
