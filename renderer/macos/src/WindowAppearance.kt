package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.Theme
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSWindow

/**
 * The appearance a window is given for a colour scheme, or null for the system's own.
 *
 * The window's appearance is what the material behind it is drawn in. The fork's window
 * backs everything with an `NSVisualEffectView`, and that view takes its light or dark
 * from the window, not from anything Compose draws. Left to the system, a light theme on a
 * Mac set to dark has its translucent chrome laid over a dark material, and the page, the
 * title strip and the sidebar all come out grey instead of near white. Null leaves the
 * window on the application's appearance, which follows the system as it changes.
 */
internal fun windowAppearanceFor(scheme: ColorScheme): NSAppearance? = when (scheme) {
    ColorScheme.Light -> NSAppearance.appearanceNamed(NSAppearanceNameAqua)
    ColorScheme.Dark -> NSAppearance.appearanceNamed(NSAppearanceNameDarkAqua)
    ColorScheme.FollowSystem -> null
}

/**
 * Keeps one window's appearance on the colour scheme the application's theme resolves to.
 *
 * The several-window path has this from the window manager, which reads each window's
 * theme after every batch. The single window has no manager, so it is told here, through
 * the [WindowSchemeFollower] the native image's AppKit window uses as well: once before
 * the first frame and again whenever the theme changes.
 *
 * [apply] is the window's, and is only called when the scheme actually changed: setting
 * the appearance makes AppKit redraw everything in the window.
 */
internal class WindowAppearanceFollower(apply: (NSAppearance?) -> Unit) {
    constructor(window: NSWindow) : this({ appearance -> window.appearance = appearance })

    private val follower = WindowSchemeFollower { scheme -> apply(windowAppearanceFor(scheme)) }

    /** The scheme the window was last given, or null before the first theme. */
    val scheme: ColorScheme? get() = follower.scheme

    fun follow(theme: Theme?) = follower.follow(theme)
}
