#!/usr/bin/env bash
# .github/scripts/native-needs-macos.sh: which pull requests let test-native-renderer.yml
# leave its macOS jobs out. Anything unknown, or nothing at all, must run them.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/native-needs-macos.sh"
failures=0
check() { # expected description files...
    local expected="$1" what="$2"; shift 2
    local got; got="$(printf '%s\n' "$@" | "$script")"
    if [[ "$got" == "macos=$expected" ]]; then echo "ok   - $what"; else echo "FAIL - $what: $got"; failures=$((failures + 1)); fi
}
check false "Windows renderer only" renderer/windows/src/Main.kt renderer/desktop/scripts/build-windows.sh .github/workflows/windows-static.yml
check false "the Windows consumer check and the multi-window fixture it builds" scripts/check-windows-consumer.sh compose-rust/tests/fixtures/multi-window/src/app.rs
check false "the HiDPI check on Windows, its scale tool and its fixture" .github/scripts/hidpi-windows-check.sh .github/scripts/set-display-scale.c compose-rust/tests/fixtures/hidpi/src/main.rs
check true "the consumer fixture (the macOS single executable builds it)" compose-rust/tests/fixtures/consumer/src/main.rs
check false "Linux renderer only" renderer/linux/src/shared/Window.kt .github/scripts/check-linux-input-access.sh
check true "the packager and the single-executable check (macOS legs run them)" .github/scripts/package-static-renderer.sh renderer/desktop/scripts/build-windows.sh
check true "shared desktop sources" renderer/desktop/src/Main.kt
check true "macOS renderer" renderer/macos/src/Main.kt
check true "iOS renderer" renderer/ios/src/shared/Interpreter.kt
check true "compose-rust build script" compose-rust/build.rs
check true "this workflow" .github/workflows/test-native-renderer.yml
check true "mixed: Windows and shared" renderer/windows/src/Main.kt renderer/libs.versions.toml
check true "a quoted name" '"renderer/windows/\303\244.kt"'
out="$("$script" < /dev/null)"; [[ "$out" == "macos=true" ]] && echo "ok   - no file list runs macOS" || { echo "FAIL - empty: $out"; failures=$((failures + 1)); }

# Wiring, read as text (PyYAML is not on every runner, and a check that skips there proves
# nothing): no job-level if reads the matrix, the macOS jobs wait on the changed job's
# decision, and the single-executable matrix comes from it.
wf="$repo_root/.github/workflows/test-native-renderer.yml"
check_wf() { # description pattern expected(yes|no)
    if grep -qE "$2" "$wf"; then found=yes; else found=no; fi
    if [[ "$found" == "$3" ]]; then echo "ok   - $1"; else echo "FAIL - $1"; failures=$((failures + 1)); fi
}
check_wf "no job-level if reads the matrix" '^    if:.*matrix\.' no
check_wf "macos-static follows the macOS decision" "needs.changed.outputs.renderer == 'true' && needs.changed.outputs.macos != 'false'" yes
check_wf "single-executable takes its matrix from changed" 'fromJSON\(needs.changed.outputs.executables\)' yes
[[ "$(grep -c "needs.changed.outputs.macos != 'false'" "$wf")" -eq 2 ]] && echo "ok   - exactly macos-static and ios follow it" || { echo "FAIL - the macOS decision is used by other jobs than macos-static and ios"; failures=$((failures + 1)); }

# The macOS Kotlin/Native renderer test is not started by the Windows build's own files.
# It used to leave them out of its pull_request path filter. It now runs only when the
# dispatched development CI calls it, so no changed file starts it at all: it has neither a
# pull_request trigger nor a path filter.
kn="$repo_root/.github/workflows/test-kn-desktop-renderer.yml"
kn_on="$(awk '/^on:/{f=1;next} /^[^ #]/{f=0} f' "$kn")"
if grep -qE '^  workflow_call:' <<<"$kn_on" && ! grep -qE '^  (pull_request|push):|^    paths' <<<"$kn_on"; then
    echo "ok   - kn-desktop is started by no file change, the Windows build's included"
else echo "FAIL - kn-desktop is started by changed files again"; failures=$((failures + 1)); fi
[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"
