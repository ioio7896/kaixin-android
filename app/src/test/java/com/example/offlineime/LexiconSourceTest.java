package com.example.offlineime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.Test;

/** Guards the on-disk sources that are packaged as the app's offline lexicon assets. */
public class LexiconSourceTest {
    private static Path lexiconRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && current != null; i++, current = current.getParent()) {
            Path direct = current.resolve("lexicon");
            if (Files.isDirectory(direct)) return direct;
        }
        throw new AssertionError("lexicon directory not found from " + System.getProperty("user.dir"));
    }

    @Test public void allLexiconFamiliesArePresentAndPopulated() throws IOException {
        Path root = lexiconRoot();
        assertTrue(countDataLines(root.resolve("zh")) > 150_000);
        assertTrue(countDataLines(root.resolve("zh-ext")) > 10_000);
        assertTrue(countDataLines(root.resolve("en")) >= 20_000);
        assertTrue(countDataLines(root.resolve("core")) > 100);
    }

    @Test public void singleCharacterSourceUsesCharacterAndScoreFormat() throws IOException {
        Path file = lexiconRoot().resolve("zh/single_char_common_8105.txt");
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(line -> {
                String[] columns = line.split("\\t");
                assertEquals(2, columns.length);
                assertEquals(1, columns[0].codePointCount(0, columns[0].length()));
                Integer.parseInt(columns[1]);
            });
        }
    }

    @Test public void representativeBundledEntriesRemainAvailable() throws IOException {
        assertTrue(contains(lexiconRoot().resolve("zh-ext/technology.txt"),
                "生成式人工智能\tsheng cheng shi ren gong zhi neng\t8800"));
        assertTrue(contains(lexiconRoot().resolve("en/kaixin_common_english.txt"),
                "the\tthe\t10000"));
    }

    private static long countDataLines(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).flatMap(path -> {
                try { return Files.lines(path, StandardCharsets.UTF_8); }
                catch (IOException error) { throw new RuntimeException(error); }
            }).filter(line -> !line.isEmpty() && !line.startsWith("#")).count();
        }
    }

    private static boolean contains(Path file, String expected) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.anyMatch(expected::equals);
        }
    }
}