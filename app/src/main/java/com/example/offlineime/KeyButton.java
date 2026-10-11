package com.example.offlineime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.util.SparseArray;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;

/** 统一处理按键外观、触摸反馈和可访问性。 */
public final class KeyButton extends Button {
    private final float maxFont;
    private final String label;
    private final String primaryLabel, secondaryLabel;
    private final int secondaryLabelColor;
    private final Paint primaryLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondaryLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics primaryMetrics = new Paint.FontMetrics();
    private final Paint.FontMetrics secondaryMetrics = new Paint.FontMetrics();
    private String swipeUpHint = "";
    private final SparseArray<Touch> touches = new SparseArray<>();
    private final KeyFeedback feedback;
    private final OrderedKeyInput inputOrder;
    private final int touchSlop;
    private static final DecelerateInterpolator RELEASE_INTERPOLATOR = new DecelerateInterpolator();
    interface TouchAction { Runnable action(float x, float y); }
    private TouchAction touchAction;
    private char inputLetter;
    void setTouchAction(TouchAction action) { touchAction = action; }
    void setInputLetter(char value) { inputLetter = value; }
    char inputLetter() { return inputLetter; }
    private Runnable alternate;
    private Runnable swipeLeft, swipeRight;
    private java.util.function.IntConsumer trackpadMove;
    private Runnable trackpadSelection;
    private boolean repeatDelete;
    private int symbolIconResource;
    private Drawable symbolIcon;
    private String symbolCaption = "";
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics captionMetrics = new Paint.FontMetrics();
    private static final class Touch {
        int id;
        float startX, startY;
        boolean consumed, swipeUpArmed, trackpad;
        int trackpadSteps, repeats;
        Runnable hold;
        OrderedKeyInput.Press press;
    }
    public void setAlternateAction(Runnable action) {
        alternate = action;
        setOnLongClickListener(v -> { action.run(); return true; });
        AccessibilitySupport.longClick(this, "输入副符号");
    }
    public void setSwipeUpSymbol(String symbol, Runnable action) {
        swipeUpHint = symbol;
        setAlternateAction(action);
        setText("");
        setContentDescription(symbolDescription(label) + "，上划或长按输入 " + symbol);
        invalidate();
    }
    public void setRepeatDelete() { repeatDelete = true; }
    public void setHorizontalActions(Runnable left, Runnable right) { swipeLeft = left; swipeRight = right; }
    public void setTrackpadActions(java.util.function.IntConsumer move, Runnable selection) {
        trackpadMove = move;
        trackpadSelection = selection;
        setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.accessibility_cursor_left, "光标左移"));
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.accessibility_cursor_right, "光标右移"));
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.accessibility_selection, "切换选择模式"));
            }
            @Override public boolean performAccessibilityAction(android.view.View host, int action, android.os.Bundle args) {
                if (action == R.id.accessibility_cursor_left) { move.accept(-1); return true; }
                if (action == R.id.accessibility_cursor_right) { move.accept(1); return true; }
                if (action == R.id.accessibility_selection) { selection.run(); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        setContentDescription(symbolDescription(label) + "，左右滑动移动光标，上滑或双指切换选择模式");
    }
    public KeyButton(Context c, String label, boolean function, KeyboardTheme theme) {
        super(c); this.label = label == null ? "" : label; this.maxFont = theme.font;
        feedback = KeyFeedback.get(c);
        inputOrder = c instanceof OfflineImeService ? ((OfflineImeService) c).keyInputOrder() : new OrderedKeyInput();
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int lineBreak = this.label.indexOf('\n');
        boolean stacked = lineBreak > 0 && lineBreak + 1 < this.label.length();
        primaryLabel = stacked ? this.label.substring(lineBreak + 1) : "";
        secondaryLabel = stacked ? this.label.substring(0, lineBreak) : "";
        secondaryLabelColor = blend(theme.text, theme.key, 0.45f);
        setText("回车".equals(this.label) ? "确认" : this.label); setAllCaps(false); setMinHeight(0); setMinWidth(0); setGravity(Gravity.CENTER); setPadding(0,0,0,0);
        setIncludeFontPadding(false);
        setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        boolean hasSecondaryLabel = this.label.indexOf(' ') >= 0 || this.label.indexOf('\n') >= 0;
        setTextSize(("空格".equals(this.label) || "Space".equals(this.label)) ? 13
                : this.label.indexOf('\n') >= 0 ? theme.font
                : hasSecondaryLabel ? Math.max(11, theme.font - 5)
                : (this.label.length() > 1 ? Math.max(10, theme.font - 4) : theme.font));
        setTextColor(theme.text);
        // Draw the two labels as one centered group. TextView's fixed two-line
        // height otherwise leaves the larger second label against the key edge.
        if (stacked) setText("");
        float radius = theme.radius * getResources().getDisplayMetrics().density;
        int normalColor = function ? theme.function : theme.key;
        GradientDrawable normal = new GradientDrawable(); normal.setColor(normalColor); normal.setCornerRadius(radius);
        normal.setStroke(1, blend(normalColor, theme.text, 0.06f));
        GradientDrawable pressed = new GradientDrawable();
        int pressedColor = blend(normalColor, theme.accent, theme.isDark ? 0.28f : 0.16f);
        pressed.setColor(pressedColor); pressed.setCornerRadius(radius);
        pressed.setStroke(1, blend(pressedColor, theme.accent, 0.08f));
        StateListDrawable states = new StateListDrawable(); states.addState(new int[]{android.R.attr.state_pressed}, pressed); states.addState(new int[]{}, normal); setBackground(states);
        setElevation((function ? 0f : 0.5f) * getResources().getDisplayMetrics().density);
        setContentDescription(symbolDescription(this.label));
        applySymbolIcon();
        setFocusable(true); setStateListAnimator(null);
    }

    public void setActionIcon(int drawableResource, String description) {
        String currentText = getText() == null ? "" : getText().toString();
        if (!currentText.isEmpty()) symbolCaption = currentText;
        symbolIconResource = drawableResource;
        symbolIcon = null;
        setText("");
        if (description != null && !description.isEmpty()) setContentDescription(description);
        invalidate();
    }

    private void applySymbolIcon() {
        if ("⇧".equals(label)) {
            symbolIconResource = R.drawable.ic_key_shift;
            symbolCaption = "";
            setText("");
        } else if ("⌫".equals(label)) {
            symbolIconResource = R.drawable.ic_key_backspace;
            symbolCaption = "";
            setText("");
        } else if ("空格".equals(label) || "Space".equals(label)) {
            symbolIconResource = R.drawable.ic_key_space;
            symbolCaption = "空格".equals(label) ? "空格" : "Space";
            setText("");
        } else if ("回车".equals(label)) {
            symbolIconResource = R.drawable.ic_key_enter;
            symbolCaption = "确认";
            setText("");
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (symbolIconResource == 0 && (!primaryLabel.isEmpty() || !swipeUpHint.isEmpty())) {
            drawKeyLabels(canvas);
            return;
        }
        if (symbolIconResource == 0) return;
        if (symbolIcon == null) {
            Drawable loaded = getContext().getDrawable(symbolIconResource);
            if (loaded == null) return;
            symbolIcon = loaded.mutate();
        }
        Drawable icon = symbolIcon;
        icon.setTint(getCurrentTextColor());

        float iconWidth = dp(symbolIconResource == R.drawable.ic_key_space ? 32 : 22);
        float iconHeight = dp(symbolIconResource == R.drawable.ic_key_space ? 10 : 22);
        captionPaint.setColor(getCurrentTextColor());
        float textSp = symbolCaption.isEmpty() ? 0
                : (symbolIconResource == R.drawable.ic_key_space ? 13 : Math.max(10, maxFont - 4));
        if (textSp > 0) {
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            captionPaint.setTextSize(textSp * scaledDensity);
            float maxTextWidth = Math.max(dp(24), getWidth() - dp(8));
            while (captionPaint.measureText(symbolCaption) > maxTextWidth && textSp > 10) {
                textSp -= 1;
                captionPaint.setTextSize(textSp * scaledDensity);
            }
        }

        captionPaint.getFontMetrics(captionMetrics);
        Paint.FontMetrics metrics = captionMetrics;
        float captionHeight = textSp == 0 ? 0 : metrics.descent - metrics.ascent;
        float gap = textSp == 0 ? 0 : dp(2);
        float groupHeight = iconHeight + gap + captionHeight;
        float availableHeight = Math.max(dp(16), getHeight() - dp(4));
        if (groupHeight > availableHeight && groupHeight > 0) {
            float scale = availableHeight / groupHeight;
            iconWidth *= scale;
            iconHeight *= scale;
            gap *= scale;
            if (textSp > 0) {
                textSp *= scale;
                captionPaint.setTextSize(textSp * getResources().getDisplayMetrics().scaledDensity);
                captionPaint.getFontMetrics(metrics);
                captionHeight = metrics.descent - metrics.ascent;
            }
            groupHeight = iconHeight + gap + captionHeight;
        }

        float iconLeft = (getWidth() - iconWidth) / 2f;
        float groupTop = (getHeight() - groupHeight) / 2f;
        icon.setBounds(Math.round(iconLeft), Math.round(groupTop),
                Math.round(iconLeft + iconWidth), Math.round(groupTop + iconHeight));
        icon.draw(canvas);
        if (textSp > 0) {
            float baseline = groupTop + iconHeight + gap - metrics.ascent;
            canvas.drawText(symbolCaption, getWidth() / 2f, baseline, captionPaint);
        }
    }

    private void drawKeyLabels(Canvas canvas) {
        boolean stacked = !primaryLabel.isEmpty();
        String main = stacked ? primaryLabel : label;
        String hint = stacked ? secondaryLabel : swipeUpHint;
        primaryLabelPaint.setTypeface(getTypeface());
        secondaryLabelPaint.setTypeface(getTypeface());
        primaryLabelPaint.setTextAlign(Paint.Align.CENTER);
        secondaryLabelPaint.setTextAlign(Paint.Align.CENTER);
        primaryLabelPaint.setColor(getCurrentTextColor());
        secondaryLabelPaint.setColor(secondaryLabelColor);
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        primaryLabelPaint.setTextSize(maxFont * scaledDensity);
        secondaryLabelPaint.setTextSize((stacked ? Math.max(9, Math.min(11, maxFont * 0.52f)) : 10)
                * scaledDensity);
        float horizontalInset = dp(stacked ? 6 : 4);
        float availableWidth = Math.max(1, getWidth() - horizontalInset * 2);
        float widest = Math.max(primaryLabelPaint.measureText(main), secondaryLabelPaint.measureText(hint));
        if (widest > availableWidth) {
            float scale = availableWidth / widest;
            primaryLabelPaint.setTextSize(primaryLabelPaint.getTextSize() * scale);
            secondaryLabelPaint.setTextSize(secondaryLabelPaint.getTextSize() * scale);
        }
        primaryLabelPaint.getFontMetrics(primaryMetrics);
        secondaryLabelPaint.getFontMetrics(secondaryMetrics);
        float mainHeight = primaryMetrics.descent - primaryMetrics.ascent;
        float hintHeight = secondaryMetrics.descent - secondaryMetrics.ascent;
        float gap = dp(2);
        float groupHeight = hintHeight + gap + mainHeight;
        // Reserve 6 dp above and below nine-key labels, even at reduced heights.
        float availableHeight = Math.max(1, getHeight() - dp(stacked ? 12 : 8));
        if (groupHeight > availableHeight) {
            float scale = availableHeight / groupHeight;
            primaryLabelPaint.setTextSize(primaryLabelPaint.getTextSize() * scale);
            secondaryLabelPaint.setTextSize(secondaryLabelPaint.getTextSize() * scale);
            gap *= scale;
            primaryLabelPaint.getFontMetrics(primaryMetrics);
            secondaryLabelPaint.getFontMetrics(secondaryMetrics);
            mainHeight = primaryMetrics.descent - primaryMetrics.ascent;
            hintHeight = secondaryMetrics.descent - secondaryMetrics.ascent;
            groupHeight = hintHeight + gap + mainHeight;
        }
        float top = (getHeight() - groupHeight) / 2f;
        float hintX = stacked ? getWidth() / 2f
                : getWidth() - horizontalInset - secondaryLabelPaint.measureText(hint) / 2f;
        canvas.drawText(hint, hintX, top - secondaryMetrics.ascent, secondaryLabelPaint);
        canvas.drawText(main, getWidth() / 2f,
                top + hintHeight + gap - primaryMetrics.ascent, primaryLabelPaint);
    }

    private String symbolDescription(String value) {
        if ("⇧".equals(value)) return "大小写切换";
        if ("⌫".equals(value)) return "退格删除";
        if ("空格".equals(value) || "Space".equals(value)) return "空格";
        if ("回车".equals(value)) return "回车";
        return value.replace('\n', ' ');
    }

    /** A soft accent identifies the primary action. */
    public void setEmphasized(KeyboardTheme theme) {
        float radius = theme.radius * getResources().getDisplayMetrics().density;
        GradientDrawable normal = new GradientDrawable();
        int surface = theme.emphasizedBackground();
        normal.setColor(surface); normal.setCornerRadius(radius);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(blend(surface, theme.accent, 0.18f)); pressed.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        setBackground(states);
        setTextColor(theme.accent);
        setElevation(0);
    }

    public void setShiftState(boolean uppercase, boolean locked, KeyboardTheme theme) {
        setSelected(locked);
        setContentDescription(locked ? "大写锁定，点击解除"
                : uppercase ? "下一字母大写，双击锁定大写" : "大小写切换，双击锁定大写");
        if (!uppercase) return;
        float radius = theme.radius * getResources().getDisplayMetrics().density;
        int surface = blend(theme.function, theme.accent, locked ? 0.42f : 0.18f);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(surface);
        normal.setCornerRadius(radius);
        if (locked) normal.setStroke(dp(2), theme.accent);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(blend(surface, theme.accent, 0.20f));
        pressed.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        setBackground(states);
        if (locked) setTextColor(theme.accent);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private static int blend(int base, int overlay, float amount) {
        int a = (int) (255 * amount);
        int r = ((base >> 16) & 0xff) * (255 - a) / 255 + ((overlay >> 16) & 0xff) * a / 255;
        int g = ((base >> 8) & 0xff) * (255 - a) / 255 + ((overlay >> 8) & 0xff) * a / 255;
        int b = (base & 0xff) * (255 - a) / 255 + (overlay & 0xff) * a / 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || label.isEmpty()) return;
        if (symbolIconResource != 0) return;
        if (!primaryLabel.isEmpty() || !swipeUpHint.isEmpty()) return;
        if ("⇧".equals(label) || "⌫".equals(label)) return;
        float target = maxFont;
        if ("空格".equals(label) || "Space".equals(label)) target = 13;
        else if (label.indexOf(' ') >= 0) target = Math.max(11, maxFont - 5);
        else if (label.length() > 1) target = Math.max(11, maxFont - 4);
        float available = Math.max(dp(12), w - getPaddingLeft() - getPaddingRight() - dp(12));
        // Always fit from the configured size, not a previously shrunk size.
        android.graphics.Paint measure = new android.graphics.Paint(getPaint());
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        measure.setTextSize(target * scaledDensity);
        Paint.FontMetrics metrics = measure.getFontMetrics();
        float measuredHeight = metrics.descent - metrics.ascent;
        float availableHeight = Math.max(dp(10), h - getPaddingTop() - getPaddingBottom() - dp(4));
        if (measuredHeight > availableHeight)
            target = Math.max(10, target * availableHeight / measuredHeight);
        measure.setTextSize(target * scaledDensity);
        float measured = measure.measureText(label);
        if (measured > available && measured > 0) target = Math.max(10, target * available / measured);
        if (target > 0) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, target);
    }

    @Override public void setPressed(boolean pressed) {
        if (isPressed() == pressed) return;
        super.setPressed(pressed);
        animate().cancel();
        boolean animations = android.animation.ValueAnimator.areAnimatorsEnabled();
        if (!animations) { setScaleX(1f); setScaleY(1f); return; }
        if (pressed) { setScaleX(0.982f); setScaleY(0.982f); }
        else animate().scaleX(1f).scaleY(1f).setDuration(50)
                .setInterpolator(RELEASE_INTERPOLATOR).withEndAction(null).start();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex(), id = event.getPointerId(index);
            Touch old = touches.get(id);
            if (old != null) finishTouch(old, null);
            Touch touch = new Touch();
            touch.id = id; touch.startX = event.getX(index); touch.startY = event.getY(index);
            touch.press = repeatDelete ? null : inputOrder.begin();
            if (trackpadMove != null && touches.size() > 0) {
                for (int i = 0; i < touches.size(); i++) beginTrackpad(touches.valueAt(i));
                beginTrackpad(touch);
                if (trackpadSelection != null) trackpadSelection.run();
            }
            touches.put(id, touch);
            setPressed(true);
            feedback.press(this);
            // Each finger has its own gesture and hold timer. Taps still resolve
            // on UP so symbol flicks, long presses and cancellation stay exclusive.
            if (repeatDelete) performClick();
            touch.hold = () -> holdTouch(touch);
            if (touches.get(id) == touch && (repeatDelete || trackpadMove != null
                    || alternate != null || isLongClickable()))
                postDelayed(touch.hold, repeatDelete ? 360 : ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { cancelTouch(); return true; }
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                Touch touch = touches.get(event.getPointerId(i));
                if (touch != null) moveTouch(touch, event.getX(i), event.getY(i));
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            Touch touch = touches.get(event.getPointerId(index));
            if (touch != null) {
                float x = event.getX(index), y = event.getY(index);
                moveTouch(touch, x, y);
                Runnable commit = touch.swipeUpArmed ? alternate : !touch.consumed && !repeatDelete
                        && x >= -dp(8) && x <= getWidth() + dp(8)
                        && y >= -dp(8) && y <= getHeight() + dp(8) ? this::performClick : null;
                if (commit != null && !touch.swipeUpArmed && touchAction != null)
                    commit = touchAction.action(touch.startX, touch.startY);
                finishTouch(touch, commit);
            }
        }
        return true;
    }

    private void moveTouch(Touch touch, float x, float y) {
            float dx = x - touch.startX, dy = y - touch.startY;
            if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) removeCallbacks(touch.hold);
            if (trackpadMove != null && !touch.trackpad && -dy >= dp(24) && -dy > Math.abs(dx)) {
                beginTrackpad(touch);
                if (trackpadSelection != null) trackpadSelection.run();
            } else if (trackpadMove != null && (touch.trackpad || Math.abs(dx) >= dp(10))
                    && Math.abs(dx) > Math.abs(dy) * 0.8f) {
                beginTrackpad(touch);
                int steps = (int) (dx / dp(12));
                int change = steps - touch.trackpadSteps;
                if (change != 0) {
                    touch.trackpadSteps = steps;
                    trackpadMove.accept(change);
                }
            } else if (!touch.consumed && alternate != null && -dy >= dp(swipeUpHint.isEmpty() ? 28 : 24)
                    && -dy > Math.abs(dx)) {
                touch.consumed = true; touch.swipeUpArmed = true; removeCallbacks(touch.hold);
            } else if (!touch.consumed && swipeLeft != null && Math.abs(dx) > dp(30) && Math.abs(dx) > Math.abs(dy)) {
                touch.consumed = true;
                inputOrder.finish(touch.press, dx < 0 ? swipeLeft : swipeRight);
            } else if (!touch.consumed && (x < -dp(8) || x > getWidth() + dp(8)
                    || y < -dp(8) || y > getHeight() + dp(8))) {
                // Keep tracking an upward flick after it leaves the small key.
                // Horizontal exits and downward drags still cancel normally.
                boolean trackingUp = alternate != null && dy < 0 && -dy > Math.abs(dx)
                        && x >= -dp(8) && x <= getWidth() + dp(8);
                if (!trackingUp) {
                    touch.consumed = true; removeCallbacks(touch.hold);
                    inputOrder.finish(touch.press, null);
                }
            }
            updateTouchPressed();
    }

    private void beginTrackpad(Touch touch) {
        if (touch == null || touch.trackpad) return;
        touch.trackpad = true;
        if (getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("gesture_hints", true)) {
            android.content.SharedPreferences tips = getContext().getSharedPreferences("gesture_tips", Context.MODE_PRIVATE);
            if (!tips.getBoolean("trackpad", false)) {
                tips.edit().putBoolean("trackpad", true).apply();
                android.widget.Toast.makeText(getContext(), "左右滑动移动光标，上滑切换选择；编辑面板也可使用方向键", android.widget.Toast.LENGTH_LONG).show();
            }
        }
        touch.consumed = true;
        removeCallbacks(touch.hold);
        inputOrder.finish(touch.press, null);
        touch.press = null;
    }

    private void holdTouch(Touch touch) {
        if (touches.get(touch.id) != touch || touch.consumed || !isAttachedToWindow()) return;
        if (repeatDelete) {
            performClick();
            if (touches.get(touch.id) == touch) postDelayed(touch.hold, Math.max(42, 95 - (++touch.repeats) * 4));
        } else if (trackpadMove != null) {
            // Holding Space enters cursor control without committing a space
            // or consuming the gesture as an unrelated long-click action.
            beginTrackpad(touch);
            updateTouchPressed();
        } else {
            touch.consumed = true;
            updateTouchPressed();
            inputOrder.finish(touch.press, alternate != null ? alternate : () -> performLongClick());
        }
    }

    private void finishTouch(Touch touch, Runnable action) {
        touches.remove(touch.id);
        if (touch.hold != null) removeCallbacks(touch.hold);
        updateTouchPressed();
        inputOrder.finish(touch.press, action);
    }

    private void updateTouchPressed() {
        boolean active = false;
        for (int i = 0; i < touches.size(); i++) active |= !touches.valueAt(i).consumed;
        setPressed(active);
    }

    @Override public boolean performClick() {
        // Sound is emitted on DOWN, not a second time on UP/accessibility click.
        boolean sound = isSoundEffectsEnabled(); setSoundEffectsEnabled(false);
        boolean result = super.performClick(); setSoundEffectsEnabled(sound); return result;
    }
    public void cancelTouch() {
        while (touches.size() > 0) finishTouch(touches.valueAt(0), null);
        setPressed(false);
    }
    @Override protected void onDetachedFromWindow() {
        cancelTouch(); animate().cancel(); setScaleX(1f); setScaleY(1f);
        super.onDetachedFromWindow();
    }
}
