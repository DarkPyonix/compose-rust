#!/usr/bin/env bash
# .github/scripts/check-bench-workspaces.sh: every cargo workspace under compose-rust/benches/
# is formatted, linted and tested against its own lock file, a failure in any command fails
# the check, and finding no workspace at all fails it too.
#
# Runs the real script, copied into a fake tree, with a stub cargo on PATH that records what
# it was asked and fails on request. No cargo build runs.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/check-bench-workspaces.sh"
failures=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

scratch="$(mktemp -d "${TMPDIR:-/tmp}/bench-workspaces-check.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT

mkdir -p "$scratch/bin"
cat > "$scratch/bin/cargo" <<'STUB'
#!/usr/bin/env bash
echo "$(basename "$PWD"): cargo $*" >> "$CARGO_LOG"
[[ -n "${CARGO_FAIL:-}" && "$1" == "$CARGO_FAIL" ]] && exit 1
exit 0
STUB
chmod +x "$scratch/bin/cargo"

# A fake checkout: the script, one bench workspace and one bench crate that is not a workspace.
tree() { # name
    local root="$scratch/$1"
    mkdir -p "$root/.github/scripts"
    cp "$script" "$root/.github/scripts/"
    echo "$root"
}
fake="$(tree fake)"
mkdir -p "$fake/compose-rust/benches/fr39" "$fake/compose-rust/benches/loose"
printf '[workspace]\nmembers = ["a"]\n' > "$fake/compose-rust/benches/fr39/Cargo.toml"
printf '[package]\nname = "loose"\n' > "$fake/compose-rust/benches/loose/Cargo.toml"

run() { # root [CARGO_FAIL]
    : > "$scratch/log"
    CARGO_LOG="$scratch/log" CARGO_FAIL="${2:-}" PATH="$scratch/bin:$PATH" \
        bash "$1/.github/scripts/check-bench-workspaces.sh" > "$scratch/out" 2>&1
}

if run "$fake"; then ok "a tree with one bench workspace passes"; else fail "a clean tree failed: $(cat "$scratch/out")"; fi
want='fr39: cargo fmt --all -- --check
fr39: cargo clippy --workspace --all-targets --locked -- -D warnings
fr39: cargo test --workspace --locked'
if [[ "$(cat "$scratch/log")" == "$want" ]]; then
    ok "fr39 gets fmt, clippy and test, with --locked on clippy and test"
else
    fail "the commands run were:"$'\n'"$(cat "$scratch/log")"
fi
if grep -q '^loose:' "$scratch/log"; then fail "a bench crate that is not a workspace was checked"; else ok "a bench crate without [workspace] is left out"; fi
if grep -q 'compose-rust/benches/fr39: cargo clippy' "$scratch/out"; then ok "it says which workspace and command it runs"; else fail "the output does not name the workspace and command"; fi

for sub in fmt clippy test; do
    if run "$fake" "$sub"; then fail "a failing cargo $sub passed"; else ok "a failing cargo $sub fails the check"; fi
done

empty="$(tree empty)"
mkdir -p "$empty/compose-rust/benches/loose"
printf '[package]\nname = "loose"\n' > "$empty/compose-rust/benches/loose/Cargo.toml"
if run "$empty"; then
    fail "a tree with no bench workspace passed"
elif grep -q 'no cargo workspace found' "$scratch/out" && [[ ! -s "$scratch/log" ]]; then
    ok "no bench workspace fails without running cargo"
else
    fail "no bench workspace failed for another reason: $(cat "$scratch/out")"
fi

# Wired into the workflow, read as text: a script nothing calls checks nothing.
if grep -q 'run: ./.github/scripts/check-bench-workspaces.sh' "$repo_root/.github/workflows/test.yml"; then
    ok "test.yml runs the script"
else
    fail "test.yml does not run .github/scripts/check-bench-workspaces.sh"
fi

[[ $failures -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"
