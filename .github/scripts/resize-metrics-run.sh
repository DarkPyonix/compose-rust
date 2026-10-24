#!/usr/bin/env bash
# Runs the hand-check application with DXC_METRICS=1 (dirty the heap, then a scripted drag
# of 100 sizes), waits for the last phase, stops it, and summarises the frames drawn during
# the drag and the phase lines around it.
#
# With "soak" as a fourth argument it runs the long soak instead (DXC_RESIZE_SOAK, rounds of
# 100 mouse events each, 15 rounds unless DXC_RESIZE_SOAK says otherwise) and leaves only the
# log, which resize-memory-check.sh --soak reads. DXC_SKIA_CACHE_MB is passed through.
#
# Usage: resize-metrics-run.sh <application> <output directory> <label> [soak]
set -uo pipefail
app="$1"
out="$2"
label="$3"
mode="${4:-metrics}"
mkdir -p "$out"
log="$out/$label.log"

stop() {
    local pid="$1" marker="$2" tries="$3"
    for _ in $(seq "$tries"); do
        grep -q "$marker" "$log" 2>/dev/null && break
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    sleep 1
    kill "$pid" 2>/dev/null || true
    sleep 1
    kill -9 "$pid" 2>/dev/null || true
}

if [[ "$mode" == "soak" ]]; then
    rounds="${DXC_RESIZE_SOAK:-15}"
    DXC_RESIZE_SOAK="$rounds" DXC_METRICS_BREAKDOWN=1 "$app" > "$log" 2>&1 &
    # Seven seconds of rest, then each round's drag and two seconds after it, with room for
    # a slow runner; the run is stopped as soon as the soak says it is done.
    stop $! "soak done" $(( (20 + rounds * 12) * 2 ))
    exit 0
fi

if [[ "$mode" == "idle" ]]; then
    seconds="${DXC_IDLE_SOAK_SECONDS:-60}"
    DXC_SYNTH="${DXC_SYNTH:-type}" DXC_IDLE_SOAK_SECONDS="$seconds" DXC_METRICS_BREAKDOWN=1 "$app" > "$log" 2>&1 &
    # Eight seconds of input and settling, then the idle window, with room for a slow runner.
    stop $! "idle-soak done" $(( (20 + seconds) * 2 ))
    exit 0
fi

DXC_METRICS=1 DXC_METRICS_BREAKDOWN=1 DXC_REPORT_LATENCY=1 "$app" > "$log" 2>&1 &
# The scripted drag, its settled reading, then the drag made of mouse events and its own.
stop $! "metrics done" 360

summary="$out/$label.md"
{
    echo "### $label"
    echo
    echo '```'
    grep -E "metrics (phase|memory)|swapchain|isolate options|resize priority|pixel-compare|resize frames|raster bitmap" "$log" || echo "(no phase lines: see $label.log)"
    echo '```'
    echo
    echo "GPU/CPU switches: $(grep -c 'resize-mode: frames ' "$log")"
    echo
    echo '```'
    grep 'resize-mode: frames ' "$log" | head -6
    echo '...'
    grep 'resize-mode: frames ' "$log" | tail -4
    echo '```'
    echo
    echo "| frames in drag | median ms | p95 ms | max ms | frames > 16.7 ms | GCs in drag | GC pause ms in drag |"
    echo "|---|---|---|---|---|---|---|"
    awk '
        /metrics phase dirtied/ { inside = 1; next }
        /metrics phase after-100-resizes/ { inside = 0; next }
        inside && /metrics frame/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^ms=/) { split($i, kv, "="); ms[++n] = kv[2] + 0 }
                if ($i ~ /^gcs=\+/) { v = $i; sub(/^gcs=\+/, "", v); gcs += v }
                if ($i ~ /^gc_ms=\+/) { v = $i; sub(/^gc_ms=\+/, "", v); gcms += v }
            }
        }
        END {
            if (n == 0) { print "| 0 | - | - | - | - | - | - |"; exit }
            for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (ms[j] < ms[i]) { t = ms[i]; ms[i] = ms[j]; ms[j] = t }
            slow = 0; for (i = 1; i <= n; i++) if (ms[i] > 16.7) slow++
            p = int(n * 0.95); if (p < 1) p = 1
            printf "| %d | %.2f | %.2f | %.2f | %d | %d | %.2f |\n", n, ms[int((n + 1) / 2)], ms[p], ms[n], slow, gcs, gcms
        }
    ' "$log"
} > "$summary"
cat "$summary"
