#!/usr/bin/env bash
# Measures how far the drawing of a Linux window lags behind its edge while the edge is dragged.
#
# Starts the application with DXC_EDGE_MARK=1, which makes the renderer paint a 12 px black bar
# flush with the right edge of every frame it draws. Under openbox (a reparenting manager that
# answers _NET_WM_SYNC_REQUEST) it drags the window's right edge with Alt and the right button,
# a step at a time, and after each step captures one row of the screen across the window's
# right edge. The lag is the number of pixels between the window's right edge and the right
# end of the bar: the part of the window the manager has already moved and the drawing has not
# reached. Pixels left of the window's edge that are not drawn yet count whether the server
# shows them black or in any other colour, because the bar is found by its width.
#
# Every frame on the screen during a resize has to be one drawn at the window's size at that
# moment, so the default tolerance is 0 px. A sample is kept only if the window's geometry was
# the same before and after the capture; one that raced the manager measures nothing.
#
# Needs an X display (run it under xvfb-run), openbox, xdotool, xwininfo and ImageMagick.
#
# Usage: linux-live-resize.sh <output directory> <application> [application arguments...]
# Environment: LIVE_RESIZE_TOLERANCE_PX (default 0), LIVE_RESIZE_STEPS (default 20),
#              LIVE_RESIZE_STEP_PX (default 15).
set -uo pipefail
out="$1"
shift
tolerance="${LIVE_RESIZE_TOLERANCE_PX:-0}"
steps="${LIVE_RESIZE_STEPS:-20}"
step_px="${LIVE_RESIZE_STEP_PX:-15}"
bar=12
mkdir -p "$out"
log="$out/linux-live-resize.log"
: > "$log"

[[ -n "${DISPLAY:-}" ]] || { echo "FAIL no X display; run this under xvfb-run" >&2; exit 1; }
for tool in openbox xdotool xwininfo import; do
    command -v "$tool" > /dev/null || { echo "FAIL $tool is not installed" >&2; exit 1; }
done

openbox --sm-disable > "$out/openbox.log" 2>&1 &
wm=$!
DXC_EDGE_MARK=1 "$@" > "$out/application.log" 2>&1 &
app=$!
cleanup() {
    xdotool keyup alt mouseup 3 2> /dev/null || true
    kill "$app" "$wm" 2> /dev/null || true
    sleep 1
    kill -9 "$app" "$wm" 2> /dev/null || true
}
trap cleanup EXIT

# The application's window: the first visible window with a size that the manager did not make.
window=""
for _ in $(seq 60); do
    window="$(xdotool search --onlyvisible --pid "$app" 2> /dev/null | head -1)"
    [[ -n "$window" ]] || window="$(xdotool search --onlyvisible --name '.' 2> /dev/null | while read -r id; do
        xwininfo -id "$id" 2> /dev/null | grep -q 'Width: [1-9][0-9]' && { echo "$id"; break; }
    done)"
    [[ -n "$window" ]] && break
    kill -0 "$app" 2> /dev/null || break
    sleep 0.5
done
[[ -n "$window" ]] || { echo "FAIL the application's window did not appear (see $out/application.log)" >&2; exit 1; }
sleep 2

# Prints "x y width height" of the window's client area in root coordinates.
geometry() {
    xwininfo -id "$window" | awk '
        /Absolute upper-left X/ { x = $NF }
        /Absolute upper-left Y/ { y = $NF }
        /^ *Width:/ { w = $NF }
        /^ *Height:/ { h = $NF }
        END { print x, y, w, h }'
}

