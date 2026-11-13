#!/usr/bin/env bash
# The linked native image carries no AWT: no library it loads, no JAWT symbol it refers to, and
# no AWT or toolkit JNI entry point it defines.
#
# Usage: image-has-no-awt.test.sh [<image>]
#
# window-modules-no-awt.test.sh covers what the Compose fork's window modules put on the link
# line. This covers the whole image, which is where the stock skiko's JAWT getter, the JDK's
# static AWT and a toolkit that Compose reaches for a cursor would show up. The image defaults
# to the macOS one build-native.sh stages.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
image="${1:-$repo/renderer/build/native-image/dist/lib/libcompose_rust_renderer.dylib}"
[ -f "$image" ] || { echo "fail: no image at $image; build it first" >&2; exit 1; }
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "native image has no AWT"

case "$(uname -s)" in
    Darwin) needed="$(otool -L "$image" | tail -n +2 | awk '{print $1}')" ;;
    *) needed="$(readelf -d "$image" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p')" ;;
esac
if grep -Ei 'jawt|awt' <<< "$needed"; then
    fail "the image loads an AWT library (above)"
else
    echo "  ok: it loads no AWT library"
fi

undefined="$(nm -u "$image" 2>/dev/null | awk '{print $NF}')"
if grep -E 'JAWT_|^_?JAWT' <<< "$undefined"; then
    fail "the image refers to JAWT (above)"
else
    echo "  ok: it refers to no JAWT symbol"
fi

defined="$(nm -g --defined-only "$image" 2>/dev/null | awk '{print $NF}' || true)"
if grep -E 'Java_sun_awt|Java_sun_lwawt|Java_java_awt|JAWT_GetAWT|JNI_OnLoad_(awt|osxui)' <<< "$defined" | head -20 | grep .; then
    fail "the image defines AWT or toolkit entry points (above)"
else
    echo "  ok: it defines no AWT or toolkit entry point"
fi

[ "$failures" -eq 0 ] || exit 1
