package com.example.offlineime;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class HanConverterTest {
    private HanConverter converter() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("后", "後");
        values.put("后台", "後臺");
        values.put("发", "發");
        values.put("头发", "頭髮");
        return new HanConverter(values);
    }

    @Test public void phraseConversionUsesLongestMatch() {
        assertEquals("後臺頭髮", converter().toTraditional("后台头发"));
    }

    @Test public void mixedLatinEmojiAndPunctuationRemainUnchanged() {
        assertEquals("A後臺😀，test!", converter().toTraditional("A后台😀，test!"));
    }

    @Test public void nullAndEmptyAreStable() {
        assertEquals(null, converter().toTraditional(null));
        assertEquals("", converter().toTraditional(""));
    }
}
