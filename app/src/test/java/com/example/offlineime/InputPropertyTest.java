package com.example.offlineime;

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.Test;

public class InputPropertyTest {
    @Test public void seededRandomInputsStayBoundedAndNeverThrow() {
        Random random = new Random(20261004L);
        String alphabet = "abcdefghijklmnopqrstuvwxyzü:'’0123456789-😀";
        for (int sample = 0; sample < 2000; sample++) {
            int length = random.nextInt(210);
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < length; i++) raw.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String normalized = PinyinInput.normalize(raw.toString());
            assertTrue(normalized.length() <= raw.length() * 2 + 1);
            List<PinyinInput.Path> paths = PinyinInput.paths(raw.toString(), true, true, true);
            assertTrue(paths.size() <= PinyinInput.MAX_PATHS);
            for (PinyinInput.Path path : paths)
                assertTrue(path.tokens.size() <= PinyinInput.MAX_SYLLABLES);
        }
    }
}
