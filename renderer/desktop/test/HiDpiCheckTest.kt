package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.ui.platform.HiDpiImage
import dev.darkpyonix.composerust.ui.platform.HiDpiScene
import dev.darkpyonix.composerust.ui.platform.HiDpiSurface
import dev.darkpyonix.composerust.ui.platform.MAX_GLYPH_EDGE
import dev.darkpyonix.composerust.ui.platform.RasterHiDpiSurface
import dev.darkpyonix.composerust.ui.platform.curvedEdgeWidths
import dev.darkpyonix.composerust.ui.platform.difference
import dev.darkpyonix.composerust.ui.platform.drawHiDpiScene
import dev.darkpyonix.composerust.ui.platform.glyphEdgeWidth
import dev.darkpyonix.composerust.ui.platform.hiDpiReferenceSurface
import dev.darkpyonix.composerust.ui.platform.oneDpLineProblem
import dev.darkpyonix.composerust.ui.platform.renderHiDpiScene
import dev.darkpyonix.composerust.ui.platform.stretched
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry

/**
 * The measurements the renderer's HiDPI check makes of its own window (HiDpiCheck.kt), made here
 * on pictures whose answer is known: the marker scene as Compose draws it at a scale, which has
 * to pass, and the same scene drawn at 1x and stretched, aliased or blurred, which has to fail.
 * A check that passes everything proves nothing about the window it is run against.
 */
class HiDpiCheckTest {

    private fun scene(scale: Float): HiDpiImage = renderHiDpiScene(
        Density(scale),
        (HiDpiScene.WIDTH_DP * scale).roundToInt(),
        (HiDpiScene.HEIGHT_DP * scale).roundToInt(),
    )

    /** The scene drawn at 1x and stretched to [scale], as a window server shows a frame of the wrong size. */
    private fun stretchedTo(scale: Float): HiDpiImage = stretched(
        scene(1f),
        (HiDpiScene.WIDTH_DP * scale).roundToInt(),
        (HiDpiScene.HEIGHT_DP * scale).roundToInt(),
    )

    @Test
    fun fr82_one_dp_line_check_passes_whole_pixels_and_fails_a_stretched_frame() {
        assertNull(oneDpLineProblem(scene(1f), 1), "Compose draws a 1 dp bar as one black pixel at 1x")
        assertNull(oneDpLineProblem(scene(2f), 2), "Compose draws a 1 dp bar as two black pixels at 2x")
        assertNotNull(
            oneDpLineProblem(stretchedTo(2f), 2),
            "a 1x frame stretched to 2x has grey beside its bars, and the check has to see it",
        )
    }

    @Test
    fun fr82_fractional_comparison_tells_compose_at_that_scale_from_a_stretched_frame() {
        for (scale in listOf(1.25f, 1.5f)) {
            val reference = scene(scale)
            val again = difference(reference, scene(scale))!!
            assertTrue(again.matches, "the same scene drawn twice at $scale is the same picture: $again")
            val stretch = difference(reference, stretchedTo(scale))!!
            assertFalse(stretch.matches, "a 1x frame stretched to $scale differs from Compose at $scale: $stretch")
        }
    }

    @Test
    fun fr82_reference_is_drawn_on_the_window_gpu_surface_and_on_the_cpu_only_without_one() {
        val gpu = Recording(RasterHiDpiSurface(), "a GPU surface")
        assertSame(gpu, hiDpiReferenceSurface(gpu, PixelGeometry.RGB_H), "a window with a GPU surface draws the reference on it")
        val fallback = hiDpiReferenceSurface(null, PixelGeometry.RGB_H)
        assertIs<RasterHiDpiSurface>(fallback, "a window without one draws the reference on the CPU")
        assertTrue("raster" in fallback.path, "the check's line says the CPU drew it: ${fallback.path}")
    }

    @Test
    fun fr82_reference_and_its_stretched_control_go_through_the_surface_they_are_given() {
        val scale = 1.25f
        val width = (HiDpiScene.WIDTH_DP * scale).roundToInt()
        val height = (HiDpiScene.HEIGHT_DP * scale).roundToInt()
        val surface = Recording(RasterHiDpiSurface(), "a recording surface")
        val reference = assertNotNull(drawHiDpiScene(surface, Density(scale), width, height), "the scene drawn on a surface that draws is read back")
        assertNotNull(drawHiDpiScene(surface, Density(1f), HiDpiScene.WIDTH_DP, HiDpiScene.HEIGHT_DP), "the 1x control is read back too")
        assertEquals(
            listOf(width to height, HiDpiScene.WIDTH_DP to HiDpiScene.HEIGHT_DP),
            surface.sizes,
            "the reference at the window's size and the 1x control at its size in dp were both drawn on the surface",
        )
        assertContentEquals(scene(scale).pixels, reference.pixels, "the scene on a raster surface is the scene renderHiDpiScene draws")
        val failing = object : HiDpiSurface {
            override val path = "a surface that cannot be read"
            override fun draw(width: Int, height: Int, paint: (Canvas) -> Unit): HiDpiImage? = null
        }
        assertNull(drawHiDpiScene(failing, Density(scale), width, height), "a surface that cannot be read gives no reference")
    }

