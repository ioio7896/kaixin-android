package com.example.offlineime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Display order only: longest spelling first, then alphabetical, without changing locks. */
final class PinyinDisplayOrder {
    static final Comparator<String> READINGS = Comparator.comparingInt(String::length)
            .reversed().thenComparing(Comparator.naturalOrder());
    private PinyinDisplayOrder() { }
    static List<List<String>> paths(List<List<String>> source) {
        LinkedHashMap<String, List<String>> unique = new LinkedHashMap<>();
        for (List<String> path : source) unique.putIfAbsent(String.join("'", path), path);
        List<List<String>> result = new ArrayList<>(unique.values());
        result.sort((left, right) -> {
            int a = 0, b = 0;
            for (String syllable : left) a += syllable.length();
            for (String syllable : right) b += syllable.length();
            if (a != b) return Integer.compare(b, a);
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                int comparison = READINGS.compare(left.get(i), right.get(i));
                if (comparison != 0) return comparison;
            }
            return Integer.compare(left.size(), right.size());
        });
        return result;
    }
}
