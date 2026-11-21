package com.example.offlineime;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.TextView;
import java.util.function.Consumer;

/** Observes touch snapshots while TextView retains click, long-click and cancellation handling. */
final class TouchTrackingTextView extends TextView {
    private Consumer<MotionEvent> touchObserver;

    TouchTrackingTextView(Context context) { super(context); }

    void setTouchObserver(Consumer<MotionEvent> observer) { touchObserver = observer; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (isEnabled() && onFilterTouchEventForSecurity(event) && touchObserver != null)
            touchObserver.accept(event);
        return super.dispatchTouchEvent(event);
    }

    /** Shared entry point for ordinary taps, custom drag handles and accessibility actions. */
    @Override public boolean performClick() { return super.performClick(); }
}
