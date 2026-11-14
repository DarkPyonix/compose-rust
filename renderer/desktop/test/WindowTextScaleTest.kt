package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowTextScale
import org.thisisthepy.compose.window.TextScale
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The system's text size as every desktop window hands it to its scene.
 *
 * The Windows windows (Kotlin/Native and the native image) and the native image's X11 window
 * start from what the window module read when the window opened and follow the text size
 * events it queues. What the scene gets is `Density(display scale, fontScale = system)`.
 * Reading the setting itself is the window module's, tested in the Compose fork.
 */
class WindowTextScaleTest {

    @Test
    fun fr43_window_density_carries_the_os_text_size_as_font_scale() {
        val scale = WindowTextScale(1.25f)
        val density = scale.density(2f)
        assertEquals(2f, density.density, "the display's scale is the density")
        assertEquals(1.25f, density.fontScale, "the system's text size is the font scale")
    }

    @Test
    fun fr43_os_text_size_change_reaches_the_window_while_it_is_open() {
        val scale = WindowTextScale(1f)
        assertTrue(scale.receive(WindowEvent.TextScaleChanged(1.5f)), "a new size is a frame to draw")
        assertEquals(1.5f, scale.value)
        assertEquals(1.5f, scale.density(1f).fontScale)
        assertFalse(scale.receive(WindowEvent.TextScaleChanged(1.5f)), "the same size twice is one change")
    }

    @Test
    fun fr43_other_window_events_leave_the_text_size_alone() {
        val scale = WindowTextScale(1.25f)
        val press = WindowEvent(
            kind = WindowEvent.POINTER_DOWN, x = 3f, y = 0f, buttons = 1, modifiers = 0,
            keyCode = 0, codePoint = 0, text = "",
        )
        assertFalse(scale.receive(press))
        assertEquals(1.25f, scale.value)
    }

    @Test
    fun fr43_out_of_range_text_size_is_kept_inside_what_compose_can_draw() {
        assertEquals(TextScale.MAX, WindowTextScale(10f).value)
        val scale = WindowTextScale(Float.NaN)
        assertEquals(TextScale.DEFAULT, scale.value)
        scale.receive(WindowEvent.TextScaleChanged(0.1f))
        assertEquals(TextScale.MIN, scale.value)
    }

    @Test
    fun fr43_windows_text_size_percent_becomes_the_font_scale() {
        // What the native image's Win32 window turns the C record's percent into.
        assertEquals(1.25f, WindowTextScale(TextScale.fromWindowsPercent(125)).value)
        assertEquals(1f, WindowTextScale(TextScale.fromWindowsPercent(0)).value, "an unset slider is 1")
    }
}
