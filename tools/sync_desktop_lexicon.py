"""Merge a desktop lexicon into Android assets without losing Android readings.

Use --output-root to prepare a reviewable snapshot before changing the app.
Desktop heat is converted to Android's short/mixed-only bonus; it is never
substituted for the underlying full-pinyin corpus frequency.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def read_rows(path):
    if not path.is_file():
        return [], {}
    comments, rows = [], {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        if line.startswith("#"):
            comments.append(line)
            continue
        columns = line.split("\t")
        if len(columns) < 2:
            raise ValueError(f"Invalid row in {path}: {line!r}")
        if path.name == "single_char_common_8105.txt":
            key = (columns[0].strip(), "")
            int(columns[1])
        else:
            reading = columns[1].strip().lower().replace("ü", "v").replace("u:", "v")
            reading = " ".join(re.split(r"[\s']+", reading))
            if not re.fullmatch(r"[a-z]+(?: [a-z]+)*", reading):
                raise ValueError(f"Invalid reading in {path}: {line!r}")
            columns[1] = reading
            key = (columns[0].strip(), reading)
            int(columns[2])
        previous = rows.get(key)
        score_column = 1 if not key[1] else 2
        if previous is None or int(columns[score_column]) > int(previous[score_column]):
            rows[key] = columns
    return comments, rows


def inventory(root):
    result = {}
    for path in sorted(root.rglob("*.txt")):
        if path.parts[-2] not in ("core", "zh", "zh-ext", "en"):
            continue
        _, rows = read_rows(path)
        for key, columns in rows.items():
            if not key[1]:
                continue
            score = int(columns[3] if path.name.startswith("life_short_hot_") and len(columns) > 3 else columns[2])
            result[key] = max(score, result.get(key, 0))
    return result


def write_rows(path, comments, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Preserve stable score order, including the first-page long phrase cache.
    data = sorted(rows.values(), key=lambda c: (-int(c[1] if len(c) == 2 else c[2]), c[0], c[1]))
    path.write_text("\n".join(comments + ["\t".join(c) for c in data]) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Desktop lexicon directory")
    parser.add_argument("--target", type=Path, required=True, help="Android lexicon directory")
    parser.add_argument("--output-root", type=Path, help="Write merged files here instead of target")
    args = parser.parse_args()
    source, target = args.source.resolve(), args.target.resolve()
    output = (args.output_root or target).resolve()
    if source == target or not (source / "zh").is_dir() or not (target / "zh").is_dir():
        parser.error("Source and target must be different populated lexicon directories")
    before = inventory(target)
    merged = dict(before)
    files = []
    hot_names = {"life_short_hot_2char.txt", "life_hot_3char_curated.txt", "life_hot_4char_curated.txt"}
    for src in sorted(source.rglob("*.txt")):
        relative = src.relative_to(source)
        if relative.parts[0] not in ("core", "zh", "zh-ext", "en") or src.name in hot_names:
            continue
        old_comments, rows = read_rows(target / relative)
        source_comments, additions = read_rows(src)
        for key, columns in additions.items():
            score_column = 1 if not key[1] else 2
            if key not in rows or int(columns[score_column]) > int(rows[key][score_column]):
                rows[key] = columns
            if key[1]:
                merged[key] = max(merged.get(key, 0), int(columns[2]))
        comments = old_comments or source_comments
        if old_comments and old_comments != source_comments:
            comments = old_comments + ["# 桌面端补充来源（保留安卓原有词条与读音）："] + source_comments
        write_rows(output / relative, comments, rows)
        files.append({"path": relative.as_posix(), "desktop_rows": len(additions),
                      "merged_rows": len(rows), "sha256": hashlib.sha256(src.read_bytes()).hexdigest()})
    for name, source_names in (
        ("life_short_hot_2char.txt", ["life_short_hot_2char.txt"]),
        ("life_short_hot_3to4char.txt", ["life_hot_3char_curated.txt", "life_hot_4char_curated.txt"]),
    ):
        relative = Path("zh") / name
        comments, rows = read_rows(target / relative)
        for source_name in source_names:
            source_comments, additions = read_rows(source / "zh" / source_name)
            for key, columns in additions.items():
                if len(key[0]) not in (2, 3, 4) or len(key[1].split()) != len(key[0]):
                    raise ValueError(f"Hot entry has inconsistent syllables: {key}")
                heat = int(columns[2])
                bonus = 3000 if heat >= 9500 else 1200 if heat >= 8500 else 600
                prior = rows.get(key)
                base = merged.get(key, 6000)
                rows[key] = [key[0], key[1], str(max(bonus, int(prior[2]) if prior else 0)), str(base)]
                merged[key] = max(merged.get(key, 0), base)
            comments += [f"# 桌面热词来源：zh/{source_name}；领域热度仅转换为简拼/混拼加分。"]
            comments += source_comments
        write_rows(output / relative, comments, rows)
        files.append({"path": relative.as_posix(), "merged_rows": len(rows)})
    added = set(merged) - set(before)
    report = {"source": str(source), "target": str(target), "unique_readings_before": len(before),
              "unique_readings_after": len(merged), "added_readings": len(added),
              "added_words": len({word for word, reading in added} - {word for word, reading in before}),
              "added_by_word_length": {str(n): sum(len(word) == n for word, reading in added)
                                       for n in sorted({len(word) for word, reading in added})},
              "files": files}
    output.mkdir(parents=True, exist_ok=True)
    (output / "desktop-import.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "desktop-import-notice.md").write_text(
        "桌面词库扩充\n\n来源：开心输入法桌面项目 lexicon 目录。\n"
        "按词语与实际读音合并，保留安卓原有词条及多音读音；每份词库的来源说明保留在文件头部。\n"
        "领域热词只增加完整简拼、混拼的排序分数，基础全拼词频继续取原语料。\n"
        f"本次合并后共有 {len(merged):,} 条不同词语/读音记录，新增 {len(added):,} 条，新增词语 {report['added_words']:,} 个。\n"
        "详细来源文件、校验值和数量见 desktop-import.json。\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "files"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
