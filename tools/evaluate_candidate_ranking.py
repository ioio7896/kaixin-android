"""Read-only candidate review against the shipped pronunciation database.

Models bounded exact/full/short/mixed recall and the main ranking buckets.
Composition, typo/touch correction and live user history require device review.
"""

import argparse
import json
import math
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB = ROOT / "build" / "lexicon-source" / "pinyin.db"
PERSONAL_HALF_LIFE_DAYS = 21.0
SKIP_HALF_LIFE_DAYS = 14.0
CONTEXT_HALF_LIFE_DAYS = 30.0


def normalize_query(mode, query):
    value = str(query).lower().replace(" ", "").replace("'", "")
    if mode in ("nine", "t9"):
        return "".join(character for character in value if character in "23456789")
    if mode == "english":
        return "".join(character for character in value if "a" <= character <= "z")
    return value


T9 = str.maketrans("abcdefghijklmnopqrstuvwxyz", "22233344455566677778889999")


def reading_code(reading, mode, kind):
    syllables = reading.split()
    if kind == 0:
        code = "".join(syllables)
    elif kind == 1:
        code = "".join(part[0] for part in syllables)
    else:
        code = "".join(syllables[:1]) + "".join(part[0] for part in syllables[1:])
    return code.translate(T9) if mode == "nine" else code


def mixed_codes(reading, mode):
    syllables = reading.split()
    result = []
    for cut in range(1, min(3, len(syllables) - 1) + 1):
        code = "".join(syllables[:cut]) + "".join(part[0] for part in syllables[cut:])
        result.append(code.translate(T9) if mode == "nine" else code)
    return result


def load_candidates(connection, mode, query, first_screen):
    """Use runtime pronunciation rows and exact-before-prefix scan limits."""
    if not query:
        return []
    full_column = "digits" if mode == "nine" else "code"
    short_column = "initial_digits" if mode == "nine" else "initials"
    limit = 24 if first_screen else 64
    best = {}

    def rows(column, amount, prefix=False):
        condition = f"{column}>=? AND {column}<?" if prefix else f"{column}=?"
        args = (query, query + "\uffff") if prefix else (query,)
        return connection.execute(
            "SELECT word,score,syllables FROM pronunciations WHERE " + condition
            + " ORDER BY score DESC,word LIMIT ?", (*args, amount)).fetchall()

    def mixed(amount, prefix=False):
        column = "t9" if mode == "nine" else "code"
        condition = f"w.{column}>=? AND w.{column}<?" if prefix else f"w.{column}=?"
        args = (query, query + "\uffff") if prefix else (query,)
        return connection.execute(
            f"SELECT p.word,p.score,p.syllables,w.{column} FROM words w JOIN pronunciations p"
            " ON p.word=w.word WHERE w.kind=3 AND " + condition
            + " ORDER BY p.score DESC,p.word LIMIT ?", (*args, amount)).fetchall()

    def add(source, kind, prefix=False):
        for item in source:
            word, score, reading = item[:3]
            code = item[3] if len(item) > 3 else reading_code(reading, mode, kind)
            if kind == 3 and code not in mixed_codes(reading, mode):
                continue
            if not (code.startswith(query) if prefix else code == query):
                continue
            tier = 30 if prefix else (10 if kind == 0 else 20 if kind == 1 else 25)
            candidate = {"word": word, "score": score, "tier": tier, "reading": reading}
            previous = best.get(word)
            if previous is None or (tier, -score) < (previous["tier"], -previous["score"]):
                best[word] = candidate

    add(rows(full_column, max(24, min(64, limit * 2))), 0)
    add(rows(short_column, max(96, min(256, limit * 4))), 1)
    add(mixed(max(96, min(256, limit * 4))), 3)
    if len(best) < 15:
        add(rows(full_column, max(24, min(64, limit * 2)), True), 0, True)
        add(rows(short_column, max(64, min(192, limit * 3)), True), 1, True)
        add(mixed(max(64, min(192, limit * 3)), True), 3, True)
    return list(best.values())


def decay(days, half_life):
    return math.pow(0.5, max(0.0, float(days)) / half_life)


def personal_adjustment(case, word):
    personal = case.get("personal", {}).get(word, {})
    selected = min(30, max(0, int(personal.get("count", 0))))
    selected_days = personal.get("days", 0)
    positive = round(1200 * min(1.0, math.log1p(selected) / math.log1p(30))
                     * decay(selected_days, PERSONAL_HALF_LIFE_DAYS))
    skipped = min(8, max(0, int(personal.get("skipped", 0))))
    skipped_days = personal.get("skip_days", 0)
    negative = round(320 * min(1.0, math.log1p(skipped) / math.log1p(8))
                     * decay(skipped_days, SKIP_HALF_LIFE_DAYS))
    return positive - negative


