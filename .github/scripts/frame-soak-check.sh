#!/usr/bin/env bash
# Reads the log of a synthetic-input frame soak (frame-soak-run.sh) and fails if
# physical footprint at the end of the soak is not strictly under max_ratio times
# the start footprint, or if fewer than min_frames frames were drawn.
#
# Usage: frame-soak-check.sh <log> [max ratio] [min frames]
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
    echo "usage: $0 <log> [max ratio] [min frames]" >&2
    exit 2
fi

log="$1"
max_ratio="${2:-2.0}"
min_frames="${3:-2000}"

[[ -f "$log" ]] || { echo "FAIL no log at $log" >&2; exit 1; }

awk -v max_ratio="$max_ratio" -v min_frames="$min_frames" '
    function field(line, key,    n, i, parts, kv) {
        n = split(line, parts, " ")
        for (i = 1; i <= n; i++) {
            if (index(parts[i], key "=") == 1) {
                split(parts[i], kv, "=")
                return kv[2]
            }
        }
        return ""
    }
    /compose-rust: frame-soak phase / {
        line = $0
        sub(/.*compose-rust: frame-soak phase /, "", line)
        split(line, w, " ")
        phase = w[1]
        frames = field(line, "frames") + 0
        kb = field(line, "footprint_kb") + 0
        metric = field(line, "metric")
        if (metric == "") metric = "unknown"
        if (phase == "start") {
            has_start = 1
            start_frames = frames
            start_kb = kb
            start_metric = metric
        }
        if (phase == "end") {
            has_end = 1
            end_frames = frames
            end_kb = kb
            end_metric = metric
        }
        n_rows++
        row_phase[n_rows] = phase
        row_frames[n_rows] = frames
        row_kb[n_rows] = kb
        row_metric[n_rows] = metric
        next
    }
    END {
        if (!has_start || !has_end) {
            print "FAIL frame-soak log is missing start or end phase lines"
            exit 1
        }
        print "| phase | frames | footprint MB | footprint KB | metric |"
        print "|---|---|---|---|---|"
        for (i = 1; i <= n_rows; i++) {
            printf "| %s | %d | %.2f | %d | %s |\n",
                row_phase[i], row_frames[i], row_kb[i] / 1024.0, row_kb[i], row_metric[i]
        }
        print ""
        failed = 0
        if (start_kb <= 0 || end_kb <= 0) {
            printf "FAIL invalid footprint reading (start=%d KB, end=%d KB)\n", start_kb, end_kb
            exit 1
        }
        if (end_frames < min_frames) {
            printf "FAIL only %d frames drawn (minimum required: %d)\n", end_frames, min_frames
            failed = 1
        }
        ratio = (end_kb + 0.0) / (start_kb + 0.0)
        limit_kb = start_kb * (max_ratio + 0.0)
        if ((end_kb + 0.0) >= limit_kb) {
            printf "FAIL end footprint %.2f MB (%d KB) is %.2fx start %.2f MB (%d KB), must be under %.2fx after %d frames\n",
                end_kb / 1024.0, end_kb, ratio, start_kb / 1024.0, start_kb, max_ratio + 0.0, end_frames
            failed = 1
        } else if (failed == 0) {
            printf "ok   end footprint %.2f MB (%d KB) is %.2fx start %.2f MB (%d KB), under %.2fx after %d frames (%s)\n",
                end_kb / 1024.0, end_kb, ratio, start_kb / 1024.0, start_kb, max_ratio + 0.0, end_frames, end_metric
        }
        exit failed
    }
' "$log"
