package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowActivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A window on its own: an unfocused desktop window flattens glass to its opaque
 * fallback, which it can only do if `isWindowFocused` follows the window's real activation.
 * The Windows single window held `focused = true` for ever; it now starts from whether the
 * window is active and follows every activation the window reports (WM_ACTIVATE).
 */
class WindowActivationTest {

    @Test
    fun fr14_1_5_window_on_its_own_follows_its_activation() {
        val reports = ArrayDeque<Boolean?>()
        val activation = WindowActivation(initial = true) { reports.removeFirstOrNull() }
        assertTrue(activation.focused.value)

        // Another window was activated: this one is told it is no longer the active one.
        reports.addLast(false)
        activation.poll()
        assertFalse(activation.focused.value, "the window still says it is focused after it lost the activation")

        // Nothing new reported: the state stays as it is.
        activation.poll()
        assertFalse(activation.focused.value)

        reports.addLast(true)
        activation.poll()
        assertTrue(activation.focused.value, "the window did not take the activation back")
    }

    @Test
    fun fr14_1_5_window_opened_behind_another_starts_unfocused() {
        val activation = WindowActivation(initial = false) { null }
        assertFalse(activation.focused.value)
    }

    @Test
    fun fr14_1_5_several_reports_in_one_turn_leave_the_last() {
        val reports = ArrayDeque(listOf<Boolean?>(false, true, false))
        val activation = WindowActivation(initial = true) { reports.removeFirstOrNull() }
        activation.poll()
        assertEquals(false, activation.focused.value)
    }
}
