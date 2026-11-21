package com.example.offlineime;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Shared immutable UI snapshot; decryption and mutations run only on PersonalDataIO. */
final class QuickPhraseRepository {
    private static QuickPhraseRepository instance;
    static synchronized QuickPhraseRepository get(Context context) {
        if (instance == null) instance = new QuickPhraseRepository(context.getApplicationContext());
        return instance;
    }
    private final QuickPhraseStore store;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<QuickPhraseStore.Entry> snapshot = Collections.emptyList();
    private volatile String error = "";
    private volatile boolean loaded;
    private boolean loading;
    private QuickPhraseRepository(Context context) { store = new QuickPhraseStore(context); }
    void observe(Runnable listener) { listeners.addIfAbsent(listener); reload(); }
    void removeObserver(Runnable listener) { listeners.remove(listener); }
    List<QuickPhraseStore.Entry> list() { if (!loaded) reload(); return snapshot; }
    String status() { return !error.isEmpty() ? error : loaded ? "" : "正在读取快捷短语…"; }
    QuickPhraseStore.Entry find(String id) {
        for (QuickPhraseStore.Entry entry : snapshot) if (entry.id.equals(id)) return entry;
        return null;
    }
    synchronized void reload() {
        if (loading) return;
        loading = true;
        PersonalDataIO.EXECUTOR.execute(() -> {
            readSnapshot();
            synchronized (this) { loading = false; }
            notifyChanged();
        });
    }
    private void readSnapshot() {
        try {
            snapshot = Collections.unmodifiableList(new ArrayList<>(store.list()));
            error = "";
        } catch (RuntimeException failure) {
            snapshot = Collections.emptyList();
            error = "快捷短语读取失败，原数据已保留；可重新打开页面重试";
        }
        loaded = true;
    }
    void save(String id, String text, Consumer<String> completed) {
        mutate(() -> store.save(id, text), completed);
    }
    void remove(String id, Consumer<String> completed) {
        mutate(() -> store.remove(id), completed);
    }
    private void mutate(java.util.function.BooleanSupplier operation, Consumer<String> completed) {
        long generation = PersonalDataIO.generation();
        PersonalDataIO.EXECUTOR.execute(() -> {
            String message = "";
            try {
                if (generation != PersonalDataIO.generation()) message = "本地数据刚刚清除，请重试";
                else if (!operation.getAsBoolean()) message = "保存失败，请重试；原数据已保留";
            }
            catch (RuntimeException failure) { message = failure.getMessage() == null ? "操作失败，原数据已保留" : failure.getMessage(); }
            readSnapshot();
            notifyChanged();
            String result = message;
            PersonalDataIO.MAIN.post(() -> completed.accept(result));
        });
    }
    /** Invoked on the serial IO lane after an explicit global deletion. */
    static synchronized void onDataCleared() {
        if (instance == null) return;
        instance.readSnapshot();
        instance.notifyChanged();
    }
    private void notifyChanged() {
        PersonalDataIO.MAIN.post(() -> { for (Runnable listener : listeners) listener.run(); });
    }
}
