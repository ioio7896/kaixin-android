package com.example.offlineime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Dependencies of learned words, including queries whose previous result was empty. */
final class LearningChange {
    final Set<String> words = new LinkedHashSet<>();
    final Set<String> inputKeys = new LinkedHashSet<>();
    final Set<String> fragments = new LinkedHashSet<>();

    void reading(String reading) {
        String full = PinyinInput.normalize(reading.replace(' ', '\'')).replace("'", "");
        if (full.isEmpty()) return;
        fragments.add(full); fragments.add(PinyinDictionary.toT9(full));
        StringBuilder initials = new StringBuilder();
        for (String part : reading.split("[ ']+")) if (!part.isEmpty()) initials.append(part.charAt(0));
        addPrefixes(full); addPrefixes(initials.toString());
        for (String alias : PinyinDictionary.userMixedInputCodes(reading)) {
            inputKeys.add(alias); inputKeys.add(PinyinDictionary.toT9(alias));
        }
    }
    private void addPrefixes(String value) {
        for (int i = 1; i <= value.length(); i++) {
            String prefix = value.substring(0, i);
            inputKeys.add(prefix); inputKeys.add(PinyinDictionary.toT9(prefix));
        }
    }
    boolean affects(String raw, List<PinyinDictionary.Entry> entries) {
        if (words.isEmpty()) return false;
        if (entries != null) for (PinyinDictionary.Entry entry : entries)
            for (String word : words) if (entry.word.contains(word)) return true;
        String query = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT).replace("'", "").replace(" ", "");
        if (query.isEmpty()) return false;
        if (inputKeys.contains(query)) return true;
        for (String fragment : fragments) if (!fragment.isEmpty() && query.contains(fragment)) return true;
        return false;
    }
}
