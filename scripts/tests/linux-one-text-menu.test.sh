#!/usr/bin/env bash
# The Linux windows put up one right-click menu on a text field, and it is Compose's drawn menu.
#
# X11 has no toolkit-free standard OS popup menu, so both Linux paths rely on Compose's drawn
# context menu backed by the X11 CLIPBOARD selection so Paste enabled state follows clipboard
# text.
#
# The Kotlin/Native Linux renderer keeps ComposeFoundationFlags.isNewContextMenuEnabled = false
# and platformTextMenuModifier = Modifier (nothing suppresses Compose's menu), and wires
# LocalClipboard and LocalClipboardManager to X11Window's readClipboardText / writeClipboardText.
#
# The GraalVM Linux window (X11Window.kt) mounts NativeWindowContent, which provides
# LocalClipboard and LocalClipboardManager backed by the window's pasteboard.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
renderer="$repo_root/renderer"
linux="$renderer/linux"
shared="$renderer/desktop/src/renderer"
red=0
fail() {
    echo "fail: $1"
    red=1
}

# The Kotlin/Native Linux window wires LocalClipboard and LocalClipboardManager to X11 clipboard.
linux_window="$linux/src/LinuxWindow.kt"
grep -Fq 'LocalClipboard provides clipboard' "$linux_window" ||
    fail "LinuxWindow.kt does not provide LocalClipboard"
grep -Fq 'LocalClipboardManager provides clipboardManager' "$linux_window" ||
    fail "LinuxWindow.kt does not provide LocalClipboardManager"
grep -Fq 'x11::readClipboardText' "$linux_window" ||
    fail "LinuxWindow.kt does not back clipboard reads with x11::readClipboardText"
grep -Fq 'x11::writeClipboardText' "$linux_window" ||
    fail "LinuxWindow.kt does not back clipboard writes with x11::writeClipboardText"

# Nothing on Linux suppresses Compose's drawn text menu or enables the unimplemented new menu path.
suppressed="$(grep -rnE 'platformTextMenuModifier\s*=|isNewContextMenuEnabled\s*=\s*true' \
    "$linux/src" 2>/dev/null | grep -v '^\s*//' || true)"
if [[ -n "$suppressed" ]]; then
    fail "renderer/linux/src suppresses Compose's drawn text menu: $suppressed"
fi

# The shared interpreter keeps platformTextMenuModifier defaulting to Modifier.
grep -Fq 'var platformTextMenuModifier: Modifier = Modifier' "$shared/HostTextField.kt" ||
    fail "HostTextField.kt no longer defaults platformTextMenuModifier to Modifier"

# The Linux K/N test guards platformTextMenuModifier, isNewContextMenuEnabled, and clipboard hasText.
linux_test="$linux/test/LinuxTextMenuTest.kt"
grep -Fq 'platformTextMenuModifier' "$linux_test" ||
    fail "LinuxTextMenuTest.kt does not check platformTextMenuModifier"
grep -Fq 'isNewContextMenuEnabled' "$linux_test" ||
    fail "LinuxTextMenuTest.kt does not check ComposeFoundationFlags.isNewContextMenuEnabled"
grep -Fq 'hasText()' "$linux_test" ||
    fail "LinuxTextMenuTest.kt does not check clipboard hasText() availability"

# The GraalVM X11 window uses NativeWindowContent, which wires LocalClipboard / LocalClipboardManager.
graal_x11="$renderer/desktop/src/X11Window.kt"
shell="$renderer/desktop/src/NativeWindowShell.kt"
grep -Fq 'NativeWindowContent(' "$graal_x11" ||
    fail "GraalVM X11Window.kt does not mount NativeWindowContent"
grep -Fq 'LocalClipboard provides WindowClipboard' "$shell" ||
    fail "NativeWindowShell.kt does not provide LocalClipboard"
grep -Fq 'LocalClipboardManager provides WindowClipboardManagerInstance' "$shell" ||
    fail "NativeWindowShell.kt does not provide LocalClipboardManager"

if (( red == 0 )); then
    echo "ok    one right-click menu on the Linux windows, Compose's drawn menu with X11 clipboard wired on both paths"
fi
exit $red
