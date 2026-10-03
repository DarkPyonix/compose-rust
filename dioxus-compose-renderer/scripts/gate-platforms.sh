#!/usr/bin/env bash
# Usage: ./dioxus-compose-renderer/scripts/gate-platforms.sh <build|test> [project-dir]
#
# Prints, one per line, the platforms this machine can build, or can run tests for. Used by
# scripts/check.sh so that the Kotlin gate runs everywhere rather than only on a machine
# with every SDK and a phone plugged into it.
#
# The list starts from the project's own modules, so a platform added later is covered
# without anyone remembering to add it here. Only the reasons to leave one out are written
# down, and each one is a thing this machine has not got rather than a thing we do not
# want built:
#
#   linuxX64   the window reaches Xlib, the sync extension and GLX through cinterop, and
#              cinterop compiles against the real headers rather than a copy of them. One
#              package manager line on Linux; XQuartz on macOS, which is a download.
#   mingwX64   Compose and skiko publish no Kotlin/Native target for Windows, so both are
#              built from the fork and published to the local Maven repository first
#              (scripts/build-compose.sh --target mingwX64, an hour and the MSVC headers).
#              Without that publication there is nothing for the module to compile against.
#              Its tests are compiled for Windows and run only there.
#   android    the tests are instrumented, so they need a device or an emulator. Building
#              needs neither, so it is only left out of the test run.
#   iosArm64   a device target has no test task at all, and naming one that has none is an
#              error rather than a skip.
#   iosSimulatorArm64
#              the tests run in a simulator, so there has to be one installed. Building
#              needs none, so this too is only left out of the test run. A machine with no
#              simulators is what this was written for: the failure it gives is an internal
#              error reading `No available device`, thrown after every other platform's
#              tests have already passed, which reads as the gate breaking rather than as
#              something not installed.
set -euo pipefail

case "${1:-}" in
    build|test) phase="$1" ;;
    *) echo "usage: $0 <build|test>" >&2; exit 2 ;;
esac

# Which project to read. The renderer by default, and the design systems project when it
# is named, because both are Amper projects gated the same way and the reasons to leave a
# platform out are the machine's rather than either project's.
project_dir="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
[[ -d "$project_dir" ]] || { echo "no such project directory: $project_dir" >&2; exit 2; }
cd "$project_dir"

all_platforms() {
    {
        # Every platform a multiplatform module declares.
        sed -n 's/^[[:space:]]*platforms:[[:space:]]*\[\(.*\)\][[:space:]]*$/\1/p' ./*/module.yaml |
            tr ',' '\n'
        # And the ones a product implies rather than declares. `kmp` is not one of them: a
        # multiplatform module says which platforms it is for in the key read above.
        sed -En 's@^[[:space:]]*product:[[:space:]]*(jvm|wasm-js)/.*$@\1@p' ./*/module.yaml
    } |
        sed 's/[[:space:]]//g' |
        grep -v '^$' |
        sed 's/^wasm-js$/wasmJs/' |
        sort -u
}

has_x11() {
    [[ -e /usr/include/X11/Xlib.h || -e /opt/X11/include/X11/Xlib.h ]]
}

# The Windows Compose this module compiles against, as build-compose.sh publishes it. One
# module stands for the fourteen: they are published together or not at all.
has_mingw_compose() {
    [[ -d "$HOME/.m2/repository/org/jetbrains/compose/ui/ui-mingwx64" ]]
}

is_windows() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

has_ios_simulator() {
    command -v xcrun >/dev/null 2>&1 || return 1
    # Available rather than booted: the test runner starts one, but it cannot install one.
    xcrun simctl list devices available 2>/dev/null |
        grep -qE '^[[:space:]]+[^[:space:]].*\([0-9A-F-]{36}\)'
}

has_android_device() {
    command -v adb >/dev/null 2>&1 || return 1
    # A device line is anything after the header that is not a blank line. `-l` is asked for
    # because a device listed as `unauthorized` or `offline` cannot run anything.
    adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'
}

all_platforms | while read -r platform; do
    case "$platform" in
        linuxX64) has_x11 || continue ;;
        mingwX64)
            has_mingw_compose || continue
            [[ "$phase" == "build" ]] || is_windows || continue
            ;;
        android) [[ "$phase" == "build" ]] || has_android_device || continue ;;
        iosArm64) [[ "$phase" == "build" ]] || continue ;;
        iosSimulatorArm64) [[ "$phase" == "build" ]] || has_ios_simulator || continue ;;
    esac
    echo "$platform"
done
