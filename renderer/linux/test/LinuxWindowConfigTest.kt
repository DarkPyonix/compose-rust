package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.Window
import org.thisisthepy.compose.window.SizeHints
import org.thisisthepy.compose.window.sizeHintsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the Kotlin/Native Linux window tells the window manager about its size.
 *
 * The window used to be opened from its title and size alone, so a window the application
 * asked to hold still could be dragged to any size, and one with a minimum could be dragged
 * below it. The hints themselves are the X11 layer's to send; what is checked here is that the
 * application's answer reaches the rule that decides them, by the same path the window is
 * opened from.
 */
class LinuxWindowConfigTest {

    private fun asked(
        width: Int = 900,
        height: Int = 640,
        minWidth: Int = 0,
        minHeight: Int = 0,
        resizable: Boolean = true,
    ) = Window(
        chrome = Chrome.Modern,
        titleBar = TitleBar.Normal,
        title = "Chat",
        icon = 0,
        width = width,
        height = height,
        minWidth = minWidth,
        minHeight = minHeight,
        resizable = resizable,
    )

    private fun hintsFor(window: Window?): SizeHints? {
        val config = linuxWindowConfig(window)
        return sizeHintsFor(
            config.minWidth,
            config.minHeight,
            config.resizable,
            config.width,
            config.height,
        )
    }

    @Test
    fun fr19_3_a_window_that_may_not_be_resized_is_held_to_its_size() {
        val hints = hintsFor(asked(resizable = false))
        assertEquals(SizeHints(min = 900 to 640, max = 900 to 640), hints)
    }

    @Test
    fun fr19_3_a_minimum_size_reaches_the_window_manager() {
        val hints = hintsFor(asked(minWidth = 480, minHeight = 320))
        assertEquals(SizeHints(min = 480 to 320, max = null), hints)
    }

    @Test
    fun fr19_3_a_window_that_said_nothing_tells_the_manager_nothing() {
        assertNull(hintsFor(null))
        assertNull(hintsFor(asked()))
        val config = linuxWindowConfig(null)
        assertEquals("compose-rust", config.title)
        assertEquals(520 to 360, config.width to config.height)
    }
}
