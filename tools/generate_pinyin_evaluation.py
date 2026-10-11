"""Reproducible corpus-derived evaluation; separate from curated adversarial tests."""
import random
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
db = sqlite3.connect(root / "build/lexicon-source/pinyin.db")
pool = [(word, reading.split()) for word, reading in db.execute(
    "SELECT word,syllables FROM pronunciations WHERE score>=4000 ORDER BY word,syllables")
    if re.fullmatch(r"[\u4e00-\u9fff]{2,8}", word)
    and len(word) == len(reading.split()) and all(re.fullmatch("[a-z]+", s) for s in reading.split())]
random.Random(20261003).shuffle(pool)
seen = set()
output = root / "app/src/androidTest/assets/pinyin-evaluation.tsv"
output.parent.mkdir(parents=True, exist_ok=True)
rows = []
for word, parts in pool:
    if word in seen:
        continue
    seen.add(word)
    variants = {
        "full": "".join(parts),
        "short": "".join(s[0] for s in parts),
        "mixed_head": parts[0] + "".join(s[0] for s in parts[1:]),
        "mixed_tail": "".join(s[0] for s in parts[:-1]) + parts[-1],
        "mixed_alternate": "".join(s if i % 2 else s[0] for i, s in enumerate(parts)),
    }
    for category, query in variants.items():
        rows.append(f"{category}\t{query}\t{word}")
    if len(seen) >= 500:
        break
output.write_text("\n".join(rows) + "\n", encoding="utf-8")
print(f"Generated {len(rows)} cases from {len(seen)} phrases, seed 20261003")
