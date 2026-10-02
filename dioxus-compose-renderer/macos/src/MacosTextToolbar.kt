@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dioxus.compose.ui.platform

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlinx.cinterop.useContents
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSView
import platform.CoreGraphics.CGPointMake

/**
 * What a selection offers when it is asked.
 *
 * The system draws this, rather than Compose drawing a strip of its own. A menu that came
 * from the platform is the one the reader already knows: it takes the same keys, it reads
 * to a screen reader, and it looks like every other menu on the machine.
 *
 * Which items appear is Compose's decision and is made afresh each time, because what a
 * selection can do changes: there is nothing to copy without a selection, and nothing to
 * paste without a clipboard.
 */
internal class MacosTextToolbar(private val view: () -> NSView) : TextToolbar {

    private var shown = false

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val menu = NSMenu()
        // In the order every other application on this platform puts them.
        add(menu, "Cut", onCutRequested)
        add(menu, "Copy", onCopyRequested)
        add(menu, "Paste", onPasteRequested)
        if (onSelectAllRequested != null) {
            menu.addItem(NSMenuItem.separatorItem())
            add(menu, "Select All", onSelectAllRequested)
        }
        if (menu.numberOfItems.toInt() == 0) return

        val host = view()
        // The rectangle is the selection's, in the scene's coordinates, which count down
        // from the top; the view's count up from the bottom, so the menu goes at the
        // bottom edge of the selection turned over.
        val where = host.frame.useContents {
            CGPointMake(rect.left.toDouble(), size.height - rect.bottom.toDouble())
        }
        shown = true
        status = TextToolbarStatus.Shown
        menu.popUpMenuPositioningItem(null, where, host)
        shown = false
        status = TextToolbarStatus.Hidden
    }

    override fun hide() {
        // The system takes the menu down itself, on the click that chose an item or the
        // one that went elsewhere. There is nothing left to take down by the time anything
        // here would ask.
        status = TextToolbarStatus.Hidden
    }

    private fun add(menu: NSMenu, title: String, action: (() -> Unit)?) {
        if (action == null) return
        val item = NSMenuItem()
        item.setTitle(title)
        item.setTarget(MenuAction(action))
        item.setAction(platform.darwin.sel_registerName("perform"))
        menu.addItem(item)
    }
}

/** Holds the closure a menu item runs, because a menu item calls a selector on a target. */
private class MenuAction(private val run: () -> Unit) : platform.darwin.NSObject() {
    @kotlinx.cinterop.ObjCAction
    fun perform() = run()
}
