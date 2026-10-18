"""Build the immutable Android lookup database from bundled UTF-8 lexicons."""
from pathlib import Path
import re
import sqlite3
import sys

root = Path(__file__).resolve().parents[1]
lexicon = root / "lexicon"
corrections = root / "app/src/main/assets/lexicon-corrections"
# The loader is the single source of truth for bundled upgrade metadata.
dictionary_source = root / "app/src/main/java/com/example/offlineime/PinyinDictionary.java"
version_match = re.search(r"LEXICON_VERSION\s*=\s*(\d+)", dictionary_source.read_text(encoding="utf-8"))
if version_match is None:
    raise ValueError("PinyinDictionary has no lexicon version")
lexicon_version = version_match.group(1)
output = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build/lexicon-source/pinyin.db"
output.parent.mkdir(parents=True, exist_ok=True)
if output.exists():
    output.unlink()

def t9(code: str) -> str:
    table = str.maketrans("abcdefghijklmnopqrstuvwxyz", "22233344455566677778889999")
    return code.translate(table)

score_caps = {}
prefix_caps = {}
source_scales = {}
word_caps = {}
excluded = set()
with (corrections / "rules.tsv").open(encoding="utf-8") as rules:
    for line in rules:
        if not line.strip() or line.startswith("#"):
            continue
        columns = line.rstrip("\n").split("\t")
        action = columns[0]
        if action == "cap" and len(columns) == 3:
            score_caps[columns[1]] = int(columns[2])
        elif action == "cap-prefix" and len(columns) == 3:
            prefix_caps[columns[1]] = int(columns[2])
        elif action == "scale-prefix" and len(columns) == 3:
            percent = int(columns[2])
            if not 1 <= percent <= 100:
                raise ValueError(f"Invalid source scale: {line!r}")
            source_scales[columns[1]] = percent
        elif action == "cap-word" and len(columns) == 4:
            word_caps[(columns[1], columns[2])] = int(columns[3])
        elif action == "exclude" and len(columns) == 3:
            excluded.add((columns[1], columns[2]))
        else:
            raise ValueError(f"Invalid lexicon correction: {line!r}")


