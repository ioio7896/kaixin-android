"""Offline top-1/top-5 evaluation for labeled local IME candidate cases.

Build the lexicon database first with ``gradlew assembleDebug``. Each JSONL case
contains ``mode``, ``query``, ``expected`` and optional per-word ``personal``,
``context`` and ``pinned`` values. The script compares corpus-only ordering with
the current bounded personalization/context scoring without recording input.
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


def load_candidates(connection, mode, query):
    mode = "nine" if mode == "t9" else mode
    if mode == "nine":
        column, kinds = "t9", (0, 1, 3)
    elif mode == "english":
        column, kinds = "code", (2,)
    else:
        column, kinds = "code", (0, 1, 3)
    if not query:
        return []
    kind_clause = "kind IN (" + ",".join("?" for _ in kinds) + ")"
    upper = query + "\uffff"
    sql = (f"SELECT code, word, score, kind, {column} FROM words "
           f"WHERE {kind_clause} AND {column}>=? AND {column}<?")
    rows = connection.execute(sql, (*kinds, query, upper)).fetchall()
    best = {}
    for code, word, score, kind, value in rows:
        exact = value == query
        if mode == "english":
            tier = 10 if exact else 30
        elif exact and kind == 0:
            tier = 10
        elif exact and kind == 1:
            tier = 20
        elif exact and kind == 3:
            tier = 25
        else:
            tier = 30
        candidate = {"word": word, "score": score, "tier": tier}
        previous = best.get(word)
        if previous is None or (tier, -score) < (previous["tier"], -previous["score"]):
            best[word] = candidate
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
        tier = 0 if word in pinned else candidate["tier"]
        score = candidate["score"]
        if personalized:
            score += personal_adjustment(case, word)
            score += context_adjustment(case, word)
        char_tier = 0
        if mode == "nine" and len(query) <= 3:
            char_tier = 0 if len(word) == 1 else 1
        result.append((tier, char_tier, -score, -len(word), word))
    result.sort()
    return [entry[-1] for entry in result]


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
    baseline_top1 = baseline_top5 = adjusted_top1 = adjusted_top5 = 0
    changed = []
    for case in cases:
        mode = "nine" if case["mode"] == "t9" else case["mode"]
        query = normalize_query(mode, case["query"])
        candidates = load_candidates(connection, mode, query)
        expected = expected_words(case)
        baseline = rank(candidates, case, False)
        adjusted = rank(candidates, case, True)
        base1, base5 = hit(baseline, expected, 1), hit(baseline, expected, 5)
        tuned1, tuned5 = hit(adjusted, expected, 1), hit(adjusted, expected, 5)
        baseline_top1 += base1
        baseline_top5 += base5
        adjusted_top1 += tuned1
        adjusted_top5 += tuned5
        if (baseline[:5] != adjusted[:5]) or (base1 != tuned1):
            changed.append((case["mode"], case["query"], baseline[:3], adjusted[:3], expected))
    connection.close()
    total = len(cases)
    print(f"Cases: {total}")
    print(f"Corpus only: top-1 {percent(baseline_top1, total)}, top-5 {percent(baseline_top5, total)}, "
          f"first-choice error {percent(total - baseline_top1, total)}")
    print(f"Personal/context: top-1 {percent(adjusted_top1, total)}, top-5 {percent(adjusted_top5, total)}, "
          f"first-choice error {percent(total - adjusted_top1, total)}")
    for mode, query, before, after, expected in changed:
        print(f"{mode}:{query} expected={sorted(expected)} corpus={before} adjusted={after}")


if __name__ == "__main__":
    main()
