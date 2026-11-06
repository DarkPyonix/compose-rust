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
check false "docs only" docs/notes/plan.txt docs/guide/index.md
check false "root markdown and licence" README.md CHANGELOG.md PROJECT.md LICENSE
check false "workflows no shell test reads" .github/workflows/pages.yml .github/workflows/windows-static.yml
check true "a workflow the shell tests read" .github/workflows/test-native-renderer.yml
check true "static-renderer workflow" .github/workflows/static-renderer.yml
check false "a path with spaces under docs" "docs/a file with spaces.md"
check true "a quoted non-ASCII name git could not print plainly" '"docs/\303\244.md"'
check false "Windows renderer files" renderer/windows/src/Main.kt renderer/desktop/scripts/build-windows.sh scripts/check-windows-consumer.sh
check true "test.yml itself" .github/workflows/test.yml
check true "the reusable fork workflow" .github/workflows/fork-desktop-modules.yml
check true "a CI script (the shell suite tests it on macOS)" .github/scripts/package-static-renderer.sh
check true "Rust source" compose-rust/src/lib.rs
check true "a docs change beside Rust source" docs/notes/plan.txt compose-rust/src/lib.rs
check true "the macOS renderer" renderer/macos/src/Main.kt
check true "a root file nobody listed" Cargo.toml
check true "a script under scripts/" scripts/check.sh
check true "Windows desktop sources the JVM job compiles" renderer/desktop/src/NativeWindowShell.kt
# Wiring: test.yml must be valid, and the macOS legs must leave the matrices.
wf="$repo_root/.github/workflows/test.yml"
if python3 -c 'import yaml' 2>/dev/null; then
    if python3 - "$wf" <<'PY'
import sys, yaml
d = yaml.safe_load(open(sys.argv[1]))
jobs = d["jobs"]
bad = [n for n, j in jobs.items() if "matrix." in str(j.get("if", ""))]
assert not bad, f"job-level if reads the matrix in {bad}: GitHub rejects the workflow"
for n in ("rust", "kotlin", "shell"):
    j = jobs[n]
    needs = j["needs"] if isinstance(j["needs"], list) else [j["needs"]]
    assert "changed" in needs, f"{n} does not wait for changed"
    assert "fromJSON(needs.changed.outputs" in str(j["strategy"]["matrix"]), f"{n} matrix is not built by changed"
PY
    then echo "ok   - test.yml wiring: no matrix in job ifs, matrices come from changed"
    else echo "FAIL - test.yml wiring"; failures=$((failures + 1)); fi
else
    echo "skip - no PyYAML for the wiring check"
fi

# Renames: the old path counts, as the workflow lists it with --no-renames.
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
( cd "$tmp" && git init -q . && git config user.email t@t && git config user.name t &&
  mkdir -p renderer docs && echo x > renderer/Notes.md && echo y > "docs/seed.md" &&
  git add -A && git commit -qm base && git mv renderer/Notes.md docs/Notes.md && git commit -qam move )
got="$(cd "$tmp" && git -c core.quotepath=off diff --no-renames --name-only HEAD^1 HEAD | "$script")"
[[ "$got" == "macos=true" ]] && echo "ok   - a file moved out of renderer/ into docs/ runs macOS" || { echo "FAIL - rename: $got"; failures=$((failures + 1)); }

out="$("$script" < /dev/null)"; [[ "$out" == "macos=true" ]] && echo "ok   - no file list runs macOS" || { echo "FAIL - empty: $out"; failures=$((failures + 1)); }
[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"
