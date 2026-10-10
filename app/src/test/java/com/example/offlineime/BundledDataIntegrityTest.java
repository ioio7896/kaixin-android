package com.example.offlineime;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class BundledDataIntegrityTest {
    @Test public void strokeAndOpenCcAssetsArePopulatedAndWellFormed() throws IOException {
        Path assets = projectRoot().resolve("app/src/main/assets");
        List<String> sequences = Files.readAllLines(assets.resolve("stroke/sequence-characters.txt"), StandardCharsets.UTF_8);
        int rows = 0, characters = 0;
        Set<String> keys = new HashSet<>();
        for (String line : sequences) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\\t", 2);
            assertTrue(columns.length == 2 && columns[0].matches("[1-5]+") && !columns[1].isEmpty());
            assertTrue(keys.add(columns[0]));
            rows++; characters += columns[1].codePointCount(0, columns[1].length());
        }
        assertTrue(rows > 50_000 && characters > 60_000);
        assertTrue(dataLines(assets.resolve("opencc/STCharacters.txt")) > 3_500);
        assertTrue(dataLines(assets.resolve("opencc/STPhrases.txt")) > 45_000);
        assertTrue(Files.size(assets.resolve("opencc/LICENSE.txt")) > 1_000);
        assertTrue(Files.size(assets.resolve("stroke/NOTICE.txt")) > 100);
    }

    private static long dataLines(Path file) throws IOException {
        try (java.util.stream.Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isEmpty() && !line.startsWith("#")).count();
        }
    }

    private static Path projectRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++, current = current.getParent())
            if (Files.isDirectory(current.resolve("app/src/main/assets"))) return current;
        throw new AssertionError("project root not found");
    }
}
