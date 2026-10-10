package com.example.offlineime;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.util.SparseArray;
import java.util.ArrayList;

/** Routes otherwise unhandled key-gap touches to the closest key, at most 6 dp away. */
final class KeyTouchRow extends LinearLayout {
    private final SparseArray<KeyButton> gapTargets = new SparseArray<>();
    private final ArrayList<KeyButton> dispatchTargets = new ArrayList<>();
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[32];
    private final MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[32];
    KeyTouchRow(Context context) { super(context); setMotionEventSplittingEnabled(true); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            KeyButton gapTarget = null;
            float limit = 6 * getResources().getDisplayMetrics().density;
            float nearest = limit * limit;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof KeyButton) || !child.isEnabled()
                        || child.getVisibility() != VISIBLE) continue;
                float x = Math.max(child.getLeft(), Math.min(event.getX(index), child.getRight() - 1));
                float y = Math.max(child.getTop(), Math.min(event.getY(index), child.getBottom() - 1));
                float dx = x - event.getX(index), dy = y - event.getY(index);
                if (dx * dx + dy * dy <= nearest) {
                    nearest = dx * dx + dy * dy; gapTarget = (KeyButton) child;
                }
            }
            if (gapTarget != null) gapTargets.put(event.getPointerId(index), gapTarget);
        }
        if (gapTargets.size() == 0) return super.onTouchEvent(event);
        dispatchTargets.clear();
        for (int i = 0; i < gapTargets.size(); i++)
            if (!dispatchTargets.contains(gapTargets.valueAt(i))) dispatchTargets.add(gapTargets.valueAt(i));
        for (KeyButton target : dispatchTargets) {
            MotionEvent mapped = mapForTarget(event, target);
            if (mapped == null) continue;
            try { target.dispatchTouchEvent(mapped); }
            finally { mapped.recycle(); }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
            gapTargets.remove(event.getPointerId(event.getActionIndex()));
        else if (action == MotionEvent.ACTION_CANCEL) gapTargets.clear();
        return true;
    }

    /** Preserve pointer IDs and remap the action index using public SDK APIs. */
    private MotionEvent mapForTarget(MotionEvent event, KeyButton target) {
        int count = 0, changed = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (gapTargets.get(event.getPointerId(i)) != target) continue;
            if (properties[count] == null) {
                properties[count] = new MotionEvent.PointerProperties();
                coordinates[count] = new MotionEvent.PointerCoords();
            }
            event.getPointerProperties(i, properties[count]);
            event.getPointerCoords(i, coordinates[count]);
            coordinates[count].x -= target.getLeft();
            coordinates[count].y -= target.getTop();
            if (i == event.getActionIndex()) changed = count;
            count++;
        }
        if (count == 0) return null;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            if (changed < 0) action = MotionEvent.ACTION_MOVE;
            else if (count == 1) action = action == MotionEvent.ACTION_POINTER_DOWN
                    ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
            else action |= changed << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        }
        return MotionEvent.obtain(event.getDownTime(), event.getEventTime(), action, count,
                properties, coordinates, event.getMetaState(), event.getButtonState(),
                event.getXPrecision(), event.getYPrecision(), event.getDeviceId(),
                event.getEdgeFlags(), event.getSource(), event.getFlags());
    }
    @Override protected void onDetachedFromWindow() {
        for (int i = 0; i < gapTargets.size(); i++) gapTargets.valueAt(i).cancelTouch();
        gapTargets.clear(); super.onDetachedFromWindow();
    }
}
