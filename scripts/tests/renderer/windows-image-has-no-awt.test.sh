#!/usr/bin/env bash
# Run after build-native-windows.ps1. The Windows renderer opens its own Win32 window, so the
# staged directory must hold no Java toolkit library, and the build must forbid the toolkit's
# types. The build removes what Native Image writes; the smoke test then runs the image
# without them, so a toolkit class that gets initialised fails there. This checks the
# directory and the build script.
#
# jawt.dll is allowed only as the build's own stub (jawt_stub.c), which skiko's library can
# load and which answers that there is no toolkit.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
bin="${1:-$repo_root/renderer/build/native-image/dist/bin}"
build="$repo_root/renderer/desktop/scripts/build-native-windows.ps1"
[[ -d "$bin" ]] || { echo "FAIL: $bin does not exist; run build-native-windows.ps1 first" >&2; exit 1; }
status=0

for library in awt.dll fontmanager.dll javaaccessbridge.dll javajpeg.dll lcms.dll; do
    if [[ -e "$bin/$library" ]]; then
        echo "FAIL: the Windows image directory holds the toolkit's $library" >&2
        status=1
    fi
done
if [[ -e "$bin/jawt.dll" ]] && ! grep -aq "compose-rust: no Java toolkit" "$bin/jawt.dll"; then
    echo "FAIL: the Windows image directory holds the JDK's jawt.dll, not the build's stub" >&2
    status=1
fi
for type in java.awt.Toolkit java.awt.Component; do
    if ! grep -q "ReportAnalysisForbiddenType=$type" "$build"; then
        echo "FAIL: build-native-windows.ps1 does not forbid $type" >&2
        status=1
    fi
done
[[ $status -eq 0 ]] && echo "the Windows image carries no Java toolkit library and the build forbids the toolkit's types: ok"
exit $status
