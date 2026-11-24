package com.example.offlineime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline voice dictation panel: waveform status bar, draft banner, scrollable preview, and controls. */
final class VoicePanelView extends LinearLayout {
    /** Lightweight 7-bar audio level visualizer driven by capture RMS updates. */
    public static final class WaveformView extends View {
        private static final int BAR_COUNT = 7;
        private static final float[] WEIGHTS = {0.42f, 0.72f, 0.94f, 1.0f, 0.90f, 0.68f, 0.45f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barRect = new RectF();
        private final float[] heights = new float[BAR_COUNT];
        private int activeColor = 0xff2f6fed;
        private int idleColor = 0x55888888;
        private boolean active;
        private int tick;

        public WaveformView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
        }

        void configure(int activeColor, int idleColor) {
            this.activeColor = activeColor;
            this.idleColor = idleColor;
            invalidate();
        }

        void setLevel(float level, boolean listening) {
            this.active = listening;
            tick++;
            float clamped = listening ? Math.max(0f, Math.min(1f, level)) : 0f;
            for (int i = 0; i < BAR_COUNT; i++) {
                float ripple = listening
                        ? 0.82f + 0.18f * (float) Math.sin(tick * 0.65 + i * 0.95)
                        : 1f;
                float target = listening ? Math.max(0.16f, clamped * WEIGHTS[i] * ripple) : 0.16f;
                heights[i] = heights[i] * 0.45f + target * 0.55f;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            paint.setColor(active ? activeColor : idleColor);
            float gap = Math.max(2f, width * 0.06f);
            float barWidth = Math.max(2f, (width - gap * (BAR_COUNT - 1)) / BAR_COUNT);
            float radius = barWidth * 0.5f;
            float maxBarHeight = height * 0.86f;
            float minBarHeight = Math.max(barWidth, height * 0.18f);
            float centerY = height * 0.5f;
            float left = (width - (barWidth * BAR_COUNT + gap * (BAR_COUNT - 1))) * 0.5f;
            for (int i = 0; i < BAR_COUNT; i++) {
                float h = minBarHeight + (maxBarHeight - minBarHeight) * heights[i];
                barRect.set(left, centerY - h * 0.5f, left + barWidth, centerY + h * 0.5f);
                canvas.drawRoundRect(barRect, radius, radius, paint);
                left += barWidth + gap;
            }
        }
    }

    private final WaveformView waveformView;
    private final TextView statusView;
    private final TextView quickSendButton;
    private final TextView timerView;
    private final LinearLayout draftBanner;
    private final TextView draftTextView;
    private final TextView previewView;
    private final ScrollView scrollView;
    private final KeyButton clearButton;
    private final KeyButton backspaceButton;
    private final KeyButton pauseButton;
    private final KeyButton finishButton;
    private final long styleRevision;
    private final int normalTextColor;
    private final int hintTextColor;

    public VoicePanelView(Context context) {
        this(context, KeyboardTheme.from(context, KeyboardTheme.resolveDark(context)),
                KeyboardView.requireService(context), -1L, 0, 2, 2);
    }

