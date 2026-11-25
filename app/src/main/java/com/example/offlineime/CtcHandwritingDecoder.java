package com.example.offlineime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Bounded CTC prefix beam search. Blank separates repeated characters such as 人人.
 * Uses the zero-allocation Rust beam decoder when available, with an integer-keyed Java fallback.
 */
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

    private static final class TokenKey {
        final int[] tokens;
        final int hash;
        TokenKey(int[] tokens) {
            this.tokens = tokens;
            this.hash = Arrays.hashCode(tokens);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof TokenKey && Arrays.equals(tokens, ((TokenKey) other).tokens);
        }
    }

    private static final class Beam {
        final TokenKey key;
        double blank = NEGATIVE, ink = NEGATIVE;
        Beam(TokenKey key) { this.key = key; }
        double score() { return add(blank, ink); }
    }

    static Result decode(int[] ids, float[] probabilities, int steps, String[] labels, boolean single) {
        if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), 0);
        Result nativeResult = decodeNative(ids, probabilities, steps, labels, single);
        if (nativeResult != null) return nativeResult;
        return decodeJava(ids, probabilities, steps, labels, single);
    }

    private static Result decodeNative(int[] ids, float[] probabilities, int steps, String[] labels, boolean single) {
        byte[] encoded;
        try {
            encoded = NativeEngine.decodeCtc(ids, probabilities, steps, single);
        } catch (LinkageError ignored) {
            return null;
        }
        if (encoded == null || encoded.length < 16) return null;
        ByteBuffer buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        float confidence = buf.getFloat();
        int greedyCount = buf.getInt();
        int lastEmitted = buf.getInt();
        int seqCount = buf.getInt();
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        StringBuilder value = new StringBuilder();
        for (int s = 0; s < seqCount; s++) {
            if (!buf.hasRemaining()) break;
            int len = buf.get() & 0xff;
            if (buf.remaining() < len * 4) break;
            value.setLength(0);
            boolean valid = true;
            for (int k = 0; k < len; k++) {
                int id = buf.getInt();
                if (id <= 0 || id >= labels.length) { valid = false; continue; }
                value.append(labels[id]);
            }
            if (!valid) continue;
            String text = value.toString().trim();
            int count = text.codePointCount(0, text.length());
            if (count == 0 || count > MAX_CHARACTERS || (single && count != 1)) continue;
            texts.add(text);
            if (texts.size() >= 12) break;
        }
        if (single && (greedyCount != 1 || lastEmitted >= labels.length || lastEmitted < 1
                || texts.isEmpty() || !texts.iterator().next().equals(labels[lastEmitted]))) confidence = 0;
        return new Result(new ArrayList<>(texts), confidence);
    }

    private static Result decodeJava(int[] ids, float[] probabilities, int steps, String[] labels, boolean single) {
        boolean[] allowedCache = new boolean[labels.length];
        for (int i = 1; i < labels.length; i++) {
            allowedCache[i] = labels[i] != null && isAllowedLabel(labels[i]);
        }
        Map<TokenKey, Beam> beams = new HashMap<>(BEAM_WIDTH * 2);
        TokenKey emptyKey = new TokenKey(new int[0]);
        Beam empty = new Beam(emptyKey); empty.blank = 0;
        beams.put(emptyKey, empty);
        float greedyScore = 0;
        int greedyCount = 0, lastEmitted = 0, previous = 0;
        for (int time = 0; time < steps; time++) {
            if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), 0);
            Map<TokenKey, Beam> next = new HashMap<>(BEAM_WIDTH * 4);
            int offset = time * CHOICES;
            int best = 0;
            for (int j = 1; j < CHOICES; j++)
                if (probabilities[offset + j] > probabilities[offset + best]) best = j;
            int token = ids[offset + best];
            if (token != 0 && token != previous) {
                greedyScore += probabilities[offset + best];
                greedyCount++;
                lastEmitted = token;
            }
            previous = token;
            double blankLog = log(probabilities[offset]);
            for (Beam beam : beams.values()) {
                double beamScore = beam.score();
                Beam same = obtain(next, beam.key);
                same.blank = add(same.blank, beamScore + blankLog);
                int[] tokens = beam.key.tokens;
                int last = tokens.length == 0 ? -1 : tokens[tokens.length - 1];
                for (int j = 1; j < CHOICES; j++) {
                    int id = ids[offset + j];
                    if (id <= 0 || id >= allowedCache.length || !allowedCache[id]) continue;
                    double chance = log(probabilities[offset + j]);
                    if (id == last) same.ink = add(same.ink, beam.ink + chance);
                    if (tokens.length >= (single ? 1 : MAX_CHARACTERS)) continue;
                    int[] extended = Arrays.copyOf(tokens, tokens.length + 1);
                    extended[extended.length - 1] = id;
                    Beam longer = obtain(next, new TokenKey(extended));
                    longer.ink = add(longer.ink, (id == last ? beam.blank : beamScore) + chance);
                }
            }
            ArrayList<Beam> ordered = new ArrayList<>(next.values());
            ordered.sort((a, b) -> Double.compare(b.score(), a.score()));
            beams = new HashMap<>(BEAM_WIDTH * 2);
            for (int i = 0; i < Math.min(BEAM_WIDTH, ordered.size()); i++) {
                Beam beam = ordered.get(i);
                if (Double.isFinite(beam.score())) beams.put(beam.key, beam);
            }
        }
        ArrayList<Beam> ordered = new ArrayList<>(beams.values());
        ordered.sort((a, b) -> Double.compare(b.score(), a.score()));
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        StringBuilder value = new StringBuilder();
        for (Beam beam : ordered) {
            value.setLength(0);
            for (int id : beam.key.tokens) value.append(labels[id]);
            String text = value.toString().trim();
            int count = text.codePointCount(0, text.length());
            if (count == 0 || count > MAX_CHARACTERS || (single && count != 1)) continue;
            texts.add(text);
            if (texts.size() >= 12) break;
        }
        float confidence = greedyCount == 0 ? 0 : greedyScore / greedyCount;
        // Use the greedy score for the single-character fast path only when
        // it describes the same character as the beam search's first choice.
        if (single && (greedyCount != 1 || lastEmitted >= labels.length || lastEmitted < 1
                || texts.isEmpty() || !texts.iterator().next().equals(labels[lastEmitted]))) confidence = 0;
        return new Result(new ArrayList<>(texts), confidence);
    }

    static boolean isAllowedLabel(String label) {
        if (label == null || label.isEmpty()) return false;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            if (!(Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                    || Character.isLetterOrDigit(cp) || "，。！？、；：,.!?-· ".indexOf(cp) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static Beam obtain(Map<TokenKey, Beam> beams, TokenKey key) {
        Beam value = beams.get(key);
        if (value == null) { value = new Beam(key); beams.put(key, value); }
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
