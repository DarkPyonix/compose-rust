#!/usr/bin/env bash
# Sourced by the smoke runs: smoke-test.sh and smoke-test-linux.sh for the native image,
# smoke-test-kn.sh and .github/scripts/check-single-executable.sh for the Kotlin/Native
# renderers. Decides whether a smoke run came up.
#
# A renderer that returns 0 has not shown that it drew anything, so the run must also have
# printed the window start lines (renderer/desktop/src/WindowStartReport.kt, which every
# desktop renderer compiles). In order, they say: the window opened, a Compose scene was made
# with Compose's main-thread work on the dispatcher this renderer has to have, and a frame
# reached the window. An image built from upstream Compose opens its window and then dies
# making the scene, because upstream sends that work to Swing and Swing loads the toolkit's
# library. This is the check that fails on it.
#
# Kept apart from the smoke scripts so scripts/tests/smoke-startup.test.sh and
# scripts/tests/kn-start-report.test.sh can run it without a renderer, a toolchain or a
# display.

# The class each renderer's `Dispatchers.Main` is. The native image installs its own, which
# runs the work on the window's thread once a frame. Kotlin/Native on macOS has
# kotlinx.coroutines' dispatcher onto the main run loop, which is where the fork's window
# sends the scene's work. Kotlin/Native on Linux has none of its own, so the renderer installs
# the window's frame dispatcher as `Dispatchers.Main` before it makes the scene. A Linux run
# that names kotlinx.coroutines' stand-in instead has a `Dispatchers.Main` that throws.
smoke_main_dispatcher_native_image="dev.darkpyonix.composerust.ui.platform.FrameMainDispatcher"
smoke_main_dispatcher_kn_macos="kotlinx.coroutines.DarwinMainDispatcher"
smoke_main_dispatcher_kn_linux="dev.darkpyonix.composerust.ui.platform.FrameDispatcher"

# What the native image's smoke host prints on top: it was started and answered two events.
smoke_host_lines=(
    "compose_rust_host_init"
    "compose_rust_host_dispatch_event: click 2"
    "compose_rust_renderer_run returned 0"
)

# check_window_start <everything the run printed> <its exit status> <main dispatcher class>
# Prints why and returns 1 when the run did not come up: it exited with anything but 0, or
# one of the three start lines is missing, or the scene's main dispatcher is another class.
check_window_start() {
    local out="$1" status="$2" dispatcher="$3" needed
    if [[ "$status" != 0 ]]; then
        echo "error: the smoke run exited with status $status" >&2
        echo "  A run that came up and was closed (by COMPOSE_RUST_AUTOEXIT_MS, or by the" >&2
        echo "  application's self-check once its frames were in) exits 0." >&2
        # What it said last, and on macOS what the system recorded about the crash: an abort
        # (134) or a crash (139) otherwise leaves nothing in the log to say where.
        echo "-- the last lines it printed:" >&2
        tail -40 <<< "$out" >&2
        if [[ "$status" -gt 128 && "$(uname -s)" == Darwin ]]; then
            local report
            report="$(ls -t "$HOME"/Library/Logs/DiagnosticReports/*.ips 2>/dev/null | head -1 || true)"
            if [[ -n "$report" ]]; then
                echo "-- crash report $report (first 120 lines):" >&2
                head -120 "$report" >&2
            else
                echo "-- no crash report in ~/Library/Logs/DiagnosticReports" >&2
            fi
        fi
        return 1
    fi
    for needed in \
        "compose-rust: a window of our own" \
        "compose-rust: Compose scene created, main dispatcher $dispatcher " \
        "compose-rust: first frame drawn"; do
        if ! grep -qF -- "$needed" <<< "$out"; then
            echo "error: the smoke run never printed '$needed'" >&2
            echo "  The renderer did not open its window, make a Compose scene with its" >&2
            echo "  main-thread work on $dispatcher and draw a frame." >&2
            echo "  A scene that dies loading the toolkit's library means the image was built" >&2
            echo "  from upstream Compose rather than the fork's." >&2
            return 1
        fi
    done
}

# check_smoke_startup <everything the run printed> <its exit status>
# The native image's smoke: the start lines on its own dispatcher, and the smoke host's.
check_smoke_startup() {
    local out="$1" status="$2" needed
    check_window_start "$out" "$status" "$smoke_main_dispatcher_native_image" || return 1
    for needed in "${smoke_host_lines[@]}"; do
        if ! grep -qF -- "$needed" <<< "$out"; then
            echo "error: the smoke run never printed '$needed'" >&2
            echo "  The smoke host was not started, or did not answer the two clicks." >&2
            return 1
        fi
    done
}

# Run rather than sourced, by a smoke run that is not bash (smoke-test-windows.ps1):
#   smoke-startup.sh check-log <log file> <exit status>
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    case "${1:-}" in
        check-log)
            check_smoke_startup "$(cat "$2")" "$3"
            exit $?
            ;;
        *)
            echo "usage: smoke-startup.sh check-log <log file> <exit status>" >&2
            exit 2
            ;;
    esac
fi
