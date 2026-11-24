package com.example.offlineime;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.util.SparseArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Routes unhandled key-gap touches to the closest key without changing its visual spacing. */
final class KeyTouchRow extends LinearLayout {
    private final SparseArray<KeyButton> gapTargets = new SparseArray<>();
    private final ArrayList<KeyButton> dispatchTargets = new ArrayList<>();
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[32];
    private final MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[32];
    KeyTouchRow(Context context) {
        super(context);
        setMotionEventSplittingEnabled(true);
        // View invokes its delegate only for otherwise unhandled touches. The
        // target KeyButton keeps click, long-press, swipe and accessibility logic.
        setTouchDelegate(new TouchDelegate(new Rect(), this) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                return dispatchGapTouch(event);
            }

            @Override public AccessibilityNodeInfo.TouchDelegateInfo getTouchDelegateInfo() {
                if (Build.VERSION.SDK_INT < 29) return null;
                // Report the actual key nodes, not the non-clickable row. Keep
                // their non-overlapping bounds for screen-reader hit detection.
                Map<Region, View> targets = new LinkedHashMap<>();
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (!(child instanceof KeyButton) || !child.isEnabled()
                            || child.getVisibility() != VISIBLE) continue;
                    Rect bounds = new Rect();
                    child.getHitRect(bounds);
                    if (!bounds.isEmpty()) targets.put(new Region(bounds), child);
                }
                return targets.isEmpty() ? super.getTouchDelegateInfo()
                        : new AccessibilityNodeInfo.TouchDelegateInfo(targets);
            }
        });
    }

    private boolean dispatchGapTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            KeyButton gapTarget = null;
            float density = getResources().getDisplayMetrics().density;
            float nearest = Float.MAX_VALUE;
            float touchX = event.getX(index);
            float touchY = event.getY(index);
            KeyButton firstVisible = null;
            KeyButton lastVisible = null;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof KeyButton) || !child.isEnabled()
                        || child.getVisibility() != VISIBLE) continue;
                KeyButton key = (KeyButton) child;
                if (firstVisible == null) firstVisible = key;
                lastVisible = key;
                float x = Math.max(child.getLeft(), Math.min(touchX, child.getRight() - 1));
                float y = Math.max(child.getTop(), Math.min(touchY, child.getBottom() - 1));
                float dx = x - touchX, dy = y - touchY;
                float distance = dx * dx + dy * dy;
                float limit = (key.inputLetter() == 0 ? 8 : 10) * density;
                if (distance <= limit * limit && distance < nearest) {
                    nearest = distance; gapTarget = key;
                }
            }
            // In horizontal rows (e.g. QWERTY row 2 with outer insets), route outer
            // horizontal margin touches to the first/last key instead of dropping them.
            if (gapTarget == null && getOrientation() == HORIZONTAL && firstVisible != null
                    && touchY >= 0 && touchY < getHeight()) {
                if (touchX < firstVisible.getLeft()) gapTarget = firstVisible;
                else if (touchX >= lastVisible.getRight()) gapTarget = lastVisible;
            }
            if (gapTarget != null) gapTargets.put(event.getPointerId(index), gapTarget);
        }
        if (gapTargets.size() == 0) return false;
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
