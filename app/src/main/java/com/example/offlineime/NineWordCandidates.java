package com.example.offlineime;

import java.util.ArrayList;
import java.util.List;

/** Bounded three/four-character assistance; service validates explicit reading locks. */
final class NineWordCandidates {
    private NineWordCandidates() { }

    static boolean isCompletion(PinyinDictionary.Entry entry, String raw) {
        if (entry.matchTier != PinyinDictionary.TIER_PREFIX
                || entry.codePointLength < 3 || entry.codePointLength > 4
                || entry.pronunciation.isEmpty() || raw == null
                || !CandidateRanker.hasStrongWordSupport(entry)) return false;
        String digits = raw.replace("'", "");
        if (digits.isEmpty()) return false;
        for (int i = 0; i < digits.length(); i++)
            if (digits.charAt(i) < '2' || digits.charAt(i) > '9') return false;
        String[] syllables = entry.pronunciation.split("[ ']+");
        if (syllables.length != entry.codePointLength) return false;
        StringBuilder full = new StringBuilder();
        int twoSyllables = 0;
        for (int i = 0; i < syllables.length; i++) {
            if (!PinyinPathEditor.isCompleteReading(syllables[i])) return false;
            full.append(PinyinDictionary.toT9(syllables[i]));
            if (i == 1) twoSyllables = full.length();
        }
        return digits.length() >= twoSyllables && digits.length() < full.length()
                && full.toString().startsWith(digits);
    }

    static List<PinyinDictionary.Entry> rank(List<PinyinDictionary.Entry> source,
            boolean preferSingle, int limit, String input) {
        List<PinyinDictionary.Entry> ordinary = new ArrayList<>();
        List<PinyinDictionary.Entry> completeLong = new ArrayList<>();
        List<PinyinDictionary.Entry> completions = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (isCompletion(entry, input)) { completions.add(entry); continue; }
            ordinary.add(entry);
            if (entry.codePointLength >= 3 && entry.codePointLength <= 4
                    && entry.consumedLength == input.length() && entry.rankingTier < 35
                    && (entry.matchTier == PinyinDictionary.TIER_EXACT_SHORT
                        || entry.matchTier == PinyinDictionary.TIER_MIXED)) completeLong.add(entry);
        }
        List<PinyinDictionary.Entry> ranked = CandidateRanker.rankTop(ordinary, preferSingle, limit, input);
        int insert = Math.min(3, ranked.size());
        while (insert < ranked.size() && ranked.get(insert).matchTier == PinyinDictionary.TIER_EXACT_FULL
                && ranked.get(insert).rankingTier < 35) insert++;
        for (PinyinDictionary.Entry entry : CandidateRanker.rankTop(completeLong, false, 2, input)) {
            int position = ranked.indexOf(entry);
            if (position >= 0 && position < 6) continue;
            ranked.remove(entry);
            ranked.add(Math.min(insert++, ranked.size()), entry);
        }
        // Keep the two-slot bound for both the first frame and its supplement,
        // even when fewer than a page of complete matches exist.
        List<PinyinDictionary.Entry> predicted = CandidateRanker.rankTop(completions, false, 2, input);
        int predictionPosition = ranked.size();
        for (int i = 0; i < ranked.size(); i++) {
            PinyinDictionary.Entry entry = ranked.get(i);
            if (entry.matchTier == PinyinDictionary.TIER_COMPOSED
                    || entry.matchTier == PinyinDictionary.TIER_FUZZY
                    || entry.matchTier == PinyinDictionary.TIER_PREFIX || entry.rankingTier >= 35) {
                predictionPosition = i;
                break;
            }
        }
        ranked.addAll(predictionPosition, predicted);
        if (ranked.size() > limit) ranked.subList(limit, ranked.size()).clear();
        return ranked;
    }
}
