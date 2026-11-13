#!/usr/bin/env bash
# Every Windows run of the native image opens the window of our own, with no environment
# variable asking for it.
#
# It was reached only when DXC_WIN32_WINDOW was set, so every run without it opened the
# toolkit's window, and the window the Kotlin/Native renderer ships was exercised on the
# native image by hand. A test cannot open a window here, but it can read the place that
# decides: the Kotlin entry point.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
kotlin="renderer/desktop/src/RendererEntryPoints.kt"
status=0

fail() { echo "FAIL: $1"; status=1; }

[ -f "$kotlin" ] || { echo "FAIL: $kotlin is missing"; exit 1; }

if git grep -n "DXC_WIN32_WINDOW" -- renderer compose-rust .github scripts ':!scripts/tests/windows-window-default.test.sh' >/dev/null; then
    fail "something still reads DXC_WIN32_WINDOW, so the Windows window depends on the environment"
fi

if ! grep -qE '^        if \(platform\.startsWith\("Windows"\)\) \{$' "$kotlin"; then
    fail "RendererEntryPoints.kt does not open the window of our own for every Windows run"
fi

# From that choice to the window being run, only the auto-exit time is read.
choice="$(awk '/if \(platform\.startsWith\("Windows"\)\) \{/{on=1} on{print} on && /return@rendererRun 0/{exit}' "$kotlin")"
grep -q 'runWin32Window(' <<< "$choice" || fail "the Windows branch does not run the Win32 window"
if grep -v 'AUTOEXIT_MS' <<< "$choice" | grep -q 'getenv'; then
    fail "the Windows choice of window reads the environment"
fi

[ $status -eq 0 ] && echo "ok: Windows opens its own window by default"
exit $status
