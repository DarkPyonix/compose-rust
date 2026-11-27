package dev.darkpyonix.composerust.foundation

import org.thisisthepy.compose.window.macos.MacosPopupMenu
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuDidBeginTrackingNotification
import platform.AppKit.NSWindow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer

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

/**
 * One window's entry menus on the Kotlin/Native macOS window, and the place they go up from.
 *
 * `NSApplication.run()` owns this loop. A window draws from AppKit's display cycle and from the
 * renderer's turn timer, and neither is a place to run a menu's tracking loop: the first is
 * inside drawing, and the second would hold every window's turns for as long as the menu is up,
 * because a repeating timer does not fire again while its own callout runs. So [afterFrame],
 * which the window calls after each frame it drew and after each turn, only arms a one-shot
 * timer, and the menu goes up from that timer once the frame or turn has returned. The timer is
 * in the common modes, as the turns are, and the turn timer keeps every window turning while
 * the menu tracks.
 *
 * [window] is the window's `NSWindow`, [requestFrame] asks it for a frame, which the result of
 * a menu needs to show.
 */
class MacosOsMenuLoop(private val window: () -> NSWindow?, private val requestFrame: () -> Unit) {

    /** The presenter the window's content is given as `LocalOsMenuPresenter`. */
    val presenter = BlockingOsMenuPresenter(MacosBlockingOsMenu(window))

    private var armed = false
    private var running = false

    /** Called after a frame or a turn: arms the menu waiting, if any, to go up right after. */
    fun afterFrame() {
        if (armed || running || !presenter.waiting) return
        armed = true
        val timer = NSTimer.timerWithTimeInterval(interval = 0.0, repeats = false) { _: NSTimer? ->
            armed = false
            // The window may have closed between the frame and this timer.
            if (window()?.visible != true) {
                presenter.dismissPending()
                return@timerWithTimeInterval
            }
            running = true
            val busy = try {
                runOsMenuAfterFrame(presenter)
            } finally {
                running = false
            }
            if (busy) {
                requestFrame()
                afterFrame()
            }
        }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
    }
}
