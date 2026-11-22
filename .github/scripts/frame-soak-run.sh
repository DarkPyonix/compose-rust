#!/usr/bin/env bash
# Runs an application with --frame-soak=<frames>, samples its physical footprint at the
# start (post-warmup) and end phases across macOS, Linux and Windows, and writes phase
# lines that frame-soak-check.sh reads.
#
# Usage: frame-soak-run.sh <application> <output directory> <label> [frames]
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
    echo "usage: $0 <application> <output directory> <label> [frames]" >&2
    exit 2
fi

app="$1"
out="$2"
label="$3"
frames="${4:-${DXC_FRAME_SOAK_FRAMES:-2500}}"
timeout_s="${DXC_FRAME_SOAK_TIMEOUT:-300}"

mkdir -p "$out"
log="$out/$label.log"
: > "$log"

sample_memory() {
    local os_pid="$1"
    local os_name kb metric
    os_name="$(uname -s)"
    kb=""
    metric="unknown"
    case "$os_name" in
        Darwin)
            if command -v footprint >/dev/null 2>&1; then
                kb="$(footprint "$os_pid" 2>/dev/null | awk '
                    /Footprint:/ {
                        for (i = 1; i <= NF; i++) {
                            if ($i == "Footprint:") {
                                val = $(i + 1) + 0
                                unit = $(i + 2)
                                if (unit == "B") printf "%d", (val + 1023) / 1024
                                else if (unit == "KB") printf "%d", val
                                else if (unit == "MB") printf "%d", val * 1024
                                else if (unit == "GB") printf "%d", val * 1048576
                                exit
                            }
                        }
                    }
                ')"
                if [[ -n "$kb" && "$kb" -gt 0 ]]; then
                    metric="phys_footprint"
                fi
            fi
            if [[ -z "$kb" || "$kb" -le 0 ]]; then
                kb="$(ps -o rss= -p "$os_pid" 2>/dev/null | tr -d ' ' || true)"
                metric="rss_kb"
            fi
            ;;
        Linux)
            if [[ -r "/proc/$os_pid/smaps_rollup" ]]; then
                kb="$(awk '/^Pss:/ { print $2; exit }' "/proc/$os_pid/smaps_rollup" 2>/dev/null || true)"
                if [[ -n "$kb" && "$kb" -gt 0 ]]; then
                    metric="pss"
                fi
            fi
            if [[ -z "$kb" || "$kb" -le 0 ]] && [[ -r "/proc/$os_pid/status" ]]; then
                kb="$(awk '/^VmRSS:/ { print $2; exit }' "/proc/$os_pid/status" 2>/dev/null || true)"
                metric="vm_rss"
            fi
            ;;
        MINGW*|MSYS*|CYGWIN*)
            local bytes
            bytes="$(powershell -NoProfile -Command "try { (Get-Process -Id $os_pid -ErrorAction Stop).WorkingSet64 } catch { 0 }" 2>/dev/null | tr -d '\r\n ' || true)"
            if [[ -n "$bytes" && "$bytes" =~ ^[0-9]+$ && "$bytes" -gt 0 ]]; then
                kb="$(( (bytes + 1023) / 1024 ))"
                metric="working_set"
            fi
            ;;
    esac
    echo "${kb:-0} $metric"
}

wait_for_phase() {
    local app_pid="$1" phase="$2" max_tries="$3"
    local line=""
    for _ in $(seq "$max_tries"); do
        line="$(grep -m1 "frame-soak: phase $phase " "$log" 2>/dev/null || true)"
        if [[ -n "$line" ]]; then
            echo "$line"
            return 0
        fi
        kill -0 "$app_pid" 2>/dev/null || return 1
        sleep 0.2
    done
    return 1
}

field_of() {
    local line="$1" key="$2"
    sed -nE "s/.* ${key}=([0-9]+).*/\1/p" <<<"$line" | head -1
}

"$app" "--frame-soak=$frames" >> "$log" 2>&1 &
app_pid=$!

cleanup() {
    kill "$app_pid" 2>/dev/null || true
    sleep 0.5
    kill -9 "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
}
trap cleanup EXIT

max_tries=$(( timeout_s * 5 ))

if start_line="$(wait_for_phase "$app_pid" start "$max_tries")"; then
    os_pid="$(field_of "$start_line" pid)"
    start_frames="$(field_of "$start_line" frames)"
    read -r start_kb start_metric < <(sample_memory "${os_pid:-$app_pid}")
    printf 'compose-rust: frame-soak phase start frames=%s footprint_kb=%s metric=%s\n' \
        "${start_frames:-0}" "${start_kb:-0}" "${start_metric:-unknown}" >> "$log"
fi

if end_line="$(wait_for_phase "$app_pid" end "$max_tries")"; then
    os_pid="$(field_of "$end_line" pid)"
    end_frames="$(field_of "$end_line" frames)"
    read -r end_kb end_metric < <(sample_memory "${os_pid:-$app_pid}")
    printf 'compose-rust: frame-soak phase end frames=%s footprint_kb=%s metric=%s\n' \
        "${end_frames:-0}" "${end_kb:-0}" "${end_metric:-unknown}" >> "$log"
    printf 'compose-rust: frame-soak done frames=%s\n' "${end_frames:-0}" >> "$log"
fi

cleanup
trap - EXIT
