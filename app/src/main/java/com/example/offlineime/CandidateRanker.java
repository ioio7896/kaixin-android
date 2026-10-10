package com.example.offlineime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Deterministic ranking, with an explicit single-character-first policy for one-syllable input. */
final class CandidateRanker {
    private CandidateRanker() {}

    static boolean isPriority(PinyinDictionary.Entry entry) {
        return entry.pinned || entry.matchTier == PinyinDictionary.TIER_COMPOSED || entry.rankingTier <= PinyinDictionary.TIER_MIXED
                || entry.corpusScore >= 4000;
    }

    static List<PinyinDictionary.Entry> rank(Iterable<PinyinDictionary.Entry> source) {
        return rank(source, false);
    }

    static List<PinyinDictionary.Entry> rank(
            Iterable<PinyinDictionary.Entry> source, boolean preferSingleCharacter) {
        return rankTop(source, preferSingleCharacter, Integer.MAX_VALUE);
    }

    static List<PinyinDictionary.Entry> rankTop(
            Iterable<PinyinDictionary.Entry> source, boolean preferSingleCharacter, int limit) {
        if (limit <= 0) return new ArrayList<>();
        Comparator<PinyinDictionary.Entry> ranking = comparator(preferSingleCharacter);
        if (limit != Integer.MAX_VALUE) {
            PriorityQueue<PinyinDictionary.Entry> best = new PriorityQueue<>(
                    Math.max(1, limit), ranking.reversed());
            for (PinyinDictionary.Entry entry : source) {
                QueryWork.check();
                if (entry == null || entry.word == null) continue;
                if (best.size() < limit) best.add(entry);
                else if (ranking.compare(entry, best.peek()) < 0) {
                    best.poll();
                    best.add(entry);
                }
            }
            ArrayList<PinyinDictionary.Entry> out = new ArrayList<>(best);
            out.sort(ranking);
            QueryWork.check();
            return out;
        }
        ArrayList<PinyinDictionary.Entry> out = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry != null && entry.word != null) out.add(entry);
        }
        out.sort(ranking);
        QueryWork.check();
        return out;
    }

    private static Comparator<PinyinDictionary.Entry> comparator(boolean preferSingleCharacter) {
        Comparator<PinyinDictionary.Entry> ranking = preferSingleCharacter
                ? Comparator.comparingInt((PinyinDictionary.Entry entry) -> entry.codePointLength == 1 ? 0 : 1)
                        .thenComparingInt(entry -> entry.rankingTier)
                : Comparator.comparingInt((PinyinDictionary.Entry entry) -> entry.rankingTier);
        return ranking.thenComparing(Comparator.comparingInt(
                        (PinyinDictionary.Entry entry) -> entry.score).reversed())
                .thenComparing(Comparator.comparingInt(
                        (PinyinDictionary.Entry entry) -> entry.codePointLength).reversed())
                .thenComparing(entry -> entry.word);
    }
}
