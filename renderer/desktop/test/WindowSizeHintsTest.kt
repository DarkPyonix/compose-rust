package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.SizeHints
import dev.darkpyonix.composerust.ui.platform.sizeHintsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an X11 window tells its window manager about the sizes it may take. */
class WindowSizeHintsTest {
    @Test
    fun a_window_that_asked_for_nothing_tells_the_manager_nothing() {
        assertNull(sizeHintsFor(0, 0, true, 520, 360))
    }

    @Test
    fun a_smallest_size_is_stated_and_a_largest_is_not() {
        assertEquals(SizeHints(300 to 260, null), sizeHintsFor(300, 260, true, 520, 360))
    }

    @Test
    fun one_axis_may_be_named_without_the_other() {
        assertEquals(SizeHints(300 to 0, null), sizeHintsFor(300, 0, true, 520, 360))
    }

    @Test
    fun a_window_that_cannot_be_resized_is_held_to_the_size_it_opened_at() {
        assertEquals(SizeHints(520 to 360, 520 to 360), sizeHintsFor(300, 260, false, 520, 360))
    }

    @Test
    fun a_smallest_size_is_scaled_to_pixels_and_rounded() {
        assertEquals(SizeHints(451 to 391, null), sizeHintsFor(300, 260, true, 520, 360, scale = 1.5f))
    }
}
