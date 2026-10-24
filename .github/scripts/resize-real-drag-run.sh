#!/usr/bin/env bash
# A real modal drag of the window's right edge, driven from another process: resize-probe
# starts first (so the desktop it compares against has no window on it), the application
# starts with DXC_REAL_DRAG=external and only puts its window in place, and the probe presses
# the edge, waits for the window's sizing loop, drags it out and back with absolute mouse input
# at the given rate, and records every frame the compositor shows meanwhile. The application
# records its side with DXC_RESIZE_TRACE=1.
#
# Usage: resize-real-drag-run.sh <application> <probe> <output directory> <label> <interval ms> <step px>
set -uo pipefail
app="$1"
probe="$2"
out="$3"
label="$4"
interval="$5"
step="$6"
mkdir -p "$out"
log="$out/$label.log"
probe_log="$out/$label-probe.log"
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

"$probe" --turn-on-full-window-drag --interval-ms "$interval" --step-px "$step" --out "$(native "$probe_log")" &
probe_pid=$!
for _ in $(seq 60); do
    grep -q "waiting for a" "$probe_log" 2>/dev/null && break
    kill -0 "$probe_pid" 2>/dev/null || break
    sleep 0.5
done
DXC_METRICS=1 DXC_REAL_DRAG=external DXC_RESIZE_TRACE=1 "$app" > "$log" 2>&1 &
app_pid=$!
wait "$probe_pid"
probe_status=$?
sleep 2
kill "$app_pid" 2>/dev/null || true
sleep 1
kill -9 "$app_pid" 2>/dev/null || true
echo "$label: probe exit $probe_status"
grep -E "resize-probe: (duplicating|window|client|move-size|summary|verdict)" "$probe_log" || tail -20 "$probe_log"
grep -E "real drag (ready|done)|resize-trace (start|dump)" "$log" || echo "$label: the application printed no real drag lines"
exit 0
