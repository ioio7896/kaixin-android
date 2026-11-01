package com.example.offlineime;

import java.util.List;
import java.util.Locale;

/** A small exact-reading map keeps emoji offers predictable and out of word ranking. */
final class EmojiSuggestions {
    private static final String[][] RULES = {
            {"kaixin", "开心", "😊", "[大笑]"},
            {"gaoxing", "高兴", "😄", "[愉快]"},
            {"daxiao", "大笑", "😆", "[大笑]"},
            {"haha", "哈哈", "😂", "[呲牙]"},
            {"weixiao", "微笑", "🙂", "[微笑]"},
            {"ku", "哭", "😭", "[大哭]"},
            {"nanguo", "难过", "😢", "[难过]"},
            {"shengqi", "生气", "😠", "[发怒]"},
            {"haixiu", "害羞", "☺️", "[害羞]"},
            {"jingya", "惊讶", "😮", "[惊讶]"},
            {"dianzan", "点赞", "👍", "[好的]"},
            {"jiayou", "加油", "💪", "[加油]"},
            {"xiexie", "谢谢", "🙏", "[抱拳]"},
            {"aixin", "爱心", "❤️", "[亲亲]"},
            {"shengri", "生日", "🎂", "[大笑]"},
            {"wanan", "晚安", "🌙", "[睡]"}
    };

    private EmojiSuggestions() { }

    static String[] forQuery(String query, List<PinyinDictionary.Entry> words, boolean includeWeChat) {
        if (query == null || query.isEmpty()) return new String[0];
        String code = query.toLowerCase(Locale.ROOT).replace("'", "").replace(" ", "");
        for (String[] rule : RULES) {
            if (code.equals(rule[0]) || code.equals(t9(rule[0])) && hasFullWord(words, rule[1]))
                return includeWeChat && WeChatEmojiCatalog.contains(rule[3])
                        ? new String[]{rule[2], rule[3]} : new String[]{rule[2]};
        }
        return new String[0];
    }

    static boolean isSuggestion(String value) {
        if (value == null) return false;
        for (String[] rule : RULES)
            if (value.equals(rule[2]) || value.equals(rule[3])) return true;
        return false;
    }

    private static boolean hasFullWord(List<PinyinDictionary.Entry> words, String word) {
        for (int i = 0; i < Math.min(3, words.size()); i++)
            if (word.equals(words.get(i).word)) return true;
        return false;
    }

    private static String t9(String code) {
        StringBuilder digits = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c <= 'c') digits.append('2');
            else if (c <= 'f') digits.append('3');
            else if (c <= 'i') digits.append('4');
            else if (c <= 'l') digits.append('5');
            else if (c <= 'o') digits.append('6');
            else if (c <= 's') digits.append('7');
            else if (c <= 'v') digits.append('8');
            else digits.append('9');
        }
        return digits.toString();
    }
}
