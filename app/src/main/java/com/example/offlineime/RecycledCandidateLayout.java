package com.example.offlineime;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Full flow geometry, bounded live cells. No dependency on an OEM layout manager. */
final class RecycledCandidateLayout extends ViewGroup {
    interface Binder { void bind(TextView view, int position); }
    private final List<String> words;
    private final Binder binder;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint firstPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Rect> bounds = new ArrayList<>();
    private final ArrayList<Integer> rowStarts = new ArrayList<>();
    private final SparseArray<TextView> attached = new SparseArray<>();
    private final ArrayDeque<TextView> pool = new ArrayDeque<>();
    private final int minimumWidth, cellHeight, horizontalPadding, gap;
    private final boolean numbered;
    private int geometryWidth = -1, totalHeight, viewportTop, viewportHeight, rows;

    String focusedWord() {
        for (int i = 0; i < attached.size(); i++) if (attached.valueAt(i).isAccessibilityFocused())
            return words.get(attached.keyAt(i));
        return "";
    }
    int wordTop(String word) {
        int position = words.indexOf(word);
        if (position < 0 || bounds.isEmpty()) return -1;
        return bounds.get(position).top;
    }

    public RecycledCandidateLayout(Context context) {
        this(context, java.util.Collections.emptyList(),
                17 * context.getResources().getDisplayMetrics().scaledDensity, null, null, false,
                Math.round(48 * context.getResources().getDisplayMetrics().density),
                Math.round(48 * context.getResources().getDisplayMetrics().density), 0, null);
    }

    /** {@code textPx} 与 {@code typeface} 必须和单元格实际字体一致，避免加粗或序号导致截断。 */
    RecycledCandidateLayout(Context context, List<String> words, float textPx,
                            android.graphics.Typeface typeface, android.graphics.Typeface firstTypeface, boolean numbered,
                            int minimumWidth, int cellHeight, int padding, Binder binder) {
        super(context);
        this.words = new ArrayList<>(words);
        this.binder = binder;
        this.minimumWidth = minimumWidth;
        this.cellHeight = cellHeight;
        this.horizontalPadding = padding;
        this.numbered = numbered;
        gap = 0;
        paint.setTextSize(textPx);
        if (typeface != null) paint.setTypeface(typeface);
        firstPaint.setTextSize(textPx);
        firstPaint.setTypeface(firstTypeface != null ? firstTypeface : paint.getTypeface());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClipChildren(false);
    }
    void viewport(int top, int height) {
        top = Math.max(0, top); height = Math.max(cellHeight, height);
        if (top != viewportTop || height != viewportHeight) {
            viewportTop = top; viewportHeight = height; requestLayout();
        }
    }
    private void geometry(int width) {
        if (geometryWidth == width) return;
        geometryWidth = width; bounds.clear(); rowStarts.clear(); rows = 0;
        if (!words.isEmpty()) rowStarts.add(0);
        int left = 0, top = 0, first = 0;
        for (int i = 0; i < words.size(); i++) {
            Paint measure = i == 0 ? firstPaint : paint;
            float textWidth = measure.measureText(words.get(i));
            if (numbered) textWidth += measure.measureText((i + 1) + " ") * CandidateTextStyle.NUMBER_SCALE;
            int desired = Math.max(minimumWidth, (int) Math.ceil(textWidth) + horizontalPadding * 2);
            int cellWidth = Math.min(width, desired);
            if (left > 0 && left + cellWidth > width) {
                fillRow(first, i, width - (left - gap));
                first = i; left = 0; top += cellHeight + gap; rows++;
                rowStarts.add(i);
            }
            bounds.add(new Rect(left, top, left + cellWidth, top + cellHeight));
            left += cellWidth + gap;
        }
        if (!words.isEmpty()) rows++;
        totalHeight = words.isEmpty() ? cellHeight : top + cellHeight;
    }
    private void fillRow(int first, int end, int spare) {
        int count = end - first, offset = 0;
        for (int i = first; i < end; i++) {
            Rect rect = bounds.get(i);
            int extra = Math.max(0, spare) / count + (i - first < Math.max(0, spare) % count ? 1 : 0);
            extra = Math.min(extra, Math.round(32 * getResources().getDisplayMetrics().density));
            rect.offset(offset, 0); rect.right += extra; offset += extra;
        }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        long started = ImePerformanceMonitor.start();
        int width = Math.max(1, MeasureSpec.getSize(widthSpec));
        geometry(width);
        int visibleHeight = viewportHeight > 0 ? viewportHeight : getResources().getDisplayMetrics().heightPixels / 2;
        int fromY = Math.max(0, viewportTop - cellHeight * 2), toY = viewportTop + visibleHeight + cellHeight * 2;
        for (int i = attached.size() - 1; i >= 0; i--) {
            int position = attached.keyAt(i); TextView view = attached.valueAt(i);
            Rect rect = bounds.get(position);
            if ((rect.bottom < fromY || rect.top > toY) && !view.isAccessibilityFocused()
                    && !view.hasFocus() && !view.isPressed()) {
                removeViewInLayout(view); attached.removeAt(i);
                if (pool.size() < 48) pool.addLast(view);
            }
        }
        int firstRow = Math.min(rows, Math.max(0, (fromY - cellHeight + cellHeight + gap - 1) / (cellHeight + gap)));
        int endRow = Math.min(rows, toY / (cellHeight + gap) + 1);
        int firstPosition = firstRow < rows ? rowStarts.get(firstRow) : words.size();
        int endPosition = endRow < rows ? rowStarts.get(endRow) : words.size();
        for (int position = firstPosition; position < endPosition; position++) {
            Rect rect = bounds.get(position);
            if (rect.bottom < fromY || rect.top > toY) continue;
            TextView view = attached.get(position);
            if (view == null) {
                view = pool.pollFirst();
                if (view == null) {
                    view = new TextView(getContext());
                    view.setId(View.generateViewId());
                }
                if (binder == null) view.setText(words.get(position));
                else binder.bind(view, position);
                attached.put(position, view);
                addViewInLayout(view, -1, new LayoutParams(rect.width(), cellHeight), true);
            }
        }
        for (int i = 0; i < attached.size(); i++) {
            TextView view = attached.valueAt(i); Rect rect = bounds.get(attached.keyAt(i));
            view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(totalHeight, heightSpec));
        ImePerformanceMonitor.stage(getContext(), "candidate_measure", started, attached.size(), false);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int previousId = View.NO_ID;
        for (int i = 0; i < attached.size(); i++) {
            Rect rect = bounds.get(attached.keyAt(i));
            TextView view = attached.valueAt(i);
            view.setAccessibilityTraversalAfter(previousId);
            previousId = view.getId();
            view.layout(rect.left, rect.top, rect.right, rect.bottom);
        }
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(rows, -1, false));
    }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-2, -2); }
}
