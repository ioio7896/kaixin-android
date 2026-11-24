"""Read-only source and lexicon static checks. Does not import application code or run tests."""
import ast
import hashlib
import json
import re
import tomllib
from pathlib import Path
import xml.etree.ElementTree as ET

FULLWIDTH_PUNCT_RE = re.compile(r"[（）【】《》，。！？、；：“”‘’]")
HANZI_ONLY_RE = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\U00020000-\U000323af]+$")
CORE_CURATED_FILES = {"life_hot_3char_curated.txt", "life_hot_4char_curated.txt"}
CORE_BLOCKED_TERMS = {
    "操你妈", "傻逼", "他妈的", "希特勒", "法西斯", "真他妈", "草泥马", "去死吧", "谁他妈",
}
TWO_CHAR_MAIN_FILE = "life_common_2char_125000.txt"
TWO_CHAR_HOT_SCORE = 3500
TWO_CHAR_HOT_LIMIT = 30_000
EXT_OUTRANK_MARGIN_LIMIT = 3500


def check_lexicon_quality(root: Path) -> dict:
    lexicon_root = root / "lexicon"
    errors: list[str] = []
    zh_best_by_py: dict[str, tuple[int, str]] = {}
    ext_rows_by_file: dict[str, list[tuple[str, str, int]]] = {}
    scanned_files = 0
    two_char_hot_count = 0

    for path in sorted(lexicon_root.rglob("*.txt")):
        rel_parts = path.relative_to(lexicon_root).parts
        if not rel_parts or rel_parts[0] == "en":
            continue
        scanned_files += 1
        rel_str = path.relative_to(root).as_posix()
        is_core_layer = rel_parts[0] == "core" or path.name in CORE_CURATED_FILES
        is_zh_or_ext = rel_parts[0] in ("zh", "zh-ext")
        prev_2char_score = 10_001

        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            phrase = fields[0]

            # Gate 1: No fullwidth punctuation inside lexicon phrases.
            if FULLWIDTH_PUNCT_RE.search(phrase):
                errors.append(f"{rel_str}:{number}: fullwidth punctuation in phrase: {phrase}")

            # Gate 2: Core layer must not contain blocked vulgar/sensitive terms.
            if is_core_layer and phrase in CORE_BLOCKED_TERMS:
                errors.append(f"{rel_str}:{number}: blocked vulgar/sensitive term in Core layer: {phrase}")

            try:
                score = int(fields[1] if len(fields) == 2 else fields[2])
                if not 1 <= score <= 10_000:
                    raise ValueError("out of range")
            except (ValueError, IndexError):
                errors.append(f"{rel_str}:{number}: expected a 1-10000 score")
                score = 0

            if len(fields) >= 3:
                reading = fields[1]
                # Gate 3: Canonical lve/nve spelling across zh/ and zh-ext/.
                if is_zh_or_ext and any(syl in ("lue", "nue") for syl in reading.split()):
                    errors.append(f"{rel_str}:{number}: noncanonical lue/nue (use lve/nve): {phrase} {reading}")

                # Gate 4: Reject single-letter ASCII input codes for non-Hanzi phrases.
                if not HANZI_ONLY_RE.match(phrase) and len(reading.strip()) == 1 and reading.strip().isascii():
                    errors.append(f"{rel_str}:{number}: single-letter non-hanzi code: {phrase} -> {reading}")

                # Gate 5: Main 2-char lexicon monotonic descending order and bounded hot slice.
                if path.name == TWO_CHAR_MAIN_FILE:
                    if score > prev_2char_score:
                        errors.append(f"{rel_str}:{number}: score inversion ({score} > {prev_2char_score}): {phrase}")
                    prev_2char_score = score
                    if score >= TWO_CHAR_HOT_SCORE:
                        two_char_hot_count += 1

                if rel_parts[0] == "zh" and path.name != "single_char_common_8105.txt":
                    prev = zh_best_by_py.get(reading)
                    if prev is None or score > prev[0]:
                        zh_best_by_py[reading] = (score, phrase)
                elif rel_parts[0] == "zh-ext":
                    ext_rows_by_file.setdefault(path.name, []).append((phrase, reading, score))

    if two_char_hot_count > TWO_CHAR_HOT_LIMIT:
        errors.append(
            f"lexicon/zh/{TWO_CHAR_MAIN_FILE}: hot mixed-index slice (>={TWO_CHAR_HOT_SCORE}) "
            f"too large: {two_char_hot_count} > {TWO_CHAR_HOT_LIMIT}"
        )

    # Gate 6: Verify 26-category manifest integrity & default-enabled ext homophone outrank cap.
    manifest_path = lexicon_root / "category_lexicons.json"
    if not manifest_path.is_file():
        errors.append("lexicon/category_lexicons.json is missing; run tools/sync_desktop_lexicon.py")
        category_count = 0
    else:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        categories = manifest.get("categories", [])
        category_count = len(categories)
        manifest_files = set()
        for item in categories:
            filename = item["file"]
            manifest_files.add(filename)
            ext_path = lexicon_root / "zh-ext" / filename
            if not ext_path.is_file():
                errors.append(f"lexicon/zh-ext/{filename}: missing file declared in category_lexicons.json")
                continue
            data = ext_path.read_bytes()
            row_count = sum(
                bool(line.strip()) and not line.startswith("#")
                for line in data.decode("utf-8-sig").splitlines()
            )
            if row_count != item["rows"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                errors.append(f"lexicon/zh-ext/{filename}: category_lexicons.json has stale count/hash")
            if item.get("default_enabled", False):
                for phrase, py, score in ext_rows_by_file.get(filename, []):
                    zh_best = zh_best_by_py.get(py)
                    if zh_best and zh_best[1] != phrase and score - zh_best[0] > EXT_OUTRANK_MARGIN_LIMIT:
                        errors.append(
                            f"lexicon/zh-ext/{filename}: default-on ext entry {phrase}({score}) "
                            f"severely outranks zh {zh_best[1]}({zh_best[0]}) on '{py}'"
                        )
        actual_ext_files = {p.name for p in (lexicon_root / "zh-ext").glob("*.txt")}
        extra_ext = sorted(actual_ext_files - manifest_files)
        if extra_ext:
            errors.append(f"lexicon/zh-ext/: undeclared category files not in category_lexicons.json: {extra_ext}")

    # Verify rules.tsv does not reference retired/missing lexicon files.
    rules_path = root / "app/src/main/assets/lexicon-corrections/rules.tsv"
    if rules_path.is_file():
        for number, line in enumerate(rules_path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) >= 2 and cols[0] in ("cap", "cap-word", "exclude"):
                target = lexicon_root / cols[1]
                if not target.is_file():
                    errors.append(f"rules.tsv:{number}: rule targets missing lexicon file: {cols[1]}")
            elif len(cols) >= 2 and cols[0] in ("scale-prefix", "cap-prefix") and cols[1].endswith(".txt"):
                target = lexicon_root / cols[1]
                if not target.is_file():
                    errors.append(f"rules.tsv:{number}: prefix rule targets missing lexicon file: {cols[1]}")

    if errors:
        preview = "\n".join(errors[:25])
        raise ValueError(f"Lexicon quality gate failed with {len(errors)} issue(s):\n{preview}")

    return {
        "lexicon_files_checked": scanned_files,
        "lexicon_categories": category_count,
        "two_char_hot_entries": two_char_hot_count,
    }


def main():
    root = Path(__file__).resolve().parents[1]
    python_files = sorted((root / "tools").glob("*.py"))
    xml_files = sorted((root / "app/src/main").rglob("*.xml"))
    for path in python_files:
        ast.parse(path.read_text(encoding="utf-8-sig"), filename=str(path))
    for path in xml_files:
        ET.parse(path)
    # Every Java native declaration must have a matching Rust JNI export and vice versa.
    java_bridge = (root / "app/src/main/java/com/example/offlineime/NativeEngine.java").read_text(encoding="utf-8")
    rust_bridge = (root / "engine/src/jni_api.rs").read_text(encoding="utf-8")
    declared = set(re.findall(r"static native [\w\[\]]+ (\w+)\(", java_bridge))
    exported = set(re.findall(r"Java_com_example_offlineime_NativeEngine_(\w+)\(", rust_bridge))
    if declared != exported:
        raise ValueError(f"JNI mismatch: java-only={sorted(declared - exported)} rust-only={sorted(exported - declared)}")
    with (root / "engine/Cargo.toml").open("rb") as manifest:
        profile = tomllib.load(manifest).get("profile", {}).get("release", {})
    if profile.get("panic") != "unwind":
        raise ValueError('engine/Cargo.toml must set [profile.release] panic = "unwind" for JNI panic handling')

    lexicon_report = check_lexicon_quality(root)
    print(json.dumps({
        "python_syntax_files": len(python_files),
        "xml_files": len(xml_files),
        "jni_methods": len(declared),
        "rust_release_panic": profile["panic"],
        **lexicon_report,
        "execution": "static-only; no tests",
    }))


if __name__ == "__main__":
    main()
