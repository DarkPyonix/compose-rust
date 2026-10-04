@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.Foundation.NSDistributedNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

// The operating system calls only. What a change means, and what is done about it, is
// decided by SystemDarkMonitor in WindowParity.kt.

/** The application's effective appearance, read now. */
internal fun systemIsDark(): Boolean {
    val appearance = NSApplication.sharedApplication().valueForKey("effectiveAppearance") as? NSAppearance
        ?: return false
    val best = appearance.bestMatchFromAppearancesWithNames(
        listOf(NSAppearanceNameAqua, NSAppearanceNameDarkAqua),
    ) as? String
    return isDarkAppearanceName(best)
}

/**
 * Calls [onChange] when the system's light or dark setting changes.
 *
 * Listens for the system-wide interface theme notification, which is sent for the switch
 * in System Settings and for the automatic day and night change. It can arrive before the
 * application's own appearance has caught up, so [onChange] is called again shortly after;
 * the monitor ignores a repeat that changes nothing.
 */
internal fun observeSystemAppearance(onChange: () -> Unit) {
    NSDistributedNotificationCenter.defaultCenter.addObserverForName(
        name = "AppleInterfaceThemeChangedNotification",
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        onChange()
        for (delayMillis in listOf(150L, 750L)) {
            dispatch_after(
                dispatch_time(DISPATCH_TIME_NOW, delayMillis * NSEC_PER_MSEC.toLong()),
                dispatch_get_main_queue(),
            ) { onChange() }
        }
    }
}
