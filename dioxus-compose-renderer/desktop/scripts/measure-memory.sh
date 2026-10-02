#!/usr/bin/env bash
# Measures the renderer's memory against the desktop weight target: an empty window under
# 56MB of physical footprint.
#
# Runs the smoke host with its window open, samples `footprint` until the process settles,
# and prints the steady footprint, the peak, and the vmmap regions that account for it. The measured
# number is macOS physical footprint, not RSS: RSS counts clean pages mapped from the image
# and reads about twice the real occupancy.
#
# Usage: ./desktop/scripts/measure-memory.sh [label]
set -euo pipefail
source "$(dirname "$0")/env.sh"

label="${1:-unlabelled}"
samples="${DIOXUS_COMPOSE_SAMPLES:-12}"
settle_seconds="${DIOXUS_COMPOSE_SETTLE:-6}"

lib="$DIST_DIR/lib"
[[ -f "$lib/$LIBRARY_NAME.dylib" ]] || die \
    "$lib/$LIBRARY_NAME.dylib not found" \
    "fix: run $(dirname "$0")/build-native.sh first"

host="$BUILD_DIR/smoke_host"
cc -O2 -o "$host" "$NATIVE_DIR/c/smoke_host.c" -L"$lib" -ldioxus_compose_renderer -Wl,-rpath,"$lib"

# Long enough to outlast the sampling window; the script kills the host when it is done.
DIOXUS_COMPOSE_AUTOEXIT_MS=120000 "$host" >"$BUILD_DIR/measure-host.log" 2>&1 &
host_pid=$!
trap 'kill "$host_pid" 2>/dev/null || true' EXIT

# The window has to compose before the graphics surfaces exist, so let it settle first.
for _ in $(seq "$settle_seconds"); do
    kill -0 "$host_pid" 2>/dev/null || die "smoke host exited early; see $BUILD_DIR/measure-host.log"
    sleep 1
done

footprint_bytes() {
    # `footprint -p` reports the phys_footprint line; that value is the one we hold to.
    footprint -p "$1" 2>/dev/null | awk '
        /phys_footprint/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^[0-9][0-9.]*$/) {
                    unit = $(i + 1)
                    value = $i + 0
                    if (unit ~ /^K/) value *= 1024
                    else if (unit ~ /^M/) value *= 1024 * 1024
                    else if (unit ~ /^G/) value *= 1024 * 1024 * 1024
                    printf "%d\n", value
                    exit
                }
            }
        }'
}

peak=0
last=0
for _ in $(seq "$samples"); do
    kill -0 "$host_pid" 2>/dev/null || break
    value="$(footprint_bytes "$host_pid" || true)"
    if [[ -n "$value" && "$value" != "0" ]]; then
        last="$value"
        [[ "$value" -gt "$peak" ]] && peak="$value"
    fi
    sleep 1
done

as_mb() { awk -v b="$1" 'BEGIN { printf "%.1f", b / 1048576 }'; }

echo "== resident memory measurement: $label =="
echo "pid            $host_pid"
echo "footprint      $(as_mb "$last") MB"
echo "peak           $(as_mb "$peak") MB"
echo
echo "-- footprint --"
footprint -p "$host_pid" 2>/dev/null || true
echo
echo "-- vmmap summary --"
vmmap -summary "$host_pid" 2>/dev/null || true
