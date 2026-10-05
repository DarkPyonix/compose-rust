#!/usr/bin/env bash
# Run after build-native-linux.sh. The Linux renderer opens its own X11 window, so the
# staged directory must hold no Java toolkit library. Native Image writes libawt, libawt_xawt
# and libawt_headless beside an image whenever java.awt.Toolkit is reachable, so finding one
# means something on the renderer's path reaches the toolkit again. The build itself fails on
# that reachability (-H:ReportAnalysisForbiddenType=java.awt.Toolkit); this checks the result.
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
if ! grep -q 'ReportAnalysisForbiddenType=java.awt.Toolkit' "$repo_root/renderer/desktop/scripts/build-native-linux.sh"; then
    echo "FAIL: build-native-linux.sh no longer forbids java.awt.Toolkit" >&2
    exit 1
fi
echo "the Linux image carries no Java toolkit library: ok"
