#!/usr/bin/env bash
# Usage: scripts/check-windows-mingw-link.sh <renderer> <scratch directory>
#
# Kotlin exceptions and Kotlin's startup in an MSVC executable, with and without the rewrite
# that makes a MinGW object mean the same thing to an MSVC linker. Runs on Windows from Git
# Bash, after desktop/scripts/build-windows.sh, with cl.exe on PATH (a Visual Studio
# developer environment).
#
#   <renderer>           the directory build-windows.sh writes: the GCC runtime under gcc/
#                        and the MinGW bridge under native/ are taken from it, so the probe is
#                        linked from the same pieces an application is
#   <scratch directory>  where the probe is built; emptied first
#
# The probe is scripts/tests/fixtures/windows-exceptions: a Kotlin/Native static library for
# mingwX64, built the way the renderer is, and a C program that calls it. Three runs:
#
#   1. rewritten, `catch`: a thousand exceptions thrown three calls down are caught, and what
#      Kotlin computed at startup is there. Passing it means static constructors ran and the
#      unwinder found every function's unwind data.
#   2. rewritten, `uncaught`: Kotlin reports the exception and ends the process, rather than
#      the call returning to C or the process hanging.
#   3. as Kotlin/Native wrote it, `catch`: has to fail, by hanging or by crashing. This is the
#      control: if it passed, the first run would prove nothing about the rewrite.
#
# A hang is a failure of its own kind, so each run has a time limit.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$repo_root/scripts/tests/fixtures/windows-exceptions"
fixer="$repo_root/renderer/scripts/fix-mingw-objects.py"

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ;;
    *) fail "this links and runs a Windows executable and runs on Windows only" ;;
esac
command -v cl >/dev/null || fail "cl.exe is not on PATH" "Run this from a Visual Studio developer environment."
command -v timeout >/dev/null || fail "no timeout command; Git Bash has one"
python="$(command -v python3 || command -v python || true)"
[[ -n "$python" ]] || fail "no python on PATH"

renderer="$(cd "$1" && pwd)"
for needed in gcc/libstdc++.a gcc/libgcc.a gcc/libgcc_eh.a gcc/libwinpthread.a native/mingw_bridge.obj \
              native/dxc-windows-static-ucrt.lib; do
    [[ -f "$renderer/$needed" ]] || fail "no $needed in $renderer" "Run build-windows.sh first."
done

mkdir -p "$2"
work="$(cd "$2" && pwd)"
rm -rf "$work"
mkdir -p "$work"

# The Kotlin/Native compiler build-windows.sh used, from the toolchain's cache.
konanc=""
for candidate in "$(cygpath -u "${LOCALAPPDATA:-$HOME/AppData/Local}")"/JetBrains/Kotlin/extract.cache/*kotlin-native-prebuilt-*.d; do
    [[ -f "$candidate/bin/konanc.bat" ]] && konanc="$candidate/bin/konanc.bat"
done
[[ -n "$konanc" ]] || fail "no Kotlin/Native compiler in the toolchain cache" "Run build-windows.sh first."

echo "== the Kotlin half, for mingwX64, as Kotlin/Native writes it"
"$konanc" -produce static -target mingw_x64 -opt-in kotlin.experimental.ExperimentalNativeApi \
    "$(cygpath -m "$fixture/Exceptions.kt")" -o "$(cygpath -m "$work/probe")" >"$work/konanc.log" 2>&1 ||
    { cat "$work/konanc.log" >&2; fail "konanc did not build the probe"; }
[[ -f "$work/probe.a" ]] || fail "konanc wrote no probe.a"
cp "$work/probe.a" "$work/probe-unfixed.a"
"$python" "$fixer" "$(cygpath -m "$work/probe.a")"

# Linked with the static C runtime (/MT), which is the case that needs the bridge's import
# pointers as well; the GCC runtime the object carries, the bridge to MSVC's runtime, and the
# system libraries the Kotlin runtime reaches. The application's own link, with the UCRT from
# Windows, is check-windows-consumer.sh's.
link_probe() {
    local archive="$1" executable="$2"
    (
        cd "$work"
        MSYS_NO_PATHCONV=1 cl /nologo /MT /Fe"$executable" "$(cygpath -w "$fixture/probe.c")" \
            "$archive" \
            "$(cygpath -w "$renderer/gcc/libstdc++.a")" "$(cygpath -w "$renderer/gcc/libgcc.a")" \
            "$(cygpath -w "$renderer/gcc/libgcc_eh.a")" "$(cygpath -w "$renderer/gcc/libwinpthread.a")" \
            "$(cygpath -w "$renderer/native/mingw_bridge.obj")" \
            "$(cygpath -w "$renderer/native/dxc-windows-static-ucrt.lib")" \
            kernel32.lib user32.lib advapi32.lib shell32.lib ole32.lib bcrypt.lib ws2_32.lib \
            dbghelp.lib oldnames.lib legacy_stdio_definitions.lib \
            /link /NOLOGO >"$work/$executable.link.log" 2>&1
    ) || { cat "$work/$executable.link.log" >&2; fail "the MSVC link of $executable failed"; }
}
echo "== linking both with MSVC"
link_probe probe.a probe.exe
link_probe probe-unfixed.a probe-unfixed.exe

run() {
    local seconds="$1"
    shift
    local status=0
    ( cd "$work" && timeout "$seconds" "$@" ) >"$work/run.log" 2>&1 || status=$?
    cat "$work/run.log"
    return "$status"
}

echo "== rewritten: a thousand exceptions, three calls deep"
status=0
run 120 ./probe.exe catch || status=$?
[[ "$status" -ne 124 ]] || fail "the rewritten probe hung"
[[ "$status" -eq 0 ]] || fail "the rewritten probe failed (exit $status)"

echo "== rewritten: an exception nothing catches"
status=0
run 120 ./probe.exe uncaught || status=$?
[[ "$status" -ne 124 ]] || fail "an uncaught Kotlin exception hung the process"
[[ "$status" -ne 0 && "$status" -ne 42 ]] || fail "an uncaught Kotlin exception came back to C (exit $status)"
grep -q 'Uncaught Kotlin exception' "$work/run.log" ||
    fail "the process ended (exit $status) without Kotlin reporting the exception"

echo "== as Kotlin/Native wrote it: the control"
status=0
run 60 ./probe-unfixed.exe catch || status=$?
if [[ "$status" -eq 0 ]]; then
    fail "the probe linked without the rewrite passed too" \
        "Either the toolchain no longer writes .ctors and unpaired unwind data, and the rewrite" \
        "can go, or this probe no longer reaches them, and it proves nothing."
fi
echo "-- it failed, as it should (exit $status$([[ "$status" -eq 124 ]] && echo ", hung"))"

echo "ok    Kotlin's startup runs and its exceptions unwind in an MSVC executable, and only with the rewrite"
