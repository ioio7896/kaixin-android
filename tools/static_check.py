"""Read-only source checks. Does not import application code or run tests."""
import ast
import json
import re
import tomllib
from pathlib import Path
import xml.etree.ElementTree as ET

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
print(json.dumps({"python_syntax_files": len(python_files), "xml_files": len(xml_files),
                  "jni_methods": len(declared),
                  "rust_release_panic": profile["panic"],
                  "execution": "static-only; no tests"}))
