package com.example.offlineime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Raw input spans stay internal; the UI only receives readable syllables. */
final class PinyinPathEditor {
    private static final java.util.Set<String> SYLLABLES =
            new java.util.HashSet<>(Arrays.asList(PinyinLexer.STANDARD_SYLLABLES));
    private static final java.util.Map<String, List<String>> ENTERED = buildEnteredReadings();
    private static final java.util.Map<String, List<String>> FULL_NINE = buildFullNineReadings();

    private static java.util.Map<String, List<String>> buildFullNineReadings() {
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        for (String reading : PinyinLexer.STANDARD_SYLLABLES)
            result.computeIfAbsent(PinyinDictionary.toT9(reading), ignored -> new ArrayList<>()).add(reading);
        for (List<String> values : result.values()) values.sort(Comparator.naturalOrder());
        return result;
    }

    /** Actual full syllables, independent of whether the whole phrase exists in the corpus. */
    static List<List<String>> fullNinePaths(String digits, int limit) {
        List<List<String>> result = new ArrayList<>();
        for (PinyinInput.Path path : PinyinInput.numericPaths(digits, true, false, false)) {
            List<List<String>> variants = new ArrayList<>();
            variants.add(new ArrayList<>());
            for (String token : path.tokens) {
                List<String> readings = FULL_NINE.get(token);
                if (readings == null) { variants.clear(); break; }
                List<List<String>> next = new ArrayList<>();
                for (List<String> prior : variants) {
                    for (String reading : readings) {
                        List<String> variant = new ArrayList<>(prior); variant.add(reading);
                        next.add(variant);
                        if (next.size() >= 16) break;
                    }
                    if (next.size() >= 16) break;
                }
                variants = next;
            }
            for (List<String> variant : variants) {
                if (!result.contains(variant)) result.add(variant);
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    private static java.util.Map<String, List<String>> buildEnteredReadings() {
        java.util.Map<String, java.util.Set<String>> buckets = new java.util.HashMap<>();
        for (String syllable : PinyinLexer.STANDARD_SYLLABLES) {
            String digits = PinyinDictionary.toT9(syllable);
            for (int length = 1; length <= syllable.length(); length++) {
                String prefix = syllable.substring(0, length);
                buckets.computeIfAbsent("n:" + digits.substring(0, length), key -> new java.util.HashSet<>()).add(prefix);
                buckets.computeIfAbsent("p:" + prefix, key -> new java.util.HashSet<>()).add(prefix);
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

    /** Immediate legal paths without a database lookup or untyped suffixes. */
    static List<List<String>> memoryNinePaths(String digits) {
        List<List<String>> result = fullNinePaths(digits, 32);
        if (!result.isEmpty()) return result;
        for (String reading : enteredReadings(digits, true, "")) {
            result.add(Collections.singletonList(reading));
            if (result.size() == 8) return result;
        }
        if (!result.isEmpty()) return result;
        for (PinyinInput.Path path : PinyinInput.numericPaths(digits, true, true, true)) {
            List<String> readings = new ArrayList<>();
            for (String token : path.tokens) {
                List<String> choices = enteredReadings(token, true, "");
                if (choices.isEmpty()) { readings.clear(); break; }
                readings.add(choices.get(0));
            }
            if (!readings.isEmpty() && !result.contains(readings)) result.add(readings);
            if (result.size() == 8) break;
        }
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
        PinyinInput.Match match(String pronunciation, boolean full, boolean shortInput,
                                boolean mixed, boolean doubles, boolean prefix) {
            if (pronunciation == null || !pronunciation.matches("[a-z]+(?:[ ']+[a-z]+)*")) return null;
            String[] syllables = pronunciation.split("[ ']+");
            int tier = matchParts(syllables, 0, 0, full, shortInput, mixed, doubles, prefix,
                    new java.util.HashSet<>());
            return tier < 0 ? null : new PinyinInput.Match(tier, raw.length());
        }
        private int matchParts(String[] syllables, int partIndex, int syllableIndex,
                               boolean full, boolean shortInput, boolean mixed,
                               boolean doubles, boolean prefix, java.util.Set<Long> visited) {
            QueryWork.check();
            if (partIndex == parts.size())
                return syllableIndex == syllables.length ? PinyinDictionary.TIER_EXACT_FULL
                        : prefix ? PinyinDictionary.TIER_PREFIX : -1;
            if (syllableIndex >= syllables.length) return -1;
            if (!visited.add(((long) partIndex << 32) | syllableIndex)) return -1;
            Part part = parts.get(partIndex);
            if (part.locked) {
                String syllable = syllables[syllableIndex];
                if (part.prefix) {
                    String entered = raw.substring(part.start, part.end);
                    if (numeric && entered.matches("[a-z]+")) entered = PinyinDictionary.toT9(entered);
                    String spelling = numeric ? PinyinDictionary.toT9(syllable) : syllable;
                    if (!syllable.startsWith(part.text) || !spelling.startsWith(entered)) return -1;
                } else if (!part.text.equals(syllable)) return -1;
                int remaining = matchParts(syllables, partIndex + 1, syllableIndex + 1,
                        full, shortInput, mixed, doubles, prefix, visited);
                return remaining < 0 ? -1 : Math.max(remaining,
                        part.prefix && !part.text.equals(syllable) ? PinyinDictionary.TIER_PREFIX : 0);
            }
            for (int end = syllableIndex + 1; end <= syllables.length; end++) {
                boolean last = partIndex == parts.size() - 1;
                if (last && end != syllables.length) continue;
                String reading = String.join(" ", Arrays.copyOfRange(syllables, syllableIndex, end));
                PinyinInput.Match match = PinyinInput.match(part.text, reading, full, shortInput,
                        mixed, doubles, last && prefix);
                if (match == null) continue;
                int remaining = matchParts(syllables, partIndex + 1, end,
                        full, shortInput, mixed, doubles, prefix, visited);
                if (remaining >= 0) return Math.max(match.tier, remaining);
            }
            return -1;
        }
        List<List<String>> tokenPaths(boolean full, boolean shortInput, boolean doubles) {
            List<List<String>> result = new ArrayList<>();
            result.add(new ArrayList<>());
            for (Part part : parts) {
                List<List<String>> alternatives = new ArrayList<>();
                if (part.locked) {
                    List<String> entered = part.prefix
                            ? enteredReadings(raw.substring(part.start, part.end), numeric, part.text)
                            : Collections.singletonList(part.text);
                    for (String reading : entered) {
                        alternatives.add(Collections.singletonList(reading));
                        if (alternatives.size() == 2) break;
                    }
                }
                else {
                    List<PinyinInput.Path> parsed = numeric
                            ? PinyinInput.numericPaths(part.text, full, shortInput, doubles)
                            : PinyinInput.paths(part.text, full, shortInput, doubles);
                    for (PinyinInput.Path path : parsed) {
                        if (path.tokens.size() == 1) { alternatives.add(path.tokens); break; }
                    }
                    for (PinyinInput.Path path : PinyinInput.compositionPaths(parsed, 6)) {
                        if (alternatives.size() >= 6) break;
                        alternatives.add(path.tokens);
                    }
                    // A single unlocked syllable still forms part of a longer
                    // path when another syllable has already been selected.
                    if (alternatives.isEmpty() && !parsed.isEmpty()) alternatives.add(parsed.get(0).tokens);
                }
                List<List<String>> next = new ArrayList<>();
                for (List<String> prior : result) for (List<String> alternative : alternatives) {
                    if (next.size() >= 6) break;
                    List<String> joined = new ArrayList<>(prior); joined.addAll(alternative);
                    if (joined.size() <= PinyinInput.MAX_SYLLABLES) next.add(joined);
                }
                result = next;
            }
            return result;
        }
        List<String> initialKeys(boolean full, boolean shortInput, boolean doubles) {
            List<String> keys = new ArrayList<>(); keys.add("");
            for (Part part : parts) {
                List<String> options = new ArrayList<>();
                if (part.locked) options.add(numeric ? PinyinDictionary.toT9(part.text.substring(0, 1))
                        : part.text.substring(0, 1));
                else if (numeric) options.addAll(PinyinInput.numericKeys(part.text, full, shortInput, doubles));
                else for (PinyinInput.Path path : PinyinInput.paths(part.text, full, shortInput, doubles))
                    if (!options.contains(path.initials)) options.add(path.initials);
                List<String> joined = new ArrayList<>();
                for (String key : keys) for (String option : options) {
                    if (joined.size() >= 32) break;
                    String value = key + option;
                    if (!joined.contains(value)) joined.add(value);
                }
                keys = joined;
            }
            return keys;
        }
        String leadingReading() {
            List<String> leading = new ArrayList<>();
            for (Part part : parts) {
                if (!part.locked) break;
                leading.add(part.text);
                if (part.prefix) break;
            }
            return String.join(" ", leading);
        }
        boolean leadingIsPrefix() {
            for (Part part : parts) {
                if (!part.locked) break;
                if (part.prefix) return true;
            }
            return false;
        }
        static Query forTokens(String input, boolean numeric) {
            List<Part> parts = new ArrayList<>();
            int offset = 0;
            for (String token : input.split("'")) {
                boolean selected = numeric && token.matches("[a-z]+");
                parts.add(new Part(offset, offset + token.length(), token,
                        selected, selected && !isCompleteReading(token)));
                offset += token.length() + 1;
            }
            return new Query(input, numeric, parts);
        }
    }

    private final List<Segment> locks = new ArrayList<>();
    private String snapshot = "";
    private boolean numeric;
    private int focus = -1;
    void sync(String raw, boolean nine) {
        if (!raw.equals(snapshot) || nine != numeric) {
            locks.clear(); focus = -1; snapshot = raw; numeric = nine;
        }
    }
    void clear() { locks.clear(); focus = -1; snapshot = ""; }
    void automatic() { locks.clear(); focus = -1; }
    void unlock(int start) { locks.removeIf(lock -> lock.start == start); focus = start; }
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
    }
    boolean select(String raw, boolean nine, int start, int end, String reading) {
        sync(raw, nine);
        if (start < 0 || end > raw.length() || start >= end || !isEnteredReading(reading)) return false;
        String typed = raw.substring(start, end);
        String spelling = nine ? PinyinDictionary.toT9(reading) : reading;
        if (!spelling.startsWith(typed) && !typed.equals(spelling.substring(0, 1))
                && !(reading.startsWith("zh") || reading.startsWith("ch") || reading.startsWith("sh")
                    ? typed.equals(nine ? spelling.substring(0, 2) : reading.substring(0, 2)) : false)) return false;
        locks.removeIf(lock -> lock.start < end && lock.end > start);
        locks.add(new Segment(start, end, reading, Math.min(typed.length(), reading.length()),
                true, !isCompleteReading(reading)));
        locks.sort(Comparator.comparingInt(segment -> segment.start));
        focus = start;
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
        while (start < end && raw.charAt(start) == '\'') start++;
        while (end > start && raw.charAt(end - 1) == '\'') end--;
        if (end > start) parts.add(new Part(start, end, raw.substring(start, end), false));
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
            else if (!nine) {
                for (PinyinInput.Path path : PinyinInput.paths(part.text, true, true, true)) {
                    List<Segment> attempt = new ArrayList<>();
                    if (align(part.text, false, path.tokens, 0, 0, part.start, attempt, new java.util.HashSet<>())) {
                        result.addAll(attempt); break;
                    }
                }
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
        if (raw.startsWith(spelling, at)) lengths.add(spelling.length());
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
