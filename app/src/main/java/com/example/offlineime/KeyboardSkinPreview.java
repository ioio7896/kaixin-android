package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/** Miniature keyboard for skin, input, layout and sizing previews. */
final class KeyboardSkinPreview extends View {
    private static final String[][] QWERTY = {
            {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G", "H", "J", "K", "L"},
            {"123", "空格", "↵"}
    };
    private static final String[][] NINE_KEYS = {
            {"ABC", "DEF", "GHI"}, {"JKL", "MNO", "PQRS"}, {"TUV", "WXYZ", "空格"}
    };

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private KeyboardTheme theme;
    private CandidateTextStyle candidateStyle;
    private float keyFont, keyGap, keyRadius;
    private int heightDp;
    private boolean nine, traditional, showNineNumbers, showToolbar, showMode, showComposing;
    private boolean layoutOptions, inputOptions, previewsHeight, previewLandscape;
    private String code, toolbar = "";
    private int candidateCount;

    KeyboardSkinPreview(Context context) {
        this(context, KeyboardTheme.from(context, false));
    }

    KeyboardSkinPreview(Context context, KeyboardTheme theme) {
        super(context);
        this.theme = theme;
        previewLandscape = KeyboardPlacement.landscape(context);
        update(null, 0);
    }

    void setShowsLayoutOptions(boolean layoutOptions) {
        this.layoutOptions = layoutOptions;
        inputOptions = !layoutOptions;
        update(null, 0);
    }

    void setPreviewsHeight(boolean enabled) {
        previewsHeight = enabled;
        update(null, 0);
    }

    void refresh(String key, int value) {
        theme = KeyboardTheme.from(getContext(), false);
        update(key, value);
    }

    private void update(String key, int value) {
        SharedPreferences preferences = SettingsRepository.preferences(getContext());
        Map<String, Object> values = new HashMap<>(preferences.getAll());
        if (key != null) values.put(key, value);
        candidateStyle = CandidateTextStyle.fromValues(values);
        keyFont = SettingsRepository.integer(values, "key_font", (int) theme.font);
        keyRadius = SettingsRepository.integer(values, "key_radius", (int) theme.radius);
        keyGap = SettingsRepository.integer(values, "key_gap", (int) theme.gap);
        if ("key_height_landscape".equals(key)) previewLandscape = true;
        else if ("key_height".equals(key)) previewLandscape = false;
        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        heightDp = previewLandscape ? SettingsRepository.integer(values, "key_height_landscape", tablet ? 300 : 220)
                : SettingsRepository.integer(values, "key_height", tablet ? 320 : KeyboardSizing.DEFAULT_HEIGHT);

        nine = "nine".equals(SettingsRepository.string(values, "default_layout", "qwerty"));
        traditional = "traditional".equals(SettingsRepository.string(values, "output_script", "simplified"));
        showNineNumbers = !"letters".equals(SettingsRepository.string(values, "nine_key_labels", "letters_numbers"));
        showToolbar = layoutOptions && SettingsRepository.bool(values, "toolbar", true);
        showMode = layoutOptions && SettingsRepository.bool(values, "show_mode", true);
        showComposing = inputOptions || (layoutOptions && SettingsRepository.bool(values, "show_composing", true));
        code = nine ? "64426" : "xiaohe".equals(SettingsRepository.string(values, "pinyin_scheme", "full"))
                ? "nihk" : "ni hao";
        candidateCount = CandidatePaging.pageSize(SettingsRepository.integer(values, "candidate_count", 6));
        if (showToolbar) {
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < ToolbarConfig.DEFAULTS.length; i++) {
                String item = ToolbarConfig.normalize(SettingsRepository.string(values, "toolbar_item_" + i,
                        ToolbarConfig.DEFAULTS[i]), i);
                if (item.isEmpty()) continue;
                if (labels.length() > 0) labels.append("  ·  ");
                labels.append(item);
            }
            toolbar = labels.toString();
        }
        if (previewsHeight) {
            setContentDescription(getContext().getString(previewLandscape
                    ? R.string.settings_keyboard_preview_landscape : R.string.settings_keyboard_preview_portrait, heightDp));
            requestLayout();
        } else {
            setContentDescription(getContext().getString(nine
                    ? R.string.settings_keyboard_preview_nine : R.string.settings_keyboard_preview_qwerty));
        }
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height = previewsHeight ? Math.round(dp(Math.max(110, Math.min(228, heightDp * 0.41f)))) : 0;
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padding = dp(6);
        float width = getWidth() - 2 * padding;
        float header = dp(Math.max(20, Math.min(38, candidateStyle.barHeight(getContext()) * 0.5f)));
        float top = padding;
        if (width <= 0) return;
        paint.setColor(theme.background);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(10), dp(10), paint);
        if (showToolbar) {
            paint.setTextSize(dp(12));
            paint.setTypeface(Typeface.DEFAULT);
            paint.setColor(theme.text);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(TextUtils.ellipsize(toolbar, paint, width - dp(4),
                    TextUtils.TruncateAt.END).toString(), padding + dp(4), baseline(top, dp(21)), paint);
            top += dp(21);
        }
        if (showMode || showComposing) {
            paint.setTextSize(dp(11));
            paint.setTypeface(Typeface.DEFAULT);
            paint.setColor(theme.text);
            paint.setTextAlign(Paint.Align.LEFT);
            String mode = showMode ? getContext().getString(nine
                    ? R.string.settings_keyboard_mode_nine : R.string.settings_keyboard_mode_qwerty) : "";
            canvas.drawText(mode + (showMode && showComposing ? "  ·  " : "") + (showComposing ? code : ""),
                    padding + dp(4), baseline(top, dp(16)), paint);
            top += dp(16);
        }
        drawCandidates(canvas, padding, top, header, width);
        top += header;
        float rowHeight = (getHeight() - padding - top) / 3;
        if (rowHeight <= 0) return;
        drawKeys(canvas, padding, top, width, rowHeight);
    }

    private void drawCandidates(Canvas canvas, float left, float top, float height, float width) {
        String[] words = traditional ? new String[]{"你好", "開心", "輸入", "漢字", "你", "好", "擬好", "您好", "李好"}
                : new String[]{"你好", "开心", "输入", "汉字", "你", "好", "拟好", "您好", "李好"};
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(Math.min(candidateStyle.textPx(getContext(), false) * 0.68f, height * 0.65f));
        float end = left + width;
        float gap = dp(candidateStyle.barPaddingDp * 0.55f);
        int limit = inputOptions ? Math.min(candidateCount, words.length) : 4;
        for (int i = 0; i < limit; i++) {
            boolean first = i == 0;
            paint.setTypeface(candidateStyle.typeface(first, false));
            String number = candidateStyle.barNumbers ? (i + 1) + " " : "";
            float numberWidth = paint.measureText(number) * CandidateTextStyle.NUMBER_SCALE;
            float cellWidth = numberWidth + paint.measureText(words[i]) + gap * 2;
            if (left + cellWidth > end) break;
            paint.setColor(theme.candidateForeground(first && candidateStyle.accentFirst(false), false));
            float baseline = baseline(top, height);
            if (!number.isEmpty()) {
                float size = paint.getTextSize();
                paint.setTextSize(size * CandidateTextStyle.NUMBER_SCALE);
                canvas.drawText(number, left + gap, baseline, paint);
                paint.setTextSize(size);
            }
            canvas.drawText(words[i], left + gap + numberWidth, baseline, paint);
            left += cellWidth;
        }
    }

    private void drawKeys(Canvas canvas, float padding, float top, float width, float rowHeight) {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.CENTER);
        float gap = Math.min(dp(keyGap), rowHeight / 8);
        String[][] rows = nine ? NINE_KEYS : QWERTY;
        for (int row = 0; row < rows.length; row++) {
            boolean bottom = !nine && row == 2;
            float units = bottom ? 5 : rows[row].length;
            float left = padding;
            for (int column = 0; column < rows[row].length; column++) {
                float cellWidth = width * (bottom && column == 1 ? 3 : 1) / units;
                boolean enter = bottom && column == 2;
                float radius = Math.min(dp(keyRadius), Math.min(cellWidth, rowHeight) / 4);
                paint.setColor(enter ? theme.emphasizedBackground() : bottom && column == 0 ? theme.function : theme.key);
                canvas.drawRoundRect(left + gap, top + gap, left + cellWidth - gap,
                        top + rowHeight - gap, radius, radius, paint);
                paint.setColor(enter ? theme.accent : theme.text);
                paint.setTextSize(Math.min(dp(keyFont * 0.7f), Math.min(rowHeight * 0.55f, cellWidth * 0.46f)));
                String label = rows[row][column];
                if (nine && showNineNumbers && !(row == 2 && column == 2)) label += " " + (row * 3 + column + 2);
                canvas.drawText(label, left + cellWidth / 2, baseline(top, rowHeight), paint);
                left += cellWidth;
            }
            top += rowHeight;
        }
    }

    private float baseline(float top, float height) {
        return top + height / 2 - (paint.ascent() + paint.descent()) / 2;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
