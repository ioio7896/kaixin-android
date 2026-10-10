package com.example.offlineime;

import java.util.ArrayDeque;

/** Gesture results are resolved on UP, then delivered in the order fingers landed. */
final class OrderedKeyInput {
    static final class Press {
        boolean ready, discarded;
        Runnable action;
    }
    private final ArrayDeque<Press> pending = new ArrayDeque<>();
    private boolean draining;
    private Runnable idle;
    void setOnIdle(Runnable action) { idle = action; }
    boolean hasPending() { return !pending.isEmpty(); }

    Press begin() {
        Press press = new Press();
        pending.addLast(press);
        return press;
    }

    void finish(Press press, Runnable action) {
        if (press == null || press.ready || press.discarded) return;
        press.action = action;
        press.ready = true;
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
        for (Press press : pending) { press.discarded = true; press.action = null; }
        pending.clear();
    }
}
