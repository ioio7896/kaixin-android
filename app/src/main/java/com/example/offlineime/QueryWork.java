package com.example.offlineime;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;

/** Cancellation reaches SQLite and the loops that parse, compose and rank candidates. */
final class QueryWork {
    private static final ThreadLocal<CancellationSignal> current = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Map<String, Long>> timings = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> firstScreen = new ThreadLocal<>();
    private static final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> limited = new ThreadLocal<>();
    private static final ThreadLocal<StageData> stages = new ThreadLocal<>();
    static final class StageData {
        // Owned sequentially by the first-screen lane, then its supplement.
        final java.util.Map<String, Rows> rows = new java.util.LinkedHashMap<>();
        int bytes;
    }
    private static final class Rows {
        final java.util.List<Object[]> values;
        final boolean complete;
        final int bytes;
        Rows(java.util.List<Object[]> values, boolean complete, int bytes) {
            this.values = values; this.complete = complete; this.bytes = bytes;
        }
    }
    static boolean firstScreen() { return Boolean.TRUE.equals(firstScreen.get()); }
    static void firstScreen(boolean value) {
        firstScreen(value, true);
    }
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
    static void begin(CancellationSignal signal) { begin(signal, null); }
    static void begin(CancellationSignal signal, StageData data) {
        current.set(signal); timings.set(new java.util.HashMap<>()); limited.set(false); stages.set(data);
    }
    static void end() {
        current.remove(); timings.remove(); firstScreen.remove(); deadline.remove(); limited.remove(); stages.remove();
    }
    /** Reuse immutable database rows, including exhausted small result sets and empty results.
     * A larger second-stage limit fetches only the missing tail. Never reuse a truncated set as complete. */
    static android.database.Cursor selectRows(String key, String[] columns, int limit,
            java.util.function.IntFunction<android.database.Cursor> query) {
        StageData data = stages.get();
        if (data == null || limit <= 0 || limit > 256) return query.apply(0);
        Rows previous = data.rows.get(key);
        if (previous == null && !firstScreen()) return query.apply(0);
        java.util.List<Object[]> values = previous == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(previous.values);
        boolean complete = previous != null && previous.complete;
        int bytes = previous == null ? 64 + key.length() * 2 : previous.bytes;
        if (!complete && values.size() < limit) {
            try (android.database.Cursor cursor = query.apply(values.size())) {
                while (cursor.moveToNext()) {
                    check();
                    Object[] row = new Object[columns.length]; bytes += 24 + columns.length * 8;
                    for (int i = 0; i < columns.length; i++) {
                        switch (cursor.getType(i)) {
                            case android.database.Cursor.FIELD_TYPE_INTEGER: row[i] = cursor.getLong(i); bytes += 24; break;
                            case android.database.Cursor.FIELD_TYPE_FLOAT: row[i] = cursor.getDouble(i); bytes += 24; break;
                            case android.database.Cursor.FIELD_TYPE_BLOB: row[i] = cursor.getBlob(i); bytes += ((byte[]) row[i]).length; break;
                            case android.database.Cursor.FIELD_TYPE_STRING: row[i] = cursor.getString(i); bytes += 40 + ((String) row[i]).length() * 2; break;
                            default: row[i] = null;
                        }
                    }
                    values.add(row);
                }
                complete = values.size() < limit;
            }
            check();
            int retained = data.bytes - (previous == null ? 0 : previous.bytes) + bytes;
            if (retained <= 256 * 1024 && (previous != null || data.rows.size() < 16)) {
                data.rows.put(key, new Rows(values, complete, bytes)); data.bytes = retained;
            }
        }
        android.database.MatrixCursor cursor = new android.database.MatrixCursor(columns, Math.min(limit, values.size()));
        for (int i = 0; i < Math.min(limit, values.size()); i++) cursor.addRow(values.get(i));
        return cursor;
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
