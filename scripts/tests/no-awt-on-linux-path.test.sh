#!/usr/bin/env bash
# The renderer Linux ships opens its own X11 window and draws with Skia over GLX, so it does
# not load the Java toolkit: no X11 toolkit, no input method or accessibility
# registration, and no preserved java.desktop module.
#
# This test fails when:
#   - the Linux build script preserves java.desktop, registers the toolkit's input method or
#     accessibility features,
#   - the Linux entry point stops declaring that there is no display for the toolkit,
#   - the stub that stands in for libjawt is missing,
#   - a source outside the toolkit-window list names a toolkit type (the same scan the macOS
#     test makes, run here so a Linux-only change cannot slip past it).
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
desktop="renderer/desktop"
status=0
fail() { echo "FAIL: $1"; status=1; }

build="$desktop/scripts/build-native-linux.sh"
[[ -f "$build" ]] || { echo "FAIL: $build is missing"; exit 1; }

code="$(sed 's/[[:space:]]*#.*$//' "$build")"
if grep -q 'Preserve=module=java.desktop' <<< "$code"; then
    fail "$build preserves the whole java.desktop module"
fi
if grep -q 'ImeReachabilityFeature\|AccessibilityReachabilityFeature' <<< "$code"; then
    fail "$build registers the toolkit's input method or accessibility bridge"
fi
if ! grep -q 'jawt_absent\.c' <<< "$code" || [[ ! -f "$desktop/c/jawt_absent.c" ]]; then
    fail "the stub libjawt is not built from $desktop/c/jawt_absent.c"
fi

entry="$desktop/src/RendererEntryPoints.kt"
branch="$(awk '/if \(platform.startsWith\("Linux"\)\) \{/{on=1} on{print} on && /^        \}/{exit}' "$entry")"
grep -q 'java.awt.headless' <<< "$branch" || fail "the Linux branch does not declare there is no display for the toolkit"
grep -q 'runX11Window(' <<< "$branch" || fail "the Linux branch does not open the X11 window"
if grep -v 'java\.awt\.headless' "$entry" | grep -qE 'java\.awt|bringAwtWindowToFront'; then
    fail "$entry reaches the toolkit, which the Linux image would then carry"
fi

bash "$repo_root/scripts/tests/no-awt-on-macos-path.test.sh" > /dev/null || fail "a source names the Java toolkit outside the toolkit-window list (run no-awt-on-macos-path.test.sh)"

[[ $status -eq 0 ]] && echo "the Linux path names no Java toolkit: ok"
exit $status