    VoicePanelView(Context context, KeyboardTheme theme, KeyboardView.Callback callback,
                   long styleRevision, int bottomHeightPx, int horizontalGapDp, int verticalGapDp) {
        super(context);
        this.styleRevision = styleRevision;
        this.normalTextColor = theme.text;
        this.hintTextColor = blendColor(theme.text, theme.background, 0.45f);
        setOrientation(VERTICAL);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setPadding(dp(12), dp(4), dp(12), dp(4));

        // Top status header: animated waveform + status text + optional send pill + recording timer.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        waveformView = new WaveformView(context);
        waveformView.configure(theme.accent, blendColor(theme.text, theme.background, 0.65f));
        LayoutParams waveParams = new LayoutParams(dp(42), dp(22));
        waveParams.rightMargin = dp(8);
        header.addView(waveformView, waveParams);

        statusView = new TextView(context);
        statusView.setTextColor(theme.accent);
        statusView.setTextSize(13);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(statusView, new LayoutParams(0, -2, 1f));

        quickSendButton = new TextView(context);
        quickSendButton.setTextColor(theme.accent);
        quickSendButton.setTextSize(12);
        quickSendButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        quickSendButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        quickSendButton.setBackground(pillDrawable(blendColor(theme.function, theme.accent, theme.isDark ? 0.28f : 0.14f), 14));
        quickSendButton.setOnClickListener(v -> callback.onKey("语音完成并发送"));
        quickSendButton.setVisibility(GONE);
        LayoutParams sendParams = new LayoutParams(-2, -2);
        sendParams.leftMargin = dp(6);
        header.addView(quickSendButton, sendParams);

        timerView = new TextView(context);
        timerView.setTextColor(hintTextColor);
        timerView.setTextSize(12);
        timerView.setTypeface(Typeface.MONOSPACE);
        timerView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        timerView.setText(context.getString(R.string.voice_timer, 0, 0));
        LayoutParams timerParams = new LayoutParams(-2, -2);
        timerParams.leftMargin = dp(8);
        header.addView(timerView, timerParams);
        body.addView(header, new LayoutParams(-1, dp(34)));

        // Optional in-memory draft recovery banner when prior dictation was interrupted.
        draftBanner = new LinearLayout(context);
        draftBanner.setOrientation(HORIZONTAL);
        draftBanner.setGravity(Gravity.CENTER_VERTICAL);
        draftBanner.setPadding(dp(10), dp(5), dp(6), dp(5));
        draftBanner.setBackground(pillDrawable(blendColor(theme.function, theme.accent, theme.isDark ? 0.20f : 0.10f), 10));
        draftBanner.setVisibility(GONE);

        draftTextView = new TextView(context);
        draftTextView.setTextColor(theme.text);
        draftTextView.setTextSize(12);
        draftTextView.setSingleLine(true);
        draftTextView.setEllipsize(TextUtils.TruncateAt.END);
        draftBanner.addView(draftTextView, new LayoutParams(0, -2, 1f));

        TextView restoreBtn = new TextView(context);
        restoreBtn.setText("恢复");
        restoreBtn.setTextColor(theme.accent);
        restoreBtn.setTextSize(12);
        restoreBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        restoreBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        restoreBtn.setContentDescription("恢复上次未上屏语音草稿");
        restoreBtn.setOnClickListener(v -> callback.onKey("语音恢复草稿"));
        draftBanner.addView(restoreBtn, new LayoutParams(-2, -2));

        TextView dismissBtn = new TextView(context);
        dismissBtn.setText("×");
        dismissBtn.setTextColor(hintTextColor);
        dismissBtn.setTextSize(15);
        dismissBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        dismissBtn.setContentDescription("清除未上屏语音草稿");
        dismissBtn.setOnClickListener(v -> callback.onKey("语音清除草稿"));
        draftBanner.addView(dismissBtn, new LayoutParams(-2, -2));

        LayoutParams draftParams = new LayoutParams(-1, -2);
        draftParams.bottomMargin = dp(4);
        body.addView(draftBanner, draftParams);

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        previewView = new TextView(context);
        previewView.setTextColor(theme.text);
        previewView.setTextSize(18);
        previewView.setLineSpacing(dp(3), 1f);
        previewView.setPadding(dp(6), dp(6), dp(6), dp(6));
        previewView.setContentDescription("语音识别文字预览");
        scrollView.addView(previewView, new ScrollView.LayoutParams(-1, -2));
        body.addView(scrollView, new LayoutParams(-1, 0, 1f));
        addView(body, new LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new KeyTouchRow(context);
        addActionKey(buttons, "取消", "语音取消", 1f, theme, callback, horizontalGapDp, verticalGapDp);
        clearButton = addActionKey(buttons, "清空", "语音清空", 1f, theme, callback, horizontalGapDp, verticalGapDp);
        backspaceButton = addActionKey(buttons, "⌫", "语音退格", 1f, theme, callback, horizontalGapDp, verticalGapDp);
        backspaceButton.setContentDescription("删除语音识别末尾文字");
        pauseButton = addActionKey(buttons, "暂停", "语音暂停", 1f, theme, callback, horizontalGapDp, verticalGapDp);
        finishButton = addActionKey(buttons, "完成", "语音完成", 1.35f, theme, callback, horizontalGapDp, verticalGapDp);
        finishButton.setEmphasized(theme);
        addView(buttons, new LayoutParams(-1, bottomHeightPx > 0 ? bottomHeightPx : dp(50)));
    }

    long styleRevision() {
        return styleRevision;
    }

    void update(String status, String preview, boolean finishing, boolean paused,
                float audioLevel, long elapsedMs, boolean segmentCommitMode,
                String draftPreview, String quickSendLabel) {
        setKeepScreenOn(!finishing && !paused);
        statusView.setText(status);
        updateAudio(audioLevel, elapsedMs, !finishing && !paused);

        boolean hasPreview = preview != null && !preview.isEmpty();
        boolean followTail = !scrollView.canScrollVertically(1);
        if (hasPreview) {
            previewView.setTextColor(normalTextColor);
            previewView.setText(preview);
        } else {
            previewView.setTextColor(hintTextColor);
            previewView.setText(getContext().getString(segmentCommitMode
                    ? R.string.voice_placeholder_segment : R.string.voice_placeholder_confirm));
        }
        if (followTail) {
            scrollView.post(() -> {
                if (isAttachedToWindow()) scrollView.fullScroll(View.FOCUS_DOWN);
            });
        }

        boolean showDraft = !hasPreview && !finishing && draftPreview != null && !draftPreview.isEmpty();
        draftBanner.setVisibility(showDraft ? VISIBLE : GONE);
        if (showDraft) {
            draftTextView.setText(getContext().getString(R.string.voice_draft_banner, draftPreview));
        }

        boolean showQuickSend = hasPreview && !finishing && quickSendLabel != null && !quickSendLabel.isEmpty();
        quickSendButton.setVisibility(showQuickSend ? VISIBLE : GONE);
        if (showQuickSend) {
            quickSendButton.setText(getContext().getString(R.string.voice_finish_and_action, quickSendLabel));
        }

        setKeyEnabled(clearButton, hasPreview && !finishing);
        setKeyEnabled(backspaceButton, hasPreview && !finishing);
        pauseButton.setText(paused ? "继续" : "暂停");
        pauseButton.setContentDescription(paused ? "继续语音录音" : "暂停语音录音");
        setKeyEnabled(pauseButton, !finishing);
        setKeyEnabled(finishButton, !finishing);
    }

    /** Fast-path audio meter and duration refresh without triggering a full keyboard re-layout. */
    void updateAudio(float audioLevel, long elapsedMs, boolean activeListening) {
        setKeepScreenOn(activeListening);
        waveformView.setLevel(audioLevel, activeListening);
        long totalSec = Math.max(0L, elapsedMs / 1000L);
        int mins = (int) Math.min(99L, totalSec / 60L);
        int secs = (int) (totalSec % 60L);
        timerView.setText(getContext().getString(R.string.voice_timer, mins, secs));
    }

    @Override protected void onDetachedFromWindow() {
        setKeepScreenOn(false);
        super.onDetachedFromWindow();
    }

    private static void setKeyEnabled(KeyButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private KeyButton addActionKey(LinearLayout row, String label, String action, float weight,
                                   KeyboardTheme theme, KeyboardView.Callback callback,
                                   int horizontalGapDp, int verticalGapDp) {
        KeyButton button = new KeyButton(getContext(), label, true, theme);
        button.setOnClickListener(v -> callback.onKey(action));
        LayoutParams params = new LayoutParams(0, -1, weight);
        params.setMargins(dp(horizontalGapDp), dp(verticalGapDp), dp(horizontalGapDp), dp(verticalGapDp));
        row.addView(button, params);
        return button;
    }

    private GradientDrawable pillDrawable(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private static int blendColor(int from, int to, float ratio) {
        int a = Math.round(((from >>> 24) & 0xff) * (1f - ratio) + ((to >>> 24) & 0xff) * ratio);
        int r = Math.round(((from >>> 16) & 0xff) * (1f - ratio) + ((to >>> 16) & 0xff) * ratio);
        int g = Math.round(((from >>> 8) & 0xff) * (1f - ratio) + ((to >>> 8) & 0xff) * ratio);
        int b = Math.round((from & 0xff) * (1f - ratio) + (to & 0xff) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
