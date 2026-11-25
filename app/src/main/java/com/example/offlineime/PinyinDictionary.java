package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Java facade over the Rust engine. The bundled lexicon is a memory-mapped
 * asset; learned words and context stay in the keystore-encrypted stores.
 */
public final class PinyinDictionary {
    private static final String TAG = "OfflineImeLexicon";
    static final String DICTIONARY_ASSET = "engine/kaixin.dict";
    static final String WORDS_PREFS = "user_words";
    static final String WORDS_KEY = "offline_ime_words";
    static final String CONTEXT_PREFS = "context_model";
    static final String CONTEXT_KEY = "offline_ime_context";

    static final int TIER_USER = 0;
    static final int TIER_EXACT_FULL = 10;
    static final int TIER_EXACT_SHORT = 20;
    static final int TIER_MIXED = 25;
    static final int TIER_PREFIX = 30;
    static final int TIER_FUZZY = 40;
    static final int TIER_COMPOSED = 50;

    public static final class Entry {
        public final String word;
        final String originalWord;
        public final int score;
        public final int corpusScore;
        public final int matchTier;
        public final int rankingTier;
        public final boolean pinned;
        public final int codePointLength;
        int personalBonus;
        int contextBonus;
        int correctionPenalty;
        long newWordPriorityUntil;
        long recentWordPriorityUntil;
        long recentWordUsedAt;
        boolean nineStation, emojiSuggestion;
        public int consumedLength;
        public String pronunciation = "";
        public String queryText = "";
        public String inputVersion = "";
        public List<String> wordSegments = Collections.emptyList();

        Entry withInput(int consumed, String reading) {
            return copy(word, consumed, reading);
        }

        Entry withDisplayWord(String displayWord) {
            return java.util.Objects.equals(word, displayWord) ? this : copy(displayWord, consumedLength, pronunciation);
        }

        private Entry copy(String displayWord, int consumed, String reading) {
            Entry copy = new Entry(displayWord, score, matchTier, pinned, corpusScore, rankingTier, originalWord);
            copy.consumedLength = consumed; copy.pronunciation = reading == null ? "" : reading;
            copy.queryText = queryText; copy.wordSegments = wordSegments; copy.inputVersion = inputVersion;
            copy.personalBonus = personalBonus; copy.contextBonus = contextBonus;
            copy.correctionPenalty = correctionPenalty;
            copy.newWordPriorityUntil = newWordPriorityUntil;
            copy.recentWordPriorityUntil = recentWordPriorityUntil;
            copy.recentWordUsedAt = recentWordUsedAt;
            copy.nineStation = nineStation;
            copy.emojiSuggestion = emojiSuggestion;
            return copy;
        }

        Entry(String word, int score) { this(word, score, TIER_EXACT_FULL, false, score); }
        Entry(String word, int score, int matchTier) { this(word, score, matchTier, false, score); }
        Entry(String word, int score, int matchTier, boolean pinned) { this(word, score, matchTier, pinned, score); }
        Entry(String word, int score, int matchTier, boolean pinned, int corpusScore) {
            this(word, score, matchTier, pinned, corpusScore, matchTier);
        }
        Entry(String word, int score, int matchTier, boolean pinned, int corpusScore, int rankingTier) {
            this(word, score, matchTier, pinned, corpusScore, rankingTier, word);
        }
        private Entry(String word, int score, int matchTier, boolean pinned, int corpusScore, int rankingTier,
                      String originalWord) {
            this.word = word;
            this.originalWord = originalWord;
            this.score = score;
            this.matchTier = matchTier;
            this.rankingTier = rankingTier;
            this.pinned = pinned;
            this.corpusScore = corpusScore;
            this.codePointLength = word == null ? 0 : word.codePointCount(0, word.length());
        }
    }

    /** One confirmed choice queued for learning. */
    static final class Selection {
        final String code, word, usage, skipped;
        final int tier;
        final boolean assembled;
        Selection(String code, String word, String usage, String skipped, int tier, boolean assembled) {
            this.code = code; this.word = word; this.usage = usage; this.skipped = skipped; this.tier = tier;
            this.assembled = assembled;
        }
    }

