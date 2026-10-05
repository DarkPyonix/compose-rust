#!/usr/bin/env bash
# Run after build-native-linux.sh. The Linux renderer opens its own X11 window, so the
# staged directory must hold no Java toolkit library. The build removes the ones Native Image
# writes; the smoke test then runs the image without them, so a toolkit class that gets
# initialised fails there. This checks the directory.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
lib="${1:-$repo_root/renderer/build/native-image-linux/dist/lib}"
[[ -d "$lib" ]] || { echo "FAIL: $lib does not exist; run build-native-linux.sh first" >&2; exit 1; }

found="$(find "$lib" -maxdepth 1 -name 'libawt*.so' -print)"
if [[ -n "$found" ]]; then
    echo "FAIL: the Linux image directory holds toolkit libraries:" >&2
    echo "$found" >&2
    exit 1
fi
for type in java.awt.Toolkit java.awt.Component; do
    if ! grep -q "ReportAnalysisForbiddenType=$type" "$repo_root/renderer/desktop/scripts/build-native-linux.sh"; then
        echo "FAIL: build-native-linux.sh no longer forbids $type" >&2
        exit 1
    fi
done
echo "the Linux image carries no Java toolkit library and the build forbids the toolkit's types: ok"
