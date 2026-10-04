#!/usr/bin/env bash
# The parity script's rows are what the CI summary is read by, so the rows it writes have to
# be the rows it documents, and the names the windows print have to be the ones it reads.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/parity/linux.sh"
trace="$repo_root/renderer/desktop/src/LatencyTrace.kt"
status=0
fail() { echo "FAIL: $1"; status=1; }

bash -n "$script" || fail "the parity script does not parse"
for other in common.sh macos.sh; do
    bash -n "$repo_root/scripts/parity/$other" || fail "scripts/parity/$other does not parse"
done
grep -q 'report_measurements' "$repo_root/scripts/parity/macos.sh" || fail "the macOS parity run does not read the same rows"

# Every row the header promises is written by the script.
for name in input_latency_avg_ms frame_resize_avg_ms frame_resize_worst_ms frame_idle_avg_ms \
            clipboard system_theme min_size icon shortcut_ctrl_ resize_steps resize_stretched; do
    grep -q "$name" "$script" || fail "the parity script no longer mentions $name"
done

# And every number it reads is one the shared trace prints.
grep -q 'parity frame_\${name}_avg_ms' "$trace" || fail "LatencyTrace does not print the per-phase frame time"
grep -q 'parity input_latency_avg_ms' "$trace" || fail "LatencyTrace does not print the input latency"
grep -q 'parity system_theme' "$trace" || fail "LatencyTrace does not print the system theme"

# The resize accounting is the one header, counted by the same calls on both paths.
grep -q 'dxc_resize_step(' "$repo_root/renderer/desktop/c/x11_window.c" || fail "x11_window.c does not count resize steps"
grep -q 'dxc_resize_step(' "$repo_root/renderer/linux/src/LinuxWindow.kt" || fail "the Kotlin/Native window does not count resize steps"
[[ "$(readlink "$repo_root/renderer/linux/cinterop/include/appkit_resize.h")" == "../../../desktop/c/appkit_resize.h" ]] \
    || fail "the Kotlin/Native window reads a copy of the resize header rather than the one"

# The macOS Kotlin/Native window feeds the same trace and counts with the same header.
for needle in 'LatencyTrace.frameDrawn' 'LatencyTrace.inputSent' 'LatencyTrace.summary' 'dxc_resize_step(' 'dxc_resize_present_against('; do
    grep -q "$needle" "$repo_root/renderer/macos/src/MacosWindow.kt" || fail "MacosWindow.kt does not call $needle"
done

# Both windows that run it feed the same trace.
for window in renderer/desktop/src/X11Window.kt renderer/linux/src/LinuxWindow.kt; do
    grep -q 'LatencyTrace.frameDrawn' "$repo_root/$window" || fail "$window does not count its frames"
    grep -q 'LatencyTrace.inputSent' "$repo_root/$window" || fail "$window does not time typed input"
    grep -q 'LatencyTrace.summary' "$repo_root/$window" || fail "$window does not print the summary"
done

[[ $status -eq 0 ]] && echo "the parity script and the windows agree on what is measured: ok"
exit $status
