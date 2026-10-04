#!/usr/bin/env bash
# Usage: scripts/parity/linux.sh <label> <output directory> -- <command ...>
#
# The parity checklist for one desktop window path on Linux. Run it once for the GraalVM path
# and once for the Kotlin/Native path, with the same application, and the two tables have the
# same rows: what is measured and what is checked does not depend on which path drew the window.
# Needs a display (run under `xvfb-run -a`) and xdotool, xprop and xclip.
#
#   <label>             graalvm or native; only names the output files and the table column
#   <output directory>  where <label>.err, <label>.out and <label>.tsv are written
#   <command ...>       the application, which opens the renderer's window
#
# The application is asked, through environment variables the window reads, to type five
# letters into its first text field, to take itself through sixty window sizes the way a drag
# does, and to close itself (DXC_SYNTH). While it waits, this script presses the editing
# shortcuts through the display server and reads the clipboard through it, which is how a
# person would meet either path. Nothing here knows which path it is looking at.
#
# Rows, in this order:
#   input_latency_avg_ms   a typed letter handed to the scene, to the frame that shows it
#   frame_resize_avg_ms    what a frame cost while the window was being resized
#   frame_resize_worst_ms  the slowest of those
#   frame_idle_avg_ms      what a frame cost otherwise
#   shortcut_ctrl_<k>      key and modifiers the scene was handed for ctrl+a, c, v, x, z
#   clipboard              ctrl+a then ctrl+c in the field, read back from the display server
#   system_theme           what the window thinks the desktop's theme is
#   min_size               the minimum size the window told the window manager
#   icon                   whether the window set _NET_WM_ICON
#
# A row that fails is printed as FAIL and the script still exits 0, so the table is complete.
# PARITY_STRICT=1 makes any FAIL the exit status.
set -uo pipefail

if [[ $# -lt 4 || "$3" != "--" ]]; then
    echo "usage: $0 <label> <output directory> -- <command ...>" >&2
    exit 2
fi
label="$1"
out="$2"
shift 3
mkdir -p "$out"
out="$(cd "$out" && pwd)"
err="$out/$label.err"
log="$out/$label.out"
table="$out/$label.tsv"
: > "$table"

[[ -n "${DISPLAY:-}" ]] || { echo "fail  DISPLAY is not set; run this under xvfb-run -a" >&2; exit 1; }
for tool in xdotool xprop xclip; do
    command -v "$tool" >/dev/null || { echo "fail  $tool is not on PATH" >&2; exit 1; }
done

row() { printf '%s\t%s\n' "$1" "$2" >> "$table"; }

# Letters to type, and when to do what, in seconds from the window opening.
export DXC_SYNTH="type,resize,exit"
export DXC_SYNTH_RESIZE_AT="${DXC_SYNTH_RESIZE_AT:-16}"
export DXC_SYNTH_EXIT_AT="${DXC_SYNTH_EXIT_AT:-20}"
export DXC_REPORT_LATENCY=1
export DXC_REPORT_INPUT=1
export GTK_THEME="${GTK_THEME:-Adwaita:dark}"

"$@" >"$log" 2>"$err" &
app=$!
trap 'kill "$app" 2>/dev/null || true' EXIT

window=""
for _ in $(seq 1 120); do
    window="$(xdotool search --onlyvisible --name 'compose-rust' 2>/dev/null | head -1 || true)"
    [[ -n "$window" ]] && break
    kill -0 "$app" 2>/dev/null || break
    sleep 0.5
done
if [[ -z "$window" ]]; then
    row window "FAIL no window"
    echo "the application did not open a window; its error output:" >&2
    tail -n 40 "$err" >&2
else
    # Window manager hints, read from the server. Same probe whichever path made the window.
    min_hint="$(xprop -id "$window" WM_NORMAL_HINTS 2>/dev/null | grep -i 'minimum size' | sed 's/^[^:]*: *//' || true)"
    row min_size "${min_hint:-none}"
    if xprop -id "$window" _NET_WM_ICON 2>/dev/null | grep -q 'CARDINAL'; then
        row icon present
    else
        row icon absent
    fi

    # Wait for the five letters, then press the shortcuts. The window holds the clipboard
    # text until something else takes it.
    for _ in $(seq 1 120); do
        [[ "$(grep -c 'synthetic 7 sent' "$err" 2>/dev/null || true)" -ge 5 ]] && break
        kill -0 "$app" 2>/dev/null || break
        sleep 0.25
    done
    sleep 1
    xdotool windowfocus "$window" 2>/dev/null || true
    for key in a c v x z; do
        xdotool key --delay 150 "ctrl+$key"
        sleep 0.4
    done
    clip="$(xclip -selection clipboard -o 2>/dev/null || true)"
    if [[ "$clip" == "abcde" ]]; then
        row clipboard ok
    else
        row clipboard "FAIL ctrl+a ctrl+c left '${clip}' on the clipboard"
    fi
fi

wait "$app" 2>/dev/null
trap - EXIT

# The key numbers are the shared board's: a 0, s 1, d 2, x 7, c 8, v 9, z 6, and control is
# the bit 1 << 18 of the modifier word. What the window handed the scene is read from its log.
declare -A want=([a]=0 [c]=8 [v]=9 [x]=7 [z]=6)
for key in a c v x z; do
    if grep -qE "kind=5,.*modifiers=262144, keyCode=${want[$key]}," "$err"; then
        row "shortcut_ctrl_$key" ok
    else
        got="$(grep -E 'kind=5,' "$err" | grep 'modifiers=262144' | sed -E 's/.*keyCode=(-?[0-9]+).*/\1/' | tr '\n' ' ')"
        row "shortcut_ctrl_$key" "FAIL expected key ${want[$key]}, ctrl keys heard: ${got:-none}"
    fi
done

# The numbers the window printed at the end of the run.
value() { sed -nE "s/.*parity $1 ([^ ]+).*/\1/p" "$err" | tail -1; }
field() { sed -nE "s/.*parity $1 [^ ]+ .*$2=([^ ]+).*/\1/p" "$err" | tail -1; }
latency="$(value input_latency_avg_ms)"
resize="$(value frame_resize_avg_ms)"
idle="$(value frame_idle_avg_ms)"
row input_latency_avg_ms "${latency:-FAIL not reported}"
row frame_resize_avg_ms "${resize:-FAIL not reported}"
row frame_resize_worst_ms "$(field frame_resize_avg_ms worst_ms)"
row frame_idle_avg_ms "${idle:-FAIL not reported}"
row system_theme "$(sed -nE 's/.*parity system_theme ([a-z]+).*/\1/p' "$err" | tail -1)"

echo "== parity: $label"
column -t -s $'\t' "$table" 2>/dev/null || cat "$table"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        echo "### Parity: $label"
        echo
        echo "| check | $label |"
        echo "|---|---|"
        while IFS=$'\t' read -r name result; do echo "| $name | $result |"; done < "$table"
        echo
    } >> "$GITHUB_STEP_SUMMARY"
fi
if grep -q $'\tFAIL' "$table"; then
    echo "::warning::parity $label has failing rows"
    [[ "${PARITY_STRICT:-}" == "1" ]] && exit 1
fi
exit 0
