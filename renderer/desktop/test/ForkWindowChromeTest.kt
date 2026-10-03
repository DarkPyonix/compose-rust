package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowChrome
import dev.darkpyonix.composerust.ui.platform.applyForkWindowChromeProperties
import dev.darkpyonix.composerust.ui.platform.forkWindowChromeProperties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose fork's window on Windows draws its own caption band unless told otherwise, and
 * this renderer's bar and design system draw that strip already. Two sets of window buttons
 * on one window is the failure these tests keep out.
 */
class ForkWindowChromeTest {

    @Test
    fun fr19_8_modern_chrome_asks_the_fork_for_the_strip_without_its_band() {
        assertEquals(mapOf("compose.windows.caption" to "content"), forkWindowChromeProperties(WindowChrome.Modern))
    }

    @Test
    fun fr19_8_system_chrome_asks_the_fork_for_the_system_caption() {
        assertEquals(mapOf("compose.windows.caption" to "system"), forkWindowChromeProperties(WindowChrome.System))
    }

    @Test
    fun fr19_8_a_property_already_set_is_left_alone() {
        val properties = mutableMapOf("compose.windows.caption" to "system")
        applyForkWindowChromeProperties(WindowChrome.Modern, properties::get) { key, value -> properties[key] = value }
        assertEquals("system", properties["compose.windows.caption"])

        val empty = mutableMapOf<String, String>()
        applyForkWindowChromeProperties(WindowChrome.Modern, empty::get) { key, value -> empty[key] = value }
        assertEquals("content", empty["compose.windows.caption"])
    }
}
