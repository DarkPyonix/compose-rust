# Sourced by the tests that read the window code the Compose fork owns.
#
# `fork_window_or_skip <repo root>` sets `fork_window` to the extended/window directory of the
# fork at the commit renderer/scripts/build-compose.sh pins. Without a network it ends the
# test as skipped, except under CI, where a skip would be a pass nobody earned.
fork_window_or_skip() {
    local repo="$1" fetched
    if fetched="$("$repo/scripts/fetch-fork-window.sh" 2>/dev/null)"; then
        fork_window="$fetched/extended/window"
        return 0
    fi
    if [ -n "${CI:-}" ]; then
        echo "fail: could not fetch the window modules from the pinned fork commit" >&2
        exit 1
    fi
    echo "skipped: cannot reach the fork to read the window modules"
    exit 0
}
