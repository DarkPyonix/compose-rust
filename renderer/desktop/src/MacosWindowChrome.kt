package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.runtime.WindowCaption

/**
 * How a macOS window is built, decided once for both macOS renderers.
 *
 * The native image's window (`appkit_window.m`) and the Kotlin/Native one (`MacosWindow`)
 * both read this rather than each writing down their own style mask and title bar. They
 * used to disagree: one had a unified toolbar and the other did not, and on macOS 26 a
 * window with a toolbar has a larger corner radius and a taller title bar, so the same
 * application came up with different corners and its content at a different height.
 *
 * The window's corner is the system's in every case. AppKit has no public way to set a
 * window's radius, and a radius drawn by clipping the content stands inside the system's
 * own outline and shadow, which is what made one window look rounder than the other.
 * The ordinary mode's larger radius and its buttons set further in are what a unified
 * toolbar gives a window, so that mode asks for one; the simple mode does not.
 */
data class MacosWindowChrome(
    /** The content runs under the title bar rather than starting below it. */
    val fullSizeContentView: Boolean,
    /** The title bar draws nothing of its own, so the content shows through it. */
    val titlebarAppearsTransparent: Boolean,
    /** The title is carried, for the switcher and Mission Control, but not drawn. */
    val titleHidden: Boolean,
    /** An empty unified toolbar, which sets the bar's height and the window's radius. */
    val unifiedToolbar: Boolean,
) {
    companion object {
        /** The window [chrome] and [titleBar] ask for. */
        fun of(chrome: Chrome, titleBar: TitleBar): MacosWindowChrome =
            if (chrome == Chrome.System) {
                MacosWindowChrome(
                    fullSizeContentView = false,
                    titlebarAppearsTransparent = false,
                    titleHidden = false,
                    unifiedToolbar = false,
                )
            } else {
                MacosWindowChrome(
                    fullSizeContentView = true,
                    titlebarAppearsTransparent = true,
                    titleHidden = true,
                    unifiedToolbar = titleBar == TitleBar.Normal,
                )
            }
    }
}

/**
 * The strip the title bar takes and the room its buttons take, from what the window
 * reports, in points.
 *
 * Both macOS windows measure the same four numbers and hand them here, so the content
 * starts at the same height in both. [windowHeight] is the window's frame,
 * [contentLayoutHeight] the part of it below the bar, [closeMinX] where the close button
 * starts and [zoomMaxX] where the zoom button ends, or null for a window with no buttons.
 * The gap in front of the first button is mirrored after the last.
 *
 * Null while the window is changing size: the frame and the layout rect are updated at
 * different moments and the difference can be negative for an instant. The last reading
 * stands until there is a real one. A window that kept the system's title bar has nothing
 * running under it, so its caption is empty.
 */
fun macosWindowCaption(
    chrome: MacosWindowChrome,
    windowHeight: Double,
    contentLayoutHeight: Double,
    closeMinX: Double?,
    zoomMaxX: Double?,
): WindowCaption? {
    if (!chrome.fullSizeContentView) return WindowCaption.None
    val height = windowHeight - contentLayoutHeight
    if (height < 0.0) return null
    val width = if (closeMinX == null || zoomMaxX == null) 0.0 else zoomMaxX + closeMinX
    if (width < 0.0) return null
    return WindowCaption(
        height = height.toFloat().dp,
        buttonsWidth = width.toFloat().dp,
        // The platform's own, and this platform puts them at the leading edge.
        buttonsAtStart = true,
    )
}
