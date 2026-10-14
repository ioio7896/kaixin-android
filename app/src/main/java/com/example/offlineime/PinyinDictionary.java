package com.example.offlineime;

import android.content.res.AssetManager;
import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Arrays;

/** 纯本地词库。词库文件来自本工程 lexicon 目录，加载和查询均不联网。 */
public final class PinyinDictionary {
    private static final String TAG = "OfflineImeLexicon";
    private static final int LEXICON_VERSION = 2026101101;
    private final ThreadLocal<Boolean> shuangpinLookup = new ThreadLocal<>();
    List<Entry> suggestShuangpin(String expanded) {
        shuangpinLookup.set(true);
        try { return suggest(expanded); }
        finally { shuangpinLookup.remove(); }
    }
    static final int TIER_USER = 0;
    static final int TIER_EXACT_FULL = 10;
    static final int TIER_EXACT_SHORT = 20;
    static final int TIER_MIXED = 25;
    static final int TIER_PREFIX = 30;
    static final int TIER_FUZZY = 40;
    static final int TIER_COMPOSED = 50;
    private static final int RANK_EXACT_MIXED = 15;
    private static final int RANK_EXACT_SHORT_AFTER_FULL = 25;
    private static final int MAX_PERSONAL_BOOST = 1_200;
    private static final int MAX_SKIP_PENALTY = 320;
    private static final int FIRST_SCREEN_TARGET = 15;
    private static final int COMPOSITION_TARGET = 8;
    private static final long PERSONAL_HALF_LIFE_MS = 21L * 24 * 60 * 60 * 1000;
    private static final long SKIP_HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000;
    public static final class Entry {
        public final String word;
        public final int score;
        public final int corpusScore;
        public final int matchTier;
        /** Query-specific ordering tier; matchTier remains the source used for learning. */
        public final int rankingTier;
        public final boolean pinned;
        public final int codePointLength;
        // Preserve each signal separately so corpus frequencies can be calibrated
        // without turning personal/context bonuses into fake corpus frequency.
        int personalBonus;
        int contextBonus;
        public int consumedLength;
        public String pronunciation = "";
        public String queryText = "";
        public String inputVersion = "";
        public List<String> wordSegments = Collections.emptyList();

        Entry withInput(int consumed, String reading) {
            Entry copy = new Entry(word, score, matchTier, pinned, corpusScore, rankingTier);
            copy.consumedLength = consumed; copy.pronunciation = reading == null ? "" : reading; copy.queryText = queryText;
            copy.wordSegments = wordSegments;
            copy.inputVersion = inputVersion;
            copy.personalBonus = personalBonus; copy.contextBonus = contextBonus;
            return copy;
        }
        Entry withScore(int value) {
            Entry copy = new Entry(word, value, matchTier, pinned, corpusScore, rankingTier).withInput(consumedLength, pronunciation);
            copy.personalBonus = personalBonus; copy.contextBonus = contextBonus;
            copy.queryText = queryText; copy.wordSegments = wordSegments; copy.inputVersion = inputVersion; return copy;
        }

        Entry withPersonalization(int personal, int contextual) {
            // Replacement keeps repeated ranking from accumulating old bonuses.
            long base = (long) score - personalBonus - contextBonus;
            Entry copy = withScore((int) Math.max(Integer.MIN_VALUE,
                    Math.min(Integer.MAX_VALUE, base + personal + contextual)));
            copy.personalBonus = personal; copy.contextBonus = contextual;
            return copy;
        }

        Entry(String word, int score) { this(word, score, TIER_EXACT_FULL, false, score); }

        Entry(String word, int score, int matchTier) {
            this(word, score, matchTier, false, score);
        }

        Entry(String word, int score, int matchTier, boolean pinned) {
            this(word, score, matchTier, pinned, score);
        }

        Entry(String word, int score, int matchTier, boolean pinned, int corpusScore) {
            this(word, score, matchTier, pinned, corpusScore, matchTier);
        }

        Entry(String word, int score, int matchTier, boolean pinned,
              int corpusScore, int rankingTier) {
            this.word = word;
            this.score = score;
            this.matchTier = matchTier;
            this.rankingTier = rankingTier;
            this.pinned = pinned;
            this.corpusScore = corpusScore;
            this.codePointLength = word == null ? 0 : word.codePointCount(0, word.length());
        }
    }

    private static final class UserWordRecord {
        final String code;
        final String word;
        final String usageKey;
        final int count;
        final long lastUsed;
        final boolean pinned;
        final int skipped;
        final long lastSkipped;
        final int preferredTier;

        UserWordRecord(String code, String word, String usageKey, int count, long lastUsed,
                       boolean pinned, int skipped, long lastSkipped, int preferredTier) {
            this.code = code;
            this.word = word;
            this.usageKey = usageKey;
            this.count = count;
            this.lastUsed = lastUsed;
            this.pinned = pinned;
            this.skipped = skipped;
            this.lastSkipped = lastSkipped;
            this.preferredTier = preferredTier;
        }
    }

    private static final class HotPhrase {
        final String code;
        final String digits;
        final String reading;
        final String initials;
        final int shortBoost;
        final Entry entry;

        HotPhrase(String reading, String word, int score, int shortBoost) {
            this.reading = reading;
            this.code = normalize(reading);
            this.digits = toT9(code);
            this.initials = toInitials(reading);
            this.shortBoost = shortBoost;
            this.entry = new Entry(word, score, TIER_PREFIX);
        }
    }

    private final AssetManager assets;
    private final Context context;
    private final PinyinDatabase database;
    private final Map<String, List<Entry>> index = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> shortIndex = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> mixedIndex = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> userIndex = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> t9Index = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> t9ShortIndex = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> t9MixedIndex = new ConcurrentHashMap<>();
    // Frozen before the large background corpus starts changing its indexes.
    private final Map<String, List<Entry>> bootstrapPinyinIndex;
    private final Map<String, List<Entry>> bootstrapShortIndex;
    private final Map<String, List<Entry>> bootstrapT9Index;
    private final Map<String, List<Entry>> bootstrapT9ShortIndex;
    private final Map<String, List<Entry>> userT9Index = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> englishUserIndex = new ConcurrentHashMap<>();
    private final Map<String, UserWordRecord> userWordRecords = new ConcurrentHashMap<>();
    private final Map<String, String> userReadings = new ConcurrentHashMap<>();
    private final Set<String> pinnedWords = ConcurrentHashMap.newKeySet();
    private volatile Map<String, List<UserWordRecord>> userSearchIndex = new ConcurrentHashMap<>();
    private volatile Map<String, CustomWordStore.Word> customWords = Collections.emptyMap();
    private volatile Map<String, List<CustomWordStore.Word>> customSearchIndex = Collections.emptyMap();
    private volatile UserContextModel sentenceContext;
    private final SentenceComposer.Decoder sentenceDecoder = new SentenceComposer.Decoder();
    void setSentenceContext(UserContextModel contextModel) { sentenceContext = contextModel; sentenceDecoder.clear(); }
    private String decoderKey(String kind, boolean numeric) {
        UserContextModel model = sentenceContext;
        return kind + ':' + numeric + ':' + settingsSignature() + ':' + managedRevision
                + ':' + (model == null ? 0 : model.scoringRevision());
    }
    private String combinedSignature() {
        UserContextModel model = sentenceContext;
        return settingsSignature() + ':' + managedRevision + ':' + (model == null ? 0 : model.scoringRevision());
    }
    private final Map<String, List<Entry>> englishIndex = new ConcurrentHashMap<>();
    private volatile Map<String, List<MixedTerm>> mixedTermPrefixes = Collections.emptyMap();
    private volatile Map<String, List<MixedTerm>> mixedTermT9Prefixes = Collections.emptyMap();
    private volatile Set<String> mixedTermWords = Collections.emptySet();
    private static final class MixedTerm {
        final String word, code;
        final int score;
        MixedTerm(String word, String code, int score) {
            this.word = word; this.code = code; this.score = score;
        }
    }
    private final List<HotPhrase> hotPhraseBuffer = new ArrayList<>();
    private volatile List<HotPhrase> hotPhrases = Collections.emptyList();
    // Independent buckets retain hot candidates even when general memory indices are trimmed.
    private volatile Map<String, List<HotPhrase>> shortHotIndex = Collections.emptyMap();
    private volatile Map<String, HotPhrase> shortHotReadings = Collections.emptyMap();
    private final Map<String, LinkedHashSet<String>> characterPronunciations = new HashMap<>();
    private Object hanLatinTransliterator;
    private int loadedEntryCount;
    private int loadErrorCount;
    private volatile PinyinLexer lexer;
    private final PinyinLexer canonicalLexer = new PinyinLexer(Collections.emptyList());
    private volatile boolean loaded;
    private volatile boolean databaseReady;
    private volatile SingleCharacterIndex singleCharacters = SingleCharacterIndex.EMPTY;
    private android.content.SharedPreferences settings;
    private volatile Map<String, ?> settingsValues = Collections.emptyMap();
    private volatile String settingsKey = "default";
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> cacheSettings();
    private static final class Recall {
        final String input;
        final List<Entry> entries;
        Recall(String input, List<Entry> entries) { this.input = input; this.entries = entries; }
    }
    private final ByteBudgetCache<Recall> queryCache = new ByteBudgetCache<>(8 * 1024 * 1024, 1024,
            value -> 48 + value.input.length() * 2 + ByteBudgetCache.entries(value.entries), 0);
    private List<Entry> recalled(String key) {
        Recall value = queryCache.get(key); return value == null ? null : value.entries;
    }
    private void cacheRecall(String key, String input, List<Entry> entries, long revision) {
        if (!QueryWork.wasLimited()) queryCache.putIfUnchanged(key, new Recall(input, entries), revision);
    }
    private final ByteBudgetCache<List<Entry>> corpusQueryCache = new ByteBudgetCache<>(8 * 1024 * 1024, 2048, ByteBudgetCache::entries, 2);
    // Corpus fragments have a separate budget so whole-input and correction
    // queries cannot evict the syllables reused while typing or backspacing.
    private final ByteBudgetCache<List<Entry>> compositionWordCache = new ByteBudgetCache<>(8 * 1024 * 1024, 2048, ByteBudgetCache::entries, 2);
    private final ByteBudgetCache<List<List<String>>> pinyinPathCache = new ByteBudgetCache<>(2 * 1024 * 1024, 1024, ByteBudgetCache::paths);

    public PinyinDictionary(AssetManager assets) { this(assets, null); }

    public PinyinDictionary(AssetManager assets, Context context) {
        this.assets = assets;
        this.context = context == null ? null : context.getApplicationContext();
        this.database = this.context == null ? null : new PinyinDatabase(this.context);
        if (this.context != null) {
            settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            cacheSettings();
            settings.registerOnSharedPreferenceChangeListener(settingsListener);
        }
        addCommonEntries();
        bootstrapPinyinIndex = snapshotIndex(index);
        bootstrapShortIndex = snapshotIndex(shortIndex);
        bootstrapT9Index = snapshotIndex(t9Index);
        bootstrapT9ShortIndex = snapshotIndex(t9ShortIndex);
        lexer = new PinyinLexer(index.keySet());
        // Keystore, user lexicon parsing and database installation run on the loader.
    }

    public boolean isLoaded() {
        return loaded;
    }

