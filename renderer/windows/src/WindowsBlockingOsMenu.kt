@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.foundation

import kotlinx.cinterop.COpaquePointer
import org.thisisthepy.compose.window.windows.Win32PopupMenu

/**
 * Entry menus as Windows' own on the Kotlin/Native window, through the Compose fork's
 * [Win32PopupMenu] (`TrackPopupMenuEx` under the anchor). [show] runs the menu's loop;
 * [BlockingOsMenuPresenter] calls it between frames.
 *
 * [window] is the backend window's `handle` (its HWND), [scale] its pixels per point (the
 * window's DPI over 96), for the size the entries' icons are drawn at.
 */
class WindowsBlockingOsMenu(
    private val window: () -> COpaquePointer?,
    private val scale: () -> Float,
) : BlockingOsMenu {

    override fun show(request: OsMenuRequest): List<Int>? =
        Win32PopupMenu.show(window(), forkPopupMenuEntries(request, forkMenuIconPixels(scale())), forkPopupMenuAnchor(request))

    override fun close() = Win32PopupMenu.close()
}