def context_adjustment(case, word):
    context = case.get("context", {}).get(word, {})
    days = context.get("days", 0)
    bigram = max(0, int(context.get("bigram", 0))) * decay(days, CONTEXT_HALF_LIFE_DAYS)
    trigram = max(0, int(context.get("trigram", 0))) * decay(days, CONTEXT_HALF_LIFE_DAYS)
    return min(650, round(240 * math.log1p(bigram))) + min(900, round(320 * math.log1p(trigram)))


def rank(candidates, case, personalized):
    mode = "nine" if case["mode"] == "t9" else case["mode"]
    query = normalize_query(mode, case["query"])
    pinned = set(case.get("pinned", [])) if personalized else set()
    result = []
    for candidate in candidates:
        word = candidate["word"]
        tier = candidate["tier"]
        corpus = candidate["score"]
        personal = personal_adjustment(case, word) if personalized else 0
        context = context_adjustment(case, word) if personalized else 0
        score = round(1000 * math.log1p(max(0, corpus) / 1000))
        score += max(-640, min(1200, personal)) // 3
        score += max(0, min(1550, context)) * 450 // 1550
        if corpus < 4000 and len(word) > 2 and any("\u4e00" <= ch <= "\u9fff" for ch in word):
            score -= min(320, (len(word) - 2) * 80)
        score -= 700 if tier == 30 else 120 if tier in (20, 25) else 0
        common_short = tier != 30 and len(word) in (2, 3) and corpus >= 4000
        common_char = tier != 30 and len(word) == 1 and corpus >= 4000
        prefer_single = mode == "nine" and len(query) == 1
        prefer_two = len(query) == 3 and query.isalpha()
        order = (0 if prefer_single and len(word) == 1 else 1 if prefer_single else 0,
                 0 if word in pinned else 1,
                 0 if common_char else 1,
                 1 if tier == 30 else 0,
                 0 if common_short else 1,
                 0 if prefer_two and common_short and len(word) == 2 else 1,
                 -score, tier, -corpus, word)
        result.append((order, word))
    result.sort()
    return [entry[1] for entry in result]


def expected_words(case):
    expected = case.get("expected", [])
    if isinstance(expected, str):
        return {expected}
    return set(expected)


def hit(ranked, expected, limit):
    return any(word in expected for word in ranked[:limit])


def load_cases(path):
    cases = []
    with Path(path).open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            try:
                case = json.loads(line)
                if not {"mode", "query", "expected"}.issubset(case):
                    raise ValueError("requires mode, query and expected")
                if case["mode"] == "english":
                    continue  # English suggestions use a different runtime path.
                cases.append(case)
            except (json.JSONDecodeError, ValueError) as error:
                raise SystemExit(f"{path}:{line_number}: {error}")
    return cases


def percent(value, total):
    return "n/a" if total == 0 else f"{100.0 * value / total:.1f}%"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", help="UTF-8 JSONL file with expected candidate choices")
    parser.add_argument("--db", default=str(DEFAULT_DB), help="generated pinyin.db from lexicon/")
    args = parser.parse_args()
    cases = load_cases(args.cases)
    connection = sqlite3.connect(args.db)
    baseline_top1 = adjusted_top1 = top5_miss = not_recalled = top_swap = 0
    changed = []
    for case in cases:
        mode = "nine" if case["mode"] == "t9" else case["mode"]
        query = normalize_query(mode, case["query"])
        first_candidates = load_candidates(connection, mode, query, True)
        candidates = load_candidates(connection, mode, query, False)
        expected = expected_words(case)
        baseline = rank(candidates, case, False)
        adjusted = rank(candidates, case, True)
        first = rank(first_candidates, case, True)
        baseline_top1 += hit(baseline, expected, 1)
        adjusted_top1 += hit(adjusted, expected, 1)
        top5_miss += not hit(adjusted, expected, 5)
        not_recalled += not hit(adjusted, expected, len(adjusted))
        swapped = bool(first and adjusted and first[0] != adjusted[0])
        top_swap += swapped
        if not hit(adjusted, expected, 1) or swapped:
            changed.append((case["mode"], case["query"], first[:5], adjusted[:5], expected))
    connection.close()
    total = len(cases)
    print(f"Cases: {total}")
    print(f"Corpus top-1 {percent(baseline_top1, total)}; adjusted top-1 {percent(adjusted_top1, total)}")
    print(f"First-choice error {percent(total - adjusted_top1, total)}; "
          f"top-5 miss {percent(top5_miss, total)}; "
          f"not recalled {percent(not_recalled, total)}; "
          f"first/full top swap {percent(top_swap, total)}")
    for mode, query, first, full, expected in changed:
        print(f"{mode}:{query} expected={sorted(expected)} first={first} full={full}")
    print("Scope: pronunciation corpus exact/initial/mixed/prefix recall and main ranking buckets; "
          "composition, corrections and live history require device review.")


if __name__ == "__main__":
    main()
