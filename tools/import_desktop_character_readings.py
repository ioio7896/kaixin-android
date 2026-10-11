"""Materialize desktop single-character readings as portable Android lexicon rows.

Uses the desktop project's pinned pinyin crate data and pronunciation overrides.
The generated text is bundled, so normal APK builds need only Python's stdlib.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata


def normalize(reading):
    # Preserve the umlaut before removing tone marks (lü -> lv, not lu).
    decomposed = unicodedata.normalize("NFD", reading.lower())
    decomposed = decomposed.replace("u\u0308", "v")
    plain = "".join(c for c in decomposed if not unicodedata.combining(c))
    return plain.replace("nue", "nve").replace("lue", "lve")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--desktop-root", type=Path, required=True)
    parser.add_argument("--pinyin-crate", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    raw_path = args.pinyin_crate / "pinyin-data/pinyin.txt"
    source_path = args.desktop_root / "lexicon/zh/single_char_common_8105.txt"
    override_paths = [args.desktop_root / "data_sources/kaixin" / name
                      for name in ("pronunciation_aliases.tsv", "pronunciation_exclusions.tsv")]
    scores = {}
    for line in source_path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.startswith("#"):
            char, score = line.split("\t")
            scores[char] = int(score)
    readings = {}
    for line in raw_path.read_text(encoding="utf-8-sig").splitlines():
        content = line.split("#", 1)[0].strip()
        if not content:
            continue
        point, values = content.split(":", 1)
        char = chr(int(point.strip()[2:], 16))
        if char in scores:
            readings[char] = list(dict.fromkeys(normalize(v.strip()) for v in values.split(",")))
    excluded = {}
    for path in override_paths:
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            columns = line.split("\t")
            char, reading = columns[0], normalize(columns[1])
            if char not in scores or len(char) != 1:
                continue
            if len(columns) > 3 and columns[3].lower() == "historical":
                excluded.setdefault(char, set()).add(reading)
            else:
                values = readings.setdefault(char, [])
                if reading not in values:
                    values.append(reading)
    rows = []
    for char, score in scores.items():
        values = [v for v in readings.get(char, [])
                  if v not in excluded.get(char, ()) and re.fullmatch("[a-z]+", v)]
        if not values:
            raise ValueError(f"Desktop character has no usable reading: U+{ord(char):04X}")
        rows.extend((char, reading, score) for reading in values)
    output = root / "lexicon/zh/single_char_readings.txt"
    comments = ["# 桌面 8105 单字词库的显式拼音读音；保留原始字频及多音读音。",
                "# 字频来源：开心输入法/lexicon/zh/single_char_common_8105.txt",
                "# 读音来源：桌面项目 pinyin crate 0.10.0 内含 pinyin-data 0.13.0。",
                "# 应用桌面项目 pronunciation_aliases.tsv 和 pronunciation_exclusions.tsv；排除明确旧读。",
                "# MIT 许可全文见 zh/LICENSE.pinyin-data.md；普通构建无需安装 Rust 或拼音库。",
                "# 格式：汉字<TAB>无声调拼音<TAB>字频"]
    output.write_text("\n".join(comments + [f"{c}\t{r}\t{s}" for c, r, s in rows]) + "\n", encoding="utf-8")
    license_text = (args.pinyin_crate / "LICENSE").read_text(encoding="utf-8")
    (output.parent / "LICENSE.pinyin-data.md").write_text(license_text, encoding="utf-8")
    report = {"character_count": len(scores), "reading_count": len(rows),
              "pinyin_crate_version": "0.10.0", "pinyin_data_version": "0.13.0",
              "sources": [{"path": ("pinyin-0.10.0/" + p.relative_to(args.pinyin_crate).as_posix()
                                     if p == raw_path else "desktop/" + p.relative_to(args.desktop_root).as_posix()),
                           "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                          for p in [source_path, raw_path, *override_paths]],
              "output": "zh/single_char_readings.txt"}
    (root / "lexicon/desktop-character-import.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(scores)} desktop characters with {len(rows)} readings")


if __name__ == "__main__":
    main()
