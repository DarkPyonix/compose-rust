#!/usr/bin/env bash
# Runs an application built on a Kotlin/Native desktop renderer and fails unless it came up.
#
# Usage: smoke-test-kn.sh <application> <macos|linux>
#
#   <application>  an executable linked against the static renderer (build-macos.sh or
#                  build-linux.sh) that takes --self-check, as the consumer fixture
#                  (compose-rust/tests/fixtures/consumer) does: it opens the window and ends
#                  the process with 0 once the renderer has driven its frames.
#   <macos|linux>  which renderer it links, which decides the main dispatcher its scene line
#                  has to name.
#
# Came up means what it means for the native image (smoke-startup.sh): the run exited 0 and
# printed the window line, the scene line on that platform's main dispatcher, and the first
# frame line. Everything the run printed is echoed, the renderer's standard error included.
#
# Needs a display. On Linux run it under `xvfb-run -a`; a macOS runner has one.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/smoke-startup.sh"

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <application> <macos|linux>" >&2
    exit 2
fi
application="$1"
case "$2" in
    macos) dispatcher="$smoke_main_dispatcher_kn_macos" ;;
    linux) dispatcher="$smoke_main_dispatcher_kn_linux" ;;
    *)
        echo "error: no Kotlin/Native desktop renderer for '$2'; there is one for macos and linux" >&2
        exit 2
        ;;
esac
[[ -x "$application" ]] || { echo "error: $application is not an executable" >&2; exit 2; }

# The Kotlin/Native renderers cannot close their own window, so a run told to wait for that
# would never end. The self-check ends the process itself once the frames are in.
unset COMPOSE_RUST_AUTOEXIT_MS

status=0
out="$("$application" --self-check 2>&1)" || status=$?
echo "$out"
check_window_start "$out" "$status" "$dispatcher" || exit 1
echo "ok    the $2 renderer opened its window, made a scene on $dispatcher and drew a frame"
