package com.example.offlineime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded beam decoder for composing short phrases from pinyin syllables. */
final class SentenceComposer {
    private static final int MAX_WORD_LENGTH = 32;
    private static final int MAX_SEGMENT_CANDIDATES = 6;

    private SentenceComposer() {}

    interface WordLookup { List<PinyinDictionary.Entry> lookup(String input); }
    interface TransitionScore { int score(String previous, String next); }
    static final class Decoder {
        private final ByteBudgetCache<Decoded> recent = new ByteBudgetCache<>(2 * 1024 * 1024, 16, value -> {
            int bytes = 64;
            for (List<State> beam : value.beams) for (State state : beam)
                bytes += 96 + (state.text.length() + state.reading.length()) * 2 + state.words.size() * 48;
            return bytes;
        });
        void clear() { recent.clear(); }
    }
    private static final class Decoded {
        final List<String> tokens;
        final List<List<State>> beams;
        Decoded(List<String> tokens, List<List<State>> beams) { this.tokens = new ArrayList<>(tokens); this.beams = beams; }
    }
    private static final Comparator<State> STATE_ORDER = Comparator.comparingInt((State state) -> state.score)
            .reversed().thenComparing(state -> state.text);

    /** Word edges consume 1–8 input tokens; pruning bounds both memory and DB work. */
    static List<PinyinDictionary.Entry> composeWords(List<String> tokens, WordLookup lookup, int consumed) {
        return composeWords(tokens, lookup, consumed, (previous, next) -> 0);
    }
    static List<PinyinDictionary.Entry> composeWords(List<String> tokens, WordLookup lookup, int consumed, TransitionScore transitions) {
        return composeWords(tokens, lookup, consumed, transitions, null, "");
    }
    static List<PinyinDictionary.Entry> composeWords(List<String> tokens, WordLookup lookup, int consumed,
            TransitionScore transitions, Decoder decoder, String cacheKey) {
        long stageStarted = ImePerformanceMonitor.start();
        try {
        if (tokens.isEmpty() || tokens.size() > 32) return java.util.Collections.emptyList();
        List<List<State>> beams = new ArrayList<>();
        for (int i = 0; i <= tokens.size(); i++) beams.add(new ArrayList<>());
        beams.get(0).add(new State("", 0, ""));
        Decoded previous = decoder == null ? null : decoder.recent.get(cacheKey);
        int common = 0;
        if (previous != null) {
            while (common < Math.min(previous.tokens.size(), tokens.size())
                    && previous.tokens.get(common).equals(tokens.get(common))) common++;
            for (int i = 0; i <= common; i++) beams.set(i, previous.beams.get(i));
        }
        int queries = 0;
        // Decode by destination; every finalized prefix beam can be reused on append, edit or backspace.
        for (int end = common + 1; end <= tokens.size(); end++) {
            QueryWork.check();
            Map<String, State> unique = new LinkedHashMap<>();
            for (int start = Math.max(0, end - 8); start < end; start++) {
                QueryWork.check();
                if (beams.get(start).isEmpty()) continue;
                if (++queries > 256) break;
                List<PinyinDictionary.Entry> words = lookup.lookup(String.join("'", tokens.subList(start, end)));
                for (State prior : beams.get(start)) {
                    for (int i = 0; i < Math.min(4, words.size()); i++) {
                        PinyinDictionary.Entry word = words.get(i);
                        if (word.matchTier >= PinyinDictionary.TIER_PREFIX) continue;
                        int probability = (int) Math.round(1000 * (Math.log1p(Math.max(1, word.corpusScore)) - 14));
                        State next = new State(prior.text + word.word, prior.score + probability + transitions.score(prior.last, word.word), word.word);
                        next.words = new ArrayList<>(prior.words); next.words.add(word.word);
                        next.reading = (prior.reading + " " + word.pronunciation).trim();
                        State existing = unique.get(next.text);
                        if (existing == null || existing.score < next.score) unique.put(next.text, next);
                    }
                }
            }
            List<State> target = new ArrayList<>(unique.values());
            target.sort(STATE_ORDER);
            if (target.size() > 12) target.subList(12, target.size()).clear();
            beams.set(end, target);
        }
        QueryWork.check();
        if (decoder != null) decoder.recent.put(cacheKey, new Decoded(tokens, beams));
        List<PinyinDictionary.Entry> out = new ArrayList<>();
        for (State state : beams.get(tokens.size())) {
            if (out.size() >= 8) break;
            PinyinDictionary.Entry entry = new PinyinDictionary.Entry(state.text, state.score, PinyinDictionary.TIER_COMPOSED, false, 0, 32)
                    .withInput(consumed, state.reading.isEmpty() ? String.join(" ", tokens) : state.reading);
            entry.wordSegments = java.util.Collections.unmodifiableList(state.words); out.add(entry);
        }
        return out;
    
        } finally { QueryWork.elapsed("sentence", stageStarted); }
    }