    private static Map<String, List<Entry>> snapshotIndex(Map<String, List<Entry>> source) {
        Map<String, List<Entry>> copy = new HashMap<>();
        for (Map.Entry<String, List<Entry>> item : source.entrySet()) {
            synchronized (item.getValue()) {
                copy.put(item.getKey(), Collections.unmodifiableList(new ArrayList<>(item.getValue())));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Selectable bundled common words while the full lexicon opens in the background. */
    List<Entry> suggestBootstrap(String raw, boolean numeric) {
        String query = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (!query.matches(numeric ? "[2-9]{1,8}" : "[a-z]{1,8}")) return Collections.emptyList();
        Map<String, List<Entry>> full = numeric ? bootstrapT9Index : bootstrapPinyinIndex;
        Map<String, List<Entry>> shortWords = numeric ? bootstrapT9ShortIndex : bootstrapShortIndex;
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        if (fullEnabled()) {
            mergeAll(result, full.get(query), TIER_EXACT_FULL);
            addPrefix(result, full, query, TIER_PREFIX);
        }
        if (shortEnabled()) {
            mergeAll(result, shortWords.get(query), TIER_EXACT_SHORT);
            addPrefix(result, shortWords, query, TIER_PREFIX);
        }
        List<Entry> ranked = CandidateRanker.rankTop(result.values(), numeric && query.length() == 1, 24, query);
        ArrayList<Entry> ready = new ArrayList<>(ranked.size());
        for (Entry entry : ranked) ready.add(entry.withInput(raw.length(), ""));
        return ready;
    }

    public void load() {
        if (loaded) return;
        boolean importRequired = database == null;
        boolean importing = false;
        loadedEntryCount = 0;
        loadErrorCount = 0;
        try {
            if (database != null) {
                database.prepareBundledDatabase(LEXICON_VERSION);
                importRequired = !database.isLexiconCurrent(LEXICON_VERSION);
                importing = importRequired;
                if (importing) database.beginLexiconImport();
            }
            // Core files are intentionally kept in memory for instant candidates.
            loadFileSafely("core/kaixin_explicit.txt", true, importing, 0, true);
            loadFileSafely("core/kaixin_polyphone.txt", true, importing, 0, true);
            loadFileSafely("core/kaixin_pronunciation_aliases.txt", true, importing, 0, true);
            try { loadMixedTerms(); }
            catch (IOException error) { Log.e(TAG, "failed to load mixed/common_terms.txt", error); }
            loadHotFileSafely("zh/life_common_3char_20000.txt", 1200);
            // Desktop coverage gets its own bounded cache; existing Android hot words remain.
            loadHotFileSafely("zh/life_common_3char.txt", 1200);
            loadHotFileSafely("zh/life_common_4char.txt", 1600);
            loadHotFileSafely("zh/life_common_phrases_5to8.txt", 400);
            loadHotFileSafely("zh/life_common_phrases.txt", 400);
            loadShortHotOverlay("zh/life_short_hot_2char.txt");
            loadShortHotOverlay("zh/life_short_hot_3to4char.txt");
            // Only a bounded selection stays in memory; all categories live in SQLite.
            loadHotFileSafely("zh/life_category_hot.txt", 1200);
            publishHotPhrases();

            if (importRequired) {
                String[] zhFiles = safeList("zh");
                for (String name : zhFiles) {
                    if (!"single_char_common_8105.txt".equals(name)) {
                        loadFileSafely("zh/" + name, database == null, importing, 0, true);
                    }
                }
                for (String name : safeList("zh-ext")) {
                    loadFileSafely("zh-ext/" + name, database == null, importing, 0, true);
                }
                // This source stores character + score; pronunciation is resolved
                // from every other bundled Chinese lexicon and Android ICU, never
                // treated as a pinyin code or silently truncated.
                loadSingleCharacterFileSafely("zh/single_char_common_8105.txt",
                        database == null, importing);
                loadFileSafely("en/kaixin_common_english.txt",
                        database == null, importing, 2, false);
            }

            if (importing && loadErrorCount == 0) {
                database.finishLexiconImport(LEXICON_VERSION, loadedEntryCount);
            } else if (importing) {
                database.abortLexiconImport();
            }
            databaseReady = database != null && database.isLexiconCurrent(LEXICON_VERSION);
            if (databaseReady) singleCharacters = database.loadCharacterIndex();
            sortAndTrimMemoryIndexes();
            lexer = new PinyinLexer(index.keySet());
            long userRevision = context == null ? 0 : context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getLong("user_words_revision", 0);
            loadUserEntries();
            managedRevision = userRevision;
            queryCache.clear();
            corpusQueryCache.clear();
            compositionWordCache.clear();
            pinyinPathCache.clear();
            Log.i(TAG, "lexicon ready: imported=" + importRequired
                    + " entries=" + loadedEntryCount + " errors=" + loadErrorCount);
        } catch (RuntimeException failure) {
            if (importing) database.abortLexiconImport();
            loadErrorCount++;
            Log.e(TAG, "lexicon import failed", failure);
        } finally {
            loaded = true;
        }
    }

    public void load(Runnable onFinished) {
        try { load(); } finally { if (onFinished != null) onFinished.run(); }
    }

    private void sortAndTrimMemoryIndexes() {
        for (List<Entry> entries : index.values()) {
            entries.sort(Comparator.comparingInt((Entry e) -> e.score).reversed());
            if (entries.size() > 16) entries.subList(16, entries.size()).clear();
        }
        for (List<Entry> entries : shortIndex.values()) {
            entries.sort(Comparator.comparingInt((Entry e) -> e.score).reversed());
            if (entries.size() > 16) entries.subList(16, entries.size()).clear();
        }
        for (List<Entry> entries : mixedIndex.values()) {
            entries.sort(Comparator.comparingInt((Entry e) -> e.score).reversed());
            if (entries.size() > 16) entries.subList(16, entries.size()).clear();
        }
        publishHotPhrases();
    }

    private void publishHotPhrases() {
        hotPhraseBuffer.sort(Comparator.comparingInt((HotPhrase phrase) -> phrase.entry.score).reversed());
        hotPhrases = Collections.unmodifiableList(new ArrayList<>(hotPhraseBuffer));
        Map<String, HotPhrase> readings = new HashMap<>();
        for (HotPhrase phrase : hotPhrases) {
            String key = phrase.entry.word + ':' + phrase.reading;
            HotPhrase prior = readings.get(key);
            if (prior == null || phrase.shortBoost > prior.shortBoost) readings.put(key, phrase);
        }
        Map<String, List<HotPhrase>> buckets = new HashMap<>();
        for (HotPhrase phrase : readings.values()) {
            buckets.computeIfAbsent("p:" + phrase.initials, ignored -> new ArrayList<>()).add(phrase);
            buckets.computeIfAbsent("9:" + toT9(phrase.initials), ignored -> new ArrayList<>()).add(phrase);
        }
        buckets.replaceAll((key, values) -> {
            values.sort(Comparator.comparingInt((HotPhrase phrase) -> phrase.entry.score + phrase.shortBoost)
                    .reversed().thenComparing(phrase -> phrase.entry.word));
            return Collections.unmodifiableList(values);
        });
        shortHotReadings = Collections.unmodifiableMap(readings);
        shortHotIndex = Collections.unmodifiableMap(buckets);
    }

    public int loadedEntryCount() { return loadedEntryCount; }
    public int loadErrorCount() { return loadErrorCount; }

    private void cacheSettings() {
        settingsValues = settings.getAll();
        StringBuilder key = new StringBuilder();
        for (String name : new String[]{"full_pinyin", "short_pinyin", "mixed_pinyin", "double_initials",
                "fuzzy_pinyin", "spelling_correction", "sentence_prediction"})
            key.append(name).append('=').append(settingsValues.get(name)).append(';');
        settingsKey = key.toString();
    }

    void close() {
        if (settings != null) settings.unregisterOnSharedPreferenceChangeListener(settingsListener);
    }
    void trimQueryCaches() {
        queryCache.clear(); corpusQueryCache.clear(); compositionWordCache.clear();
        pinyinPathCache.clear(); sentenceDecoder.clear();
    }

    /** Memory-only first paint; the service replaces it with a full DB result asynchronously. */
    public List<Entry> suggestFast(String raw) {
        String segmented = normalizeForSegmentation(raw);
        String query = normalize(segmented);
        if (query.isEmpty() || (!fullEnabled() && !shortEnabled())) return Collections.emptyList();
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        boolean abbreviation = isAbbreviationQuery(segmented);
        if (fullEnabled()) mergePinyinUserAll(result, userIndex.get(query), TIER_EXACT_FULL, abbreviation);
        if (fullEnabled()) mergePinyinAll(result, index.get(query), TIER_EXACT_FULL, abbreviation);
        if (shortEnabled()) mergePinyinAll(result, shortIndex.get(query), TIER_EXACT_SHORT, abbreviation);
        if (shortEnabled() && preference("mixed_pinyin", true)) mergePinyinAll(result, mixedIndex.get(query), TIER_MIXED, abbreviation);
        mergeShortHotMatches(result, segmented, false, null);
        if (fullEnabled()) { addHotPrefix(result, query, false); addPrefix(result, index, query, TIER_PREFIX); }
        if (shortEnabled()) addPrefix(result, shortIndex, query, TIER_PREFIX);
        if (segmented.indexOf('\'') >= 0) result.entrySet().removeIf(item -> {
            if (!item.getValue().pronunciation.isEmpty())
                return PinyinInput.match(segmented, item.getValue().pronunciation, fullEnabled(), shortEnabled(),
                        preference("mixed_pinyin", true), preference("double_initials", true), true) == null;
            for (List<String> path : canonicalLexer.splitAll(query, 16))
                if (path.size() == item.getValue().word.codePointCount(0, item.getValue().word.length())
                        && PinyinInput.match(segmented, String.join(" ", path), fullEnabled(), shortEnabled(),
                                preference("mixed_pinyin", true), preference("double_initials", true), true) != null) return false;
            return true;
        });
        return CandidateRanker.rankTop(result.values(), false, Integer.MAX_VALUE, segmented);
    }

    public List<Entry> suggestT9Fast(String raw) {
        String query = normalize(raw).replaceAll("[^2-9]", "");
        if (query.isEmpty() || (!fullEnabled() && !shortEnabled())) return Collections.emptyList();
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        mergeT9Users(result, query);
        if (fullEnabled()) { mergeAll(result, t9Index.get(query), TIER_EXACT_FULL); addHotPrefix(result, query, true); addPrefix(result, t9Index, query, TIER_PREFIX); }
        if (shortEnabled()) { mergeAll(result, t9ShortIndex.get(query), TIER_EXACT_SHORT); addPrefix(result, t9ShortIndex, query, TIER_PREFIX); }
        if (fullEnabled() && shortEnabled() && preference("mixed_pinyin", true)) {
            mergeAll(result, t9MixedIndex.get(query), TIER_MIXED); addPrefix(result, t9MixedIndex, query, TIER_PREFIX);
        }
        mergeShortHotMatches(result, query, true, null);
        return rankResults(result);
    }

    public List<Entry> suggestEnglishFast(String raw) {
        String query = normalize(raw).replaceAll("[^a-z]", "");
        if (query.isEmpty()) return Collections.emptyList();
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        mergeUserAll(result, englishUserIndex.get(query), TIER_EXACT_FULL);
        mergeAll(result, englishIndex.get(query), TIER_EXACT_FULL);
        addUserPrefix(result, englishUserIndex, query, TIER_PREFIX);
        addPrefix(result, englishIndex, query, TIER_PREFIX);
        return rankResults(result);
    }

    public List<Entry> suggest(String raw) {
        long recallRevision = queryCache.revision();
        QueryWork.check();
        String segmented = normalizeForSegmentation(raw);
        if (segmented.isEmpty() || segmented.length() > 192 || (!fullEnabled() && !shortEnabled())) return Collections.emptyList();
        if (database == null || !loaded || !databaseReady) return suggestFast(segmented);
        String cacheKey = "p:" + combinedSignature() + ':' + QueryWork.firstScreen() + ':' + segmented;
        List<Entry> cached = recalled(cacheKey);
        if (cached != null) return cached;
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        seedPreviousCandidates(result, "p:" + combinedSignature() + ':' + QueryWork.firstScreen() + ':', segmented, false);
        mergeAll(result, queryInputWords(segmented, true));
        boolean abbreviation = isAbbreviationQuery(segmented);
        for (UserWordRecord record : matchingUserRecords(segmented, false)) {
            if (record.code.startsWith("@") || record.preferredTier == TIER_FUZZY || "en".equals(record.usageKey) || customWords.containsKey(record.word)) continue;
            String reading = recordReading(record);
            PinyinInput.Match match = PinyinInput.match(segmented, reading, fullEnabled(), shortEnabled(),
                    preference("mixed_pinyin", true), preference("double_initials", true), false);
            if (match != null) merge(result, new Entry(record.word, 0, match.tier, record.pinned, 0,
                    record.pinned ? TIER_USER : rankingTier(match.tier, abbreviation)).withInput(segmented.length(), reading));
            else if (fullEnabled() && segmented.equals(record.code))
                merge(result, new Entry(record.word, 0, TIER_USER, record.pinned).withInput(segmented.length(), reading));
        }
        int exactCount = primaryCandidateCount(result.values());
        if (!QueryWork.firstScreen() && exactCount < FIRST_SCREEN_TARGET && (fuzzyEnabled()
                || segmented.length() <= 24 && preference("spelling_correction", true))) {
            for (String variant : fuzzyVariants(segmented)) {
                for (Entry entry : queryInputWords(variant, false)) {
                    if (entry.matchTier == TIER_PREFIX) continue;
                    merge(result, new Entry(entry.word, entry.score - 800, TIER_FUZZY, false, entry.corpusScore, 28)
                            .withInput(segmented.length(), entry.pronunciation));
                }
            }
        }
        mergeCustomWords(result, segmented, false, null);
        composeInput(result, segmented, false);
        // Offer complete leading words with their exact consumed span, leaving the suffix intact.
        if (!QueryWork.firstScreen() && result.size() < FIRST_SCREEN_TARGET) {
            java.util.TreeSet<Integer> boundaries = new java.util.TreeSet<>(Collections.reverseOrder());
            for (PinyinInput.Path path : PinyinInput.paths(segmented, fullEnabled(), shortEnabled(), preference("double_initials", true))) {
                int offset = 0;
                for (String token : path.tokens) {
                    if (offset < segmented.length() && segmented.charAt(offset) == '\'') offset++;
                    offset += token.length();
                    if (offset < segmented.length() && offset >= 2) boundaries.add(offset);
                }
                if (boundaries.size() >= 6) break;
            }
            int partialQueries = 0;
            for (int end : boundaries) {
                if (partialQueries++ >= 6 || result.size() >= FIRST_SCREEN_TARGET) break;
                String leading = segmented.substring(0, end).replaceAll("'+$", "");
                for (Entry entry : queryInputWords(leading, false)) {
                    Entry partial = new Entry(entry.word, entry.score, entry.matchTier, entry.pinned, entry.corpusScore, 35)
                            .withInput(end, entry.pronunciation);
                    if (!result.containsKey(partial.word)) result.put(partial.word, partial);
                }
            }
        }
        if (!QueryWork.firstScreen() && fullCandidateCount(result.values()) < COMPOSITION_TARGET)
            mergeCreationPrefixes(result, segmented, false, null);
        List<Entry> ranked = rankForRecall(result, segmented, false, false,
                QueryWork.firstScreen() ? 24 : 128);
        for (Entry entry : ranked) entry.queryText = segmented;
        cacheRecall(cacheKey, segmented, ranked, recallRevision);
        return ranked;
    }

    private List<Entry> queryInputWords(String input, boolean prefix) {
        QueryWork.check();
        String key = "word:" + fullEnabled() + shortEnabled() + preference("mixed_pinyin", true)
                + preference("double_initials", true) + ":" + prefix + ":" + QueryWork.firstScreen() + ":" + input;
        List<Entry> cached = corpusQueryCache.get(key);
        if (cached != null) return cached;
        LinkedHashMap<String, Entry> combined = new LinkedHashMap<>();
        List<Entry> characters = singleCharacters.lookup(input, input.matches("[2-9']+"), null, fullEnabled(), shortEnabled(),
                preference("double_initials", true), prefix, QueryWork.firstScreen() ? 32 : 128);
        for (Entry entry : characters) merge(combined, entry);
        List<Entry> found = QueryWork.firstScreen() && primaryCandidateCount(characters) >= 15
                ? Collections.emptyList() : corpusCandidates(input, input.matches("[2-9']+"), null, prefix);
        for (Entry entry : found) merge(combined, withShortHotBoost(entry));
        mergeShortHotMatches(combined, input, input.matches("[2-9']+"), null);
        List<Entry> ranked = CandidateRanker.rankTop(combined.values(), false, 128, input);
        if (!QueryWork.wasLimited()) corpusQueryCache.put(key, ranked);
        return ranked;
    }
    private List<Entry> corpusCandidates(String input, boolean numeric, PinyinPathEditor.Query constraint, boolean prefix) {
        String key = "corpus:" + numeric + ':' + fullEnabled() + ':' + shortEnabled() + ':'
                + preference("mixed_pinyin", true) + ':' + preference("double_initials", true) + ':'
                + QueryWork.firstScreen() + ':' + prefix + ':' + input + ':' + (constraint == null ? "" : constraint.key);
        List<Entry> cached = corpusQueryCache.get(key);
        if (cached != null) return cached;
        int limit = QueryWork.firstScreen() ? 24 : 64;
        List<Entry> found = constraint == null
                ? database.queryFlexible(input, limit, fullEnabled(), shortEnabled(), preference("mixed_pinyin", true),
                        preference("double_initials", true), prefix)
                : database.queryConstrained(constraint, limit, fullEnabled(), shortEnabled(), preference("mixed_pinyin", true),
                        preference("double_initials", true), prefix);
        QueryWork.check();
        List<Entry> result = Collections.unmodifiableList(found);
        if (!QueryWork.wasLimited()) corpusQueryCache.put(key, result); return result;
    }

    /** Corpus pruning must leave a bounded set of learned words for the service's
     * final personal/context ranking, even when their base frequency is low. */
    private List<Entry> rankForRecall(Map<String, Entry> values, String input, boolean numeric,
                                      boolean preferSingleCharacter, int limit) {
        List<Entry> ranked = CandidateRanker.rankTop(values.values(), preferSingleCharacter, limit, input);
        Set<String> included = new java.util.HashSet<>();
        for (Entry entry : ranked) included.add(entry.word);
        List<UserWordRecord> learned = new ArrayList<>(matchingUserRecords(input, numeric));
        learned.sort(Comparator.comparing((UserWordRecord record) -> !record.pinned)
                .thenComparing(Comparator.comparingInt((UserWordRecord record) -> record.count).reversed())
                .thenComparing(Comparator.comparingLong((UserWordRecord record) -> record.lastUsed).reversed()));
        int retained = 0;
        for (UserWordRecord record : learned) {
            QueryWork.check();
            Entry entry = values.get(record.word);
            if (entry == null || record.count <= 0 && !record.pinned || !included.add(record.word)) continue;
            ranked.add(entry);
            if (++retained >= 32) break;
        }
        return ranked;
    }

    /** Cached corpus edges only; context and personal scores are applied during
     * decoding/ranking, never stored here. Empty results are useful cache hits. */
    private List<Entry> compositionWords(String input, boolean numeric, boolean complete, boolean constrained) {
        QueryWork.check();
        boolean full = fullEnabled(), shortInput = shortEnabled();
        boolean mixed = preference("mixed_pinyin", true), doubles = preference("double_initials", true);
        String key = (constrained ? "locked:" : complete ? "full:" : "mixed:")
                + numeric + ':' + full + ':' + shortInput + ':' + mixed + ':' + doubles + ':' + input;
        List<Entry> cached = compositionWordCache.get(key);
        if (cached != null) return cached;
        List<Entry> found = complete ? database.queryFullSyllables(input, 32)
                : constrained ? database.queryConstrained(PinyinPathEditor.Query.forTokens(input, numeric),
                        32, full, shortInput, mixed, doubles, false)
                : queryInputWords(input, false);
        List<Entry> snapshots = new ArrayList<>(found.size());
        for (Entry entry : found) snapshots.add(entry.withInput(entry.consumedLength, entry.pronunciation));
        QueryWork.check();
        List<Entry> result = Collections.unmodifiableList(snapshots);
        compositionWordCache.put(key, result);
        return result;
    }

    /** Reuse the longest completed prefix as a bounded seed for the new query. */
    private void seedPreviousCandidates(Map<String, Entry> out, String cachePrefix,
                                        String input, boolean numeric) {
        if (input == null || input.length() < 2) return;
        int floor = Math.max(1, input.length() - 3);
        for (int end = input.length() - 1; end >= floor; end--) {
            String previous = input.substring(0, end).replaceAll("'+$", "");
            if (previous.isEmpty()) continue;
            List<Entry> cached = recalled(cachePrefix + previous);
            if (cached == null) continue;
            for (Entry entry : cached) {
                QueryWork.check();
                if (entry.pronunciation == null || entry.pronunciation.isEmpty()) continue;
                PinyinInput.Match match = PinyinInput.match(input, entry.pronunciation,
                        fullEnabled(), shortEnabled(), preference("mixed_pinyin", true),
                        preference("double_initials", true), true);
                if (match != null) {
                    merge(out, new Entry(entry.word, entry.corpusScore, match.tier,
                            entry.pinned, entry.corpusScore,
                            entry.pinned ? TIER_USER : match.tier)
                            .withInput(input.length(), entry.pronunciation));
                }
            }
            break;
        }
    }

    private static int primaryCandidateCount(Iterable<Entry> entries) {
        int count = 0;
        for (Entry entry : entries) if (entry.matchTier <= TIER_MIXED) count++;
        return count;
    }

    private static int fullCandidateCount(Iterable<Entry> entries) {
        int count = 0;
        for (Entry entry : entries) if (entry.matchTier == TIER_EXACT_FULL) count++;
        return count;
    }

    /** Full spelling has its own fallback budget: unrelated abbreviations must
     * not suppress sentence decoding, and letter/digit length is not word length. */
    private void composeInput(Map<String, Entry> result, String input, boolean numeric) {
        if (QueryWork.firstScreen() || !sentenceEnabled()) return;
        boolean full = fullEnabled(), shortInput = shortEnabled();
        boolean doubles = preference("double_initials", true);
        List<PinyinInput.Path> selected = new ArrayList<>();
        int limit = input.length() > 24 ? 2 : 6;
        if (full && fullCandidateCount(result.values()) < COMPOSITION_TARGET) {
            List<PinyinInput.Path> complete = new ArrayList<>();
            List<PinyinInput.Path> parsed = numeric ? PinyinInput.numericPaths(input, true, false, false)
                    : PinyinInput.paths(input, true, false, false);
            for (PinyinInput.Path path : parsed) if (path.cost == 0) complete.add(path);
            selected.addAll(PinyinInput.compositionPaths(complete, limit));
        }
        if (selected.isEmpty() && primaryCandidateCount(result.values()) < COMPOSITION_TARGET) {
            List<PinyinInput.Path> parsed = numeric ? PinyinInput.numericPaths(input, full, shortInput, doubles)
                    : PinyinInput.paths(input, full, shortInput, doubles);
            selected.addAll(PinyinInput.compositionPaths(parsed, 2));
        }
        Map<String, List<Entry>> words = new HashMap<>();
        Map<String, Integer> transitions = new HashMap<>();
        for (PinyinInput.Path path : selected) {
            boolean complete = full && path.cost == 0;
            List<Entry> composed = SentenceComposer.composeWords(path.tokens,
                    code -> words.computeIfAbsent((complete ? "full:" : "mixed:") + code,
                            key -> compositionWords(code, numeric, complete, false)), input.length(),
                    (previous, next) -> transitions.computeIfAbsent(previous + ':' + next,
                            key -> database.transitionBonus(previous, next)
                                    + (sentenceContext == null ? 0 : sentenceContext.transitionBonus(previous, next))),
                    sentenceDecoder, decoderKey(complete ? "full" : "mixed", numeric));
            for (Entry entry : composed) merge(result, rankComposed(entry, input));
        }
    }

    private Entry rankComposed(Entry entry, String input) {
        PinyinInput.Match match = PinyinInput.match(input, entry.pronunciation,
                fullEnabled(), false, false, false, false);
        if (match == null || match.tier != TIER_EXACT_FULL) return entry;
        // Keep the composed source tag for learning, while placing a complete full
        // spelling before abbreviated matches and unfinished phrase completions.
        Entry ranked = new Entry(entry.word, entry.score, entry.matchTier, entry.pinned,
                entry.corpusScore, RANK_EXACT_MIXED).withInput(entry.consumedLength, entry.pronunciation);
        ranked.wordSegments = entry.wordSegments;
        return ranked;
    }

    private List<UserWordRecord> matchingUserRecords(String input, boolean numeric) {
        String normalized = numeric
                ? normalize(input).replaceAll("[^2-9]", "")
                : normalizeStoredCode(input).replace("'", "");
        if (normalized.isEmpty()) return Collections.emptyList();
        List<UserWordRecord> records = userSearchIndex.get((numeric ? "9:" : "p:") + normalized);
        return records == null ? Collections.emptyList() : records;
    }

    String wordPronunciation(String word) {
        // Input lifecycle and preference menus must never wait for a database import/upgrade lock.
        if (!databaseReady || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return "";
        return database.pronunciation(word);
    }

    private String recordReading(UserWordRecord record) {
        String key = record.code + ':' + record.word;
        String cached = userReadings.get(key); if (cached != null) return cached;
        for (List<String> path : canonicalLexer.splitAll(record.code, 16))
            if (path.size() == record.word.codePointCount(0, record.word.length())) {
                String reading = String.join(" ", path); userReadings.put(key, reading); return reading;
            }
        String reading = wordPronunciation(record.word);
        if (reading.isEmpty()) return record.code;
        userReadings.put(key, reading); return reading;
    }

    /** Returns bounded syllable paths for displaying and selecting segmentation choices. */
    public List<List<String>> paths(String raw) {
        String query = normalizeForSegmentation(raw);
        if (query.isEmpty()) return Collections.emptyList();
        String cacheKey = "p:" + query;
        List<List<String>> cached = pinyinPathCache.get(cacheKey);
        if (cached != null) return cached;
        PinyinLexer current = canonicalLexer;
        if (current == null) return Collections.emptyList();
        List<List<String>> values = current.splitAll(query, 8);
        if (values.isEmpty()) {
            values = new ArrayList<>();
            for (PinyinInput.Path path : PinyinInput.paths(query, fullEnabled(), shortEnabled(), preference("double_initials", true))) {
                values.add(path.tokens); if (values.size() >= 8) break;
            }
        }
        return cachePinyinPaths(cacheKey, values);
    }

    /** Most likely legal pinyin paths represented by a T9 digit prefix. */
    public List<List<String>> t9Paths(String digits) {
        QueryWork.check();
        String query = normalize(digits).replaceAll("[^2-9]", "");
        if (query.isEmpty() || database == null || !loaded || !databaseReady) return Collections.emptyList();
        String cacheKey = "t:" + query;
        List<List<String>> cached = pinyinPathCache.get(cacheKey);
        if (cached != null) return cached;
        ArrayList<List<String>> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String reading : database.queryT9Readings(query, 32)) {
            QueryWork.check();
            List<String> path = Arrays.asList(reading.split("[ ']+"));
            boolean valid = true;
            for (String syllable : path) if (!PinyinPathEditor.isCompleteReading(syllable)) { valid = false; break; }
            if (!valid) continue;
            String key = String.join("'", path);
            if (seen.add(key)) result.add(path);
        }
        List<List<String>> grammar = PinyinPathEditor.fullNinePaths(query, 32);
        for (List<String> path : grammar) {
            String key = String.join("'", path);
            if (seen.add(key)) result.add(path);
        }
        if (result.isEmpty()) result.addAll(PinyinPathEditor.memoryNinePaths(query));
        // Prefer natural paths with fewer syllables, retaining database score
        // order among equally segmented alternatives.
        android.content.SharedPreferences usage = context == null ? null
                : context.getSharedPreferences("pinyin_path_usage", Context.MODE_PRIVATE);
        result.sort(Comparator
                .comparingDouble((List<String> path) -> usage == null ? 0
                        : -decayedPathUsage(usage, String.join("·", path)))
                .thenComparingInt(List::size));
        // Keep independent grammar splits alongside the strongest corpus paths. A busy
        // corpus prefix or shorter segmentation must not hide all alternate boundaries.
        List<List<String>> limited = new ArrayList<>(result.subList(0, Math.min(16, result.size())));
        for (List<String> path : grammar) if (!limited.contains(path)) limited.add(path);
        QueryWork.check();
        return cachePinyinPaths(cacheKey, limited);
    }

    private List<List<String>> cachePinyinPaths(String key, List<List<String>> values) {
        ArrayList<List<String>> copied = new ArrayList<>(values.size());
        for (List<String> path : values) {
            boolean valid = !path.isEmpty();
            for (String reading : path)
                if (!PinyinPathEditor.isEnteredReading(reading)) { valid = false; break; }
            if (valid) copied.add(Collections.unmodifiableList(new ArrayList<>(path)));
        }
        List<List<String>> cached = Collections.unmodifiableList(copied);
        pinyinPathCache.put(key, cached);
        return cached;
    }

    public void clearPinyinPathCache() { pinyinPathCache.clear(); }

    /** UI reads only cached corpus paths or bounded in-memory spelling paths. */
    List<List<String>> cachedT9Paths(String digits) {
        List<List<String>> corpus = pinyinPathCache.get("t:" + digits.replace("'", ""));
        if (corpus != null && !corpus.isEmpty()) return corpus;
        String key = "tm:" + digits;
        List<List<String>> memory = pinyinPathCache.get(key);
        return memory == null ? cachePinyinPaths(key, PinyinPathEditor.memoryNinePaths(digits)) : memory;
    }

    /** Include legal incomplete prefixes, displaying only letters already entered. */
    List<String> syllableChoices(String raw, boolean numeric) {
        return raw == null ? new ArrayList<>() : PinyinPathEditor.enteredReadings(raw, numeric, "");
    }

    List<Entry> suggestConstrained(PinyinPathEditor.Query query) {
        long recallRevision = queryCache.revision();
        QueryWork.check();
        if (database == null || !loaded || !databaseReady || query.raw.isEmpty()) return Collections.emptyList();
        String cacheKey = "constraint:" + combinedSignature() + ':' + QueryWork.firstScreen() + ':' + query.numeric + ':' + query.raw + ':' + query.key;
        List<Entry> cached = recalled(cacheKey);
        if (cached != null) return cached;
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        boolean full = fullEnabled(), shortInput = shortEnabled();
        boolean mixed = preference("mixed_pinyin", true), doubles = preference("double_initials", true);
        boolean abbreviation = !query.numeric && isAbbreviationQuery(query.raw);
        List<Entry> characters = singleCharacters.lookup(query.raw, query.numeric, query, full, shortInput, doubles, true, QueryWork.firstScreen() ? 32 : 128);
        mergeAll(result, characters);
        for (Entry entry : QueryWork.firstScreen() && primaryCandidateCount(characters) >= 15
                ? Collections.<Entry>emptyList() : corpusCandidates(query.raw, query.numeric, query, true))
            merge(result, withShortHotBoost(entry));
        mergeShortHotMatches(result, query.databaseInput, query.numeric, query);
        for (UserWordRecord record : matchingUserRecords(query.raw, query.numeric)) {
            String reading = recordReading(record);
            PinyinInput.Match match = query.match(reading, full, shortInput, mixed, doubles, true);
            if (match != null) merge(result, new Entry(record.word, 0, match.tier, record.pinned, 0,
                    record.pinned ? TIER_USER : rankingTier(match.tier, abbreviation))
                    .withInput(query.raw.length(), reading));
        }
        mergeCustomWords(result, query.raw, query.numeric, query);
        if (!QueryWork.firstScreen() && fullCandidateCount(result.values()) < COMPOSITION_TARGET && sentenceEnabled()) {
            Map<String, List<Entry>> words = new HashMap<>();
            Map<String, Integer> transitions = new HashMap<>();
            for (List<String> tokens : query.tokenPaths(full, shortInput, doubles)) {
                if (tokens.size() < 2) continue;
                List<Entry> composed = SentenceComposer.composeWords(tokens,
                        code -> words.computeIfAbsent(code,
                                key -> compositionWords(key, query.numeric, false, true)), query.raw.length(),
                        (previous, next) -> transitions.computeIfAbsent(previous + ':' + next,
                                key -> database.transitionBonus(previous, next)
                                        + (sentenceContext == null ? 0 : sentenceContext.transitionBonus(previous, next))),
                        sentenceDecoder, decoderKey("locked", query.numeric));
                for (Entry entry : composed)
                    if (query.match(entry.pronunciation, full, shortInput, mixed, doubles, false) != null)
                        merge(result, rankComposed(entry, query.raw));
            }
        }
        if (!QueryWork.firstScreen() && fullCandidateCount(result.values()) < COMPOSITION_TARGET)
            mergeCreationPrefixes(result, query.raw, query.numeric, query);
        List<Entry> ranked = rankForRecall(result, query.raw, query.numeric, false,
                QueryWork.firstScreen() ? 24 : 128);
        for (Entry entry : ranked) entry.queryText = query.raw;
        cacheRecall(cacheKey, query.raw, ranked, recallRevision);
        return ranked;
    }


    private boolean isPinnedForInput(String code, Entry entry) {
        if (customWords.containsKey(entry.word)) return entry.pinned;
        for (UserWordRecord record : matchingUserRecords(code, code.matches("[2-9]+"))) {
            if (!record.word.equals(entry.word) || !record.pinned) continue;
            String reading = recordReading(record);
            if (!entry.pronunciation.isEmpty() && !normalizeStoredCode(entry.pronunciation).equals(normalizeStoredCode(reading))) continue;
            return true;
        }
        return false;
    }

    private void reloadCustomWords() {
        if (context == null) return;
        try {
            Map<String, CustomWordStore.Word> words = CustomWordStore.load(context);
            Map<String, LinkedHashSet<CustomWordStore.Word>> buckets = new HashMap<>();
            for (CustomWordStore.Word word : words.values()) {
                for (String reading : word.readings) {
                    String first = reading.substring(0, 1);
                    buckets.computeIfAbsent("p:" + first, k -> new LinkedHashSet<>()).add(word);
                    buckets.computeIfAbsent("9:" + toT9(first), k -> new LinkedHashSet<>()).add(word);
                }
                for (String alias : word.aliases) {
                    if (alias.matches("[a-z]+")) buckets.computeIfAbsent("p:" + alias.charAt(0), k -> new LinkedHashSet<>()).add(word);
                    String digits = alias.matches("[2-9]+") ? alias : toT9(alias);
                    buckets.computeIfAbsent("9:" + digits.charAt(0), k -> new LinkedHashSet<>()).add(word);
                }
            }
            Map<String, List<CustomWordStore.Word>> index = new HashMap<>();
            for (Map.Entry<String, LinkedHashSet<CustomWordStore.Word>> bucket : buckets.entrySet())
                index.put(bucket.getKey(), Collections.unmodifiableList(new ArrayList<>(bucket.getValue())));
            customWords = words; customSearchIndex = Collections.unmodifiableMap(index);
        } catch (IllegalStateException unavailable) { Log.e(TAG, "custom lexicon unavailable; preserving file"); }
    }

    private void mergeCustomWords(Map<String, Entry> out, String raw, boolean numeric, PinyinPathEditor.Query constraint) {
        String input = numeric ? raw.replace("\'", "") : normalizeStoredCode(raw);
        if (input.isEmpty()) return;
        List<CustomWordStore.Word> values = customSearchIndex.get((numeric ? "9:" : "p:") + input.charAt(0));
        if (values == null) return;
        for (CustomWordStore.Word word : values) {
            QueryWork.check();
            for (String pronunciation : word.readings) {
                String reading = pronunciation.replace('\'', ' ');
                PinyinInput.Match match = constraint == null
                        ? PinyinInput.match(input, reading, true, true, true, true, true)
                        : constraint.match(reading, true, true, true, true, true);
                if (match == null) continue;
                // Explicit words are available immediately; only a requested pin forces top ranking.
                int score = match.tier == TIER_EXACT_FULL ? 8500 : 800;
                boolean pinned = word.pinned && match.tier != TIER_PREFIX;
                merge(out, new Entry(word.text, score, match.tier, pinned, score, pinned ? TIER_USER : match.tier)
                        .withInput(raw.length(), reading));
            }
            if (constraint == null) for (String alias : word.aliases) {
                String code = numeric ? alias.matches("[2-9]+") ? alias : toT9(alias) : alias;
                if (!input.replace("'", "").equals(code)) continue;
                String reading = word.readings.isEmpty() ? "" : word.readings.get(0).replace('\'', ' ');
                merge(out, new Entry(word.text, 8500, TIER_EXACT_SHORT, word.pinned, 8500,
                        word.pinned ? TIER_USER : TIER_EXACT_SHORT).withInput(raw.length(), reading));
            }
        }
    }

    private void mergeCreationPrefixes(Map<String, Entry> out, String raw, boolean numeric,
            PinyinPathEditor.Query constraint) {
        for (Entry entry : creationCandidates(raw, numeric, constraint, null)) {
            if (entry.consumedLength <= 0 || entry.consumedLength >= raw.length()) continue;
            // Whole-word matches retain their usual priority; a leading word can be selected separately.
            Entry leading = new Entry(entry.word, entry.score, entry.matchTier, entry.pinned,
                    entry.corpusScore, Math.max(35, entry.rankingTier))
                    .withInput(entry.consumedLength, entry.pronunciation);
            merge(out, leading);
        }
    }

    List<Entry> creationCandidates(String raw, boolean numeric, PinyinPathEditor.Query constraint, List<ShuangpinInput.Path> doubles) {
        return creationCandidates(raw, numeric, constraint, doubles, false);
    }
    List<Entry> creationCandidates(String raw, boolean numeric, PinyinPathEditor.Query constraint,
            List<ShuangpinInput.Path> doubles, boolean memoryOnly) {
        QueryWork.check();
        LinkedHashMap<String, Entry> out = new LinkedHashMap<>();
        if (database == null || !databaseReady || raw.isEmpty()) return new ArrayList<>();
        if (doubles != null) {
            for (ShuangpinInput.Path path : doubles) {
                for (int count = 1; count <= Math.min(QueryWork.firstScreen() ? 2 : 4, path.readings.size()); count++) {
                    String spelling = String.join("'", path.readings.subList(0, count));
                    for (Entry entry : creationPrefixWords(spelling, false, null, memoryOnly))
                        merge(out, entry.withInput(path.ends.get(count - 1), entry.pronunciation));
                    mergeCreationDoubleCustom(out, spelling, path.ends.get(count - 1));
                }
            }
        } else {
            PinyinPathEditor editor = new PinyinPathEditor();
            if (constraint != null) for (PinyinPathEditor.Part part : constraint.parts)
                if (part.locked) editor.select(raw, numeric, part.start, part.end, part.text);
            List<PinyinPathEditor.Segment> parts = editor.segments(raw, numeric,
                    numeric ? this::cachedT9Paths : this::paths);
            for (int index = 0; index < Math.min(QueryWork.firstScreen() ? 2 : 4, parts.size()); index++) {
                int end = parts.get(index).end;
                String leading = raw.substring(0, end);
                List<PinyinPathEditor.Part> locks = new ArrayList<>();
                if (constraint != null) for (PinyinPathEditor.Part part : constraint.parts) {
                    if (part.start >= end) break;
                    int stop = Math.min(part.end, end);
                    locks.add(new PinyinPathEditor.Part(part.start, stop,
                            part.locked ? part.text : raw.substring(part.start, stop), part.locked, part.prefix));
                }
                PinyinPathEditor.Query prefix = locks.isEmpty() ? null : new PinyinPathEditor.Query(leading, numeric, locks);
                for (Entry entry : creationPrefixWords(leading, numeric, prefix, memoryOnly)) merge(out, entry.withInput(end, entry.pronunciation));
                mergeCustomWords(out, leading, numeric, prefix);
            }
        }
        // Keep enough low-frequency characters for the expanded page; no sentence generation here.
        List<Entry> result = CandidateRanker.rankTop(out.values(), true, QueryWork.firstScreen() ? 24 : 512);
        for (Entry entry : result) entry.queryText = raw;
        return result;
    }
    private List<Entry> creationPrefixWords(String raw, boolean numeric, PinyinPathEditor.Query constraint, boolean memoryOnly) {
        List<Entry> result = new ArrayList<>(singleCharacters.lookup(raw, numeric, constraint, true, true, true,
                constraint != null && constraint.leadingIsPrefix(), QueryWork.firstScreen() ? 64 : 512));
        if (!memoryOnly) {
            String key = "creation:" + numeric + ':' + QueryWork.firstScreen() + ':' + raw + ':' + (constraint == null ? "" : constraint.key);
            List<Entry> phrases = corpusQueryCache.get(key);
            if (phrases == null) {
                phrases = Collections.unmodifiableList(database.creationPhrases(raw, numeric, constraint));
                QueryWork.check(); if (!QueryWork.wasLimited()) corpusQueryCache.put(key, phrases);
            }
            result.addAll(phrases);
        }
        return result;
    }
    private void mergeCreationDoubleCustom(Map<String, Entry> out, String spelling, int consumed) {
        Map<String, Entry> saved = new LinkedHashMap<>();
        mergeCustomWords(saved, spelling, false, null);
        for (Entry entry : saved.values()) merge(out, entry.withInput(consumed, entry.pronunciation));
    }

    List<String> wordReadings(String word) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        CustomWordStore.Word saved = customWords.get(word);
        if (saved != null) result.addAll(saved.readings);
        if (databaseReady) result.addAll(database.readings(word));
        return new ArrayList<>(result);
    }

    private static double decayedPathUsage(android.content.SharedPreferences usage, String key) {
        int count = usage.getInt(key, 0);
        if (count <= 0) return 0;
        long lastUsed = usage.getLong("time_" + key, 0);
        if (lastUsed <= 0) return count * 0.5;
        long age = Math.max(0L, System.currentTimeMillis() - lastUsed);
        return count * Math.pow(0.5, age / (double) PERSONAL_HALF_LIFE_MS);
    }

    /** 真正的 9 键候选：按拼音字母的 T9 映射反查本地词库。 */
    public List<Entry> suggestT9(String digits) {
        long recallRevision = queryCache.revision();
        QueryWork.check();
        String query = normalize(digits).replaceAll("[^2-9]", "");
        if (query.isEmpty() || (!fullEnabled() && !shortEnabled())) return Collections.emptyList();
        String cacheKey = "t:" + combinedSignature() + ':' + QueryWork.firstScreen() + ":" + query;
        List<Entry> cached = recalled(cacheKey);
        if (cached != null) return cached;
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        seedPreviousCandidates(result, "t:" + combinedSignature() + ':' + QueryWork.firstScreen() + ':', query, true);
        boolean full = fullEnabled();
        boolean shortPinyin = shortEnabled();
        mergeT9Users(result, query);
        if (full) addHotPrefix(result, query, true);
        if (full) {
            mergeAll(result, t9Index.get(query), TIER_EXACT_FULL);
            addPrefix(result, t9Index, query, TIER_PREFIX);
        }
        if (shortPinyin) {
            mergeAll(result, t9ShortIndex.get(query), TIER_EXACT_SHORT);
            addPrefix(result, t9ShortIndex, query, TIER_PREFIX);
            if (full && preference("mixed_pinyin", true)) {
                mergeAll(result, t9MixedIndex.get(query), TIER_MIXED);
                addPrefix(result, t9MixedIndex, query, TIER_PREFIX);
            }
        }
        if (database != null && loaded && databaseReady) {
            List<Entry> characters = singleCharacters.lookup(query, true, null, full, shortPinyin,
                    preference("double_initials", true), true, QueryWork.firstScreen() ? 32 : 128);
            mergeAll(result, characters);
            for (Entry entry : QueryWork.firstScreen() && primaryCandidateCount(characters) >= 15
                    ? Collections.<Entry>emptyList() : corpusCandidates(query, true, null, true))
                merge(result, withShortHotBoost(entry));
            composeInput(result, query, true);
        }
        mergeShortHotMatches(result, query, true, null);
        mergeCustomWords(result, query, true, null);
        if (!QueryWork.firstScreen() && fullCandidateCount(result.values()) < COMPOSITION_TARGET)
            mergeCreationPrefixes(result, query, true, null);
        List<Entry> ranked = rankForRecall(result, query, true, query.length() == 1,
                QueryWork.firstScreen() ? 24 : 128);
        for (Entry entry : ranked) entry.queryText = query;
        cacheRecall(cacheKey, query, ranked, recallRevision);
        return ranked;
    }

    /** Small indexed lookup used when space is pressed before the background result arrives. */
    List<Entry> suggestForCommit(String raw, boolean nineDigits) {
        return nineDigits ? suggestT9(raw) : suggest(raw);
    }

    /** English completion candidates sourced from lexicon/en. */
    public List<Entry> suggestEnglish(String raw) {
        long recallRevision = queryCache.revision();
        QueryWork.check();
        String query = normalize(raw).replaceAll("[^a-z]", "");
        if (query.isEmpty()) return Collections.emptyList();
        List<Entry> cached = recalled("e:" + query);
        if (cached != null) return cached;
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        mergeUserAll(result, englishUserIndex.get(query), TIER_EXACT_FULL);
        mergeAll(result, englishIndex.get(query), TIER_EXACT_FULL);
        addUserPrefix(result, englishUserIndex, query, TIER_PREFIX);
        addPrefix(result, englishIndex, query, TIER_PREFIX);
        if (database != null && loaded && databaseReady) mergeAll(result, database.queryEnglish(query, 64));
        List<Entry> ranked = rankResults(result);
        cacheRecall("e:" + query, query, ranked, recallRevision);
        return ranked;
    }

    /** Curated literal English and Chinese-English words for Chinese layouts. */
    List<Entry> suggestMixedTerms(String raw, boolean numeric) {
        String query = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (!query.matches(numeric ? "[2-9]+" : "[a-z]+")) return Collections.emptyList();
        List<MixedTerm> matches = (numeric ? mixedTermT9Prefixes : mixedTermPrefixes).get(query);
        if (matches == null) return Collections.emptyList();
        ArrayList<Entry> result = new ArrayList<>(Math.min(matches.size(), 16));
        for (MixedTerm term : matches) {
            String code = numeric ? toT9(term.code) : term.code;
            boolean exact = code.equals(query);
            if (numeric && !exact) continue; // T9 prefixes are too ambiguous.
            int tier = exact ? TIER_MIXED : TIER_PREFIX;
            result.add(new Entry(term.word, exact ? term.score : term.score / 4, tier)
                    .withInput(raw.length(), ""));
            if (result.size() >= 16) break;
        }
        return result;
    }

    boolean isMixedTerm(String word) { return mixedTermWords.contains(word); }

    private void loadMixedTerms() throws IOException {
        Map<String, List<MixedTerm>> letterPrefixes = new HashMap<>();
        Map<String, List<MixedTerm>> digitPrefixes = new HashMap<>();
        Set<String> words = new LinkedHashSet<>();
        try (InputStream input = assets.open("mixed/common_terms.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\\t");
                if (columns.length != 3) continue;
                String word = columns[0].trim();
                String code = columns[1].trim().toLowerCase(Locale.ROOT);
                if (word.isEmpty() || !code.matches("[a-z]+")) continue;
                int score;
                try { score = Integer.parseInt(columns[2].trim()); }
                catch (NumberFormatException ignored) { continue; }
                MixedTerm term = new MixedTerm(word, code, Math.max(1, score));
                words.add(word);
                String digits = toT9(code);
                for (int length = 1; length <= code.length(); length++)
                    letterPrefixes.computeIfAbsent(code.substring(0, length), key -> new ArrayList<>()).add(term);
                for (int length = 1; length <= digits.length(); length++)
                    digitPrefixes.computeIfAbsent(digits.substring(0, length), key -> new ArrayList<>()).add(term);
            }
        }
        Comparator<MixedTerm> byScore = Comparator.comparingInt((MixedTerm term) -> term.score).reversed();
        for (List<MixedTerm> values : letterPrefixes.values()) values.sort(byScore);
        for (List<MixedTerm> values : digitPrefixes.values()) values.sort(byScore);
        mixedTermPrefixes = letterPrefixes;
        mixedTermT9Prefixes = digitPrefixes;
        mixedTermWords = words;
    }

    /** Populate a small hot set only after the keyboard has been idle. */
    void prewarmCommonQueries() {
        if (!loaded || database == null || !databaseReady) return;
        String[] common = {"ni", "wo", "shi", "de", "hao"};
        LinkedHashSet<String> digits = new LinkedHashSet<>();
        for (String query : common) {
            QueryWork.check();
            suggest(query);
            digits.add(toT9(query));
        }
        for (String query : digits) {
            QueryWork.check();
            suggestT9(query);
        }
    }

    /** Shared ordering by match quality, calibrated frequency and local preferences. */
    private List<Entry> rankResults(Map<String, Entry> values) {
        return CandidateRanker.rank(values.values());
    }

    private void addPrefix(Map<String, Entry> out, Map<String, List<Entry>> source,
                           String prefix, int tier) {
        if (out.size() >= 64) return;
        for (Map.Entry<String, List<Entry>> item : source.entrySet()) {
            if (!item.getKey().equals(prefix) && item.getKey().startsWith(prefix)) {
                mergeAll(out, item.getValue(), tier);
                if (out.size() >= 64) return;
            }
        }
    }

    private void addUserPrefix(Map<String, Entry> out, Map<String, List<Entry>> source,
                               String prefix, int tier) {
        if (out.size() >= 64) return;
        for (Map.Entry<String, List<Entry>> item : source.entrySet()) {
            if (item.getKey().startsWith(prefix)) {
                mergeUserAll(out, item.getValue(), tier);
                if (out.size() >= 64) return;
            }
        }
    }

    private void addHotPrefix(Map<String, Entry> out, String query, boolean nineDigits) {
        int added = 0;
        for (HotPhrase phrase : hotPhrases) {
            String code = nineDigits ? phrase.digits : phrase.code;
            if (code.length() > query.length() && code.startsWith(query)) {
                merge(out, phrase.entry);
                if (++added >= 16) break;
            }
        }
    }

    private Entry withShortHotBoost(Entry entry) {
        if (entry.matchTier != TIER_EXACT_SHORT && entry.matchTier != TIER_MIXED) return entry;
        HotPhrase hot = shortHotReadings.get(entry.word + ':' + entry.pronunciation);
        return hot == null ? entry : entry.withScore(entry.corpusScore + hot.shortBoost);
    }

    /** Fetch complete hot readings independently of the general candidate limits. */
    private void mergeShortHotMatches(Map<String, Entry> out, String input, boolean numeric,
                                      PinyinPathEditor.Query constraint) {
        if (!shortEnabled() || input.length() > 192) return;
        boolean full = fullEnabled(), mixed = preference("mixed_pinyin", true);
        boolean doubles = preference("double_initials", true);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (constraint != null) keys.addAll(constraint.initialKeys(full, true, doubles));
        else {
            // Short-only paths also preserve zh/ch/sh alternatives when full paths fill the parser budget.
            List<PinyinInput.Path> shortPaths = numeric ? PinyinInput.numericPaths(input, false, true, doubles)
                    : PinyinInput.paths(input, false, true, doubles);
            for (PinyinInput.Path path : shortPaths) keys.add(path.initials);
            List<PinyinInput.Path> paths = numeric ? PinyinInput.numericPaths(input, full, true, doubles)
                    : PinyinInput.paths(input, full, true, doubles);
            for (PinyinInput.Path path : paths) keys.add(path.initials);
        }
        boolean abbreviation = !numeric && isAbbreviationQuery(input);
        Set<HotPhrase> seen = new LinkedHashSet<>();
        for (String key : keys) {
            List<HotPhrase> bucket = shortHotIndex.get((numeric ? "9:" : "p:") + key);
            if (bucket == null) continue;
            for (HotPhrase phrase : bucket) {
                QueryWork.check();
                if (!seen.add(phrase)) continue;
                PinyinInput.Match match = constraint == null
                        ? PinyinInput.match(input, phrase.reading, full, true, mixed, doubles, false)
                        : constraint.match(phrase.reading, full, true, mixed, doubles, false);
                if (match == null) continue;
                int boost = match.tier == TIER_EXACT_SHORT || match.tier == TIER_MIXED ? phrase.shortBoost : 0;
                merge(out, new Entry(phrase.entry.word, phrase.entry.score + boost, match.tier,
                        false, phrase.entry.score, rankingTier(match.tier, abbreviation))
                        .withInput(constraint == null ? input.length() : constraint.raw.length(), phrase.reading));
            }
        }
    }

    /** Bounded, memoized phrase composition for 3–8 character Chinese words. */
    private List<Entry> composeSentence(String query) {
        PinyinLexer currentLexer = lexer;
        if (currentLexer == null) return Collections.emptyList();
        List<List<String>> paths = currentLexer.splitAll(query, 8);
        if (paths.isEmpty()) return Collections.emptyList();

        Map<String, List<Entry>> segmentCache = new HashMap<>();
        LinkedHashMap<String, Entry> merged = new LinkedHashMap<>();
        for (List<String> path : paths) {
            boolean valid = true;
            for (String syllable : path) {
                if (!segmentCache.containsKey(syllable))
                    segmentCache.put(syllable, exactCandidates(syllable, 6));
                if (segmentCache.get(syllable).isEmpty()) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;
            for (Entry entry : SentenceComposer.compose(path, segmentCache, 24, 24)) {
                merge(merged, entry);
            }
        }
        List<Entry> ranked = rankResults(merged);
        return ranked.size() <= 24 ? ranked : new ArrayList<>(ranked.subList(0, 24));
    }

    private List<Entry> exactCandidates(String code, int limit) {
        LinkedHashMap<String, Entry> values = new LinkedHashMap<>();
        mergeUserAll(values, userIndex.get(code), TIER_COMPOSED);
        mergeAll(values, index.get(code), TIER_COMPOSED);
        if (database != null && loaded && databaseReady) {
            mergeAll(values, database.queryExactPinyin(code, limit));
        }
        List<Entry> ranked = rankResults(values);
        return ranked.size() <= limit ? ranked : new ArrayList<>(ranked.subList(0, limit));
    }
    private void mergeAll(Map<String, Entry> out, List<Entry> values) {
        if (values == null) return;
        synchronized (values) { for (Entry entry : values) merge(out, entry); }
    }

    private void mergeAll(Map<String, Entry> out, List<Entry> values, int tier) {
        if (values == null) return;
        synchronized (values) {
            for (Entry entry : values) merge(out, new Entry(entry.word, entry.score, tier));
        }
    }

    private void mergeUserAll(Map<String, Entry> out, List<Entry> values, int tier) {
        if (values == null) return;
        synchronized (values) {
            for (Entry entry : values) {
                int candidateTier = entry.pinned ? TIER_USER : Math.max(tier, entry.matchTier);
                merge(out, new Entry(entry.word, entry.score, candidateTier,
                        entry.pinned, entry.corpusScore));
            }
        }
    }

    private void mergePinyinAll(Map<String, Entry> out, List<Entry> values,
                                int tier, boolean abbreviation) {
        if (values == null) return;
        synchronized (values) {
            for (Entry entry : values) {
                int rankingTier = entry.pinned ? TIER_USER : rankingTier(tier, abbreviation);
                merge(out, new Entry(entry.word, entry.score, tier, entry.pinned,
                        entry.corpusScore, rankingTier));
            }
        }
    }

    private void mergePinyinAll(Map<String, Entry> out, List<Entry> values,
                                boolean abbreviation) {
        if (values == null) return;
        synchronized (values) {
            for (Entry entry : values) {
                int rankingTier = entry.pinned ? TIER_USER
                        : rankingTier(entry.matchTier, abbreviation);
                merge(out, new Entry(entry.word, entry.score, entry.matchTier, entry.pinned,
                        entry.corpusScore, rankingTier));
            }
        }
    }

    private void mergePinyinUserAll(Map<String, Entry> out, List<Entry> values,
                                    int tier, boolean abbreviation) {
        if (values == null) return;
        synchronized (values) {
            for (Entry entry : values) {
                int candidateTier = entry.pinned ? TIER_USER : Math.max(tier, entry.matchTier);
                int rankingTier = entry.pinned ? TIER_USER
                        : rankingTier(candidateTier, abbreviation);
                merge(out, new Entry(entry.word, entry.score, candidateTier,
                        entry.pinned, entry.corpusScore, rankingTier));
            }
        }
    }

    static int rankingTier(int matchTier, boolean abbreviation) {
        if (abbreviation) {
            if (matchTier == TIER_EXACT_SHORT) return TIER_EXACT_FULL;
            if (matchTier == TIER_MIXED) return RANK_EXACT_MIXED;
            if (matchTier == TIER_EXACT_FULL) return TIER_EXACT_SHORT;
        } else {
            if (matchTier == TIER_EXACT_FULL) return TIER_EXACT_FULL;
            if (matchTier == TIER_MIXED) return TIER_EXACT_SHORT;
            if (matchTier == TIER_EXACT_SHORT) return RANK_EXACT_SHORT_AFTER_FULL;
        }
        return matchTier;
    }

    private boolean isAbbreviationQuery(String segmented) {
        return PinyinInput.isAbbreviationQuery(segmented, fullEnabled());
    }

    private void merge(Map<String, Entry> out, Entry candidate) {
        QueryWork.check();
        Entry previous = out.get(candidate.word);
        if (previous == null || candidate.rankingTier < previous.rankingTier
                || (candidate.rankingTier == previous.rankingTier
                && (candidate.matchTier < previous.matchTier
                || (candidate.matchTier == previous.matchTier && candidate.score > previous.score)))) {
            out.put(candidate.word, candidate);
        }
    }

    /** Persist accepted candidates separately for each layout and syllable path. */
    public void learn(String code, String word) {
        learnSelection(code, word, "*", null, TIER_EXACT_FULL);
    }

    static final class Selection {
        final String code, word, usage, skipped;
        final int tier;
        Selection(String code, String word, String usage, String skipped, int tier) {
            this.code = code; this.word = word; this.usage = usage; this.skipped = skipped; this.tier = tier;
        }
    }
    private volatile long userMutation;

    void learnSelection(String code, String word, String usageKey, String skippedWord, int sourceTier) {
        long revision = context == null ? 0 : context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getLong("user_words_revision", 0);
        learnSelections(Collections.singletonList(new Selection(code, word, usageKey, skippedWord, sourceTier)), revision);
    }

    LearningChange learnSelections(List<Selection> selections, long expectedStoreRevision) {
        LearningChange change = new LearningChange();
        Map<String, UserWordRecord> snapshot;
        long mutation, revision;
        synchronized (this) {
            reloadManagedWords();
            if (context == null || expectedStoreRevision != context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getLong("user_words_revision", 0)) return change;
            for (Selection selection : selections)
                applySelection(selection.code, selection.word, selection.usage, selection.skipped, selection.tier, change);
            if (change.words.isEmpty()) return change;
            mutation = ++userMutation;
            revision = managedRevision;
            snapshot = new LinkedHashMap<>(userWordRecords);
            queryCache.removeIf((key, value) -> change.affects(value.input, value.entries));
            sentenceDecoder.clear();
        }
        // Serialization, Keystore and both durable commits run without the dictionary monitor.
        // The store lock plus revision check prevent a late snapshot from undoing a clear/edit.
        synchronized (PinyinDictionary.class) {
            if (mutation == userMutation && revision == context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getLong("user_words_revision", 0)) {
                if (!writeUserWords(context, snapshot)) Log.w(TAG, "learning persistence failed");
            }
        }
        return change;
    }

    private void applySelection(String code, String word, String usageKey,
                        String skippedWord, int sourceTier, LearningChange change) {
        code = normalizeStoredCode(code);
        word = word == null ? "" : word.replace('\t', ' ').replace('\n', ' ').trim();
        usageKey = normalizeUsageKey(usageKey);
        if ("en".equals(usageKey)) {
            word = word.toLowerCase(Locale.ROOT);
            if (skippedWord != null) skippedWord = skippedWord.toLowerCase(Locale.ROOT);
        }
        if (skippedWord != null) skippedWord = skippedWord.replace('\t', ' ').replace('\n', ' ').trim();
        if (code.isEmpty() || word.isEmpty() || context == null) return;
        long now = System.currentTimeMillis();
        String key = userRecordKey(code, word, usageKey);
        UserWordRecord old = userWordRecords.get(key);
        UserWordRecord updated = new UserWordRecord(code, word, usageKey,
                Math.min(1000, old == null ? 1 : old.count + 1), now,
                old != null && old.pinned, old == null ? 0 : old.skipped,
                old == null ? 0 : old.lastSkipped,
                old != null && old.pinned ? TIER_USER : normalizeUserTier(sourceTier));
        userWordRecords.put(key, updated);
        if (sourceTier != TIER_FUZZY) addUser(updated);
        updateUserSearchRecord(updated, old, change);
        change.words.add(word);
        change.reading(code);
        if (!"en".equals(usageKey)) {
            String sharedKey = userRecordKey("@word", word, "*");
            UserWordRecord shared = userWordRecords.get(sharedKey);
            userWordRecords.put(sharedKey, new UserWordRecord("@word", word, "*",
                    Math.min(1000, shared == null ? 1 : shared.count + 1), now, false, 0, 0, TIER_EXACT_FULL));
        }

        if (skippedWord != null && !skippedWord.isEmpty() && !skippedWord.equals(word)) {
            String skippedKey = userRecordKey(code, skippedWord, usageKey);
            UserWordRecord previous = userWordRecords.get(skippedKey);
            UserWordRecord rejected = new UserWordRecord(code, skippedWord, usageKey,
                    previous == null ? 0 : previous.count,
                    previous == null ? 0 : previous.lastUsed,
                    previous != null && previous.pinned,
                    Math.min(1000, previous == null ? 1 : previous.skipped + 1), now,
                    previous == null ? normalizeUserTier(sourceTier) : previous.preferredTier);
            userWordRecords.put(skippedKey, rejected);
            addUser(rejected);
            updateUserSearchRecord(rejected, previous, change);
            change.words.add(skippedWord);
        }
    }

    void addCustomWord(String code, String word) {
        addCustomWord(code, word, "*");
    }

    synchronized void addCustomWord(String code, String word, String usageKey) {
        userMutation++;
        code = normalizeStoredCode(code);
        word = word == null ? "" : word.replace('\t', ' ').replace('\n', ' ').trim();
        usageKey = normalizeUsageKey(usageKey);
        if ("en".equals(usageKey)) word = word.toLowerCase(Locale.ROOT);
        if (context == null || code.isEmpty() || word.isEmpty()) return;
        UserWordRecord existing = userWordRecords.get(userRecordKey(code, word, usageKey));
        UserWordRecord pinned = new UserWordRecord(code, word, usageKey,
                existing == null ? 0 : existing.count,
                existing == null ? System.currentTimeMillis() : existing.lastUsed,
                true, existing == null ? 0 : existing.skipped,
                existing == null ? 0 : existing.lastSkipped, TIER_USER);
        userWordRecords.put(userRecordKey(code, word, usageKey), pinned);
        addUser(pinned);
        rebuildUserSearchIndex();
        writeUserWords(context, userWordRecords);
        queryCache.clear();
    }

    List<Entry> personalize(String code, String usageKey, List<Entry> source,
                           boolean preferSingleCharacter) {
        return personalize(code, usageKey, source, preferSingleCharacter, Collections.emptyMap());
    }
    List<Entry> personalize(String code, String usageKey, List<Entry> source,
                           boolean preferSingleCharacter, Map<String, Integer> contextBonuses) {
        if (source == null || source.isEmpty() || userWordRecords.isEmpty() && contextBonuses.isEmpty()) return source;
        String normalizedCode = normalize(code);
        String normalizedUsageKey = normalizeUsageKey(usageKey);
        long now = System.currentTimeMillis();
        ArrayList<Entry> result = new ArrayList<>(source.size());
        for (Entry entry : source) {
            QueryWork.check();
            int personal = personalBoost(entry.pronunciation.isEmpty() ? normalizedCode : normalizeStoredCode(entry.pronunciation), entry.word, normalizedUsageKey, now);
            int contextual = contextBonuses.getOrDefault(entry.word, 0);
            boolean pinned = entry.pinned || isPinnedForInput(normalizedCode, entry);
            Entry copy = pinned ? new Entry(entry.word, entry.score - entry.personalBonus - entry.contextBonus,
                    entry.matchTier, true, entry.corpusScore, TIER_USER)
                    .withInput(entry.consumedLength, entry.pronunciation) : entry;
            copy = copy.withPersonalization(personal, contextual);
            copy.queryText = entry.queryText; copy.wordSegments = entry.wordSegments; result.add(copy);
        }
        return result;
    }

    private int personalBoost(String code, String word, String usageKey, long now) {
        UserWordRecord record = userWordRecords.get(userRecordKey(code, word, usageKey));
        double legacyScale = 1.0;
        if (record == null && !"*".equals(usageKey)) {
            record = userWordRecords.get(userRecordKey(code, word, "*"));
            legacyScale = 0.5;
        }
        if (record == null) { record = userWordRecords.get(userRecordKey("@word", word, "*")); legacyScale = 0.4; }
        if (record == null || record.pinned) return 0;
        double countStrength = Math.min(1.0,
                Math.log1p(record.count) / Math.log1p(30.0));
        double age = Math.max(0L, now - record.lastUsed);
        double recency = Math.pow(0.5, age / (double) PERSONAL_HALF_LIFE_MS);
        int positive = (int) Math.round(MAX_PERSONAL_BOOST * countStrength * recency * legacyScale);

        double skipStrength = Math.min(1.0,
                Math.log1p(record.skipped) / Math.log1p(8.0));
        double skipAge = Math.max(0L, now - record.lastSkipped);
        double skipRecency = Math.pow(0.5, skipAge / (double) SKIP_HALF_LIFE_MS);
        int negative = (int) Math.round(MAX_SKIP_PENALTY * skipStrength * skipRecency);
        UserWordRecord global = userWordRecords.get(userRecordKey("@word", word, "*"));
        if (global != null && global != record && global.skipped > 0) negative += Math.min(MAX_SKIP_PENALTY, global.skipped * 60);
        return positive - negative;
    }

    synchronized void adjustPreference(String input, String word, String action) {
        userMutation++;
        if (context == null) return;
        CustomWordStore.Word explicit = customWords.get(word);
        if (explicit != null && !"delete".equals(action)) {
            CustomWordStore.Change changed = CustomWordStore.save(context, new CustomWordStore.Word(explicit.text,
                    explicit.readings, explicit.aliases, explicit.category, "pin".equals(action), explicit.createdAt));
            if (changed.succeeded()) { reloadCustomWords(); queryCache.clear(); }
            return;
        }
        if ("pin".equals(action)) {
            String reading = wordPronunciation(word);
            addCustomWord(reading.isEmpty() ? input : reading, word);
        } else if ("delete".equals(action)) {
            userWordRecords.values().removeIf(record -> record.word.equals(word));
            userIndex.clear(); userT9Index.clear(); englishUserIndex.clear();
            for (UserWordRecord record : userWordRecords.values()) if (!record.code.startsWith("@") && record.preferredTier != TIER_FUZZY) addUser(record);
            writeUserWords(context, userWordRecords);
        } else {
            userWordRecords.replaceAll((key, record) -> record.word.equals(word) && record.pinned
                    ? new UserWordRecord(record.code, record.word, record.usageKey, record.count, record.lastUsed,
                            false, record.skipped, record.lastSkipped, TIER_EXACT_FULL) : record);
            userIndex.clear(); userT9Index.clear(); englishUserIndex.clear();
            for (UserWordRecord record : userWordRecords.values()) if (!record.code.startsWith("@")) addUser(record);
            String key = userRecordKey("@word", word, "*");
            UserWordRecord prior = userWordRecords.get(key);
            userWordRecords.put(key, new UserWordRecord("@word", word, "*", 0, 0, false,
                    prior == null ? 4 : prior.skipped + 4, System.currentTimeMillis(), TIER_EXACT_FULL));
            writeUserWords(context, userWordRecords);
        }
        rebuildUserSearchIndex();
        queryCache.clear();
    }

    static List<String[]> managedWords(Context context) {
        List<String[]> out = new ArrayList<>();
        for (UserWordRecord record : readUserWords(context).values())
            if (!record.code.startsWith("@")) out.add(new String[]{record.word, record.code, record.pinned ? "固定" : "学习"});
        out.sort(Comparator.comparing((String[] row) -> row[0]).thenComparing(row -> row[1]));
        return out;
    }

    static synchronized boolean deleteManagedWord(Context context, String word) {
        Map<String, UserWordRecord> records = readUserWords(context);
        records.values().removeIf(record -> record.word.equals(word));
        if (!writeUserWords(context, records)) return false;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putLong("user_words_revision", System.currentTimeMillis()).apply();
        return true;
    }

    static synchronized boolean renameManagedWord(Context context, String before, String after) {
        Map<String, UserWordRecord> records = readUserWords(context), renamed = new LinkedHashMap<>();
        for (UserWordRecord record : records.values()) {
            UserWordRecord value = record.word.equals(before) ? new UserWordRecord(record.code, after, record.usageKey,
                    record.count, record.lastUsed, record.pinned, record.skipped, record.lastSkipped, record.preferredTier) : record;
            renamed.put(userRecordKey(value.code, value.word, value.usageKey), value);
        }
        if (!writeUserWords(context, renamed)) return false;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putLong("user_words_revision", System.currentTimeMillis()).apply();
        return true;
    }

    private volatile long managedRevision = -1;
    boolean isUserDataReady() {
        return loaded && (context == null || managedRevision == context
                .getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("user_words_revision", 0));
    }
    synchronized void reloadManagedWords() {
        if (context == null || !loaded) return;
        long revision = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("user_words_revision", 0);
        if (revision == managedRevision) return;
        userMutation++;
        userIndex.clear(); userT9Index.clear(); englishUserIndex.clear(); userWordRecords.clear(); userReadings.clear();
        loadUserEntries(); queryCache.clear();
        // A canceled rebuild must be retried by the next candidate query.
        managedRevision = revision;
    }

    static synchronized boolean clearStoredUserWords(Context context) {
        if (!SecureLocalStore.clear(context, "user_words", "offline_ime_words")) return false;
        android.content.SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        long revision = Math.max(System.currentTimeMillis(), prefs.getLong("user_words_revision", 0) + 1);
        prefs.edit().putLong("user_words_revision", revision).apply();
        return true;
    }

    public synchronized void clearUserWords() {
        userMutation++;
        synchronized (PinyinDictionary.class) {
            if (context != null) clearStoredUserWords(context);
        }
        userWordRecords.clear();
        customWords = Collections.emptyMap(); customSearchIndex = Collections.emptyMap();
        userIndex.clear();
        userT9Index.clear();
        englishUserIndex.clear();
        rebuildUserSearchIndex();
        queryCache.clear();
        pinyinPathCache.clear();
    }

    public static void saveCustomWord(Context context, String code, String word) {
        // Compatibility entry point; new editors display errors and keep aliases separate.
        CustomWordStore.Word value;
        try { value = CustomWordStore.validate(word, code, "", "普通词", false, 0); }
        catch (IllegalArgumentException alias) { value = CustomWordStore.validate(word, "", code, "普通词", false, 0); }
        CustomWordStore.Change change = CustomWordStore.save(context, value);
        if (!change.succeeded()) throw new IllegalStateException(change.error);
    }

    private void loadUserEntries() {
        if (context == null) return;
        reloadCustomWords();
        userWordRecords.putAll(readUserWords(context));
        for (UserWordRecord record : userWordRecords.values()) if (!record.code.startsWith("@") && record.preferredTier != TIER_FUZZY) addUser(record);
        rebuildUserSearchIndex();
    }

    private static Map<String, UserWordRecord> readUserWords(Context c) {
        Map<String, UserWordRecord> out = new LinkedHashMap<>();
        boolean migrateLegacy = false;
        try {
            String text = SecureLocalStore.read(c, "user_words", "offline_ime_words");
            long now = System.currentTimeMillis();
            for (String line : text.split("\\n")) {
                String[] fields = line.split("\\t", -1);
                if (fields.length == 3) {
                    migrateLegacy = true;
                    String code = normalizeStoredCode(fields[0]);
                    String word = fields[1].trim();
                    int count = Integer.parseInt(fields[2]);
                    UserWordRecord legacy = new UserWordRecord(code, word, "*", count,
                            Math.max(0L, now - PERSONAL_HALF_LIFE_MS), false, 0, 0,
                            TIER_EXACT_FULL);
                    out.put(userRecordKey(code, word, "*"), legacy);
                } else if (fields.length >= 9) {
                    String code = normalizeStoredCode(fields[0]);
                    String word = fields[1].trim();
                    String usageKey = normalizeUsageKey(fields[2]);
                    UserWordRecord record = new UserWordRecord(code, word, usageKey,
                            Math.max(0, Integer.parseInt(fields[3])),
                            Math.max(0L, Long.parseLong(fields[4])),
                            Boolean.parseBoolean(fields[5]),
                            Math.max(0, Integer.parseInt(fields[6])),
                            Math.max(0L, Long.parseLong(fields[7])),
                            normalizeUserTier(Integer.parseInt(fields[8])));
                    out.put(userRecordKey(code, word, usageKey), record);
                }
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "user lexicon parse failed: " + error.getClass().getSimpleName());
            migrateLegacy = false;
        }
        if (migrateLegacy) writeUserWords(c, out);
        return out;
    }

    private static synchronized boolean writeUserWords(Context c, Map<String, UserWordRecord> words) {
        StringBuilder text = new StringBuilder();
        for (UserWordRecord record : words.values()) {
            text.append(record.code).append('\t').append(record.word).append('\t')
                    .append(record.usageKey).append('\t').append(record.count).append('\t')
                    .append(record.lastUsed).append('\t').append(record.pinned).append('\t')
                    .append(record.skipped).append('\t').append(record.lastSkipped).append('\t')
                    .append(record.preferredTier).append('\n');
        }
        return SecureLocalStore.write(c, "user_words", "offline_ime_words", text.toString());
    }

    private void addUser(UserWordRecord record) {
        if (customWords.containsKey(record.word)) return;
        int tier = record.pinned ? TIER_USER : normalizeUserTier(record.preferredTier);
        if ("en".equals(record.usageKey)) {
            upsert(englishUserIndex, record.code, record.word, 0, tier, record.pinned);
            return;
        }
        upsert(userIndex, normalize(record.code), record.word, 0, tier, record.pinned);
        String digits = record.code.matches("[2-9]+") ? record.code : toT9(normalize(record.code));
        if (!digits.isEmpty()) upsert(userT9Index, digits, record.word, 0, tier, record.pinned);
    }

    private void rebuildUserSearchIndex() {
        Map<String, LinkedHashMap<String, UserWordRecord>> staged = new HashMap<>();
        LinkedHashSet<String> pinned = new LinkedHashSet<>();
        for (UserWordRecord record : userWordRecords.values()) {
            QueryWork.check();
            if (record.pinned) pinned.add(record.word);
            indexSearchRecord(staged, record);
        }
        Map<String, List<UserWordRecord>> published = new HashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, UserWordRecord>> item : staged.entrySet())
            published.put(item.getKey(), Collections.unmodifiableList(new ArrayList<>(item.getValue().values())));
        userSearchIndex = new ConcurrentHashMap<>(published);
        pinnedWords.clear();
        pinnedWords.addAll(pinned);
    }

    private void indexSearchRecord(Map<String, LinkedHashMap<String, UserWordRecord>> staged, UserWordRecord record) {
        if (record.code.startsWith("@") || "en".equals(record.usageKey)
                || record.preferredTier == TIER_FUZZY || customWords.containsKey(record.word)) return;
        String reading = recordReading(record).toLowerCase(Locale.ROOT);
        String full = reading.replaceAll("[^a-z]", "");
        if (full.isEmpty()) full = normalizeStoredCode(record.code).replace("'", "");
        StringBuilder initials = new StringBuilder();
        for (String syllable : reading.split("[ ']+"))
            if (!syllable.isEmpty() && syllable.charAt(0) >= 'a' && syllable.charAt(0) <= 'z')
                initials.append(syllable.charAt(0));
        indexUserPrefixes(staged, "p:", full, record);
        indexUserPrefixes(staged, "p:", initials.toString(), record);
        indexUserPrefixes(staged, "9:", toT9(full), record);
        indexUserPrefixes(staged, "9:", toT9(initials.toString()), record);
        // A learned/custom pronunciation must support the same arbitrary
        // full-pinyin + initials mixing as the bundled lexicon. Keep these
        // aliases as exact keys so lookups stay indexed without multiplying
        // every alias into another prefix tree.
        for (String alias : userMixedInputCodes(reading)) {
            indexUserExact(staged, "p:" + alias, record);
            indexUserExact(staged, "9:" + toT9(alias), record);
        }
    }

    private void updateUserSearchRecord(UserWordRecord record, UserWordRecord previous, LearningChange change) {
        if ("en".equals(record.usageKey)) { change.reading(record.code); return; }
        Map<String, LinkedHashMap<String, UserWordRecord>> changed = new HashMap<>();
        Map<String, LinkedHashMap<String, UserWordRecord>> before = new HashMap<>();
        indexSearchRecord(changed, record);
        if (previous != null) indexSearchRecord(before, previous);
        Set<String> keys = new LinkedHashSet<>(before.keySet()); keys.addAll(changed.keySet());
        String recordKey = userRecordKey(record.code, record.word, record.usageKey);
        for (String key : keys) {
            change.inputKeys.add(key.substring(2));
            List<UserWordRecord> prior = userSearchIndex.get(key);
            List<UserWordRecord> next = prior == null ? new ArrayList<>() : new ArrayList<>(prior);
            next.removeIf(value -> userRecordKey(value.code, value.word, value.usageKey).equals(recordKey));
            if (changed.containsKey(key)) next.add(record);
            if (next.isEmpty()) userSearchIndex.remove(key);
            else userSearchIndex.put(key, Collections.unmodifiableList(next));
        }
        if (record.pinned) pinnedWords.add(record.word);
        change.reading(recordReading(record));
    }

    private static void indexUserPrefixes(
            Map<String, LinkedHashMap<String, UserWordRecord>> target,
            String namespace, String value, UserWordRecord record) {
        if (value == null || value.isEmpty()) return;
        String recordKey = userRecordKey(record.code, record.word, record.usageKey);
        for (int length = 1; length <= value.length(); length++) {
            target.computeIfAbsent(namespace + value.substring(0, length), ignored -> new LinkedHashMap<>())
                    .put(recordKey, record);
        }
    }

    private static void indexUserExact(
            Map<String, LinkedHashMap<String, UserWordRecord>> target,
            String key, UserWordRecord record) {
        if (key == null || key.endsWith(":")) return;
        target.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                .put(userRecordKey(record.code, record.word, record.usageKey), record);
    }

    /** All full/initial combinations for a bounded custom pronunciation. */
    static List<String> userMixedInputCodes(String pronunciation) {
        if (pronunciation == null) return Collections.emptyList();
        String[] syllables = pronunciation.toLowerCase(Locale.ROOT).trim().split("[ '\\t]+");
        if (syllables.length < 2 || syllables.length > PinyinInput.MAX_SYLLABLES) return Collections.emptyList();
        List<String> codes = new ArrayList<>(); codes.add("");
        // Bound memory for long phrases; never enumerate 2^32 combinations.
        for (String syllable : syllables) {
            if (!syllable.matches("[a-z]+")) return Collections.emptyList();
            List<String> next = new ArrayList<>();
            for (String prefix : codes) {
                next.add(prefix + syllable);
                if (next.size() < 256) next.add(prefix + syllable.charAt(0));
                if (next.size() >= 256) break;
            }
            codes = next;
        }
        return codes;
    }

    private static String normalizeUsageKey(String value) {
        if (value == null || value.isEmpty()) return "*";
        return value.replace('\t', ' ').replace('\n', ' ').trim();
    }

    private static String userRecordKey(String code, String word, String usageKey) {
        return normalizeStoredCode(code) + '\t' + word.trim() + '\t' + normalizeUsageKey(usageKey);
    }

    private void mergeT9Users(Map<String, Entry> result, String digits) {
        for (UserWordRecord record : matchingUserRecords(digits, true)) {
            String reading = recordReading(record);
            PinyinInput.Match match = PinyinInput.match(digits, reading, fullEnabled(), shortEnabled(),
                    preference("mixed_pinyin", true), preference("double_initials", true), true);
            if (match != null) merge(result, new Entry(record.word, 0, match.tier, record.pinned, 0,
                    record.pinned ? TIER_USER : rankingTier(match.tier, false)).withInput(digits.length(), reading));
        }
    }

    private static String normalizeStoredCode(String code) {
        if ("@word".equals(code)) return code;
        if (code != null && code.matches("[2-9]+")) return code;
        return PinyinInput.normalize(code == null ? "" : code.trim().replace(' ', '\''));
    }

    private static int normalizeUserTier(int tier) {
        if (tier == TIER_EXACT_FULL || tier == TIER_EXACT_SHORT || tier == TIER_MIXED
                || tier == TIER_PREFIX
                || tier == TIER_FUZZY || tier == TIER_COMPOSED) return tier;
        return TIER_EXACT_FULL;
    }

    private void upsert(Map<String, List<Entry>> target, String code,
                        String word, int score, int tier, boolean pinned) {
        List<Entry> list = target.computeIfAbsent(code,
                ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).word.equals(word)) {
                    Entry old = list.get(i);
                    boolean keepPinned = pinned || old.pinned;
                    int bestTier = keepPinned ? TIER_USER : Math.min(tier, old.matchTier);
                    list.set(i, new Entry(word, Math.max(score, old.score), bestTier, keepPinned));
                    list.sort(Comparator.comparingInt((Entry entry) -> entry.score).reversed());
                    return;
                }
            }
            list.add(new Entry(word, score, tier, pinned));
            list.sort(Comparator.comparingInt((Entry entry) -> entry.score).reversed());
        }
    }

    private void addT9(String code, String word, int score) {
        String digits = toT9(code); if (digits.isEmpty()) return;
        addT9Key(t9Index, digits, word, score);
    }

    private void addT9Key(Map<String, List<Entry>> target, String digits, String word, int score) {
        List<Entry> list = target.computeIfAbsent(digits, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            for (Entry e : list) if (e.word.equals(word)) return;
            list.add(new Entry(word, score));
            list.sort(Comparator.comparingInt((Entry e) -> e.score).reversed());
            if (list.size() > 24) list.subList(24, list.size()).clear();
        }
    }

    private List<String> fuzzyVariants(String raw) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        List<List<String>> paths = canonicalLexer.splitAll(raw, 2);
        if (fuzzyEnabled()) for (List<String> path : paths) {
            for (int i = 0; i < path.size() && variants.size() < 8; i++) {
                for (String replacement : fuzzySyllables(path.get(i))) {
                    ArrayList<String> changed = new ArrayList<>(path); changed.set(i, replacement);
                    variants.add(String.join("'", changed));
                }
            }
        }
        String q = normalize(raw);
        if (preference("spelling_correction", true) && fullEnabled() && q.length() >= 4 && raw.indexOf('\'') < 0) {
            for (int i = 0; i + 1 < q.length(); i++) {
                char[] letters = q.toCharArray(); char swap = letters[i]; letters[i] = letters[i + 1]; letters[i + 1] = swap;
                addCorrection(variants, new String(letters));
            }
            for (int i = 0; i < q.length(); i++) addCorrection(variants, q.substring(0, i) + q.substring(i + 1));
            String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
            for (int i = 0; i < q.length() && variants.size() < 12; i++) for (String row : rows) {
                int position = row.indexOf(q.charAt(i));
                if (position < 0) continue;
                for (int next : new int[]{position - 1, position + 1}) if (next >= 0 && next < row.length())
                    addCorrection(variants, q.substring(0, i) + row.charAt(next) + q.substring(i + 1));
            }
            for (int i = 0; i <= q.length() && variants.size() < 12; i++)
                for (char letter : "aeiouvnghrszc".toCharArray())
                    addCorrection(variants, q.substring(0, i) + letter + q.substring(i));
        }
        variants.remove(raw); variants.remove(q);
        return new ArrayList<>(variants).subList(0, Math.min(12, variants.size()));
    }

    private void addCorrection(java.util.Set<String> variants, String code) {
        if (variants.size() < 12 && !canonicalLexer.splitAll(code, 1).isEmpty()) variants.add(code);
    }

    private List<String> fuzzySyllables(String syllable) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[][] pairs = {{"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"n", "l"}, {"h", "f"},
                {"an", "ang"}, {"en", "eng"}, {"in", "ing"}, {"ian", "iang"}};
        for (int i = 0; i < pairs.length; i++) {
            String[] pair = pairs[i];
            if (!preference("fuzzy_" + pair[0] + "_" + pair[1], false)) continue;
            boolean initial = i < 5;
            for (int side = 0; side < 2; side++) {
                String from = pair[side], to = pair[1 - side];
                if (initial && syllable.startsWith(from)) out.add(to + syllable.substring(from.length()));
                else if (!initial && syllable.endsWith(from)) out.add(syllable.substring(0, syllable.length() - from.length()) + to);
            }
        }
        return new ArrayList<>(out);
    }
    private void loadFileSafely(String path, boolean keepInMemory,
                                boolean importToDatabase, int kind,
                                boolean collectPronunciations) {
        try {
            loadFile(path, keepInMemory, importToDatabase, kind, collectPronunciations);
        } catch (Exception error) {
            loadErrorCount++;
            Log.e(TAG, "failed to load asset " + path, error);
        }
    }

    private void loadFile(String path, boolean keepInMemory,
                          boolean importToDatabase, int kind,
                          boolean collectPronunciations) throws IOException {
        try (InputStream input = assets.open(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), 32768)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\\t");
                if (columns.length < 2) continue;
                String word = columns[0].trim();
                String rawCode = columns[1].trim().toLowerCase(Locale.ROOT);
                String code = normalize(rawCode);
                if (word.isEmpty() || code.isEmpty()) continue;
                // Overlay heat is query-specific; only its fourth-column base frequency is imported.
                boolean shortOverlay = path.startsWith("zh/life_short_hot_");
                if (shortOverlay && columns.length < 4) continue;
                int score = parseScore(shortOverlay ? new String[]{word, rawCode, columns[3]} : columns, 1);
                if (kind == 2) {
                    if (keepInMemory) addEnglish(code, word, score);
                    if (importToDatabase) database.put(code, word, score, 2, "");
                } else {
                    if (keepInMemory) add(code, word, score);
                    if (importToDatabase) { database.put(code, word, score, 0, toT9(code)); database.putPronunciation(rawCode, word, score); }
                    String initials = toInitials(rawCode);
                    if (!initials.isEmpty() && !initials.equals(code)) {
                        if (keepInMemory) addShort(initials, word, score);
                        if (importToDatabase) database.put(initials, word, score, 1, toT9(initials));
                    }
                    if (score >= 4000) {
                        for (String mixed : mixedCodes(rawCode)) {
                            if (keepInMemory) addMixed(mixed, word, score);
                            if (importToDatabase) database.put(mixed, word, score, 3, toT9(mixed));
                        }
                    }
                    if (collectPronunciations) collectCharacterPronunciations(word, rawCode);
                }
                loadedEntryCount++;
            }
        }
    }

    private void loadHotFileSafely(String path, int limit) {
        try (InputStream input = assets.open(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), 16384)) {
            String line;
            // Keep the best three per initials, then cover distinct codes before adding collisions.
            Map<String, List<HotPhrase>> buckets = new HashMap<>();
            boolean longPhrases = path.endsWith("life_common_phrases_5to8.txt");
            int readCount = 0;
            Comparator<HotPhrase> order = Comparator.comparingInt((HotPhrase phrase) -> phrase.entry.score)
                    .reversed().thenComparing(phrase -> phrase.entry.word);
            while ((!longPhrases || readCount < limit) && (line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\\t");
                if (columns.length < 3) continue;
                String word = columns[0].trim();
                String rawCode = columns[1].trim().toLowerCase(Locale.ROOT);
                String code = normalize(rawCode);
                if (word.isEmpty() || code.isEmpty()) continue;
                int score = parseScore(columns, 1);
                String reading = PinyinInput.normalize(rawCode.replace(' ', '\'')).replace('\'', ' ');
                if (word.codePointCount(0, word.length()) != reading.split(" +").length) continue;
                if (longPhrases) {
                    addMemoryHotPhrase(new HotPhrase(reading, word, score, 0));
                    readCount++;
                    continue;
                }
                HotPhrase phrase = new HotPhrase(reading, word, score, score >= 9000 ? 400 : 200);
                List<HotPhrase> bucket = buckets.computeIfAbsent(phrase.initials, ignored -> new ArrayList<>());
                bucket.add(phrase);
                bucket.sort(order);
                if (bucket.size() > 3) bucket.remove(bucket.size() - 1);
            }
            int added = 0;
            for (int rank = 0; rank < 3 && added < limit; rank++) {
                List<HotPhrase> round = new ArrayList<>();
                for (List<HotPhrase> bucket : buckets.values()) if (bucket.size() > rank) round.add(bucket.get(rank));
                round.sort(order);
                for (HotPhrase phrase : round) {
                    if (added >= limit) break;
                    addMemoryHotPhrase(phrase);
                    added++;
                }
            }
        } catch (Exception error) {
            loadErrorCount++;
            Log.e(TAG, "failed to load hot phrases " + path, error);
        }
    }

    private void addMemoryHotPhrase(HotPhrase phrase) {
        hotPhraseBuffer.add(phrase);
        add(phrase.code, phrase.entry.word, phrase.entry.score);
        addShort(phrase.initials, phrase.entry.word, phrase.entry.score);
        for (String mixed : mixedCodes(phrase.reading)) addMixed(mixed, phrase.entry.word, phrase.entry.score);
    }

    /** Overlay columns: word, reading, short-only bonus, unmodified corpus score. */
    private void loadShortHotOverlay(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(assets.open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\\t");
                if (columns.length < 4) continue;
                String word = columns[0].trim();
                String reading = PinyinInput.normalize(columns[1].trim().replace(' ', '\'')).replace('\'', ' ');
                int length = word.codePointCount(0, word.length());
                if (length < 2 || length > 4 || reading.split(" +").length != length) continue;
                int boost = Math.max(0, Math.min(3000, Integer.parseInt(columns[2])));
                int corpus = Math.max(1, Integer.parseInt(columns[3]));
                addMemoryHotPhrase(new HotPhrase(reading, word, corpus, boost));
            }
        } catch (Exception error) {
            loadErrorCount++;
            Log.e(TAG, "failed to load short hot overlay " + path, error);
        }
    }

    static List<String> mixedCodes(String rawCode) {
        String[] syllables = rawCode.split("[\\s']+");
        if (syllables.length < 3 || syllables.length > 8) return Collections.emptyList();
        ArrayList<String> mixed = new ArrayList<>();
        for (int cut = 1; cut <= Math.min(3, syllables.length - 1); cut++) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < syllables.length; i++) {
                if (syllables[i].isEmpty()) return Collections.emptyList();
                code.append(i < cut ? syllables[i] : syllables[i].charAt(0));
            }
            mixed.add(code.toString());
        }
        return mixed;
    }

    private int parseScore(String[] columns, int fallback) {
        if (columns.length < 3) return fallback;
        try { return Integer.parseInt(columns[2].trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void collectCharacterPronunciations(String word, String rawCode) {
        String[] syllables = rawCode.split("[\\s']+");
        int[] characters = word.codePoints().toArray();
        if (characters.length != syllables.length) return;
        for (int i = 0; i < characters.length; i++) {
            String character = new String(Character.toChars(characters[i]));
            String syllable = syllables[i].replaceAll("[^a-z]", "");
            if (!syllable.isEmpty()) {
                characterPronunciations.computeIfAbsent(character,
                        ignored -> new LinkedHashSet<>()).add(syllable);
            }
        }
    }

    private void loadSingleCharacterFileSafely(
            String path, boolean keepInMemory, boolean importToDatabase) {
        try (InputStream input = assets.open(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), 16384)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] columns = line.split("\\t");
                if (columns.length < 2) continue;
                String word = columns[0].trim();
                int score;
                try { score = Integer.parseInt(columns[1].trim()); }
                catch (NumberFormatException ignored) { continue; }
                LinkedHashSet<String> codes = characterPronunciations.get(word);
                if (codes == null || codes.isEmpty()) {
                    String generated = transliterateCharacter(word);
                    if (!generated.isEmpty()) codes = new LinkedHashSet<>(Collections.singleton(generated));
                }
                if (codes == null) continue;
                for (String code : codes) {
                    if (keepInMemory) add(code, word, score);
                    if (importToDatabase) { database.put(code, word, score, 0, toT9(code)); database.putPronunciation(code, word, score); }
                }
                loadedEntryCount++;
            }
        } catch (Exception error) {
            loadErrorCount++;
            Log.e(TAG, "failed to load asset " + path, error);
        }
    }

    private String transliterateCharacter(String value) {
        try {
            if (hanLatinTransliterator == null) {
                Class<?> type = Class.forName("android.icu.text.Transliterator");
                hanLatinTransliterator = type.getMethod("getInstance", String.class)
                        .invoke(null, "Han-Latin; Latin-ASCII; Lower()");
            }
            String converted = (String) hanLatinTransliterator.getClass()
                    .getMethod("transliterate", String.class).invoke(hanLatinTransliterator, value);
            return converted.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        } catch (Exception ignored) {
            return "";
        }
    }
    private String[] safeList(String path) {
        try {
            String[] names = assets.list(path);
            if (names == null) return new String[0];
            Arrays.sort(names);
            return names;
        } catch (IOException error) {
            loadErrorCount++;
            Log.e(TAG, "failed to list asset directory " + path, error);
            return new String[0];
        }
    }

    private void add(String code, String word, int score) {
        List<Entry> list = index.computeIfAbsent(code, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            if (list.size() < 32) list.add(new Entry(word, score));
        }
        addT9(code, word, score);
    }

    private void addShort(String initials, String word, int score) {
        List<Entry> list = shortIndex.computeIfAbsent(initials, ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            if (list.size() < 32) list.add(new Entry(word, score));
        }
        String digits = toT9(initials);
        if (!digits.isEmpty()) addT9Key(t9ShortIndex, digits, word, score);
    }

    private void addMixed(String code, String word, int score) {
        upsert(mixedIndex, code, word, score, TIER_MIXED, false);
        addT9Key(t9MixedIndex, toT9(code), word, score);
    }


    private void addEnglish(String code, String word, int score) {
        List<Entry> list = englishIndex.computeIfAbsent(code,
                ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            if (list.size() < 32) list.add(new Entry(word, score));
        }
    }
    private boolean fullEnabled() { return Boolean.TRUE.equals(shuangpinLookup.get()) || preference("full_pinyin", true); }
    private boolean fuzzyEnabled() { return preference("fuzzy_pinyin", false); }
    private boolean sentenceEnabled() { return preference("sentence_prediction", true); }
    private boolean preference(String key, boolean fallback) {
        Object value = settingsValues.get(key);
        return context == null || (value instanceof Boolean ? (Boolean) value : fallback);
    }
    private String settingsSignature() {
        return settingsKey + (Boolean.TRUE.equals(shuangpinLookup.get()) ? ":xiaohe" : "");
    }
    private boolean shortEnabled() {
        return preference("short_pinyin", true);
    }

    /** 从带空格的词库拼音生成简拼，例如“ni hao”->“nh”。 */
    private static String toInitials(String rawCode) {
        StringBuilder out = new StringBuilder();
        for (String syllable : rawCode.split("[\\s']+")) {
            if (!syllable.isEmpty() && Character.isLetter(syllable.charAt(0))) out.append(syllable.charAt(0));
        }
        return out.toString();
    }

    private static String normalizeForSegmentation(String value) {
        if (value == null) return "";
        return PinyinInput.normalize(value);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("u:", "v").replace("ü", "v").replace(" ", "").replace("'", "").trim();
    }

    static String toT9(String code) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if ("abc".indexOf(c) >= 0) out.append('2');
            else if ("def".indexOf(c) >= 0) out.append('3');
            else if ("ghi".indexOf(c) >= 0) out.append('4');
            else if ("jkl".indexOf(c) >= 0) out.append('5');
            else if ("mno".indexOf(c) >= 0) out.append('6');
            else if ("pqrs".indexOf(c) >= 0) out.append('7');
            else if ("tuv".indexOf(c) >= 0) out.append('8');
            else if ("wxyz".indexOf(c) >= 0) out.append('9');
        }
        return out.toString();
    }

    private void addCommonEntries() {
        // 词库尚未完成后台加载时，常见音节也能立即输入。
        String[][] common = {
                {"a", "啊,阿"}, {"ai", "爱,矮,哎"}, {"an", "安,按,案"}, {"ba", "吧,八,把"},
                {"bai", "白,百"}, {"ban", "办,半,班"}, {"bao", "包,报,保"}, {"bei", "被,北,备"},
                {"ben", "本"}, {"bi", "比,笔,必"}, {"bian", "变,便,边"}, {"biao", "表"},
                {"bu", "不,部,步"}, {"cai", "才,菜,采"}, {"cao", "操"}, {"ce", "测"},
                {"cha", "查,差"}, {"chang", "长,常,厂"}, {"cheng", "成,城,程"}, {"chi", "吃,持"},
                {"chu", "出,处,初"}, {"ci", "次,此"}, {"cong", "从,聪"}, {"cuo", "错"},
                {"da", "大,打,答"}, {"dai", "带,代,待"}, {"dan", "但,单,蛋"}, {"dao", "到,道,倒"},
                {"de", "的,得,德"}, {"deng", "等,灯"}, {"di", "地,第,低"}, {"dian", "点,电,店"},
                {"dong", "东,动,懂"}, {"du", "都,读,度"}, {"duo", "多,夺"}, {"er", "而,二,儿"},
                {"fa", "发,法"}, {"fan", "反,饭,犯"}, {"fang", "方,房,放"}, {"fei", "非,飞,费"},
                {"fen", "分,份"}, {"feng", "风,丰"}, {"fu", "服,福,付"}, {"gai", "该"}, {"ge", "个,各,歌"},
                {"gen", "跟,根"}, {"geng", "更"}, {"gong", "工,公,共"}, {"guo", "国,过,果"},
                {"hai", "还,海"}, {"han", "汉,含"}, {"hao", "好,号"}, {"he", "和,何,喝"},
                {"hen", "很"}, {"hou", "后,候"}, {"hu", "湖,互,护"}, {"hua", "话,花"}, {"huan", "换,欢"},
                {"hui", "会,回,惠"}, {"ji", "几,机,记"}, {"jia", "家,加,假"}, {"jian", "见,间,建"},
                {"jiang", "将,讲,江"}, {"jiao", "叫,教,交"}, {"jie", "接,节,解"}, {"jin", "今,进,近"},
                {"jing", "经,京,静"}, {"jiu", "就,九,久"}, {"ju", "具,句"}, {"kai", "开"}, {"kan", "看"},
                {"ke", "可,科,客"}, {"kong", "空"}, {"kuai", "快"}, {"la", "拉"}, {"lai", "来"},
                {"lan", "兰"}, {"le", "了,乐"}, {"li", "里,理,力"}, {"lian", "连,联,练"}, {"liang", "两,良"},
                {"liao", "了,聊"}, {"lin", "林"}, {"ling", "另,零"}, {"liu", "六,留"}, {"long", "龙"},
                {"lu", "路,陆"}, {"lv", "绿,旅"}, {"ma", "吗,马,妈"}, {"mai", "买,卖"}, {"man", "满,慢"},
                {"mao", "毛,冒"}, {"mei", "没,每,美"}, {"men", "们,门"}, {"mi", "米,密"}, {"mian", "面,免"},
                {"ming", "明,名,命"}, {"mo", "么,莫"}, {"na", "那,哪,拿"}, {"nai", "乃"}, {"nan", "南"},
                {"nei", "内"}, {"ni", "你,尼"}, {"nian", "年,念"}, {"nin", "您"}, {"ning", "宁"},
                {"niu", "牛"}, {"nong", "农"}, {"nu", "怒,奴"}, {"nv", "女"}, {"ou", "欧"}, {"pa", "怕"},
                {"pai", "排"}, {"pan", "盘"}, {"peng", "朋"}, {"pi", "批"}, {"pian", "片"}, {"ping", "平"},
                {"qi", "起,其,气"}, {"qian", "前,钱,千"}, {"qiang", "强"}, {"qiao", "桥"}, {"qie", "切"},
                {"qin", "亲"}, {"qing", "请,情,清"}, {"qiu", "求"}, {"qu", "去,取"}, {"ran", "然"},
                {"rang", "让"}, {"re", "热"}, {"ren", "人,认"}, {"ri", "日"}, {"rong", "容"}, {"ru", "如"},
                {"san", "三"}, {"sao", "扫"}, {"se", "色"}, {"sen", "森"}, {"sha", "杀"}, {"shan", "山,善"},
                {"shang", "上,商"}, {"shao", "少"}, {"she", "社,设"}, {"shen", "什,身,深"}, {"sheng", "生,声"},
                {"shi", "是,时,事"}, {"shou", "手,收"}, {"shu", "书,数"}, {"shui", "水"}, {"si", "四,思"},
                {"song", "送"}, {"su", "速"}, {"sui", "岁"}, {"ta", "他,她,它"}, {"tai", "太,台"},
                {"tan", "谈"}, {"tang", "堂"}, {"te", "特"}, {"ti", "提,题"}, {"tian", "天,田"},
                {"ting", "听,停"}, {"tong", "同,通"}, {"tu", "图,土"}, {"tuan", "团"}, {"tui", "退"},
                {"wa", "哇"}, {"wai", "外"}, {"wan", "完,晚,万"}, {"wang", "王,网,往"}, {"wei", "为,位,未"},
                {"wen", "问,文,温"}, {"wo", "我,握"}, {"wu", "无,五,物"}, {"xi", "西,系,喜"}, {"xia", "下,夏"},
                {"xian", "先,现,线"}, {"xiang", "想,向,相"}, {"xiao", "小,笑,校"}, {"xie", "些,写,谢"},
                {"xin", "新,心,信"}, {"xing", "行,性,星"}, {"xiong", "兄"}, {"xiu", "休"}, {"xu", "需,许"},
                {"xue", "学,雪"}, {"ya", "呀"}, {"yan", "眼,言,研"}, {"yang", "样,阳"}, {"yao", "要,药"},
                {"ye", "也,夜,业"}, {"yi", "一,以,已"}, {"yin", "因,音,引"}, {"ying", "应,影"}, {"yong", "用,永"},
                {"you", "有,又,友"}, {"yu", "于,与,语"}, {"yuan", "元,原,远"}, {"yue", "月,越"}, {"yun", "云,运"},
                {"za", "杂"}, {"zai", "在,再"}, {"zan", "咱"}, {"zao", "早"}, {"ze", "则"}, {"zen", "怎"},
                {"zhan", "站,战"}, {"zhang", "张,长"}, {"zhao", "找,照"}, {"zhe", "这,着"}, {"zhen", "真,镇"},
                {"zheng", "正,整"}, {"zhi", "之,只,知"}, {"zhong", "中,种"}, {"zhou", "周"}, {"zhu", "主,住"},
                {"zhuang", "装"}, {"zhun", "准"}, {"zhuo", "着"}, {"zi", "自,字"}, {"zong", "总"}, {"zou", "走"},
                {"zu", "组,足"}, {"zui", "最"}, {"zun", "尊"}, {"zuo", "做,作,坐"}
        };
        for (String[] pair : common) for (String word : pair[1].split(",")) add(pair[0], word, 11000);
        add("nihao", "你好", 12000);
        add("zaijian", "再见", 11000);
        add("xiexie", "谢谢", 11000);
        add("duibuqi", "对不起", 10000);
        add("meiguanxi", "没关系", 10000);
        add("zhongguo", "中国", 12000);
        add("shijie", "世界", 11500);
        add("women", "我们", 12000);
        add("xianzai", "现在", 11000);
        add("shenme", "什么", 11000);
        add("wojintian", "我今天", 11800);
        add("wojintianxiangchi", "我今天想吃", 12500);
        add("wojintianxiangchifan", "我今天想吃饭", 12400);
        add("wojintianxiangchihuoguo", "我今天想吃火锅", 12300);
        add("jintian", "今天", 11800);
        add("mingtian", "明天", 11200);
        add("qingdengyixia", "请等一下", 11200);
        add("duobangmang", "多帮忙", 10000);
        // 常见短语在后台词库尚未加载时也支持简拼/9键简拼。
        addShort("nh", "你好", 12000);
        addShort("sj", "世界", 10000);
        addShort("zj", "再见", 11000);
        addShort("xx", "谢谢", 11000);
        addShort("zg", "中国", 12000);
        addShort("wm", "我们", 12000);
        addShort("xz", "现在", 11000);
        addShort("sm", "什么", 11000);
        addShort("jt", "今天", 11800);
        addShort("mt", "明天", 11200);
    }
}
