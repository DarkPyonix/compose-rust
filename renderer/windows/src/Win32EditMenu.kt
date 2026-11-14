@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.Modifier
import java.lang.System
import kotlinx.cinterop.COpaquePointer
import org.thisisthepy.compose.window.EditMenuState
import org.thisisthepy.compose.window.win32EditMenuFlags

// The right-click menu of a text field on this window: the system's popup, which the Compose
// fork's Win32 window puts up itself (`dxc_native_set_edit_menu` in
// `extended/window/windows-shared/c/win32_window.c`, its entries in `win32_edit_menu.h`).
// Reached by symbol name like the calls in `Win32Calls.kt`.
//
// Compose for this target draws a field's menu itself and has no place to hand it to the
// system, so the window's menu stands in for it and Compose's is kept shut while the window's
// is on (`SystemEditMenu.kt`). One menu comes up for one click either way.

@SymbolName("dxc_native_set_edit_menu")
private external fun nativeSetEditMenu(window: COpaquePointer?, flags: Int)

/**
 * The text edit menu of the windows of this process.
 *
 * [systemMenu] is read when a window opens. True by default: a right click in a focused field
 * puts up the system's menu. `DXC_MENU_OVERRIDE=drawn` turns it off and leaves the menu to
 * Compose, which draws its own: that stands in for an application that draws a menu of its
 * own, for checking that the one menu then is that one. A check sets it directly.
 */
internal object Win32EditMenu {
    var systemMenu: Boolean = System.getenv("DXC_MENU_OVERRIDE") != "drawn"

    /** The menu of the window whose events the scene is being handed now. */
    var current: SystemEditMenuGate? = null

    private var installed = false

    /**
     * Gives the shared interpreter the two hooks the window's menu needs: every text field
     * keeps Compose's menu shut while the window's is on, and a press the application took
     * for a menu of its own turns the window's off for that press. Once per process.
     */
    fun install() {
        if (installed) return
        installed = true
        dev.darkpyonix.composerust.foundation.platformTextMenuModifier =
            Modifier.leaveTextMenuToWindow { current?.armed == true }
        dev.darkpyonix.composerust.foundation.platformContextMenuTaken = { current?.contextMenuTaken() }
    }

    /** The flags the window is told for [field]: 0 for none focused, which turns its menu off. */
    fun flagsFor(field: FocusedTextField?): Int = win32EditMenuFlags(
        field?.let { EditMenuState(hasSelection = it.hasSelection, editable = it.editable, hasText = it.hasText) },
    )

    /** Tells [window] what its menu offers now, [flags] as [flagsFor] gives them. */
    fun set(window: COpaquePointer, flags: Int) = nativeSetEditMenu(window, flags)
}
