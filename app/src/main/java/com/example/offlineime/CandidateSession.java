package com.example.offlineime;

import java.util.List;

/** Owns selectable-result generations, cached frames, and query cancellation for one IME. */
final class CandidateSession implements AutoCloseable {
    CandidateQueryCoordinator queries;
    volatile String candidateResultKey = "";
    volatile String candidatePendingKey = "";
    volatile String candidateCompleteKey = "";
    String candidateFirstFrameKey = "";
    long candidateFirstFrameAt;
    volatile String candidateSelectableKey = "";
    String measuredCandidateKey = "";
    long measuredCandidateAt;
    boolean measuredCandidateDrawn;
    volatile List<PinyinDictionary.Entry> candidateResults = java.util.Collections.emptyList();
    OfflineImeService.Mode candidateDisplayMode;
    String candidateDisplayInput = "";
    static final class Frame {
        final String input;
        final boolean doubleInput;
        final List<PinyinDictionary.Entry> entries;
        final long expiresAt;
        Frame(String input, boolean doubleInput, List<PinyinDictionary.Entry> entries) {
            this.input = input; this.doubleInput = doubleInput; this.entries = entries;
            long earliest = Long.MAX_VALUE;
            for (PinyinDictionary.Entry entry : entries) {
                if (entry.newWordPriorityUntil > 0) earliest = Math.min(earliest, entry.newWordPriorityUntil);
                if (entry.recentWordPriorityUntil > 0) earliest = Math.min(earliest, entry.recentWordPriorityUntil);
            }
            expiresAt = earliest;
        }
        boolean fresh(long now) { return now < expiresAt; }
    }
    final ByteBudgetCache<Frame> candidateCache = new ByteBudgetCache<>(4 * 1024 * 1024, 512,
            frame -> 48 + frame.input.length() * 2 + ByteBudgetCache.entries(frame.entries), 0);

    void cancel() { if (queries != null) queries.cancel(); }
    void invalidate() {
        candidateCache.evictAll(); cancel();
        candidateResultKey = candidatePendingKey = candidateCompleteKey = candidateSelectableKey = "";
        candidateResults = java.util.Collections.emptyList();
        candidateDisplayMode = null; candidateDisplayInput = "";
    }
    @Override public void close() { if (queries != null) queries.close(); }
}
