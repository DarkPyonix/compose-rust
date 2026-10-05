#!/usr/bin/env bash
# Fails if a scripted resize left memory behind. Reads the phase lines the renderer prints
# with DXC_METRICS=1 (see resize-metrics-run.sh) and compares each reading taken during and
# after the drag with "start": "mid-drag" (after the first 50 sizes) and "after-100-resizes"
# (right after the last), which are what a drag holds while it is going on, and "settled",
# two seconds later once the run loop has had time to turn. "after-100-resizes" is required;
# the other two are checked when the renderer prints them.
#
# The scripted drag sets sizes directly and turns the run loop between them, because a drag
# of the edge does. "event-drag" checks that assumption against the real path: a drag made
# of mouse events that AppKit runs its own live resize for, read at the last position with
# the button held, and "event-settled" two seconds after release. When the renderer prints
# them, the drag must have widened the window, or it measured nothing.
#
# The bounds, in MB above the start:
#
#   metal      112  Core Animation keeps up to three drawables. At 1200x900 points and two
#                   pixels per point each is 16.5 MB, so three are 50 MB. A frame's drawable
#                   goes back with the frame's own autorelease pool; the rest of the bound
#                   is Skia's own resources and margin. The leak this guards against grew
#                   it by 528 MB.
#   footprint  160  The Metal bound above, which the footprint includes, plus what the
#                   process itself grows: the native image grew 17 MB over 50 resizes (46 to
#                   63) and this renderer 23 MB (37 to 60), both on macOS arm64, and the
#                   metrics run dirties about 10 MB of heap before the drag. The leak grew
#                   the footprint by 540 MB.
#
# Usage: resize-memory-check.sh <log> [footprint bound MB] [metal bound MB]
set -euo pipefail
log="$1"
footprint_bound="${2:-160}"
metal_bound="${3:-112}"

value() {
    local phase="$1" key="$2" line
    line="$(grep "metrics phase $phase " "$log" | tail -1)"
    [[ -n "$line" ]] || { echo "no '$phase' phase line in $log: the scripted resize did not run" >&2; exit 1; }
    sed -nE "s/.* $key=([0-9]+).*/\1/p" <<< "$line"
}

phases=()
for phase in mid-drag after-100-resizes settled event-drag event-settled; do
    if [[ "$phase" == after-100-resizes ]] || grep -q "metrics phase $phase " "$log"; then
        phases+=("$phase")
    fi
done
failed=0
if grep -q "metrics phase event-drag " "$log"; then
    widened="$(sed -nE 's/.*metrics event-drag widened_by=(-?[0-9]+).*/\1/p' "$log" | tail -1)"
    if [[ -z "$widened" ]] || (( widened <= 0 )); then
        echo "FAIL the drag made of mouse events did not resize the window (widened_by=${widened:-none})" >&2
        failed=1
    else
        echo "ok   the drag made of mouse events widened the window by $widened points"
    fi
fi
for phase in "${phases[@]}"; do
    for pair in "footprint_mb:$footprint_bound" "metal_allocated_mb:$metal_bound"; do
        key="${pair%%:*}"
        bound="${pair##*:}"
        start="$(value start "$key")"
        after="$(value "$phase" "$key")"
        [[ -n "$start" && -n "$after" ]] || { echo "the phase lines in $log carry no $key" >&2; exit 1; }
        grown=$((after - start))
        if (( grown > bound )); then
            echo "FAIL $key: $start -> $after MB at $phase, grew $grown MB, bound $bound MB" >&2
            failed=1
        else
            echo "ok   $key: $start -> $after MB at $phase, grew $grown MB, bound $bound MB"
        fi
    done
done
exit "$failed"
