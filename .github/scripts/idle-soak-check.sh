#!/usr/bin/env bash
# Reads the log of an idle memory check (resize-metrics-run.sh with "idle",
# DXC_IDLE_SOAK_SECONDS) and fails if a window that typed into its text field and then sat
# untouched grows in physical footprint, graphics driver memory, or idle frame count.
#
# What this guards against:
#   - A coroutine timer on the scene's FlushCoroutineDispatcher (such as the 1-second dark
#     mode poll on GraalVM) that left hasImmediateTasks() true or woke the scene at 2.2 Hz
#     forever, submitting a new Metal command buffer every 450 ms while the window was idle.
#   - Memory that climbs after text input while the application sits open (up to 30 minutes
#     when DXC_IDLE_SOAK_SECONDS=1800).
#
# Usage: idle-soak-check.sh <log> [footprint bound MB] [max idle frames per 60s]
set -euo pipefail
log="$1"
bound_mb="${2:-4}"
max_frames_per_min="${3:-4}"
accel_bound_kb=256

[[ -f "$log" ]] || { echo "FAIL no log at $log" >&2; exit 1; }

awk -v bound_mb="$bound_mb" -v max_fpm="$max_frames_per_min" -v accel_bound_kb="$accel_bound_kb" '
    function field(line, key,    n, i, parts, kv) {
        n = split(line, parts, " ")
        for (i = 1; i <= n; i++) if (index(parts[i], key "=") == 1) { split(parts[i], kv, "="); return kv[2] }
        return ""
    }
    function dash(value) { if (value == "") return "-"; return value }
    function size_mb(value, unit) {
        if (unit == "B") return value / 1048576
        if (unit == "KB") return value / 1024
        if (unit == "MB") return value
        if (unit == "GB") return value * 1024
        return 0
    }
    /compose-rust: idle-soak breakdown / {
        line = $0
        sub(/.*compose-rust: idle-soak breakdown /, "", line)
        n = split(line, w, " ")
        if (n < 10) next
        key = w[1] " " w[2]
        if (w[4] !~ /^(B|KB|MB|GB)$/) next
        category = w[10]
        for (i = 11; i <= n; i++) category = category " " w[i]
        dirty = size_mb(w[3], w[4])
        if (category ~ /IOSurface/) surf[key] += dirty
        else if (category ~ /IOAccelerator|graphics|IOKit/) gfx[key] += dirty
        if (category ~ /IOAccelerator/) accel[key] += dirty
        else if (category ~ /MALLOC/) malloc_mb[key] += dirty
        else if (category ~ /untagged|VM_ALLOCATE|app-specific/) untagged[key] += dirty
        next
    }
    /compose-rust: idle-soak phase / {
        line = $0
        sub(/.*compose-rust: idle-soak phase /, "", line)
        split(line, w, " ")
        phase = w[1]
        elapsed = field(line, "elapsed_s") + 0
        fp = field(line, "footprint_mb") + 0
        frames = field(line, "frames") + 0
        if (phase == "start") {
            has_start = 1
            start_fp = fp
            start_frames = frames
        }
        if (phase == "end") {
            has_end = 1
            end_fp = fp
            end_frames = frames
            end_elapsed = elapsed
        }
        n_rows++
        row_phase[n_rows] = phase
        row_elapsed[n_rows] = elapsed
        row_frames[n_rows] = frames
        row_fp[n_rows] = fp
        row_metal[n_rows] = field(line, "metal_allocated_mb")
        row_heap[n_rows] = field(line, "heap_used_mb") "/" field(line, "heap_max_mb")
        row_skia[n_rows] = field(line, "skia_cpu_cache_mb")
        row_key[n_rows] = "elapsed_s=" elapsed " " phase
        next
    }
    END {
        if (!has_start || !has_end) {
            print "FAIL idle-soak log is missing start or end phase lines"
            exit 1
        }
        print "| phase | elapsed s | frames | idle frames | footprint MB | over start MB | Metal MB | IOSurface MB | graphics MB | malloc MB | heap/VM MB | heap MB (used/cap) | Skia CPU cache MB |"
        print "|---|---|---|---|---|---|---|---|---|---|---|---|---|"
        for (i = 1; i <= n_rows; i++) {
            k = row_key[i]
            surf_s = "-"; if (k in surf) surf_s = sprintf("%.1f", surf[k])
            gfx_s = "-"; if (k in gfx) gfx_s = sprintf("%.1f", gfx[k])
            malloc_s = "-"; if (k in malloc_mb) malloc_s = sprintf("%.1f", malloc_mb[k])
            untagged_s = "-"; if (k in untagged) untagged_s = sprintf("%.1f", untagged[k])
            printf "| %s | %d | %d | %+d | %d | %+d | %s | %s | %s | %s | %s | %s | %s |\n",
                row_phase[i], row_elapsed[i], row_frames[i], row_frames[i] - start_frames,
                row_fp[i], row_fp[i] - start_fp, dash(row_metal[i]), surf_s, gfx_s, malloc_s,
                untagged_s, dash(row_heap[i]), dash(row_skia[i])
        }
        print ""
        failed = 0
        fp_grew = end_fp - start_fp
        if (fp_grew > bound_mb) {
            printf "FAIL footprint grew %d MB during %ds of idle (%d -> %d MB, bound %d MB)\n",
                fp_grew, end_elapsed, start_fp, end_fp, bound_mb
            failed = 1
        } else {
            printf "ok   footprint changed %+d MB during %ds of idle (%d -> %d MB, bound %d MB)\n",
                fp_grew, end_elapsed, start_fp, end_fp, bound_mb
        }
        idle_frames = end_frames - start_frames
        allowed_frames = int((end_elapsed / 60.0) * max_fpm + 2)
        if (idle_frames > allowed_frames) {
            printf "FAIL %d frames were drawn during %ds of idle (%d -> %d, bound %d): the idle window is redrawing\n",
                idle_frames, end_elapsed, start_frames, end_frames, allowed_frames
            failed = 1
        } else {
            printf "ok   %d frames drawn during %ds of idle (bound %d)\n",
                idle_frames, end_elapsed, allowed_frames
        }
        first_k = "elapsed_s=0 start"
        last_k = "elapsed_s=" end_elapsed " end"
        if ((first_k in accel) && (last_k in accel)) {
            grew_kb = int((accel[last_k] - accel[first_k]) * 1024)
            if (grew_kb > accel_bound_kb) {
                printf "FAIL IOAccelerator memory grew %d KB during %ds of idle (bound %d KB)\n",
                    grew_kb, end_elapsed, accel_bound_kb
                failed = 1
            } else {
                printf "ok   IOAccelerator memory changed %+d KB during %ds of idle (bound %d KB)\n",
                    grew_kb, end_elapsed, accel_bound_kb
            }
        }
        exit failed
    }
' "$log"
