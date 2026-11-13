package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowBackingScale
import dev.darkpyonix.composerust.ui.platform.WindowTextScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The native image's macOS window following a change of backing scale, as when it is moved
 * between a 1x and a 2x screen. The window measures itself every frame and on every resize;
 * this is what it does with the measurement.
 */
class WindowBackingScaleTest {

    @Test
    fun fr82_appkit_window_follows_a_backing_scale_change() {
        val follower = WindowBackingScale(1f, WindowTextScale(1f))
        // A 400 by 300 point window on a 1x screen.
        assertNull(follower.follow(400, 300, 1f), "the same scale is not a change")

        // Moved onto a 2x screen: the same points, twice the pixels.
        val change = assertNotNull(follower.follow(800, 600, 2f), "a new scale is a frame to draw")
        assertEquals(2f, change.density.density, "the scene is laid out at the new scale")
        assertEquals(1f, change.density.fontScale, "the system's text size is kept")
        assertEquals(400, (change.size.width / change.density.density).toInt(), "the width in dp is kept")
        assertEquals(300, (change.size.height / change.density.density).toInt(), "the height in dp is kept")
        assertEquals(2f, follower.scale)

        // Asked again every frame: nothing more until the scale moves again.
        assertNull(follower.follow(800, 600, 2f), "a scale already followed is not a change")
        val back = assertNotNull(follower.follow(400, 300, 1f), "moving back is a change too")
        assertEquals(1f, back.density.density)
    }

    @Test
    fun fr82_backing_scale_keeps_the_os_text_size_as_font_scale() {
        val change = assertNotNull(WindowBackingScale(1f, WindowTextScale(1.5f)).follow(800, 600, 2f))
        assertEquals(1.5f, change.density.fontScale, "the system's text size survives a scale change")
    }

    @Test
    fun fr82_backing_scale_ignores_a_measurement_with_no_scale() {
        assertNull(WindowBackingScale(2f, WindowTextScale(1f)).follow(0, 0, 0f), "a window with no screen has nothing to say")
    }
}
