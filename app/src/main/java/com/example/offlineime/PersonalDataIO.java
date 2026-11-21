package com.example.offlineime;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** One process-wide lane orders personal-data writes and explicit deletion. */
final class PersonalDataIO {
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();
    static long generation() { return GENERATION.get(); }
    static void beginClear() { GENERATION.incrementAndGet(); }
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "ime-personal-data");
        thread.setDaemon(true);
        return thread;
    });
    static { EXECUTOR.setRemoveOnCancelPolicy(true); }
    private PersonalDataIO() { }
}
