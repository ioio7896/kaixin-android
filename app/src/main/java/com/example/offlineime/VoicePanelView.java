package com.example.offlineime;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Offline voice dictation panel: status bar, scrollable live preview, and cancel/finish controls. */
final class VoicePanelView extends LinearLayout {
    private final TextView statusView;
    private final TextView previewView;
    private final ScrollView scrollView;
    private final KeyButton finishButton;
    private final long styleRevision;

    public VoicePanelView(Context context) {
        this(context, KeyboardTheme.from(context, KeyboardTheme.resolveDark(context)),
                KeyboardView.requireService(context), -1L, 0, 2, 2);
    }

    VoicePanelView(Context context, KeyboardTheme theme, KeyboardView.Callback callback,
                   long styleRevision, int bottomHeightPx, int horizontalGapDp, int verticalGapDp) {
        super(context);
        this.styleRevision = styleRevision;
        setOrientation(VERTICAL);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setPadding(dp(12), dp(6), dp(12), dp(6));

        statusView = new TextView(context);
        statusView.setTextColor(theme.accent);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(statusView, new LayoutParams(-1, dp(36)));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        previewView = new TextView(context);
        previewView.setTextColor(theme.text);
        previewView.setTextSize(19);
        previewView.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewView.setContentDescription("语音识别文字预览");
        scrollView.addView(previewView, new ScrollView.LayoutParams(-1, -2));
        body.addView(scrollView, new LayoutParams(-1, 0, 1f));
        addView(body, new LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new KeyTouchRow(context);
        addActionKey(buttons, "取消", "语音取消", theme, callback, horizontalGapDp, verticalGapDp);
        finishButton = addActionKey(buttons, "完成", "语音完成", theme, callback, horizontalGapDp, verticalGapDp);
        addView(buttons, new LayoutParams(-1, bottomHeightPx > 0 ? bottomHeightPx : dp(50)));
    }

    long styleRevision() {
        return styleRevision;
    }

    void update(String status, String preview, boolean finishing) {
        statusView.setText(status);
        boolean followTail = !scrollView.canScrollVertically(1);
        previewView.setText(preview.isEmpty() ? "请说普通话，识别结果会显示在这里" : preview);
        if (followTail) {
            scrollView.post(() -> {
                if (isAttachedToWindow()) scrollView.fullScroll(View.FOCUS_DOWN);
            });
        }
        finishButton.setEnabled(!finishing);
        finishButton.setAlpha(finishing ? 0.5f : 1f);
    }

    private KeyButton addActionKey(LinearLayout row, String label, String action,
                                   KeyboardTheme theme, KeyboardView.Callback callback,
                                   int horizontalGapDp, int verticalGapDp) {
        KeyButton button = new KeyButton(getContext(), label, true, theme);
        button.setOnClickListener(v -> callback.onKey(action));
        LayoutParams params = new LayoutParams(0, -1, 1f);
        params.setMargins(dp(horizontalGapDp), dp(verticalGapDp), dp(horizontalGapDp), dp(verticalGapDp));
        row.addView(button, params);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
