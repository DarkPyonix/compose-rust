#!/usr/bin/env bash
# Checks that every ported sample builds the same Renderer tree as its rsx original after
# every step of every recorded interaction: one pass of each scenario on each path, and a
# comparison that reads the trees and the record counts and nothing else.
#
#   compose-rust/benches/fr39/parity.sh
#
# This is the whole-sample half of the parity check. The other half, fixtures written both
# ways in one test binary, needs the Dioxus layer and the slot table runtime linked
# together, so it lives with the Dioxus layer in dioxus-compose.
set -euo pipefail

bench="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$bench/../../.." && pwd)"

# The baseline's manifest names the dioxus-compose commit it builds, and
# scripts/bench/fr39-baseline.env is where the project says which commit that is. A
# measurement of anything else is not the comparison, so the two have to agree.
# shellcheck source=/dev/null
source "$repo_root/scripts/bench/fr39-baseline.env"
if ! grep -q "rev = \"$FR39_BASELINE_REV\"" "$bench/baseline/Cargo.toml"; then
    echo "fail  baseline/Cargo.toml does not build the pinned dioxus-compose $FR39_BASELINE_REV" >&2
    echo "      Change scripts/bench/fr39-baseline.env and baseline/Cargo.toml together." >&2
    exit 1
fi
out="$repo_root/.scratch/fr39/parity-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

cd "$bench"
CARGO_BUILD_JOBS=2 cargo build --release --bins

for side in baseline candidate; do
    (cd "$out" && "$bench/target/release/fr39-$side" --iterations 1 --warmup 0 --out "$out/$side.json")
done

"$bench/target/release/compare" --trees-only \
    --baseline "$out/baseline.json" \
    --candidate "$out/candidate.json"
