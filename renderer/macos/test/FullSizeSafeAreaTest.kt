@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowZoomButton
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real macOS window, the one `Chrome::FullSize` draws in. Its content view covers the whole
 * window, title bar included, so content laid out from the top starts at y = 0 under the
 * traffic lights; and the caption the renderer turns into `WindowSafeArea` says how wide the
 * traffic lights are, as AppKit itself places them: `leading` is the zoom button's right edge
 * plus the close button's inset from the left, and `top` is the title bar's height.
 */
class FullSizeSafeAreaTest {
    @Test
    fun fr19_9_content_starts_at_the_top_and_leading_is_the_traffic_lights() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "full size", width = 640, height = 400)
        window.initWindow()
        try {
            val ns = window.window
            // The caption is measured once the title bar is laid out, a few times after the
            // window is shown, so give the run loop that long.
            repeat(20) {
                if (window.caption.value.buttonsWidth.value > 0f) return@repeat
                NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05))
            }
            assertTrue(ns.styleMask and NSWindowStyleMaskFullSizeContentView != 0uL, "the content view reaches under the title bar")
            val frameHeight = ns.frame.useContents { size.height }
            val contentHeight = ns.contentView!!.frame.useContents { size.height }
            assertEquals(frameHeight, contentHeight, "content starts at y = 0, not below the title bar")

            val close = ns.standardWindowButton(NSWindowCloseButton)!!.frame.useContents { origin.x }
            val zoom = ns.standardWindowButton(NSWindowZoomButton)!!.frame.useContents { origin.x + size.width }
            val caption = window.caption.value
            assertTrue(caption.buttonsAtStart, "the traffic lights are at the leading edge")
            assertTrue(
                abs(caption.buttonsWidth.value - (zoom + close).toFloat()) < 0.5f,
                "leading is the traffic lights' width: ${caption.buttonsWidth} vs ${zoom + close}",
            )
            val layoutHeight = ns.contentLayoutRect.useContents { size.height }
            assertTrue(
                abs(caption.height.value - (frameHeight - layoutHeight).toFloat()) < 0.5f && caption.height.value > 0f,
                "top is the title bar's height: ${caption.height} vs ${frameHeight - layoutHeight}",
            )
        } finally {
            window.window.close()
        }
    }
}
