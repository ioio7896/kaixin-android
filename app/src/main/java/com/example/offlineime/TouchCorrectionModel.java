package com.example.offlineime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Ephemeral geometry only. Coordinates are never persisted or printed. */
final class TouchCorrectionModel {
    static final class Alternative {
        final char letter;
        final float cost;
        Alternative(char letter, float cost) { this.letter = letter; this.cost = cost; }
    }
    static final class Sample {
        final char letter;
        final List<Alternative> alternatives;
        Sample(char letter, List<Alternative> alternatives) {
            this.letter = letter;
            this.alternatives = Collections.unmodifiableList(new ArrayList<>(alternatives));
        }
    }
    static final class Variant {
        final String input;
        final int penalty;
        Variant(String input, int penalty) { this.input = input; this.penalty = penalty; }
    }
    private final ArrayList<Sample> samples = new ArrayList<>();
    private String raw = "";
    void clear() { raw = ""; samples.clear(); }
    String signature() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            if (sample == null || sample.alternatives.isEmpty()) continue;
            result.append(i).append(':');
            for (Alternative alternative : sample.alternatives)
                result.append(alternative.letter).append(Math.round(alternative.cost * 16)).append(',');
            result.append(';');
        }
        return result.toString();
    }
    void sync(String input) {
        if (raw.equals(input)) return;
        samples.clear();
        for (int i = 0; i < input.length(); i++) samples.add(null);
        raw = input;
    }
    void insert(String before, int cursor, String value, Sample sample) {
        sync(before);
        for (int i = 0; i < value.length(); i++)
            samples.add(cursor + i, i == 0 && sample != null && sample.letter == value.charAt(i) ? sample : null);
        raw = before.substring(0, cursor) + value + before.substring(cursor);
    }
    void delete(String before, int start, int end) {
        sync(before);
        samples.subList(start, end).clear();
        raw = before.substring(0, start) + before.substring(end);
    }
    List<Variant> variants(String input) {
        if (!raw.equals(input) || input.length() < 4 || input.length() > 32 || input.indexOf('\'') >= 0)
            return Collections.emptyList();
        ArrayList<Variant> values = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            if (sample == null) continue;
            for (Alternative alternative : sample.alternatives) {
                String changed = input.substring(0, i) + alternative.letter + input.substring(i + 1);
                values.add(new Variant(changed, 180 + Math.round(alternative.cost * 260)));
            }
        }
        values.sort(Comparator.comparingInt(value -> value.penalty));
        return new ArrayList<>(values.subList(0, Math.min(3, values.size())));
    }
}
