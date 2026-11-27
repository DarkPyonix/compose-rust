package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.BlockingOsMenu
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.forkMenuIconPixels
import dev.darkpyonix.composerust.foundation.forkPopupMenuAnchor
import dev.darkpyonix.composerust.foundation.forkPopupMenuEntries
import org.thisisthepy.compose.window.graalvm.macos.NativeWindow
import org.thisisthepy.compose.window.graalvm.macos.closePopupMenu
import org.thisisthepy.compose.window.graalvm.macos.showPopupMenu
import org.thisisthepy.compose.window.graalvm.windows.Win32GraalWindow

// The GraalVM windows' entry menus (FR-33.2 criterion 6: the same list on both paths), through
// the same C the Kotlin/Native windows call (dxc_native_popup_menu). [BlockingOsMenuPresenter]
// calls [BlockingOsMenu.show] between frames; [scale] is the window's pixels per point, for the
// size the entries' icons are drawn at.

/** Entry menus as `NSMenu` on the GraalVM AppKit window. */
class AppKitBlockingOsMenu(
    private val window: NativeWindow,
    private val scale: () -> Float,
) : BlockingOsMenu {
    override fun show(request: OsMenuRequest): List<Int>? =
        window.showPopupMenu(forkPopupMenuEntries(request, forkMenuIconPixels(scale())), forkPopupMenuAnchor(request))

    override fun close() = closePopupMenu()
}

/** Entry menus as Windows' own (`TrackPopupMenuEx`) on the GraalVM Win32 window. */
class Win32BlockingOsMenu(
    private val window: Win32GraalWindow,
    private val scale: () -> Float,
) : BlockingOsMenu {
    override fun show(request: OsMenuRequest): List<Int>? =
        window.showPopupMenu(forkPopupMenuEntries(request, forkMenuIconPixels(scale())), forkPopupMenuAnchor(request))

    override fun close() = window.closePopupMenu()
}
