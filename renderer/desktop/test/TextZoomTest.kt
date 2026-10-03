package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.ui.platform.TextScale
import dev.darkpyonix.composerust.ui.platform.TextZoom
import dev.darkpyonix.composerust.ui.platform.ZoomShortcut
import dev.darkpyonix.composerust.ui.platform.macZoomShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The text size control a macOS window offers in place of a system one.
 *
 * macOS publishes no text size an application can read, so the window zooms its own text on
 * Command with plus, minus and zero, and that zoom is the font scale its scene is given.
 */
class TextZoomTest {

    @Test
    fun command_with_plus_minus_and_zero_are_the_zoom_shortcuts() {
        assertEquals(ZoomShortcut.In, macZoomShortcut('='.code, 0x18, COMMAND))
        assertEquals(ZoomShortcut.In, macZoomShortcut('+'.code, 0x18, COMMAND or SHIFT))
        assertEquals(ZoomShortcut.Out, macZoomShortcut('-'.code, 0x1B, COMMAND))
        assertEquals(ZoomShortcut.Reset, macZoomShortcut('0'.code, 0x1D, COMMAND))
        // A press that carried no character is read from where the key is.
        assertEquals(ZoomShortcut.In, macZoomShortcut(0, 0x45, COMMAND))
        assertEquals(ZoomShortcut.Out, macZoomShortcut(0, 0x4E, COMMAND))
        assertEquals(ZoomShortcut.Reset, macZoomShortcut(0, 0x52, COMMAND))
    }

    @Test
    fun the_same_keys_without_command_or_with_more_are_left_alone() {
        assertNull(macZoomShortcut('='.code, 0x18, 0L), "typing an equals sign is text")
        assertNull(macZoomShortcut('='.code, 0x18, COMMAND or CONTROL))
        assertNull(macZoomShortcut('-'.code, 0x1B, COMMAND or OPTION))
        assertNull(macZoomShortcut('c'.code, 0x08, COMMAND), "copy is not a zoom")
    }

    @Test
    fun zoom_steps_in_and_out_and_comes_back() {
        val zoom = TextZoom()

        assertTrue(zoom.apply(ZoomShortcut.In))
        assertEquals(1.1f, zoom.fontScale)
        assertTrue(zoom.apply(ZoomShortcut.In))
        assertEquals(1.25f, zoom.fontScale)
        assertTrue(zoom.apply(ZoomShortcut.Out))
        assertEquals(1.1f, zoom.fontScale)
        assertTrue(zoom.apply(ZoomShortcut.Reset))
        assertEquals(1f, zoom.fontScale)
        assertFalse(zoom.apply(ZoomShortcut.Reset), "already at the default")
        assertTrue(zoom.apply(ZoomShortcut.Out))
        assertEquals(0.9f, zoom.fontScale)
    }

    @Test
    fun zoom_stops_at_its_ends() {
        val zoom = TextZoom()
        repeat(40) { zoom.apply(ZoomShortcut.In) }
        assertEquals(3f, zoom.fontScale)
        assertFalse(zoom.apply(ZoomShortcut.In))
        repeat(40) { zoom.apply(ZoomShortcut.Out) }
        assertEquals(0.5f, zoom.fontScale)
        assertFalse(zoom.apply(ZoomShortcut.Out))
    }

    /** The zoom is what the window's scene is given as its font scale. */
    @Test
    fun a_zoom_is_the_font_scale_the_scene_is_given() {
        val zoom = TextZoom()
        val scale = TextScale { zoom.fontScale }

        zoom.apply(ZoomShortcut.In)
        assertTrue(scale.refresh())

        assertEquals(Density(2f, 1.1f), scale.density(2f))
    }

    private companion object {
        const val SHIFT = 1L shl 17
        const val CONTROL = 1L shl 18
        const val OPTION = 1L shl 19
        const val COMMAND = 1L shl 20
    }
}
