package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.ringThickness
import kotlin.test.Test
import kotlin.test.assertEquals

/** A small ring takes a stroke in proportion to its size, a large one the system's. */
class SpinnerStrokeTest {
    @Test
    fun fr_15_a_14_dp_ring_is_drawn_with_a_thin_stroke() {
        val stroke = ringThickness(4.dp, 14.dp)
        assertEquals(2.1f, stroke.value, 0.001f)
    }

    @Test
    fun fr_15_a_ring_that_named_no_size_keeps_the_systems_stroke() {
        assertEquals(4.dp, ringThickness(4.dp, null))
    }

    @Test
    fun fr_15_a_large_ring_keeps_the_systems_stroke() {
        assertEquals(4.dp, ringThickness(4.dp, 80.dp))
    }
}
