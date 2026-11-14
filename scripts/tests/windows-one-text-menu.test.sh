#!/usr/bin/env bash
# The Windows windows put up one right-click menu on a text field, and it is the system's.
#
# The two Windows paths get there differently, because their Compose differs.
#
# The native image runs Compose for the JVM, whose menus are drawn through
# LocalContextMenuRepresentation. The renderer answers it with the system's popup
# (dxc_native_context_menu), as the AppKit window does, and an application that provides its
# own representation replaces it. The fork's window has an edit menu of its own as well
# (setEditMenu); armed beside the representation it would be a second menu, so it stays off.
#
# The Kotlin/Native renderer runs Compose for mingwX64, which draws a field's menu itself and
# has no representation to hand it to the system through. There the window's own edit menu is
# the system's menu (Win32EditMenu, dxc_native_set_edit_menu), on while a field has focus, and
# every text field keeps Compose's menu shut while it is on (platformTextMenuModifier). A press
# the application consumed for a menu of its own turns the window's off for that press
# (platformContextMenuTaken).
#
# None of it can be run from here. This checks that the pieces point at each other; the
# behaviour is checked by the JVM tests (SystemEditMenuTest, PointerEventsTest) and on a
# Windows runner by the fr33_1_windows_* checks that check-windows-consumer.sh runs.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
renderer="$repo_root/renderer"
windows="$renderer/windows/src"
shared="$renderer/desktop/src/renderer"
red=0
fail() {
    echo "fail: $1"
    red=1
}

# The Kotlin/Native renderer.
grep -Fq '@SymbolName("dxc_native_set_edit_menu")' "$windows/Win32EditMenu.kt" ||
    fail "Win32EditMenu.kt does not reach the window's edit menu by name"
grep -Fq 'win32EditMenuFlags(' "$windows/Win32EditMenu.kt" ||
    fail "Win32EditMenu.kt builds the window's flags itself rather than with the fork's win32EditMenuFlags"
grep -Fq 'Win32EditMenu.install()' "$windows/WindowsRenderer.kt" ||
    fail "the Windows renderer never installs the edit menu's hooks, so Compose's menu comes up beside the window's"
grep -Fq 'SystemEditMenuGate(Win32EditMenu.systemMenu)' "$windows/Win32Window.kt" ||
    fail "a Win32Window does not decide its edit menu with SystemEditMenuGate"
grep -Fq 'Win32EditMenu.current = editMenu' "$windows/Win32Window.kt" ||
    fail "a Win32Window does not say whose menu the events it hands the scene are for"
link="$windows/shared/SystemEditMenu.kt"
[[ -L "$link" && -e "$link" ]] ||
    fail "windows/src/shared/SystemEditMenu.kt is not a working link to the desktop renderer's copy"

# The shared interpreter: every text field takes the hook, and a consumed menu press is told.
grep -Fq '.then(platformTextMenuModifier)' "$shared/HostTextField.kt" ||
    fail "HostTextField does not take platformTextMenuModifier, so its Compose menu opens beside the window's"
grep -Fq 'platformTextMenuModifier' "$shared/CodeEditorSurface.kt" ||
    fail "the code editor's field does not take platformTextMenuModifier"
grep -Fq 'if (menu) platformContextMenuTaken()' "$shared/PointerEvents.kt" ||
    fail "a menu press the application consumed is not told to the platform"

# The native image.
graal="$renderer/desktop/src/Win32Window.kt"
grep -Fq 'LocalContextMenuRepresentation provides menu' "$graal" ||
    fail "the GraalVM Windows window does not give Compose's menus a representation"
grep -Fq 'NativeContextMenuRepresentation {' "$graal" ||
    fail "the GraalVM Windows window's menu is not the system's"
grep -Fq '@CFunction("dxc_native_context_menu")' "$graal" ||
    fail "the GraalVM Windows window does not reach the system's popup"
if grep -Eq '\bsetEditMenu\b|dxc_native_set_edit_menu' "$graal"; then
    fail "the GraalVM Windows window arms the window's edit menu beside Compose's representation: two menus"
fi

# The checks on a Windows runner are read back by the script that runs them.
grep -Fq 'fr33_1_windows' "$repo_root/scripts/check-windows-consumer.sh" ||
    fail "check-windows-consumer.sh does not read back the fr33_1_windows checks"
for name in fr33_1_windows_focused_field_turns_the_system_edit_menu_on \
    fr33_1_windows_drawn_menu_leaves_the_system_edit_menu_off; do
    grep -Fq "\"$name\"" "$windows/Fr53WindowsCheck.kt" || fail "no Windows check named $name"
done

if (( red == 0 )); then
    echo "ok    one right-click menu on the Windows windows, the system's, wired on both paths"
fi
exit $red
