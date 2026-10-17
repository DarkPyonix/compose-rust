#!/usr/bin/env bash
# Fails if the content of a window moves on the screen while its height is dragged.
#
# Reads the log vertical-resize-run.sh leaves. The probe lays a magenta square over every
# frame at a fixed distance below the frame's top edge, drags the window's bottom edge down
# and back and then its top edge up and back, and from another thread takes the window's
# image over and over, printing how far below the top of the window the square is
# (marker_top_pt). Content that stays where it was drawn keeps that distance through the
# whole drag. It must not move by more than a point, and it must be on the screen in every
# image: a frame that is missing or anchored to the wrong edge shows either as a distance
# that changes or as no square at all.
#
# The drag must also have changed the window's height, or it measured nothing.
#
# Usage: vertical-resize-check.sh <log> [tolerance in points]
set -euo pipefail
log="$1"
tolerance="${2:-1.0}"

grep -q "vresize begin" "$log" || { echo "FAIL the vertical resize probe did not start (no 'vresize begin' in $log)" >&2; exit 1; }
grep -q "vresize done" "$log" || { echo "FAIL the vertical resize probe did not finish (no 'vresize done' in $log)" >&2; exit 1; }
grep -E "vresize (begin|error)" "$log" || true

awk -v tolerance="$tolerance" '
    function field(name,   i, kv) {
        for (i = 1; i <= NF; i++) if (index($i, name "=") == 1) { return substr($i, length(name) + 2) }
        return ""
    }
    /vresize step / { steps++ }
    /vresize drawn / { drawn++ }
    /vresize sample / {
        if (field("error") != "") { errors++; next }
        n++
        h = field("image_height_px") + 0
        s = field("scale") + 0
        if (s <= 0) s = 1
        hp = h / s
        if (n == 1 || hp < hmin) hmin = hp
        if (n == 1 || hp > hmax) hmax = hp
        if (field("found") != "1") { missing++; if (missing <= 10) bad[++nbad] = "missing: " $0; next }
        y = field("marker_top_pt") + 0
        ys[++found] = y
        line[found] = $0
        count[sprintf("%.2f", y)]++
    }
    END {
        printf "samples=%d found=%d missing=%d capture_errors=%d steps=%d frames_drawn=%d\n", n, found, missing, errors, steps, drawn
        failed = 0
        if (n < 20) { print "FAIL fewer than 20 images of the window were taken"; failed = 1 }
        printf "window height on screen: %.1f to %.1f pt\n", hmin, hmax
        if (hmax - hmin < 50) { printf "FAIL the drag changed the window height by %.1f pt, under 50: it measured nothing\n", hmax - hmin; failed = 1 }
        if (found > 0) {
            # The distance most images agree on is where the content belongs.
            best = ""; for (k in count) if (best == "" || count[k] > count[best]) best = k
            ymin = ys[1]; ymax = ys[1]; off = 0
            for (i = 1; i <= found; i++) {
                if (ys[i] < ymin) ymin = ys[i]
                if (ys[i] > ymax) ymax = ys[i]
                d = ys[i] - best; if (d < 0) d = -d
                if (d > tolerance) { off++; if (off <= 15) bad[++nbad] = sprintf("moved %+.2f pt: %s", ys[i] - best, line[i]) }
            }
            printf "marker_top_pt: usual %.2f, min %.2f, max %.2f, spread %.2f pt; %d of %d images off by more than %.2f pt\n", best, ymin, ymax, ymax - ymin, off, found, tolerance
            if (ymax - ymin > tolerance) { printf "FAIL the content moved by %.2f pt during the vertical drag, more than %.2f\n", ymax - ymin, tolerance; failed = 1 }
        }
        if (missing > 0) { printf "FAIL %d images had no content where it was drawn\n", missing; failed = 1 }
        for (i = 1; i <= nbad; i++) print "  " bad[i]
        if (!failed) print "ok   the content stayed within " tolerance " pt of where it was drawn through the vertical drag"
        exit failed
    }
' "$log"
