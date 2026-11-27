package dev.darkpyonix.composerust.foundation

import org.thisisthepy.compose.window.macos.MacosPopupMenu
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuDidBeginTrackingNotification
import platform.AppKit.NSWindow
import platform.Foundation.NSNotificationCenter

/**
 * Entry menus as `NSMenu` on the Kotlin/Native macOS window, through the Compose fork's
 * [MacosPopupMenu] (`popUpMenuPositioningItem` in the window's content view). [show] runs the
 * menu's tracking loop; [BlockingOsMenuPresenter] calls it between frames.
 *
 * [window] is the backend's `nativeWindow`. The fork keeps the menu it built to itself, so the
 * menu that begins tracking while [show] runs is noted from `NSMenuDidBeginTrackingNotification`
 * (the root, the first to begin), and [close] cancels its tracking.
 */
class MacosBlockingOsMenu(private val window: () -> NSWindow?) : BlockingOsMenu {

    private val popup = MacosPopupMenu { window()?.contentView }
    private var tracking: NSMenu? = null

    override fun show(request: OsMenuRequest): List<Int>? {
        val scale = (window()?.backingScaleFactor ?: 1.0).toFloat()
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(NSMenuDidBeginTrackingNotification, null, null) { note ->
            if (tracking == null) tracking = note?.`object` as? NSMenu
        }
        try {
            return popup.show(forkPopupMenuEntries(request, forkMenuIconPixels(scale)), forkPopupMenuAnchor(request))
        } finally {
            center.removeObserver(observer)
            tracking = null
        }
    }

    override fun close() {
        tracking?.cancelTracking()
    }
}
