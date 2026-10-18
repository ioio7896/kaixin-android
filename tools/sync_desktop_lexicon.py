"""Mirror the desktop lexicon into the Android asset directory.

This intentionally replaces old Android-only entries and preserves every
desktop source file byte-for-byte. The single-character reading index is
generated separately from the desktop project's pinned pronunciation data.
"""
import argparse
from pathlib import Path
import shutil


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
    removed = 0
    for path in target.rglob("*"):
        if path.is_file() and path.relative_to(target) not in source_files:
            path.unlink()
            removed += 1
    for path in sorted((path for path in target.rglob("*") if path.is_dir()), reverse=True):
        if not any(path.iterdir()):
            path.rmdir()
    print(f"Mirrored {len(source_files)} desktop files; removed {removed} Android-only files")


if __name__ == "__main__":
    main()
