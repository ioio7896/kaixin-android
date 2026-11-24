package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Recycles visible grid rows while allowing wide tiles for text faces. */
final class EmojiGridAdapter extends BaseAdapter {
    private final Context context;
    private final EmojiCatalog catalog;
    private final SharedPreferences prefs;
    private final KeyboardTheme theme;
    private final Consumer<String> commit;
    private final BiConsumer<View, String> menu;
    private final List<Row> rows = new ArrayList<>();

    private static final class Row {
        final List<String> values;
        final int columns, height;
        final boolean text;

        Row(List<String> values, int columns, int height, boolean text) {
            this.values = values; this.columns = columns; this.height = height; this.text = text;
        }
    }

    EmojiGridAdapter(Context context, EmojiCatalog catalog, SharedPreferences prefs, KeyboardTheme theme,
                    Consumer<String> commit, BiConsumer<View, String> menu) {
        this.context = context; this.catalog = catalog; this.prefs = prefs; this.theme = theme;
        this.commit = commit; this.menu = menu;
    }

    void submit(List<String> values, int width) {
        rows.clear();
        for (int from = 0; from < values.size();) {
            boolean text = EmojiCatalog.isTextFace(values.get(from));
            int columns = text ? Math.max(1, Math.min(4, width / 135))
                    : Math.max(1, Math.min(14, width / 42));
            int end = from + 1;
            while (end < values.size() && end - from < columns
                    && EmojiCatalog.isTextFace(values.get(end)) == text) end++;
            int height = text ? 44 : Math.max(40, Math.min(48, width / columns));
            rows.add(new Row(values.subList(from, end), columns, height, text));
            from = end;
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Object getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Row row = rows.get(position);
        LinearLayout line = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(context);
        line.setLayoutParams(new AbsListView.LayoutParams(-1, dp(row.height)));
        while (line.getChildCount() < row.columns) {
            TextView tile = new TextView(context);
            tile.setGravity(Gravity.CENTER);
            tile.setMaxLines(2);
            line.addView(tile, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        while (line.getChildCount() > row.columns) line.removeViewAt(line.getChildCount() - 1);
        for (int i = 0; i < row.columns; i++) {
            TextView tile = (TextView) line.getChildAt(i);
            if (i >= row.values.size()) {
                tile.setVisibility(View.INVISIBLE);
                continue;
            }
            String glyph = row.values.get(i);
            String output = EmojiPreferences.preferred(prefs, catalog, glyph);
            tile.setVisibility(View.VISIBLE);
            tile.setText(output);
            tile.setTextSize(row.text ? 15 : 23);
            tile.setPadding(row.text ? dp(3) : 0, 0, row.text ? dp(3) : 0, 0);
            tile.setTextColor(theme.text);
            tile.setContentDescription(catalog.description(output));
            tile.setOnClickListener(v -> commit.accept(output));
            tile.setOnLongClickListener(v -> { menu.accept(v, glyph); return true; });
            AccessibilitySupport.longClick(tile, "选择肤色、收藏或调整顺序");
        }
        return line;
    }

    private int dp(int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }
}
