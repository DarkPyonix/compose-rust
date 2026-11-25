#!/usr/bin/env bash
# Usage: .github/scripts/check-bench-workspaces.sh
#
# Formats, lints and tests every cargo workspace directly under compose-rust/benches/. Each
# of them is a workspace of its own, outside the repository's, so scripts/check.sh and the
# Rust job never reach them, and their code drifted unformatted and with lint warnings
# while nothing looked. A workspace is a compose-rust/benches/<name>/Cargo.toml with a
# [workspace] table, so a new one is checked without editing this file. Finding none is a
# failure: a check that silently checks nothing passes forever.
#
# It builds nothing that draws. The bench crates turn compose-rust's mock-renderer feature
# on (the baseline through dioxus-compose's), and the build script links no renderer when
# that feature is on, so no renderer, stand-in or system link library is needed.
#
# --locked on clippy and test: the bench keeps its own Cargo.lock, which went stale once
# without anyone noticing. Without --locked cargo rewrites it on the runner and the check
# passes on a lock file that is not the one checked in. A change that moves a dependency the
# bench shares, such as the compose-rust version, has to refresh that lock file as well.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

workspaces=()
for manifest in "$repo_root"/compose-rust/benches/*/Cargo.toml; do
    [[ -f "$manifest" ]] || continue
    if grep -qE '^\[workspace\][[:space:]]*$' "$manifest"; then
        workspaces+=("$(dirname "$manifest")")
    fi
done

if (( ${#workspaces[@]} == 0 )); then
    echo "check-bench-workspaces: no cargo workspace found under compose-rust/benches/" >&2
    exit 1
fi

run() { # workspace command...
    local workspace="$1"; shift
    echo "== ${workspace#"$repo_root"/}: $*"
    (cd "$workspace" && "$@")
}

for workspace in "${workspaces[@]}"; do
    run "$workspace" cargo fmt --all -- --check
    run "$workspace" cargo clippy --workspace --all-targets --locked -- -D warnings
    run "$workspace" cargo test --workspace --locked
done
echo "check-bench-workspaces: ${#workspaces[@]} workspace(s) checked"
