#!/usr/bin/env bash
# Measures how far the drawing of a Linux window lags behind its edge while the edge is dragged.
#
# Starts the application with DXC_EDGE_MARK=1, which makes the renderer paint a 12 px magenta
# bar flush with the right edge of every frame it draws. Under openbox (a reparenting manager that
# answers _NET_WM_SYNC_REQUEST) it drags the window's right edge with Alt and the right button,
# a step at a time, and after each step captures the window from the screen and reads one row
# of it up to the window's right edge. The lag is the number of pixels between the window's right edge and the right
# end of the bar: the part of the window the manager has already moved and the drawing has not
# reached. The bar is magenta because nothing else in the window is: a window whose scene leaves
# the row transparent shows black there, and a black bar in it cannot be told from the rest.
#
# The window at rest is measured first and has to read 0 px, or nothing after it means
# anything. rest.png is that capture, and worst.png is the step that lagged most.
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
for tool in openbox xdotool xwininfo import convert identify od; do
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

# The application's window. The window sets no _NET_WM_PID, so the search by process finds it
# only if that changes; until then it is the first visible named window with a size, which
# under a bare openbox is the only one there is.
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

# Captures the window's client area from the screen into the file "$5".
capture() {
    import -silent -window root -crop "${3}x${4}+${1}+${2}" +repage "$5" 2> /dev/null
}

# Reads the middle row of a capture "$1" of a window "$2" pixels wide and prints the lag in
# pixels, or "missing" when no bar is there. The row is read as raw bytes, three to a pixel,
# because the text ImageMagick prints for a pixel is laid out differently from one version to
# the next. Only the rightmost bar counts: a buffer that kept an older frame can show that
# frame's bar further left as well.
lag_in() {
    local shot="$1" w="$2"
    local span=$((w < 200 ? w : 200))
    local rows
    rows="$(identify -format '%h' "$shot" 2> /dev/null)" || { echo missing; return; }
    convert "$shot" -crop "${span}x1+$((w - span))+$((rows / 2))" +repage -depth 8 rgb:- 2> /dev/null |
        od -An -v -tu1 -w3 | awk -v bar="$bar" -v span="$span" '
        { mark[NR - 1] = ($1 > 200 && $2 < 60 && $3 > 200) ? 1 : 0 }
        END {
            if (NR != span) { print "missing"; exit }
            gap = 0
            x = span - 1
            while (x >= 0 && !mark[x]) { gap++; x-- }
            run = 0
            while (x >= 0 && mark[x]) { run++; x-- }
            if (run < bar - 1) { print "missing"; exit }
            print gap
        }'
}

shot="$out/step.bmp"
read -r x0 y0 w0 h0 < <(geometry)
echo "linux-live-resize begin window=$window x=$x0 y=$y0 width=$w0 height=$h0" | tee -a "$log"
# The window at rest, kept whatever happens next: it shows what the measurement started from.
capture "$x0" "$y0" "$w0" "$h0" "$out/rest.png"
rest="$(lag_in "$out/rest.png" "$w0")"
echo "linux-live-resize rest lag_px=$rest" | tee -a "$log"
if [[ "$rest" == "missing" ]]; then
    echo "FAIL no edge mark in the window at rest (see rest.png): DXC_EDGE_MARK is not read by this renderer, or the capture is not of its window" >&2
    exit 1
fi
if ((rest != 0)); then
    echo "FAIL the edge mark is $rest px from the right edge of a window nobody is dragging (see rest.png): the measurement is wrong, or the frame is not as wide as the window" >&2
    exit 1
fi

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
        capture "$x" "$y" "$w" "$h" "$shot"
        lag="$(lag_in "$shot" "$w")"
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
            # The first of them is kept, and counts as the worst step until a later one lags.
            ((missing == 1 && worst == 0)) && convert "$shot" "$out/worst.png"
            continue
        fi
        if ((lag > worst)); then
            worst=$lag
            convert "$shot" "$out/worst.png"
        fi
        ((lag > tolerance)) && over=$((over + 1))
    done
done
xdotool mouseup 3 keyup alt
read -r _ _ w_end _ < <(geometry)
rm -f "$shot"
echo "linux-live-resize done samples=$samples worst_lag_px=$worst over_tolerance=$over missing=$missing" | tee -a "$log"

red=0
if ((samples == 0)); then echo "FAIL no sample survived; the drag never settled long enough to capture"; red=1; fi
if ((samples > 0 && widest == narrowest)); then echo "FAIL the drag did not change the window's width; it measured nothing"; red=1; fi
if ((missing > 0)); then echo "FAIL $missing captures had no edge mark: a frame of another size or no frame was on the screen (see worst.png)"; red=1; fi
if ((over > 0)); then echo "FAIL $over of $samples steps lagged by more than $tolerance px (worst $worst px, see worst.png)"; red=1; fi
((red == 0)) && echo "ok    $samples steps, worst lag $worst px (tolerance $tolerance px), final width $w_end"
exit "$red"
