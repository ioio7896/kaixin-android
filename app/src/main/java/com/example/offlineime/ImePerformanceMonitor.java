package com.example.offlineime;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/** Optional timings only: no text, coordinates, editor identity or file logging. */
final class ImePerformanceMonitor {
    private static final String TAG = "OfflineImePerf";
    private static final int WINDOW_SIZE = 100;
    private static final String[] METRICS = {"fast_candidates", "first_selectable", "first_visible", "full_query", "candidate_ready", "queue_wait",
            "lookup", "database_cursor", "sentence", "ranking", "personalization", "context_ranking",
            "ui_bind", "candidate_measure", "ui_draw", "input_view_ready"};
    private static final long[][][] samples = new long[4][METRICS.length][WINDOW_SIZE];
    private static final int[][] counts = new int[4][METRICS.length];
    private static volatile int phase;
    private static boolean shown;
    private static long inputStarted;
    private ImePerformanceMonitor() { }
    static long start() { return SystemClock.elapsedRealtimeNanos(); }
    static synchronized void inputStarted() {
        phase = shown ? 1 : 0; shown = true; inputStarted = start();
    }
    static void firstDraw(Context context) {
        long started;
        synchronized (ImePerformanceMonitor.class) { started = inputStarted; inputStarted = 0; }
        if (started != 0) stage(context, "input_view_ready", started, 0, false);
    }
    static void query(Context context, long started, int count, boolean t9) { stage(context, "full_query", started, count, t9); }
    static void fastCandidates(Context context, long started, int count, boolean t9) { stage(context, "fast_candidates", started, count, t9); }
    static void candidateReady(Context context, long started, int count, boolean t9) { stage(context, "candidate_ready", started, count, t9); }
    static void stage(Context context, String metric, long started, int count, boolean t9) {
        duration(context, metric, Math.max(0L, start() - started), count, t9);
    }
    static void duration(Context context, String metric, long nanos, int count, boolean t9) {
        if (context == null || !context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("performance_logging", false)) return;
        long elapsed = Math.max(0, nanos / 1_000_000L); int currentPhase = phase;
        Log.d(TAG, metric + " phase=" + (currentPhase == 0 ? "cold" : "warm")
                + " mode=" + (t9 ? "t9" : "alpha") + " elapsed=" + elapsed + "ms candidates=" + count);
        record(currentPhase * 2 + (t9 ? 1 : 0), metric, elapsed);
    }
    private static int budgetMs(String metric) {
        if (metric.startsWith("ui_") || metric.equals("candidate_measure")) return 8;
        if (metric.equals("queue_wait")) return 16;
        if (metric.equals("first_selectable") || metric.equals("first_visible") || metric.equals("fast_candidates")) return 50;
        if (metric.equals("input_view_ready")) return 200;
        return 150;
    }
    private static synchronized void record(int phase, String metric, long elapsed) {
        int index = -1;
        for (int i = 0; i < METRICS.length; i++) if (METRICS[i].equals(metric)) { index = i; break; }
        if (index < 0) return;
        int count = counts[phase][index]++;
        samples[phase][index][count % WINDOW_SIZE] = elapsed;
        if ((count + 1) % WINDOW_SIZE != 0) return;
        long[] sorted = samples[phase][index].clone(); java.util.Arrays.sort(sorted);
        String summary = metric + " phase=" + (phase < 2 ? "cold" : "warm") + " mode=" + (phase % 2 == 1 ? "t9" : "alpha")
                + " p50=" + sorted[49] + "ms p95=" + sorted[94] + "ms p99=" + sorted[98] + "ms";
        int budget = budgetMs(metric);
        if (sorted[94] > budget || sorted[98] > budget * 2) Log.w(TAG, summary + " budget=" + budget + "ms");
        else Log.d(TAG, summary);
    }
}
