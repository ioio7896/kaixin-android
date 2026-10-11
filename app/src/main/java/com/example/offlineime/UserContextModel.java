package com.example.offlineime;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encrypted local bigram/trigram model with bounded, decaying influence. */
final class UserContextModel {
    private static final String TAG = "OfflineImeContext";
    private static final String PREFS = "context_model";
    private static final String ALIAS = "offline_ime_context";
    private static final int MAX_TRANSITIONS = 1_000;
    private static final long HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_BIGRAM_BONUS = 650;
    private static final int MAX_TRIGRAM_BONUS = 900;
    private final Context context;
    private final Map<String, Transition> transitions = new LinkedHashMap<>();
    private boolean loaded;
    private long loadGeneration;
    private volatile long scoringRevision;
    long scoringRevision() { return scoringRevision; }

    UserContextModel(Context context) {
        this(context, false);
    }

    UserContextModel(Context context, boolean deferLoading) {
        this.context = context.getApplicationContext();
        if (!deferLoading) {
            load(true);
            loaded = true;
        }
    }

    void loadDeferred() {
        final long generation;
        synchronized (this) {
            if (loaded) return;
            generation = loadGeneration;
        }
        // Read/decrypt into a private model without holding the UI's model lock.
        UserContextModel stored = new UserContextModel(context, true);
        boolean migrateLegacy = stored.load(false);
        synchronized (this) {
            if (loaded || generation != loadGeneration || Thread.currentThread().isInterrupted()) return;
            boolean learnedWhileLoading = !transitions.isEmpty();
            for (Map.Entry<String, Transition> item : transitions.entrySet()) {
                Transition pending = item.getValue();
                Transition previous = stored.transitions.get(item.getKey());
                stored.transitions.put(item.getKey(), new Transition(pending.trigram,
                        pending.previousPrevious, pending.previous, pending.next,
                        Math.min(1_000, pending.count + (previous == null ? 0 : previous.count)),
                        Math.max(pending.lastUsed, previous == null ? 0 : previous.lastUsed)));
            }
            transitions.clear();
            transitions.putAll(stored.transitions);
            scoringRevision++;
            trim();
            loaded = true;
            if (learnedWhileLoading || migrateLegacy) save();
        }
    }

    synchronized void cancelDeferredLoad() {
        loadGeneration++;
        loaded = true;
    }

    synchronized void learn(String previousPrevious, String previous, String next) {
        previousPrevious = normalize(previousPrevious);
        previous = normalize(previous);
        next = normalize(next);
        if (next.isEmpty()) return;
        scoringRevision++;
        long now = System.currentTimeMillis();
        if (!previous.isEmpty()) increment(false, previousPrevious, previous, next, now);
        if (!previousPrevious.isEmpty() && !previous.isEmpty()) {
            increment(true, previousPrevious, previous, next, now);
        }
        trim();
        // Early input is merged with the stored model after loading finishes.
        if (loaded) save();
    }

    private void increment(boolean trigram, String previousPrevious, String previous,
                           String next, long now) {
        String key = key(trigram, previousPrevious, previous, next);
        Transition current = transitions.get(key);
        int count = Math.min(1_000, current == null ? 1 : current.count + 1);
        transitions.put(key, new Transition(trigram, previousPrevious, previous, next, count, now));
    }

    synchronized void clear() {
        cancelDeferredLoad();
        transitions.clear();
        scoringRevision++;
        SecureLocalStore.clear(context, PREFS, ALIAS);
    }

    synchronized List<PinyinDictionary.Entry> rerank(
            String previousPrevious, String previous,
            List<PinyinDictionary.Entry> candidates, boolean preferSingleCharacter) {
        if (candidates == null || candidates.isEmpty()) return candidates;
        previousPrevious = normalize(previousPrevious);
        previous = normalize(previous);
        if (previous.isEmpty()) return candidates;
        final String prior2 = previousPrevious;
        final String prior1 = previous;
        final long now = System.currentTimeMillis();
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>(candidates.size());
        for (PinyinDictionary.Entry entry : candidates) {
            result.add(entry.withPersonalization(entry.personalBonus,
                    contextBonus(prior2, prior1, entry.word, now)));
        }
        // The service applies the single final ordering after every score source
        // (corpus, personal history and context) has contributed.
        return result;
    }
    synchronized Map<String, Integer> rankingScores(String prior2, String prior1, List<PinyinDictionary.Entry> candidates) {
        if (transitions.isEmpty() || prior1.isEmpty()) return java.util.Collections.emptyMap();
        prior2 = normalize(prior2); prior1 = normalize(prior1);
        long now = System.currentTimeMillis();
        Map<String, Integer> result = new java.util.HashMap<>();
        for (PinyinDictionary.Entry entry : candidates) {
            QueryWork.check();
            int bonus = contextBonus(prior2, prior1, entry.word, now);
            if (bonus != 0) result.put(entry.word, bonus);
        }
        return result;
    }

