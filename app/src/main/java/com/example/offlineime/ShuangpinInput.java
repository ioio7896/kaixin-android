package com.example.offlineime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Xiaohe key assignments. Query expansion never changes the raw two-key composition. */
final class ShuangpinInput {
    private static final Map<String, Character> FINALS = finals();
    private static final Map<String, List<String>> PAIRS = pairs();
    private final Map<Integer, String> locks = new HashMap<>();
    private String raw = "";
    private String cachedPathKey = "";
    private List<Path> cachedPaths = Collections.emptyList();
    static final class Path {
        final List<String> readings;
        final List<Integer> ends;
        final String query;
        Path(List<String> readings, List<Integer> ends) {
            this.readings = readings; this.ends = ends; query = String.join("'", readings);
        }
        int consumed(int expandedOffset) {
            if (expandedOffset <= 0) return ends.isEmpty() ? 0 : ends.get(ends.size() - 1);
            int at = 0;
            for (int i = 0; i < readings.size(); i++) {
                at += readings.get(i).length();
                if (at >= expandedOffset) return ends.get(i);
                at++;
            }
            return ends.isEmpty() ? 0 : ends.get(ends.size() - 1);
        }
    }
    private static Map<String, Character> finals() {
        Map<String, Character> result = new HashMap<>();
        String[] values = {"q:iu", "w:ei", "e:e", "r:uan", "t:ue,ve", "y:un", "u:u", "i:i",
                "o:o,uo", "p:ie", "a:a", "s:iong,ong", "d:ai", "f:en", "g:eng", "h:ang",
                "j:an", "k:ing,uai", "l:iang,uang", "z:ou", "x:ia,ua", "c:ao", "v:ui,v", "b:in", "n:iao", "m:ian"};
        for (String value : values) for (String ending : value.substring(2).split(",")) result.put(ending, value.charAt(0));
        return result;
    }
    static String encode(String syllable) {
        if (syllable == null || syllable.isEmpty()) return "";
        if ("aeo".indexOf(syllable.charAt(0)) >= 0) {
            if (syllable.length() == 1) return syllable + syllable;
            if (syllable.length() == 2) return syllable;
            Character ending = FINALS.get(syllable);
            return ending == null ? "" : "" + syllable.charAt(0) + ending;
        }
        int length = syllable.startsWith("zh") || syllable.startsWith("ch") || syllable.startsWith("sh") ? 2 : 1;
        char first = syllable.startsWith("zh") ? 'v' : syllable.startsWith("ch") ? 'i' : syllable.startsWith("sh") ? 'u' : syllable.charAt(0);
        Character last = FINALS.get(syllable.substring(length));
        return last == null ? "" : "" + first + last;
    }
    private static Map<String, List<String>> pairs() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String syllable : PinyinLexer.STANDARD_SYLLABLES) {
            String pair = encode(syllable);
            if (!pair.isEmpty()) result.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(syllable);
        }
        for (List<String> values : result.values()) Collections.sort(values);
        return result;
    }
    void clear() { raw = ""; locks.clear(); }
    void sync(String value) {
        if (raw.equals(value)) return;
        int common = 0;
        while (common < Math.min(raw.length(), value.length()) && raw.charAt(common) == value.charAt(common)) common++;
        // Locks refer to raw spans, so any edit invalidates that syllable and its successors.
        final int stableEnd = common;
        locks.keySet().removeIf(position -> position + 2 > stableEnd);
        raw = value;
    }
    void consumePrefix(String value, int consumed) {
        sync(value);
        int end = Math.max(0, Math.min(consumed, value.length()));
        Map<Integer, String> remaining = new HashMap<>();
        for (Map.Entry<Integer, String> lock : locks.entrySet()) {
            if (lock.getKey() >= end) remaining.put(lock.getKey() - end, lock.getValue());
        }
        locks.clear(); locks.putAll(remaining);
        raw = value.substring(end);
    }
    String signature(String input) {
        sync(input);
        StringBuilder result = new StringBuilder();
        List<Integer> positions = new ArrayList<>(locks.keySet()); Collections.sort(positions);
        for (int position : positions) result.append(position).append('=').append(locks.get(position)).append(';');
        return result.toString();
    }
    boolean select(String input, int start, int end, String reading) {
        sync(input);
        if (end - start != 2 || start < 0 || end > input.length()
                || !encode(reading).equals(input.substring(start, end))) return false;
        locks.put(start, reading); return true;
    }
    void unlock(int start) { locks.remove(start); }
    boolean selected(int start, String reading) { return reading.equals(locks.get(start)); }
    static List<String> choices(String entered) {
        if (entered.length() == 2) return new ArrayList<>(PAIRS.getOrDefault(entered, Collections.emptyList()));
        if (entered.length() != 1) return Collections.emptyList();
        char initial = entered.charAt(0);
        return Collections.singletonList(initial == 'v' ? "zh" : initial == 'i' ? "ch" : initial == 'u' ? "sh" : entered);
    }
    List<Path> paths(String input) {
        String key = input + ':' + signature(input);
        if (key.equals(cachedPathKey)) return cachedPaths;
        List<Path> found = Collections.unmodifiableList(paths(input, new HashMap<>(locks)));
        cachedPaths = found; cachedPathKey = key; return found;
    }
    private static List<Path> paths(String input, Map<Integer, String> locks) {
        if (input.isEmpty() || input.length() > 96) return Collections.emptyList();
        List<Path> result = new ArrayList<>(); result.add(new Path(Collections.emptyList(), Collections.emptyList()));
        for (int at = 0; at < input.length();) {
            QueryWork.check();
            if (input.charAt(at) == '\'') { at++; continue; }
            int end = Math.min(input.length(), at + 2);
            if (end > at + 1 && input.charAt(at + 1) == '\'') end = at + 1;
            List<String> options = choices(input.substring(at, end));
            if (locks.containsKey(at)) options = Collections.singletonList(locks.get(at));
            if (options.isEmpty()) return Collections.emptyList();
            List<Path> next = new ArrayList<>();
            for (Path prior : result) for (String option : options) {
                if (next.size() >= 8) break;
                List<String> readings = new ArrayList<>(prior.readings); readings.add(option);
                List<Integer> ends = new ArrayList<>(prior.ends); ends.add(end);
                next.add(new Path(readings, ends));
            }
            result = next; at = end;
        }
        return result;
    }
    List<PinyinPathEditor.Segment> segments(String input, String preferred) {
        sync(input);
        List<Path> paths = paths(input);
        if (paths.isEmpty()) return Collections.emptyList();
        Path chosen = paths.get(0);
        for (Path path : paths) if (path.query.replace('\'', ' ').equals(preferred)) { chosen = path; break; }
        List<PinyinPathEditor.Segment> result = new ArrayList<>(); int start = 0;
        for (int i = 0; i < chosen.readings.size(); i++) {
            while (start < input.length() && input.charAt(start) == '\'') start++;
            int end = chosen.ends.get(i); String reading = chosen.readings.get(i);
            result.add(new PinyinPathEditor.Segment(start, end, reading, reading.length(), locks.containsKey(start), end - start < 2));
            start = end;
        }
        return result;
    }
}
