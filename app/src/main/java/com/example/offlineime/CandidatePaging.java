package com.example.offlineime;

/** Candidate strip paging; ranking itself lives in the native engine. */
final class CandidatePaging {
    private CandidatePaging() { }

    static int pageSize(int requested) {
        return Math.max(1, Math.min(15, requested));
    }

    static int pageCount(int count, int size) {
        return Math.max(1, (Math.max(0, count) + pageSize(size) - 1) / pageSize(size));
    }
}
