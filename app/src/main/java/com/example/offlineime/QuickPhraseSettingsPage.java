package com.example.offlineime;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Lifecycle-bound settings page with asynchronous persistence and visible recovery errors. */
final class QuickPhraseSettingsPage implements AutoCloseable {
    private final Activity activity;
    private final UiTokens.Palette palette;
    private final QuickPhraseRepository repository;
    private final LinearLayout list;
    private final Runnable observer = this::render;
    private boolean closed;
    QuickPhraseSettingsPage(Activity activity, LinearLayout list, UiTokens.Palette palette) {
        this.activity = activity; this.list = list; this.palette = palette;
        repository = QuickPhraseRepository.get(activity);
        repository.observe(observer);
        render();
    }
    void refresh() { repository.reload(); render(); }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void render() {
        if (!alive()) return;
        list.removeAllViews();
        String status = repository.status();
        if (!status.isEmpty()) {
            TextView message = text(status, 13, palette.danger);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
            statusParams.bottomMargin = dp(12);
            list.addView(message, statusParams);
        }
        java.util.List<QuickPhraseStore.Entry> entries = repository.list();
        if (entries.isEmpty()) {
            TextView heading = text("还没有快捷短语", 17, palette.textPrimary);
            heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            list.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            TextView description = text("保存常用地址或回复，在键盘上快速插入。", 13,
                    palette.textSecondary);
            description.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
            descriptionParams.topMargin = dp(6);
            list.addView(description, descriptionParams);
            Button add = actionButton("添加快捷短语", true, false);
            add.setOnClickListener(v -> showEditor(null));
            LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48));
            addParams.topMargin = dp(18);
            list.addView(add, addParams);
            return;
        }
        Button add = actionButton("添加快捷短语", true, false);
        add.setOnClickListener(v -> showEditor(null));
        list.addView(add, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView count = text("已保存 " + entries.size() + " 条", 13, palette.textSecondary);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-1, -2);
        countParams.topMargin = dp(16);
        countParams.bottomMargin = dp(4);
        list.addView(count, countParams);
        for (int index = 0; index < entries.size(); index++) {
            QuickPhraseStore.Entry phrase = entries.get(index);
            if (index > 0) {
                View divider = new View(activity);
                divider.setBackgroundColor(palette.divider);
                list.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
            }
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(4));
            row.setOnClickListener(v -> showDetails(phrase));
            row.setContentDescription("查看完整快捷短语：" + phrase.text);
            TextView preview = text(phrase.text, 15, palette.textPrimary);
            preview.setMaxLines(3); preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            preview.setPadding(0, dp(4), 0, dp(4));
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(preview, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.END);
            Button edit = actionButton("编辑", false, false);
            edit.setContentDescription("编辑快捷短语：" + phrase.text);
            edit.setOnClickListener(v -> showEditor(phrase));
            actions.addView(edit, new LinearLayout.LayoutParams(dp(64), dp(48)));
            Button delete = actionButton("删除", false, true);
            delete.setContentDescription("删除快捷短语：" + phrase.text);
            delete.setOnClickListener(v -> confirmDelete(phrase));
            actions.addView(delete, new LinearLayout.LayoutParams(dp(64), dp(48)));
            row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private Button actionButton(String label, boolean primary, boolean danger) {
        Button button = new Button(activity);
        button.setText(label);
        if (primary) {
            SettingsUi.styleActionButton(button, activity, palette, true, false);
        } else {
            button.setAllCaps(false);
            button.setTextSize(14);
            button.setTextColor(danger ? palette.danger : palette.accent);
            button.setGravity(Gravity.CENTER);
            button.setPadding(0, 0, 0, 0);
            button.setBackground(SettingsUi.pressable(activity, Color.TRANSPARENT,
                    danger ? palette.dangerSoft : palette.accentSoft, SettingsUi.CONTROL_RADIUS));
            button.setBackgroundTintList(null);
            button.setStateListAnimator(null);
            button.setElevation(0f);
        }
        return button;
    }
    private void showDetails(QuickPhraseStore.Entry phrase) {
        ScrollView scroll = new ScrollView(activity);
        TextView full = text(phrase.text, 16, palette.textPrimary);
        full.setTextIsSelectable(true); full.setPadding(dp(20), dp(12), dp(20), dp(12));
        scroll.addView(full);
        RoundedCardPopup.showDialog(new AlertDialog.Builder(activity).setTitle("快捷短语")
                .setView(scroll).setPositiveButton("关闭", null).create());
    }
    private void confirmDelete(QuickPhraseStore.Entry phrase) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("删除快捷短语？")
                .setMessage(phrase.text).setNegativeButton("取消", null).setPositiveButton("删除", null).create();
        RoundedCardPopup.showDialog(dialog);
        Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE); button.setTextColor(palette.danger);
        button.setOnClickListener(v -> {
            button.setEnabled(false);
            repository.remove(phrase.id, error -> {
                if (!alive() || !dialog.isShowing()) return;
                button.setEnabled(true);
                if (error.isEmpty()) dialog.dismiss();
                Toast.makeText(activity, error.isEmpty() ? "快捷短语已删除" : error, Toast.LENGTH_LONG).show();
            });
        });
    }
    void showEditor(QuickPhraseStore.Entry phrase) {
        EditText input = new EditText(activity); input.setHint("输入常用文字");
        input.setMinLines(3); input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        if (phrase != null) { input.setText(phrase.text); input.setSelection(input.length()); }
        LinearLayout frame = new LinearLayout(activity);
        frame.setPadding(dp(20), dp(4), dp(20), dp(4));
        frame.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(phrase == null ? "添加快捷短语" : "编辑快捷短语")
                .setView(frame).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        RoundedCardPopup.showDialog(dialog);
        Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setOnClickListener(v -> {
            String value = input.getText().toString(); button.setEnabled(false);
            repository.save(phrase == null ? null : phrase.id, value, error -> {
                if (!alive() || !dialog.isShowing()) return;
                button.setEnabled(true);
                if (!error.isEmpty()) { input.setError(error); return; }
                dialog.dismiss();
                Toast.makeText(activity, phrase == null ? "快捷短语已添加" : "快捷短语已更新", Toast.LENGTH_SHORT).show();
            });
        });
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    @Override public void close() { closed = true; repository.removeObserver(observer); }
}
