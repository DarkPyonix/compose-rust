@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar
import kotlinx.cinterop.useContents
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowToolbarStyleUnified
import platform.AppKit.NSWindowZoomButton
import platform.Foundation.NSMakeRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin/Native window builds its title bar from the same description the native
 * image's window is given, and measures its caption with the same function.
 *
 * The two used to differ: this one had no toolbar and clipped its corners by hand, so on
 * macOS 26 it came up rounder than the other and with its content starting higher.
 */
class MacosWindowChromeTest {

    private fun window(chrome: MacosWindowChrome): NSWindow {
        val window = NSWindow(
            contentRect = NSMakeRect(240.0, 360.0, 480.0, 640.0),
            styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskMiniaturizable or
                NSWindowStyleMaskClosable or NSWindowStyleMaskResizable or
                (if (chrome.fullSizeContentView) NSWindowStyleMaskFullSizeContentView else 0uL),
            backing = NSBackingStoreBuffered,
            defer = true,
        )
        applyChrome(window, chrome)
        return window
    }

    private fun caption(window: NSWindow, chrome: MacosWindowChrome) = macosWindowCaption(
        chrome = chrome,
        windowHeight = window.frame.useContents { size.height },
        contentLayoutHeight = window.contentLayoutRect.useContents { size.height },
        closeMinX = window.standardWindowButton(NSWindowCloseButton)?.frame?.useContents { origin.x },
        zoomMaxX = window.standardWindowButton(NSWindowZoomButton)?.frame?.useContents {
            origin.x + size.width
        },
    )

    @Test
    fun fr19_the_ordinary_window_has_the_unified_toolbar_the_native_image_window_has() {
        val chrome = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        val window = window(chrome)
        val toolbar = assertNotNull(window.toolbar, "the window is built with a toolbar")
        assertEquals(false, toolbar.showsBaselineSeparator)
        assertEquals(NSWindowToolbarStyleUnified, window.toolbarStyle)
        assertTrue(window.titlebarAppearsTransparent)
        assertEquals(NSWindowTitleHidden, window.titleVisibility)
        assertTrue(window.styleMask and NSWindowStyleMaskFullSizeContentView != 0uL)
    }

    @Test
    fun fr19_7_the_simple_window_has_no_toolbar() {
        val window = window(MacosWindowChrome.of(Chrome.Modern, TitleBar.Simple))
        assertNull(window.toolbar)
    }

    @Test
    fun fr19_2_the_toolbar_window_reserves_the_taller_strip_for_its_content() {
        val normal = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        val simple = MacosWindowChrome.of(Chrome.Modern, TitleBar.Simple)
        val tall = assertNotNull(caption(window(normal), normal))
        val plain = assertNotNull(caption(window(simple), simple))
        assertTrue(plain.height.value > 0f, "the content steps clear of the plain bar too")
        assertTrue(
            tall.height > plain.height,
            "a unified toolbar makes the bar taller (${tall.height} against ${plain.height})",
        )
        assertTrue(tall.buttonsWidth.value > 0f)
    }
}