    private static final class NativeRef {
        final ReentrantReadWriteLock lock;
        volatile long handle;
        NativeRef(ReentrantReadWriteLock lock, long handle) {
            this.lock = lock;
            this.handle = handle;
        }
    }

    private static volatile NativeRef activeNative;

    private final AssetManager assets;
    private final Context context;
    private final SharedPreferences settings;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> refreshCachedSettings();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private long handle;
    private NativeRef nativeRef;
    private boolean closed;
    private volatile boolean loaded;
    private volatile boolean userReady;
    private volatile int cachedAlphaFlags;
    private volatile int cachedNineFlags;
    private volatile boolean cachedBasicContext = true;
    private volatile boolean cachedDoubleInitials = true;
    private volatile boolean cachedPrivacyMode;
    private long wordsRevision = Long.MIN_VALUE;
    private long contextRevision = Long.MIN_VALUE;
    private long customRevision = Long.MIN_VALUE;
    private long savedWordsRevision;
    private long savedContextRevision;

    public PinyinDictionary(AssetManager assets, Context context) {
        this.assets = assets;
        this.context = context == null ? null : context.getApplicationContext();
        this.settings = this.context == null ? null
                : this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        refreshCachedSettings();
        if (this.settings != null) {
            this.settings.registerOnSharedPreferenceChangeListener(settingsListener);
        }
    }

    void refreshCachedSettings() {
        cachedBasicContext = preference("basic_context", true);
        cachedDoubleInitials = preference("double_initials", true);
        cachedPrivacyMode = preference("privacy_mode", false);
        int common = 0;
        if (preference("full_pinyin", true)) common |= NativeEngine.FLAG_FULL;
        if (cachedDoubleInitials) common |= NativeEngine.FLAG_DOUBLE_INITIALS;
        if (preference("mixed_english", true)) common |= NativeEngine.FLAG_MIXED_ENGLISH;
        if (cachedBasicContext) common |= NativeEngine.FLAG_BASIC_CONTEXT;
        if (preference("fuzzy_pinyin", false)) {
            common |= NativeEngine.FLAG_FUZZY;
            for (int i = 0; i < FUZZY_KEYS.length; i++)
                if (preference(FUZZY_KEYS[i], false)) common |= 1 << (NativeEngine.FUZZY_SHIFT + i);
        }
        boolean shortPinyin = preference("short_pinyin", true);
        int alpha = common;
        if (shortPinyin) alpha |= NativeEngine.FLAG_SHORT;
        if (preference("sentence_prediction", true)) alpha |= NativeEngine.FLAG_SENTENCE;
        int nine = common;
        if (shortPinyin && preference("nine_short_words", true)) nine |= NativeEngine.FLAG_SHORT;
        if (preference("nine_word_completion", false)) nine |= NativeEngine.FLAG_NINE_COMPLETION;
        if (preference("nine_sentence_prediction", false)) nine |= NativeEngine.FLAG_SENTENCE;
        cachedAlphaFlags = alpha;
        cachedNineFlags = nine;
    }

    public boolean isLoaded() { return loaded; }
    boolean isUserDataReady() { return userReady; }

    /** Maps the bundled lexicon and loads personal data; runs on the loader thread. */
    public void load(Runnable onFinished) {
        try {
            long opened = open();
            lock.writeLock().lock();
            try {
                if (closed) {
                    if (opened != 0) NativeEngine.close(opened);
                    return;
                }
                handle = opened;
                if (opened != 0) {
                    nativeRef = new NativeRef(lock, opened);
                    activeNative = nativeRef;
                }
            } finally { lock.writeLock().unlock(); }
            if (opened == 0) Log.e(TAG, "native lexicon unavailable");
            else reloadManagedWords();
        } catch (RuntimeException failure) {
            Log.e(TAG, "native lexicon failed", failure);
        } finally {
            loaded = true;
            if (onFinished != null) onFinished.run();
        }
    }