    static List<PinyinDictionary.Entry> compose(
            List<String> syllables,
            Map<String, List<PinyinDictionary.Entry>> candidatesBySyllable,
            int beamWidth,
            int resultLimit) {
        long stageStarted = ImePerformanceMonitor.start();
        try {
        if (syllables == null || syllables.isEmpty()) return java.util.Collections.emptyList();
        int width = Math.max(4, Math.min(32, beamWidth));
        int limit = Math.max(1, Math.min(48, resultLimit));
        List<State> beam = new ArrayList<>();
        beam.add(new State("", 0, ""));

        for (String syllable : syllables) {
            QueryWork.check();
            List<PinyinDictionary.Entry> candidates = candidatesBySyllable.get(syllable);
            if (candidates == null || candidates.isEmpty()) return java.util.Collections.emptyList();
            int candidateCount = Math.min(MAX_SEGMENT_CANDIDATES, candidates.size());
            Map<String, State> unique = new LinkedHashMap<>();
            for (State prior : beam) {
                for (int i = 0; i < candidateCount; i++) {
                    PinyinDictionary.Entry entry = candidates.get(i);
                    String text = prior.text + entry.word;
                    if (text.codePointCount(0, text.length()) > MAX_WORD_LENGTH) continue;
                    int contextualTieBreak = prior.last.isEmpty() || entry.word.isEmpty()
                            ? 0 : (prior.last.charAt(0) == entry.word.charAt(0) ? 30 : 0);
                    int score = prior.score + entry.score
                            + Math.min(8, entry.word.length()) * 40 + contextualTieBreak;
                    State next = new State(text, score, entry.word);
                    State old = unique.get(text);
                    if (old == null || next.score > old.score) unique.put(text, next);
                }
            }
            ArrayList<State> nextBeam = new ArrayList<>(unique.values());
            nextBeam.sort(Comparator.comparingInt((State state) -> state.score).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (State state) -> state.text.codePointCount(0, state.text.length())).reversed())
                    .thenComparing(state -> state.text));
            if (nextBeam.size() > width) nextBeam.subList(width, nextBeam.size()).clear();
            if (nextBeam.isEmpty()) return java.util.Collections.emptyList();
            beam = nextBeam;
        }

        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>();
        for (State state : beam) {
            if (state.text.codePointCount(0, state.text.length()) > 1) {
                result.add(new PinyinDictionary.Entry(state.text, state.score,
                        PinyinDictionary.TIER_COMPOSED, false, 0));
            }
        }
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, limit));
    
        } finally { QueryWork.elapsed("sentence", stageStarted); }
    }

    private static final class State {
        List<String> words = java.util.Collections.emptyList();
        String reading = "";
        final String text;
        final int score;
        final String last;

        State(String text, int score, String last) {
            this.text = text;
            this.score = score;
            this.last = last;
        }
    }
}
