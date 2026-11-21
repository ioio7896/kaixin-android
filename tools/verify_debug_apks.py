"""Inspect finished APKs, signatures and embedded resources without installing/running them."""
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
outputs = json.loads((root / "build/debug-export.json").read_text(encoding="utf-8"))
sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
properties = root / "local.properties"
if properties.exists():
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("sdk.dir="):
            sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
if not sdk:
    raise SystemExit("Android SDK location is required for APK inspection")
versions = [p for p in (Path(sdk) / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
build_tools = max(versions, key=lambda p: tuple(map(int, p.name.split("."))))
windows = os.name == "nt"
aapt = build_tools / ("aapt2.exe" if windows else "aapt2")
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if windows else "java")) if os.environ.get("JAVA_HOME") else "java"
signer = build_tools / "lib/apksigner.jar"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def certificate(path):
    process = subprocess.run([java, "-jar", str(signer), "verify", "--print-certs", str(path)],
                             check=True, capture_output=True, text=True, encoding="utf-8", errors="replace")
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", process.stdout)
    if not match:
        raise ValueError("APK has no verified signer certificate")
    return match.group(1)


dictionary = root / "build/engine-assets/engine/kaixin.dict"
dictionary_sha256 = digest(dictionary)
emoji_assets = root / "build/emoji-assets/emoji"
engine_abis = {"universal": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], "arm64-v8a": ["arm64-v8a"]}
speech_manifest = json.loads((root / "app/src/main/assets/speech/manifest.json").read_text(encoding="utf-8"))
results = []
for item in outputs:
    apk = (root / item["file"]).resolve()
    if apk.parent != root or apk.suffix != ".apk":
        raise ValueError("APK export path must stay in the project root")
    expected_time = datetime.fromtimestamp(item["versionCode"], timezone(timedelta(hours=8))).strftime("%Y%m%d-%H%M%S")
    if expected_time != item["versionName"] or expected_time not in apk.name:
        raise ValueError("APK file name and version timestamp differ")
    if apk.stat().st_size != item["bytes"] or digest(apk) != item["sha256"]:
        raise ValueError("APK size/checksum mismatch")
    badging = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True,
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package.groups() != ("com.example.offlineime", str(item["versionCode"]), item["versionName"]):
        raise ValueError("Packaged AndroidManifest version differs from export metadata")
    if "android.permission.INTERNET" in badging:
        raise ValueError("Offline input method unexpectedly declares network permission")
    if "android.permission.RECORD_AUDIO" not in badging:
        raise ValueError("Offline speech recognition requires the microphone permission")
    with zipfile.ZipFile(apk) as archive:
        info = archive.getinfo("assets/engine/kaixin.dict")
        if info.compress_type != zipfile.ZIP_STORED:
            raise ValueError("Engine dictionary must be stored uncompressed so it can be memory-mapped")
        with archive.open(info) as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != dictionary_sha256:
                raise ValueError("APK contains a stale engine dictionary")
        for abi in engine_abis[item["abi"]]:
            archive.getinfo(f"lib/{abi}/libkaixin_engine.so")
        if json.loads(archive.read("assets/speech/manifest.json")) != speech_manifest:
            raise ValueError("APK contains stale speech metadata")
        for name, expected in speech_manifest["sha256"].items():
            with archive.open("assets/speech/" + name) as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                    raise ValueError("APK speech model payload checksum mismatch: " + name)
        names = set(archive.namelist())
        for name in ("catalog.tsv", "LICENSE.txt", "NOTICE.txt"):
            if archive.read("assets/emoji/" + name) != (emoji_assets / name).read_bytes():
                raise ValueError("APK contains stale emoji data: " + name)
        if any(name.startswith(("assets/emoji_styles/", "assets/emoji/wechat/")) for name in names):
            raise ValueError("APK contains removed emoji artwork")
        if any(name.startswith("assets/speech/punctuation/")
               or (name.startswith("lib/") and "/libonnxruntime" in name) for name in names):
            raise ValueError("APK still contains the removed punctuation model or ONNX Runtime")
        abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")})
        expected_abis = sorted(engine_abis[item["abi"]])
        if abis != expected_abis:
            raise ValueError("APK contains unexpected native architectures")
        for abi in expected_abis:
            archive.getinfo(f"lib/{abi}/liboffline_handwriting.so")
    results.append({**item, "native_abis": abis, "signer_sha256": certificate(apk), "manifest_verified": True,
                    "lexicon_verified": True, "speech_assets_verified": True, "installed_or_executed": False})
if len({item["signer_sha256"] for item in results}) != 1:
    raise ValueError("Debug packages have different signing keys")
verification_report = root / "build/debug-verification.json"
expected_signer = None
if verification_report.exists():
    # An exported APK may have failed verification. Use the last successful report first.
    previous_results = json.loads(verification_report.read_text(encoding="utf-8"))
    expected_signer = next((item["signer_sha256"] for item in previous_results if item["abi"] == "universal"), None)
if expected_signer is None:
    current = {item["file"] for item in results}
    previous = sorted(p for p in root.glob("kaixin-android-debug-*.apk") if p.name not in current)
    if previous:
        expected_signer = certificate(previous[-1])
if expected_signer is not None and expected_signer != results[0]["signer_sha256"]:
    raise ValueError("Debug signing key differs from the previous APK; preserve the original key for upgrades")
verification_report.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
print("Verified APK manifests, timestamps, SHA-256, signatures, native ABIs, lexicon and speech assets; no device execution.")
