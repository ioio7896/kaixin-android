package com.example.offlineime;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Owns clipboard storage lifecycle; the input service reads only immutable snapshots. */
final class ClipboardController implements AutoCloseable {
    private final ClipboardHistoryStore store;
    private final Runnable changed;
    private final Consumer<String> failed;
    private volatile List<ClipboardHistoryStore.Entry> snapshot = Collections.emptyList();
    private volatile boolean closed;
    private long generation;
    private boolean loading, loaded;
    ClipboardController(Context context, Runnable changed, Consumer<String> failed) {
        store = new ClipboardHistoryStore(context);
        this.changed = changed; this.failed = failed;
    }
    List<ClipboardHistoryStore.Entry> listEntries() { ensureLoaded(); return snapshot; }
    private synchronized void ensureLoaded() {
        if (closed || loading || loaded) return;
        loading = true;
        submit(() -> { }, null);
    }
    void add(String text) { submit(() -> store.add(text), null); }
    void addIfAbsent(String text) { submit(() -> store.addIfAbsent(text), null); }
    void remove(String text) { submit(() -> store.remove(text), null); }
    void togglePinned(String text) { submit(() -> store.togglePinned(text), null); }
    void removeAll(java.util.Collection<String> values) {
        List<String> copy = new ArrayList<>(values);
        submit(() -> store.removeAll(copy), null);
    }
    void restore(List<ClipboardHistoryStore.Entry> entries) {
        List<ClipboardHistoryStore.Entry> copy = new ArrayList<>(entries);
        submit(() -> store.restore(copy), null);
    }
    void clearWithUndo(Consumer<List<ClipboardHistoryStore.Entry>> completed) {
        final List<ClipboardHistoryStore.Entry> before = new ArrayList<>();
        submit(() -> { before.addAll(store.snapshot()); store.clear(); }, () -> completed.accept(before));
    }
    private void submit(Runnable operation, Runnable completed) {
        final long request;
        final long epoch = PersonalDataIO.generation();
        synchronized (this) { if (closed) return; request = generation; }
        PersonalDataIO.EXECUTOR.execute(() -> {
            synchronized (this) {
                // Accepted edits finish before the queued close. Only deletion invalidates persistence.
                if (epoch != PersonalDataIO.generation()) { loading = false; return; }
            }
            try {
                operation.run();
                List<ClipboardHistoryStore.Entry> result = Collections.unmodifiableList(new ArrayList<>(store.listEntries()));
                PersonalDataIO.MAIN.post(() -> {
                    synchronized (this) {
                        if (closed || request != generation || epoch != PersonalDataIO.generation()) return;
                        snapshot = result; loaded = true; loading = false;
                    }
                    if (completed != null) completed.run();
                    changed.run();
                });
            } catch (RuntimeException error) {
                PersonalDataIO.MAIN.post(() -> {
                    synchronized (this) { if (closed || request != generation || epoch != PersonalDataIO.generation()) return; loading = false; loaded = true; }
                    failed.accept("剪贴板操作失败，原数据已保留；可重新打开面板重试");
                });
            }
        });
    }
    void releaseMemory() {
        synchronized (this) { loaded = false; snapshot = Collections.emptyList(); }
        PersonalDataIO.EXECUTOR.execute(store::releaseMemory);
    }
    void retryLoad() {
        synchronized (this) { loaded = false; }
        ensureLoaded();
    }
    void resumeAfterGlobalClear() {
        synchronized (this) { generation++; loaded = false; loading = false; snapshot = Collections.emptyList(); }
        PersonalDataIO.EXECUTOR.execute(store::resumeAfterGlobalClear);
        changed.run();
    }
    @Override public void close() {
        synchronized (this) { closed = true; generation++; snapshot = Collections.emptyList(); }
        // No Future.get or disk work on the lifecycle callback; pending persistence finishes in order.
        PersonalDataIO.EXECUTOR.execute(() -> {
            try { store.close(); }
            catch (RuntimeException error) { android.util.Log.e("OfflineImeClipboard", "clipboard close failed: " + error.getClass().getSimpleName()); }
        });
    }
}
