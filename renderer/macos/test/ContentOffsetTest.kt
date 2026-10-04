@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the scene is drawn and where a click lands in it are the same place.
 *
 * The window that came up in the comparison build drew its content shifted upwards, so its
 * heading was off the top of the window, and no button or field could be clicked. The view
 * the scene draws into had been made from the window's frame, which is a rectangle on the
 * screen, so it sat inside the window offset by the window's own screen position; and the
 * pointer was turned into scene coordinates by subtracting from the view's height, which
 * knows nothing of that offset. The drawing went one way and the pointer the other.
 *
 * The native image path has neither problem because its view is the content view and is
 * flipped, and a click is converted by AppKit into that view. These check that this path
 * follows the same rules: the view covers the content exactly, from its corner, and a point
 * in the window converts to the scene pixel that is drawn under it.
 */
class ContentOffsetTest {
    private val scale = 2.0

    private fun window(): NSWindow = NSWindow(
        // Somewhere other than the corner of the screen on purpose: a window frame with a
        // zero origin hides exactly the mistake this is here to catch.
        contentRect = NSMakeRect(240.0, 360.0, 480.0, 640.0),
        styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskMiniaturizable or
            NSWindowStyleMaskClosable or NSWindowStyleMaskResizable or
            NSWindowStyleMaskFullSizeContentView,
        backing = NSBackingStoreBuffered,
        defer = true,
    )

    @Test
    fun nfr14_the_scene_view_covers_the_content_from_its_corner() {
        val window = window()
        // Made the way the window used to make it, from the window's frame, to show that
        // what it ends up as does not depend on where it started.
        val backdrop = NSView(window.frame)
        val view = FlippedView(window.frame)
        installContent(window, backdrop, view)

        val content = backdrop.bounds.useContents { listOf(origin.x, origin.y, size.width, size.height) }
        val drawn = view.frame.useContents { listOf(origin.x, origin.y, size.width, size.height) }
        assertEquals(
            content,
            drawn,
            "the view the scene draws into does not cover the window's content exactly, so " +
                "the drawing is shifted off the window by the difference",
        )
    }

    @Test
    fun nfr14_a_click_at_the_centre_of_a_label_lands_on_the_label() {
        val window = window()
        val backdrop = NSView(contentBounds(480, 640))
        val view = FlippedView(contentBounds(480, 640))
        installContent(window, backdrop, view)

        // A heading the scene draws at the top of the window, in pixels: 48 points tall,
        // the full width, starting 20 points down.
        val labelLeft = 16.0 * scale
        val labelTop = 20.0 * scale
        val labelWidth = 300.0 * scale
        val labelHeight = 48.0 * scale
        val centre = Offset(
            (labelLeft + labelWidth / 2).toFloat(),
            (labelTop + labelHeight / 2).toFloat(),
        )

        // The same point as AppKit reports a click there: in the window's coordinates,
        // which count up from the bottom.
        val inWindow = view.convertPoint(
            NSMakePoint(centre.x / scale, centre.y / scale),
            toView = null,
        )
        val windowHeight = window.contentView!!.bounds.useContents { size.height }
        inWindow.useContents {
            assertEquals(
                windowHeight - centre.y / scale,
                y,
                "the scene's top is not the window's top, so the heading is drawn off it",
            )
        }

        assertEquals(
            centre,
            scenePoint(view, inWindow, scale),
            "a click at the centre of the label reaches the scene somewhere else, so the " +
                "control drawn there cannot be pressed",
        )
    }
}

/** Flipped like the window's scene view, which is an anonymous subclass and cannot be made here. */
private class FlippedView(frame: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>) : NSView(frame) {
    override fun isFlipped() = true
}