# Reads one row of the screen ending at the window's right edge and prints the lag in pixels,
# or "missing" when no bar is there.
lag_at() {
    local x="$1" y="$2" w="$3" h="$4"
    local row=$((y + h / 2))
    local span=$((w < 200 ? w : 200))
    local left=$((x + w - span))
    import -silent -window root -crop "${span}x1+${left}+${row}" +repage -depth 8 txt:- 2> /dev/null | awk -v bar="$bar" -v span="$span" '
        function hex_value(h,   i, v) {
            v = 0
            for (i = 1; i <= length(h); i++) v = v * 16 + index("0123456789ABCDEF", toupper(substr(h, i, 1))) - 1
            return v
        }
        /^#/ { next }
        {
            split($1, at, ",")
            px = at[1] + 0
            # "x,y: (r,g,b)  #RRGGBB  name": the hex field is the same in every ImageMagick.
            hex = $3
            sub(/^#/, "", hex)
            r = hex_value(substr(hex, 1, 2)); g = hex_value(substr(hex, 3, 2)); b = hex_value(substr(hex, 5, 2))
            black[px] = (r < 24 && g < 24 && b < 24) ? 1 : 0
        }
        END {
            gap = 0
            x = span - 1
            while (x >= 0 && !black[x]) { gap++; x-- }
            run = 0
            while (x >= 0 && black[x]) { run++; x-- }
            if (run < bar - 1) { print "missing"; exit }
            extra = run - bar
            if (extra < 0) extra = 0
            print gap + extra
        }'
}

read -r x0 y0 w0 h0 < <(geometry)
echo "linux-live-resize begin window=$window x=$x0 y=$y0 width=$w0 height=$h0" | tee -a "$log"
rest="$(lag_at "$x0" "$y0" "$w0" "$h0")"
echo "linux-live-resize rest lag_px=$rest" | tee -a "$log"
[[ "$rest" == "missing" ]] && { echo "FAIL no edge mark at rest; is DXC_EDGE_MARK read by this renderer?" >&2; exit 1; }

# Grab in the right middle of the window, where openbox resizes the right edge.
grab_x=$((x0 + w0 * 9 / 10))
grab_y=$((y0 + h0 / 2))
xdotool mousemove "$grab_x" "$grab_y" keydown alt mousedown 3
sleep 0.3
samples=0
worst=0
over=0
missing=0
widest=0
narrowest=0
for direction in 1 -1; do
    for i in $(seq "$steps"); do
        grab_x=$((grab_x + direction * step_px))
        xdotool mousemove "$grab_x" "$grab_y"
        before="$(geometry)"
        read -r x y w h <<< "$before"
        lag="$(lag_at "$x" "$y" "$w" "$h")"
        after="$(geometry)"
        if [[ "$before" != "$after" ]]; then
            echo "linux-live-resize raced width=$w" >> "$log"
            continue
        fi
        samples=$((samples + 1))
        ((samples == 1 || w > widest)) && widest=$w
        ((samples == 1 || w < narrowest)) && narrowest=$w
        echo "linux-live-resize step direction=$direction width=$w lag_px=$lag" | tee -a "$log"
        if [[ "$lag" == "missing" ]]; then
            missing=$((missing + 1))
            continue
        fi
        ((lag > worst)) && worst=$lag
        ((lag > tolerance)) && over=$((over + 1))
    done
done
xdotool mouseup 3 keyup alt
read -r _ _ w_end _ < <(geometry)
echo "linux-live-resize done samples=$samples worst_lag_px=$worst over_tolerance=$over missing=$missing" | tee -a "$log"

red=0
if ((samples == 0)); then echo "FAIL no sample survived; the drag never settled long enough to capture"; red=1; fi
if ((samples > 0 && widest == narrowest)); then echo "FAIL the drag did not change the window's width; it measured nothing"; red=1; fi
if ((missing > 0)); then echo "FAIL $missing captures had no edge mark: a frame of another size or no frame was on the screen"; red=1; fi
if ((over > 0)); then echo "FAIL $over of $samples steps lagged by more than $tolerance px (worst $worst px)"; red=1; fi
((red == 0)) && echo "ok    $samples steps, worst lag $worst px (tolerance $tolerance px), final width $w_end"
exit "$red"
