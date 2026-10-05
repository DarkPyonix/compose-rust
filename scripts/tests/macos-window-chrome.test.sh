#!/usr/bin/env bash
# Both macOS windows build their title bar from MacosWindowChrome and measure their caption
# with macosWindowCaption.
#
# The native image's window and the Kotlin/Native one each used to decide these for
# themselves, and they drifted: one had a unified toolbar and the other clipped its corner
# by hand, so the same application had different corners and its content at a different
# height. The Kotlin tests check the decision and the function; this checks that both
# windows still go through them, which no single-target test can see.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
appkit_c="$repo_root/renderer/desktop/c/appkit_window.m"
appkit_kt="$repo_root/renderer/desktop/src/AppKitWindow.kt"
native_kt="$repo_root/renderer/macos/src/MacosWindow.kt"
native_entry="$repo_root/renderer/macos/src/MacosRenderer.kt"
shared_link="$repo_root/renderer/macos/src/shared/MacosWindowChrome.kt"
status=0

fail() { echo "FAIL: $1"; status=1; }

for file in "$appkit_c" "$appkit_kt" "$native_kt" "$native_entry"; do
    [[ -f "$file" ]] || { echo "FAIL: $file is missing"; exit 1; }
done

[[ -L "$shared_link" ]] || fail "the Kotlin/Native renderer does not link the shared MacosWindowChrome.kt"

grep -q 'MacosWindowChrome.of(' "$appkit_kt" || fail "the native image window does not ask MacosWindowChrome"
grep -q 'configureNativeWindowChrome(chrome)' "$appkit_kt" || fail "the native image window does not hand the chrome to C"
grep -q 'macosWindowCaption(' "$appkit_kt" || fail "the native image window measures its caption on its own"

grep -q 'MacosWindowChrome.of(' "$native_entry" || fail "the Kotlin/Native window does not ask MacosWindowChrome"
grep -q 'applyChrome(window, chrome)' "$native_kt" || fail "the Kotlin/Native window does not apply the chrome"
grep -q 'macosWindowCaption(' "$native_kt" || fail "the Kotlin/Native window measures its caption on its own"
if grep -q 'layer.cornerRadius\|layer?.cornerRadius' "$native_kt"; then
    fail "the Kotlin/Native window clips its own corner, which stands inside the system's"
fi

# In the C window, every title bar property comes from the options the chrome filled in.
open_body="$(awk '/^int32_t dxc_native_window_open\(/{on=1} on{print} on && /^}/{exit}' "$appkit_c")"
grep -q 'dxc_options.full_size_content' <<< "$open_body" || fail "the C window decides the full size content view itself"
grep -q 'dxc_options.transparent_title_bar' <<< "$open_body" || fail "the C window decides title bar transparency itself"
grep -q 'dxc_options.title_hidden' <<< "$open_body" || fail "the C window decides the title visibility itself"
grep -q 'dxc_options.unified_toolbar' <<< "$open_body" || fail "the C window decides the toolbar itself"
if grep -q 'system_chrome' <<< "$open_body"; then
    fail "the C window still builds its title bar from system_chrome instead of the shared chrome"
fi

[[ $status -eq 0 ]] && echo "both macOS windows build from one chrome description: ok"
exit $status
