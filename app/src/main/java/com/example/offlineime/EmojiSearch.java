package com.example.offlineime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline search across Unicode names, keywords, categories and literal glyphs. */
final class EmojiSearch {
    private EmojiSearch() { }

    static List<String> find(EmojiCatalog catalog, String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<String> exact = new ArrayList<>(), matching = new ArrayList<>();
        if (normalized.isEmpty()) return exact;
        String[] terms = normalized.split("\\s+");
        String literalQuery = EmojiCatalog.key(normalized);
        boolean tones = normalized.contains("肤色") || normalized.contains("skin tone");
        for (EmojiCatalog.Item item : catalog.items()) {
            boolean literal = EmojiCatalog.key(item.glyph).equals(literalQuery);
            if (!literal && !tones && !item.base.equals(item.glyph)) continue;
            if (literal || item.name.equalsIgnoreCase(normalized)) {
                if (EmojiCatalog.visible(item.glyph)) exact.add(item.glyph);
            } else {
                boolean matches = true;
                for (String term : terms) if (!item.searchable.contains(term)) { matches = false; break; }
                if (matches && EmojiCatalog.visible(item.glyph)) matching.add(item.glyph);
            }
        }
        exact.addAll(matching);
        return exact;
    }
}
