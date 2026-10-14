package com.example.offlineime;

import android.os.CancellationSignal;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns one running query and at most one replacement; every stage shares cancellation. */
final class CandidateQueryCoordinator {
    private static ThreadPoolExecutor lane(String name, int priority) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1), runnable -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(priority);
            runnable.run();
        }, name);
        thread.setDaemon(true);
        return thread;
        });
    }
    private final ThreadPoolExecutor executor = lane("offline-candidate-screen", android.os.Process.THREAD_PRIORITY_DEFAULT);
    private final ThreadPoolExecutor supplement = lane("offline-candidate-supplement", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private volatile int generation;
    private CancellationSignal cancellation;
    private Future<?> task;
    private Future<?> supplementTask;

    static final class Ticket {
        final int generation;
        final CancellationSignal cancellation = new CancellationSignal();
        final QueryWork.StageData stages = new QueryWork.StageData();
        Ticket(int generation) { this.generation = generation; }
    }
    Ticket next() {
        cancel();
        Ticket ticket = new Ticket(generation);
        cancellation = ticket.cancellation;
        return ticket;
    }
    void submit(Ticket ticket, Runnable work) {
        if (isCurrent(ticket)) task = executor.submit(work);
    }
    synchronized void submitSupplement(Ticket ticket, Runnable work) {
        if (isCurrent(ticket)) supplementTask = supplement.submit(work);
    }
    boolean isCurrent(Ticket ticket) {
        return ticket.generation == generation && !ticket.cancellation.isCanceled()
                && !executor.isShutdown();
    }
    synchronized void cancel() {
        generation++;
        if (cancellation != null) cancellation.cancel();
        if (task != null) task.cancel(true);
        if (supplementTask != null) supplementTask.cancel(true);
        executor.getQueue().clear();
        executor.purge();
        supplement.getQueue().clear(); supplement.purge();
        cancellation = null;
        task = null;
        supplementTask = null;
    }
    void close() { cancel(); executor.shutdownNow(); supplement.shutdownNow(); }
}
