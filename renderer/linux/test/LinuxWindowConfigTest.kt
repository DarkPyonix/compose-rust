package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.runtime.WindowCaption
import org.thisisthepy.compose.window.SizeHints
import org.thisisthepy.compose.window.TitleBarStyle
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.X11Chrome
import org.thisisthepy.compose.window.sizeHintsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Kotlin/Native Linux window tells the window manager about its size, chrome,
 * title bar and icon.
 *
 * The window used to be opened from its title and size alone, so a window the application
 * asked to hold still could be dragged to any size, and one with a minimum could be dragged
 * below it. The hints themselves are the X11 layer's to send; what is checked here is that the
 * application's answer reaches the rule that decides them, by the same path the window is
 * opened from.
 */
class LinuxWindowConfigTest {

    private fun asked(
        chrome: Chrome = Chrome.Modern,
        titleBar: TitleBar = TitleBar.Normal,
        icon: Int = 0,
        width: Int = 900,
        height: Int = 640,
        minWidth: Int = 0,
        minHeight: Int = 0,
        resizable: Boolean = true,
    ) = Window(
        chrome = chrome,
        titleBar = titleBar,
        title = "Chat",
        icon = icon,
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
        assertEquals(WindowChrome.Modern, config.chrome)
        assertEquals(TitleBarStyle.Normal, config.titleBar)
    }

    @Test
    fun fr19_7_chrome_and_title_bar_reach_the_linux_window_config() {
        val normalWindow = asked(chrome = Chrome.Modern, titleBar = TitleBar.Normal)
        val normalConfig = linuxWindowConfig(normalWindow)
        assertEquals(WindowChrome.Modern, normalConfig.chrome)
        assertEquals(TitleBarStyle.Normal, normalConfig.titleBar)
        assertFalse(X11Chrome.keepsFrame(normalConfig))
        val normalCaption = linuxWindowCaption(normalWindow)
        assertEquals(46.dp, normalCaption.height)
        assertEquals(138.dp, normalCaption.buttonsWidth)
        assertFalse(normalCaption.buttonsAtStart)
        assertFalse(normalCaption.fullSize)

        val simpleWindow = asked(chrome = Chrome.Modern, titleBar = TitleBar.Simple)
        val simpleConfig = linuxWindowConfig(simpleWindow)
        assertEquals(WindowChrome.Modern, simpleConfig.chrome)
        assertEquals(TitleBarStyle.Simple, simpleConfig.titleBar)
        val simpleCaption = linuxWindowCaption(simpleWindow)
        assertEquals(32.dp, simpleCaption.height)
        assertEquals(96.dp, simpleCaption.buttonsWidth)
        assertFalse(simpleCaption.buttonsAtStart)
        assertFalse(simpleCaption.fullSize)

        assertTrue(normalCaption.height != simpleCaption.height)
        assertEquals(normalCaption, linuxWindowCaption(null))
    }

    @Test
    fun fr19_7_system_chrome_keeps_the_manager_frame_and_drops_scene_caption() {
        val normalSystem = asked(chrome = Chrome.System, titleBar = TitleBar.Normal)
        val simpleSystem = asked(chrome = Chrome.System, titleBar = TitleBar.Simple)
        assertEquals(WindowChrome.System, linuxWindowConfig(normalSystem).chrome)
        assertEquals(WindowChrome.System, linuxWindowConfig(simpleSystem).chrome)
        assertTrue(X11Chrome.keepsFrame(linuxWindowConfig(normalSystem)))
        assertTrue(X11Chrome.keepsFrame(linuxWindowConfig(simpleSystem)))
        assertEquals(WindowCaption.None, linuxWindowCaption(normalSystem))
        assertEquals(WindowCaption.None, linuxWindowCaption(simpleSystem))
    }

    @Test
    fun fr19_9_full_size_chrome_sets_full_size_caption_with_title_bar_height() {
        val normalFull = asked(chrome = Chrome.FullSize, titleBar = TitleBar.Normal)
        val normalConfig = linuxWindowConfig(normalFull)
        assertEquals(WindowChrome.Modern, normalConfig.chrome)
        assertEquals(TitleBarStyle.Normal, normalConfig.titleBar)
        val normalCaption = linuxWindowCaption(normalFull)
        assertTrue(normalCaption.fullSize)
        assertEquals(46.dp, normalCaption.height)
        assertEquals(138.dp, normalCaption.buttonsWidth)
        assertFalse(normalCaption.buttonsAtStart)

        val simpleFull = asked(chrome = Chrome.FullSize, titleBar = TitleBar.Simple)
        val simpleConfig = linuxWindowConfig(simpleFull)
        assertEquals(WindowChrome.Modern, simpleConfig.chrome)
        assertEquals(TitleBarStyle.Simple, simpleConfig.titleBar)
        val simpleCaption = linuxWindowCaption(simpleFull)
        assertTrue(simpleCaption.fullSize)
        assertEquals(32.dp, simpleCaption.height)
        assertEquals(96.dp, simpleCaption.buttonsWidth)
        assertFalse(simpleCaption.buttonsAtStart)

        assertEquals(WindowCaption.None, linuxWindowCaption(normalFull, fullscreen = true))
    }

    @Test
    fun fr19_3_net_wm_icon_encodes_dimensions_and_unpremultiplied_argb() {
        val rgba = byteArrayOf(
            // Opaque red
            0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
            // 50% alpha premultiplied green -> unpremultiplied (0, 255, 0, 128)
            0x00, 0x80.toByte(), 0x00, 0x80.toByte(),
            // Fully transparent
            0x00, 0x00, 0x00, 0x00,
        )
        val encoded = assertNotNull(netWmIconData(rgba, width = 3, height = 1))
        assertEquals(5, encoded.size)
        assertEquals(3uL, encoded[0])
        assertEquals(1uL, encoded[1])
        assertEquals(0xFFFF0000uL, encoded[2])
        assertEquals(0x8000FF00uL, encoded[3])
        assertEquals(0x00000000uL, encoded[4])

        assertNull(netWmIconData(rgba, width = 0, height = 1))
        assertNull(netWmIconData(rgba, width = 3, height = 0))
        assertNull(netWmIconData(rgba, width = 2, height = 2))
    }
}
