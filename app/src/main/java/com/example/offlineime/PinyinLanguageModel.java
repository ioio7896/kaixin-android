package com.example.offlineime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Tiny embedded n-gram model; deterministic and fully offline. */
final class PinyinLanguageModel {
    private final Map<String,Integer> bigrams = new HashMap<>();
    PinyinLanguageModel() {
        add("我", "们", 900); add("我", "的", 700); add("今", "天", 900); add("明", "天", 850);
        add("中", "国", 1000); add("世", "界", 950); add("你", "好", 1000); add("谢", "谢", 950);
    }
    private void add(String a, String b, int score) { bigrams.put(a + b, score); }
    int transitionScore(String previous, String next) {
        if (previous == null || next == null || previous.isEmpty() || next.isEmpty()) return 0;
        String a = previous.substring(previous.length() - 1), b = next.substring(0, 1);
        return bigrams.getOrDefault(a + b, 0);
    }
    Map<String,Integer> snapshot() { return Collections.unmodifiableMap(bigrams); }
}
