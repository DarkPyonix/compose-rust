#!/usr/bin/env bash
# Fails if the code an application runs knows that it is being measured.
#
# The FR-39 comparison is only worth anything if both paths run, under the benchmark,
# exactly the code they run for a user. A `cfg(bench)`, a feature that switches something
# off, an environment variable the runtime reads to take a shorter route, or anything that
# names the harness would let a number be bought without the work it stands for. The
# harness lives in benchmarks/ and drives the public API; nothing it needs may reach back
# into the libraries.
#
# The libraries are every crate an application links: the runtime, its macro crate, and
# the Dioxus layer. Their manifests are checked too, because a feature is declared there.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

sources=()
for dir in dioxus-compose/src compose-rust-macros/src dioxus-compose-dioxus/src; do
    [[ -d "$dir" ]] && sources+=("$dir")
done
manifests=()
for manifest in dioxus-compose/Cargo.toml compose-rust-macros/Cargo.toml dioxus-compose-dioxus/Cargo.toml; do
    [[ -f "$manifest" ]] && manifests+=("$manifest")
done

failed=0
check() {
    local what="$1"
    local pattern="$2"
    shift 2
    local hits
    if hits="$(grep -rnE "$pattern" "$@" 2>/dev/null)"; then
        echo "FAIL  $what:"
        echo "$hits" | sed 's/^/      /'
        failed=1
    fi
}

check "a cfg that only a benchmark sets" 'cfg\(\s*bench|cfg\(\s*test_bench|cfg\(\s*feature\s*=\s*"bench' "${sources[@]}"
# Only the [features] table: `bench = false` on a target is how a manifest says a target is
# not a benchmark, which is the opposite of what this looks for.
for manifest in "${manifests[@]}"; do
    features="$(awk '/^\[features\]/{inside=1; next} /^\[/{inside=0} inside' "$manifest")"
    if hits="$(grep -nE '^\s*(bench|benchmark|measure|fr39)[A-Za-z0-9_-]*\s*=' <<< "$features")"; then
        echo "FAIL  a feature named for measuring in $manifest:"
        echo "$hits" | sed 's/^/      /'
        failed=1
    fi
done
check "the runtime reading a benchmark variable" 'env::var(_os)?\(\s*"[^"]*(BENCH|FR39|MEASURE)' "${sources[@]}"
check "the runtime naming the harness" 'fr39|criterion|is_bench|benchmark_mode|BENCH_MODE' "${sources[@]}"

if [[ "$failed" -ne 0 ]]; then
    echo "The libraries must not know they are being measured; see the comment above."
    exit 1
fi
echo "ok    no measurement-only path in the libraries"
