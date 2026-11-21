"""Build the offline emoji catalog from pinned Unicode data, without network access."""

from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET

GROUPS = {
    "Smileys & Emotion": "笑脸",
    "People & Body": "人物",
    "Animals & Nature": "动物",
    "Food & Drink": "食物",
    "Travel & Places": "旅行",
    "Activities": "活动",
    "Objects": "物品",
    "Symbols": "符号",
    "Flags": "旗帜",
}
TONES = re.compile("[\U0001f3fb-\U0001f3ff]")
VARIATION = "\ufe0f"


def key(glyph):
    return glyph.replace(VARIATION, "")


def annotations(*paths):
    names, words = {}, {}
    for path in paths:
        for annotation in ET.parse(path).iter("annotation"):
            glyph = key(annotation.attrib["cp"])
            value = " ".join((annotation.text or "").split())
            if value and value != "↑↑↑":
                (names if annotation.attrib.get("type") == "tts" else words)[glyph] = value
    return names, words


def build(source, dest):
    zh, zh_keywords = annotations(source / "zh-derived.xml", source / "zh.xml")
    _, en_keywords = annotations(source / "en.xml")
    aliases = {}
    for line in (source / "aliases.tsv").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            glyph, terms = line.split("\t")
            aliases[key(glyph)] = terms

    rows = []
    group = ""
    for line in (source / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
        if line.startswith("# group: "):
            group = GROUPS.get(line.removeprefix("# group: "), "")
        if not group or not line or line.startswith("#"):
            continue
        fields, comment = line.split("#", 1)
        points, status = fields.split(";")
        if status.strip() != "fully-qualified":
            continue
        glyph = "".join(chr(int(point, 16)) for point in points.split())
        english = comment.strip().split(maxsplit=2)[2]
        rows.append((group, glyph, english))

    glyphs = {key(glyph): glyph for _, glyph, _ in rows}
    if len(rows) != len(glyphs) or len(rows) < 3_000:
        raise ValueError("Incomplete or duplicate Unicode emoji catalog")
    # Multi-person variants sometimes use a different untinted sequence.
    tone_bases = {
        "🫱‍🫲": "🤝", "👩‍🤝‍👩": "👭", "👩‍🤝‍👨": "👫", "👨‍🤝‍👨": "👬",
        "🧑‍❤️‍🧑": "💑", "🧑‍❤️‍💋‍🧑": "💏",
    }
    tone_bases = {key(form): base for form, base in tone_bases.items()}
    dest.mkdir(parents=True, exist_ok=True)
    with (dest / "catalog.tsv").open("w", encoding="utf-8", newline="\n") as out:
        for group, glyph, english in rows:
            lookup = key(glyph)
            untinted = TONES.sub("", lookup)
            base = glyphs.get(untinted, tone_bases.get(untinted, glyph))
            name = zh.get(lookup)
            if not name:
                base_name = zh.get(key(base))
                name = base_name + "（肤色变体）" if base_name else group + "表情 · " + english
            fields = (group, glyph, name, english + " | " + en_keywords.get(lookup, ""),
                      zh_keywords.get(lookup, ""), aliases.get(lookup, ""), base)
            out.write("\t".join(fields) + "\n")
    for name in ("LICENSE.txt", "NOTICE.txt"):
        shutil.copyfile(source / name, dest / name)


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
