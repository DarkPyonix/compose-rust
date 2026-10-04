package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.DockIcon
import dev.darkpyonix.composerust.ui.platform.SystemDarkMonitor
import dev.darkpyonix.composerust.ui.platform.TextPasteboard
import dev.darkpyonix.composerust.ui.platform.contentMinimum
import dev.darkpyonix.composerust.ui.platform.copyText
import dev.darkpyonix.composerust.ui.platform.hasText
import dev.darkpyonix.composerust.ui.platform.isDarkAppearanceName
import dev.darkpyonix.composerust.ui.platform.pasteText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions both macOS windows share: the GraalVM window and the Kotlin/Native window
 * call these and make only the system calls themselves, so one set of tests covers both.
 */
class WindowParityTest {

    private class FakePasteboard(var text: String? = null) : TextPasteboard {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun fr19_a_window_that_named_a_minimum_gets_it_and_one_that_did_not_gets_none() {
        assertEquals(320.0 to 240.0, contentMinimum(320, 240))
        assertEquals(320.0 to 0.0, contentMinimum(320, 0), "one axis may be named alone")
        assertNull(contentMinimum(0, 0), "zero means the application did not ask")
        assertNull(contentMinimum(-1, -1))
    }

    @Test
    fun fr19_3_the_dock_icon_waits_for_the_asset_and_is_put_on_once() {
        var arrived: String? = null
        val applied = ArrayList<String>()
        val icon = DockIcon(lookup = { arrived }, apply = { applied.add(it) })

        assertFalse(icon.tryApply(0), "no icon named")
        assertFalse(icon.tryApply(7), "the asset has not arrived")
        assertTrue(applied.isEmpty())

        arrived = "picture"
        assertTrue(icon.tryApply(7))
        assertTrue(icon.tryApply(7))
        assertEquals(listOf("picture"), applied, "applied once however often it is asked")
        assertTrue(icon.applied)
    }

    @Test
    fun fr14_4_the_dark_family_of_appearance_names_reads_as_dark() {
        assertTrue(isDarkAppearanceName("NSAppearanceNameDarkAqua"))
        assertTrue(isDarkAppearanceName("NSAppearanceNameVibrantDark"))
        assertFalse(isDarkAppearanceName("NSAppearanceNameAqua"))
        assertFalse(isDarkAppearanceName(null))
    }

    @Test
    fun fr14_4_a_change_after_the_window_opened_is_published_and_asks_for_a_frame() {
        var system = false
        var frames = 0
        val monitor = SystemDarkMonitor(read = { system }, requestFrame = { frames++ })
        assertFalse(monitor.dark.value)
        system = true
        monitor.refresh()
        assertTrue(monitor.dark.value, "the window stayed light after the system went dark")
        assertEquals(1, frames)
        system = false
        monitor.refresh()
        assertFalse(monitor.dark.value)
        assertEquals(2, frames)
    }

    @Test
    fun fr14_4_a_refresh_that_changes_nothing_costs_no_frame() {
        var frames = 0
        val monitor = SystemDarkMonitor(read = { true }, requestFrame = { frames++ })
        assertTrue(monitor.dark.value, "the first answer is the system's own")
        monitor.refresh()
        monitor.refresh()
        assertEquals(0, frames)
    }

    @Test
    fun fr5_a_copy_reaches_the_pasteboard_and_a_paste_reads_it_back() {
        val board = FakePasteboard()
        board.copyText("안녕 hello")
        assertEquals("안녕 hello", board.text)
        assertEquals("안녕 hello", board.pasteText())
        assertTrue(board.hasText())
    }

    @Test
    fun fr5_an_empty_pasteboard_pastes_nothing_and_greys_out_paste() {
        assertNull(FakePasteboard().pasteText())
        assertNull(FakePasteboard("").pasteText(), "an empty string is not a paste")
        assertFalse(FakePasteboard().hasText())
        assertFalse(FakePasteboard(null).apply { copyText(null) }.hasText(), "null clears")
    }
}
