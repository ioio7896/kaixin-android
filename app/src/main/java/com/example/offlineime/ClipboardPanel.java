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
    private static final int HEADER_HEIGHT_DP = 42;
    private final KeyboardView.Callback callback;
    private final KeyboardTheme theme;
    private final boolean dark;
    private final android.content.SharedPreferences renderSettings;
    private final java.util.function.BiConsumer<View, RoundedCardPopup> menus;
    private final java.util.function.BooleanSupplier expandedState;
    private final Runnable toggleExpanded;
    private TextView expandButton;

    public ClipboardPanel(Context context) {
        this(context, KeyboardView.requireService(context), KeyboardTheme.from(context, false),
                KeyboardTheme.resolveDark(context), (anchor, menu) -> menu.show(anchor),
                () -> false, () -> { });
    }

    ClipboardPanel(Context context, KeyboardView.Callback callback, KeyboardTheme theme,
                   boolean dark, java.util.function.BiConsumer<View, RoundedCardPopup> menus) {
        this(context, callback, theme, dark, menus, () -> false, () -> { });
    }

    ClipboardPanel(Context context, KeyboardView.Callback callback, KeyboardTheme theme,
                   boolean dark, java.util.function.BiConsumer<View, RoundedCardPopup> menus,
                   java.util.function.BooleanSupplier expandedState, Runnable toggleExpanded) {
        super(context); setOrientation(VERTICAL);
        this.callback = callback; this.theme = theme; this.dark = dark; this.menus = menus;
        this.expandedState = expandedState; this.toggleExpanded = toggleExpanded;
        renderSettings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    public void showClipboardHistory(List<ClipboardHistoryStore.Entry> values, String filter,
                                    String query, boolean confirmingClear, boolean undoAvailable,
                                    boolean multiSelect, Set<String> selected) {
        removeAllViews();
        expandButton = null;
        boolean empty = values == null || values.isEmpty();
        boolean searching = query != null && !query.isEmpty();
        boolean twoCol = isTwoColumnEnabled();
        LinearLayout header = clipboardControlHeader();
        if (searching && !multiSelect && !undoAvailable && !confirmingClear) {
            addInlineSearchChip(header, query);
        } else {
            addClipboardFilters(header, filter);
            if (!multiSelect)
                addClipboardIcon(header, R.drawable.ic_panel_search, "搜索", "搜索剪贴板", true, 32, 7);
        }
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
            if (canTwoColumn()) {
                addColumnToggle(header, twoCol, () -> showClipboardHistory(
                        values, filter, query, confirmingClear, undoAvailable, multiSelect, selected));
            }
            if (!searching) {
                addPanelTextAction(header, "多选", "剪贴板多选", hasHistory && !empty);
                addClipboardIcon(header, R.drawable.ic_panel_clear, "清空", "清空剪贴板", hasHistory, 32, 7);
            }
        }
        addExpandToggle(header);
        addView(header, new LayoutParams(-1, dp(HEADER_HEIGHT_DP)));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(VERTICAL);
        list.setPadding(dp(6), dp(2), dp(6), dp(4));
        if (empty) {
            list.setGravity(Gravity.CENTER);
            String title = searching ? "没有匹配的剪贴板内容"
                    : "pinned".equals(filter) ? "暂无固定内容"
                    : "recent".equals(filter) ? "暂无最近内容" : "暂无剪贴板内容";
            String hint = searching ? "试试其他关键词，或清除搜索"
                    : "pinned".equals(filter) ? "长按内容卡片，可固定常用文字"
                    : "复制文字后，打开剪贴板即可查看";
            addClipboardEmptyState(list, title, hint);
        } else {
            if (!twoCol) {
                for (int i = 0; i < values.size(); i++) {
                    ClipboardHistoryStore.Entry entry = values.get(i);
                    addClipboardRow(list, entry, i, multiSelect, selected.contains(entry.text), false);
                }
            } else {
                for (int i = 0; i < values.size(); i += 2) {
                    LinearLayout pair = new LinearLayout(getContext());
                    pair.setOrientation(HORIZONTAL);
                    LinearLayout left = new LinearLayout(getContext());
                    left.setOrientation(VERTICAL);
                    ClipboardHistoryStore.Entry first = values.get(i);
                    addClipboardRow(left, first, i, multiSelect, selected.contains(first.text), true);
                    LayoutParams lpLeft = new LayoutParams(0, -2, 1f);
                    lpLeft.setMargins(0, 0, dp(3), 0);
                    pair.addView(left, lpLeft);
                    LinearLayout right = new LinearLayout(getContext());
                    right.setOrientation(VERTICAL);
                    if (i + 1 < values.size()) {
                        ClipboardHistoryStore.Entry second = values.get(i + 1);
                        addClipboardRow(right, second, i + 1, multiSelect, selected.contains(second.text), true);
                    }
                    LayoutParams lpRight = new LayoutParams(0, -2, 1f);
                    lpRight.setMargins(dp(3), 0, 0, 0);
                    pair.addView(right, lpRight);
                    list.addView(pair, new LayoutParams(-1, -2));
                }
            }
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
    }

    public void showQuickPhrases(List<QuickPhraseStore.Entry> phrases, String status) {
        removeAllViews();
        expandButton = null;
        boolean twoCol = isTwoColumnEnabled();
        LinearLayout header = clipboardHeader("快捷短语");
        if (canTwoColumn()) addColumnToggle(header, twoCol, () -> showQuickPhrases(phrases, status));
        addExpandToggle(header);
        addView(header, new LayoutParams(-1, dp(HEADER_HEIGHT_DP)));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(VERTICAL);
        list.setPadding(dp(6), dp(2), dp(6), dp(4));
        if (phrases == null || phrases.isEmpty()) {
            list.setGravity(Gravity.CENTER);
            addClipboardEmptyState(list, status.isEmpty() ? "暂无快捷短语" : status, "请在设置首页的“快捷短语”中添加；点按短语输入，长按可编辑或删除",
                    R.drawable.ic_toolbar_phrases);
        } else {
            if (!twoCol) {
                for (QuickPhraseStore.Entry phrase : phrases) addQuickPhraseRow(list, phrase, false);
            } else {
                for (int i = 0; i < phrases.size(); i += 2) {
                    LinearLayout pair = new LinearLayout(getContext());
                    pair.setOrientation(HORIZONTAL);
                    LinearLayout left = new LinearLayout(getContext());
                    left.setOrientation(VERTICAL);
                    addQuickPhraseRow(left, phrases.get(i), true);
                    LayoutParams lpLeft = new LayoutParams(0, -2, 1f);
                    lpLeft.setMargins(0, 0, dp(3), 0);
                    pair.addView(left, lpLeft);
                    LinearLayout right = new LinearLayout(getContext());
                    right.setOrientation(VERTICAL);
                    if (i + 1 < phrases.size()) addQuickPhraseRow(right, phrases.get(i + 1), true);
                    LayoutParams lpRight = new LayoutParams(0, -2, 1f);
                    lpRight.setMargins(dp(3), 0, 0, 0);
                    pair.addView(right, lpRight);
                    list.addView(pair, new LayoutParams(-1, -2));
                }
            }
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
    }

    private boolean canTwoColumn() {
        return KeyboardPlacement.width(getContext(), renderSettings) >= 320;
    }

    private boolean isTwoColumnEnabled() {
        int width = KeyboardPlacement.width(getContext(), renderSettings);
        if (width < 320) return false;
        return renderSettings.getBoolean("clipboard_two_column", width >= 600);
    }

    private void addInlineSearchChip(LinearLayout header, String query) {
        LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(roundRect(theme.key, 10));
        TextView text = new TextView(getContext());
        text.setText(getContext().getString(R.string.panel_search_query, query));
        text.setTextSize(13);
        text.setTextColor(theme.text);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(8), 0, dp(4), 0);
        text.setContentDescription("搜索剪贴板，当前：" + query);
        text.setOnClickListener(v -> callback.onKey("搜索剪贴板"));
        chip.addView(text, new LayoutParams(0, -1, 1f));
        TextView clear = new TextView(getContext());
        clear.setText("×");
        clear.setTextSize(18);
        clear.setTextColor(blendColor(theme.text, theme.key, 0.25f));
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("清除剪贴板搜索");
        clear.setOnClickListener(v -> callback.onKey("清除剪贴板搜索"));
        chip.addView(clear, new LayoutParams(dp(30), -1));
        LayoutParams lp = new LayoutParams(0, dp(32), 1f);
        lp.setMargins(dp(2), 0, dp(4), 0);
        header.addView(chip, lp);
    }

    private void addColumnToggle(LinearLayout row, boolean twoCol, Runnable rerender) {
        TextView toggle = new TextView(getContext());
        toggle.setText(twoCol ? "单列" : "双列");
        toggle.setTextSize(11);
        toggle.setTextColor(twoCol ? theme.accent : blendColor(theme.text, theme.background, 0.2f));
        toggle.setGravity(Gravity.CENTER);
        toggle.setSingleLine(true);
        toggle.setPadding(dp(5), 0, dp(5), 0);
        toggle.setBackground(panelButtonBackground(twoCol));
        toggle.setContentDescription(twoCol ? "切换为单列列表" : "切换为双列网格");
        toggle.setOnClickListener(v -> {
            renderSettings.edit().putBoolean("clipboard_two_column", !twoCol).apply();
            rerender.run();
        });
        LayoutParams lp = new LayoutParams(-2, dp(32));
        lp.setMargins(dp(1), 0, dp(1), 0);
        row.addView(toggle, lp);
    }

    private void addExpandToggle(LinearLayout row) {
        TextView button = new TextView(getContext());
        button.setTextSize(15);
        button.setTextColor(theme.accent);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> {
            toggleExpanded.run();
            updateExpandButton();
        });
        expandButton = button;
        updateExpandButton();
        row.addView(button, new LayoutParams(dp(32), dp(36)));
    }

    private void updateExpandButton() {
        if (expandButton == null) return;
        boolean expanded = expandedState.getAsBoolean();
        expandButton.setText(expanded ? "⤡" : "⤢");
        expandButton.setBackground(panelButtonBackground(expanded));
        expandButton.setContentDescription(expanded ? "恢复默认面板高度" : "展开面板高度");
    }

    private void addClipboardFilters(LinearLayout header, String selected) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(12));
        LinearLayout filters = new LinearLayout(getContext());
        filters.setGravity(Gravity.CENTER_VERTICAL);
        addClipboardFilter(filters, "全部", "all", selected);
        addClipboardFilter(filters, "固定", "pinned", selected);
        addClipboardFilter(filters, "最近", "recent", selected);
        scroll.addView(filters, new HorizontalScrollView.LayoutParams(-2, -1));
        header.addView(scroll, new LayoutParams(0, -1, 1f));
    }

    private LinearLayout clipboardHeader() {
        return clipboardHeader("剪贴板");
    }

    private LinearLayout clipboardHeader(String titleText) {
        LinearLayout header = clipboardControlHeader();
        TextView title = new TextView(getContext());
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(theme.text);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), 0, 0, 0);
        header.addView(title, new LayoutParams(0, -2, 1f));
        return header;
    }

    private LinearLayout clipboardControlHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(4), 0);
        TextView back = new TextView(getContext());
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(theme.accent);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回键盘");
        back.setBackground(panelButtonBackground(false));
        back.setOnClickListener(v -> callback.onKey("返回"));
        header.addView(back, new LayoutParams(dp(34), dp(36)));
        return header;
    }

    private android.widget.ImageButton addClipboardIcon(LinearLayout row, int drawable,
                                                        String label, String action, boolean enabled) {
        return addClipboardIcon(row, drawable, label, action, enabled, 34, 8);
    }

    private android.widget.ImageButton addClipboardIcon(LinearLayout row, int drawable,
                                                        String label, String action, boolean enabled,
                                                        int sizeDp, int padDp) {
        android.widget.ImageButton button = new android.widget.ImageButton(getContext());
        button.setImageResource(drawable);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        button.setColorFilter(theme.text, android.graphics.PorterDuff.Mode.SRC_IN);
        button.setBackground(panelButtonBackground(false));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        button.setOnClickListener(v -> callback.onKey(action));
        row.addView(button, new LayoutParams(dp(sizeDp), dp(36)));
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
        button.setSingleLine(true);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(panelButtonBackground(false));
        button.setContentDescription(label);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        button.setOnClickListener(v -> callback.onKey(action));
        row.addView(button, new LayoutParams(-2, dp(36)));
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
        LayoutParams params = new LayoutParams(dp(42), dp(30));
        params.setMargins(0, 0, dp(2), 0);
        row.addView(tab, params);
    }

    private void addClipboardEmptyState(LinearLayout parent, String title, String hint) {
        addClipboardEmptyState(parent, title, hint, R.drawable.ic_toolbar_clipboard);
    }

    private void addClipboardEmptyState(LinearLayout parent, String title, String hint, int iconResource) {
        android.widget.ImageView icon = new android.widget.ImageView(getContext());
        icon.setImageResource(iconResource);
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

    public void showClipboardEnablePrompt() {
        removeAllViews();
        expandButton = null;
        LinearLayout header = clipboardHeader();
        addExpandToggle(header);
        addView(header, new LayoutParams(-1, dp(HEADER_HEIGHT_DP)));
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout explanation = new LinearLayout(getContext());
        explanation.setOrientation(VERTICAL);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(dp(16), dp(8), dp(16), dp(8));
        addClipboardEmptyState(explanation, "剪贴板历史已关闭", "开启后，打开面板时可将当前复制的文字保存到历史");
        TextView privacy = new TextView(getContext());
        int limit = getIntPreference(renderSettings, "clipboard_limit", 10);
        int days = getIntPreference(renderSettings, "clipboard_retention_days", 7);
        String retention = days == 0 ? getContext().getString(R.string.clipboard_retention_forever)
                : getContext().getString(R.string.clipboard_retention_days, days);
        privacy.setText(getContext().getString(R.string.clipboard_privacy_summary, limit, retention));
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
    }

    private void addClipboardRow(LinearLayout list, ClipboardHistoryStore.Entry entry,
                                 int index, boolean multiSelect, boolean selected, boolean compact) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(compact ? 8 : 10), dp(6), dp(2), dp(6));
        int rowColor = (index & 1) == 0 ? theme.key
                : blendColor(theme.function, theme.accent, dark ? 0.12f : 0.08f);
        GradientDrawable cardBackground = roundRect(selected
                ? blendColor(rowColor, theme.accent, dark ? 0.30f : 0.16f) : rowColor, 10);
        cardBackground.setStroke(dp(selected ? 2 : 1), selected ? theme.accent
                : blendColor(rowColor, theme.text, dark ? 0.18f : 0.10f));
        card.setBackground(cardBackground);
        LinearLayout preview = new LinearLayout(getContext());
        preview.setOrientation(VERTICAL);
        TextView content = new TextView(getContext());
        content.setText(entry.text);
        content.setTextSize(compact ? 13 : 14);
        content.setTextColor(theme.text);
        content.setMaxLines(2);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView metadata = new TextView(getContext());
        String time = clipboardTime(entry.timestamp);
        if (compact) {
            metadata.setText(entry.pinned ? getContext().getString(R.string.clipboard_entry_pinned_time, time) : time);
        } else {
            int retention = getIntPreference(renderSettings, "clipboard_retention_days", 7);
            String expiry = entry.pinned ? getContext().getString(R.string.clipboard_expiry_pinned)
                    : retention == 0 ? getContext().getString(R.string.clipboard_expiry_forever)
                    : getContext().getString(R.string.clipboard_expiry_days, Math.max(1,
                            (int) Math.ceil((entry.timestamp + retention * 86400000L
                                    - System.currentTimeMillis()) / 86400000.0)));
            metadata.setText(getContext().getString(R.string.clipboard_entry_metadata,
                    time, expiry, entry.text.codePointCount(0, entry.text.length())));
        }
        metadata.setTextSize(compact ? 10 : 10.5f);
        metadata.setSingleLine(true);
        metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metadata.setTextColor(blendColor(theme.text, rowColor, 0.28f));
        preview.addView(content, new LayoutParams(-1, -2));
        LayoutParams metadataParams = new LayoutParams(-1, -2);
        metadataParams.setMargins(0, dp(2), 0, 0);
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
                    "剪贴板内容操作：" + entry.text, "", true, compact ? 28 : 34, compact ? 6 : 8);
            more.setOnClickListener(v -> showClipboardItemMenu(more, entry));
            more.setTooltipText("固定或删除");
        }
        LayoutParams params = new LayoutParams(-1, -2);
        params.setMargins(0, dp(2), 0, dp(2));
        list.addView(card, params);
    }

    private void showClipboardItemMenu(View anchor, ClipboardHistoryStore.Entry entry) {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "剪贴板操作", theme);
        menu.item(entry.pinned ? "取消固定" : "固定", entry.pinned ? null : "保留这条剪贴板内容", null,
                false, false, () -> callback.onKey("固定:" + entry.text));
        menu.item("查看完整内容", "长文本可滚动查看", null, false, false, () -> {
            RoundedCardPopup preview = RoundedCardPopup.keyboard(getContext(), "剪贴板内容", theme);
            ScrollView scroll = new ScrollView(getContext()); TextView text = new TextView(getContext());
            text.setText(entry.text); text.setTextSize(16); text.setTextColor(theme.text); text.setPadding(dp(12), dp(12), dp(12), dp(12));
            scroll.addView(text); scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(240))); preview.content(scroll);
            preview.item("粘贴", null, null, false, false, () -> callback.onKey("粘贴历史" + entry.text)); menus.accept(anchor, preview);
        });
        menu.divider().item("删除", null, null, false, true,
                () -> callback.onKey("删除剪贴板:" + entry.text));
        menus.accept(anchor, menu);
    }

    private void addQuickPhraseRow(LinearLayout list, QuickPhraseStore.Entry phrase, boolean compact) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(compact ? 8 : 10), dp(7), dp(2), dp(7));
        card.setBackground(roundRect(theme.key, 10));
        TextView content = new TextView(getContext());
        content.setText(phrase.text);
        content.setTextSize(compact ? 13 : 14);
        content.setTextColor(theme.text);
        content.setMaxLines(2);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(content, new LayoutParams(0, -2, 1f));
        card.setContentDescription("输入快捷短语：" + phrase.text);
        card.setOnClickListener(v -> callback.onKey("输入快捷短语:" + phrase.id));
        card.setOnLongClickListener(v -> { showQuickPhraseMenu(card, phrase); return true; });
        android.widget.ImageButton more = addClipboardIcon(card, R.drawable.ic_panel_more,
                "快捷短语操作：" + phrase.text, "", true, compact ? 28 : 34, compact ? 6 : 8);
        more.setOnClickListener(v -> showQuickPhraseMenu(more, phrase));
        more.setTooltipText("编辑或删除");
        LayoutParams params = new LayoutParams(-1, -2);
        params.setMargins(0, dp(2), 0, dp(2));
        list.addView(card, params);
    }

    private void showQuickPhraseMenu(View anchor, QuickPhraseStore.Entry phrase) {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "快捷短语", theme);
        menu.item("编辑", null, null, false, false,
                () -> callback.onKey("编辑快捷短语:" + phrase.id));
        menu.item("查看完整内容", null, null, false, false, () -> {
            RoundedCardPopup preview = RoundedCardPopup.keyboard(getContext(), "快捷短语", theme);
            ScrollView scroll = new ScrollView(getContext());
            TextView text = new TextView(getContext());
            text.setText(phrase.text);
            text.setTextSize(16);
            text.setTextColor(theme.text);
            text.setPadding(dp(12), dp(12), dp(12), dp(12));
            scroll.addView(text);
            scroll.setLayoutParams(new LayoutParams(-1, dp(240)));
            preview.content(scroll);
            preview.item("输入", null, null, false, false,
                    () -> callback.onKey("输入快捷短语:" + phrase.id));
            menus.accept(anchor, preview);
        });
        menu.divider().item("删除", null, null, false, true, () -> {
            RoundedCardPopup confirm = RoundedCardPopup.keyboard(getContext(), "删除快捷短语？", theme);
            confirm.item("确认删除", null, null, false, true,
                    () -> callback.onKey("删除快捷短语:" + phrase.id));
            menus.accept(anchor, confirm);
        });
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
