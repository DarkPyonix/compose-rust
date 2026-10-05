#!/usr/bin/env bash
# Runs the hand-check application with DXC_METRICS=1 (dirty the heap, then a scripted drag
# of 100 sizes), waits for the last phase, stops it, and summarises the frames drawn during
# the drag and the phase lines around it.
#
# Usage: resize-metrics-run.sh <application> <output directory> <label>
set -uo pipefail
app="$1"
out="$2"
label="$3"
mkdir -p "$out"
log="$out/$label.log"
DXC_METRICS=1 DXC_REPORT_LATENCY=1 "$app" > "$log" 2>&1 &
pid=$!
for _ in $(seq 240); do
    grep -q "metrics phase after-100-resizes" "$log" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
sleep 2
kill "$pid" 2>/dev/null || true
sleep 1
kill -9 "$pid" 2>/dev/null || true

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
