package com.example.offlineime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Raw input spans stay internal; the UI only receives readable syllables. */
final class PinyinPathEditor {
    private static final java.util.Set<String> SYLLABLES =
            new java.util.HashSet<>(Arrays.asList(NativeEngine.standard()));
    private static final java.util.Map<String, List<String>> ENTERED = buildEnteredReadings();

    private static java.util.Map<String, List<String>> buildEnteredReadings() {
        java.util.Map<String, java.util.Set<String>> buckets = new java.util.HashMap<>();
        for (String syllable : SYLLABLES) {
            String digits = PinyinDictionary.toT9(syllable);
            for (int length = 1; length <= syllable.length(); length++) {
                String prefix = syllable.substring(0, length);
                buckets.computeIfAbsent("n:" + digits.substring(0, length), key -> new java.util.HashSet<>()).add(prefix);
                buckets.computeIfAbsent("p:" + prefix, key -> new java.util.HashSet<>()).add(prefix);
            }
            String alternate = alternateSpelling(syllable);
            if (!alternate.equals(syllable)) {
                buckets.computeIfAbsent("p:" + alternate, key -> new java.util.HashSet<>()).add(syllable);
            }
        }
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.Set<String>> bucket : buckets.entrySet()) {
            List<String> values = new ArrayList<>(bucket.getValue());
            values.sort(Comparator.comparingInt((String value) -> isCompleteReading(value) ? 0 : 1)
                    .thenComparing(Comparator.naturalOrder()));
            result.put(bucket.getKey(), Collections.unmodifiableList(values));
        }
        return Collections.unmodifiableMap(result);
    }

    static boolean isCompleteReading(String reading) { return SYLLABLES.contains(reading); }

    private static String alternateSpelling(String reading) {
        switch (reading) {
            case "lve": return "lue";
            case "nve": return "nue";
            default: return reading;
        }
    }

    /** Shared guard for corpus-backed paths and manually selected fragments. */
    static boolean isEnteredReading(String reading) { return ENTERED.containsKey("p:" + reading); }

    /** Legal entered prefixes only: their spelling consumes exactly the entered keys. */
    static List<String> enteredReadings(String raw, boolean numeric, String selectedPrefix) {
        List<String> result = new ArrayList<>();
        List<String> values = ENTERED.get((numeric ? "n:" : "p:") + raw);
        if (values != null) for (String value : values)
            if (value.startsWith(selectedPrefix)) result.add(value);
        return result;
    }

    interface Paths { List<List<String>> find(String input); }
    static final class Segment {
        final int start, end, typedLetters;
        final String reading;
        final boolean locked, prefix;
        Segment(int start, int end, String reading, int typedLetters, boolean locked) {
            this(start, end, reading, typedLetters, locked, false);
        }
        Segment(int start, int end, String reading, int typedLetters, boolean locked, boolean prefix) {
            this.start = start; this.end = end; this.reading = reading;
            this.typedLetters = typedLetters; this.locked = locked; this.prefix = prefix;
        }
    }
    static final class Part {
        final int start, end;
        final String text;
        final boolean locked, prefix;
        Part(int start, int end, String text, boolean locked) {
            this(start, end, text, locked, false);
        }
        Part(int start, int end, String text, boolean locked, boolean prefix) {
            this.start = start; this.end = end; this.text = text; this.locked = locked;
            this.prefix = prefix;
        }
    }
    static final class Query {
        final String raw, key, databaseInput;
        final boolean numeric;
        final List<Part> parts;
        Query(String raw, boolean numeric, List<Part> parts) {
            this.raw = raw; this.numeric = numeric;
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
            StringBuilder signature = new StringBuilder(), input = new StringBuilder();
            for (Part part : parts) {
                signature.append(part.locked ? part.prefix ? "^" : "=" : "~").append(part.text).append('|');
                if (input.length() > 0) input.append('\'');
                String spelling = part.prefix ? raw.substring(part.start, part.end) : part.text;
                input.append(numeric && part.locked && spelling.matches("[a-z]+")
                        ? PinyinDictionary.toT9(spelling) : spelling);
            }
            key = signature.toString(); databaseInput = input.toString();
        }
        /** Constraint for the native engine: parts joined by U+001F, see engine::parse_parts. */
        String encoded() {
            StringBuilder out = new StringBuilder();
            for (Part part : parts) {
                if (out.length() > 0) out.append(NativeEngine.UNIT);
                char kind = !part.locked ? '~' : part.prefix ? '^' : '=';
                out.append(kind).append(part.start).append(',').append(part.end).append(',').append(part.text);
            }
            return out.toString();
        }
    }

    private final List<Segment> locks = new ArrayList<>();
    private String snapshot = "";
    private boolean numeric;
    private int focus = -1;
    private long revision;
    long revision() { return revision; }
    void sync(String raw, boolean nine) {
        if (!raw.equals(snapshot) || nine != numeric) {
            locks.clear(); focus = -1; snapshot = raw; numeric = nine; revision++;
        }
    }
    void clear() {
        if (!locks.isEmpty() || !snapshot.isEmpty()) revision++;
        locks.clear(); focus = -1; snapshot = "";
    }
    void automatic() { if (!locks.isEmpty()) revision++; locks.clear(); focus = -1; }
    void unlock(int start) { if (locks.removeIf(lock -> lock.start == start)) revision++; focus = start; }
    boolean hasLocks() { return !locks.isEmpty(); }
    int focus() { return focus; }
    void focus(int start) { focus = start; }
    void replace(String raw, int start, int end, String inserted, boolean nine) {
        sync(raw, nine);
        int delta = inserted.length() - (end - start);
        List<Segment> retained = new ArrayList<>();
        for (Segment lock : locks) {
            if (lock.prefix && start == end && start == lock.end && !inserted.isEmpty()) {
                String extended = raw.substring(lock.start, lock.end) + inserted;
                List<String> options = enteredReadings(extended, nine, lock.reading);
                if (!options.isEmpty()) {
                    String common = options.get(0);
                    for (String option : options) {
                        int shared = 0;
                        while (shared < common.length() && shared < option.length()
                                && common.charAt(shared) == option.charAt(shared)) shared++;
                        common = common.substring(0, shared);
                    }
                    retained.add(new Segment(lock.start, lock.end + inserted.length(), common,
                            extended.length(), true, true));
                    continue;
                }
            }
            // A fully selected syllable is confirmed. An extendable prefix was
            // handled above; other input belongs to the next syllable.
            if (lock.end <= start) retained.add(lock);
            else if (lock.start >= end)
                retained.add(new Segment(lock.start + delta, lock.end + delta,
                        lock.reading, lock.typedLetters, true, lock.prefix));
        }
        locks.clear(); locks.addAll(retained);
        snapshot = raw.substring(0, start) + inserted + raw.substring(end);
        focus = -1;
        revision++;
    }
    boolean select(String raw, boolean nine, int start, int end, String reading) {
        sync(raw, nine);
        if (start < 0 || end > raw.length() || start >= end || !isEnteredReading(reading)) return false;
        String typed = raw.substring(start, end);
        String spelling = nine ? PinyinDictionary.toT9(reading) : reading;
        if (!spelling.startsWith(typed) && (nine || !alternateSpelling(reading).equals(typed))
                && !typed.equals(spelling.substring(0, 1))
                && !(reading.startsWith("zh") || reading.startsWith("ch") || reading.startsWith("sh")
                    ? typed.equals(nine ? spelling.substring(0, 2) : reading.substring(0, 2)) : false)) return false;
        locks.removeIf(lock -> lock.start < end && lock.end > start);
        locks.add(new Segment(start, end, reading, Math.min(typed.length(), reading.length()),
                true, !isCompleteReading(reading)));
        locks.sort(Comparator.comparingInt(segment -> segment.start));
        focus = start;
        revision++;
        return true;
    }
    Query query(String raw, boolean nine) {
        sync(raw, nine);
        List<Part> parts = new ArrayList<>();
        int offset = 0;
        for (Segment lock : locks) {
            if (lock.start > offset) addRawPart(parts, raw, offset, lock.start);
            parts.add(new Part(lock.start, lock.end, lock.reading, true, lock.prefix)); offset = lock.end;
        }
        if (offset < raw.length()) addRawPart(parts, raw, offset, raw.length());
        return new Query(raw, nine, parts);
    }
    private static void addRawPart(List<Part> parts, String raw, int start, int end) {
        // Explicit separators are boundaries, not T9 digits to be stripped.
        // Keep offsets in the original input so selection/deletion consumes them correctly.
        while (start < end) {
            if (raw.charAt(start) == '\'') { start++; continue; }
            int separator = raw.indexOf('\'', start);
            int stop = separator < 0 ? end : Math.min(end, separator);
            parts.add(new Part(start, stop, raw.substring(start, stop), false));
            start = stop + 1;
        }
    }
    List<Segment> segments(String raw, boolean nine, Paths provider) {
        Query query = query(raw, nine);
        List<Segment> result = new ArrayList<>();
        for (Part part : query.parts) {
            if (part.locked) {
                result.add(new Segment(part.start, part.end, part.text,
                        part.end - part.start, true, part.prefix));
                continue;
            }
            List<List<String>> alternatives = provider.find(part.text);
            List<Segment> aligned = null;
            for (List<String> path : alternatives) {
                List<Segment> attempt = new ArrayList<>();
                if (align(part.text, nine, path, 0, 0, part.start, attempt, new java.util.HashSet<>())) { aligned = attempt; break; }
            }
            if (aligned != null) result.addAll(aligned);
            else if (nine) {
                List<String> entered = enteredReadings(part.text, true, "");
                if (!entered.isEmpty()) result.add(new Segment(part.start, part.end,
                        entered.get(0), part.text.length(), false));
            }
        }
        return result;
    }

    /** Alternatives may change an unlocked syllable's end while retaining all explicit locks. */
    List<Segment> alternativeSegments(String raw, boolean nine, Segment focus, Paths provider) {
        if (focus == null) return Collections.emptyList();
        List<Segment> result = new ArrayList<>();
        result.add(focus);
        if (focus.locked) return result;
        for (Part part : query(raw, nine).parts) {
            if (part.locked || focus.start < part.start || focus.start >= part.end) continue;
            for (List<String> path : provider.find(part.text)) {
                List<Segment> aligned = new ArrayList<>();
                if (!align(part.text, nine, path, 0, 0, part.start, aligned, new java.util.HashSet<>())) continue;
                for (Segment segment : aligned) {
                    if (segment.start != focus.start) continue;
                    boolean duplicate = false;
                    for (Segment prior : result)
                        if (prior.end == segment.end && prior.reading.equals(segment.reading)) { duplicate = true; break; }
                    if (!duplicate) result.add(segment);
                }
            }
        }
        // Offer every legal entered prefix at this boundary, including longer
        // syllables absent from the corpus path shortlist. Never cross a lock.
        int boundary = raw.length();
        for (Part part : query(raw, nine).parts)
            if (part.locked && part.start > focus.start) boundary = Math.min(boundary, part.start);
        for (int end = focus.start + 1; end <= Math.min(boundary, focus.start + 6); end++) {
            String fragment = raw.substring(focus.start, end);
            if (fragment.indexOf('\'') >= 0) break;
            for (String reading : enteredReadings(fragment, nine, "")) {
                boolean duplicate = false;
                for (Segment prior : result)
                    if (prior.end == end && prior.reading.equals(reading)) { duplicate = true; break; }
                if (!duplicate) result.add(new Segment(focus.start, end, reading, fragment.length(), false));
            }
        }
        result.sort((a, b) -> {
            int order = PinyinDisplayOrder.READINGS.compare(a.reading, b.reading);
            return order != 0 ? order : Integer.compare(b.end, a.end);
        });
        return result;
    }
    private static boolean align(String raw, boolean numeric, List<String> tokens,
                                 int at, int index, int offset, List<Segment> result, java.util.Set<Long> visited) {
        if (at < raw.length() && raw.charAt(at) == '\'') at++;
        if (at == raw.length()) return true;
        if (index >= tokens.size()) return false;
        if (!visited.add(((long) index << 32) | at)) return false;
        String reading = tokens.get(index);
        if (!isEnteredReading(reading)) return false;
        String spelling = numeric ? PinyinDictionary.toT9(reading) : reading;
        List<Integer> lengths = new ArrayList<>();
        if (raw.startsWith(spelling, at) || (!numeric && raw.startsWith(alternateSpelling(reading), at))) {
            lengths.add(spelling.length());
        }
        String remaining = raw.substring(at);
        if (spelling.startsWith(remaining)) lengths.add(remaining.length());
        if (raw.startsWith(spelling.substring(0, 1), at)) lengths.add(1);
        if ((reading.startsWith("zh") || reading.startsWith("ch") || reading.startsWith("sh"))
                && raw.startsWith(spelling.substring(0, 2), at)) lengths.add(2);
        for (int length : lengths) {
            result.add(new Segment(offset + at, offset + at + length, reading, length, false));
            if (align(raw, numeric, tokens, at + length, index + 1, offset, result, visited)) return true;
            result.remove(result.size() - 1);
        }
        return false;
    }
}
