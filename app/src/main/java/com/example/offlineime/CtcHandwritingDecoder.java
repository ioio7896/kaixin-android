package com.example.offlineime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bounded CTC prefix beam search. Blank separates repeated characters such as 人人. */
final class CtcHandwritingDecoder {
    static final int CHOICES = 7;
    static final int MAX_CHARACTERS = 12;
    private static final int BEAM_WIDTH = 32;
    private static final double NEGATIVE = Double.NEGATIVE_INFINITY;

    static final class Result {
        final List<String> candidates;
        final float confidence;
        Result(List<String> candidates, float confidence) {
            this.candidates = candidates; this.confidence = confidence;
        }
    }

    private static final class Beam {
        final int[] tokens;
        double blank = NEGATIVE, ink = NEGATIVE;
        Beam(int[] tokens) { this.tokens = tokens; }
        double score() { return add(blank, ink); }
    }

    static Result decode(int[] ids, float[] probabilities, int steps, String[] labels, boolean single) {
        Map<String, Beam> beams = new HashMap<>();
        Beam empty = new Beam(new int[0]); empty.blank = 0;
        beams.put("[]", empty);
        float greedyScore = 0;
        int greedyCount = 0, previous = 0;
        for (int time = 0; time < steps; time++) {
            if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), 0);
            Map<String, Beam> next = new HashMap<>();
            int offset = time * CHOICES;
            int best = 0;
            for (int j = 1; j < CHOICES; j++)
                if (probabilities[offset + j] > probabilities[offset + best]) best = j;
            int token = ids[offset + best];
            if (token != 0 && token != previous) { greedyScore += probabilities[offset + best]; greedyCount++; }
            previous = token;
            for (Beam beam : beams.values()) {
                Beam same = obtain(next, beam.tokens);
                same.blank = add(same.blank, beam.score() + log(probabilities[offset]));
                int last = beam.tokens.length == 0 ? -1 : beam.tokens[beam.tokens.length - 1];
                for (int j = 1; j < CHOICES; j++) {
                    int id = ids[offset + j];
                    if (id <= 0 || id >= labels.length || !allowed(labels[id])) continue;
                    double chance = log(probabilities[offset + j]);
                    if (id == last) same.ink = add(same.ink, beam.ink + chance);
                    if (beam.tokens.length >= (single ? 1 : MAX_CHARACTERS)) continue;
                    int[] extended = Arrays.copyOf(beam.tokens, beam.tokens.length + 1);
                    extended[extended.length - 1] = id;
                    Beam longer = obtain(next, extended);
                    longer.ink = add(longer.ink, (id == last ? beam.blank : beam.score()) + chance);
                }
            }
            ArrayList<Beam> ordered = new ArrayList<>(next.values());
            ordered.sort((a, b) -> Double.compare(b.score(), a.score()));
            beams = new HashMap<>();
            for (int i = 0; i < Math.min(BEAM_WIDTH, ordered.size()); i++) {
                Beam beam = ordered.get(i);
                if (Double.isFinite(beam.score())) beams.put(Arrays.toString(beam.tokens), beam);
            }
        }
        ArrayList<Beam> ordered = new ArrayList<>(beams.values());
        ordered.sort((a, b) -> Double.compare(b.score(), a.score()));
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        for (Beam beam : ordered) {
            StringBuilder value = new StringBuilder();
            for (int id : beam.tokens) value.append(labels[id]);
            String text = value.toString().trim();
            int count = text.codePointCount(0, text.length());
            if (count == 0 || count > MAX_CHARACTERS || (single && count != 1)) continue;
            texts.add(text);
            if (texts.size() >= 12) break;
        }
        return new Result(new ArrayList<>(texts), greedyCount == 0 ? 0 : greedyScore / greedyCount);
    }

    private static boolean allowed(String label) {
        if (label.isEmpty()) return false;
        return label.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || Character.isLetterOrDigit(cp) || "，。！？、；：,.!?-· ".indexOf(cp) >= 0);
    }

    private static Beam obtain(Map<String, Beam> beams, int[] tokens) {
        String key = Arrays.toString(tokens);
        Beam value = beams.get(key);
        if (value == null) { value = new Beam(tokens); beams.put(key, value); }
        return value;
    }
    private static double log(float value) { return value > 0 ? Math.log(value) : NEGATIVE; }
    private static double add(double a, double b) {
        if (a == NEGATIVE) return b;
        if (b == NEGATIVE) return a;
        double max = Math.max(a, b);
        return max + Math.log1p(Math.exp(Math.min(a, b) - max));
    }
}
