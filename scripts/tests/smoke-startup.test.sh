#!/usr/bin/env bash
# The desktop smoke runs pass only when a window came up, a Compose scene was made on the
# renderer's own main dispatcher, a frame was drawn and the run returned 0.
#
# Checks renderer/desktop/scripts/smoke-startup.sh, which smoke-test.sh and smoke-test-linux.sh
# both use, against recorded output: a run that came up, and the ways a run fails to. The one
# that matters most is an image built from upstream Compose. It opens its window, starts the
# Host and dies making the scene with the toolkit's library missing, and a check that only
# looked for the Host's lines let that through.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../../renderer/desktop/scripts/smoke-startup.sh
source "$repo_root/renderer/desktop/scripts/smoke-startup.sh"

tests_run=0
tests_failed=0

# expect <name> <pass|fail> <status> <output> [text the refusal must name]
expect() {
    local name="$1" want="$2" status="$3" out="$4" names="${5:-}" said got
    tests_run=$((tests_run + 1))
    said="$(check_smoke_startup "$out" "$status" 2>&1)" && got=pass || got=fail
    if [[ "$got" != "$want" ]]; then
        tests_failed=$((tests_failed + 1))
        printf 'FAIL %s: the check said %s, expected %s\n%s\n' "$name" "$got" "$want" "$said" >&2
        return
    fi
    if [[ -n "$names" && "$said" != *"$names"* ]]; then
        tests_failed=$((tests_failed + 1))
        printf 'FAIL %s: the refusal does not name %s:\n%s\n' "$name" "$names" "$said" >&2
        return
    fi
    printf 'ok   %s\n' "$name"
}

window='compose-rust: a window of our own, 520x360 at 1.0x, with no toolkit in it'
scene='compose-rust: Compose scene created, main dispatcher dev.darkpyonix.composerust.ui.platform.FrameMainDispatcher (compose.main.dispatcher=coroutines), window 520x360 at 1.0x'
frame='compose-rust: first frame drawn, 520x360'
host='compose_rust_host_init: 180 bytes, 8 records
compose_rust_host_dispatch_event: click 1
compose_rust_host_dispatch_event: click 2
compose_rust_host_shutdown'
returned='compose_rust_renderer_run returned 0'

came_up="$window
$scene
$frame
$host
$returned"
expect nfr14_smoke_passes_a_run_that_drew_a_frame pass 0 "$came_up"

# What the macOS image printed when upstream Compose was linked in (a local build, 2026-10-05).
upstream="compose-rust: a window of our own, 480x640 at 1.0x, with no toolkit in it
compose_rust_host_init: 180 bytes, 8 records
Exception in thread \"main\" java.lang.UnsatisfiedLinkError: Can't load library: libawt_lwawt.dylib
	at java.desktop@25.0.4.1/java.awt.Toolkit.<clinit>(Toolkit.java:1299)
	at org.jetbrains.skiko.SwingDispatcher.dispatch(MainUIDispatcher.awt.kt:32)
	at androidx.compose.ui.platform.GlobalSnapshotManager.ensureStarted(GlobalSnapshotManager.skiko.kt:43)
	at androidx.compose.ui.scene.BaseComposeScene.<init>(BaseComposeScene.skiko.kt:120)"
expect nfr14_smoke_fails_an_image_built_from_upstream_compose fail 1 "$upstream" "exited with status 1"
# The same run, had the process somehow returned 0: the missing scene still fails it.
expect nfr14_smoke_fails_when_no_scene_was_made fail 0 "$upstream
$returned" "Compose scene created"

on_swing="$window
compose-rust: Compose scene created, main dispatcher kotlinx.coroutines.swing.SwingDispatcher (compose.main.dispatcher=coroutines), window 520x360 at 1.0x
$frame
$host
$returned"
expect nfr14_smoke_fails_a_scene_on_another_main_dispatcher fail 0 "$on_swing" "FrameMainDispatcher"

no_frame="$window
$scene
$host
$returned"
expect nfr14_smoke_fails_when_no_frame_was_drawn fail 0 "$no_frame" "first frame drawn"

no_window="$scene
$frame
$host
$returned"
expect nfr14_smoke_fails_when_no_window_opened fail 0 "$no_window" "a window of our own"

expect nfr14_smoke_fails_a_nonzero_exit fail 3 "$came_up" "exited with status 3"

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[[ $tests_failed -eq 0 ]]
