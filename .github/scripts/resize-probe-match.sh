#!/usr/bin/env bash
# Lines up what resize-probe saw on screen with what the application did, by QPC: for every
# frame the compositor showed during the drag, the client width the probe measured on it
# against the last WM_SIZE width and the last CPU frame copied in (BLIT) before that frame
# was presented. Both logs use the same clock (QPC); the application's side is its
# DXC_RESIZE_TRACE=1 dump.
#
# Each frame is counted by how many sizes behind the last one it shows; a frame at no recent
# size is reported, with the first few. Nothing is judged here: what a lag means depends on
# the path (the GPU path has no BLIT events to match).
#
# Usage: resize-probe-match.sh <application log> <probe log>
set -uo pipefail
app="$1"
probe="$2"
offset="$(sed -n 's/.*edge_offset=\(-\{0,1\}[0-9]*\).*/\1/p' "$probe" | head -1)"
[ -n "$offset" ] || { echo "match: no edge_offset in $probe"; exit 0; }
{
    grep -hE "resize-trace qpc=[0-9]+ kind=(WM_SIZE|BLIT) " "$app" |
        sed -E 's/.*qpc=([0-9]+) kind=([A-Z_]+) w=([0-9-]+).*/A \1 \2 \3/'
    grep -h "resize-probe: frame n=" "$probe" | grep " in_drag=1 " |
        sed -E 's/.*frame n=([0-9]+) qpc_present=([0-9]+) .* width=(-?[0-9]+) .*/P \2 \1 \3/'
} | sort -k2,2n | awk -v offset="$offset" '
    # How many WM_SIZE steps behind the screen is: 0 when the frame shows the last size the
    # window was given before it was presented, 1 when the one before, and so on; "none"
    # when the width is not among the last 64 sizes at all.
    function lag(list, count, width,    k) {
        for (k = count; k > 0 && k > count - 64; k--) if (list[k] == width) return count - k
        return "none"
    }
    $1 == "A" && $3 == "WM_SIZE" { sizes[++size_count] = $4; next }
    $1 == "A" && $3 == "BLIT" { blits[++blit_count] = $4; next }
    $1 == "P" {
        if ($4 < 0) next
        seen = $4 - offset
        frames++
        behind = lag(sizes, size_count, seen)
        size_lag[behind]++
        if (blit_count > 0) blit_lag[lag(blits, blit_count, seen)]++
        if (behind == "none" && shown < 8) {
            printf "match: frame n=%s qpc_present=%s seen %d, last WM_SIZE %s, last BLIT %s\n", $3, $2, seen,
                sizes[size_count], (blit_count ? blits[blit_count] : "none")
            shown++
        }
    }
    END {
        line = ""
        for (k = 0; k < 64; k++) if (k in size_lag) line = line sprintf(" %d:%d", k, size_lag[k])
        if ("none" in size_lag) line = line sprintf(" none:%d", size_lag["none"])
        printf "match: %d frames in the drag; WM_SIZE steps behind (steps:frames)%s\n", frames, line
        line = ""
        for (k = 0; k < 64; k++) if (k in blit_lag) line = line sprintf(" %d:%d", k, blit_lag[k])
        if ("none" in blit_lag) line = line sprintf(" none:%d", blit_lag["none"])
        printf "match: CPU frames (BLIT) behind (steps:frames)%s\n", (blit_count ? line : " none, no CPU frames")
    }'