    @Test
    fun fr82_curved_edge_check_sees_antialiasing_and_its_absence() {
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            val widths = curvedEdgeWidths(scene(scale), scale)
            assertTrue(widths.passes, "Compose antialiases the circle and the clip at $scale: $widths")
            val aliased = curvedEdgeWidths(thresholded(scene(scale)), scale)
            assertFalse(aliased.passes, "the same edges with every pixel black or white are not antialiased: $aliased")
        }
    }

    @Test
    fun fr82_glyph_edge_check_passes_compose_text_and_fails_a_soft_stroke() {
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            val width = glyphEdgeWidth(scene(scale), scale)
            assertNotNull(width, "the check finds \"${HiDpiScene.TEXT}\" where the scene draws it at $scale")
            assertTrue(width <= MAX_GLYPH_EDGE, "Compose's glyph edges at $scale are at most $MAX_GLYPH_EDGE px: $width")
        }
        assertEquals(0, glyphEdgeWidth(stroke(ramp = 0), 1f), "a stroke that starts black at its edge has no ramp")
        val soft = glyphEdgeWidth(stroke(ramp = 5), 1f)
        assertNotNull(soft)
        assertTrue(soft > MAX_GLYPH_EDGE, "a stroke whose edge fades in over 5 px is wider than $MAX_GLYPH_EDGE px: $soft")
    }

    @Test
    fun fr82_pixel_difference_allows_a_tenth_of_a_percent_over_eight_levels() {
        val white = HiDpiImage(100, 100, IntArray(10_000) { WHITE })
        val ten = HiDpiImage(100, 100, IntArray(10_000) { if (it < 10) GREY_9 else WHITE })
        val eleven = HiDpiImage(100, 100, IntArray(10_000) { if (it < 11) GREY_9 else WHITE })
        val atTheLimit = difference(white, ten)!!
        assertEquals(10, atTheLimit.overEight)
        assertTrue(atTheLimit.matches, "10 of 10000 pixels over 8 levels is 0.1%, which is allowed: $atTheLimit")
        assertFalse(difference(white, eleven)!!.matches, "11 of 10000 is over 0.1%")
        val shifted = HiDpiImage(100, 100, IntArray(10_000) { 0xFFFDFDFD.toInt() })
        assertFalse(difference(white, shifted)!!.matches, "a picture 2 levels off everywhere has a mean over 1")
        assertNull(difference(white, HiDpiImage(1, 1, IntArray(1) { WHITE })), "pictures of two sizes are not compared")
    }

    /** A surface that draws on [inner] and remembers the size of each drawing. */
    private class Recording(private val inner: HiDpiSurface, override val path: String) : HiDpiSurface {
        val sizes = mutableListOf<Pair<Int, Int>>()

        override fun draw(width: Int, height: Int, paint: (Canvas) -> Unit): HiDpiImage? {
            sizes += width to height
            return inner.draw(width, height, paint)
        }
    }

    /** Every pixel made black or white at the half way level: what an edge without antialiasing looks like. */
    private fun thresholded(image: HiDpiImage): HiDpiImage = HiDpiImage(
        image.width,
        image.height,
        IntArray(image.pixels.size) { index ->
            val x = index % image.width
            val y = index / image.width
            if (image.level(x, y) < 128) BLACK else WHITE
        },
    )

    /**
     * A white 1x picture of the scene's size with one black vertical stroke where the scene
     * draws its text, 4 px wide and 14 px high, whose left edge goes from white to black over
     * [ramp] pixels.
     */
    private fun stroke(ramp: Int): HiDpiImage {
        val left = HiDpiScene.TEXT_LEFT + 2
        val top = HiDpiScene.TEXT_TOP + 2
        return HiDpiImage(
            HiDpiScene.WIDTH_DP,
            HiDpiScene.HEIGHT_DP,
            IntArray(HiDpiScene.WIDTH_DP * HiDpiScene.HEIGHT_DP) { index ->
                val x = index % HiDpiScene.WIDTH_DP
                val y = index / HiDpiScene.WIDTH_DP
                val level = when {
                    y !in top until top + 14 -> 255
                    x in left until left + ramp -> 255 - 255 * (x - left + 1) / (ramp + 1)
                    x in left + ramp until left + ramp + 4 -> 0
                    else -> 255
                }
                (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            },
        )
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val GREY_9 = 0xFFF6F6F6.toInt()
    }
}
