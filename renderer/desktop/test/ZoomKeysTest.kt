package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.runtime.ZoomShortcut
import dev.darkpyonix.composerust.ui.platform.ConfigLayout
import dev.darkpyonix.composerust.ui.platform.ZoomLevelFile
import dev.darkpyonix.composerust.ui.platform.applicationIdentity
import dev.darkpyonix.composerust.ui.platform.macZoomShortcut
import dev.darkpyonix.composerust.ui.platform.win32ZoomShortcut
import dev.darkpyonix.composerust.ui.platform.x11ZoomShortcut
import dev.darkpyonix.composerust.ui.platform.zoomLevelPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The keys that zoom a window on each desktop, and where the level is kept between runs.
 *
 * Each desktop records a key press its own way, so each reading is driven here with the
 * record that desktop's window makes.
 */
class ZoomKeysTest {

    @Test
    fun fr43_command_with_plus_minus_and_zero_zoom_on_macos() {
        assertEquals(ZoomShortcut.In, macZoomShortcut('='.code, 0x18, COMMAND))
        assertEquals(ZoomShortcut.In, macZoomShortcut('+'.code, 0x18, COMMAND or SHIFT))
        assertEquals(ZoomShortcut.Out, macZoomShortcut('-'.code, 0x1B, COMMAND))
        assertEquals(ZoomShortcut.Reset, macZoomShortcut('0'.code, 0x1D, COMMAND))
        // A press that carried no character is read from where the key is.
        assertEquals(ZoomShortcut.In, macZoomShortcut(0, 0x45, COMMAND))
        assertEquals(ZoomShortcut.Out, macZoomShortcut(0, 0x4E, COMMAND))
        assertEquals(ZoomShortcut.Reset, macZoomShortcut(0, 0x52, COMMAND))

        assertNull(macZoomShortcut('='.code, 0x18, 0L), "typing an equals sign is text")
        assertNull(macZoomShortcut('='.code, 0x18, CONTROL), "Control is not the macOS zoom key")
        assertNull(macZoomShortcut('='.code, 0x18, COMMAND or OPTION))
        assertNull(macZoomShortcut('c'.code, 0x08, COMMAND), "copy is not a zoom")
    }

    @Test
    fun fr43_control_with_plus_minus_and_zero_zoom_on_linux() {
        assertEquals(ZoomShortcut.In, x11ZoomShortcut('='.code, CONTROL))
        assertEquals(ZoomShortcut.In, x11ZoomShortcut('+'.code, CONTROL or SHIFT))
        assertEquals(ZoomShortcut.Out, x11ZoomShortcut('-'.code, CONTROL))
        assertEquals(ZoomShortcut.Reset, x11ZoomShortcut('0'.code, CONTROL))

        assertNull(x11ZoomShortcut('='.code, COMMAND), "the logo key is not the zoom key here")
        assertNull(x11ZoomShortcut('='.code, CONTROL or OPTION))
        assertNull(x11ZoomShortcut('='.code, 0L))
    }

    @Test
    fun fr43_control_with_plus_minus_and_zero_zoom_on_windows() {
        // The Win32 window records the character the key carries unshifted, and its own
        // modifier word: 1 Shift, 2 Control, 4 Alt, 8 the Windows key.
        assertEquals(ZoomShortcut.In, win32ZoomShortcut('='.code, 2))
        assertEquals(ZoomShortcut.In, win32ZoomShortcut('+'.code, 2), "the keypad's plus")
        assertEquals(ZoomShortcut.Out, win32ZoomShortcut('-'.code, 2 or 1))
        assertEquals(ZoomShortcut.Reset, win32ZoomShortcut('0'.code, 2))

        assertNull(win32ZoomShortcut('='.code, 0))
        assertNull(win32ZoomShortcut('='.code, 2 or 4), "Control and Alt is something else")
        assertNull(win32ZoomShortcut('='.code, 8))
    }

    @Test
    fun fr43_the_level_is_kept_in_the_application_configuration_directory() {
        val environment = mapOf(
            "APPDATA" to "C:\\Users\\reader\\AppData\\Roaming",
            "HOME" to "/home/reader",
        )
        assertEquals(
            "C:\\Users\\reader\\AppData\\Roaming\\calculator\\zoom-level",
            zoomLevelPath(ConfigLayout.Windows, "calculator", environment::get),
        )
        assertEquals(
            "/home/reader/.config/calculator/zoom-level",
            zoomLevelPath(ConfigLayout.Linux, "calculator", environment::get),
        )
        val xdg = environment + ("XDG_CONFIG_HOME" to "/srv/config")
        assertEquals(
            "/srv/config/calculator/zoom-level",
            zoomLevelPath(ConfigLayout.Linux, "calculator", xdg::get),
        )
        assertNull(zoomLevelPath(ConfigLayout.Windows, "calculator", emptyMap<String, String>()::get))

        assertEquals("calculator", applicationIdentity("C:\\Apps\\calculator.exe"))
        assertEquals("calculator", applicationIdentity("/usr/bin/calculator"))
        assertEquals("compose-rust", applicationIdentity(null))
        assertEquals("compose-rust", applicationIdentity("/usr/bin/.."))
    }

    @Test
    fun fr43_app_zoom_steps_and_persists_in_a_file() {
        val files = mutableMapOf<String, String>()
        val store = ZoomLevelFile(
            "/home/reader/.config/calculator/zoom-level",
            read = { files[it] },
            write = { path, text ->
                files[path] = text
                true
            },
        )
        assertNull(store.load(), "nothing saved yet")
        store.save(2)
        assertEquals("2", files["/home/reader/.config/calculator/zoom-level"])
        assertEquals(2, store.load())

        files["/home/reader/.config/calculator/zoom-level"] = " -3\n"
        assertEquals(-3, store.load())
        files["/home/reader/.config/calculator/zoom-level"] = "99"
        assertEquals(8, store.load(), "a level out of range is held to the range")
        files["/home/reader/.config/calculator/zoom-level"] = "large"
        assertNull(store.load(), "a damaged file is no level")
    }

    private companion object {
        const val SHIFT = 1L shl 17
        const val CONTROL = 1L shl 18
        const val OPTION = 1L shl 19
        const val COMMAND = 1L shl 20
    }
}
