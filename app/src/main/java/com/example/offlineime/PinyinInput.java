package com.example.offlineime;

import java.util.*;

/** Bounded, corpus-independent full/initial/mixed input parser. */
final class PinyinInput {
    static final int MAX_SYLLABLES = 32;
    static final int MAX_PATHS = 32;
    private static final Set<String> SYLLABLES = new HashSet<>(Arrays.asList(PinyinLexer.STANDARD_SYLLABLES));
    private static final Set<String> SYLLABLE_PREFIXES = new HashSet<>();
    private static final Set<String> NINE_SPELLINGS = new HashSet<>();
    private static final Map<String, List<Path>> PATH_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, List<Path>>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Path>> entry) {
                    return size() > 256;
                }
            });
    private static final ByteBudgetCache<ParseState> INCREMENTAL = new ByteBudgetCache<>(2 * 1024 * 1024, 16, state -> {
        int bytes = 64 + state.input.length() * 2;
        for (List<Path> paths : state.states) for (Path path : paths)
            bytes += 96 + path.tokens.size() * 48;
        return bytes;
    });
    private static final class ParseState {
        final String input;
        final List<List<Path>> states;
        ParseState(String input, List<List<Path>> states) { this.input = input; this.states = states; }
    }
    private static final Comparator<Path> PATH_ORDER = Comparator.comparingInt((Path p) -> p.cost)
            .thenComparingInt(p -> p.tokens.size()).thenComparing(p -> p.spelling);
    static {
        for (String syllable : SYLLABLES) {
            NINE_SPELLINGS.add(PinyinDictionary.toT9(syllable));
            for (int length = 1; length <= syllable.length(); length++)
                SYLLABLE_PREFIXES.add(syllable.substring(0, length));
        }
    }

    static String normalize(String input) {
        if (input == null) return "";
        boolean plain = !input.isEmpty() && input.charAt(0) != '\'';
        for (int i = 0; i < input.length() && plain; i++) {
            char ch = input.charAt(i);
            plain = ch >= 'a' && ch <= 'z' || ch == '\'' && (i == 0 || input.charAt(i - 1) != '\'');
        }
        if (plain) return input;
        return input.toLowerCase(Locale.ROOT).replace("u:", "v").replace('ü', 'v')
                .replace('’', '\'').replaceAll("[^a-z']", "").replaceAll("'{2,}", "'")
                .replaceAll("^'", "");
    }

    static final class Path {
        final List<String> tokens;
        final String initials;
        final int cost;
        final String spelling;
        private Path(List<String> tokens, String initials, int cost) {
            // The parser owns each token list and never changes it after publication.
            this.tokens = Collections.unmodifiableList(tokens);
            this.initials = initials; this.cost = cost;
            this.spelling = String.join("'", tokens);
        }
    }

    static List<Path> paths(String input, boolean full, boolean shortInput, boolean doubles) {
        String q = normalize(input);
        if (q.isEmpty() || q.length() > 192 || (!full && !shortInput)) return Collections.emptyList();
        QueryWork.check();
        String key = "p:" + full + ':' + shortInput + ':' + doubles + ':' + q;
        List<Path> cached = PATH_CACHE.get(key);
        if (cached != null) return cached;
        return cachePaths(key, parsePaths(q, full, shortInput, doubles));
    }

    private static List<Path> parsePaths(String q, boolean full, boolean shortInput, boolean doubles) {
        if (full && q.indexOf('\'') >= 0) {
            List<String> forced = Arrays.asList(q.replaceAll("'+$", "").split("'"));
            if (forced.size() <= MAX_SYLLABLES && SYLLABLES.containsAll(forced)) {
                StringBuilder initials = new StringBuilder(); for (String token : forced) initials.append(token.charAt(0));
                return Collections.singletonList(new Path(forced, initials.toString(), 0));
            }
        }
        List<List<Path>> states = new ArrayList<>();
        for (int i = 0; i <= q.length(); i++) states.add(new ArrayList<>());
        states.get(0).add(new Path(Collections.emptyList(), "", 0));
        String stateKey = "p:" + full + ':' + shortInput + ':' + doubles;
        int stable = restorePrefix(stateKey, q, states, true);
        for (int end = stable + 1; end <= q.length(); end++) {
            QueryWork.check();
            if (q.charAt(end - 1) == '\'') {
                for (Path path : states.get(end - 1)) offer(states.get(end), path);
                continue;
            }
            for (int at = Math.max(0, end - 6); at < end; at++) {
                String token = q.substring(at, end);
                if (token.indexOf('\'') >= 0) continue;
                boolean complete = full && SYLLABLES.contains(token);
                boolean initial = shortInput && SYLLABLE_PREFIXES.contains(token) && (token.length() == 1
                        || doubles && (token.equals("zh") || token.equals("ch") || token.equals("sh")));
                boolean tail = full && end == q.length() && isPrefix(token);
                if (!complete && !initial && !tail) continue;
                int cost = complete ? 0 : initial ? 3 : 5;
                for (Path prior : states.get(at)) {
                    if (prior.tokens.size() >= MAX_SYLLABLES) continue;
                    List<String> tokens = new ArrayList<>(prior.tokens); tokens.add(token);
                    offer(states.get(end), new Path(tokens, prior.initials + token.charAt(0), prior.cost + cost));
                }
            }
        }
        QueryWork.check(); INCREMENTAL.put(stateKey, new ParseState(q, states));
        return states.get(q.length());
    }

    private static boolean isPrefix(String token) {
        return SYLLABLE_PREFIXES.contains(token);
    }

    static List<String> numericKeys(String digits, boolean full, boolean shortInput, boolean doubles) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Path path : numericPaths(digits, full, shortInput, doubles)) keys.add(path.initials);
        return new ArrayList<>(keys);
    }
    static List<Path> numericPaths(String digits, boolean full, boolean shortInput, boolean doubles) {
        if (digits.isEmpty() || digits.length() > 192 || (!full && !shortInput)) return Collections.emptyList();
        QueryWork.check();
        String key = "9:" + full + ':' + shortInput + ':' + doubles + ':' + digits;
        List<Path> cached = PATH_CACHE.get(key);
        if (cached != null) return cached;
        return cachePaths(key, parseNumericPaths(digits, full, shortInput, doubles));
    }

    private static List<Path> cachePaths(String key, List<Path> paths) {
        QueryWork.check();
        List<Path> result = Collections.unmodifiableList(new ArrayList<>(paths));
        PATH_CACHE.put(key, result);
        return result;
    }

    private static List<Path> parseNumericPaths(String digits, boolean full, boolean shortInput, boolean doubles) {
        if (digits.indexOf('\'') >= 0) {
            List<String> tokens = Arrays.asList(digits.split("'")); StringBuilder initials = new StringBuilder();
            for (String token : tokens) { if (token.isEmpty()) return Collections.emptyList(); initials.append(token.charAt(0)); }
            return tokens.size() > MAX_SYLLABLES ? Collections.emptyList() : Collections.singletonList(new Path(tokens, initials.toString(), 0));
        }
        List<List<Path>> states = new ArrayList<>();
        for (int i = 0; i <= digits.length(); i++) states.add(new ArrayList<>());
        states.get(0).add(new Path(Collections.emptyList(), "", 0));
        String stateKey = "9:" + full + ':' + shortInput + ':' + doubles;
        int stable = restorePrefix(stateKey, digits, states, false);
        for (int end = stable + 1; end <= digits.length(); end++) {
            QueryWork.check();
            for (int at = Math.max(0, end - 6); at < end; at++) {
                String token = digits.substring(at, end);
                boolean complete = full && NINE_SPELLINGS.contains(token);
                boolean initial = shortInput && (token.length() == 1 || doubles && (token.equals("93") || token.equals("24") || token.equals("74")));
                if (!complete && !initial) continue;
                for (Path prior : states.get(at)) {
                    if (prior.tokens.size() >= MAX_SYLLABLES) continue;
                    List<String> tokens = new ArrayList<>(prior.tokens); tokens.add(token);
                    offer(states.get(end), new Path(tokens, prior.initials + token.charAt(0), prior.cost + (complete ? 0 : 3)));
                }
            }
        }
        QueryWork.check(); INCREMENTAL.put(stateKey, new ParseState(digits, states));
        return states.get(digits.length());
    }

    private static int restorePrefix(String key, String input, List<List<Path>> states, boolean hasIncompleteTail) {
        ParseState previous = INCREMENTAL.get(key);
        if (previous == null) return 0;
        int common = 0;
        while (common < Math.min(previous.input.length(), input.length())
                && previous.input.charAt(common) == input.charAt(common)) common++;
        // A trailing incomplete syllable may become complete or move into the middle after an append.
        int stable = hasIncompleteTail ? Math.max(0, common - 6) : common;
        for (int i = 0; i <= stable; i++) states.set(i, previous.states.get(i));
        return stable;
    }

    private static void offer(List<Path> paths, Path value) {
        for (Path existing : paths) if (existing.tokens.equals(value.tokens)) return;
        int position = 0;
        while (position < paths.size() && PATH_ORDER.compare(paths.get(position), value) <= 0) position++;
        if (position >= MAX_PATHS) return;
        paths.add(position, value);
        if (paths.size() > MAX_PATHS) paths.remove(paths.size() - 1);
    }

    /** Share the bounded decoding budget across syllable counts instead of only
     * trying the shortest ambiguous splits (especially with nine-key input). */
    static List<Path> compositionPaths(List<Path> paths, int limit) {
        Map<Integer, List<Path>> groups = new LinkedHashMap<>();
        for (Path path : paths) {
            if (path.tokens.size() < 2) continue;
            groups.computeIfAbsent(path.tokens.size(), key -> new ArrayList<>()).add(path);
        }
        List<Path> selected = new ArrayList<>();
        for (int round = 0; selected.size() < limit; round++) {
            boolean added = false;
            for (List<Path> group : groups.values()) {
                if (round >= group.size()) continue;
                selected.add(group.get(round)); added = true;
                if (selected.size() >= limit) break;
            }
            if (!added) break;
        }
        return selected;
    }

    static final class Match {
        final int tier, consumed;
        Match(int tier, int consumed) { this.tier = tier; this.consumed = consumed; }
    }

    static boolean isAbbreviationQuery(String input, boolean full) {
        if (!full) return true;
        if (input == null || input.isEmpty() || input.indexOf('\'') >= 0 || input.matches("[2-9]+")) return false;
        for (Path path : paths(input, true, false, false)) if (path.cost == 0) return false;
        return true;
    }

    /** Match against an actual pronunciation, preserving every explicit boundary. */
    static Match match(String input, String pronunciation, boolean full, boolean shortInput,
                       boolean mixed, boolean doubles, boolean prefix) {
        String q = input != null && input.matches("[2-9']+") ? input : normalize(input);
        String[] syllables = pronunciation.split("[ '\\t]+");
        if (q.isEmpty() || syllables.length == 0 || syllables.length > MAX_SYLLABLES) return null;
        return match(q, syllables, 0, 0, full, shortInput, mixed, doubles, prefix, false, false, new HashSet<>());
    }

    private static Match match(String q, String[] syllables, int at, int index, boolean full,
                               boolean shortInput, boolean mixed, boolean doubles, boolean prefix,
                               boolean usedFull, boolean usedShort, Set<String> visited) {
        QueryWork.check();
        String state = at + ":" + index + ":" + usedFull + ":" + usedShort;
        if (!visited.add(state)) return null;
        if (at < q.length() && q.charAt(at) == '\'') at++;
        if (at == q.length()) {
            if (index < syllables.length && !prefix) return null;
            return new Match(index < syllables.length ? PinyinDictionary.TIER_PREFIX
                    : usedFull && usedShort ? PinyinDictionary.TIER_MIXED
                    : usedShort ? PinyinDictionary.TIER_EXACT_SHORT : PinyinDictionary.TIER_EXACT_FULL, q.length());
        }
        if (index >= syllables.length) return null;
        String syllable = syllables[index];
        if (syllable.isEmpty()) return null;
        boolean numeric = q.charAt(0) >= '2' && q.charAt(0) <= '9';
        String spelling = numeric ? PinyinDictionary.toT9(syllable) : syllable;
        if (full && (mixed || !usedShort) && q.startsWith(spelling, at)) {
            Match value = match(q, syllables, at + spelling.length(), index + 1, full, shortInput,
                    mixed, doubles, prefix, true, usedShort, visited);
            if (value != null) return value;
        }
        if (shortInput && (mixed || !usedFull)) {
            String initial = syllable.substring(0, 1);
            if (numeric) initial = PinyinDictionary.toT9(initial);
            if (doubles && (syllable.startsWith("zh") || syllable.startsWith("ch") || syllable.startsWith("sh"))) {
                String pair = syllable.substring(0, 2);
                if (numeric) pair = PinyinDictionary.toT9(pair);
                if (q.startsWith(pair, at)) {
                    Match value = match(q, syllables, at + 2, index + 1, full, shortInput,
                            mixed, doubles, prefix, usedFull, true, visited);
                    if (value != null) return value;
                }
            }
            if (q.startsWith(initial, at)) {
                Match value = match(q, syllables, at + 1, index + 1, full, shortInput,
                        mixed, doubles, prefix, usedFull, true, visited);
                if (value != null) return value;
            }
        }
        if (prefix && full && spelling.startsWith(q.substring(at)) && (mixed || !usedShort))
            return new Match(PinyinDictionary.TIER_PREFIX, q.length());
        return null;
    }
}
