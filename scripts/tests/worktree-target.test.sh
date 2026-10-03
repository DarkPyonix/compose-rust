#!/usr/bin/env bash
# Usage: ./scripts/tests/worktree-target.test.sh
#
# Every checkout of this repository builds into its own directory, and the two scripts
# that decide that agree with each other.
#
# What goes wrong when they do not: cargo leaves the package path out of the unit hash for
# a path package, so two checkouts holding identical sources are a single cache entry, and
# the binary that runs is whichever one compiled first. A run in the main checkout has
# already generated into an agent's worktree that way, and a sample built there came up
# with a black window because it had been compiled somewhere else.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

# --- nfr12_no_worktree_overrides_the_build_directory ------------------------
#
# The invariant itself. scripts/setup-worktrees.sh --check looks at every worktree and at
# CARGO_TARGET_DIR, and says which one is wrong and how to fix it.
check_output="$(./scripts/setup-worktrees.sh --check 2>&1)"
if [[ $? -eq 0 ]]; then
    pass "nfr12_no_worktree_overrides_the_build_directory"
else
    fail "nfr12_no_worktree_overrides_the_build_directory" \
         "scripts/setup-worktrees.sh --check reported:" \
         "$check_output"
fi

# --- nfr12_the_launcher_leaves_the_build_directory_to_one_script ------------
#
# The launcher used to write a cargo config of its own pointing every worktree at the main
# checkout. Two scripts answering the same question is how they came to disagree, so the
# launcher must ask rather than answer.
launcher=scripts/launch-agent.sh
if [[ ! -f "$launcher" ]]; then
    fail "nfr12_the_launcher_leaves_the_build_directory_to_one_script" \
         "$launcher is missing; the agent launcher belongs in this repository so that" \
         "this test can hold it to the same rule as scripts/setup-worktrees.sh."
else
    launcher_body="$(grep -vE '^[[:space:]]*#' "$launcher")"
    if grep -qE 'target[-_]dir|CARGO_TARGET_DIR' <<<"$launcher_body"; then
        fail "nfr12_the_launcher_leaves_the_build_directory_to_one_script" \
             "$launcher decides the build directory itself:" \
             "$(grep -nE 'target[-_]dir|CARGO_TARGET_DIR' <<<"$launcher_body")" \
             "It has to call scripts/setup-worktrees.sh instead."
    elif ! grep -q 'setup-worktrees.sh' <<<"$launcher_body"; then
        fail "nfr12_the_launcher_leaves_the_build_directory_to_one_script" \
             "$launcher never calls scripts/setup-worktrees.sh, so a worktree it creates" \
             "keeps whatever build directory it inherited."
    else
        pass "nfr12_the_launcher_leaves_the_build_directory_to_one_script"
    fi
fi

# --- nfr12_separate_build_directories_run_their_own_binaries ----------------
#
# The property everything above exists to buy, checked on a crate small enough to build in
# a second. Two copies of one source, each with its own build directory: each runs its own
# binary. The same two copies sharing a build directory do not, and this reports that
# rather than asserting it, because a future cargo that fixes the sharing must not turn
# this red. The rule stands either way: this repository does not share.
# pwd -P, because cargo reports the canonical manifest path and on macOS the
# temporary directory is reached through a symlink.
probe="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf "$probe"' EXIT

mkdir -p "$probe/first/src/bin"
cat > "$probe/first/Cargo.toml" <<'EOF'
[package]
name = "probe"
version = "0.0.0"
edition = "2021"
EOF
cat > "$probe/first/src/bin/probe.rs" <<'EOF'
fn main() {
    println!("{}", env!("CARGO_MANIFEST_DIR"));
}
EOF
cp -R "$probe/first" "$probe/second"

if ! command -v cargo >/dev/null 2>&1; then
    fail "nfr12_separate_build_directories_run_their_own_binaries" \
         "cargo is not on PATH, so this check could not run."
else
    (cd "$probe/first" && cargo build --quiet --bin probe) >/dev/null 2>&1
    first_says="$(cd "$probe/first" && cargo run --quiet --bin probe 2>/dev/null)"
    second_says="$(cd "$probe/second" && cargo run --quiet --bin probe 2>/dev/null)"
    if [[ "$first_says" == "$probe/first" && "$second_says" == "$probe/second" ]]; then
        pass "nfr12_separate_build_directories_run_their_own_binaries"
    else
        fail "nfr12_separate_build_directories_run_their_own_binaries" \
             "first/  ran a binary that says it was compiled in: ${first_says:-nothing}" \
             "second/ ran a binary that says it was compiled in: ${second_says:-nothing}" \
             "expected each to name its own directory."
    fi

    shared="$probe/shared"
    rm -rf "$probe/first/target" "$probe/second/target"
    (cd "$probe/first" && CARGO_TARGET_DIR="$shared" cargo build --quiet --bin probe) >/dev/null 2>&1
    shared_says="$(cd "$probe/second" && CARGO_TARGET_DIR="$shared" cargo run --quiet --bin probe 2>/dev/null)"
    if [[ "$shared_says" == "$probe/first" ]]; then
        printf '      sharing one build directory, second/ ran the binary compiled in first/\n'
        printf '      which is why this repository gives every worktree its own\n'
    else
        printf '      sharing one build directory, second/ ran a binary from %s\n' "${shared_says:-nothing}"
    fi
fi

if [[ $failures -gt 0 ]]; then
    echo "$failures worktree build directory check(s) failed" >&2
    exit 1
fi
echo "ok    worktree build directories are separate"

# --- nfr12_every_launcher_tells_its_run_where_to_work -----------------------
#
# Entering the worktree is not enough on its own. Two runs started by launch-agy.sh did
# their work in the main checkout anyway: they created the branch there, switched it twice
# while another worker was committing, and left that worker's commits on two unrelated
# branches. Whatever resolved the path, the run never read the cd. So each launcher says
# the working directory in the prompt, where the run cannot miss it.
for launcher in scripts/launch-agent.sh scripts/launch-agy.sh scripts/launch-codex.sh; do
    name="nfr12_${launcher##*/} names the worktree in the prompt"
    if grep -q 'Your working directory is' "$launcher"; then
        pass "$name"
    else
        fail "$name" \
            "$launcher enters the worktree but never says so to the run it starts." \
            "A run that resolves the repository some other way edits the main checkout," \
            "which is where another worker is committing."
    fi
done
