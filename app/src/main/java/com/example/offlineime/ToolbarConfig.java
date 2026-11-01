package com.example.offlineime;

/** Shared slot definitions for the keyboard and its settings. Empty slots are hidden. */
final class ToolbarConfig {
    static final String[] LABELS = {"不显示", "表情", "剪贴板", "键盘布局", "手写", "文本编辑", "快捷短语", "日期时间", "设置", "符号", "人名候选"};
    static final String[] VALUES = {"", "😊", "剪贴板", "键盘布局", "手写", "工具", "快捷短语", "日期时间", "设置", "符号", "人名模式"};
    static final String[] DEFAULTS = {"😊", "剪贴板", "键盘布局", "工具", "快捷短语", "", "日期时间"};

    static void migrateFormerDefaults(android.content.SharedPreferences preferences) {
        if (preferences.getBoolean("toolbar_quick_phrase_date_migrated", false)) return;
        boolean formerDefaults = "😊".equals(preferences.getString("toolbar_item_0", "😊"))
                && "剪贴板".equals(preferences.getString("toolbar_item_1", "剪贴板"))
                && "键盘布局".equals(preferences.getString("toolbar_item_2", "键盘布局"))
                && "工具".equals(preferences.getString("toolbar_item_3", "工具"))
                && preferences.getString("toolbar_item_4", "").isEmpty()
                && preferences.getString("toolbar_item_5", "").isEmpty()
                && preferences.getString("toolbar_item_6", "").isEmpty();
        android.content.SharedPreferences.Editor edit = preferences.edit()
                .putBoolean("toolbar_quick_phrase_date_migrated", true);
        if (formerDefaults) edit.putString("toolbar_item_4", "快捷短语")
                .putString("toolbar_item_6", "日期时间");
        edit.apply();
    }

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
