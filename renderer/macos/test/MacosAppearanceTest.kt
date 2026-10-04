package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.SystemDarkMonitor
import dev.darkpyonix.composerust.ui.platform.isDarkAppearanceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window follows the system's light and dark setting while it is open. */
class MacosAppearanceTest {

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
        var notify: () -> Unit = {}
        var frames = 0
        val monitor = SystemDarkMonitor(
            read = { system },
            subscribe = { notify = it },
            requestFrame = { frames++ },
        )
        assertFalse(monitor.dark.value)
        system = true
        notify()
        assertTrue(monitor.dark.value, "the window stayed light after the system went dark")
        assertEquals(1, frames)
        system = false
        notify()
        assertFalse(monitor.dark.value)
        assertEquals(2, frames)
    }

    @Test
    fun fr14_4_a_notification_that_changes_nothing_costs_no_frame() {
        var notify: () -> Unit = {}
        var frames = 0
        SystemDarkMonitor(read = { true }, subscribe = { notify = it }, requestFrame = { frames++ })
        notify()
        notify()
        assertEquals(0, frames)
    }

    @Test
    fun fr14_4_the_first_answer_is_the_systems_own() {
        assertTrue(SystemDarkMonitor({ true }, {}, {}).dark.value)
    }
}
