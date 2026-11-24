"""Mirror the desktop lexicon into the Android asset directory.

This intentionally replaces old desktop-mirrored entries and preserves every
desktop source file byte-for-byte while retaining explicit Android-only files
(`en/kaixin_mixed_100.txt`, `zh/kaixin_requested_words.txt`) and mirroring the
shared 26-category manifest (`shared/category_lexicons.json`) when present.
"""
import argparse
from pathlib import Path
import shutil

PRESERVED_ANDROID_FILES = {
    Path("en/kaixin_mixed_100.txt"),
    Path("zh/kaixin_requested_words.txt"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Desktop lexicon directory")
    parser.add_argument("--target", type=Path, required=True, help="Android lexicon directory")
    args = parser.parse_args()

    workspace = Path(__file__).resolve().parents[1]
    source = args.source.resolve(strict=True)
    target = args.target.resolve(strict=True)
    expected = (workspace / "lexicon").resolve(strict=True)
    if target != expected or source == target or not (source / "zh/single_char_common_8105.txt").is_file():
        parser.error("target must be this project's lexicon, and source must be a desktop lexicon")

    source_files = {path.relative_to(source): path for path in source.rglob("*") if path.is_file()}
    for relative, path in source_files.items():
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)

    retained = set(source_files.keys()) | PRESERVED_ANDROID_FILES
    category_manifest = source.parent / "shared/category_lexicons.json"
    if category_manifest.is_file():
        shutil.copyfile(category_manifest, target / "category_lexicons.json")
        retained.add(Path("category_lexicons.json"))

    removed = 0
    for path in target.rglob("*"):
        if path.is_file() and path.relative_to(target) not in retained:
            path.unlink()
            removed += 1
    for path in sorted((path for path in target.rglob("*") if path.is_dir()), reverse=True):
        if not any(path.iterdir()):
            path.rmdir()
    print(f"Mirrored {len(source_files)} desktop files; removed {removed} retired files")


if __name__ == "__main__":
    main()
