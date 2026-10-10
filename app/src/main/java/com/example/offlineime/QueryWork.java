package com.example.offlineime;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;

/** Cancellation reaches SQLite and the loops that parse, compose and rank candidates. */
final class QueryWork {
    private static final ThreadLocal<CancellationSignal> current = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Map<String, Long>> timings = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> firstScreen = new ThreadLocal<>();
    static boolean firstScreen() { return Boolean.TRUE.equals(firstScreen.get()); }
    static void firstScreen(boolean value) { firstScreen.set(value); }
    static void begin(CancellationSignal signal) { current.set(signal); timings.set(new java.util.HashMap<>()); }
    static void end() { current.remove(); timings.remove(); firstScreen.remove(); }
    static void elapsed(String metric, long started) {
        java.util.Map<String, Long> values = timings.get();
        if (values != null) values.merge(metric, Math.max(0, ImePerformanceMonitor.start() - started), Long::sum);
    }
    static void publishTimings(android.content.Context context, int count, boolean t9) {
        java.util.Map<String, Long> values = timings.get();
        if (values != null) for (java.util.Map.Entry<String, Long> value : values.entrySet())
            ImePerformanceMonitor.duration(context, value.getKey(), value.getValue(), count, t9);
    }
    static android.database.Cursor timedCursor(android.database.Cursor cursor, long started) {
        return new android.database.CursorWrapper(cursor) {
            private boolean recorded;
            @Override public void close() {
                try { super.close(); }
                finally { if (!recorded) { recorded = true; elapsed("database_cursor", started); } }
            }
        };
    }
    static CancellationSignal signal() { return current.get(); }
    static void check() {
        if (Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
        CancellationSignal signal = current.get();
        if (signal != null) {
            signal.throwIfCanceled();
        }
    }
}
