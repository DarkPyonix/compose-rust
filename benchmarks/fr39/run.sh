#!/usr/bin/env bash
# Runs the FR-39 comparison: every scenario on the Dioxus path at the pinned commit and on
# the slot table path at this checkout, the same day, the same machine, the same release
# profile, one after the other, and then compares them.
#
#   benchmarks/fr39/run.sh [ITERATIONS] [WARMUP]
#
# Raw runs go to .scratch/fr39/<timestamp>/, inside this checkout. The comparison is
# written into the fr39 entry of dioxus-compose/benches/baseline.json together with the
# machine and the load average it was measured at. A load average above 1.0 is recorded
# as it is; the verdict is only to be read from a run on an idle machine.
#
# One build at a time, with two jobs, so the measurement does not compete with its own
# compilation and nothing else on the machine is starved.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bench="$repo_root/benchmarks/fr39"
iterations="${1:-50}"
warmup="${2:-5}"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$repo_root/.scratch/fr39/$stamp"
mkdir -p "$out"

cd "$bench"
CARGO_BUILD_JOBS=2 cargo build --release --bins

baseline_bins=(fr39-baseline-sweep fr39-baseline-calculator fr39-baseline-todo fr39-baseline-chat fr39-baseline-minimal)
candidate_bins=(fr39-candidate-sweep fr39-candidate-calculator fr39-candidate-todo fr39-candidate-chat fr39-candidate-minimal)

load_average() {
    if [[ -r /proc/loadavg ]]; then
        cut -d' ' -f1 /proc/loadavg
    else
        sysctl -n vm.loadavg | awk '{print $2}'
    fi
}

loads=()
baseline_runs=()
candidate_runs=()
# The two paths alternate, application by application, so a change in the machine's load
# during the run falls on both of them rather than on whichever went second.
for index in "${!baseline_bins[@]}"; do
    for side in baseline candidate; do
        if [[ "$side" == baseline ]]; then bin="${baseline_bins[$index]}"; else bin="${candidate_bins[$index]}"; fi
        loads+=("$(load_average)")
        # The working directory is the run's own folder, so whatever a sample reads from
        # the current directory starts empty.
        (cd "$out" && "$bench/target/release/$bin" --iterations "$iterations" --warmup "$warmup" --out "$out/$bin.json")
        if [[ "$side" == baseline ]]; then baseline_runs+=("$out/$bin.json"); else candidate_runs+=("$out/$bin.json"); fi
    done
done

machine="$(uname -sm)"
if command -v sysctl >/dev/null 2>&1 && sysctl -n machdep.cpu.brand_string >/dev/null 2>&1; then
    machine="$machine, $(sysctl -n machdep.cpu.brand_string)"
fi

"$bench/target/release/compare" \
    --baseline "${baseline_runs[@]}" \
    --candidate "${candidate_runs[@]}" \
    --load-average "${loads[*]}" \
    --machine "$machine" \
    --record "$repo_root/dioxus-compose/benches/baseline.json"
