#!/usr/bin/env bash
# Checks that every ported sample builds the same Renderer tree as its rsx original after
# every step of every recorded interaction: one pass of each scenario on each path, and a
# comparison that reads the trees and the record counts and nothing else.
#
#   benchmarks/fr39/parity.sh
#
# This is the whole-sample half of the parity check. The fixtures in
# dioxus-compose/tests/compose_parity.rs are the half that runs with cargo test.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bench="$repo_root/benchmarks/fr39"
out="$repo_root/.scratch/fr39/parity-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

cd "$bench"
CARGO_BUILD_JOBS=2 cargo build --release --bins

runs=()
for app in sweep calculator todo chat minimal; do
    for side in baseline candidate; do
        bin="fr39-$side-$app"
        (cd "$out" && "$bench/target/release/$bin" --iterations 1 --warmup 0 --out "$out/$bin.json")
    done
done

"$bench/target/release/compare" --trees-only \
    --baseline "$out"/fr39-baseline-*.json \
    --candidate "$out"/fr39-candidate-*.json
