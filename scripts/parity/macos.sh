#!/usr/bin/env bash
# Usage: scripts/parity/macos.sh <label> <output directory> -- <command ...>
#
# The measured half of the parity checklist for one macOS window path, in the same rows
# scripts/parity/linux.sh writes: input latency, frame time while resizing and otherwise, the
# resize accounting with its share of frames at the wrong size, and the theme the window reads.
# Run once for the GraalVM path and once for the Kotlin/Native path with the same application.
#
# What it cannot do here is what a hand does: a hosted macOS session gives a process no
# accessibility permission, so a key cannot be posted from outside, the clipboard cannot be
# driven through a second process, and the window's hints cannot be read back. Those rows say
# n/a. Everything the window can do to itself, it does: DXC_SYNTH types five letters into the
# first text field and takes the window through sixty sizes, as it does on Linux.
#
# A row that fails is printed as FAIL and the script still exits 0; PARITY_STRICT=1 makes any
# FAIL the exit status.
set -uo pipefail

if [[ $# -lt 4 || "$3" != "--" ]]; then
    echo "usage: $0 <label> <output directory> -- <command ...>" >&2
    exit 2
fi
label="$1"
out="$2"
shift 3
mkdir -p "$out"
out="$(cd "$out" && pwd)"
err="$out/$label.err"
log="$out/$label.out"
table="$out/$label.tsv"
: > "$table"

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

export DXC_SYNTH="type,resize,exit"
export DXC_SYNTH_RESIZE_AT="${DXC_SYNTH_RESIZE_AT:-8}"
export DXC_SYNTH_EXIT_AT="${DXC_SYNTH_EXIT_AT:-12}"
export DXC_REPORT_LATENCY=1
export DXC_REPORT_RESIZE=1

"$@" >"$log" 2>"$err" &
app=$!
trap 'kill "$app" 2>/dev/null || true' EXIT

# The run closes itself. Given two minutes to open a window and do it.
for _ in $(seq 1 240); do
    kill -0 "$app" 2>/dev/null || break
    sleep 0.5
done
if kill -0 "$app" 2>/dev/null; then
    row exit "FAIL the application did not close itself"
    kill "$app" 2>/dev/null || true
fi
wait "$app" 2>/dev/null
trap - EXIT

for name in shortcut_ctrl_a shortcut_ctrl_c shortcut_ctrl_v shortcut_ctrl_x shortcut_ctrl_z \
            clipboard min_size icon; do
    row "$name" "n/a (needs a hand or accessibility permission)"
done

report_measurements
print_table
