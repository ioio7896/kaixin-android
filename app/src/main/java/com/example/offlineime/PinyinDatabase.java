package com.example.offlineime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import android.util.Log;

/** Versioned persistent index built exclusively from the bundled lexicon assets. */
final class PinyinDatabase extends SQLiteOpenHelper {
    private static final int SCHEMA_VERSION = 8;
    private final Context applicationContext;
    private SQLiteDatabase importDatabase;
    private final Map<String, Integer> transitionCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Integer>(128, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) {
                    return size() > 2048;
                }
            });

    private Cursor select(String table, String[] columns, String selection, String[] args,
                          String groupBy, String having, String orderBy, String limit) {
        QueryWork.check();
        long started = ImePerformanceMonitor.start();
        return QueryWork.timedCursor(getReadableDatabase().query(false, table, columns, selection, args,
                groupBy, having, orderBy, limit, QueryWork.signal()), started);
    }

    private Cursor selectRaw(String sql, String[] args) {
        QueryWork.check();
        long started = ImePerformanceMonitor.start();
        return QueryWork.timedCursor(getReadableDatabase().rawQuery(sql, args, QueryWork.signal()), started);
    }

    PinyinDatabase(Context context) {
        super(context, "pinyin.db", null, SCHEMA_VERSION);
        applicationContext = context.getApplicationContext();
    }

    /** Called exclusively by the lexicon loader, before any candidate database queries. */
    void prepareBundledDatabase(int lexiconVersion) {
        transitionCache.clear();
        close();
        installBundledDatabase(applicationContext, lexiconVersion);
    }

    private static boolean matchesBundledVersion(File file, int lexiconVersion) {
        if (!file.isFile()) return false;
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            if (database.getVersion() != SCHEMA_VERSION) return false;
            try (Cursor cursor = database.query("metadata", new String[]{"value"}, "name=?",
                    new String[]{"lexicon_version"}, null, null, null, "1")) {
                return cursor.moveToFirst() && Integer.toString(lexiconVersion).equals(cursor.getString(0));
            }
        } catch (RuntimeException error) {
            Log.w("OfflineImeLexicon", "database version unavailable", error);
            return false;
        }
    }

    private static InputStream openBundledDatabase(Context context) throws IOException {
        try { return new java.util.zip.GZIPInputStream(context.getAssets().open("databases/pinyin.bin"), 64 * 1024); }
        catch (java.io.FileNotFoundException missing) { return context.getAssets().open("databases/pinyin.db"); }
    }

    private static void installBundledDatabase(Context context, int lexiconVersion) {
        File target = context.getDatabasePath("pinyin.db");
        if (matchesBundledVersion(target, lexiconVersion)) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        File temporary = new File(parent, "pinyin.db.installing");
        try {
            try (InputStream input = openBundledDatabase(context);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    QueryWork.check();
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (!matchesBundledVersion(temporary, lexiconVersion))
                throw new IOException("Bundled lexicon version does not match " + lexiconVersion);
            // The helper is still unopened. No queries may use the old database during replacement.
            if (target.exists()) {
                try (SQLiteDatabase old = SQLiteDatabase.openDatabase(target.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                    old.disableWriteAheadLogging();
                } catch (RuntimeException error) {
                    Log.w("OfflineImeLexicon", "replacing unreadable old lexicon", error);
                }
            }
            QueryWork.check();
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // A stale sidecar belongs to the replaced corpus, never the new prebuilt file.
            for (String suffix : new String[]{"-wal", "-shm", "-journal"})
                new File(target.getPath() + suffix).delete();
        } catch (IOException error) {
            Log.e("OfflineImeLexicon", "bundled lexicon installation failed", error);
        } finally {
            temporary.delete();
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE words(code TEXT NOT NULL, word TEXT NOT NULL, score INTEGER NOT NULL, kind INTEGER NOT NULL, t9 TEXT NOT NULL DEFAULT '', PRIMARY KEY(code,word,kind))");
        db.execSQL("CREATE INDEX idx_words_kind_code_score ON words(kind,code,score DESC)");
        db.execSQL("CREATE INDEX idx_words_kind_t9_score ON words(kind,t9,score DESC)");
        db.execSQL("CREATE TABLE pronunciations(code TEXT NOT NULL, syllables TEXT NOT NULL, initials TEXT NOT NULL, word TEXT NOT NULL, score INTEGER NOT NULL, digits TEXT NOT NULL DEFAULT '', initial_digits TEXT NOT NULL DEFAULT '', PRIMARY KEY(code,syllables,word))");
        db.execSQL("CREATE INDEX idx_pron_digits ON pronunciations(digits,score DESC,word,syllables)");
        db.execSQL("CREATE INDEX idx_pron_initial_digits ON pronunciations(initial_digits,score DESC,word,syllables)");
        db.execSQL("CREATE INDEX idx_pron_initials ON pronunciations(initials,score DESC,word,syllables)");
        db.execSQL("CREATE INDEX idx_pron_code ON pronunciations(code,score DESC,word,syllables)");
        db.execSQL("CREATE INDEX idx_pron_word ON pronunciations(word,score DESC)");
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE word_transitions(previous TEXT NOT NULL, next TEXT NOT NULL, weight INTEGER NOT NULL, PRIMARY KEY(previous,next))");
        db.execSQL("CREATE TABLE characters AS SELECT * FROM pronunciations WHERE length(word)=1");
        for (String column : new String[]{"code", "digits", "initials", "initial_digits"})
            db.execSQL("CREATE INDEX idx_char_" + column + " ON characters(" + column + ",score DESC,word,syllables)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        transitionCache.clear();
        db.execSQL("DROP TABLE IF EXISTS word_transitions");
        db.execSQL("DROP TABLE IF EXISTS pronunciations");
        db.execSQL("DROP TABLE IF EXISTS characters");
        db.execSQL("DROP TABLE IF EXISTS words");
        db.execSQL("DROP TABLE IF EXISTS metadata");
        onCreate(db);
    }

    boolean isLexiconCurrent(int version) {
        Cursor cursor = select("metadata", new String[]{"value"},
                "name=?", new String[]{"lexicon_version"}, null, null, null, "1");
        try { return cursor.moveToFirst() && Integer.toString(version).equals(cursor.getString(0)); }
        finally { cursor.close(); }
    }

    void beginLexiconImport() {
        transitionCache.clear();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        db.delete("words", null, null);
        db.delete("pronunciations", null, null);
        db.delete("characters", null, null);
        db.delete("metadata", null, null);
        importDatabase = db;
    }

    void finishLexiconImport(int version, int entryCount) {
        SQLiteDatabase db = importDatabase;
        if (db == null) return;
        try {
            db.execSQL("INSERT INTO characters SELECT * FROM pronunciations WHERE length(word)=1");
            putMetadata(db, "lexicon_version", Integer.toString(version));
            putMetadata(db, "entry_count", Integer.toString(entryCount));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            importDatabase = null;
        }
    }

    void abortLexiconImport() {
        SQLiteDatabase db = importDatabase;
        if (db == null) return;
        try { db.endTransaction(); } finally { importDatabase = null; }
    }

    private void putMetadata(SQLiteDatabase db, String name, String value) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("value", value);
        db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void put(String code, String word, int score, int kind, String t9) {
        SQLiteDatabase db = importDatabase;
        if (db == null) return;
        ContentValues values = new ContentValues();
        values.put("code", code);
        values.put("word", word);
        values.put("score", score);
        values.put("kind", kind);
        values.put("t9", t9 == null ? "" : t9);
        if (db.insertWithOnConflict("words", null, values, SQLiteDatabase.CONFLICT_IGNORE) < 0) {
            db.update("words", values, "code=? AND word=? AND kind=? AND score<?",
                    new String[]{code, word, Integer.toString(kind), Integer.toString(score)});
        }
    }

    void putPronunciation(String raw, String word, int score) {
        if (importDatabase == null) return;
        String syllables = PinyinInput.normalize(raw.replace(' ', '\'')).replace('\'', ' ').trim();
        if (syllables.isEmpty()) return;
        StringBuilder initials = new StringBuilder();
        for (String part : syllables.split(" +")) initials.append(part.charAt(0));
        ContentValues values = new ContentValues();
        values.put("code", syllables.replace(" ", "")); values.put("syllables", syllables);
        values.put("initials", initials.toString()); values.put("word", word); values.put("score", score);
        values.put("digits", PinyinDictionary.toT9(syllables.replace(" ", ""))); values.put("initial_digits", PinyinDictionary.toT9(initials.toString()));
        importDatabase.insertWithOnConflict("pronunciations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    List<PinyinDictionary.Entry> queryFlexible(String input, int limit, boolean full,
            boolean shortInput, boolean mixed, boolean doubles, boolean prefix) {
        return queryFlexible(input, limit, full, shortInput, mixed, doubles, prefix, null);
    }
    List<PinyinDictionary.Entry> queryConstrained(PinyinPathEditor.Query query, int limit,
            boolean full, boolean shortInput, boolean mixed, boolean doubles, boolean prefix) {
        return queryFlexible(query.databaseInput, limit, full, shortInput, mixed, doubles, prefix, query);
    }
    private List<PinyinDictionary.Entry> queryFlexible(String input, int limit, boolean full,
            boolean shortInput, boolean mixed, boolean doubles, boolean prefix,
            PinyinPathEditor.Query constraint) {
        final int resultLimit = Math.max(1, limit);
        final int firstScreenTarget = Math.min(15, resultLimit);
        boolean numeric = input.matches("[2-9']+");
        List<PinyinInput.Path> paths = numeric ? Collections.emptyList() : PinyinInput.paths(input, full, shortInput, doubles);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (constraint != null) keys.addAll(constraint.initialKeys(full, shortInput, doubles));
        else {
            if (shortInput) {
                List<PinyinInput.Path> shortPaths = numeric ? PinyinInput.numericPaths(input, false, true, doubles)
                        : PinyinInput.paths(input, false, true, doubles);
                for (PinyinInput.Path path : shortPaths) keys.add(path.initials);
            }
            for (PinyinInput.Path path : paths) keys.add(path.initials);
            if (numeric) keys.addAll(PinyinInput.numericKeys(input, full, shortInput, doubles));
        }
        if (keys.isEmpty()) return new ArrayList<>();
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (String key : keys) {
            if (where.length() > 0) where.append(" OR ");
            String column = numeric ? "initial_digits" : "initials";
            where.append(prefix ? "(" + column + ">=? AND " + column + "<?)" : column + "=?");
            args.add(key); if (prefix) args.add(prefixUpperBound(key));
        }
        ArrayList<PinyinDictionary.Entry> out = new ArrayList<>();
        boolean abbreviation = !numeric && PinyinInput.isAbbreviationQuery(input, full);
        // Always retrieve exact full spelling before broad initials: high-frequency
        // neighbours must never push an exact low-frequency word outside the limit.
        String plain = (numeric ? input : PinyinInput.normalize(input)).replace("'", "");
        String fullColumn = numeric ? "digits" : "code";
        String leadingReading = constraint == null ? "" : constraint.leadingReading();
        String leadingPattern = leadingReading + (constraint != null && constraint.leadingIsPrefix() ? "%" : " %");
        String lockedFilter = leadingReading.isEmpty() ? "" : " AND (syllables=? OR syllables LIKE ?)";
        if (!leadingReading.isEmpty()) {
            where.insert(0, '(').append(')').append(lockedFilter);
            args.add(leadingReading); args.add(leadingPattern);
        }
        if (full || constraint != null) {
            int primaryLimit = Math.max(24, Math.min(64, resultLimit * 2));
            // Complete spellings and initials are recalled before any completion.
            List<String> fullArgs = new ArrayList<>();
            fullArgs.add(plain);
            if (!leadingReading.isEmpty()) {
                fullArgs.add(leadingReading); fullArgs.add(leadingPattern);
            }
            try (Cursor cursor = select("pronunciations",
                    new String[]{"word", "score", "syllables"},
                    fullColumn + "=?" + lockedFilter,
                    fullArgs.toArray(new String[0]),
                    null, null, "score DESC, word", Integer.toString(primaryLimit))) {
                while (cursor.moveToNext()) {
                    QueryWork.check();
                    String reading = cursor.getString(2);
                    PinyinInput.Match match = constraint == null
                            ? PinyinInput.match(input, reading, full, shortInput, mixed, doubles, prefix)
                            : constraint.match(reading, full, shortInput, mixed, doubles, prefix);
                    if (match != null) out.add(new PinyinDictionary.Entry(cursor.getString(0), cursor.getInt(1), match.tier)
                            .withInput(constraint == null ? input.length() : constraint.raw.length(), reading));
                }
            }
        }
        // Equality is a separate phase: longer high-frequency completions must not
        // consume the scan budget before complete short/mixed readings are retrieved.
        if (shortInput) {
            StringBuilder exactWhere = new StringBuilder();
            List<String> exactArgs = new ArrayList<>();
            String column = numeric ? "initial_digits" : "initials";
            for (String key : keys) {
                if (exactWhere.length() > 0) exactWhere.append(" OR ");
                exactWhere.append(column).append("=?");
                exactArgs.add(key);
            }
            exactWhere.insert(0, '(').append(')').append(lockedFilter);
            if (!leadingReading.isEmpty()) {
                exactArgs.add(leadingReading); exactArgs.add(leadingPattern);
            }
            int exactLimit = Math.max(96, Math.min(256, resultLimit * 4));
            // Recall matching two-character words before three-character heat fills the scan cap.
            String exactOrder = CandidateRanker.isThreeLetterInput(input)
                    ? "CASE WHEN length(word)=2 AND score>=4000 THEN 0 ELSE 1 END, score DESC, word"
                    : "score DESC, word";
            try (Cursor cursor = select("pronunciations", new String[]{"word", "score", "syllables"},
                    exactWhere.toString(), exactArgs.toArray(new String[0]), null, null,
                    exactOrder, Integer.toString(exactLimit))) {
                while (cursor.moveToNext()) {
                    QueryWork.check();
                    String reading = cursor.getString(2);
                    PinyinInput.Match match = constraint == null
                            ? PinyinInput.match(input, reading, full, shortInput, mixed, doubles, false)
                            : constraint.match(reading, full, shortInput, mixed, doubles, false);
                    if (match != null) out.add(new PinyinDictionary.Entry(cursor.getString(0), cursor.getInt(1), match.tier)
                            .withInput(constraint == null ? input.length() : constraint.raw.length(), reading));
                }
            }
        }
        LinkedHashSet<String> primaryWords = new LinkedHashSet<>();
        for (PinyinDictionary.Entry entry : out) {
            if (entry.matchTier <= PinyinDictionary.TIER_MIXED) primaryWords.add(entry.word);
        }
        // Once complete readings fill the strip, neither full nor initials
        // prefix ranges can outrank them; avoid both scans on this hot path.
        if (primaryWords.size() >= firstScreenTarget)
            return rankFlexible(out, abbreviation, resultLimit, input);
        if (prefix && (full || constraint != null)) {
            List<String> prefixArgs = new ArrayList<>();
            prefixArgs.add(plain); prefixArgs.add(prefixUpperBound(plain));
            if (!leadingReading.isEmpty()) {
                prefixArgs.add(leadingReading); prefixArgs.add(leadingPattern);
            }
            int prefixLimit = Math.max(24, Math.min(64, resultLimit * 2));
            try (Cursor cursor = select("pronunciations", new String[]{"word", "score", "syllables"},
                    fullColumn + ">? AND " + fullColumn + "<?" + lockedFilter,
                    prefixArgs.toArray(new String[0]), null, null,
                    "score DESC, word", Integer.toString(prefixLimit))) {
                while (cursor.moveToNext()) {
                    QueryWork.check();
                    String reading = cursor.getString(2);
                    PinyinInput.Match match = constraint == null
                            ? PinyinInput.match(input, reading, full, shortInput, mixed, doubles, true)
                            : constraint.match(reading, full, shortInput, mixed, doubles, true);
                    if (match != null) out.add(new PinyinDictionary.Entry(cursor.getString(0), cursor.getInt(1), match.tier)
                            .withInput(constraint == null ? input.length() : constraint.raw.length(), reading));
                }
            }
        }
        // With no completion requested the equality phase already scanned all
        // initials keys. Do not run that same SQL again for composition edges.
        if (!prefix || !shortInput) return rankFlexible(out, abbreviation, resultLimit, input);
        int expansionLimit = Math.max(64, Math.min(192, resultLimit * 3));
        try (Cursor cursor = select("pronunciations",
                new String[]{"word", "score", "syllables"}, where.toString(), args.toArray(new String[0]),
                null, null, "score DESC, word", Integer.toString(expansionLimit))) {
            while (cursor.moveToNext()) {
                QueryWork.check();
                String pronunciation = cursor.getString(2);
                PinyinInput.Match match = constraint == null
                        ? PinyinInput.match(input, pronunciation, full, shortInput, mixed, doubles, prefix)
                        : constraint.match(pronunciation, full, shortInput, mixed, doubles, prefix);
                if (match != null) out.add(new PinyinDictionary.Entry(cursor.getString(0), cursor.getInt(1), match.tier)
                        .withInput(constraint == null ? input.length() : constraint.raw.length(), pronunciation));
            }
        }
        return rankFlexible(out, abbreviation, resultLimit, input);
    }

    /** Full syllable edges need only the indexed code/digits equality query,
     * not flexible initials parsing, prefix scans or abbreviation ranking. */
    List<PinyinDictionary.Entry> queryFullSyllables(String input, int limit) {
        QueryWork.check();
        boolean numeric = input.matches("[2-9']+");
        String normalized = numeric ? input : PinyinInput.normalize(input);
        String plain = normalized.replace("'", "");
        if (plain.isEmpty()) return Collections.emptyList();
        String where = (numeric ? "digits" : "code") + "=?";
        List<String> args = new ArrayList<>(); args.add(plain);
        if (!numeric && normalized.indexOf('\'') >= 0) {
            // Filter explicit letter boundaries in SQL, before the scan limit.
            where += " AND syllables=?";
            args.add(normalized.replaceAll("'+$", "").replace('\'', ' '));
        }
        List<PinyinDictionary.Entry> out = new ArrayList<>();
        int resultLimit = Math.max(1, limit);
        int scanLimit = Math.max(24, Math.min(64, resultLimit * 2));
        try (Cursor cursor = select("pronunciations", new String[]{"word", "score", "syllables"},
                where, args.toArray(new String[0]), null, null, "score DESC, word", Integer.toString(scanLimit))) {
            while (cursor.moveToNext()) {
                QueryWork.check();
                String reading = cursor.getString(2);
                PinyinInput.Match match = PinyinInput.match(normalized, reading, true, false, false, false, false);
                if (match != null) out.add(new PinyinDictionary.Entry(cursor.getString(0), cursor.getInt(1), match.tier)
                        .withInput(input.length(), reading));
            }
        }
        // Multiple pronunciations of one word still consume only one edge slot.
        return rankFlexible(out, false, resultLimit, normalized);
    }

    private List<PinyinDictionary.Entry> rankFlexible(List<PinyinDictionary.Entry> entries,
                                                      boolean abbreviation, int limit, String input) {
        java.util.Map<String, PinyinDictionary.Entry> unique = new java.util.LinkedHashMap<>();
        for (PinyinDictionary.Entry entry : entries) {
            QueryWork.check();
            PinyinDictionary.Entry candidate = new PinyinDictionary.Entry(entry.word, entry.score,
                    entry.matchTier, entry.pinned, entry.corpusScore,
                    PinyinDictionary.rankingTier(entry.matchTier, abbreviation))
                    .withInput(entry.consumedLength, entry.pronunciation);
            PinyinDictionary.Entry prior = unique.get(candidate.word);
            if (prior == null || candidate.rankingTier < prior.rankingTier
                    || candidate.rankingTier == prior.rankingTier && candidate.score > prior.score)
                unique.put(candidate.word, candidate);
        }
        return CandidateRanker.rankTop(unique.values(), false, limit, input);
    }

    List<String> readings(String word) {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = select("pronunciations", new String[]{"syllables"},
                "word=?", new String[]{word}, null, null, "score DESC", "32")) {
            while (cursor.moveToNext()) {
                QueryWork.check(); String value = cursor.getString(0).replace(' ', '\'');
                if (!result.contains(value)) result.add(value);
            }
        }
        return result;
    }

    List<PinyinDictionary.Entry> creationWords(String input, boolean numeric, PinyinPathEditor.Query constraint) {
        List<PinyinDictionary.Entry> result = creationWords(input, numeric, constraint, true);
        result.addAll(creationWords(input, numeric, constraint, false));
        return result;
    }
    List<PinyinDictionary.Entry> creationPhrases(String input, boolean numeric, PinyinPathEditor.Query constraint) {
        return creationWords(input, numeric, constraint, false);
    }
    SingleCharacterIndex loadCharacterIndex() {
        try (Cursor cursor = select("characters", new String[]{"word", "score", "syllables"},
                null, null, null, null, "score DESC, word", null)) {
            return SingleCharacterIndex.load(cursor);
        }
    }

    private List<PinyinDictionary.Entry> creationWords(String input, boolean numeric,
            PinyinPathEditor.Query constraint, boolean singleCharacters) {
        List<PinyinDictionary.Entry> result = creationWords(input, numeric, constraint, singleCharacters, false);
        result.addAll(creationWords(input, numeric, constraint, singleCharacters, true));
        return result;
    }
    private List<PinyinDictionary.Entry> creationWords(String input, boolean numeric,
            PinyinPathEditor.Query constraint, boolean singleCharacters, boolean initialBranch) {
        QueryWork.check();
        String plain = input.replace("'", "");
        String column = numeric ? "digits" : "code", initials = numeric ? "initial_digits" : "initials";
        String where = (initialBranch ? initials : column) + "=?";
        List<String> args = new ArrayList<>(); args.add(plain);
        if (constraint != null && !constraint.leadingReading().isEmpty()) {
            String leading = constraint.leadingReading();
            if (constraint.leadingIsPrefix()) {
                if (initialBranch) return Collections.emptyList();
                where = "(" + column + ">=? AND " + column + "<?) AND syllables LIKE ?";
                args.clear(); args.add(plain); args.add(prefixUpperBound(plain)); args.add(leading + "%");
            } else {
                where += " AND (syllables=? OR syllables LIKE ?)";
                args.add(leading); args.add(leading + " %");
            }
        }
        // Recall characters separately: frequent phrases must not exhaust their scan budget.
        if (!singleCharacters) where = "(" + where + ") AND length(word)>1";
        List<PinyinDictionary.Entry> result = new ArrayList<>();
        try (Cursor cursor = select(singleCharacters ? "characters" : "pronunciations", new String[]{"word", "score", "syllables"},
                where, args.toArray(new String[0]), null, null, "score DESC, word", QueryWork.firstScreen() ? "48" : "1024")) {
            while (cursor.moveToNext()) {
                QueryWork.check(); String reading = cursor.getString(2);
                PinyinInput.Match match = constraint == null
                        ? PinyinInput.match(input, reading, true, true, true, true, false)
                        : constraint.match(reading, true, true, true, true, false);
                if (match == null) continue;
                boolean valid = true;
                for (String token : reading.split("[ ']+")) if (!PinyinPathEditor.isCompleteReading(token)) { valid = false; break; }
                if (valid) result.add(new PinyinDictionary.Entry(cursor.getString(0), cursor.getInt(1), match.tier)
                        .withInput(input.length(), reading));
            }
        }
        return result;
    }

    String pronunciation(String word) {
        try (Cursor cursor = select("pronunciations", new String[]{"syllables"},
                "word=?", new String[]{word}, null, null, "score DESC", "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    int transitionBonus(String previous, String next) {
        QueryWork.check();
        if (previous.isEmpty()) return 0;
        String key = previous.length() + ":" + previous + next;
        Integer cached = transitionCache.get(key);
        if (cached != null) return cached;
        int bonus;
        try (Cursor cursor = selectRaw("SELECT weight FROM word_transitions WHERE previous=? AND next=?", new String[]{previous, next})) {
            bonus = cursor.moveToFirst() ? Math.min(1500, (int)(180 * Math.log1p(cursor.getInt(0)))) : 0;
        }
        QueryWork.check();
        transitionCache.put(key, bonus);
        return bonus;
    }

    List<PinyinDictionary.Entry> queryPinyin(
            String code, int limit, boolean includeFull, boolean includeShort) {
        if (!includeFull && !includeShort) return new ArrayList<>();
        String kinds = includeFull && includeShort ? "kind IN (0,1,3)"
                : (includeFull ? "kind=0" : "kind IN (1,3)");
        return queryRanked("code", code, kinds, limit, false);
    }

    List<PinyinDictionary.Entry> queryPinyin(
            String code, int limit, boolean includeFull, boolean includeShort,
            boolean abbreviation) {
        if (!abbreviation || !includeFull || !includeShort)
            return queryPinyin(code, limit, includeFull, includeShort);
        ArrayList<PinyinDictionary.Entry> out = new ArrayList<>(limit * 2);
        // Fetch the user's exact initials before full-pinyin collisions so the
        // database limit cannot hide the candidates that match their input style.
        out.addAll(queryRanked("code", code, "kind IN (1,3)", limit, false));
        out.addAll(queryRanked("code", code, "kind=0", limit, false));
        return out;
    }

    List<PinyinDictionary.Entry> queryFuzzy(String code, int limit,
                                             boolean includeFull, boolean includeShort) {
        if (!includeFull && !includeShort) return new ArrayList<>();
        String kinds = includeFull && includeShort ? "kind IN (0,1,3)"
                : (includeFull ? "kind=0" : "kind IN (1,3)");
        return queryRanked("code", code, kinds, limit, true);
    }

    List<PinyinDictionary.Entry> queryExactPinyin(String code, int limit) {
        ArrayList<PinyinDictionary.Entry> out = new ArrayList<>();
        Cursor cursor = select("words", new String[]{"word", "score"},
                "kind=0 AND code=?", new String[]{code}, null, null,
                "score DESC, length(word) DESC", Integer.toString(limit));
        try {
            while (cursor.moveToNext()) out.add(new PinyinDictionary.Entry(
                    cursor.getString(0), cursor.getInt(1), PinyinDictionary.TIER_COMPOSED));
        } finally { cursor.close(); }
        return out;
    }

    List<PinyinDictionary.Entry> queryT9(
            String digits, int limit, boolean includeFull, boolean includeShort) {
        return queryT9(digits, limit, includeFull, includeShort, true);
    }
    List<PinyinDictionary.Entry> queryT9(
            String digits, int limit, boolean includeFull, boolean includeShort, boolean mixed) {
        if (!includeFull && !includeShort) return new ArrayList<>();
        String kinds = includeFull && includeShort ? "kind IN (0,1,3)"
                : (includeFull ? "kind=0" : "kind IN (1,3)");
        if (!mixed || !includeFull || !includeShort) kinds = includeFull && includeShort ? "kind IN (0,1)" : includeFull ? "kind=0" : "kind=1";
        return queryRanked("t9", digits, kinds, limit, false);
    }

    List<PinyinDictionary.Entry> queryEnglish(String prefix, int limit) {
        return queryRanked("code", prefix, "kind=2", limit, false);
    }

    List<String> queryT9Readings(String digits, int limit) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        Cursor cursor = select("pronunciations", new String[]{"syllables"},
                "digits>=? AND digits<?", new String[]{digits, prefixUpperBound(digits)},
                null, null, "CASE WHEN digits='" + sqlLiteral(digits)
                        + "' THEN 0 ELSE 1 END, score DESC", Integer.toString(limit * 4));
        try {
            while (cursor.moveToNext() && unique.size() < limit) { QueryWork.check(); unique.add(cursor.getString(0)); }
        } finally { cursor.close(); }
        return new ArrayList<>(unique);
    }

    private List<PinyinDictionary.Entry> queryRanked(
            String column, String value, String kinds, int limit, boolean fuzzy) {
        ArrayList<PinyinDictionary.Entry> out = new ArrayList<>();
        String exactTier = "CASE WHEN " + column + "='" + sqlLiteral(value)
                + "' THEN CASE kind WHEN 0 THEN " + PinyinDictionary.TIER_EXACT_FULL
                + " WHEN 1 THEN " + PinyinDictionary.TIER_EXACT_SHORT
                + " WHEN 3 THEN " + PinyinDictionary.TIER_MIXED + " ELSE "
                + PinyinDictionary.TIER_EXACT_FULL + " END ELSE "
                + PinyinDictionary.TIER_PREFIX + " END";
        Cursor cursor = select("words",
                new String[]{"word", "score", exactTier + " AS match_tier"},
                kinds + " AND " + column + ">=? AND " + column + "<?",
                new String[]{value, prefixUpperBound(value)},
                null, null, "match_tier, score DESC, length(word) DESC", Integer.toString(limit));
        try {
            while (cursor.moveToNext()) out.add(new PinyinDictionary.Entry(
                    cursor.getString(0), cursor.getInt(1), fuzzy
                            ? PinyinDictionary.TIER_FUZZY : cursor.getInt(2)));
        } finally { cursor.close(); }
        return out;
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    /** All normalized lookup codes are ASCII, so this is an index-friendly prefix bound. */
    private static String prefixUpperBound(String value) {
        return value + '\uffff';
    }
}
