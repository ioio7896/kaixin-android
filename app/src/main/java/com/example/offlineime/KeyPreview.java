package com.example.offlineime;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

/** One touch-transparent preview per IME window, owned by the latest previewed press. */
final class KeyPreview {
    private static final int LEFT = Gravity.getAbsoluteGravity(Gravity.START, View.LAYOUT_DIRECTION_LTR);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::dismiss;
    private final PopupWindow popup;
    private final TextView glyph;
    private final GradientDrawable background = new GradientDrawable();
    private final Rect displayFrame = new Rect();
    private final int[] anchorPosition = new int[2];
    private KeyButton anchor;
    private Object owner;
    private KeyboardTheme theme;
    private String text = "";
    private int lastX, lastY, lastWidth;

    KeyPreview(Context context) {
        glyph = new TextView(context);
        glyph.setGravity(Gravity.CENTER);
        glyph.setSingleLine(true);
        glyph.setIncludeFontPadding(false);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glyph.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        glyph.setBackground(background);
        popup = new PopupWindow(glyph, 0, 0, false);
        popup.setTouchable(false);
        popup.setAnimationStyle(0);
        popup.setAttachedInDecor(false);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        popup.setOnDismissListener(() -> {
            handler.removeCallbacks(hide);
            anchor = null;
            owner = null;
        });
    }

    void show(KeyButton key, Object press, String value, KeyboardTheme palette) {
        if (!key.isShown() || key.getWindowToken() == null || value.isEmpty()) return;
        handler.removeCallbacks(hide);
        if (theme != palette) {
            theme = palette;
            background.setColor(theme.key);
            background.setCornerRadius(dp(Math.max(8f, theme.radius)));
            background.setStroke(dp(1), theme.accent);
            glyph.setTextColor(KeyboardTheme.readableText(theme.text, theme.key));
        }
        position(key, value, false, 0, 0);
        if (popup.isShowing()) {
            anchor = key;
            owner = press;
        }
    }

    void follow(KeyButton key, Object press, String value, float x, float y) {
        if (anchor == key && owner == press) position(key, value, true, x, y);
    }

    void release(KeyButton key, Object press, boolean delayed) {
        if (anchor != key || owner != press) return;
        if (delayed) handler.postDelayed(hide, KeyButton.RELEASE_DELAY_MS);
        else dismiss();
    }

    void cancel(KeyButton key) {
        if (anchor == key) dismiss();
    }

    void dismiss() {
        handler.removeCallbacks(hide);
        anchor = null;
        owner = null;
        if (popup.isShowing()) popup.dismiss();
    }

    private void position(KeyButton key, String value, boolean follow, float x, float y) {
        key.getWindowVisibleDisplayFrame(displayFrame);
        key.getLocationOnScreen(anchorPosition);
        int inset = dp(8);
        int height = dp(50);
        if (displayFrame.width() <= 2 * inset || displayFrame.height() < height + 2 * inset) {
            dismiss();
            return;
        }
        int width = Math.min(displayFrame.width() - 2 * inset,
                Math.min(dp(92), Math.max(dp(54), key.getWidth() + inset)));
        int left = Math.round(anchorPosition[0] + (follow ? x : key.getWidth() / 2f) - width / 2f);
        int top = Math.round(anchorPosition[1] + (follow ? y : 0) - height - inset);
        left = Math.max(displayFrame.left + inset, Math.min(left, displayFrame.right - inset - width));
        top = Math.max(displayFrame.top + inset, Math.min(top, displayFrame.bottom - inset - height));
        int offsetX = left - anchorPosition[0];
        int offsetY = top - anchorPosition[1] - key.getHeight();
        if (!text.equals(value) || lastWidth != width) {
            text = value;
            int length = value.codePointCount(0, value.length());
            glyph.setTextSize(length >= 4 ? 18 : length >= 3 ? 21 : 28);
            glyph.setText(value);
        }
        try {
            if (!popup.isShowing()) {
                popup.setWidth(width);
                popup.setHeight(height);
                popup.showAsDropDown(key, offsetX, offsetY, LEFT);
            } else if (anchor != key || offsetX != lastX || offsetY != lastY || width != lastWidth) {
                popup.update(key, offsetX, offsetY, width, height);
            }
        } catch (WindowManager.BadTokenException | IllegalStateException unavailableWindow) {
            dismiss();
        }
        lastX = offsetX;
        lastY = offsetY;
        lastWidth = width;
    }

    private int dp(float size) {
        return Math.round(size * glyph.getResources().getDisplayMetrics().density);
    }
}
