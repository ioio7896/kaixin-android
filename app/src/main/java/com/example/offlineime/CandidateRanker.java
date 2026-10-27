package com.example.offlineime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic ranking with well-matched common characters before short words. */
final class CandidateRanker {
    private static final int COMMON_CORPUS_SCORE = 4000;
    private static final int COMMON_RANK_SCORE = corpusScore(COMMON_CORPUS_SCORE);
    private static final int STRONG_PERSONAL_SCORE = 200;
    private static final int STRONG_CONTEXT_SCORE = 240;

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
        return Math.max(-640, Math.min(1200, bonus)) / 3;
    }

    private static int contextScore(int bonus) {
        return Math.max(0, Math.min(1550, bonus)) * 450 / 1550;
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

    private static boolean isFrequentMatchingCharacter(PinyinDictionary.Entry entry, String input) {
        if (entry.codePointLength != 1 || entry.corpusScore < COMMON_CORPUS_SCORE
                || !containsHan(entry.word) || entry.rankingTier >= 35) return false;
        if (entry.matchTier == PinyinDictionary.TIER_EXACT_FULL
                || entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                || entry.matchTier == PinyinDictionary.TIER_MIXED) return true;
        if (entry.matchTier != PinyinDictionary.TIER_PREFIX) return false;
        String query = input == null || input.isEmpty() ? entry.queryText : input;
        if (query == null) return false;
        query = query.replace("'", "");
        String reading = entry.pronunciation;
        if (reading == null || reading.isEmpty() || query.length() < 2) return false;
        if (query.charAt(0) >= '2' && query.charAt(0) <= '9')
            reading = PinyinDictionary.toT9(reading);
        // Promote a substantial single-syllable prefix, never a partial-input
        // character or a broad one-key prefix with little spelling evidence.
        return reading.startsWith(query) && query.length() * 2 >= reading.length()
                && entry.consumedLength >= query.length();
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

    /** Preserve small exact and common-short pools before later personal/context ranking. */
    static List<PinyinDictionary.Entry> rankForRecall(
            Iterable<PinyinDictionary.Entry> source, boolean preferSingleCharacter,
            int limit, String input) {
        List<PinyinDictionary.Entry> ranked = rankTop(source, preferSingleCharacter, limit, input);
        java.util.HashSet<String> included = new java.util.HashSet<>();
        for (PinyinDictionary.Entry entry : ranked) included.add(entry.word);
        ArrayList<PinyinDictionary.Entry> exact = new ArrayList<>();
        ArrayList<PinyinDictionary.Entry> commonShort = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry == null || entry.word == null || included.contains(entry.word)) continue;
            if (entry.rankingTier < 35 && entry.matchTier == PinyinDictionary.TIER_EXACT_FULL)
                exact.add(entry);
            if (entry.rankingTier < 35 && isFrequentShortWord(entry)) commonShort.add(entry);
        }
        for (PinyinDictionary.Entry entry : rankTop(exact, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        for (PinyinDictionary.Entry entry : rankTop(commonShort, preferSingleCharacter, 4, input))
            if (included.add(entry.word)) ranked.add(entry);
        return ranked;
    }

    static boolean isThreeLetterInput(String input) {
        if (input == null || input.length() != 3) return false;
        for (int i = 0; i < 3; i++)
            if (input.charAt(i) < 'a' || input.charAt(i) > 'z') return false;
        return true;
    }

    static List<PinyinDictionary.Entry> rankTop(
            Iterable<PinyinDictionary.Entry> source, boolean preferSingleCharacter, int limit, String input) {
        if (limit <= 0) return new ArrayList<>();
        boolean preferTwoCharacters = isThreeLetterInput(input);
        Comparator<RankedEntry> ranking = comparator(preferSingleCharacter);
        ArrayList<RankedEntry> out = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry != null && entry.word != null) out.add(new RankedEntry(entry, preferTwoCharacters, input));
        }
        out.sort(ranking);
        QueryWork.check();
        // Preserve the leading choice, then reserve a few places for common
        // complete words and phrase completions. The order follows the input:
        // full spellings favor full-word completions; initials favor short words.
        if (!preferSingleCharacter && input != null && input.replace("'", "").length() >= 2
                && out.size() > 1) {
            ArrayList<RankedEntry> ordered = new ArrayList<>(out.size());
            int at = 0;
            while (at < out.size() && out.get(at).entry.pinned) ordered.add(out.get(at++));
            if (at < out.size()) ordered.add(out.get(at++));
            boolean[] moved = new boolean[out.size()];
            boolean fullSpelling = !input.matches("[2-9']+")
                    && !PinyinInput.isAbbreviationQuery(input, true);
            int[] groups = fullSpelling ? new int[]{0, 2, 1, 3} : new int[]{1, 0, 2, 3};
            int[] quotas = fullSpelling ? new int[]{3, 2, 2, 1} : new int[]{4, 2, 1, 1};
            for (int group : groups) {
                int promoted = 0;
                for (int i = at; i < out.size() && promoted < quotas[group]; i++) {
                    RankedEntry candidate = out.get(i);
                    if (!moved[i] && phraseGroup(candidate.entry) == group) {
                        ordered.add(candidate);
                        moved[i] = true;
                        promoted++;
                    }
                }
            }
            for (int i = at; i < out.size(); i++) {
                if (!moved[i]) ordered.add(out.get(i));
            }
            out = ordered;
        }
        return entries(out.subList(0, Math.min(limit, out.size())));
    }

    private static int phraseGroup(PinyinDictionary.Entry entry) {
        if (entry.codePointLength < 2 || !containsHan(entry.word)
                || entry.rankingTier >= 35 && entry.matchTier != PinyinDictionary.TIER_COMPOSED)
            return -1;
        if (entry.matchTier == PinyinDictionary.TIER_COMPOSED)
            return entry.codePointLength >= 3 && entry.wordSegments.size() > 1 ? 3 : -1;
        if (entry.corpusScore < COMMON_CORPUS_SCORE
                && personalScore(entry.personalBonus) < STRONG_PERSONAL_SCORE
                && contextScore(entry.contextBonus) < STRONG_CONTEXT_SCORE) return -1;
        if (entry.matchTier == PinyinDictionary.TIER_EXACT_FULL) return 0;
        if (entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                || entry.matchTier == PinyinDictionary.TIER_MIXED) return 1;
        return entry.matchTier == PinyinDictionary.TIER_PREFIX ? 2 : -1;
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
        final int singleCharacterPriority;
        final int shortWordPriority;
        final int twoCharacterPriority;

        RankedEntry(PinyinDictionary.Entry entry, boolean preferTwoCharacters, String input) {
            this.entry = entry;
            score = rankingScore(entry) - matchPenalty(entry);
            // General prefixes stay behind complete matches; well-matched common
            // single characters receive their own priority before this comparison.
            incomplete = entry.matchTier == PinyinDictionary.TIER_PREFIX
                    || entry.rankingTier >= 35 && entry.matchTier != PinyinDictionary.TIER_COMPOSED ? 1 : 0;
            singleCharacterPriority = isFrequentMatchingCharacter(entry, input) ? 0 : 1;
            shortWordPriority = incomplete == 0 && isFrequentShortWord(entry) ? 0 : 1;
            // Three letters can match two syllables via zh/ch/sh or mixed spelling.
            // Keep frequent complete two-character matches ahead of three-character heat.
            twoCharacterPriority = (preferTwoCharacters || isThreeLetterInput(entry.queryText))
                    && shortWordPriority == 0 && entry.codePointLength == 2 ? 0 : 1;
        }
    }

    private static int matchPenalty(PinyinDictionary.Entry entry) {
        if (entry.matchTier == PinyinDictionary.TIER_COMPOSED) return 0;
        if (entry.rankingTier <= PinyinDictionary.TIER_EXACT_FULL) return 0;
        if (entry.rankingTier <= PinyinDictionary.TIER_MIXED) return 120;
        if (entry.matchTier == PinyinDictionary.TIER_FUZZY) return 1100;
        if (entry.matchTier == PinyinDictionary.TIER_PREFIX) return 700;
        return 350;
    }

    private static Comparator<RankedEntry> comparator(boolean preferSingleCharacter) {
        Comparator<RankedEntry> ranking = preferSingleCharacter
                ? Comparator.comparingInt((RankedEntry value) -> value.entry.codePointLength == 1 ? 0 : 1)
                        .thenComparingInt(value -> value.entry.pinned ? 0 : 1)
                : Comparator.comparingInt((RankedEntry value) -> value.entry.pinned ? 0 : 1);
        return ranking.thenComparingInt(value -> value.singleCharacterPriority)
                .thenComparingInt(value -> value.incomplete)
                .thenComparingInt(value -> value.shortWordPriority)
                .thenComparingInt(value -> value.twoCharacterPriority)
                .thenComparing(Comparator.comparingInt((RankedEntry value) -> value.score).reversed())
                .thenComparingInt(value -> value.entry.rankingTier)
                .thenComparing(Comparator.comparingInt((RankedEntry value) -> value.entry.corpusScore).reversed())
                .thenComparing(value -> value.entry.word);
    }
}
