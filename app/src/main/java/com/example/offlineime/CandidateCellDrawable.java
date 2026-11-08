package com.example.offlineime;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Subtle dividers shared by the candidate strip and expanded candidate page. */
final class CandidateCellDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int dividerColor, pressedColor;
    private final boolean grid;
    private final float density;
    private boolean pressed;

    CandidateCellDrawable(int dividerColor, int pressedColor, boolean grid, float density) {
        this.dividerColor = dividerColor;
        this.pressedColor = pressedColor;
        this.grid = grid;
        this.density = density;
    }

    @Override public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        if (pressed) {
            paint.setColor(pressedColor);
            canvas.drawRect(bounds, paint);
        }
        paint.setColor(dividerColor);
        paint.setStrokeWidth(Math.max(1f, density));
        float inset = (grid ? 5f : 13f) * density;
        float right = bounds.right - paint.getStrokeWidth() / 2f;
        canvas.drawLine(right, bounds.top + inset, right, bounds.bottom - inset, paint);
        if (grid) {
            float bottom = bounds.bottom - paint.getStrokeWidth() / 2f;
            canvas.drawLine(bounds.left + 5f * density, bottom,
                    bounds.right - 5f * density, bottom, paint);
        }
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean next = false;
        for (int state : states) if (state == android.R.attr.state_pressed) next = true;
        if (next == pressed) return false;
        pressed = next;
        invalidateSelf();
        return true;
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
