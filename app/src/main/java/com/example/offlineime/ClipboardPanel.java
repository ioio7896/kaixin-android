package com.example.offlineime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.GridLayout;
import android.widget.FrameLayout;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** Clipboard presentation only; storage and permission policy stay in the service. */
final class ClipboardPanel extends LinearLayout {
    private final KeyboardView.Callback callback;
    private final KeyboardTheme theme;
    private final boolean dark;
    private final android.content.SharedPreferences renderSettings;
    private final java.util.function.BiConsumer<View, RoundedCardPopup> menus;
    ClipboardPanel(Context context, KeyboardView.Callback callback, KeyboardTheme theme,
                   boolean dark, java.util.function.BiConsumer<View, RoundedCardPopup> menus) {
        super(context); setOrientation(VERTICAL);
        this.callback = callback; this.theme = theme; this.dark = dark; this.menus = menus;
        renderSettings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    public void showClipboardHistory(List<ClipboardHistoryStore.Entry> values, String filter,
                                    String query, boolean confirmingClear, boolean undoAvailable,
                                    boolean multiSelect, Set<String> selected) {
        removeAllViews();
        boolean empty = values == null || values.isEmpty();
        boolean searching = query != null && !query.isEmpty();
        LinearLayout header = clipboardHeader();
        if (!multiSelect)
            addClipboardIcon(header, R.drawable.ic_panel_search, searching ? "搜索中" : "搜索", "搜索剪贴板", true);
        // Clear applies to the entire history, even if the active filter has no results.
        boolean hasHistory = ((OfflineImeService) getContext()).hasClipboardHistory();
        if (multiSelect) {
            addPanelTextAction(header, "删除(" + selected.size() + ")", "删除所选剪贴板", !selected.isEmpty());
            addPanelTextAction(header, "完成", "退出剪贴板多选");
        } else if (undoAvailable) addPanelTextAction(header, "撤销清空", "撤销清空剪贴板");
        else if (confirmingClear) {
            addPanelTextAction(header, "确定清空", "确认清空剪贴板");
            addPanelTextAction(header, "取消", "取消清空剪贴板");
        } else {
            addPanelTextAction(header, "多选", "剪贴板多选", hasHistory);
            addClipboardIcon(header, R.drawable.ic_panel_clear, "清空", "清空剪贴板", hasHistory);
        }
        addView(header, new LayoutParams(-1, dp(36)));

        LinearLayout filters = new LinearLayout(getContext());
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(dp(10), 0, dp(10), dp(4));
        addClipboardFilter(filters, "全部", "all", filter);
        addClipboardFilter(filters, "固定", "pinned", filter);
        addClipboardFilter(filters, "最近", "recent", filter);
        addView(filters, new LayoutParams(-1, dp(32)));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(VERTICAL);
        list.setPadding(dp(10), dp(2), dp(10), dp(2));
        if (empty) {
            list.setGravity(Gravity.CENTER);
            String title = searching ? "没有匹配的剪贴板内容"
                    : "pinned".equals(filter) ? "暂无固定内容"
                    : "recent".equals(filter) ? "暂无最近内容" : "暂无剪贴板内容";
            String hint = searching ? "试试其他关键词，或清除搜索"
                    : "pinned".equals(filter) ? "长按内容卡片，可固定常用文字"
                    : "复制文字后，打开剪贴板即可查看";
            addClipboardEmptyState(list, title, hint);
        } else for (ClipboardHistoryStore.Entry entry : values)
            addClipboardRow(list, entry, multiSelect, selected.contains(entry.text));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
        addClipboardBackButton();
    }

    private LinearLayout clipboardHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), 0, dp(8), 0);
        TextView title = new TextView(getContext());
        title.setText("剪贴板");
        title.setTextSize(16);
        title.setTextColor(theme.text);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        header.addView(title, new LayoutParams(0, -2, 1f));
        return header;
    }

    private android.widget.ImageButton addClipboardIcon(LinearLayout row, int drawable,
                                                        String label, String action, boolean enabled) {
        android.widget.ImageButton button = new android.widget.ImageButton(getContext());
        button.setImageResource(drawable);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setColorFilter(theme.text, android.graphics.PorterDuff.Mode.SRC_IN);
        button.setBackground(panelButtonBackground(false));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        button.setOnClickListener(v -> callback.onKey(action));
        row.addView(button, new LayoutParams(dp(40), dp(40)));
        return button;
    }

    private android.graphics.drawable.StateListDrawable panelButtonBackground(boolean selected) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundRect(theme.function, 10));
        states.addState(new int[]{}, roundRect(selected ? blendColor(theme.key, theme.accent, dark ? 0.24f : 0.12f) : Color.TRANSPARENT, 10));
        return states;
    }

    private void addPanelTextAction(LinearLayout row, String label, String action) {
        addPanelTextAction(row, label, action, true);
    }

    private void addPanelTextAction(LinearLayout row, String label, String action, boolean enabled) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(theme.accent);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(panelButtonBackground(false));
        button.setContentDescription(label);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        button.setOnClickListener(v -> callback.onKey(action));
        row.addView(button, new LayoutParams(-2, dp(40)));
    }

    private void addClipboardFilter(LinearLayout row, String label, String value, String active) {
        TextView tab = new TextView(getContext());
        boolean selected = value.equals(active);
        tab.setText(label);
        tab.setTextSize(13);
        tab.setTextColor(selected ? theme.accent : blendColor(theme.text, theme.background, 0.25f));
        tab.setGravity(Gravity.CENTER);
        tab.setSelected(selected);
        tab.setBackground(panelButtonBackground(selected));
        tab.setContentDescription(label);
        tab.setOnClickListener(v -> callback.onKey("剪贴板筛选:" + value));
        LayoutParams params = new LayoutParams(dp(64), dp(30));
        params.setMargins(0, 0, dp(4), 0);
        row.addView(tab, params);
    }

    private void addClipboardEmptyState(LinearLayout parent, String title, String hint) {
        android.widget.ImageView icon = new android.widget.ImageView(getContext());
        icon.setImageResource(R.drawable.ic_toolbar_clipboard);
        icon.setColorFilter(blendColor(theme.text, theme.background, 0.60f));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(icon, new LayoutParams(dp(32), dp(32)));
        TextView message = new TextView(getContext());
        message.setText(title);
        message.setTextSize(14);
        message.setTextColor(theme.text);
        message.setGravity(Gravity.CENTER);
        LayoutParams messageParams = new LayoutParams(-1, -2);
        messageParams.setMargins(0, dp(8), 0, dp(4));
        parent.addView(message, messageParams);
        TextView detail = new TextView(getContext());
        detail.setText(hint);
        detail.setTextSize(11);
        detail.setTextColor(blendColor(theme.text, theme.background, 0.35f));
        detail.setGravity(Gravity.CENTER);
        parent.addView(detail, new LayoutParams(-1, -2));
    }

    private void addClipboardBackButton() {
        TextView back = new TextView(getContext());
        back.setText("‹  返回键盘");
        back.setTextSize(14);
        back.setTextColor(theme.accent);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回键盘");
        back.setBackground(panelButtonBackground(false));
        back.setOnClickListener(v -> callback.onKey("返回"));
        addView(back, new LayoutParams(-1, dp(36)));
    }

    public void showClipboardEnablePrompt() {
        removeAllViews();
        addView(clipboardHeader(), new LayoutParams(-1, dp(40)));
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout explanation = new LinearLayout(getContext());
        explanation.setOrientation(VERTICAL);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(dp(16), dp(8), dp(16), dp(8));
        addClipboardEmptyState(explanation, "剪贴板历史默认关闭", "开启后，复制的文字可在这里再次粘贴");
        TextView privacy = new TextView(getContext());
        int limit = getIntPreference(renderSettings, "clipboard_limit", 10);
        int days = getIntPreference(renderSettings, "clipboard_retention_days", 7);
        privacy.setText("仅在本机加密保存，打开面板时读取当前文字。密码、隐私模式和敏感内容会跳过。普通记录最多保留 "
                + limit + " 条、" + (days == 0 ? "永久" : days + " 天") + "，固定内容持续保留。");
        privacy.setTextSize(11);
        privacy.setTextColor(blendColor(theme.text, theme.background, 0.25f));
        privacy.setGravity(Gravity.CENTER);
        LayoutParams detailParams = new LayoutParams(-1, -2);
        detailParams.setMargins(0, dp(10), 0, 0);
        explanation.addView(privacy, detailParams);
        scroll.addView(explanation, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
        LinearLayout actions = new LinearLayout(getContext());
        actions.setGravity(Gravity.CENTER);
        addPanelTextAction(actions, "开启剪贴板历史", "启用剪贴板历史");
        addPanelTextAction(actions, "暂不开启", "暂不开启剪贴板");
        addView(actions, new LayoutParams(-1, dp(40)));
        addClipboardBackButton();
    }

    private void addClipboardRow(LinearLayout list, ClipboardHistoryStore.Entry entry,
                                 boolean multiSelect, boolean selected) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(9), dp(4), dp(9));
        GradientDrawable cardBackground = roundRect(selected
                ? blendColor(theme.key, theme.accent, dark ? 0.30f : 0.16f) : theme.key, 12);
        if (selected) cardBackground.setStroke(dp(2), theme.accent);
        card.setBackground(cardBackground);
        LinearLayout preview = new LinearLayout(getContext());
        preview.setOrientation(VERTICAL);
        TextView content = new TextView(getContext());
        content.setText(entry.text);
        content.setTextSize(14);
        content.setTextColor(theme.text);
        content.setMaxLines(2);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView metadata = new TextView(getContext());
        metadata.setText((entry.pinned ? "已固定 · " : "") + clipboardTime(entry.timestamp));
        metadata.setTextSize(10);
        metadata.setTextColor(blendColor(theme.text, theme.key, 0.40f));
        preview.addView(content, new LayoutParams(-1, -2));
        LayoutParams metadataParams = new LayoutParams(-1, -2);
        metadataParams.setMargins(0, dp(4), 0, 0);
        preview.addView(metadata, metadataParams);
        card.addView(preview, new LayoutParams(0, -2, 1f));
        card.setContentDescription((multiSelect ? (selected ? "已选择：" : "选择：") : "粘贴剪贴板内容：")
                + entry.text + (entry.pinned ? "，已固定" : ""));
        card.setOnClickListener(v -> callback.onKey(multiSelect
                ? "选择剪贴板:" + entry.text : "粘贴历史" + entry.text));
        card.setOnLongClickListener(v -> {
            if (multiSelect) callback.onKey("选择剪贴板:" + entry.text);
            else showClipboardItemMenu(card, entry);
            return true;
        });
        if (!multiSelect) {
            android.widget.ImageButton more = addClipboardIcon(card, R.drawable.ic_panel_more,
                    "剪贴板内容操作：" + entry.text, "", true);
            more.setOnClickListener(v -> showClipboardItemMenu(more, entry));
            more.setTooltipText("固定或删除");
        }
        LayoutParams params = new LayoutParams(-1, -2);
        params.setMargins(0, dp(3), 0, dp(3));
        list.addView(card, params);
    }

    private void showClipboardItemMenu(View anchor, ClipboardHistoryStore.Entry entry) {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "剪贴板操作", theme);
        menu.item(entry.pinned ? "取消固定" : "固定", entry.pinned ? null : "保留这条剪贴板内容", null,
                false, false, () -> callback.onKey("固定:" + entry.text));
        menu.divider().item("删除", null, null, false, true,
                () -> callback.onKey("删除剪贴板:" + entry.text));
        menus.accept(anchor, menu);
    }

    private String clipboardTime(long timestamp) {
        long age = Math.max(0, System.currentTimeMillis() - timestamp);
        if (age < 60_000L) return "刚刚";
        if (age < 60L * 60_000L) return (age / 60_000L) + " 分钟前";
        if (age < 24L * 60 * 60_000L) return (age / (60L * 60_000L)) + " 小时前";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(timestamp));
    }

    private static int blendColor(int base, int overlay, float amount) {
        int alpha = Math.round(255 * amount);
        int red = ((base >> 16) & 0xff) * (255 - alpha) / 255
                + ((overlay >> 16) & 0xff) * alpha / 255;
        int green = ((base >> 8) & 0xff) * (255 - alpha) / 255
                + ((overlay >> 8) & 0xff) * alpha / 255;
        int blue = (base & 0xff) * (255 - alpha) / 255
                + (overlay & 0xff) * alpha / 255;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private static int getIntPreference(android.content.SharedPreferences prefs, String key, int fallback) {
        try { return prefs.getInt(key, fallback); }
        catch (ClassCastException ignored) {
            String value = prefs.getString(key, null);
            if (value != null) try { return Integer.parseInt(value); } catch (NumberFormatException ignoredNumber) { }
            return fallback;
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
