package com.example.offlineime;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Fast JVM regression tests for path splitting and candidate ranking. */
public class PinyinCoreTest {
    @Test public void splitsContinuousPinyinIntoBoundedPaths() {
        PinyinLexer lexer = new PinyinLexer(Arrays.asList("ni", "hao", "n", "i", "ha", "o"));
        List<List<String>> paths = lexer.splitAll("nihao", 8);
        assertFalse(paths.isEmpty());
        assertEquals(Arrays.asList("ni", "hao"), paths.get(0));
    }

    @Test public void apostropheForcesSyllableBoundary() {
        PinyinLexer lexer = new PinyinLexer(Arrays.asList("xi", "an", "xian"));
        List<List<String>> paths = lexer.splitAll("xi'an", 8);
        assertEquals(1, paths.size());
        assertEquals(Arrays.asList("xi", "an"), paths.get(0));
    }

    @Test public void rankingUsesScoreThenPhraseLength() {
        List<PinyinDictionary.Entry> ranked = CandidateRanker.rank(Arrays.asList(
                new PinyinDictionary.Entry("甲", 10),
                new PinyinDictionary.Entry("甲乙", 10),
                new PinyinDictionary.Entry("乙", 20)));
        assertEquals("乙", ranked.get(0).word);
        assertEquals("甲乙", ranked.get(1).word);
    }
    @Test public void exactMatchOutranksHigherFrequencyPrefix() {
        List<PinyinDictionary.Entry> ranked = CandidateRanker.rank(Arrays.asList(
                new PinyinDictionary.Entry("第四集", 3899, PinyinDictionary.TIER_EXACT_FULL),
                new PinyinDictionary.Entry("第四届", 8499, PinyinDictionary.TIER_PREFIX)));
        assertEquals("第四集", ranked.get(0).word);
        assertTrue(CandidateRanker.isPriority(ranked.get(0)));
        assertFalse(CandidateRanker.isPriority(new PinyinDictionary.Entry(
                "低频前缀", 100, PinyinDictionary.TIER_PREFIX)));
    }

    @Test public void mixedPinyinKeepsFullLeadingSyllables() {
        assertEquals(Arrays.asList("zuijzmy", "zuijinzmy", "zuijinzenmy"),
                PinyinDictionary.mixedCodes("zui jin zen me yang"));
        assertTrue(PinyinDictionary.mixedCodes("ni hao").isEmpty());
    }

    @Test public void customWordsIndexArbitraryFullAndInitialMixes() {
        List<String> aliases = PinyinDictionary.userMixedInputCodes("kai xin ce ci");
        assertTrue(aliases.contains("kaixcc"));
        assertTrue(aliases.contains("kxincci"));
        assertFalse(aliases.contains("kaixinceci"));
        assertFalse(aliases.contains("kxcc"));
    }

    @Test public void userWordOutranksCorpusAndShortFollowsFull() {
        List<PinyinDictionary.Entry> ranked = CandidateRanker.rank(Arrays.asList(
                new PinyinDictionary.Entry("简拼", 30000, PinyinDictionary.TIER_EXACT_SHORT),
                new PinyinDictionary.Entry("全拼", 1, PinyinDictionary.TIER_EXACT_FULL),
                new PinyinDictionary.Entry("用户", 1, PinyinDictionary.TIER_USER)));
        assertEquals("用户", ranked.get(0).word);
        assertEquals("全拼", ranked.get(1).word);
        assertEquals("简拼", ranked.get(2).word);
    }

    @Test public void standardSyllablesDoNotDependOnCorpus() {
        PinyinLexer lexer = new PinyinLexer(java.util.Collections.emptyList());
        assertEquals(Arrays.asList("sheng", "cheng", "shi"),
                lexer.splitAll("shengchengshi", 8).get(0));
    }

    @Test public void standardLexerPreservesExplicitApostropheBoundary() {
        PinyinLexer lexer = new PinyinLexer(java.util.Collections.emptyList());
        assertEquals(Arrays.asList("xi", "an"), lexer.splitAll("xi'an", 8).get(0));
    }
}