    synchronized List<PinyinDictionary.Entry> predict(
            String previousPrevious, String previous, int limit) {
        previousPrevious = normalize(previousPrevious);
        previous = normalize(previous);
        if (previous.isEmpty() || limit <= 0) return new ArrayList<>();
        long now = System.currentTimeMillis();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Transition transition : transitions.values()) {
            if (!transition.previous.equals(previous)) continue;
            int score = contextBonus(previousPrevious, previous, transition.next, now);
            if (score > scores.getOrDefault(transition.next, 0)) {
                scores.put(transition.next, score);
            }
        }
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>();
        for (Map.Entry<String, Integer> item : scores.entrySet()) {
            result.add(new PinyinDictionary.Entry(item.getKey(), item.getValue(),
                    PinyinDictionary.TIER_EXACT_FULL));
        }
        result.sort(Comparator.comparingInt((PinyinDictionary.Entry entry) -> entry.score)
                .reversed().thenComparing(entry -> entry.word));
        return result.size() <= limit ? result : new ArrayList<>(result.subList(0, limit));
    }

    private int contextBonus(String previousPrevious, String previous, String next, long now) {
        Transition bigram = transitions.get(key(false, "", previous, next));
        Transition trigram = previousPrevious.isEmpty() ? null
                : transitions.get(key(true, previousPrevious, previous, next));
        return bonus(bigram, MAX_BIGRAM_BONUS, 240, now)
                + bonus(trigram, MAX_TRIGRAM_BONUS, 320, now);
    }

    synchronized int transitionBonus(String previous, String next) {
        return contextBonus("", previous, next, System.currentTimeMillis());
    }

    private int bonus(Transition transition, int cap, int scale, long now) {
        if (transition == null) return 0;
        long age = Math.max(0L, now - transition.lastUsed);
        double decayedCount = transition.count * Math.pow(0.5, age / (double) HALF_LIFE_MS);
        return Math.min(cap, (int) Math.round(scale * Math.log1p(decayedCount)));
    }

    private void trim() {
        while (transitions.size() > MAX_TRANSITIONS) {
            String weakest = transitions.entrySet().stream()
                    .min(Comparator.comparingDouble(entry -> effectiveCount(entry.getValue(), System.currentTimeMillis())))
                    .map(Map.Entry::getKey).orElse(null);
            if (weakest == null) return;
            transitions.remove(weakest);
        }
    }

    private double effectiveCount(Transition transition, long now) {
        long age = Math.max(0L, now - transition.lastUsed);
        return transition.count * Math.pow(0.5, age / (double) HALF_LIFE_MS);
    }

    private boolean load(boolean persistMigration) {
        String text = SecureLocalStore.read(context, PREFS, ALIAS);
        boolean migrateLegacy = false;
        try {
            long now = System.currentTimeMillis();
            for (String line : text.split("\\n")) {
                String[] fields = line.split("\\t", -1);
                if (fields.length == 3) {
                    // Existing encrypted bigram records remain usable after upgrade.
                    int count = Integer.parseInt(fields[2]);
                    Transition legacy = new Transition(false, "", fields[0], fields[1], count,
                            Math.max(0L, now - HALF_LIFE_MS));
                    transitions.put(key(false, "", fields[0], fields[1]), legacy);
                    migrateLegacy = true;
                } else if (fields.length >= 6) {
                    boolean trigram = "T".equals(fields[0]);
                    Transition transition = new Transition(trigram, fields[1], fields[2], fields[3],
                            Math.max(0, Integer.parseInt(fields[4])),
                            Math.max(0L, Long.parseLong(fields[5])));
                    transitions.put(key(trigram, transition.previousPrevious,
                            transition.previous, transition.next), transition);
                }
            }
            trim();
        } catch (RuntimeException error) {
            Log.e(TAG, "context model parse failed: " + error.getClass().getSimpleName());
            transitions.clear();
            migrateLegacy = false;
        }
        if (migrateLegacy && persistMigration) save();
        return migrateLegacy;
    }

    private void save() {
        StringBuilder text = new StringBuilder();
        for (Transition transition : transitions.values()) {
            text.append(transition.trigram ? "T" : "B").append('\t')
                    .append(transition.previousPrevious).append('\t')
                    .append(transition.previous).append('\t')
                    .append(transition.next).append('\t')
                    .append(transition.count).append('\t')
                    .append(transition.lastUsed).append('\n');
        }
        SecureLocalStore.write(context, PREFS, ALIAS, text.toString());
    }

    private static String key(boolean trigram, String previousPrevious,
                              String previous, String next) {
        return (trigram ? "T\t" + previousPrevious : "B") + '\t' + previous + '\t' + next;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').trim();
    }

    private static final class Transition {
        final boolean trigram;
        final String previousPrevious;
        final String previous;
        final String next;
        final int count;
        final long lastUsed;

        Transition(boolean trigram, String previousPrevious, String previous,
                   String next, int count, long lastUsed) {
            this.trigram = trigram;
            this.previousPrevious = normalize(previousPrevious);
            this.previous = normalize(previous);
            this.next = normalize(next);
            this.count = count;
            this.lastUsed = lastUsed;
        }
    }
}