    private long open() {
        try (AssetFileDescriptor descriptor = assets.openFd(DICTIONARY_ASSET)) {
            long mapped = NativeEngine.openFd(descriptor.getParcelFileDescriptor().getFd(),
                    descriptor.getStartOffset(), descriptor.getLength());
            if (mapped != 0) return mapped;
        } catch (IOException compressed) {
            // A compressed asset cannot be mapped in place; fall back to a private copy.
        }
        if (context == null) return 0;
        File target = new File(context.getNoBackupFilesDir(), "kaixin-" + BuildConfig.VERSION_CODE + ".dict");
        if (!target.isFile()) {
            File partial = new File(target.getPath() + ".part");
            try (InputStream in = assets.open(DICTIONARY_ASSET); OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            } catch (IOException failure) {
                Log.e(TAG, "lexicon copy failed", failure);
                return 0;
            }
            if (!partial.renameTo(target)) return 0;
            File[] stale = context.getNoBackupFilesDir().listFiles((dir, name) ->
                    name.startsWith("kaixin-") && name.endsWith(".dict") && !name.equals(target.getName()));
            if (stale != null) for (File file : stale) if (!file.delete()) Log.w(TAG, "stale lexicon kept: " + file);
        }
        return NativeEngine.openFile(target.getPath());
    }

    void close() {
        if (settings != null) settings.unregisterOnSharedPreferenceChangeListener(settingsListener);
        lock.writeLock().lock();
        try {
            closed = true;
            if (nativeRef != null) {
                nativeRef.handle = 0;
                if (activeNative == nativeRef) activeNative = null;
                nativeRef = null;
            }
            if (handle != 0) NativeEngine.close(handle);
            handle = 0;
        } finally { lock.writeLock().unlock(); }
    }

    /** Atomically invalidates any in-flight native queries and returns the new generation token. */
    long cancelQueries() {
        lock.readLock().lock();
        try {
            return handle == 0 ? 0L : NativeEngine.cancelQueries(handle);
        } finally { lock.readLock().unlock(); }
    }

    void setBlockedWords(java.util.Collection<String> words) {
        lock.readLock().lock();
        try {
            if (handle == 0) return;
            String[] array = words == null || words.isEmpty() ? new String[0] : words.toArray(new String[0]);
            NativeEngine.setBlockedWords(handle, array);
        } finally { lock.readLock().unlock(); }
    }

    /** Zero-heap Simplified <-> Traditional conversion backed by the mmap'd Rust dictionary. */
    static String convertHanNative(String text, boolean traditional) {
        NativeRef active = activeNative;
        if (active == null || text == null || text.isEmpty()) return null;
        active.lock.readLock().lock();
        try {
            long nativeHandle = active.handle;
            if (nativeHandle == 0) return null;
            return NativeEngine.convertHan(nativeHandle, text, traditional);
        } catch (LinkageError ignored) {
            return null;
        } finally {
            active.lock.readLock().unlock();
        }
    }

    static String[] convertHanBatchNative(String[] words) {
        NativeRef active = activeNative;
        if (active == null) return null;
        active.lock.readLock().lock();
        try {
            QueryWork.check();
            long nativeHandle = active.handle;
            if (nativeHandle == 0) return null;
            return NativeEngine.convertHanBatch(nativeHandle, words, QueryWork.cancelGen());
        } catch (LinkageError ignored) {
            return null;
        } finally {
            active.lock.readLock().unlock();
        }
    }

    /** Zero-heap five-stroke candidate lookup backed by the mmap'd Rust dictionary. */
    static List<Entry> suggestStrokeNative(String strokes, boolean traditional, int limit) {
        NativeRef active = activeNative;
        if (active == null) return null;
        byte[] encoded;
        active.lock.readLock().lock();
        try {
            QueryWork.check();
            long nativeHandle = active.handle;
            if (nativeHandle == 0) return null;
            encoded = NativeEngine.suggestStroke(nativeHandle, strokes, traditional, limit, QueryWork.cancelGen());
        } catch (LinkageError ignored) {
            return null;
        } finally {
            active.lock.readLock().unlock();
        }
        QueryWork.check();
        if (encoded == null) return null;
        if (encoded.length == 0) return Collections.emptyList();
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        ArrayList<Entry> result = new ArrayList<>(encoded.length / 12);
        while (buffer.remaining() >= 12) {
            int cp = buffer.getInt();
            int score = buffer.getInt();
            boolean exact = buffer.getInt() != 0;
            result.add(new Entry(new String(Character.toChars(cp)), score,
                    exact ? TIER_EXACT_FULL : TIER_PREFIX).withInput(strokes.length(), strokes));
        }
        return result;
    }

