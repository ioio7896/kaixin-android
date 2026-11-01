package com.example.offlineime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic ranking by frequency, usage and spelling quality. */
final class CandidateRanker {
    private static final int COMMON_CORPUS_SCORE = 4000;
    private static final int COMMON_RANK_SCORE = corpusScore(COMMON_CORPUS_SCORE);
    private static final int STRONG_PERSONAL_SCORE = 200;
    private static final int STRONG_CONTEXT_SCORE = 240;
    private static final int TOP_CHANGE_MARGIN = 150;

    private CandidateRanker() {}

    static boolean isPriority(PinyinDictionary.Entry entry) {
        return isPriority(entry, rankingScore(entry));
    }

    private static boolean isPriority(PinyinDictionary.Entry entry, int score) {
        // Being a complete spelling says nothing about a word's frequency.
        // Repeated local choices or strong context can promote a personal word.
        return entry.pinned || personalScore(entry.personalBonus) >= STRONG_PERSONAL_SCORE
                || contextScore(entry.contextBonus) >= STRONG_CONTEXT_SCORE
                || entry.matchTier != PinyinDictionary.TIER_COMPOSED && score >= COMMON_RANK_SCORE;
    }

    private static int corpusScore(long score) {
        return (int) Math.round(1000 * Math.log1p(Math.max(0L, score) / 1000.0));
    }

    private static int personalScore(int bonus) {
        return Math.max(-640, Math.min(2100, bonus)) / 3;
    }

    private static int contextScore(int bonus) {
        return Math.max(0, Math.min(1550, bonus)) * 450 / 1550;
    }

    static boolean hasStrongWordSupport(PinyinDictionary.Entry entry) {
        return entry.pinned || entry.corpusScore >= COMMON_CORPUS_SCORE
                || personalScore(entry.personalBonus) >= STRONG_PERSONAL_SCORE
                || contextScore(entry.contextBonus) >= STRONG_CONTEXT_SCORE;
    }

    private static boolean isCompleteMatch(PinyinDictionary.Entry entry) {
        return entry.rankingTier < 35 && (entry.matchTier == PinyinDictionary.TIER_USER
                || entry.matchTier == PinyinDictionary.TIER_EXACT_FULL
                || entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                || entry.matchTier == PinyinDictionary.TIER_MIXED);
    }

    /** Keep a nearly tied first choice only within the same unchanged query. */
    static List<PinyinDictionary.Entry> stabilizeTop(List<PinyinDictionary.Entry> previous,
            List<PinyinDictionary.Entry> current, boolean preferSingleCharacter) {
        if (previous.isEmpty() || current.isEmpty()) return current;
        PinyinDictionary.Entry old = previous.get(0), first = current.get(0);
        if (old.word.equals(first.word) || !isCompleteMatch(first)) return current;
        for (int i = 1; i < current.size(); i++) {
            PinyinDictionary.Entry retained = current.get(i);
            if (!retained.word.equals(old.word) || !retained.pronunciation.equals(old.pronunciation)) continue;
            if (!isCompleteMatch(retained) || retained.pinned != first.pinned
                    || preferSingleCharacter && (retained.codePointLength == 1) != (first.codePointLength == 1)
                    || new RankedEntry(first).score - new RankedEntry(retained).score >= TOP_CHANGE_MARGIN)
                return current;
            ArrayList<PinyinDictionary.Entry> stable = new ArrayList<>(current);
            stable.remove(i); stable.add(0, retained);
            return stable;
        }
        return current;
    }

    private static int rankingScore(PinyinDictionary.Entry entry) {
        long base = (long) entry.score - entry.personalBonus - entry.contextBonus;
        int personal = personalScore(entry.personalBonus);
        int context = contextScore(entry.contextBonus);
        // The decoder already supplies log probabilities, not corpus frequencies.
        if (entry.matchTier == PinyinDictionary.TIER_COMPOSED) {
            // Word decoder scores sum log frequencies. Normalize per word before
            // comparing with corpus candidates so sentence length is not a penalty.
            if (!entry.wordSegments.isEmpty()) {
                double averageLogFrequency = base / (1000.0 * entry.wordSegments.size())
                        + SentenceComposer.WORD_LOG_BASE;
                int normalized = (int) Math.round(1000 * Math.log1p(Math.exp(Math.max(0, Math.min(18, averageLogFrequency))) / 1000));
                return normalized - 260 - Math.min(240, entry.wordSegments.size() * 30) + personal + context;
            }
            return corpusScore(Math.max(0L, base) / Math.max(1, entry.codePointLength)) - 500 + personal + context;
        }
        int corpus = corpusScore(entry.corpusScore);
        int adjustment = Math.max(-500, Math.min(450, corpusScore(base) - corpus));
        int score = corpus + adjustment + personal + context;
        if (!entry.pinned && entry.corpusScore < COMMON_CORPUS_SCORE
                && personal < STRONG_PERSONAL_SCORE && context < STRONG_CONTEXT_SCORE
                && entry.codePointLength > 2 && containsHan(entry.word)) {
            // An obscure phrase must not gain a length advantage just by matching
            // a complete spelling. Keep it available further down the same list.
            score -= Math.min(320, (entry.codePointLength - 2) * 80);
        }
        return score;
    }

