package com.example.offlineime;

/** Shared slot definitions for the keyboard and its settings. Empty slots are hidden. */
final class ToolbarConfig {
    static final String[] LABELS = {"不显示", "表情", "剪贴板", "键盘布局", "手写", "文本编辑", "设置", "符号", "人名候选"};
    static final String[] VALUES = {"", "😊", "剪贴板", "键盘布局", "手写", "工具", "设置", "符号", "人名模式"};
    static final String[] DEFAULTS = {"😊", "剪贴板", "键盘布局", "工具", "", "", ""};

    static String normalize(String value, int slot) {
        if ("9键".equals(value) || "ABC".equals(value) || "符".equals(value) || "字".equals(value))
            return "键盘布局";
        // Replace the former default word-creation slot without reviving it elsewhere.
        if ("造词".equals(value)) return slot == 3 ? "工具" : "";
        for (String allowed : VALUES) if (allowed.equals(value)) return allowed;
        return DEFAULTS[slot];
    }

    private ToolbarConfig() {}
}