    void trimQueryCaches() { }

    // ---------------------------------------------------------------- queries

    private boolean preference(String name, boolean fallback) {
        return settings == null ? fallback : settings.getBoolean(name, fallback);
    }

    private static final String[] FUZZY_KEYS = {"fuzzy_zh_z", "fuzzy_ch_c", "fuzzy_sh_s", "fuzzy_n_l",
            "fuzzy_h_f", "fuzzy_an_ang", "fuzzy_en_eng", "fuzzy_in_ing", "fuzzy_ian_iang"};

    int flags(OfflineImeService.Mode mode, boolean creating, boolean names) {
        int flags = mode == OfflineImeService.Mode.NINE ? cachedNineFlags : cachedAlphaFlags;
        if (names) flags |= NativeEngine.FLAG_NAMES;
        if (creating) flags |= NativeEngine.FLAG_CREATION;
        return flags;
    }

    static int nativeMode(OfflineImeService.Mode mode) {
        if (mode == OfflineImeService.Mode.NINE) return NativeEngine.MODE_NINE;
        if (mode == OfflineImeService.Mode.ENGLISH) return NativeEngine.MODE_ENGLISH;
        return NativeEngine.MODE_PINYIN;
    }

    static final class LookupResult {
        final List<Entry> entries;
        final boolean mayHaveMore;

        LookupResult(List<Entry> entries, boolean mayHaveMore) {
            this.entries = entries;
            this.mayHaveMore = mayHaveMore;
        }
    }

    /** Ranked candidates for one composition. {@code constraint} is the editor encoding or "". */
    LookupResult lookup(OfflineImeService.Mode mode, String raw, String constraint,
                        String previous2, String previous, int flags, int limit, String[] touches) {
        byte[] rows;
        lock.readLock().lock();
        try {
            QueryWork.check();
            if (handle == 0 || raw == null || raw.isEmpty()) {
                return new LookupResult(Collections.emptyList(), false);
            }
            rows = NativeEngine.query(handle, nativeMode(mode), raw, constraint == null ? "" : constraint,
                    previous2 == null ? "" : previous2, previous == null ? "" : previous, flags, limit, touches,
                    QueryWork.cancelGen());
        } finally { lock.readLock().unlock(); }
        QueryWork.check();
        if (rows == null || rows.length == 0) return new LookupResult(Collections.emptyList(), false);
        ByteBuffer buffer = ByteBuffer.wrap(rows).order(ByteOrder.LITTLE_ENDIAN);
        boolean mayHaveMore = buffer.get() != 0;
        return new LookupResult(entries(buffer, raw), mayHaveMore);
    }

    private static List<Entry> entries(ByteBuffer buffer, String raw) {
        ArrayList<Entry> result = new ArrayList<>();
        while (buffer.hasRemaining()) {
            int consumed = buffer.getInt();
            int tier = buffer.getInt();
            int score = buffer.getInt();
            String word = readCandidateText(buffer);
            String originalWord = readCandidateText(buffer);
            String reading = readCandidateText(buffer);
            Entry entry = new Entry(word, score, tier, tier == TIER_USER, score, tier,
                    originalWord.isEmpty() ? word : originalWord);
            entry.consumedLength = Math.min(raw.length(), consumed);
            entry.pronunciation = reading;
            int segmentCount = buffer.getInt();
            if (segmentCount > 0) {
                ArrayList<String> segments = new ArrayList<>(segmentCount);
                for (int i = 0; i < segmentCount; i++) segments.add(readCandidateText(buffer));
                entry.wordSegments = Collections.unmodifiableList(segments);
            }
            result.add(entry);
        }
        return result;
    }

