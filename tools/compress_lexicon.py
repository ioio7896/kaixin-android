"""Package a deterministic compressed database; preserve the source and lexicon fallbacks."""
from pathlib import Path
import gzip
import shutil
import sys

source = Path(sys.argv[1]).resolve()
target = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else source.with_suffix(".bin")
target.parent.mkdir(parents=True, exist_ok=True)
temporary = target.with_suffix(target.suffix + ".tmp")
with source.open("rb") as original, temporary.open("wb") as compressed:
    with gzip.GzipFile(filename="", mode="wb", fileobj=compressed, compresslevel=9, mtime=0) as stream:
        shutil.copyfileobj(original, stream, 1024 * 1024)
temporary.replace(target)
print(f"Bundled dictionary: {source.stat().st_size // 1024} KiB -> {target.stat().st_size // 1024} KiB")
