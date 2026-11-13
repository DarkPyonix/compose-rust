#!/usr/bin/env bash
# Builds skiko's JVM natives as a static archive with JAWT left out, for the macOS native image.
#
# Usage: build-static-skiko.sh
#
# The script that does it is the Compose fork's extended/skiko/build-skiko-static-jvm.sh, at the
# commit build-compose.sh pins, run with --no-jawt: Skiko_GetAWT answers "no AWT", so the archive
# refers to nothing in JAWT and the image links neither libjawt nor libawt for Skia's sake.
#
# Prints the directory holding libskiko-static.a and skia/*.a. Costs a few minutes the first
# time, and nothing after that while the archive is there.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../../.." && pwd)"
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) platform="macos-arm64" ;;
    *) echo "error: the static skiko is built for macOS arm64 only so far (this is $(uname -s) $(uname -m))" >&2; exit 1 ;;
esac

fork_skiko="$("$REPO/scripts/fetch-fork-skiko.sh")/extended/skiko"
work="$REPO/renderer/build/static-skiko"
out="$work/out/$platform"
if [[ ! -f "$out/libskiko-static.a" ]]; then
    # skiko's Gradle build wants a JDK 17 or 21 and the image builder's JDK is neither.
    if [[ -z "${DXC_SKIKO_JAVA_HOME:-}" ]]; then
        DXC_SKIKO_JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
    [[ -n "$DXC_SKIKO_JAVA_HOME" ]] || {
        echo "error: no JDK 17 or 21 for skiko's Gradle build; set DXC_SKIKO_JAVA_HOME" >&2
        exit 1
    }
    mkdir -p "$work"
    JAVA_HOME="$DXC_SKIKO_JAVA_HOME" "$fork_skiko/build-skiko-static-jvm.sh" --no-jawt "$work" >&2
fi
[[ -f "$out/libskiko-static.a" ]] || { echo "error: no $out/libskiko-static.a" >&2; exit 1; }
echo "$out"
