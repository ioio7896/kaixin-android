"""Bundle only the picker's Unicode emoji from official open-source archives.

Sources (download into build/emoji-sources):
  https://codeload.github.com/jdecked/twemoji/zip/refs/heads/main
  https://codeload.github.com/hfg-gmuend/openmoji/zip/refs/heads/master
  https://registry.npmjs.org/@lobehub/fluent-emoji-3d/-/fluent-emoji-3d-1.1.0.tgz
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "build" / "emoji-sources"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "emoji_styles"
CATALOG = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "offlineime" / "EmojiCatalog.java"


def codepoints(text: str) -> str:
    return "-".join(f"{ord(character):x}" for character in text)


def code_name(stem: str) -> str:
    try:
        return "-".join(f"{int(part, 16):x}" for part in stem.split("-"))
    except ValueError:
        return ""


def without_variation(code: str) -> str:
    return "-".join(part for part in code.split("-") if part not in {"fe0e", "fe0f"})


def catalog_values() -> list[str]:
    source = CATALOG.read_text(encoding="utf-8")
    strings = re.findall(r'return split\("([^"]+)"\)', source)
    return sorted({value for words in strings for value in words.split()})


def indexed_artwork(archive: zipfile.ZipFile, marker: str) -> dict[str, str]:
    result = {}
    for path in archive.namelist():
        if marker not in path or not path.endswith(".png"):
            continue
        code = code_name(Path(path).stem)
        if code:
            result[code] = path
    return result


def package_style(name: str, archive: zipfile.ZipFile, artwork: dict[str, str], values: list[str]) -> int:
    destination = OUTPUT / name
    destination.mkdir(parents=True, exist_ok=True)
    normalized = {without_variation(code): path for code, path in artwork.items()}
    count = 0
    for value in values:
        code = codepoints(value)
        source = artwork.get(code) or normalized.get(without_variation(code))
        if source is None:
            continue
        target = destination / (code + ".png")
        target.write_bytes(archive.read(source))
        count += 1
    return count


def package_fluent(values: list[str]) -> int:
    destination = OUTPUT / "fluent"
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(SOURCE / "fluent-npm.tgz", "r:gz") as archive:
        artwork = {code_name(Path(item.name).stem): item
                   for item in archive.getmembers()
                   if item.name.startswith("package/assets/") and item.name.endswith(".webp")}
        normalized = {without_variation(code): item for code, item in artwork.items()}
        count = 0
        for value in values:
            code = codepoints(value)
            item = artwork.get(code) or normalized.get(without_variation(code))
            if item is None:
                continue
            with archive.extractfile(item) as stream:
                (destination / (code + ".webp")).write_bytes(stream.read())
            count += 1
    licenses = (SOURCE / "fluent-microsoft-LICENSE.txt").read_text(encoding="utf-8")
    licenses += "\n\nLobeHub distribution:\n" + (SOURCE / "fluent-lobehub-LICENSE.txt").read_text(encoding="utf-8")
    (OUTPUT / "licenses" / "fluent.txt").write_text(licenses, encoding="utf-8")
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("styles", nargs="*", choices=["twemoji", "openmoji", "fluent"])
    selected = set(parser.parse_args().styles or ["twemoji", "openmoji", "fluent"])
    values = catalog_values()
    archives = {
        "twemoji": ("twemoji.zip", "/assets/72x72/", "LICENSE-GRAPHICS"),
        "openmoji": ("openmoji.zip", "/color/72x72/", "LICENSE.txt"),
    }
    (OUTPUT / "licenses").mkdir(parents=True, exist_ok=True)
    for name, (file_name, marker, license_name) in archives.items():
        if name not in selected:
            continue
        with zipfile.ZipFile(SOURCE / file_name) as archive:
            artwork = indexed_artwork(archive, marker)
            count = package_style(name, archive, artwork, values)
            license_path = next(path for path in archive.namelist()
                                if path.count("/") == 1 and path.endswith("/" + license_name))
            (OUTPUT / "licenses" / f"{name}.txt").write_bytes(archive.read(license_path))
            print(f"{name}: {count}/{len(values)}")
    if "fluent" in selected:
        print(f"fluent: {package_fluent(values)}/{len(values)}")
    (OUTPUT / "NOTICE.txt").write_text(
        "Emoji 面板图形来源（仅用于本机预览，输入内容仍为 Unicode）\n"
        "Twemoji: https://github.com/jdecked/twemoji — CC BY 4.0\n"
        "OpenMoji: https://github.com/hfg-gmuend/openmoji — CC BY-SA 4.0\n"
        "Fluent Emoji: https://github.com/microsoft/fluentui-emoji — MIT\n"
        "Fluent 3D 精简资源包: https://github.com/lobehub/fluent-emoji — MIT\n"
        "仅筛选本项目 EmojiCatalog 中的图形。\n"
        "各套图形的许可全文位于 emoji_styles/licenses。\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
