package com.example.offlineime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class PinyinInputTest {
    private PinyinInput.Match match(String input, String reading) {
        return PinyinInput.match(input, reading, true, true, true, true, false);
    }
    @Test public void umlautAliasesAndTrailingSeparator() {
        assertEquals("lv", PinyinInput.normalize("LÜ"));
        assertEquals("lv", PinyinInput.normalize("lu:"));
        assertNotNull(match("ni'hao'", "ni hao"));
    }
    @Test public void explicitBoundaryCannotMatchAnotherSyllable() {
        assertNull(match("xi'an", "xian"));
        assertNotNull(match("xi'an", "xi an"));
        assertNull(match("n'ihao", "ni hao"));
    }
    @Test public void arbitraryMixedPositionsAndDoubleInitials() {
        for (String query : new String[]{"nihao", "nh", "nhao", "nih"}) assertNotNull(match(query, "ni hao"));
        for (String query : new String[]{"zg", "zhg", "zhongg", "zguo"}) assertNotNull(match(query, "zhong guo"));
        assertNotNull(match("gzuory", "gong zuo ren yuan"));
    }
    @Test public void independentFlagsAreRespected() {
        assertNull(PinyinInput.match("nh", "ni hao", true, false, true, true, false));
        assertNull(PinyinInput.match("nihao", "ni hao", false, true, true, true, false));
        assertNull(PinyinInput.match("nhao", "ni hao", true, true, false, true, false));
        assertNull(PinyinInput.match("zhg", "zhong guo", true, true, true, false, false));
    }
    @Test public void nineKeyMatchingUsesTheSameModeFlags() {
        assertNotNull(match("64426", "ni hao"));
        assertNotNull(match("64", "ni hao"));
        assertNotNull(match("6426", "ni hao"));
        assertNull(PinyinInput.match("64426", "ni hao", false, true, true, true, false));
        assertNull(PinyinInput.match("6426", "ni hao", true, true, false, true, false));
    }
    @Test public void sentenceConsumesWordEdgesAndPreservesSegments() {
        Map<String, List<PinyinDictionary.Entry>> words = new HashMap<>();
        words.put("wo", Arrays.asList(new PinyinDictionary.Entry("我", 10000)));
        words.put("jin'tian", Arrays.asList(new PinyinDictionary.Entry("今天", 10000)));
        words.put("hui'jia", Arrays.asList(new PinyinDictionary.Entry("回家", 10000)));
        List<PinyinDictionary.Entry> out = SentenceComposer.composeWords(Arrays.asList("wo", "jin", "tian", "hui", "jia"),
                q -> words.getOrDefault(q, Collections.emptyList()), 17);
        assertEquals("我今天回家", out.get(0).word);
        assertEquals(Arrays.asList("我", "今天", "回家"), out.get(0).wordSegments);
        assertEquals(17, out.get(0).consumedLength);
    }
    @Test public void copiedCandidatesPreserveTapSnapshotMetadata() {
        PinyinDictionary.Entry original = new PinyinDictionary.Entry("你好", 3).withInput(5, "ni hao");
        original.queryText = "nihao"; original.wordSegments = Arrays.asList("你好");
        PinyinDictionary.Entry copy = original.withScore(8);
        assertEquals("nihao", copy.queryText); assertEquals(5, copy.consumedLength);
        assertEquals(original.wordSegments, copy.wordSegments);
    }
    @Test public void pathAndSentenceBounds() {
        String longInput = String.join("'", Collections.nCopies(33, "ni"));
        assertTrue(PinyinInput.paths(longInput, true, false, true).isEmpty());
        assertTrue(PinyinInput.paths(String.join("", Collections.nCopies(200, "a")), true, true, true).isEmpty());
    }
}
