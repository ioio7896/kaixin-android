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
        /** {@code position} 为候选序号（从 0 开始），空状态提示传 -1。 */
        void style(TextView view, String word, int position);
        void select(PinyinDictionary.Entry entry);
        void touching(boolean active);
    }
    private Host host;
    private RecycledCandidateLayout grid;
    String focusedWord() { return grid == null ? "" : grid.focusedWord(); }
    void scrollToWord(String word) {
        if (grid == null || word.isEmpty()) return;
        int top = grid.wordTop(word);
        if (top < 0) return;
        grid.viewport(top, getHeight());
        scrollTo(0, top);
    }
    public CandidatePanel(Context context) {
        super(context);
        setFillViewport(true); setVerticalScrollBarEnabled(true); setScrollbarFadingEnabled(false);
        setScrollBarStyle(SCROLLBARS_INSIDE_OVERLAY);
        setPadding(dp(2), dp(3), dp(2), dp(3));
    }

    CandidatePanel(Context context, List<PinyinDictionary.Entry> entries, List<Integer> order,
                   CandidateTextStyle style, Host host) {
        this(context); this.host = host;
        List<PinyinDictionary.Entry> snapshot = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int index : order) { snapshot.add(entries.get(index)); labels.add(entries.get(index).word); }
        if (labels.isEmpty()) {
            TextView empty = new TextView(context); empty.setText("没有候选词"); empty.setGravity(Gravity.CENTER);
            host.style(empty, "没有候选词", -1);
            addView(empty, new LayoutParams(-1, Math.max(dp(72), style.pageCellHeightPx(context))));
        } else {
            grid = createGrid(context, labels, style, (view, position) -> {
                PinyinDictionary.Entry entry = snapshot.get(position);
                view.setSelected(false); view.setPressed(false); view.setActivated(false);
                host.style(view, entry.word, position);
                view.setFocusable(true);
                String description = entry.emojiSuggestion
                        ? "表情 " + EmojiCatalog.get(context).description(entry.word)
                        : (position == 0 ? "首选候选词 " : "候选词 ") + entry.word;
                view.setContentDescription(style.pageNumbers ? (position + 1) + "，" + description : description);
                view.setOnClickListener(v -> host.select(entry));
                view.setLongClickable(false);
            });
            addView(grid, new LayoutParams(-1, -2));
        }
    }
    static RecycledCandidateLayout createGrid(Context context, List<String> labels, CandidateTextStyle style,
                                                RecycledCandidateLayout.Binder binder) {
        float density = context.getResources().getDisplayMetrics().density;
        return new RecycledCandidateLayout(context, labels, style.textPx(context, true),
                style.typeface(false, true), style.typeface(true, true), style.pageNumbers,
                Math.round(style.pageMinCellDp * density), style.pageCellHeightPx(context),
                Math.round(style.pagePaddingDp * density), binder);
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
        if (host != null && action == MotionEvent.ACTION_DOWN) host.touching(true);
        try { return super.dispatchTouchEvent(event); }
        finally {
            if (host != null && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL))
                post(() -> host.touching(false));
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
