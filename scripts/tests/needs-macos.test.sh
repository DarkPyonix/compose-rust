#!/usr/bin/env bash
# .github/scripts/needs-macos.sh: which changes let test.yml leave its macOS jobs out.
# Anything unknown, or nothing at all, must run them.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/needs-macos.sh"
failures=0
check() { # expected description files...
    local expected="$1" what="$2"; shift 2
    local got; got="$(printf '%s\n' "$@" | "$script")"
    if [[ "$got" == "macos=$expected" ]]; then echo "ok   - $what"; else echo "FAIL - $what: $got"; failures=$((failures + 1)); fi
}
check false "docs only" docs/SPEC.md docs/guide/index.md
check false "root markdown and licence" README.md CHANGELOG.md PROJECT.md LICENSE
check false "other workflows" .github/workflows/pages.yml .github/workflows/windows-static.yml
check false "Windows renderer files" renderer/windows/src/Main.kt renderer/desktop/scripts/build-windows.sh scripts/check-windows-consumer.sh
check true "test.yml itself" .github/workflows/test.yml
check true "the reusable fork workflow" .github/workflows/fork-desktop-modules.yml
check true "a CI script (the shell suite tests it on macOS)" .github/scripts/package-static-renderer.sh
check true "Rust source" compose-rust/src/lib.rs
check true "a docs change beside Rust source" docs/SPEC.md compose-rust/src/lib.rs
check true "the macOS renderer" renderer/macos/src/Main.kt
check true "a root file nobody listed" Cargo.toml
check true "a script under scripts/" scripts/check.sh
check true "Windows desktop sources the JVM job compiles" renderer/desktop/src/NativeWindowShell.kt
out="$("$script" < /dev/null)"; [[ "$out" == "macos=true" ]] && echo "ok   - no file list runs macOS" || { echo "FAIL - empty: $out"; failures=$((failures + 1)); }
[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"
