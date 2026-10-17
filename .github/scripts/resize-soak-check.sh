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
# The bound both macOS jobs pass is 16 MB over 1500 resizes (15 rounds), from CI runs
# 39028836346 and 39028159924 on macOS arm64:
#
#   Kotlin/Native  rest 30 MB; settled 35 after round 1 and 37 to 38 from round 2 to 15, so
#                  +7 to +8 and flat. Of the 7: 4.7 in footprint's "app-specific tag 7" (the
#                  runtime's heap pages; 10 to 11 MB live), a drawable at the next 256
#                  pixel step 1.5 (IOSurface), malloc 1, the graphics driver 0.3.
#   GraalVM image  rest 29 MB; settled +1 to +9, a saw: the image's heap ("untagged") grows
#                  0.5 MB a round to 7 MB used of its 64 MB cap and drops at the young
#                  collection (round 13), and the graphics driver grew 128 KB every round,
#                  which was the leak the check below catches.
#
#   Skia's GPU resource cache is not part of it: the same soaks with its limit at 16 MB
#   instead of the default 256 gave the same readings to the megabyte on both paths, and
#   Skia's CPU cache stayed at 0. Metal's own allocation rose 2 MB and stayed.
#
# 16 is twice the largest plateau, which leaves room for the heap's saw and a drawable step
# at a size the runner's screen rounds differently, and memory that grew without end at the
# rate the owner's machine showed for the native image (about 2 MB per 150 resizes) would
# cross it inside the 1500.
#
# It also fails if the graphics driver's memory keeps growing from round to round, which a
# bound on the whole footprint is too coarse to see early: see accel_bound_kb.
#
# Usage: resize-soak-check.sh <log> <bound MB> [rounds]
set -euo pipefail
log="$1"
bound="$2"
rounds_wanted="${3:-15}"
# How much the graphics driver's memory (footprint's "IOAccelerator" kinds) may grow from round
# 3 to the last. It is allocated in 64 KB regions. The Kotlin/Native window's wandered by 160 KB
# over 13 rounds with no trend (CI run 39028836346); the native image's window grew by two
# regions every round, 1.9 MB over 15 rounds (run 39028159924). 512 KB is eight regions: well
# above the wander, and over the 12 rounds compared a leak of 43 KB a drag or more reaches it.
accel_bound_kb=512

[[ -f "$log" ]] || { echo "FAIL no log at $log" >&2; exit 1; }

awk -v bound="$bound" -v wanted="$rounds_wanted" -v accel_bound_kb="$accel_bound_kb" '
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
        if (category ~ /IOAccelerator/) accel[key] += dirty
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
        # What the graphics driver holds for the process must stop growing once the rounds
        # are under way. Read from round 3, when every size the drag passes has been drawn
        # at least twice, to the last round.
        first = "round=3 settled"; last = "round=" settled_rounds " settled"
        if ((first in accel) && (last in accel)) {
            grew = (accel[last] - accel[first]) * 1024
            if (grew > accel_bound_kb) {
                printf "FAIL IOAccelerator memory grew %d KB from round 3 to round %d, bound %d KB: something is allocated per drag and kept\n", grew, settled_rounds, accel_bound_kb
                failed = 1
            } else {
                printf "ok   IOAccelerator memory grew %d KB from round 3 to round %d, bound %d KB\n", grew, settled_rounds, accel_bound_kb
            }
        } else if (settled_rounds >= 3) {
            print "FAIL the settled readings carry no footprint breakdown (DXC_METRICS_BREAKDOWN=1)"
            failed = 1
        }
        if (worst >= 0 && worst <= bound) {
            printf "ok   every settled reading within %d MB of the %d MB at rest (most: %d MB, round %s)\n", bound, idle, worst, worst_round
        }
        exit failed
    }
' "$log"
