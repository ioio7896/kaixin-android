package com.example.offlineime;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class ClipboardPolicyTest {
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test public void pinnedItemsSurviveAgeAndRecentLimit() {
        long now = 100 * DAY;
        List<ClipboardHistoryStore.Entry> values = Arrays.asList(
                entry("old pinned", now - 90 * DAY, true),
                entry("new", now - DAY, false),
                entry("older", now - 2 * DAY, false),
                entry("expired", now - 8 * DAY, false));
        assertEquals(Arrays.asList("old pinned", "new"), names(
                ClipboardHistoryStore.applyPolicy(values, 1, 7, now)));
    }

    @Test public void permanentRetentionStillHonorsCountAndNewestOrder() {
        long now = 100 * DAY;
        List<ClipboardHistoryStore.Entry> values = Arrays.asList(
                entry("three", now - 3 * DAY, false), entry("one", now - DAY, false),
                entry("two", now - 2 * DAY, false));
        assertEquals(Arrays.asList("one", "two"), names(
                ClipboardHistoryStore.applyPolicy(values, 2, 0, now)));
    }

    @Test public void nullRecordsAndZeroLimitAreHandled() {
        assertEquals(Arrays.asList("fixed"), names(ClipboardHistoryStore.applyPolicy(Arrays.asList(
                null, entry("recent", 10, false), entry("fixed", 1, true)), 0, 0, 10)));
    }

    private static ClipboardHistoryStore.Entry entry(String text, long time, boolean pinned) {
        return new ClipboardHistoryStore.Entry(text, time, pinned);
    }

    private static List<String> names(List<ClipboardHistoryStore.Entry> values) {
        return values.stream().map(entry -> entry.text).collect(Collectors.toList());
    }
}
