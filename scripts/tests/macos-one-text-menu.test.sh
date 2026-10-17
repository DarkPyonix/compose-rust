#!/usr/bin/env bash
# The GraalVM macOS window puts up one right-click menu, and it is the one Compose asks for.
#
# The fork's AppKit window can put up a text edit menu of its own on a right click, once it is
# given one with setTextMenu. The renderer does not give it one. Its menu comes from Compose's
# LocalContextMenuRepresentation, which the renderer answers with the system's NSMenu. That
# path is the one that holds what an application added with ContextMenuDataProvider, and the
# one an application replaces when it provides a representation or a TextContextMenu of its own.
# The window's own menu knows neither: armed as well, a right click on a field would bring up
# two menus, and an application that draws its own would still get the system's beside it.
#
# So this checks that nothing in the renderer arms the window's menu, and that the Compose
# path is still the one wired in.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
renderer="$repo_root/renderer"
fail=0

armed="$(grep -rnE '\bsetTextMenu\b|dxc_native_set_text_menu|AppKitWindowPlatform' \
    "$renderer"/desktop/src "$renderer"/macos/src 2>/dev/null | grep -v '^\s*//' || true)"
if [[ -n "$armed" ]]; then
    echo "FAIL: the renderer arms the window's own text menu, which comes up beside Compose's:"
    echo "$armed"
    fail=1
fi

window="$renderer/desktop/src/AppKitWindow.kt"
if ! grep -q 'LocalContextMenuRepresentation provides menu' "$window" ||
   ! grep -q 'NativeContextMenuRepresentation {' "$window"; then
    echo "FAIL: $window no longer gives Compose's menu representation the system's NSMenu"
    fail=1
fi

if [[ "$fail" -ne 0 ]]; then
    exit 1
fi
echo "ok: one right-click menu on the GraalVM macOS window, the one Compose asks for"
