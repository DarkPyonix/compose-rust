#!/usr/bin/env bash
# Fails if a scripted resize left memory behind. Reads the phase lines the renderer prints
# with DXC_METRICS=1 (see resize-metrics-run.sh) and compares "after-100-resizes" with
# "start".
#
# The bounds, in MB above the start:
#
#   footprint   96  The native image grew 17 MB over 50 resizes (46 to 63) and this
#                   renderer 23 MB (37 to 60), both measured on macOS arm64. The metrics
#                   run also dirties about 10 MB of heap before the drag, and the window
#                   goes to 1200x900, at up to two pixels per point. 96 leaves room for all
#                   of that twice and is still a sixth of the 540 MB a frame's drawables
#                   held when they were left to the run loop's pool.
#   metal       64  Core Animation keeps up to three drawables. At 1200x900 points and two
#                   pixels per point each is 16.5 MB, so three are 50 MB, plus Skia's own
#                   resources. The same leak grew this by 528 MB.
#
# Usage: resize-memory-check.sh <log> [footprint bound MB] [metal bound MB]
set -euo pipefail
log="$1"
footprint_bound="${2:-96}"
metal_bound="${3:-64}"

value() {
    local phase="$1" key="$2" line
    line="$(grep "metrics phase $phase " "$log" | tail -1)"
    [[ -n "$line" ]] || { echo "no '$phase' phase line in $log: the scripted resize did not run" >&2; exit 1; }
    sed -nE "s/.* $key=([0-9]+).*/\1/p" <<< "$line"
}

failed=0
for pair in "footprint_mb:$footprint_bound" "metal_allocated_mb:$metal_bound"; do
    key="${pair%%:*}"
    bound="${pair##*:}"
    start="$(value start "$key")"
    after="$(value after-100-resizes "$key")"
    [[ -n "$start" && -n "$after" ]] || { echo "the phase lines in $log carry no $key" >&2; exit 1; }
    grown=$((after - start))
    if (( grown > bound )); then
        echo "FAIL $key: $start -> $after MB over 100 resizes, grew $grown MB, bound $bound MB" >&2
        failed=1
    else
        echo "ok   $key: $start -> $after MB over 100 resizes, grew $grown MB, bound $bound MB"
    fi
done
exit "$failed"
