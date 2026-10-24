package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.SyntheticInput
import dev.darkpyonix.composerust.ui.platform.fixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pieces of the parity run that are plain logic: how a number is printed, which Kotlin/Native
 * has no `format` for, and when the synthetic input does each thing.
 */
class ParityMeasurementTest {
    @Test
    fun a_number_is_printed_with_the_places_asked_for() {
        assertEquals("1.23", fixed(1.2345, 2))
        assertEquals("0.050", fixed(0.05, 3))
        assertEquals("12.00", fixed(12.0, 2))
        assertEquals("0.00", fixed(0.0, 2))
    }

    @Test
    fun a_number_below_zero_is_printed_as_zero() {
        assertEquals("0.00", fixed(-3.0, 2))
    }

    @Test
    fun the_run_exits_only_when_asked_to_and_after_the_resize() {
        val quiet = SyntheticInput("type")
        assertFalse(quiet.exitDue(System.nanoTime() + 60_000_000_000L), "no exit was asked for")

        val measuring = SyntheticInput("resize,exit")
        val later = System.nanoTime() + 60_000_000_000L
        assertFalse(measuring.exitDue(later), "the resize has not happened yet")
        assertTrue(measuring.resizeDue(later))
        assertTrue(measuring.exitDue(later), "the resize is done and the time has come")
        assertFalse(measuring.resizeDue(later), "a resize happens once")
    }
}
