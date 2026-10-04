#!/usr/bin/env bash
# Every desktop opens the window of our own, with no environment variable asking for it.
#
# Each was reached only when a variable was set, so every run without it opened the
# toolkit's window and the one of our own was exercised by hand. A test cannot open a window
# here, but it can read the two places that decide: the Kotlin entry point, which must name
# a window for each platform without consulting the environment, and the C entry, which must
# not start the older arrangement of a second thread for the toolkit.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
kotlin="$repo_root/renderer/desktop/src/RendererEntryPoints.kt"
entry="$repo_root/renderer/desktop/c/renderer_entry.c"
status=0

fail() { echo "FAIL: $1"; status=1; }

[[ -f "$kotlin" && -f "$entry" ]] || { echo "FAIL: the entry points are missing"; exit 1; }

# Linux is the exception while its window waits for a check on a real desktop: it opens the
# toolkit's unless DXC_X11_WINDOW asks, and that one variable is read in RendererEntryPoints.kt
# alone. The flip removes this allowance.
if grep -rnE "DXC_(APPKIT|WIN32)_WINDOW|DXC_X11_WINDOW" "$repo_root/renderer" --include='*.kt' --include='*.c' --include='*.m' --include='*.sh' --include='*.ps1' | grep -v 'RendererEntryPoints.kt\|Win32Window.kt\|X11Window.kt' | grep -q .; then
    fail "something besides the entry point reads a DXC_*_WINDOW variable"
fi
if grep -rnE "DXC_(APPKIT|WIN32)_WINDOW" "$repo_root/renderer" --include='*.kt' --include='*.c' --include='*.m' --include='*.sh' --include='*.ps1' >/dev/null; then
    fail "something still reads a DXC_*_WINDOW variable, so the window depends on the environment"
fi

for pair in 'Mac:runAppKitWindow' 'Windows:runWin32Window'; do
    platform="${pair%%:*}"
    function="${pair##*:}"
    if ! grep -Eq "platform\.startsWith\(\"$platform\"\) -> \{" "$kotlin" || ! grep -q "$function(" "$kotlin"; then
        fail "RendererEntryPoints.kt does not open the window of our own for every $platform run"
    fi
done

# From the choice of window to the end of it there must be no environment read.
choice="$(grep -v 'linuxOwnWindow = ' "$kotlin" | awk '/when \{/ && !seen && /./ {on=0} /System.setProperty\("java.awt.headless"/{on=1} on{print} /^    \} catch/{exit}')"
if grep -q 'getenv' <<< "$choice"; then
    fail "the choice of window reads the environment"
fi

if ! grep -q 'linuxOwnWindow ->' "$kotlin" || ! grep -q 'runX11Window(' "$kotlin"; then
    fail "RendererEntryPoints.kt no longer reaches the X11 window"
fi

if grep -q 'compose_rust_park_main_thread\|dxc_unify_titlebars\|DXC_APPKIT\|dxc_reclaim_caption\|SunAwtFrame' "$entry"; then
    fail "renderer_entry.c still carries the toolkit's window arrangement"
fi

[[ $status -eq 0 ]] && echo "every desktop opens its own window by default: ok"
exit $status
