package com.example.offlineime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Small non-interactive keyboard drawn from the same palette as the IME. */
final class KeyboardSkinPreview extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private KeyboardTheme theme;
    private static final String[][] ROWS = {
            {"Q", "W", "E", "R", "T", "Y"},
            {"A", "S", "D", "F", "G", "H"},
            {"123", "空格", "↵"}
    };

    KeyboardSkinPreview(Context context, KeyboardTheme theme) {
        super(context);
        this.theme = theme;
        setContentDescription("键盘配色预览");
    }

    void setTheme(KeyboardTheme theme) {
        this.theme = theme;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padding = dp(6);
        float width = getWidth() - 2 * padding;
        float header = dp(18);
        float rowHeight = (getHeight() - 2 * padding - header) / 3;
        if (width <= 0 || rowHeight <= 0) return;
        paint.setColor(theme.background);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(10), dp(10), paint);
        paint.setColor(theme.accent);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(Math.min(dp(11), width / 16));
        canvas.drawText("你好   输入   开心", padding, padding + header * 0.7f, paint);
        float gap = Math.min(dp(theme.gap), rowHeight / 8);
        for (int row = 0; row < ROWS.length; row++) {
            float left = padding;
            float top = padding + header + row * rowHeight;
            for (int column = 0; column < ROWS[row].length; column++) {
                float weight = row < 2 ? 1 : column == 1 ? 3 : 1;
                float cellWidth = width * weight / (row < 2 ? 6 : 5);
                boolean function = row == 2 && column != 1;
                boolean enter = row == 2 && column == 2;
                float radius = Math.min(dp(theme.radius), Math.min(cellWidth, rowHeight) / 4);
                paint.setColor(enter ? theme.emphasizedBackground() : function ? theme.function : theme.key);
                canvas.drawRoundRect(left + gap, top + gap, left + cellWidth - gap,
                        top + rowHeight - gap, radius, radius, paint);
                // Match the IME's emphasized enter-key foreground.
                paint.setColor(enter ? theme.accent : theme.text);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Math.min(dp(theme.font * 0.7f), Math.min(rowHeight * 0.5f, cellWidth * 0.45f)));
                float baseline = top + rowHeight / 2 - (paint.ascent() + paint.descent()) / 2;
                canvas.drawText(ROWS[row][column], left + cellWidth / 2, baseline, paint);
                left += cellWidth;
            }
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
