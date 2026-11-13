package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.Theme

/**
 * The code a macOS window's appearance is set with through the fork's native image window:
 * one is light (Aqua), two is dark (DarkAqua), zero leaves the window on the application's
 * appearance, which follows the system as it changes.
 *
 * The window's appearance is what the material behind it is drawn in. The fork's window
 * backs the scene with an `NSVisualEffectView`, which takes its light or dark from the
 * window and knows nothing of the theme, so a light theme on a Mac set to dark is drawn
 * over a dark material and comes out grey unless the window is told.
 */
internal fun windowAppearanceCode(scheme: ColorScheme): Int = when (scheme) {
    ColorScheme.Light -> 1
    ColorScheme.Dark -> 2
    ColorScheme.FollowSystem -> 0
}

/**
 * Keeps one window on the colour scheme the application's theme resolves to.
 *
 * Shared by the macOS windows that have no window manager to read each window's theme
 * after every batch: the Kotlin/Native window and the native image's AppKit window. Each
 * gives [apply] its own way of setting the appearance, and calls [follow] once before the
 * first frame, so the window is not drawn in the wrong material and corrected a moment
 * later, and again whenever the theme changes. A theme that names no scheme, or no theme
 * at all, is the system's.
 *
 * [apply] is only called when the scheme actually changed: setting the appearance makes
 * AppKit redraw everything in the window.
 */
internal class WindowSchemeFollower(private val apply: (ColorScheme) -> Unit) {
    private var applied: ColorScheme? = null

    /** The scheme the window was last given, or null before the first theme. */
    val scheme: ColorScheme? get() = applied

    fun follow(theme: Theme?) {
        val scheme = theme?.colorScheme ?: ColorScheme.FollowSystem
        if (scheme == applied) return
        applied = scheme
        apply(scheme)
    }
}
