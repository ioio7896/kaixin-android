package com.example.offlineime;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Owns expanded candidate scrolling, recycling and immutable click snapshots. */
final class CandidatePanel extends ScrollView {
    interface Host {
        void style(TextView view, String word, boolean first);
        void select(PinyinDictionary.Entry entry);
        void touching(boolean active);
    }
    private final Host host;
    private RecycledCandidateLayout grid;
    String focusedWord() { return grid == null ? "" : grid.focusedWord(); }
    void restoreFocus(String word) {
        if (grid == null || word.isEmpty()) return;
        int top = grid.focusWord(word);
        grid.viewport(top, getHeight());
        scrollTo(0, top);
    }
    CandidatePanel(Context context, List<PinyinDictionary.Entry> entries, List<Integer> order,
                   float textSp, String density, Host host) {
        super(context); this.host = host;
        setFillViewport(true); setVerticalScrollBarEnabled(true); setScrollbarFadingEnabled(false);
        setScrollBarStyle(SCROLLBARS_INSIDE_OVERLAY);
        setPadding(dp(2), dp(3), dp(2), dp(3));
        int minWidth = "comfortable".equals(density) ? 56 : 48;
        int minHeight = "comfortable".equals(density) ? 56 : 48;
        int padding = "compact".equals(density) ? 5 : "comfortable".equals(density) ? 10 : 8;
        List<PinyinDictionary.Entry> snapshot = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int index : order) { snapshot.add(entries.get(index)); labels.add(entries.get(index).word); }
        if (labels.isEmpty()) {
            TextView empty = new TextView(context); empty.setText("没有候选词"); empty.setGravity(Gravity.CENTER);
            host.style(empty, "没有候选词", false);
            addView(empty, new LayoutParams(-1, dp(72)));
        } else {
            float scaled = getResources().getDisplayMetrics().scaledDensity;
            int height = Math.max(dp(minHeight), (int) Math.ceil(textSp * scaled * 1.4f) + dp(8));
            grid = new RecycledCandidateLayout(context, labels, textSp, dp(minWidth), height, dp(padding), (view, position) -> {
                PinyinDictionary.Entry entry = snapshot.get(position);
                view.setSelected(false); view.setPressed(false); view.setActivated(false);
                host.style(view, entry.word, position == 0);
                view.setPadding(dp(padding), dp(2), dp(padding), dp(2));
                view.setMinWidth(0); view.setMinHeight(0); view.setIncludeFontPadding(false);
                view.setSingleLine(true); view.setEllipsize(android.text.TextUtils.TruncateAt.END);
                view.setFocusable(true);
                view.setContentDescription((position == 0 ? "首选候选词 " : "候选词 ") + entry.word);
                view.setOnClickListener(v -> host.select(entry));
                view.setLongClickable(false);
            });
            addView(grid, new LayoutParams(-1, -2));
        }
    }
    @Override protected void onScrollChanged(int l, int t, int oldL, int oldT) {
        super.onScrollChanged(l, t, oldL, oldT);
        if (grid != null) grid.viewport(t, getHeight());
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (grid != null) grid.viewport(getScrollY(), h);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) host.touching(true);
        try { return super.dispatchTouchEvent(event); }
        finally {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                post(() -> host.touching(false));
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
