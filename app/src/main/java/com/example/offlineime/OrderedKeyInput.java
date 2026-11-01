package com.example.offlineime;

import java.util.ArrayDeque;

/** Gesture results are delivered in landing order; plain taps may resolve on the next DOWN. */
final class OrderedKeyInput {
    static final class Press {
        boolean ready, discarded;
        Runnable action, earlyAction;
    }
    private final ArrayDeque<Press> pending = new ArrayDeque<>();
    private boolean draining;
    private Runnable idle;
    void setOnIdle(Runnable action) { idle = action; }
    boolean hasPending() { return !pending.isEmpty(); }

    Press begin() {
        Press press = new Press();
        pending.addLast(press);
        // A second finger confirms an unmoved plain key as a tap. The new
        // press stays queued so callbacks still run in landing order.
        boolean resolved = false;
        for (Press earlier : pending) {
            if (earlier == press) break;
            if (!earlier.ready && earlier.earlyAction != null) {
                earlier.action = earlier.earlyAction;
                earlier.earlyAction = null;
                earlier.ready = true;
                resolved = true;
            }
        }
        if (resolved) drain();
        return press;
    }

    void armEarly(Press press, Runnable action) {
        if (press != null && !press.ready && !press.discarded) press.earlyAction = action;
    }

    void disarmEarly(Press press) {
        if (press != null) press.earlyAction = null;
    }

    void finish(Press press, Runnable action) {
        if (press == null || press.ready || press.discarded) return;
        press.action = action;
        press.earlyAction = null;
        press.ready = true;
        drain();
    }

    private void drain() {
        if (draining) return;
        draining = true;
        try {
            while (!pending.isEmpty() && pending.peekFirst().ready) {
                Press next = pending.removeFirst();
                if (!next.discarded && next.action != null) next.action.run();
            }
        } finally {
            draining = false;
            if (pending.isEmpty() && idle != null) idle.run();
        }
    }

    void cancelAll() {
        for (Press press : pending) { press.discarded = true; press.action = press.earlyAction = null; }
        pending.clear();
    }
}
