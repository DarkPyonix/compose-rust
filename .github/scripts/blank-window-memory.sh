#!/usr/bin/env bash
# Usage: blank-window-memory.sh <application> <output directory> [launches]
#        blank-window-memory.sh --read <smaps_rollup> <status>
#
# Measures what an idle application holds in memory on Linux, for the memory report. Starts
# the application <launches> times (3 by default), one after another. For each start it
# reads the process's memory 10 seconds after the start and again 60 seconds after it, then
# closes the application by the process id it started, never by name.
#
# The number is PSS, proportional set size, from /proc/<pid>/smaps_rollup: each page the
# process has in RAM, divided by how many processes share it. A shared library's pages count
# for a share, not in full, so PSS is the nearest Linux has to "what this process costs".
# RSS, from /proc/<pid>/status, is written beside it for reference: it counts every shared
# page in full and reads high.
#
# Writes into <output directory>:
#   samples.json            every reading, for blank-window-memory-summary.py
#   launch-<n>.log          what the application printed on that start
#   smaps-launch-1.txt      the largest mappings of the first start at its last reading
#
# Exits 1 when the application is not running at its first reading (it did not start, or
# died within 10 seconds), or when no reading could be made at all. A reading that is high
# fails nothing: this measures, it does not judge.
#
# --read prints the fields one reading takes from the two files, as `name=<kB>` lines, so
# the parsing can be tested without starting anything.
#
# BLANK_MEMORY_FIRST_S and BLANK_MEMORY_SECOND_S move the two readings (10 and 60 by
# default); the test sets them short.
set -euo pipefail

# The fields of smaps_rollup this reads, and the name each is written under. Every value
# there is in kB.
read_fields() {
    local smaps="$1" status="$2"
    awk '
        $1 == "Pss:" { print "pss_kb=" $2 }
        $1 == "Pss_Anon:" { print "pss_anon_kb=" $2 }
        $1 == "Pss_File:" { print "pss_file_kb=" $2 }
        $1 == "Pss_Shmem:" { print "pss_shmem_kb=" $2 }
        $1 == "Private_Dirty:" { print "private_dirty_kb=" $2 }
    ' "$smaps"
    awk '$1 == "VmRSS:" { print "rss_kb=" $2 }' "$status"
}

