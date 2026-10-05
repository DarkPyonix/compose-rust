#!/usr/bin/env bash
# Both macOS windows build their title bar from MacosWindowChrome and measure their caption
# with macosWindowCaption.
#
# The native image's window and the Kotlin/Native one each used to decide these for
# themselves, and they drifted: one had a unified toolbar and the other clipped its corner
# by hand, so the same application had different corners and its content at a different
# height. The windows themselves are the Compose fork's (extended/window), which tests that
# they apply what they are given. This checks that both renderers still decide it in one
# place and hand that decision to their window, which no single-target test can see.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
appkit_kt="$repo_root/renderer/desktop/src/AppKitWindow.kt"
native_entry="$repo_root/renderer/macos/src/MacosRenderer.kt"
shared_link="$repo_root/renderer/macos/src/shared/MacosWindowChrome.kt"
status=0

fail() { echo "FAIL: $1"; status=1; }

for file in "$appkit_kt" "$native_entry"; do
    [[ -f "$file" ]] || { echo "FAIL: $file is missing"; exit 1; }
done

[[ -L "$shared_link" ]] || fail "the Kotlin/Native renderer does not link the shared MacosWindowChrome.kt"

grep -q 'MacosWindowChrome.of(' "$appkit_kt" || fail "the native image window does not ask MacosWindowChrome"
grep -q 'chromeForNextWindow(chrome)' "$appkit_kt" || fail "the native image window does not hand the chrome to C"
grep -q 'macosWindowCaption(' "$appkit_kt" || fail "the native image window measures its caption on its own"

grep -q 'MacosWindowChrome.of(' "$native_entry" || fail "the Kotlin/Native window does not ask MacosWindowChrome"
grep -q 'fullSizeContentView = chrome.fullSizeContentView' "$native_entry" \
    || fail "the Kotlin/Native window is not given the chrome MacosWindowChrome chose"
if grep -q 'buttonInset\|cornerRadius' "$native_entry"; then
    fail "the Kotlin/Native window is given its own corner or button inset, which stands inside the system's"
fi

[[ $status -eq 0 ]] && echo "both macOS windows build from one chrome description: ok"
exit $status
