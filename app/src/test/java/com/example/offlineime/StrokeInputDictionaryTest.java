package com.example.offlineime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.Test;

public class StrokeInputDictionaryTest {
    private StrokeInputDictionary dictionary() {
        NavigableMap<String, String> sequences = new TreeMap<>();
        sequences.put("1", "一");
        sequences.put("12", "十干");
        sequences.put("123", "王");
        Map<Integer, Integer> simplified = ranks("一十干王");
        Map<Integer, Integer> traditional = ranks("一干十王");
        return new StrokeInputDictionary(sequences, simplified, traditional);
    }

    @Test public void exactStrokeCandidatesPrecedePrefixCandidates() {
        List<PinyinDictionary.Entry> values = dictionary().suggest("1", false, 10);
        assertEquals("一", values.get(0).word);
        assertEquals(PinyinDictionary.TIER_EXACT_FULL, values.get(0).matchTier);
        for (int i = 1; i < values.size(); i++)
            assertEquals(PinyinDictionary.TIER_PREFIX, values.get(i).matchTier);
    }

    @Test public void scriptFrequencyChangesOrderWithinTheSameTier() {
        List<PinyinDictionary.Entry> simplified = dictionary().suggest("12", false, 10);
        List<PinyinDictionary.Entry> traditional = dictionary().suggest("12", true, 10);
        assertEquals(Arrays.asList("十", "干"), Arrays.asList(simplified.get(0).word, simplified.get(1).word));
        assertEquals(Arrays.asList("干", "十"), Arrays.asList(traditional.get(0).word, traditional.get(1).word));
    }

    @Test public void invalidAndBoundedQueriesAreSafe() {
        assertTrue(dictionary().suggest("", false, 10).isEmpty());
        assertTrue(dictionary().suggest("16", false, 10).isEmpty());
        assertTrue(dictionary().suggest("1", false, 0).isEmpty());
        assertEquals(2, dictionary().suggest("1", false, 2).size());
    }

    private static Map<Integer, Integer> ranks(String order) {
        Map<Integer, Integer> result = new HashMap<>();
        int[] points = order.codePoints().toArray();
        for (int i = 0; i < points.length; i++) result.put(points[i], i);
        return result;
    }
}
