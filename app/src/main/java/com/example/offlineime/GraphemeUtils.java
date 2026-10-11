package com.example.offlineime;

import android.icu.text.BreakIterator;
import java.util.Locale;

/** Small, dependency-free Unicode grapheme helper for safe backspace behavior. */
final class GraphemeUtils {
    private GraphemeUtils() { }

    static int previousClusterStart(CharSequence text, int end) {
        if (text == null || end <= 0) return 0;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text.toString());
        int result = iterator.preceding(Math.min(end, text.length()));
        return result == BreakIterator.DONE ? 0 : result;
    }

    static int move(CharSequence text, int from, int steps) {
        String value = text == null ? "" : text.toString();
        int position = Math.max(0, Math.min(value.length(), from));
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(value);
        for (int i = 0; i < Math.min(32, Math.abs(steps)); i++) {
            int next = steps < 0 ? iterator.preceding(position) : iterator.following(position);
            if (next == BreakIterator.DONE) return steps < 0 ? 0 : value.length();
            position = next;
        }
        return position;
    }

    static int previousWordStart(CharSequence text) {
        if (text == null || text.length() == 0) return 0;
        String value = text.toString();
        int end = value.length(), at = end;
        while (at > 0 && Character.isWhitespace(value.codePointBefore(at)))
            at = previousClusterStart(value, at);
        int lowerBound = move(value, end, -16);
        if (at == 0) return lowerBound;
        BreakIterator words = BreakIterator.getWordInstance(Locale.CHINESE);
        words.setText(value);
        int start = words.preceding(at);
        return Math.max(lowerBound, start == BreakIterator.DONE ? 0 : start);
    }
}