    private static String readCandidateText(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length == 0) return "";
        int start = buffer.position();
        buffer.position(start + length);
        return new String(buffer.array(), start, length, StandardCharsets.UTF_8);
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return 0; }
    }

    String[] emojiSuggestions(String word) {
        lock.readLock().lock();
        try {
            return handle == 0 ? null : NativeEngine.emojiSuggestions(handle, word);
        } finally { lock.readLock().unlock(); }
    }

    List<Entry> predict(String previous2, String previous, int limit) {
        String[] words;
        lock.readLock().lock();
        try {
            if (handle == 0 || previous == null || previous.isEmpty()) return Collections.emptyList();
            words = NativeEngine.predict(handle, previous2 == null ? "" : previous2, previous, limit,
                    cachedBasicContext);
        } finally { lock.readLock().unlock(); }
        ArrayList<Entry> result = new ArrayList<>();
        if (words != null) for (int i = 0; i < words.length; i++)
            result.add(new Entry(words[i], 1000 - i, TIER_EXACT_FULL));
        return result;
    }

    /** Readable segmentations of typed letters or T9 digits, most plausible first. */
    public List<List<String>> paths(String raw, boolean numeric) {
        String[] values;
        lock.readLock().lock();
        try {
            if (handle == 0 || raw == null || raw.isEmpty()) return Collections.emptyList();
            values = NativeEngine.paths(handle, raw, numeric, cachedDoubleInitials, 32);
        } finally { lock.readLock().unlock(); }
        List<List<String>> result = new ArrayList<>();
        if (values != null) for (String value : values)
            if (!value.isEmpty()) result.add(Collections.unmodifiableList(Arrays.asList(value.split("'"))));
        return result;
    }

    List<String> syllableChoices(String raw, boolean numeric) {
        lock.readLock().lock();
        try {
            if (handle == 0 || raw == null || raw.isEmpty()) return new ArrayList<>();
            String[] values = NativeEngine.syllableChoices(handle, raw, numeric);
            return values == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(values));
        } finally { lock.readLock().unlock(); }
    }

    List<String> wordReadings(String word) {
        lock.readLock().lock();
        try {
            if (handle == 0 || word == null || word.isEmpty()) return Collections.emptyList();
            String[] values = NativeEngine.readings(handle, word);
            return values == null ? Collections.emptyList() : Arrays.asList(values);
        } finally { lock.readLock().unlock(); }
    }

    String wordPronunciation(String word) {
        List<String> readings = wordReadings(word);
        return readings.isEmpty() ? "" : readings.get(0);
    }

    static String toT9(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = Character.toLowerCase(code.charAt(i));
            if (c >= 'a' && c <= 'c') out.append('2');
            else if (c >= 'd' && c <= 'f') out.append('3');
            else if (c >= 'g' && c <= 'i') out.append('4');
            else if (c >= 'j' && c <= 'l') out.append('5');
            else if (c >= 'm' && c <= 'o') out.append('6');
            else if (c >= 'p' && c <= 's') out.append('7');
            else if (c >= 't' && c <= 'v') out.append('8');
            else if (c >= 'w' && c <= 'z') out.append('9');
            else out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------- learning

    /** Learns a confirmed word in memory; {@link #persistLearnedWords()} saves it. */
    boolean learn(String reading, String word, boolean english) {
        lock.readLock().lock();
        try {
            if (handle == 0 || !userReady || word == null || word.isEmpty()) return false;
            return NativeEngine.learn(handle, reading == null ? "" : reading, word, english);
        } finally { lock.readLock().unlock(); }
    }

    void learnContext(String previous2, String previous, String word) {
        lock.readLock().lock();
        try {
            if (handle == 0 || !userReady) return;
            NativeEngine.learnContext(handle, previous2 == null ? "" : previous2, previous, word);
        } finally { lock.readLock().unlock(); }
    }

    /** Pins a word chosen from the candidate menu under its typed reading. */
    void addCustomWord(String code, String word, String usage) {
        boolean english = "en".equals(usage);
        lock.readLock().lock();
        try {
            if (handle == 0 || !userReady || word == null || word.trim().isEmpty()) return;
            String value = english ? word.trim().toLowerCase(Locale.ROOT) : word.trim();
            if (NativeEngine.learn(handle, code == null ? "" : code, value, english))
                NativeEngine.setPinned(handle, value, true);
        } finally { lock.readLock().unlock(); }
        persistLearnedWords();
    }

    /** Candidate menu actions: "pin", "delete", or anything else to unpin. */
    void adjustPreference(String input, String word, String action) {
        if (context == null) return;
        Map<String, CustomWordStore.Word> custom;
        try { custom = CustomWordStore.load(context); } catch (IllegalStateException unreadable) { return; }
        CustomWordStore.Word explicit = custom.get(word);
        if (explicit != null && !"delete".equals(action)) {
            CustomWordStore.Change changed = CustomWordStore.save(context, new CustomWordStore.Word(explicit.text,
                    explicit.readings, explicit.aliases, explicit.category, "pin".equals(action), explicit.createdAt));
            if (changed.succeeded()) reloadManagedWords();
            return;
        }
        lock.readLock().lock();
        try {
            if (handle == 0 || !userReady) return;
            if ("pin".equals(action)) {
                String reading = wordPronunciation(word);
                if (NativeEngine.learn(handle, reading.isEmpty() ? input : reading, word, false))
                    NativeEngine.setPinned(handle, word, true);
            } else if ("delete".equals(action)) {
                NativeEngine.forget(handle, word);
            } else {
                NativeEngine.setPinned(handle, word, false);
            }
        } finally { lock.readLock().unlock(); }
        persistLearnedWords();
    }

    /** Re-reads personal stores whose revision changed elsewhere (settings, word manager). */
    synchronized void reloadManagedWords() {
        if (context == null || settings == null) return;
        long words = settings.getLong("user_words_revision", 0);
        long contextData = settings.getLong("context_revision", 0);
        long custom = CustomWordStore.revision(context);
        lock.readLock().lock();
        try {
            if (handle == 0) return;
            if (words != wordsRevision || contextData != contextRevision) {
                byte[] wordBytes = words != wordsRevision ? readStoreBytes(WORDS_PREFS, WORDS_KEY) : null;
                byte[] contextBytes = contextData != contextRevision ? readStoreBytes(CONTEXT_PREFS, CONTEXT_KEY) : null;
                long[] revisions = NativeEngine.importUserBinary(handle, wordBytes, contextBytes);
                if (revisions == null || revisions.length != 2) return;
                if (wordBytes != null) savedWordsRevision = revisions[0];
                if (contextBytes != null) savedContextRevision = revisions[1];
                wordsRevision = words; contextRevision = contextData;
            }
            if (custom != customRevision) {
                NativeEngine.setCustomWords(handle, customRows());
                customRevision = custom;
            }
            userReady = true;
        } finally { lock.readLock().unlock(); }
    }

    private byte[] readStoreBytes(String prefs, String key) {
        SecureLocalStore.BytesReadResult result = SecureLocalStore.readBytesResult(context, prefs, key);
        if (result.succeeded()) {
            SecureLocalStore.markReadable(prefs);
            return result.value;
        }
        SecureLocalStore.markUnreadable(prefs);
        return new byte[0];
    }

    private String[] customRows() {
        Map<String, CustomWordStore.Word> words;
        try { words = CustomWordStore.load(context); }
        catch (IllegalStateException unreadable) { return new String[0]; }
        ArrayList<String> rows = new ArrayList<>(words.size() * 4);
        for (CustomWordStore.Word word : words.values()) {
            rows.add(word.text);
            rows.add(String.join(";", word.readings));
            rows.add(String.join(";", word.aliases));
            rows.add(word.pinned ? "1" : "0");
        }
        return rows.toArray(new String[0]);
    }

    /** Saves learned words and context when they changed; call on the personal-data IO thread. */
    synchronized void persistLearnedWords() {
        if (context == null || settings == null || cachedPrivacyMode) return;
        // Another screen rewrote a store: adopt its data instead of overwriting it.
        if (settings.getLong("user_words_revision", 0) != wordsRevision
                || settings.getLong("context_revision", 0) != contextRevision) {
            reloadManagedWords();
            if (settings.getLong("user_words_revision", 0) != wordsRevision
                    || settings.getLong("context_revision", 0) != contextRevision) return;
        }
        byte[] snapshot;
        lock.readLock().lock();
        try {
            if (handle == 0 || !userReady) return;
            snapshot = NativeEngine.snapshotUserBinary(handle, savedWordsRevision, savedContextRevision);
        } finally { lock.readLock().unlock(); }
        if (snapshot == null || snapshot.length < 24) return;
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        long wordVersion = buffer.getLong();
        long contextVersion = buffer.getLong();
        int wordsLen = buffer.getInt();
        byte[] wordsBytes = null;
        if (wordsLen > 0) {
            wordsBytes = new byte[wordsLen];
            buffer.get(wordsBytes);
        }
        int contextLen = buffer.getInt();
        byte[] contextBytes = null;
        if (contextLen > 0) {
            contextBytes = new byte[contextLen];
            buffer.get(contextBytes);
        }
        synchronized (PinyinDictionary.class) {
            // Settings can rewrite the word store while the native snapshot is being prepared.
            if (wordVersion != savedWordsRevision && settings.getLong("user_words_revision", 0) == wordsRevision
                    && SecureLocalStore.writeBytes(context, WORDS_PREFS, WORDS_KEY, wordsBytes == null ? new byte[0] : wordsBytes))
                savedWordsRevision = wordVersion;
        }
        if (contextVersion != savedContextRevision
                && SecureLocalStore.writeBytes(context, CONTEXT_PREFS, CONTEXT_KEY, contextBytes == null ? new byte[0] : contextBytes))
            savedContextRevision = contextVersion;
    }

    /** Called after settings cleared the stores; drops in-memory personal data. */
    synchronized void discardClearedUserData() {
        wordsRevision = contextRevision = customRevision = Long.MIN_VALUE;
        reloadManagedWords();
    }

    // ------------------------------------------------ stored words (activities)

    private static List<String[]> storedRows(Context context) {
        SecureLocalStore.BytesReadResult result = SecureLocalStore.readBytesResult(context, WORDS_PREFS, WORDS_KEY);
        if (!result.succeeded()) throw new IllegalStateException("本地数据读取失败，原数据已保留；请重新打开页面重试");
        if (result.value.length == 0) return new ArrayList<>();
        String text;
        if (result.value.length >= 6 && result.value[0] == 'K' && result.value[1] == 'X'
                && result.value[2] == 'U' && result.value[3] == 'S'
                && result.value[4] == 'R' && result.value[5] == '1') {
            text = NativeEngine.decodeWordsToText(result.value);
        } else {
            text = new String(result.value, StandardCharsets.UTF_8);
        }
        if (text == null) throw new IllegalStateException("本地词库格式无效，原数据已保留");
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] columns = line.split("\t", -1);
            if (columns.length == 6 && "W".equals(columns[0])) rows.add(columns);
        }
        return rows;
    }

    private static boolean writeRows(Context context, List<String[]> rows) {
        StringBuilder text = new StringBuilder();
        for (String[] row : rows) text.append(String.join("\t", row)).append('\n');
        if (!SecureLocalStore.write(context, WORDS_PREFS, WORDS_KEY, text.toString())) return false;
        bumpRevision(context);
        return true;
    }

    private static void bumpRevision(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        long revision = Math.max(System.currentTimeMillis(), prefs.getLong("user_words_revision", 0) + 1);
        prefs.edit().putLong("user_words_revision", revision).apply();
    }

    /** Rows of word, reading, label for the word manager. */
    static List<String[]> managedWords(Context context) {
        List<String[]> out = new ArrayList<>();
        for (String[] row : storedRows(context)) {
            boolean pinned = (parse(row[5]) & 1) != 0;
            out.add(new String[]{row[1], row[2].replace(' ', '\''), pinned ? "固定" : "学习"});
        }
        out.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        return out;
    }

    static synchronized boolean deleteManagedWord(Context context, String word) {
        List<String[]> rows = storedRows(context);
        rows.removeIf(row -> row[1].equals(word));
        return writeRows(context, rows);
    }

    static synchronized boolean renameManagedWord(Context context, String before, String after) {
        List<String[]> rows = storedRows(context);
        for (String[] row : rows) if (row[1].equals(before)) row[1] = after.replace('\t', ' ').replace('\n', ' ');
        return writeRows(context, rows);
    }

    static synchronized boolean clearStoredUserWords(Context context) {
        if (!SecureLocalStore.clear(context, WORDS_PREFS, WORDS_KEY)) return false;
        bumpRevision(context);
        return true;
    }
}
