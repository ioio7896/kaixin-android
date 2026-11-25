package com.example.offlineime;

/** JNI bridge to the Rust pinyin engine (engine/ in the project root). */
final class NativeEngine {
    static final int MODE_PINYIN = 0;
    static final int MODE_NINE = 1;
    static final int MODE_ENGLISH = 2;

    static final int FLAG_FULL = 1;
    static final int FLAG_SHORT = 1 << 1;
    static final int FLAG_FUZZY = 1 << 2;
    static final int FLAG_SENTENCE = 1 << 3;
    static final int FLAG_MIXED_ENGLISH = 1 << 4;
    static final int FLAG_BASIC_CONTEXT = 1 << 5;
    static final int FLAG_NAMES = 1 << 6;
    static final int FLAG_CREATION = 1 << 7;
    static final int FLAG_DOUBLE_INITIALS = 1 << 8;
    static final int FLAG_NINE_COMPLETION = 1 << 9;
    static final int FLAG_SPELLING_CORRECTION = 1 << 10;
    static final int FLAG_TRADITIONAL = 1 << 11;
    /** Fuzzy pairs zh/z, ch/c, sh/s, n/l, h/f, ang/an, eng/en, ing/in, iang/ian start here. */
    static final int FUZZY_SHIFT = 16;

    /** Separator between constraint parts. */
    static final char UNIT = '\u001f';

    private static final String[] STANDARD;

    static {
        System.loadLibrary("kaixin_engine");
        STANDARD = standardSyllables();
    }

    private NativeEngine() { }

    static String[] standard() { return STANDARD.clone(); }

    static native long openFd(int fd, long offset, long length);
    static native long openFile(String path);
    static native void close(long handle);
    static native long cancelQueries(long handle);
    private static native String[] standardSyllables();

    /** A byte indicating possible truncation, followed by little-endian candidate rows. */
    static native byte[] query(long handle, int mode, String raw, String constraint,
                               String previous2, String previous, int flags, int limit, String[] touches,
                               long cancelGen);
    static native String[] paths(long handle, String raw, boolean numeric, boolean doubleInitials, int limit);
    static native String[] emojiSuggestions(long handle, String word);
    static native String[] syllableChoices(long handle, String raw, boolean numeric);
    static native String[] predict(long handle, String previous2, String previous, int limit, boolean basic);
    static native String[] readings(long handle, String word);
    /** Canonical {@code syl'syl} reading, or {@code !message} when invalid. */
    static native String canonicalReading(String code, String word);

    static native boolean learn(long handle, String reading, String word, boolean english);
    static native void learnContext(long handle, String previous2, String previous, String word);
    /** Rows of word, readings (';'), aliases (';'), pinned ("1"/"0"). */
    static native void setCustomWords(long handle, String[] rows);
    static native void setBlockedWords(long handle, String[] words);
    /** Returns the word and context revisions. A null argument keeps the current data of that kind. */
    static native long[] importUser(long handle, String words, String context);
    /** Imports binary (KXUSR1/KXCTX2) or legacy UTF-8 text stores without UTF-16 JNI copies. */
    static native long[] importUserBinary(long handle, byte[] words, byte[] context);
    /** Revisions followed by the serialized stores that changed, captured under one native lock. */
    static native String[] snapshotUser(long handle, long savedWords, long savedContext);
    /** Packed binary snapshot: wordsRev, ctxRev, wordsLen, wordsBytes, ctxLen, ctxBytes; null if unchanged. */
    static native byte[] snapshotUserBinary(long handle, long savedWords, long savedContext);
    /** Decodes KXUSR1 or UTF-8 learned words to tab-separated rows, or null on an invalid payload. */
    static native String decodeWordsToText(byte[] raw);
    static native boolean forget(long handle, String word);
    static native boolean setPinned(long handle, String word, boolean pinned);
    static native String convertHan(long handle, String text, boolean traditional);
    /** Null elements denote unchanged strings; a null array means no batch was produced. */
    static native String[] convertHanBatch(long handle, String[] words, long cancelGen);
    static native byte[] suggestStroke(long handle, String strokes, boolean traditional, int limit,
                                       long cancelGen);

    /** Registers the allowed-character mask for the extended handwriting CTC model. */
    static native void setCtcAllowed(byte[] allowed);
    /** Bounded CTC prefix beam search in Rust. */
    static native byte[] decodeCtc(int[] ids, float[] probs, int steps, boolean single);
    /** Geometric template matching and five-stroke classification in Rust. */
    static native byte[] analyzeStrokes(float[] coords, int[] strokeLens);
}
