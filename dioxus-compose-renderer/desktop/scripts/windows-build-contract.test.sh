#!/usr/bin/env bash
# Static contract for the Windows native-image path. It runs on macOS so that untested
# Windows work cannot silently lose the evidence, staging, or PE/COFF boundary rules.
set -euo pipefail

scripts_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
native_dir="$(cd "$scripts_dir/.." && pwd)"

build_script="$scripts_dir/build-native-windows.ps1"
smoke_script="$scripts_dir/smoke-test-windows.ps1"
metadata="$scripts_dir/windows-metadata/reachability-metadata.json"
resources="$scripts_dir/windows-metadata/resources/resource-config.json"
evidence="$scripts_dir/windows-metadata/evidence.json"
workflow="$native_dir/../../.github/workflows/native-renderer.yml"

for file in "$build_script" "$smoke_script" "$metadata" "$resources" "$evidence"; do
    [[ -f "$file" ]] || { echo "error: missing $file" >&2; exit 1; }
done

grep -q 'UNTESTED' "$build_script"
grep -q 'GraalVM 25' "$build_script"
grep -q 'ConfigurationFileDirectories=$MetadataDir,$ResourceMetadataDir' "$build_script"
grep -q 'skiko-windows-x64.dll' "$build_script"
grep -q 'icudtl.dat' "$build_script"
grep -q 'fontconfig.bfc' "$build_script"
grep -q 'awt.dll' "$build_script"
grep -q 'jawt.dll' "$build_script"
grep -q 'GetProcAddress' "$native_dir/c/renderer_entry.c"
grep -q 'GetModuleHandleExW' "$native_dir/c/renderer_entry.c"
grep -q '__declspec(dllexport)' "$native_dir/c/smoke_host.c"
grep -q 'RequireClick' "$smoke_script"
# The workflow job that will decide whether any of this is right. It has to run the build
# script and the smoke test on a Windows runner, not just exist.
grep -q 'runs-on: windows-2022' "$workflow"
grep -q 'build-native-windows.ps1' "$workflow"
grep -q 'smoke-test-windows.ps1' "$workflow"

python3 - "$metadata" "$resources" "$evidence" <<'PY'
import json
import sys

metadata = json.load(open(sys.argv[1], encoding="utf-8"))
resources = json.load(open(sys.argv[2], encoding="utf-8"))
evidence = json.load(open(sys.argv[3], encoding="utf-8"))
types = {entry["type"] for entry in metadata["reflection"]}
required = {
    "com.sun.java.swing.plaf.windows.WindowsLookAndFeel",
    "org.jetbrains.skiko.redrawer.Direct3DRedrawer",
    "sun.awt.windows.WToolkit",
    "sun.java2d.windows.WindowsFlags",
}
# The AWT classes the Windows toolkit reaches through JNI are not named after Windows, so a
# selection made by name drops them. java.awt.Toolkit.getDefaultToolkit is the one that shows
# up first: Toolkit.initIDs resolves it through JNI before any window exists, and without the
# registration the image starts and dies with NoSuchMethodError on it.
required |= {
    "java.awt.Component",
    "java.awt.Toolkit",
    "java.awt.event.KeyEvent",
    "sun.awt.SunToolkit",
}
missing = sorted(required - types)
if missing:
    raise SystemExit("missing Windows metadata types: " + ", ".join(missing))

by_type = {entry["type"]: entry for entry in metadata["reflection"]}
toolkit = by_type["java.awt.Toolkit"]
if not toolkit.get("jniAccessible"):
    raise SystemExit("java.awt.Toolkit must be registered as JNI accessible")
if {"name": "getDefaultToolkit", "parameterTypes": []} not in toolkit.get("methods", []):
    raise SystemExit("java.awt.Toolkit.getDefaultToolkit() must be registered")
verified = evidence["verifiedAgainst"]
assert verified == {
    "jdk": "25",
    "os": "windows-x64",
    "composeMultiplatform": "1.9.0",
}
patterns = {entry["pattern"] for entry in resources["resources"]["includes"]}
assert any("windows_ko.properties" in pattern for pattern in patterns)
assert any("skiko-windows-x64.dll.sha256" in pattern for pattern in patterns)
assert evidence["dioxusRendererStatus"] == "untested"
PY

echo "ok    Windows build contract"
