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
# The probe also puts a plain AppKit view at the same distance from the top
# (control_top_pt), drawn by nobody but AppKit. Its spread is printed beside the content's:
# it says what the window server does to any content during the same drag. The native image's probe then drags a plain AppKit window of the
# same size and place, with only that view in it ("plain-sample" lines), and its spread is
# printed too: what the window server does to a window that nothing but AppKit draws.
#
# Two bounds, chosen by the caller:
#
#   (default)         the content moves by no more than the tolerance in any image.
#   --against-appkit  an image where the content is off by more than the tolerance passes
#                     only if AppKit's own view in the same image is off by the same amount,
#                     within the tolerance. The window server takes a step of a drag in
#                     pieces, and on the native image's window it sometimes shows the
#                     window's frame a step behind the layers for a few milliseconds; AppKit's
#                     own view is then off by the same amount. That is a frame of another
#                     height than the window on the screen, so the absolute bound is not met
#                     there, and the script says so whenever it happens. What this bound
#                     holds is that the content is never anywhere AppKit's own view is not.
#
# Usage: vertical-resize-check.sh <log> [tolerance in points] [--against-appkit]
set -euo pipefail
log="$1"
tolerance="${2:-1.0}"
against_appkit=0
[[ "${3:-}" == "--against-appkit" ]] && against_appkit=1

grep -q "vresize begin" "$log" || { echo "FAIL the vertical resize probe did not start (no 'vresize begin' in $log)" >&2; exit 1; }
grep -q "vresize done" "$log" || { echo "FAIL the vertical resize probe did not finish (no 'vresize done' in $log)" >&2; exit 1; }
grep -E "vresize (begin|error)" "$log" || true

awk -v tolerance="$tolerance" -v against_appkit="$against_appkit" '
    function field(name,   i, kv) {
        for (i = 1; i <= NF; i++) if (index($i, name "=") == 1) { return substr($i, length(name) + 2) }
        return ""
    }
    /vresize step / { steps++ }
    /vresize drawn / { drawn++ }
    /vresize plain-sample / {
        c = field("control_top_pt")
        if (c != "" && c + 0 >= 0) {
            plains++
            pcount[sprintf("%.2f", c + 0)]++
            ps[plains] = c + 0
        }
        next
    }
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
        c = field("control_top_pt")
        if (c != "" && c + 0 >= 0) {
            controls++
            if (controls == 1 || c + 0 < cmin) cmin = c + 0
            if (controls == 1 || c + 0 > cmax) cmax = c + 0
            ccount[sprintf("%.2f", c + 0)]++
            cs[controls] = c + 0
        }
        y = field("marker_top_pt") + 0
        cv[found + 1] = (c != "" && c + 0 >= 0) ? c + 0 : -1
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
            cbest2 = ""; for (k in ccount) if (cbest2 == "" || ccount[k] > ccount[cbest2]) cbest2 = k
            ymin = ys[1]; ymax = ys[1]; off = 0; worse = 0
            for (i = 1; i <= found; i++) {
                if (ys[i] < ymin) ymin = ys[i]
                if (ys[i] > ymax) ymax = ys[i]
                d = ys[i] - best; if (d < 0) d = -d
                if (d > tolerance) {
                    off++
                    e = (cv[i] < 0 || cbest2 == "") ? 1e9 : (ys[i] - best) - (cv[i] - cbest2); if (e < 0) e = -e
                    if (e > tolerance) worse++
                    if (off <= 15) bad[++nbad] = sprintf("moved %+.2f pt, AppKit'"'"'s view %s: %s", ys[i] - best, (cv[i] < 0 || cbest2 == "") ? "not found" : sprintf("%+.2f pt", cv[i] - cbest2), line[i])
                }
            }
            printf "marker_top_pt: usual %.2f, min %.2f, max %.2f, spread %.2f pt; %d of %d images off by more than %.2f pt\n", best, ymin, ymax, ymax - ymin, off, found, tolerance
            printf "%d of those %d images off by more than AppKit'"'"'s own view in the same image\n", worse, off
            if (against_appkit) {
                if (off > 0) printf "NOTE the absolute bound is not met: in %d images the content was more than %.2f pt from where it was drawn, a frame of another height than the window on the screen\n", off, tolerance
                if (worse > 0) { printf "FAIL the content moved by more than %.2f pt where AppKit'"'"'s own view did not, in %d images\n", tolerance, worse; failed = 1 }
            } else if (ymax - ymin > tolerance) { printf "FAIL the content moved by %.2f pt during the vertical drag, more than %.2f\n", ymax - ymin, tolerance; failed = 1 }
        }
        if (controls > 0) {
            cbest = ""; for (k in ccount) if (cbest == "" || ccount[k] > ccount[cbest]) cbest = k
            coff = 0
            for (i = 1; i <= controls; i++) { d = cs[i] - cbest; if (d < 0) d = -d; if (d > tolerance) coff++ }
            printf "info AppKit control control_top_pt: usual %.2f, min %.2f, max %.2f, spread %.2f pt; %d of %d images off by more than %.2f pt\n", cbest, cmin, cmax, cmax - cmin, coff, controls, tolerance
        }
        if (plains > 0) {
            pbest = ""; for (k in pcount) if (pbest == "" || pcount[k] > pcount[pbest]) pbest = k
            poff = 0; pmin = ps[1]; pmax = ps[1]
            for (i = 1; i <= plains; i++) { if (ps[i] < pmin) pmin = ps[i]; if (ps[i] > pmax) pmax = ps[i]; d = ps[i] - pbest; if (d < 0) d = -d; if (d > tolerance) poff++ }
            printf "info plain AppKit window, same drag: control_top_pt usual %.2f, min %.2f, max %.2f, spread %.2f pt; %d of %d images off by more than %.2f pt\n", pbest, pmin, pmax, pmax - pmin, poff, plains, tolerance
        }
        if (missing > 0) { printf "FAIL %d images had no content where it was drawn\n", missing; failed = 1 }
        for (i = 1; i <= nbad; i++) print "  " bad[i]
        if (!failed && (!against_appkit || off == 0)) print "ok   the content stayed within " tolerance " pt of where it was drawn through the vertical drag"
        else if (!failed) print "ok   through the vertical drag the content was never more than " tolerance " pt from where it was drawn, except where AppKit'"'"'s own view was off by the same amount"
        exit failed
    }
' "$log"
