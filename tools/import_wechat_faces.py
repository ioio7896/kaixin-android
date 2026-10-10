"""Import a pinned set of offline face thumbnails, without executing upstream code."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote
from urllib.request import Request, urlopen

REVISION = "58b70fbc3b96644348b85757950fe7b54b876ba5"
REPOSITORY = "xxk8/wechat-emojis"
ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "app/src/main/assets/emoji/wechat"


def fetch(url):
    request = Request(url, headers={"User-Agent": "offline-ime-resource-import"})
    with urlopen(request, timeout=30) as response:
        return response.read()


def main():
    tree = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/git/trees/{REVISION}?recursive=1"))
    faces = [entry for entry in tree["tree"]
             if re.fullmatch(r"assets/face/[\w\u4e00-\u9fff]+\.png", entry["path"])]
    if len(faces) != 75:
        raise ValueError("The pinned thumbnail inventory has changed")
    DESTINATION.mkdir(parents=True, exist_ok=True)

    def save(entry):
        data = fetch(f"https://raw.githubusercontent.com/{REPOSITORY}/{REVISION}/{quote(entry['path'])}")
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Expected a PNG thumbnail")
        git_hash = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        if git_hash != entry["sha"]:
            raise ValueError("Thumbnail does not match the pinned source")
        destination = DESTINATION / Path(entry["path"]).name
        destination.write_bytes(data)
        return {"file": destination.name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}

    with ThreadPoolExecutor(max_workers=6) as workers:
        imported = list(workers.map(save, faces))
    manifest = {"repository": f"https://github.com/{REPOSITORY}", "revision": REVISION,
                "files": sorted(imported, key=lambda entry: entry["file"])}
    (DESTINATION / "source.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                           encoding="utf-8")
    print(f"Imported {len(imported)} offline face thumbnails ({sum(item['bytes'] for item in imported):,} bytes)")


if __name__ == "__main__":
    main()
