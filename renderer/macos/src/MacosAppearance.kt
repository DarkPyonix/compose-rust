@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import platform.AppKit.NSApplication
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.darwin.NSObject

/**
 * Whether the system is in dark mode, kept current.
 *
 * Compose's own answer is read once and never looks again, so a window opened in light
 * mode stays light while every other application on screen changes. This reads it again
 * whenever the application's effective appearance changes and writes it into snapshot
 * state, which recomposes whatever read it, and asks [requestFrame] for a frame so that
 * what changed is drawn without waiting for the next input.
 *
 * [read] and [subscribe] are what touch the system; both are parameters so the monitor can
 * be exercised without one.
 */
internal class SystemDarkMonitor(
    private val read: () -> Boolean,
    subscribe: (onChange: () -> Unit) -> Unit,
    private val requestFrame: () -> Unit,
) {
    private val state = mutableStateOf(read())

    /** True while the system is dark. */
    val dark: State<Boolean> get() = state

    init {
        subscribe { refresh() }
    }

    /** Reads the system again, and when the answer moved, publishes it and asks for a frame. */
    fun refresh() {
        val now = read()
        if (now != state.value) {
            state.value = now
            requestFrame()
        }
    }
}

/** True for the appearance names of the dark family: dark Aqua and its vibrant and contrast forms. */
internal fun isDarkAppearanceName(name: String?): Boolean = name?.contains("Dark") == true

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
