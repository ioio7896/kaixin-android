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
            List<PinyinDictionary.Entry> current, boolean preferSingleCharacter, String input) {
        if (previous.isEmpty() || current.isEmpty()) return current;
        PinyinDictionary.Entry old = previous.get(0), first = current.get(0);
        // A complete two-syllable word must replace a longer first-frame choice.
        if (first.codePointLength == 2 && old.codePointLength > 2
                && isExactTwoSyllableWord(first, input)) return current;
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

    static boolean isExactTwoSyllableWord(PinyinDictionary.Entry entry, String input) {
        if (entry == null || input == null || entry.codePointLength != 2
                || entry.matchTier != PinyinDictionary.TIER_EXACT_FULL
                || entry.pronunciation.isEmpty()) return false;
        String[] syllables = entry.pronunciation.split("[ ']+");
        return syllables.length == 2
                && (syllables[0] + syllables[1]).equals(PinyinInput.normalize(input).replace("'", ""));
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
        ArrayList<PinyinDictionary.Entry> exactTwo = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> commonShort = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> commonLong = new ArrayList<>();
        boolean numericInput = input.matches("[2-9']+");
        ArrayList<PinyinDictionary.Entry> three = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> completions = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> four = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> five = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry == null || entry.word == null || included.contains(entry.word)) continue;
            if (isExactTwoSyllableWord(entry, input)) exactTwo.add(entry);
            if (NineWordCandidates.isCompletion(entry, input)) completions.add(entry);
            if (entry.rankingTier < 35 && entry.matchTier == PinyinDictionary.TIER_EXACT_FULL)
                exact.add(entry);
            if (entry.rankingTier < 35 && isFrequentShortWord(entry)) commonShort.add(entry);
            if (isCompleteMatch(entry) && entry.corpusScore >= COMMON_CORPUS_SCORE
                    && (entry.codePointLength == 3 || entry.codePointLength == 4)
                    && containsHan(entry.word)) commonLong.add(entry);
            if (isCompleteMatch(entry) && containsHan(entry.word)
                    && (entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                        || entry.matchTier == PinyinDictionary.TIER_MIXED)) {
                if (entry.codePointLength == 3 && numericInput) three.add(entry);
                if (entry.codePointLength == 4) four.add(entry);
                if (entry.codePointLength == 5) five.add(entry);
            }
        }
        for (PinyinDictionary.Entry entry : rankTop(exact, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(exactTwo, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(commonShort, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(commonLong, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(three, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(completions, preferSingleCharacter, 4, input))
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
        // Sequence preserves List.sort's stable order even at the heap boundary.
        boolean hasCompleteThreeOrFour = false;
        if (input != null && !input.isEmpty()) {
            for (PinyinDictionary.Entry entry : source) {
                if (entry != null && entry.codePointLength >= 3 && entry.codePointLength <= 4
                        && entry.matchTier == PinyinDictionary.TIER_EXACT_FULL
                        && entry.rankingTier < 35 && entry.consumedLength == input.length()) {
                    hasCompleteThreeOrFour = true;
                    break;
                }
            }
        }
        final boolean preferComplete = hasCompleteThreeOrFour;
        Comparator<RankedEntry> ranking = comparator(preferSingleCharacter, preferComplete)
                .thenComparingLong(value -> value.sequence);
        boolean bounded = limit <= 512 && (!(source instanceof java.util.Collection)
                || ((java.util.Collection<?>) source).size() > limit);
        java.util.PriorityQueue<RankedEntry> heap = bounded
                ? new java.util.PriorityQueue<>(Math.min(limit, 128), ranking.reversed()) : null;
        ArrayList<RankedEntry> out = new ArrayList<>();
        long sequence = 0;
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry == null || entry.word == null) continue;
            RankedEntry value = new RankedEntry(entry, sequence++);
            if (!bounded) out.add(value);
            else if (heap.size() < limit) heap.add(value);
            else if (ranking.compare(value, heap.peek()) < 0) { heap.poll(); heap.add(value); }
        }
        if (bounded) out.addAll(heap);
        // Never truncate scoring on deadline: that could hide a pinned/exact candidate.
        QueryWork.shouldYield(out.size());
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

        final long sequence;

        RankedEntry(PinyinDictionary.Entry entry) { this(entry, 0); }

        RankedEntry(PinyinDictionary.Entry entry, long sequence) {
            this.sequence = sequence;
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
        // Each additional syllable inferred from abbreviated input introduces
        // another possible segmentation; retain the candidate but lower it.
        int ambiguity = Math.max(0, entry.codePointLength - 4) * 120;
        if (entry.matchTier == PinyinDictionary.TIER_MIXED) return 60 + ambiguity;
        if (entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT) return 120 + ambiguity;
        if (entry.matchTier == PinyinDictionary.TIER_PREFIX) return 700;
        return 350;
    }

    private static Comparator<RankedEntry> comparator(boolean preferSingleCharacter, boolean preferComplete) {
        Comparator<RankedEntry> ranking = preferSingleCharacter
                ? Comparator.comparingInt((RankedEntry value) -> value.entry.codePointLength == 1 ? 0 : 1)
                        .thenComparingInt(value -> value.entry.pinned ? 0 : 1)
                : Comparator.comparingInt((RankedEntry value) -> value.entry.pinned ? 0 : 1);
        return ranking.thenComparingInt(value -> reliabilityGroup(value.entry))
                .thenComparingInt(value -> preferComplete && value.entry.codePointLength >= 5
                        && (value.entry.matchTier == PinyinDictionary.TIER_MIXED
                            || value.entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT)
                        && personalScore(value.entry.personalBonus) < STRONG_PERSONAL_SCORE ? 1 : 0)
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
