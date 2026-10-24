#!/usr/bin/env bash
# The window Linux opens is the X11 one of our own, with no environment variable asking for it.
#
# It used to be reached only when DXC_X11_WINDOW was set, so every run without it opened the
# toolkit's window. A test cannot open a window here, but it can read the place that decides:
# the Kotlin entry point must name the X11 window for every Linux run, without consulting the
# environment, and must keep the toolkit from being woken. Windows keeps its own default.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
kotlin="$repo_root/renderer/desktop/src/RendererEntryPoints.kt"
status=0

fail() { echo "FAIL: $1"; status=1; }

[[ -f "$kotlin" ]] || { echo "FAIL: the entry point is missing"; exit 1; }

if grep -rn "DXC_X11_WINDOW" "$repo_root/renderer" "$repo_root/compose-rust" "$repo_root/scripts" \
    --include='*.kt' --include='*.c' --include='*.m' --include='*.sh' --include='*.rs' --include='*.md' |
    grep -v 'linux-window-default.test.sh' | grep -q .; then
    fail "something still reads DXC_X11_WINDOW, so the window depends on the environment"
fi

if ! grep -Eq 'if \(platform\.startsWith\("Linux"\)\) \{' "$kotlin" || ! grep -q 'runX11Window(' "$kotlin"; then
    fail "RendererEntryPoints.kt does not open the X11 window for every Linux run"
fi

branch="$(awk '/if \(platform.startsWith\("Linux"\)\) \{/{on=1} on{print} on && /^        \}/{exit}' "$kotlin")"
if grep -q 'getenv' <<< "$branch"; then
    fail "the Linux branch reads the environment to decide on its window"
fi
if ! grep -q 'java.awt.headless' <<< "$branch"; then
    fail "the Linux branch does not say there is no display for the toolkit to open"
fi

[[ $status -eq 0 ]] && echo "Linux opens its own window by default: ok"
exit $status
