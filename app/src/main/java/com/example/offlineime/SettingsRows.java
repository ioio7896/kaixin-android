package com.example.offlineime;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Arrays;
import java.util.function.IntConsumer;

/** Shared interactive controls for the settings pages. */
final class SettingsRows {
    private final SettingsActivity host;
    private final SharedPreferences preferences;
    private final UiTokens.Palette palette;

    SettingsRows(SettingsActivity host) {
        this.host = host;
        preferences = host.preferences();
        palette = host.palette();
    }

    void addSwitch(LinearLayout parent, String label, String key, boolean def) {
        addSwitch(parent, label, null, key, def);
    }

    void addSwitch(LinearLayout parent, String label, String summary, String key, boolean def) {
        LinearLayout row = new LinearLayout(host);
        row.setTag("setting-choice");
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(10), host.dp(SettingsUi.CARD_INSET), host.dp(10));
        row.setMinimumHeight(host.dp(SettingsUi.ROW_MIN_HEIGHT));
        LinearLayout labels = new LinearLayout(host);
        labels.setTag("setting-choice");
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = host.text(label, UiTokens.BODY_SP, palette.textPrimary);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (summary != null && !summary.isEmpty()) {
            TextView detail = host.text(summary, UiTokens.CAPTION_SP, palette.textSecondary);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = host.dp(3);
            labels.addView(detail, detailParams);
        }
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelsParams.rightMargin = host.dp(12);
        row.addView(labels, labelsParams);
        Switch control = new Switch(host);
        control.setChecked(preferences.getBoolean(key, def));
        control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        control.setClickable(false);
        control.setFocusable(false);
        control.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        });
        SettingsUi.styleCapsuleSwitch(control, host, palette);
        row.addView(control, new LinearLayout.LayoutParams(host.dp(SettingsUi.SWITCH_WIDTH), host.dp(32)));
        row.setBackground(host.pressableRoundRect(Color.TRANSPARENT, palette.surfaceMuted, SettingsUi.CONTROL_RADIUS));
        row.setContentDescription(summary == null ? label
                : host.getString(R.string.settings_label_summary, label, summary));
        row.setOnClickListener(v -> control.toggle());
        row.setFocusable(true);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo node) {
                super.onInitializeAccessibilityNodeInfo(view, node);
                node.setClassName(Switch.class.getName());
                node.setCheckable(true);
                node.setChecked(control.isChecked());
            }
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    void addNumber(LinearLayout parent, String label, String key, int min, int max,
                   int fallback, int step, String unit) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag("setting-choice");
        row.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(10), host.dp(SettingsUi.CARD_INSET), host.dp(6));

        LinearLayout heading = new LinearLayout(host);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = host.text(label, UiTokens.BODY_SP, palette.textPrimary);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = host.text("", UiTokens.CAPTION_SP, palette.accent);
        value.setPaddingRelative(host.dp(8), 0, 0, 0);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout adjustment = new LinearLayout(host);
        adjustment.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = stepButton("−", host.getString(R.string.settings_decrease, label));
        TextView plus = stepButton("+", host.getString(R.string.settings_increase, label));
        int touch = host.dp(UiTokens.MIN_TOUCH);
        SeekBar slider = new SeekBar(host);
        int[] current = {clamp(SettingsRepository.integer(preferences, key, fallback), min, max)};
        slider.setMax(max);
        slider.setMin(min);
        slider.setProgress(current[0]);
        slider.setKeyProgressIncrement(step);
        slider.setProgressTintList(ColorStateList.valueOf(palette.accent));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(palette.divider));
        adjustment.addView(minus, new LinearLayout.LayoutParams(touch, touch));
        adjustment.addView(slider, new LinearLayout.LayoutParams(0, touch, 1));
        adjustment.addView(plus, new LinearLayout.LayoutParams(touch, touch));
        row.addView(adjustment, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));

        IntConsumer display = number -> {
            current[0] = number;
            String formatted = host.getString(R.string.setting_numeric_value, number, unit);
            value.setText(formatted);
            slider.setContentDescription(host.getString(R.string.settings_label_value, label, formatted));
            minus.setEnabled(number > min);
            plus.setEnabled(number < max);
            minus.setAlpha(minus.isEnabled() ? 1f : 0.42f);
            plus.setAlpha(plus.isEnabled() ? 1f : 0.42f);
        };
        IntConsumer update = number -> {
            display.accept(number);
            host.previewNumber(key, number);
        };
        IntConsumer save = number -> {
            if (SettingsRepository.integer(preferences, key, fallback) != number)
                preferences.edit().putInt(key, number).apply();
        };
        IntConsumer stepTo = number -> {
            slider.setProgress(clamp(number, min, max));
            update.accept(slider.getProgress());
            save.accept(current[0]);
        };
        minus.setOnClickListener(v -> stepTo.accept(current[0] - step));
        plus.setOnClickListener(v -> stepTo.accept(current[0] + step));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;

            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }

            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int number = dragging && progress != max
                        ? clamp(min + Math.round((progress - min) / (float) step) * step, min, max)
                        : progress;
                if (current[0] == number) return;
                update.accept(number);
                if (!dragging) save.accept(number);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                bar.setProgress(current[0]);
                save.accept(current[0]);
            }
        });
        display.accept(current[0]);
    }

    private TextView stepButton(String symbol, String description) {
        TextView button = host.text(symbol, UiTokens.TITLE_SP, palette.accent);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(host.pressableRoundRect(palette.accentSoft, palette.surfaceMuted, 12));
        button.setFocusable(true);
        return button;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    void addSegmentedChoice(LinearLayout parent, String label, String key,
                            String[] labels, String[] values, String fallback) {
        LinearLayout control = new LinearLayout(host);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setTag("setting-choice");
        control.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(12),
                host.dp(SettingsUi.CARD_INSET), host.dp(12));
        TextView title = host.text(label, UiTokens.BODY_SP, palette.textPrimary);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.bottomMargin = host.dp(10);
        control.addView(title, titleParams);
        LinearLayout segments = new LinearLayout(host);
        segments.setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2));
        TextView[] options = new TextView[labels.length];
        Runnable repaint = () -> {
            title.setTextColor(palette.textPrimary);
            segments.setBackground(host.roundRect(palette.surfaceMuted, 11));
            String selected = choice(key, fallback);
            for (int i = 0; i < options.length; i++) {
                boolean active = values[i].equals(selected);
                options[i].setSelected(active);
                options[i].setTextColor(active ? palette.accent : palette.textPrimary);
                options[i].setBackground(host.roundRect(active
                        ? (UiTokens.isDark(host) ? palette.divider : palette.surface)
                        : Color.TRANSPARENT, 9));
                options[i].setContentDescription(host.getString(R.string.settings_label_summary, label, labels[i]));
            }
        };
        segments.setBaselineAligned(false);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView option = host.text(labels[i], UiTokens.CAPTION_SP, palette.textPrimary);
            option.setGravity(Gravity.CENTER);
            option.setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2));
            option.setMinHeight(host.dp(48));
            option.setOnClickListener(v -> {
                preferences.edit().putString(key, values[index]).apply();
                repaint.run();
            });
            options[i] = option;
            segments.addView(option, new LinearLayout.LayoutParams(0, -1, 1));
        }
        host.bindPalette(control, repaint);
        control.addView(segments, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
    }

    void addExpandableHelp(LinearLayout parent, String summary, String detail) {
        LinearLayout help = new LinearLayout(host);
        help.setOrientation(LinearLayout.VERTICAL);
        help.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(2),
                host.dp(SettingsUi.CARD_INSET), host.dp(10));
        LinearLayout heading = new LinearLayout(host);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(host.dp(52));
        TextView preview = host.text(summary, UiTokens.CAPTION_SP, palette.textSecondary);
        preview.setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(preview, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = host.text(host.getString(R.string.settings_help_expand), UiTokens.CAPTION_SP, palette.accent);
        action.setGravity(Gravity.CENTER);
        action.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(4));
        heading.addView(action, new LinearLayout.LayoutParams(host.dp(80), -2));
        TextView explanation = host.text(detail, UiTokens.CAPTION_SP, palette.textSecondary);
        explanation.setPadding(host.dp(2), 0, host.dp(2), host.dp(6));
        explanation.setVisibility(View.GONE);
        help.addView(heading);
        help.addView(explanation);
        heading.setOnClickListener(v -> {
            boolean expanded = explanation.getVisibility() == View.VISIBLE;
            explanation.setVisibility(expanded ? View.GONE : View.VISIBLE);
            action.setText(expanded ? R.string.settings_help_expand : R.string.settings_help_collapse);
            heading.setContentDescription(host.getString(R.string.settings_label_summary, summary,
                    action.getText()));
        });
        heading.setContentDescription(host.getString(R.string.settings_label_summary, summary, action.getText()));
        heading.setFocusable(true);
        host.bindPalette(help, () -> {
            preview.setTextColor(palette.textSecondary);
            action.setTextColor(palette.accent);
            explanation.setTextColor(palette.textSecondary);
        });
        parent.addView(help, new LinearLayout.LayoutParams(-1, -2));
    }

    void addChoice(LinearLayout parent, String label, String key, String[] labels, String[] values) {
        addChoice(parent, label, key, labels, values, values[0]);
    }

    void addChoice(LinearLayout parent, String label, String key, String[] labels,
                   String[] values, String defaultValue) {
        LinearLayout row = new LinearLayout(host);
        row.setTag("setting-choice");
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(SettingsUi.CARD_INSET), host.dp(8),
                host.dp(SettingsUi.CARD_INSET), host.dp(8));
        row.setMinimumHeight(host.dp(SettingsUi.ROW_MIN_HEIGHT));
        host.bindPalette(row, () -> row.setBackground(host.pressableRoundRect(Color.TRANSPARENT,
                palette.surfaceMuted, SettingsUi.CONTROL_RADIUS)));
        TextView title = host.text(label, UiTokens.BODY_SP, palette.textPrimary);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = createChoiceButton(label, key, defaultValue, labels, values, ignored -> true);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(false);
        value.setMaxLines(3);
        value.setFocusable(false);
        value.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
        value.setPadding(host.dp(10), host.dp(5), host.dp(10), host.dp(5));
        value.setMaxWidth(host.dp(148));
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.bindPalette(value, () -> {
            value.setTextColor(palette.accent);
            value.setBackgroundColor(Color.TRANSPARENT);
        });
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
        valueParams.leftMargin = host.dp(8);
        row.addView(value, valueParams);
        row.setOnClickListener(v -> value.performClick());
        row.setFocusable(true);
        updateChoiceButton(value, label, key, defaultValue, labels, values);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    TextView createChoiceButton(String label, String key, String defaultValue,
                                String[] labels, String[] values,
                                java.util.function.Predicate<String> canSelect) {
        return createChoiceButton(label, key, defaultValue, labels, values, canSelect, ignored -> true);
    }

    TextView createChoiceButton(String label, String key, String defaultValue,
                                String[] labels, String[] values,
                                java.util.function.Predicate<String> canSelect,
                                java.util.function.Predicate<String> showOption) {
        TextView button = host.text("", 14, palette.accent);
        button.setPadding(host.dp(4), host.dp(8), host.dp(2), host.dp(8));
        button.setMinimumHeight(host.dp(48));
        host.bindPalette(button, () -> button.setTextColor(palette.accent));
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        updateChoiceButton(button, label, key, defaultValue, labels, values);
        button.setOnClickListener(v -> {
            String selected = choice(key, defaultValue);
            RoundedCardPopup menu = RoundedCardPopup.settings(host, label);
            for (int i = 0; i < values.length; i++) {
                if (!showOption.test(values[i])) continue;
                final int option = i;
                menu.item(labels[i], null, null, values[i].equals(selected), false, () -> {
                    if (!canSelect.test(values[option])) return;
                    preferences.edit().putString(key, values[option]).apply();
                    updateChoiceButton(button, label, key, defaultValue, labels, values);
                });
            }
            host.showChoicePopup(button, menu);
        });
        return button;
    }

    private String choice(String key, String fallback) {
        try {
            return preferences.getString(key, fallback);
        } catch (ClassCastException legacy) {
            Object value = preferences.getAll().get(key);
            return value instanceof Integer ? value.toString() : fallback;
        }
    }

    private void updateChoiceButton(TextView button, String label, String key, String defaultValue,
                                    String[] labels, String[] values) {
        String selected = choice(key, defaultValue);
        int position = Arrays.asList(values).indexOf(selected);
        if (position < 0) position = Math.max(0, Arrays.asList(values).indexOf(defaultValue));
        button.setText(host.getString(R.string.setting_choice_value, labels[position]));
        button.setContentDescription(host.getString(R.string.settings_label_value, label, labels[position]));
        if (button.getParent() instanceof View && "setting-choice".equals(((View) button.getParent()).getTag()))
            ((View) button.getParent()).setContentDescription(button.getContentDescription());
    }

}
