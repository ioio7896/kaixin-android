package com.example.offlineime;

/** Small, dependency-free Unicode grapheme helper for safe backspace behavior. */
final class GraphemeUtils {
    private GraphemeUtils() { }

    static int previousClusterStart(CharSequence text, int end) {
        if (text == null || end <= 0) return 0;
        int index = end;
        int cp = Character.codePointBefore(text, index);
        index -= Character.charCount(cp);
        while (index > 0) {
            int before = Character.codePointBefore(text, index);
            int type = Character.getType(before);
            boolean mark = type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || before >= 0xFE00 && before <= 0xFE0F
                    || before >= 0x1F3FB && before <= 0x1F3FF;
            if (mark) {
                index -= Character.charCount(before);
                continue;
            }
            // Keep emoji ZWJ sequences together.
            if (before == 0x200D) {
                index -= Character.charCount(before);
                if (index > 0) {
                    int joined = Character.codePointBefore(text, index);
                    index -= Character.charCount(joined);
                }
                continue;
            }
            break;
        }
        return index;
    }
}
