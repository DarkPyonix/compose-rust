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

# Wiring: no matrix in a job-level if, and the macOS jobs wait on the changed job's output.
wf="$repo_root/.github/workflows/test-native-renderer.yml"
if python3 -c 'import yaml' 2>/dev/null; then
    if python3 - "$wf" <<'PY'
import sys, yaml
jobs = yaml.safe_load(open(sys.argv[1]))["jobs"]
bad = [n for n, j in jobs.items() if "matrix." in str(j.get("if", ""))]
assert not bad, f"job-level if reads the matrix in {bad}"
for n in ("macos-static", "ios"):
    assert "needs.changed.outputs.macos != 'false'" in jobs[n]["if"], f"{n} ignores the macOS decision"
assert "fromJSON(needs.changed.outputs.executables)" in str(jobs["single-executable"]["strategy"]["matrix"])
for n in ("linux-static", "linux-arm64-static", "windows"):
    assert "macos" not in str(jobs[n].get("if", "")), f"{n} must not depend on the macOS decision"
PY
    then echo "ok   - workflow wiring"; else echo "FAIL - workflow wiring"; failures=$((failures + 1)); fi
else
    echo "skip - no PyYAML for the wiring check"
fi
[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"