    private static boolean containsHan(String word) {
        for (int offset = 0; offset < word.length();) {
            int cp = word.codePointAt(offset);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
            offset += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isFrequentShortWord(PinyinDictionary.Entry entry) {
        // Use the original corpus frequency, never hot/personal/context bonuses.
        // Full, abbreviated and mixed spelling share the same short-word policy.
        return (entry.codePointLength == 2 || entry.codePointLength == 3)
                && entry.corpusScore >= COMMON_CORPUS_SCORE
                && (entry.matchTier == PinyinDictionary.TIER_EXACT_FULL
                    || entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                    || entry.matchTier == PinyinDictionary.TIER_MIXED)
                && containsHan(entry.word);
    }

    static int pageSize(int requested) {
        return Math.max(1, Math.min(15, requested));
    }

    static int pageCount(int count, int size) {
        return Math.max(1, (Math.max(0, count) + pageSize(size) - 1) / pageSize(size));
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
        return rankTop(source, preferSingleCharacter, limit, "");
    }

    /** Preserve exact, common three/four-character and short-word pools for later ranking. */
    static List<PinyinDictionary.Entry> rankForRecall(
            Iterable<PinyinDictionary.Entry> source, boolean preferSingleCharacter,
            int limit, String input) {
        List<PinyinDictionary.Entry> ranked = rankTop(source, preferSingleCharacter, limit, input);
        java.util.HashSet<String> included = new java.util.HashSet<>();
        for (PinyinDictionary.Entry entry : ranked) included.add(entry.word);
        ArrayList<PinyinDictionary.Entry> exact = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> commonShort = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> commonLong = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> four = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> five = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry == null || entry.word == null || included.contains(entry.word)) continue;
            if (entry.rankingTier < 35 && entry.matchTier == PinyinDictionary.TIER_EXACT_FULL)
                exact.add(entry);
            if (entry.rankingTier < 35 && isFrequentShortWord(entry)) commonShort.add(entry);
            if (isCompleteMatch(entry) && entry.corpusScore >= COMMON_CORPUS_SCORE
                    && (entry.codePointLength == 3 || entry.codePointLength == 4)
                    && containsHan(entry.word)) commonLong.add(entry);
            if (isCompleteMatch(entry) && containsHan(entry.word)
                    && (entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                        || entry.matchTier == PinyinDictionary.TIER_MIXED)) {
                if (entry.codePointLength == 4) four.add(entry);
                if (entry.codePointLength == 5) five.add(entry);
            }
        }
        for (PinyinDictionary.Entry entry : rankTop(exact, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(commonShort, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(commonLong, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(four, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(five, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        return ranked;
    }

    static List<PinyinDictionary.Entry> rankTop(
            Iterable<PinyinDictionary.Entry> source, boolean preferSingleCharacter, int limit, String input) {
        if (limit <= 0) return new ArrayList<>();
        Comparator<RankedEntry> ranking = comparator(preferSingleCharacter);
        ArrayList<RankedEntry> out = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry != null && entry.word != null) out.add(new RankedEntry(entry));
        }
        out.sort(ranking);
        QueryWork.check();
        return entries(out.subList(0, Math.min(limit, out.size())));
    }

    private static ArrayList<PinyinDictionary.Entry> entries(List<RankedEntry> ranked) {
        ArrayList<PinyinDictionary.Entry> out = new ArrayList<>(ranked.size());
        for (RankedEntry value : ranked) out.add(value.entry);
        return out;
    }

    private static final class RankedEntry {
        final PinyinDictionary.Entry entry;
        final int score;
        final int incomplete;

        RankedEntry(PinyinDictionary.Entry entry) {
            this.entry = entry;
            score = rankingScore(entry) - matchPenalty(entry) - Math.max(0, entry.correctionPenalty);
            incomplete = entry.matchTier == PinyinDictionary.TIER_PREFIX
                    || entry.rankingTier >= 35 && entry.matchTier != PinyinDictionary.TIER_COMPOSED ? 1 : 0;
        }
    }

    private static int matchPenalty(PinyinDictionary.Entry entry) {
        if (entry.matchTier == PinyinDictionary.TIER_COMPOSED) return 0;
        if (entry.matchTier == PinyinDictionary.TIER_FUZZY) return 1100;
        if (entry.rankingTier >= 35) return 1800;
        if (entry.matchTier <= PinyinDictionary.TIER_EXACT_FULL) return 0;
        if (entry.matchTier == PinyinDictionary.TIER_MIXED) return 60;
        if (entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT) return 120;
        if (entry.matchTier == PinyinDictionary.TIER_PREFIX) return 700;
        return 350;
    }

    private static Comparator<RankedEntry> comparator(boolean preferSingleCharacter) {
        Comparator<RankedEntry> ranking = preferSingleCharacter
                ? Comparator.comparingInt((RankedEntry value) -> value.entry.codePointLength == 1 ? 0 : 1)
                        .thenComparingInt(value -> value.entry.pinned ? 0 : 1)
                : Comparator.comparingInt((RankedEntry value) -> value.entry.pinned ? 0 : 1);
        return ranking.thenComparingInt(value -> reliabilityGroup(value.entry))
                .thenComparing(Comparator.comparingInt((RankedEntry value) -> value.score).reversed())
                .thenComparingInt(value -> value.incomplete)
                .thenComparingInt(value -> value.entry.rankingTier)
                .thenComparing(Comparator.comparingInt((RankedEntry value) -> value.entry.corpusScore).reversed())
                .thenComparing(value -> value.entry.word);
    }

    private static int reliabilityGroup(PinyinDictionary.Entry entry) {
        // A high-frequency correction may not displace a complete valid input.
        // Complete full, abbreviated and mixed words compete on one score scale.
        // Decoded sentences, corrections and incomplete completions follow.
        if (entry.matchTier == PinyinDictionary.TIER_COMPOSED) return 1;
        if (entry.matchTier == PinyinDictionary.TIER_FUZZY) return 2;
        if (entry.rankingTier >= 35 || entry.matchTier == PinyinDictionary.TIER_PREFIX) return 3;
        return 0;
    }
}
