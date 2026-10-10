package com.example.offlineime;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/** Packs short candidates tightly while giving phrases their natural width. */
final class DenseCandidateLayout extends ViewGroup {
    private final List<Rect> cells = new ArrayList<>();
    private final int gap;

    DenseCandidateLayout(Context context) {
        super(context);
        gap = Math.round(2 * getResources().getDisplayMetrics().density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED)
            width = getResources().getDisplayMetrics().widthPixels;
        int available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        while (cells.size() < getChildCount()) cells.add(new Rect());
        int first = -1, used = 0, rowHeight = 0, top = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) { cells.get(i).setEmpty(); continue; }
            LayoutParams params = child.getLayoutParams();
            int childWidth = params.width >= 0 ? Math.min(available, params.width) : available;
            child.measure(MeasureSpec.makeMeasureSpec(childWidth,
                            params.width == LayoutParams.WRAP_CONTENT ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, params.height),
                            params.height >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
            int cellWidth = Math.min(available, child.getMeasuredWidth());
            if (first >= 0 && used + gap + cellWidth > available) {
                top += finishRow(first, i, used, rowHeight, available, top, true) + gap;
                first = -1; used = 0; rowHeight = 0;
            }
            if (first < 0) first = i;
            else used += gap;
            cells.get(i).set(0, 0, cellWidth, 0);
            used += cellWidth;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        if (first >= 0) top += finishRow(first, getChildCount(), used, rowHeight, available, top, false);
        setMeasuredDimension(resolveSize(width, widthSpec),
                resolveSize(top + getPaddingBottom(), heightSpec));
    }

    private int finishRow(int first, int end, int used, int height, int available, int top, boolean fill) {
        int count = 0;
        for (int i = first; i < end; i++) if (getChildAt(i).getVisibility() != GONE) count++;
        int spare = fill ? Math.max(0, available - used) : 0;
        int left = getPaddingLeft(), position = 0;
        for (int i = first; i < end; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            Rect bounds = cells.get(i);
            int width = bounds.width() + spare / count + (position < spare % count ? 1 : 0);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            bounds.set(left, top, left + width, top + height);
            left += width + gap;
            position++;
        }
        return height;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            Rect bounds = cells.get(i);
            child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
