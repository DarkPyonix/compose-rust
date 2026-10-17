#!/usr/bin/env bash
# Reads the log of a resize soak (resize-metrics-run.sh with "soak", DXC_RESIZE_SOAK) and
# fails if memory two seconds after a round of resizing is not back within a bound of what
# the window held at rest.
#
# A soak is rounds of one drag each: 100 mouse events on the right edge, 450 points out and
# back, which AppKit runs its own live resize for. The window prints, per round, a reading
# with the button still held ("held") and one two seconds after it is let go ("settled"),
# after five seconds of rest two "idle" readings, and after the last round one more once
# the collector has run ("collected"). With DXC_METRICS_BREAKDOWN=1 each settled reading
# carries the system's own account of the footprint by kind (`footprint`), from which the
# table takes what IOSurfaces and the graphics driver hold.
#
# The GraalVM image prints its heap on lines of its own ("soak heap"), because the window is
# C and cannot see it; the heap shown for a reading is the last such line before it.
#
# Prints a Markdown table of every reading, then one line per check.
#
# Usage: resize-soak-check.sh <log> <bound MB> [rounds]
set -euo pipefail
log="$1"
bound="$2"
rounds_wanted="${3:-15}"

[[ -f "$log" ]] || { echo "FAIL no log at $log" >&2; exit 1; }

awk -v bound="$bound" -v wanted="$rounds_wanted" '
    function field(line, key,    n, i, parts, kv) {
        n = split(line, parts, " ")
        for (i = 1; i <= n; i++) if (index(parts[i], key "=") == 1) { split(parts[i], kv, "="); return kv[2] }
        return ""
    }
    function dash(value) { if (value == "") return "-"; return value }
    # A size as footprint prints it ("12 MB", "640 KB", "0 B"), in MB.
    function size_mb(value, unit) {
        if (unit == "B") return value / 1048576
        if (unit == "KB") return value / 1024
        if (unit == "MB") return value
        if (unit == "GB") return value * 1024
        return 0
    }
    /compose-rust: soak heap / {
        heap = field($0, "used_mb") "/" field($0, "max_mb")
        skia_cpu = field($0, "skia_cpu_cache_mb")
        skia_limit = field($0, "skia_cache_limit_mb")
        next
    }
    /compose-rust: soak breakdown / {
        # "... breakdown round=R phase <dirty> <unit> <clean> <unit> <reclaimable> <unit> <regions> <category...>"
        line = $0
        sub(/.*compose-rust: soak breakdown /, "", line)
        n = split(line, w, " ")
        if (n < 10) next
        key = w[1] " " w[2]
        if (w[4] !~ /^(B|KB|MB|GB)$/) next
        category = w[10]
        for (i = 11; i <= n; i++) category = category " " w[i]
        dirty = size_mb(w[3], w[4])
        if (category ~ /IOSurface/) surf[key] += dirty
        else if (category ~ /IOAccelerator|graphics|IOKit/) gfx[key] += dirty
        else if (category ~ /MALLOC/) malloc_mb[key] += dirty
        else if (category ~ /untagged|VM_ALLOCATE/) untagged[key] += dirty
        next
    }
    /compose-rust: soak phase / {
        line = $0
        sub(/.*compose-rust: soak phase /, "", line)
        split(line, w, " ")
        phase = w[1]
        round = field(line, "round")
        fp = field(line, "footprint_mb") + 0
        if (phase == "idle") { idle = fp; idle_count++ }
        n_rows++
        row_phase[n_rows] = phase
        row_round[n_rows] = round
        row_resizes[n_rows] = field(line, "resizes")
        row_fp[n_rows] = fp
        row_metal[n_rows] = field(line, "metal_allocated_mb")
        live = field(line, "heap_live_mb")
        row_heap[n_rows] = heap
        if (live != "") row_heap[n_rows] = live "/" field(line, "heap_target_mb")
        cpu = field(line, "skia_cpu_cache_mb")
        row_skia[n_rows] = skia_cpu
        if (cpu != "") row_skia[n_rows] = cpu
        lim = field(line, "skia_cache_limit_mb")
        row_limit[n_rows] = skia_limit
        if (lim != "") row_limit[n_rows] = lim
        row_key[n_rows] = "round=" round " " phase
        row_widened[n_rows] = field(line, "widened_by")
        if (phase == "settled") settled_rounds++
        if (phase == "held" && field(line, "widened_by") + 0 <= 0) not_widened++
        next
    }
    END {
        if (n_rows == 0) { print "FAIL no soak lines in the log: the soak did not run"; exit 1 }
        print "| phase | round | resizes | footprint MB | over idle MB | Metal MB | IOSurface MB | graphics MB | malloc MB | untagged VM MB | heap MB (used/cap) | Skia CPU cache MB | Skia GPU cache limit MB |"
        print "|---|---|---|---|---|---|---|---|---|---|---|---|---|"
        for (i = 1; i <= n_rows; i++) {
            k = row_key[i]
            surf_s = "-"; if (k in surf) surf_s = sprintf("%.1f", surf[k])
            gfx_s = "-"; if (k in gfx) gfx_s = sprintf("%.1f", gfx[k])
            malloc_s = "-"; if (k in malloc_mb) malloc_s = sprintf("%.1f", malloc_mb[k])
            untagged_s = "-"; if (k in untagged) untagged_s = sprintf("%.1f", untagged[k])
            printf "| %s | %s | %s | %d | %+d | %s | %s | %s | %s | %s | %s | %s | %s |\n", row_phase[i], row_round[i],
                row_resizes[i], row_fp[i], row_fp[i] - idle, dash(row_metal[i]), surf_s, gfx_s, malloc_s,
                untagged_s, dash(row_heap[i]), dash(row_skia[i]), dash(row_limit[i])
        }
        print ""
        failed = 0
        if (idle_count < 2) { print "FAIL the soak printed no rest reading to measure against"; failed = 1 }
        if (settled_rounds < wanted) {
            printf "FAIL %d of %d rounds ran\n", settled_rounds, wanted; failed = 1
        } else {
            printf "ok   %d rounds of 100 resizes ran (%d resizes)\n", settled_rounds, settled_rounds * 100
        }
        if (not_widened > 0) { printf "FAIL %d rounds did not widen the window: the drag measured nothing\n", not_widened; failed = 1 }
        worst = -1
        for (i = 1; i <= n_rows; i++) {
            if (row_phase[i] != "settled") continue
            over = row_fp[i] - idle
            if (over > worst) { worst = over; worst_round = row_round[i] }
            if (over > bound) {
                printf "FAIL round %s settled at %d MB, %d MB over the %d MB at rest, bound %d MB\n", row_round[i], row_fp[i], over, idle, bound
                failed = 1
            }
        }
        if (worst >= 0 && worst <= bound) {
            printf "ok   every settled reading within %d MB of the %d MB at rest (most: %d MB, round %s)\n", bound, idle, worst, worst_round
        }
        exit failed
    }
' "$log"
