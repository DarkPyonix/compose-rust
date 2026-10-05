#!/usr/bin/env bash
# Runs the GraalVM renderer's smoke host once per heap setting with DXC_METRICS=1, which
# dirties the heap and then takes the window through 100 scripted sizes, and summarises the
# frames drawn during that drag and the collections that ran among them.
#
# Usage: resize-gc-run.sh <output directory>
set -uo pipefail
out="$1"
mkdir -p "$out"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lib="$repo_root/renderer/build/native-image/dist/lib"
host="$out/smoke_host"
cc -O2 -o "$host" "$repo_root/renderer/desktop/c/smoke_host.c" -L"$lib" -lcompose_rust_renderer -Wl,-rpath,"$lib"

declare -a names=(default large-heap tiny-heap)
declare -a options=(
    "-XX:+PrintGC -XX:+VerboseGC"
    "-XX:+PrintGC -XX:+VerboseGC -Xms1g -Xmn512m"
    "-XX:+PrintGC -XX:+VerboseGC -Xmx96m -Xmn8m"
)

summary="$out/summary.md"
{
    echo "| heap setting | frames in drag | median ms | p95 ms | max ms | frames > 16.7 ms | GCs in drag (MXBean) | GC ms in drag | GC log lines in drag | heap after (used/committed MB) |"
    echo "|---|---|---|---|---|---|---|---|---|---|"
} > "$summary"

for index in "${!names[@]}"; do
    name="${names[$index]}"
    log="$out/$name.log"
    echo "== $name: ${options[$index]}"
    DXC_METRICS=1 DXC_SVM_OPTIONS="${options[$index]}" COMPOSE_RUST_AUTOEXIT_MS=25000 \
        "$host" > "$log" 2>&1
    echo "exit $?" >> "$log"
    # The drag is the lines between the "dirtied" phase and the "after-100-resizes" phase.
    awk -v name="$name" '
        /metrics phase dirtied/ { inside = 1; next }
        /metrics phase after-100-resizes/ {
            inside = 0
            for (i = 1; i <= NF; i++) if ($i ~ /^heap_(used|committed)_mb=/) { split($i, kv, "="); after = after kv[2] "/" }
            next
        }
        inside && /metrics frame/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^ms=/) { split($i, kv, "="); ms[++n] = kv[2] + 0 }
                if ($i ~ /^gcs=\+/) { sub(/^gcs=\+/, "", $i); gcs += $i }
                if ($i ~ /^gc_ms=\+/) { sub(/^gc_ms=\+/, "", $i); gcms += $i }
            }
        }
        inside && /GC|Collection/ && !/metrics/ { gclines++ }
        END {
            if (n == 0) { printf "| %s | 0 | - | - | - | - | - | - | %d | %s |\n", name, gclines, after; exit }
            for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (ms[j] < ms[i]) { t = ms[i]; ms[i] = ms[j]; ms[j] = t }
            slow = 0; for (i = 1; i <= n; i++) if (ms[i] > 16.7) slow++
            p95 = ms[int(n * 0.95) > 0 ? int(n * 0.95) : 1]
            printf "| %s | %d | %.2f | %.2f | %.2f | %d | %d | %d | %d | %s |\n", name, n, ms[int((n + 1) / 2)], p95, ms[n], slow, gcs, gcms, gclines, after
        }
    ' "$log" >> "$summary"
done
cat "$summary"
