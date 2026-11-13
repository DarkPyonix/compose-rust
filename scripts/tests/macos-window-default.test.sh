#!/usr/bin/env bash
# The window macOS opens is the one of our own, with no environment variable asking for it.
#
# It used to be reached only when a variable was set, so every run without it opened the
# toolkit's window and the one of our own was exercised by hand. A test cannot open a window
# here, but it can read the two places that decide: the Kotlin entry point, which must not
# consult the environment to choose a macOS window, and the C entry, which must not start
# the older arrangement of a second thread for the toolkit.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
kotlin="$repo_root/renderer/desktop/src/RendererEntryPoints.kt"
entry="$repo_root/renderer/desktop/c/renderer_entry.c"
status=0

fail() { echo "FAIL: $1"; status=1; }

[[ -f "$kotlin" && -f "$entry" ]] || { echo "FAIL: the entry points are missing"; exit 1; }

if grep -rn "DXC_APPKIT_WINDOW" "$repo_root/renderer" --include='*.kt' --include='*.c' --include='*.m' --include='*.sh' >/dev/null; then
    fail "something still reads DXC_APPKIT_WINDOW, so the window depends on the environment"
fi

if ! grep -Eq 'startsWith\("Mac"\)\) \{' "$kotlin" || ! grep -q 'runAppKitWindow(' "$kotlin"; then
    fail "RendererEntryPoints.kt does not open the window of our own for every macOS run"
fi

# Between the macOS branch and its closing brace there must be no environment read.
branch="$(awk '/if \(platform.startsWith\("Mac"\)\) \{/{on=1} on{print} on && /^        \}/{exit}' "$kotlin")"
if grep -q 'getenv' <<< "$branch"; then
    fail "the macOS branch reads the environment to decide on its window"
fi

if grep -q 'compose_rust_park_main_thread\|dxc_unify_titlebars\|DXC_APPKIT' "$entry"; then
    fail "renderer_entry.c still carries the toolkit's main-thread arrangement"
fi

[[ $status -eq 0 ]] && echo "macOS opens its own window by default: ok"
exit $status
