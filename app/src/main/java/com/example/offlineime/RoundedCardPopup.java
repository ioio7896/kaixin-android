package com.example.offlineime;

import android.content.Context;
import android.content.ContextWrapper;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared anchored card for IME menus and secondary settings, without resizing its host. */
final class RoundedCardPopup {
    private final Context context;
    private final int surface, text, secondary, divider, accent, accentSoft, danger;
    private final LinearLayout card, contents;
    private final ScrollView scroll;
    private final PopupWindow window;
    private final View.OnAttachStateChangeListener anchorListener;
    private View anchor;
    private Runnable dismissed;
    private int preferredWidth = 280;
    private boolean acceptsInput;
    private boolean dismissNotified;
    private View selectedRow;

    static RoundedCardPopup settings(Context context, String title) {
        UiTokens.Palette p = UiTokens.palette(context);
        return new RoundedCardPopup(context, title, p.surface, p.textPrimary, p.textSecondary,
                p.divider, p.accent, p.accentSoft, p.danger);
    }

    static void showDialog(AlertDialog dialog) {
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        Context context = dialog.getContext();
        UiTokens.Palette palette = UiTokens.palette(context);
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surface);
        background.setCornerRadius(20 * density);
        window.setBackgroundDrawable(background);
        window.getDecorView().setClipToOutline(true);
        window.setDimAmount(0.24f);
        window.setLayout(Math.min(Math.round(560 * density),
                context.getResources().getDisplayMetrics().widthPixels - Math.round(32 * density)), -2);
        colorDialogText(window.getDecorView(), palette);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button button = dialog.getButton(which);
            if (button != null) { button.setAllCaps(false); button.setTextColor(palette.accent); }
        }
    }

    private static void colorDialogText(View view, UiTokens.Palette palette) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(palette.textPrimary);
            ((TextView) view).setHintTextColor(palette.textSecondary);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) colorDialogText(group.getChildAt(i), palette);
        }
    }

    static RoundedCardPopup keyboard(Context context, String title, KeyboardTheme theme) {
        boolean dark = theme.isDark;
        return new RoundedCardPopup(context, title, theme.key, theme.text,
                mix(theme.text, theme.key, 0.42f), theme.function, theme.accent,
                mix(theme.key, theme.accent, dark ? 0.28f : 0.12f),
                dark ? 0xffffb4ab : 0xffba1a1a);
    }

    private RoundedCardPopup(Context context, String title, int surface, int text, int secondary,
                             int divider, int accent, int accentSoft, int danger) {
        this.context = context;
        this.surface = surface; this.text = text; this.secondary = secondary;
        this.divider = divider; this.accent = accent; this.accentSoft = accentSoft; this.danger = danger;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(4), dp(8), dp(8));
        card.setBackground(round(surface, 20));
        card.setClipToOutline(true);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(4), 0, dp(4));
        TextView heading = label(title, 16, text);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = label("×", 24, secondary);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭" + title);
        close.setBackground(pressable(surface));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contents, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        window = new PopupWindow(card, -2, -2, true);
        window.setBackgroundDrawable(round(surface, 20));
        window.setElevation(dp(8));
        window.setOutsideTouchable(true);
        window.setClippingEnabled(true);
        window.setAttachedInDecor(false);
        if (android.os.Build.VERSION.SDK_INT >= 29) window.setIsLaidOutInScreen(true);
        close.setOnClickListener(v -> dismiss());
        anchorListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { dismiss(); }
        };
        window.setOnDismissListener(this::notifyDismissed);
    }

    RoundedCardPopup width(int widthDp) { preferredWidth = widthDp; return this; }
    RoundedCardPopup acceptsInput() { acceptsInput = true; return this; }
    RoundedCardPopup onDismiss(Runnable listener) { dismissed = listener; return this; }

    RoundedCardPopup content(View view) {
        contents.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return this;
    }

    RoundedCardPopup divider() {
        View line = new View(context);
        line.setBackgroundColor(divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(dp(8), dp(6), dp(8), dp(6));
        contents.addView(line, params);
        return this;
    }

    RoundedCardPopup item(String title, String description, String badge,
                          boolean selected, boolean destructive, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(pressable(selected ? accentSoft : surface));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView main = label(title, 15, destructive ? danger : selected ? accent : text);
        main.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        labels.addView(main, new LinearLayout.LayoutParams(-1, -2));
        if (description != null && !description.isEmpty()) {
            TextView detail = label(description, 12, secondary);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(3);
            labels.addView(detail, params);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (badge != null && !badge.isEmpty()) {
            TextView tag = label(badge, 11, secondary);
            tag.setPadding(dp(7), dp(3), dp(7), dp(3));
            tag.setBackground(round(mix(surface, divider, 0.38f), 6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = dp(8);
            row.addView(tag, params);
        }
        if (selected) {
            TextView check = label("✓", 18, accent);
            check.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(26), -2);
            params.leftMargin = dp(4);
            row.addView(check, params);
        }
        row.setContentDescription(title + (description == null ? "" : "，" + description)
                + (selected ? "，已选择" : "") + (badge == null ? "" : "，" + badge));
        row.setOnClickListener(v -> { dismiss(); if (action != null) action.run(); });
        if (selected) selectedRow = row;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(2);
        contents.addView(row, params);
        return this;
    }

    boolean isShowing() { return window.isShowing(); }
    void dismiss() { window.dismiss(); }

    void show(View target) {
        if (window.isShowing()) return;
        dismissNotified = false;
        View host = popupHost(target);
        if (target == null || !target.isAttachedToWindow() || host == null
                || !host.isAttachedToWindow() || host.getWindowToken() == null) {
            notifyDismissed();
            return;
        }
        Rect frame = new Rect();
        target.getWindowVisibleDisplayFrame(frame);
        int[] position = new int[2];
        target.getLocationOnScreen(position);
        if (frame.width() <= 0 || frame.height() <= 0)
            frame.set(0, 0, context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels);
        // IME roots can report the application frame above them. Include the
        // anchor itself, then fit the card; never ask ancestors to scroll.
        frame.union(position[0], position[1], position[0] + target.getWidth(),
                position[1] + target.getHeight());
        int inset = dp(12);
        int width = Math.min(dp(preferredWidth), Math.max(dp(1), frame.width() - inset * 2));
        int maxHeight = Math.max(dp(1), Math.min(dp(520), frame.height() - inset * 2));
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (card.getMeasuredHeight() > maxHeight) {
            int headerHeight = card.getMeasuredHeight() - scroll.getMeasuredHeight();
            scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, maxHeight - headerHeight)));
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        }
        int height = card.getMeasuredHeight();
        int left = Math.max(frame.left + inset,
                Math.min(position[0], frame.right - inset - width));
        int below = position[1] + target.getHeight() + dp(6);
        int above = position[1] - height - dp(6);
        int top = below + height <= frame.bottom - inset ? below
                : above >= frame.top + inset ? above
                : Math.max(frame.top + inset, Math.min(position[1], frame.bottom - inset - height));
        window.setWidth(width);
        window.setHeight(height);
        window.setInputMethodMode(acceptsInput ? PopupWindow.INPUT_METHOD_NEEDED : PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setSoftInputMode(acceptsInput ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        anchor = target;
        target.addOnAttachStateChangeListener(anchorListener);
        // A button inside another PopupWindow owns a sub-window token. Android
        // rejects a panel attached to that token. Keep positioning by the button,
        // but attach secondary settings menus to the Activity's main window.
        try {
            window.showAtLocation(host, Gravity.TOP | Gravity.LEFT, left, top);
        } catch (WindowManager.BadTokenException | IllegalStateException unavailableWindow) {
            if (window.isShowing()) window.dismiss();
            notifyDismissed();
            return;
        }
        if (selectedRow != null) scroll.post(() -> scroll.scrollTo(0,
                Math.max(0, selectedRow.getTop() - (scroll.getHeight() - selectedRow.getHeight()) / 2)));
    }

    private View popupHost(View target) {
        if (target == null) return null;
        Context hostContext = context;
        while (hostContext != null) {
            if (hostContext instanceof Activity) {
                Activity activity = (Activity) hostContext;
                if (activity.isFinishing() || activity.isDestroyed()) return null;
                return activity.getWindow().getDecorView();
            }
            if (!(hostContext instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) hostContext).getBaseContext();
            if (base == hostContext) break;
            hostContext = base;
        }
        return target;
    }

    private void notifyDismissed() {
        if (dismissNotified) return;
        dismissNotified = true;
        if (anchor != null) anchor.removeOnAttachStateChangeListener(anchorListener);
        anchor = null;
        if (dismissed != null) dismissed.run();
    }

    private TextView label(String value, int size, int color) {
        TextView label = new TextView(context);
        label.setText(value); label.setTextSize(size); label.setTextColor(color);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private RippleDrawable pressable(int background) {
        return new RippleDrawable(ColorStateList.valueOf(mix(surface, accent, 0.18f)),
                round(background, 12), round(Color.WHITE, 12));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static int mix(int first, int second, float amount) {
        return Color.rgb(Math.round(Color.red(first) * (1 - amount) + Color.red(second) * amount),
                Math.round(Color.green(first) * (1 - amount) + Color.green(second) * amount),
                Math.round(Color.blue(first) * (1 - amount) + Color.blue(second) * amount));
    }
}