if [[ "${1:-}" == "--read" ]]; then
    [[ $# -eq 3 ]] || { echo "usage: $0 --read <smaps_rollup> <status>" >&2; exit 2; }
    read_fields "$2" "$3"
    exit 0
fi

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "usage: $0 <application> <output directory> [launches]" >&2
    exit 2
fi

app="$1"
out="$2"
launches="${3:-3}"
first_s="${BLANK_MEMORY_FIRST_S:-10}"
second_s="${BLANK_MEMORY_SECOND_S:-60}"
[[ -x "$app" ]] || { echo "error: $app is not an executable" >&2; exit 2; }
[[ "$launches" =~ ^[1-9][0-9]*$ ]] || { echo "error: launches must be a positive number, not '$launches'" >&2; exit 2; }
(( first_s < second_s )) || { echo "error: the first reading ($first_s s) has to come before the second ($second_s s)" >&2; exit 2; }
mkdir -p "$out"

# The one process this started, so a failure part way still closes it.
running=""
close_app() {
    local pid="$1"
    kill -TERM "$pid" 2>/dev/null || return 0
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}
trap '[[ -z "$running" ]] || close_app "$running"' EXIT

# Whether the process is running: there, and not a zombie waiting to be reaped, whose
# smaps_rollup is empty.
alive() {
    local pid="$1" state
    kill -0 "$pid" 2>/dev/null || return 1
    # The state follows the name in parentheses, which may itself hold spaces.
    state="$(sed 's/.*) //' "/proc/$pid/stat" 2>/dev/null | cut -d' ' -f1 || true)"
    [[ -n "$state" && "$state" != Z && "$state" != X ]]
}

# The window's size as the X server has it, when there is a display and xwininfo to ask.
window_size() {
    [[ -n "${DISPLAY:-}" ]] && command -v xwininfo >/dev/null || { echo "unknown"; return; }
    xwininfo -root -tree 2>/dev/null |
        awk '/"compose-rust blank window"/ { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+x[0-9]+\+/) { split($i, s, "+"); print s[1]; exit } }' |
        grep . || echo "unknown"
}

samples=()
window="unknown"
not_started=0

for launch in $(seq 1 "$launches"); do
    log="$out/launch-$launch.log"
    "$app" >"$log" 2>&1 &
    pid=$!
    running="$pid"
    started="$(date +%s)"
    echo "launch $launch: pid $pid"
    for at in "$first_s" "$second_s"; do
        now="$(date +%s)"
        wait_s=$(( started + at - now ))
        (( wait_s <= 0 )) || sleep "$wait_s"
        if ! alive "$pid" || [[ ! -r "/proc/$pid/smaps_rollup" ]]; then
            if [[ "$at" == "$first_s" ]]; then
                echo "::error title=The application did not start::launch $launch (pid $pid) was not running $at s after it started. What it printed:"
                not_started=$((not_started + 1))
            else
                echo "::warning title=No reading::launch $launch (pid $pid) was not running $at s after it started. What it printed:"
            fi
            sed 's/^/  /' "$log" || true
            break
        fi
        fields="$(read_fields "/proc/$pid/smaps_rollup" "/proc/$pid/status" 2>/dev/null || true)"
        if ! grep -q '^pss_kb=' <<<"$fields"; then
            echo "::warning title=No reading::launch $launch (pid $pid) at $at s: smaps_rollup had no Pss line"
            continue
        fi
        json="{\"launch\": $launch, \"at_s\": $at, \"pid\": $pid"
        while IFS='=' read -r name value; do
            [[ -n "$name" ]] && json="$json, \"$name\": $value"
        done <<<"$fields"
        samples+=("$json}")
        echo "launch $launch at $at s: $(tr '\n' ' ' <<<"$fields")"
        if [[ "$launch" == 1 && "$at" == "$second_s" ]]; then
            window="$(window_size)"
            # The mappings that hold the most, for a first look at where it goes.
            awk '
                /^[0-9a-f]+-[0-9a-f]+ / { name = ($6 == "" ? "[anonymous]" : $6) }
                $1 == "Pss:" { pss[name] += $2 }
                END { for (n in pss) printf "%8d kB  %s\n", pss[n], n }
            ' "/proc/$pid/smaps" 2>/dev/null | sort -rn | head -25 >"$out/smaps-launch-1.txt" || true
        fi
    done
    close_app "$pid"
    running=""
done

{
    echo "{"
    echo "  \"os\": \"linux\","
    echo "  \"application\": \"$(basename "$app")\","
    echo "  \"window\": \"$window\","
    echo "  \"launches\": $launches,"
    echo "  \"readings_s\": [$first_s, $second_s],"
    echo "  \"metric\": \"pss_kb\","
    echo "  \"method\": \"PSS from /proc/<pid>/smaps_rollup\","
    echo "  \"columns\": [[\"pss_kb\", \"PSS\"], [\"rss_kb\", \"RSS (reference)\"], [\"pss_anon_kb\", \"PSS anonymous\"], [\"pss_file_kb\", \"PSS file\"], [\"private_dirty_kb\", \"Private dirty\"]],"
    echo "  \"samples\": ["
    for i in "${!samples[@]}"; do
        if (( i + 1 < ${#samples[@]} )); then echo "    ${samples[$i]},"; else echo "    ${samples[$i]}"; fi
    done
    echo "  ]"
    echo "}"
} >"$out/samples.json"

echo "wrote $out/samples.json (${#samples[@]} readings)"
if (( not_started > 0 )); then
    echo "error: the application did not start on $not_started of $launches launches" >&2
    exit 1
fi
if (( ${#samples[@]} == 0 )); then
    echo "error: no reading could be made" >&2
    exit 1
fi
