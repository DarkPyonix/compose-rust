#!/usr/bin/env bash
# What the Compose fork's window modules put on a native image's link line names no AWT.
#
# The GraalVM windows are the fork's graalvm-macos and graalvm-linux modules: Kotlin that is
# published as a jar and C that the build compiles into an object and hands to the linker. If
# either of them referred to java.awt, javax.swing, sun.awt or JAWT, the image would pull the
# toolkit in through a window that exists to do without it. Checked three ways, because each
# can hide what the others see:
#
#   the sources     no mention of the toolkit in the Kotlin or the C of either module
#   the jars        no reference to it in the compiled classes the renderer depends on,
#                   which is where a call through a library would show up
#   the objects     no AWT or JAWT symbol in the C compiled on this machine
#
# The jars are the ones renderer/scripts/publish-window.sh puts in ~/.m2, so this runs after
# a build has published them and says so when they are missing.
#
# What this does not cover: whether the skiko the image links is the fork's archive built
# without JAWT. This renderer's native image still links the stock one, with the toolkit
# forwarders beside it, so that is a property of the skiko archive and not of the windows.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$repo/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "window modules have no AWT"

pattern='java\.awt|javax\.swing|sun\.awt|JAWT|libawt|libjawt'
for module in graalvm-macos graalvm-linux; do
    dir="$fork_window/graalvm/$module"
    [ -d "$dir" ] || { fail "the fork has no $module"; continue; }
    if grep -rEn "$pattern" "$dir/src" "$dir/module.yaml" "$dir/c" "$dir/native" 2>/dev/null; then
        fail "$module names the toolkit (above)"
    else
        echo "  ok: the sources of $module name no toolkit"
    fi
done

repository="${DXC_M2_REPOSITORY:-$HOME/.m2/repository}/org/thisisthepy/compose/window"
class_pattern='java/awt|javax/swing|sun/awt'
for artifact in common-jvm graalvm-macos graalvm-linux; do
    jar="$repository/$artifact/0.1.0/$artifact-0.1.0.jar"
    if [ ! -f "$jar" ]; then
        fail "$jar is missing; run renderer/scripts/publish-window.sh first"
        continue
    fi
    # Class files hold names as UTF-8 constants, so a plain search finds a reference.
    if unzip -p "$jar" '*.class' | grep -aEq "$class_pattern"; then
        fail "$artifact.jar has classes that refer to the toolkit"
    else
        echo "  ok: the classes in $artifact.jar refer to no toolkit"
    fi
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
case "$(uname -s)" in
    Darwin)
        object="$work/appkit_window.o"
        cc -c -O2 -fobjc-arc -o "$object" "$fork_window/graalvm/graalvm-macos/native/appkit_window.m" ;;
    Linux)
        object="$work/x11_window.o"
        cc -c -O2 -fPIC -o "$object" "$fork_window/graalvm/graalvm-linux/c/x11_window.c" ;;
    *) object="" ;;
esac
if [ -n "$object" ]; then
    if nm "$object" | grep -Eiq 'awt|jawt'; then
        fail "$(basename "$object") refers to an AWT or JAWT symbol"
    else
        echo "  ok: $(basename "$object") refers to no AWT or JAWT symbol"
    fi
fi

[ "$failures" -eq 0 ] || exit 1
