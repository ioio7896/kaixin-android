package com.example.offlineime;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;

/** Cancellation and timing for one candidate query on the worker lanes. */
final class QueryWork {
    private static final ThreadLocal<CancellationSignal> current = new ThreadLocal<>();
    private static final ThreadLocal<Long> cancelGen = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Map<String, Long>> timings = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> firstScreen = new ThreadLocal<>();
    private static final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> limited = new ThreadLocal<>();

    private QueryWork() { }

    static boolean firstScreen() { return Boolean.TRUE.equals(firstScreen.get()); }
    static void firstScreen(boolean value) { firstScreen(value, true); }
    static void firstScreen(boolean value, boolean budgeted) {
        firstScreen.set(value);
        deadline.set(value && budgeted ? ImePerformanceMonitor.start() + 24_000_000L : Long.MAX_VALUE);
    }
    static boolean shouldYield(int candidates) {
        check();
        Long until = deadline.get();
        if (!firstScreen() || candidates == 0 || until == null || ImePerformanceMonitor.start() < until) return false;
        limited.set(true);
        return true;
    }
    static boolean wasLimited() { return Boolean.TRUE.equals(limited.get()); }
    static void begin(CancellationSignal signal, long nativeCancelGen) {
        current.set(signal);
        cancelGen.set(nativeCancelGen);
        timings.set(new java.util.HashMap<>());
        limited.set(false);
    }
    static void end() {
        current.remove();
        cancelGen.remove();
        timings.remove();
        firstScreen.remove();
        deadline.remove();
        limited.remove();
    }
    static void elapsed(String metric, long started) {
        java.util.Map<String, Long> values = timings.get();
        if (values != null) values.merge(metric, Math.max(0, ImePerformanceMonitor.start() - started), Long::sum);
    }
    static void publishTimings(android.content.Context context, int count, boolean t9) {
        java.util.Map<String, Long> values = timings.get();
        if (values != null) for (java.util.Map.Entry<String, Long> value : values.entrySet())
            ImePerformanceMonitor.duration(context, value.getKey(), value.getValue(), count, t9);
    }
    static CancellationSignal signal() { return current.get(); }
    static long cancelGen() {
        Long value = cancelGen.get();
        return value == null ? 0L : value;
    }
    static void check() {
        if (Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
        CancellationSignal signal = current.get();
        if (signal != null) signal.throwIfCanceled();
    }
}