def rows(path: Path, source_name: str = ""):
    with path.open(encoding="utf-8") as source:
        for line in source:
            if not line or line.startswith("#"):
                continue
            columns = line.rstrip("\n").split("\t")
            if len(columns) < 2:
                continue
            word, raw = columns[0].strip(), columns[1].strip().lower()
            if (source_name, word) in excluded:
                continue
            raw = raw.replace("ü", "v").replace("u:", "v")
            code = raw.replace(" ", "").replace("'", "").strip()
            if not word or not code:
                continue
            try:
                # Hot overlays keep heat separate from the base frequency in column four.
                score_column = 3 if path.name.startswith("life_short_hot_") and len(columns) > 3 else 2
                score = int(columns[score_column].strip()) if len(columns) > score_column else 1
            except ValueError:
                score = 1
            scale = 100
            matching_scales = (prefix for prefix in source_scales if source_name.startswith(prefix))
            longest = max(matching_scales, key=len, default=None)
            if longest is not None:
                scale = source_scales[longest]
            score = max(1, (score * scale + 50) // 100)
            cap = score_caps.get(source_name, score)
            for prefix, limit in prefix_caps.items():
                if source_name.startswith(prefix):
                    cap = min(cap, limit)
            cap = min(cap, word_caps.get((source_name, word), cap))
            score = min(score, cap)
            yield word, raw, code, score

db = sqlite3.connect(output)
db.executescript("""
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
CREATE TABLE words(code TEXT NOT NULL, word TEXT NOT NULL, score INTEGER NOT NULL,
                   kind INTEGER NOT NULL, t9 TEXT NOT NULL DEFAULT '',
                   PRIMARY KEY(code,word,kind));
CREATE TABLE pronunciations(code TEXT NOT NULL, syllables TEXT NOT NULL, initials TEXT NOT NULL, word TEXT NOT NULL, score INTEGER NOT NULL, digits TEXT NOT NULL DEFAULT '', initial_digits TEXT NOT NULL DEFAULT '', PRIMARY KEY(code,syllables,word));
CREATE TABLE metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE word_transitions(previous TEXT NOT NULL, next TEXT NOT NULL, weight INTEGER NOT NULL, PRIMARY KEY(previous,next));
""")
insert = ("INSERT INTO words(code,word,score,kind,t9) VALUES(?,?,?,?,?) "
          "ON CONFLICT(code,word,kind) DO UPDATE SET score=MAX(words.score,excluded.score)")
count = 0


def mixed_codes(syllables):
    """Full spelling for the first syllables, initials for the remainder."""
    if not 3 <= len(syllables) <= 8:
        return ()
    return tuple("".join(syllables[:cut]) + "".join(s[0] for s in syllables[cut:])
                 for cut in range(1, min(3, len(syllables) - 1) + 1))

pronunciation_insert = ("INSERT INTO pronunciations(code,syllables,initials,word,score) VALUES(?,?,?,?,?) "
                        "ON CONFLICT(code,syllables,word) DO UPDATE SET score=MAX(score,excluded.score)")
chinese = list((lexicon / "core").glob("*.txt"))
chinese += [p for p in (lexicon / "zh").glob("*.txt") if p.name != "single_char_common_8105.txt"]
chinese += list((lexicon / "zh-ext").glob("*.txt"))
chinese_sources = [(path, path.relative_to(lexicon).as_posix()) for path in chinese]
chinese_sources += [(corrections / name, "lexicon-corrections/" + name)
                    for name in ("idioms.txt", "organizations.txt")]
for path, source_name in chinese_sources:
    batch = []
    phrase_batch = []
    for word, raw, code, score in rows(path, source_name):
        batch.append((code, word, score, 0, t9(code)))
        syllables = [s for s in re.split(r"[\s']+", raw) if s]
        initials = "".join(s[0] for s in syllables)
        phrase_batch.append((code, " ".join(syllables), initials, word, score))
        if initials and initials != code:
            batch.append((initials, word, score, 1, t9(initials)))
        if score >= 4000:
            for mixed in mixed_codes(syllables):
                if mixed != code and mixed != initials:
                    batch.append((mixed, word, score, 3, t9(mixed)))
        count += 1
    db.executemany(insert, batch)
    db.executemany(pronunciation_insert, phrase_batch)

for path in (lexicon / "en").glob("*.txt"):
    db.executemany(insert, ((code, word, score, 2, "") for word, raw, code, score in rows(path)))

single_scores = {}
with (lexicon / "zh/single_char_common_8105.txt").open(encoding="utf-8") as source:
    for line in source:
        if line.strip() and not line.startswith("#"):
            word, score = line.rstrip("\n").split("\t")
            single_scores[word] = int(score)

# This reading index was generated from the desktop project's pinned pinyin
# data and pronunciation overrides. Frequencies always come from the mirrored
# desktop lexicon above, so the index cannot introduce another word corpus.
single_readings = root / "data_sources/desktop_single_char_readings.txt"
batch = []
covered = set()
for word, reading, _, _ in rows(single_readings):
    if word not in single_scores:
        raise ValueError(f"reading index contains a character absent from desktop lexicon: {word}")
    covered.add(word)
    batch.append((reading, word, single_scores[word], 0, t9(reading)))
if covered != single_scores.keys():
    raise ValueError(f"desktop single-character readings missing {len(single_scores.keys() - covered)} characters")
db.executemany(insert, batch)
db.executemany(pronunciation_insert, ((code, code, code[0], word, score)
                                      for code, word, score, kind, digits in batch))

db.executescript("""
CREATE INDEX idx_words_kind_code_score ON words(kind,code,score DESC);
CREATE INDEX idx_words_kind_t9_score ON words(kind,t9,score DESC);
CREATE INDEX idx_pron_initials ON pronunciations(initials,score DESC,word,syllables);
CREATE INDEX idx_pron_digits ON pronunciations(digits,score DESC,word,syllables);
CREATE INDEX idx_pron_initial_digits ON pronunciations(initial_digits,score DESC,word,syllables);
CREATE INDEX idx_pron_code ON pronunciations(code,score DESC,word,syllables);
CREATE INDEX idx_pron_word ON pronunciations(word,score DESC);
PRAGMA user_version=8;
ANALYZE;
""")
entries = db.execute("SELECT count(*) FROM words").fetchone()[0]
db.executemany("UPDATE pronunciations SET digits=?,initial_digits=? WHERE code=? AND syllables=? AND word=?",
               ((t9(code),t9(initials),code,reading,word) for code,reading,initials,word in
                list(db.execute("SELECT code,syllables,initials,word FROM pronunciations"))))

# Independent character table avoids competing with phrase scan budgets.
db.executescript("""
CREATE TABLE characters AS SELECT * FROM pronunciations WHERE length(word)=1;
CREATE INDEX idx_char_code ON characters(code,score DESC,word,syllables);
CREATE INDEX idx_char_digits ON characters(digits,score DESC,word,syllables);
CREATE INDEX idx_char_initials ON characters(initials,score DESC,word,syllables);
CREATE INDEX idx_char_initial_digits ON characters(initial_digits,score DESC,word,syllables);
""")

# Derive bounded word bigrams from the same licensed phrase corpus. Every edge
# is backed by a phrase containing both words; no network/model is required.
frequencies = dict(db.execute("SELECT word,MAX(score) FROM pronunciations GROUP BY word"))
edges = {}
for phrase, score in frequencies.items():
    if not 3 <= len(phrase) <= 16:
        continue
    for boundary in range(1, len(phrase)):
        left = next((phrase[start:boundary] for start in range(max(0,boundary-6), boundary)
                     if phrase[start:boundary] in frequencies), None)
        right = next((phrase[boundary:end] for end in range(min(len(phrase),boundary+6),boundary,-1)
                      if phrase[boundary:end] in frequencies), None)
        if left and right:
            edge = (left,right)
            edges[edge] = min(1000000, edges.get(edge,0) + max(1,int(score ** .5)))
db.executemany("INSERT INTO word_transitions VALUES(?,?,?)", ((a,b,w) for (a,b),w in edges.items()))
db.executemany("INSERT INTO metadata(name,value) VALUES(?,?)",
               (("lexicon_version", lexicon_version), ("entry_count", str(entries))))
db.execute("ANALYZE")
db.commit()
db.execute("VACUUM")
db.close()
print(f"Built {output} with {entries} indexed rows")
