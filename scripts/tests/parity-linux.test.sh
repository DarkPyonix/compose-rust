#!/usr/bin/env bash
# The parity scripts' rows are what the CI summary is read by, so the rows they write have to
# be the rows they document, the names the windows print have to be the ones they read, and
# every window the scripts run has to feed the same measurements: the shared latency trace,
# and the resize count kept by the fork's ResizeStats on Linux and the Kotlin/Native macOS
# window (the GraalVM AppKit window counts with appkit_resize.h, by the same rules).
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo_root"
script="$repo_root/scripts/parity/linux.sh"
trace="$repo_root/renderer/desktop/src/LatencyTrace.kt"
status=0
fail() { echo "FAIL: $1"; status=1; }

bash -n "$script" || fail "the Linux parity script does not parse"
for other in common.sh macos.sh; do
    bash -n "$repo_root/scripts/parity/$other" || fail "scripts/parity/$other does not parse"
done
grep -q 'report_measurements' "$repo_root/scripts/parity/macos.sh" || fail "the macOS parity run does not read the same rows"

# Every row the header promises is written by the script.
for name in input_latency_avg_ms frame_resize_avg_ms frame_resize_worst_ms frame_idle_avg_ms \
            clipboard system_theme min_size icon shortcut_ctrl_ resize_steps resize_stretched; do
    grep -q "$name" "$script" "$repo_root/scripts/parity/common.sh" || fail "the parity scripts no longer mention $name"
done

# And every number it reads is one the shared trace prints.
grep -q 'parity frame_\${name}_avg_ms' "$trace" || fail "LatencyTrace does not print the per-phase frame time"
grep -q 'parity input_latency_avg_ms' "$trace" || fail "LatencyTrace does not print the input latency"
grep -q 'parity system_theme' "$trace" || fail "LatencyTrace does not print the system theme"

# The resize line is the one the script reads, in the fork's shared count.
stats="$fork_window/common/src/org/thisisthepy/compose/window/ResizeStats.kt"
[[ -f "$stats" ]] || fail "the fork has no shared resize count"
grep -q 'dxc resize: steps=' "$stats" || fail "ResizeStats does not print the line the parity script reads"
grep -q 'stale_ratio=' "$stats" || fail "ResizeStats does not print the stale ratio"
grep -q 'resizeStats.step(' "$fork_window/native/linux/src/X11Window.kt" || fail "the Kotlin/Native X11 window does not count resize steps"
grep -q 'resizeStats.step(' "$fork_window/graalvm/graalvm-linux/src/org/thisisthepy/compose/window/graalvm/linux/X11Window.kt" \
    || fail "the GraalVM X11 window does not count resize steps"
grep -q 'resizeStats.step(' "$fork_window/native/macos/src/org/thisisthepy/compose/window/macos/MacosWindow.kt" \
    || fail "the Kotlin/Native macOS window does not count resize steps"

# Every window the scripts run feeds the same trace and prints the resize line.
for window in renderer/desktop/src/X11Window.kt renderer/linux/src/LinuxWindow.kt renderer/macos/src/MacosParityRun.kt; do
    grep -q 'LatencyTrace.frameDrawn' "$repo_root/$window" || fail "$window does not count its frames"
    grep -q 'LatencyTrace.inputSent' "$repo_root/$window" || fail "$window does not time typed input"
    grep -q 'LatencyTrace.summary' "$repo_root/$window" || fail "$window does not print the summary"
    grep -q 'DXC_REPORT_RESIZE' "$repo_root/$window" || fail "$window does not print the resize line"
    grep -q 'scriptedResize(' "$repo_root/$window" || fail "$window does not take the window through a drag"
done
grep -q 'startParityRun(' "$repo_root/renderer/macos/src/MacosRenderer.kt" || fail "the macOS renderer never starts a parity run"
for shared in LatencyTrace.kt SyntheticInput.kt; do
    for platform in linux macos; do
        [[ "$(readlink "$repo_root/renderer/$platform/src/shared/$shared")" == "../../../desktop/src/$shared" ]] \
            || fail "the $platform renderer does not link the one $shared"
    done
done

# Both paths on both platforms run the scripts in CI.
grep -q 'scripts/parity/linux.sh native' "$repo_root/.github/workflows/static-renderer.yml" || fail "the Kotlin/Native Linux window is not measured in CI"
grep -q 'scripts/parity/macos.sh native' "$repo_root/.github/workflows/static-renderer.yml" || fail "the Kotlin/Native macOS window is not measured in CI"
grep -q 'scripts/parity/linux.sh graalvm' "$repo_root/.github/workflows/test-graalvm-renderer.yml" || fail "the GraalVM X11 window is not measured in CI"
grep -q 'scripts/parity/macos.sh graalvm' "$repo_root/.github/workflows/test-graalvm-renderer.yml" || fail "the GraalVM AppKit window is not measured in CI"

[[ $status -eq 0 ]] && echo "the parity scripts and the windows agree on what is measured: ok"
exit $status
