package com.example.offlineime;

import java.util.LinkedHashMap;
import java.util.Map;

/** WeChat-style faces are shown and committed as bracketed text markers. */
final class WeChatEmojiCatalog {
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        // Familiar faces first. The remaining faces retain a predictable catalog order.
        String[] names = {"大笑", "微笑", "呲牙", "捂脸", "偷笑", "大哭", "流泪", "害羞", "得意",
                "调皮", "憨笑", "坏笑", "奸笑", "亲亲", "可怜", "愉快", "耶",
                "撇嘴", "色", "发呆", "闭嘴", "睡", "尴尬", "发怒", "惊讶",
                "难过", "囧", "抓狂", "吐", "白眼", "傲慢", "困", "惊恐",
                "悠闲", "咒骂", "疑问", "嘘", "晕", "衰", "骷髅", "敲打",
                "再见", "擦汗", "抠鼻", "鼓掌", "右哼哼", "鄙视", "委屈", "快哭了",
                "阴险", "嘿哈", "机智", "皱眉", "吃瓜", "加油", "抱拳", "汗", "天啊",
                "Emm", "社会社会", "旺柴", "好的", "打脸", "哇", "翻白眼", "666",
                "让我看看", "叹气", "苦涩", "裂开", "笑脸", "生病", "脸红", "恐惧",
                "失望", "无语", "破涕为笑"};
        for (String name : names) NAMES.put("[" + name + "]", name);
    }

    private WeChatEmojiCatalog() { }

    static String[] values() { return NAMES.keySet().toArray(new String[0]); }
    static boolean contains(String token) { return NAMES.containsKey(token); }
    static String name(String token) { return NAMES.get(token); }
    static int trailingTokenLength(CharSequence text) {
        if (text == null || text.length() == 0 || text.charAt(text.length() - 1) != ']') return 0;
        int open = -1;
        for (int i = text.length() - 2; i >= 0 && i >= text.length() - 16; i--) {
            if (text.charAt(i) == '[') { open = i; break; }
        }
        if (open < 0) return 0;
        String token = text.subSequence(open, text.length()).toString();
        return contains(token) ? token.length() : 0;
    }
}
