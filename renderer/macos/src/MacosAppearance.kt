@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import platform.AppKit.NSApplication
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.darwin.NSObject

/** The application's effective appearance, read now. */
internal fun systemIsDark(): Boolean {
    val appearance = NSApplication.sharedApplication().effectiveAppearance
    val best = appearance.bestMatchFromAppearancesWithNames(
        listOf(NSAppearanceNameAqua, NSAppearanceNameDarkAqua),
    ) as? String
    return isDarkAppearanceName(best)
}

/**
 * Watches `effectiveAppearance` of the application with key-value observing, which is the
 * one signal that fires for the switch in System Settings, the automatic day and night
 * change, and an appearance the application was given.
 */
internal fun observeSystemAppearance(onChange: () -> Unit) {
    val observer = AppearanceObserver(onChange)
    NSApplication.sharedApplication().addObserver(
        observer,
        forKeyPath = "effectiveAppearance",
        options = NSKeyValueObservingOptionNew,
        context = null,
    )
    // Held for the life of the process: the application does not retain its observers.
    retainedObservers += observer
}

private val retainedObservers = mutableListOf<AppearanceObserver>()

private class AppearanceObserver(private val onChange: () -> Unit) : NSObject() {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: kotlinx.cinterop.COpaquePointer?,
    ) {
        if (keyPath == "effectiveAppearance") onChange()
    }
}
