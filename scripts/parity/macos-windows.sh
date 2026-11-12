#!/usr/bin/env bash
# Usage: scripts/parity/macos-windows.sh <output directory> -- <multi-window executable>
#
# What several windows cost on the Kotlin/Native macOS renderer at rest: the CPU the process
# burns and the frames it draws while nothing happens, with one window and with three, and the
# memory a second window adds. The executable is the multi-window fixture
# (compose-rust/tests/fixtures/multi-window), built against the Kotlin/Native renderer; it
# takes `--windows N` for how many windows open at the start.
#
# Each run is the renderer's idle soak (DXC_IDLE_SOAK_SECONDS): the windows open, the renderer
# waits eight seconds for them to settle, collects garbage, prints an `idle-soak phase start`
# line, rests for the soak's seconds, prints `phase end`, and quits. The soak is driven from
# the first window (DXC_SYNTH_WINDOW=1), so `frames` is that window's count. The CPU is the
# process's whole, read with ps at the two lines, so it covers every window and the 60 Hz turn
# timer that drives them.
#
# Rows, one per window count N (1, 2, 3):
#   idle_cpu_percent_N    CPU time over wall time between start and end, in percent of a core
#   idle_frames_per_s_N   frames the first window drew per second of rest
#   footprint_mb_N        phys_footprint at the start of the rest
# and second_window_mb, footprint_mb_2 less footprint_mb_1.
#
# IDLE_SECONDS sets the rest (30 by default). A row that could not be read is FAIL; the script
# still exits 0 unless PARITY_STRICT=1.
set -uo pipefail

if [[ $# -lt 3 || "$2" != "--" ]]; then
    echo "usage: $0 <output directory> -- <multi-window executable>" >&2
    exit 2
fi
out="$1"
shift 2
mkdir -p "$out"
out="$(cd "$out" && pwd)"
label="macos-windows"
table="$out/$label.tsv"
: > "$table"

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

seconds="${IDLE_SECONDS:-30}"

# ps prints CPU time as [[dd-]hh:]mm:ss.cc; this turns it into seconds.
cpu_seconds() {
    ps -o time= -p "$1" 2>/dev/null | awk '{
        n = split($1, part, ":"); total = 0
        for (i = 1; i <= n; i++) {
            v = part[i]
            if (i == 1 && index(v, "-") > 0) { split(v, d, "-"); total = d[1] * 24; v = d[2] }
            total = total * 60 + v
        }
        printf "%.3f", total
    }'
}

now() { perl -MTime::HiRes=time -e 'printf "%.3f", time'; }

# Plain variables rather than an array: the bash macOS ships is 3.2, which has no associative ones.
footprint_1="" footprint_2=""

for windows in 1 2 3; do
    err="$out/windows-$windows.err"
    : > "$err"
    DXC_IDLE_SOAK_SECONDS="$seconds" DXC_SYNTH_WINDOW=1 "$@" --windows "$windows" \
        >"$out/windows-$windows.out" 2>"$err" &
    app=$!
    trap 'kill "$app" 2>/dev/null || true' EXIT

    start_cpu="" start_wall="" end_cpu="" end_wall=""
    # The soak ends the process itself: eight seconds to settle, the rest, and a minute spare.
    limit=$(( (seconds + 70) * 10 ))
    for _ in $(seq 1 "$limit"); do
        kill -0 "$app" 2>/dev/null || break
        if [[ -z "$start_cpu" ]] && grep -q 'idle-soak phase start' "$err"; then
            start_cpu="$(cpu_seconds "$app")"
            start_wall="$(now)"
        fi
        if [[ -n "$start_cpu" && -z "$end_cpu" ]] && grep -q 'idle-soak phase end' "$err"; then
            end_cpu="$(cpu_seconds "$app")"
            end_wall="$(now)"
        fi
        sleep 0.1
    done
    if kill -0 "$app" 2>/dev/null; then
        kill "$app" 2>/dev/null || true
        row "exit_$windows" "FAIL the run did not end itself"
    fi
    wait "$app" 2>/dev/null
    trap - EXIT

    if [[ -n "$start_cpu" && -n "$end_cpu" ]]; then
        row "idle_cpu_percent_$windows" "$(awk -v c0="$start_cpu" -v c1="$end_cpu" -v w0="$start_wall" -v w1="$end_wall" \
            'BEGIN { printf "%.2f", (c1 - c0) * 100 / (w1 - w0) }')"
    else
        row "idle_cpu_percent_$windows" "FAIL the idle soak's start and end lines were not both seen"
    fi
    phase_field() { sed -nE "s/.*idle-soak phase $1 .*$2=([0-9-]+).*/\1/p" "$err" | tail -1; }
    frames_start="$(phase_field start frames)"
    frames_end="$(phase_field end frames)"
    if [[ -n "$frames_start" && -n "$frames_end" ]]; then
        row "idle_frames_per_s_$windows" "$(awk -v a="$frames_start" -v b="$frames_end" -v s="$seconds" \
            'BEGIN { printf "%.2f", (b - a) / s }')"
    else
        row "idle_frames_per_s_$windows" "FAIL not reported"
    fi
    footprint="$(phase_field start footprint_mb)"
    [[ "$windows" == 1 ]] && footprint_1="$footprint"
    [[ "$windows" == 2 ]] && footprint_2="$footprint"
    row "footprint_mb_$windows" "${footprint:-FAIL not reported}"
done

if [[ -n "$footprint_1" && -n "$footprint_2" ]]; then
    row second_window_mb "$(( footprint_2 - footprint_1 ))"
else
    row second_window_mb "FAIL a footprint was not reported"
fi

print_table
