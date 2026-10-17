#!/usr/bin/env bash
# Runs an application with DXC_VRESIZE_PROBE=1, which drags its window's bottom edge and
# then its top edge with mouse events and samples where the content is on the screen while
# it does (the probe is in the fork's macOS windows: VerticalResizeProbe.kt for
# Kotlin/Native, appkit_vresize_probe.h for the native image). Waits for the probe's last
# line, stops the application and leaves the log for vertical-resize-check.sh.
#
# Usage: vertical-resize-run.sh <application> <output directory> <label>
set -uo pipefail
app="$1"
out="$2"
label="$3"
mkdir -p "$out"
log="$out/$label.log"
DXC_VRESIZE_PROBE=1 "$app" > "$log" 2>&1 &
pid=$!
for _ in $(seq 180); do
    grep -q "vresize done" "$log" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
sleep 0.5
kill "$pid" 2>/dev/null || true
sleep 1
kill -9 "$pid" 2>/dev/null || true
grep -c "vresize sample" "$log" | sed 's/^/samples: /'
grep -E "vresize (begin|done|error)" "$log" || echo "(no probe lines: see $log)"
