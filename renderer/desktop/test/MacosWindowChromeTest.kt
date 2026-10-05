package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.ui.platform.MacosWindowChrome
import dev.darkpyonix.composerust.ui.platform.macosWindowCaption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one description of a macOS window that both macOS renderers build from.
 *
 * The native image's window and the Kotlin/Native one each wrote down their own title bar
 * and they disagreed: one had a unified toolbar and the other a corner clipped by hand, so
 * the same application came up with rounder corners and its content higher in one of them.
 * Both now take these answers, and the caption they lay the content out around comes from
 * the same function given the same measurements.
 */
class MacosWindowChromeTest {

    @Test
    fun fr19_the_ordinary_window_runs_the_content_under_a_unified_toolbar() {
        val chrome = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        assertTrue(chrome.fullSizeContentView)
        assertTrue(chrome.titlebarAppearsTransparent)
        assertTrue(chrome.titleHidden, "the title is carried but not drawn")
        assertTrue(chrome.unifiedToolbar, "the toolbar gives the bar its height and the window its radius")
    }

    @Test
    fun fr19_7_the_simple_window_keeps_the_plain_bar_height_and_radius() {
        val normal = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        val simple = MacosWindowChrome.of(Chrome.Modern, TitleBar.Simple)
        assertFalse(simple.unifiedToolbar)
        assertTrue(simple.fullSizeContentView)
        assertTrue(normal != simple, "the two modes build different windows")
    }

    @Test
    fun fr19_4_system_chrome_keeps_the_platform_title_bar_in_either_mode() {
        for (mode in TitleBar.entries) {
            val chrome = MacosWindowChrome.of(Chrome.System, mode)
            assertEquals(MacosWindowChrome(false, false, false, false), chrome, "at $mode")
        }
    }

    @Test
    fun fr19_2_the_caption_is_the_strip_above_the_layout_rect_and_the_buttons_room() {
        val chrome = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        val caption = macosWindowCaption(
            chrome,
            windowHeight = 700.0,
            contentLayoutHeight = 648.0,
            closeMinX = 20.0,
            zoomMaxX = 88.0,
        )
        assertEquals(WindowCaption(height = 52.dp, buttonsWidth = 108.dp, buttonsAtStart = true), caption)
    }

    @Test
    fun fr19_2_a_window_between_sizes_keeps_its_last_caption() {
        val chrome = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        assertNull(macosWindowCaption(chrome, 600.0, 652.0, 20.0, 88.0))
    }

    @Test
    fun fr19_2_a_window_without_buttons_reserves_no_room_for_them() {
        val chrome = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal)
        val caption = macosWindowCaption(chrome, 700.0, 648.0, null, null)
        assertEquals(0.dp, caption?.buttonsWidth)
        assertEquals(52.dp, caption?.height)
    }

    @Test
    fun fr19_4_a_system_title_bar_has_nothing_running_under_it() {
        val chrome = MacosWindowChrome.of(Chrome.System, TitleBar.Normal)
        assertEquals(WindowCaption.None, macosWindowCaption(chrome, 700.0, 672.0, 20.0, 88.0))
    }
}
