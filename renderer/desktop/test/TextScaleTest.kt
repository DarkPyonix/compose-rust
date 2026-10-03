@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.ui.platform.TextScale
import dev.darkpyonix.composerust.ui.platform.WindowFrames
import dev.darkpyonix.composerust.ui.platform.WindowMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reader's text size reaching the scene a window draws.
 *
 * Every desktop window used to build its Density from the display's scale alone, so the
 * font scale was always one and a reader who had asked the system for larger text got the
 * design's size anyway. What is checked here is the part every window shares: a source of
 * the scale, read through [TextScale], handed to the scene beside the display's scale by
 * [WindowFrames], and taken up by text in the composition, including when it changes while
 * the window is open. Where each platform finds the number is checked in its own tests.
 */
class TextScaleTest {

    /** What a platform says, as a test sets it. */
    private var system = 1f
    private val textScale = TextScale { system }

    @Test
    fun a_text_scale_is_read_from_its_source_when_made() {
        system = 1.5f
        val scale = TextScale { system }

        assertEquals(1.5f, scale.fontScale)
        assertEquals(Density(2f, 1.5f), scale.density(2f))
    }

    @Test
    fun a_refresh_says_whether_the_scale_changed() {
        assertFalse(textScale.refresh(), "nothing changed, so there is nothing to draw")

        system = 1.25f
        assertTrue(textScale.refresh(), "a changed text size is a frame to draw")
        assertEquals(1.25f, textScale.fontScale)

        assertFalse(textScale.refresh(), "the same answer twice is one change")
    }

    @Test
    fun a_scale_that_is_not_a_size_is_read_as_the_default() {
        for (nonsense in listOf(0f, -1f, Float.NaN)) {
            system = nonsense
            textScale.refresh()
            assertEquals(1f, textScale.fontScale, "a reported $nonsense")
        }
        system = 100f
        textScale.refresh()
        assertEquals(4f, textScale.fontScale, "a misread setting is held to what text can be drawn at")
    }

    /**
     * A frame hands the scene the display's scale and the reader's text scale together.
     *
     * This is where the defect was: the Density a window built had the first and not the
     * second.
     */
    @Test
    fun a_frame_is_drawn_at_the_text_scale_beside_the_display_scale() {
        system = 1.5f
        textScale.refresh()
        val handed = mutableListOf<Density>()
        val frames = WindowFrames({ WindowMeasurement(800, 600, 2f) }, textScale) { _, density ->
            handed.add(density)
        }

        assertTrue(frames.draw())
        system = 2f
        assertTrue(textScale.refresh())
        assertTrue(frames.draw())

        assertEquals(listOf(Density(2f, 1.5f), Density(2f, 2f)), handed)
    }

    /**
     * Text in the scene is laid out at the reader's size, and again when the size changes.
     *
     * Through a real scene of the kind every window draws, given its Density the way a
     * window's frame gives it: the scale is asked for, the Density built from it, and the
     * scene told only when that differs from what it has.
     */
    @Test
    fun text_in_the_scene_follows_a_text_scale_that_changes_while_the_window_is_open() {
        val scene = CanvasLayersComposeScene(density = textScale.density(1f), size = IntSize(400, 300))
        var seen = Density(0f)
        var lineHeight = 0
        scene.setContent {
            seen = LocalDensity.current
            BasicText(
                "Aa",
                style = TextStyle(fontSize = 20.sp),
                modifier = Modifier.onSizeChanged { lineHeight = it.height },
            )
        }
        val frames = WindowFrames({ WindowMeasurement(400, 300, 1f) }, textScale) { size, density ->
            if (scene.size != size || scene.density != density) {
                scene.density = density
                scene.size = size
            }
            org.jetbrains.skia.Surface.makeRasterN32Premul(size.width, size.height).use { surface ->
                scene.render(surface.canvas.asComposeCanvas(), 0L)
            }
        }
        try {
            assertTrue(frames.draw())
            assertEquals(1f, seen.fontScale)
            val defaultHeight = lineHeight
            assertTrue(defaultHeight > 0, "the text was laid out")

            // The reader asks the system for text twice the size, with the window open.
            system = 2f
            assertTrue(textScale.refresh())
            assertTrue(frames.draw())

            assertEquals(2f, seen.fontScale, "the composition sees the reader's text scale")
            assertEquals(1f, seen.density, "the display's scale is unchanged by it")
            assertTrue(
                lineHeight >= defaultHeight * 3 / 2,
                "a line of text at twice the size is taller: $defaultHeight then $lineHeight",
            )
        } finally {
            scene.close()
        }
    }
}
