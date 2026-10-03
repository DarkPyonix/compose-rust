#!/usr/bin/env bash
# Runs the performance comparison of the two authoring paths: every scenario on the Dioxus
# path at the pinned commit and on the slot table path at this checkout, the same day, the
# same machine, the same release profile, one after the other, and then compares them.
#
#   benchmarks/fr39/run.sh [ITERATIONS] [WARMUP]
#
# Raw runs go to .scratch/fr39/<timestamp>/, inside this checkout. The comparison is
# written into the fr39 entry of adapters/dioxus/benches/baseline.json together with the
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

load_average() {
    if [[ -r /proc/loadavg ]]; then
        cut -d' ' -f1 /proc/loadavg
    else
        sysctl -n vm.loadavg | awk '{print $2}'
    fi
}

# One scenario at a time, the two paths back to back, so a change in the machine's load
# during the run falls on both of them rather than on whichever went second.
scenarios=(sweep_1 sweep_5 sweep_17 sweep_33 sweep_65 sweep_129 calculator_input
    todo_add_delete chat_streaming long_list_scroll tab_switching)
loads=()
baseline_runs=()
candidate_runs=()
for scenario in "${scenarios[@]}"; do
    for side in baseline candidate; do
        loads+=("$(load_average)")
        # The working directory is the run's own folder, so whatever a sample reads from
        # the current directory starts empty.
        (cd "$out" && "$bench/target/release/fr39-$side" --scenario "$scenario" \
            --iterations "$iterations" --warmup "$warmup" --out "$out/$side-$scenario.json")
        if [[ "$side" == baseline ]]; then
            baseline_runs+=("$out/$side-$scenario.json")
        else
            candidate_runs+=("$out/$side-$scenario.json")
        fi
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
    --record "$repo_root/adapters/dioxus/benches/baseline.json"
