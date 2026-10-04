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

if grep -rnE "DXC_(APPKIT|WIN32|X11)_WINDOW" "$repo_root/renderer" --include='*.kt' --include='*.c' --include='*.m' --include='*.sh' --include='*.ps1' >/dev/null; then
    fail "something still reads a DXC_*_WINDOW variable, so the window depends on the environment"
fi

for pair in 'Mac:runAppKitWindow' 'Windows:runWin32Window' 'Linux:runX11Window'; do
    platform="${pair%%:*}"
    function="${pair##*:}"
    if ! grep -Eq "platform\.startsWith\(\"$platform\"\) -> \{" "$kotlin" || ! grep -q "$function(" "$kotlin"; then
        fail "RendererEntryPoints.kt does not open the window of our own for every $platform run"
    fi
done

# From the choice of window to the end of it there must be no environment read.
choice="$(awk '/when \{/ && !seen && /./ {on=0} /System.setProperty\("java.awt.headless"/{on=1} on{print} /^    \} catch/{exit}' "$kotlin")"
if grep -q 'getenv' <<< "$choice"; then
    fail "the choice of window reads the environment"
fi

# The toolkit's window is not an option on any desktop any more.
if grep -qE 'runRenderer\(' "$kotlin"; then
    fail "RendererEntryPoints.kt still falls back to the toolkit's window"
fi

if grep -q 'compose_rust_park_main_thread\|dxc_unify_titlebars\|DXC_APPKIT\|dxc_reclaim_caption\|SunAwtFrame' "$entry"; then
    fail "renderer_entry.c still carries the toolkit's window arrangement"
fi

[[ $status -eq 0 ]] && echo "every desktop opens its own window by default: ok"
exit $status
