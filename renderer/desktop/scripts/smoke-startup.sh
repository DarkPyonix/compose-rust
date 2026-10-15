#!/usr/bin/env bash
# Sourced by smoke-test.sh and smoke-test-linux.sh. Decides whether a smoke run came up.
#
# A renderer that returns 0 has not shown that it drew anything, so the run must also have
# printed each line below. In order, they say: the window opened, a Compose scene was made with
# Compose's main-thread work on the renderer's own dispatcher, a frame reached the window, the
# Host was started and answered two events, and the run returned 0. An image built from
# upstream Compose opens its window and then dies making the scene, because upstream sends that
# work to Swing and Swing loads the toolkit's library. This is the check that fails on it.
#
# Kept apart from the smoke scripts so scripts/tests/smoke-startup.test.sh can run it without a
# renderer, a toolchain or a display.

smoke_startup_lines=(
    "compose-rust: a window of our own"
    "compose-rust: Compose scene created, main dispatcher dev.darkpyonix.composerust.ui.platform.FrameMainDispatcher "
    "compose-rust: first frame drawn"
    "compose_rust_host_init"
    "compose_rust_host_dispatch_event: click 2"
    "compose_rust_renderer_run returned 0"
)

# check_smoke_startup <everything the run printed> <its exit status>
# Prints why and returns 1 when the run did not come up.
check_smoke_startup() {
    local out="$1" status="$2" needed
    if [[ "$status" != 0 ]]; then
        echo "error: the smoke host exited with status $status" >&2
        echo "  A run that came up and was closed by COMPOSE_RUST_AUTOEXIT_MS exits 0." >&2
        return 1
    fi
    for needed in "${smoke_startup_lines[@]}"; do
        if ! grep -qF -- "$needed" <<< "$out"; then
            echo "error: the smoke run never printed '$needed'" >&2
            echo "  The renderer did not open its window, make a Compose scene on its own" >&2
            echo "  dispatcher and draw a frame. A scene that dies loading the toolkit's library" >&2
            echo "  means the image was built from upstream Compose rather than the fork's." >&2
            return 1
        fi
    done
}
