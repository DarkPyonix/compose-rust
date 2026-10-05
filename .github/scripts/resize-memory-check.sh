#!/usr/bin/env bash
# Fails if a drag of the window's edge holds too much memory, or if what it shows on the
# screen is not what was drawn. Reads the lines the renderer prints with DXC_METRICS=1 (see
# resize-metrics-run.sh).
#
# Two drags run. The scripted one sets 100 sizes inside a single turn of the run loop, which
# no hand's drag does: Core Animation gets each size's surfaces back from the window server
# only when the loop turns, so its readings ("mid-drag", "after-100-resizes") are printed
# for the record and not bounded. "settled", two seconds after it, is bounded.
#
# The drag that is bounded is made of mouse events: 300 of them at 60 a second on the right
# edge, which AppKit runs its own live resize for, as it does for a hand. "event-drag" is
# read at the last position with the button still held, which is the most a drag holds, and
# "event-settled" two seconds after release. The drag must have widened the window, or it
# measured nothing.
#
# During that drag the renderer stops three times with the button held, draws a pattern of
# 4 pixel blocks over the frame, and compares the window's own image with it pixel for
# pixel ("metrics capture ... match="). A frame moved by a pixel or scaled by any amount
# matches about half its pixels; each capture must match at least 99%, at one image pixel
# per backing pixel.
#
# The bounds, in MB above the start:
#
#   metal      112  Core Animation keeps up to three drawables. At 1200x900 points and two
#                   pixels per point, rounded up to the 256 pixel step the drawable is
#                   sized in, each is about 18 MB, so three are 55 MB. A frame's drawable goes back
#                   with the frame's own autorelease pool; the rest of the bound is Skia's
#                   own resources and margin. The leak this guards against grew it by 528 MB.
#   footprint  160  The Metal bound above, which the footprint includes, plus what the
#                   process itself grows: the native image grew 17 MB over 50 resizes (46 to
#                   63) and this renderer 23 MB (37 to 60), both on macOS arm64, and the
#                   metrics run dirties about 10 MB of heap before the drag. A drawable of a
#                   new size at every event held 87 MB over a 50 event drag and grew with it.
#
# Usage: resize-memory-check.sh <log> [footprint bound MB] [metal bound MB]
set -euo pipefail
log="$1"
footprint_bound="${2:-160}"
metal_bound="${3:-112}"
match_floor="0.99"
captures_wanted=3

value() {
    local phase="$1" key="$2" line
    line="$(grep "metrics phase $phase " "$log" | tail -1)"
    [[ -n "$line" ]] || { echo "no '$phase' phase line in $log: the resize metrics did not run" >&2; exit 1; }
    sed -nE "s/.* $key=([0-9]+).*/\1/p" <<< "$line"
}

failed=0

for phase in mid-drag after-100-resizes; do
    grep -q "metrics phase $phase " "$log" || continue
    start="$(value start footprint_mb)"
    after="$(value "$phase" footprint_mb)"
    echo "info footprint_mb: $start -> $after MB at $phase (scripted drag, not bounded)"
done

widened="$(sed -nE 's/.*metrics event-drag widened_by=(-?[0-9]+).*/\1/p' "$log" | tail -1)"
if [[ -z "$widened" ]] || (( widened <= 0 )); then
    echo "FAIL the drag made of mouse events did not resize the window (widened_by=${widened:-none})" >&2
    failed=1
else
    echo "ok   the drag made of mouse events widened the window by $widened points"
fi

for phase in settled event-drag event-settled; do
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

captures=0
while IFS= read -r line; do
    captures=$((captures + 1))
    match="$(sed -nE 's/.* match=([0-9.]+).*/\1/p' <<< "$line")"
    if [[ -z "$match" ]] || ! awk -v m="$match" -v f="$match_floor" 'BEGIN { exit !(m >= f) }'; then
        echo "FAIL the window on screen is not the frame drawn: $line" >&2
        failed=1
    else
        echo "ok   on screen as drawn: $line"
    fi
done < <(grep -oE "metrics capture .*" "$log" || true)
if (( captures < captures_wanted )); then
    echo "FAIL $captures of $captures_wanted on-screen comparisons ran" >&2
    failed=1
fi
exit "$failed"
