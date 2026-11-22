package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.ui.node.blurredShadowPaint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A blurred CSS `box-shadow` is one path drawn with a paint that blurs it.
 *
 * Drawn here on a bare canvas, with no window, no scene and no graphics context behind it.
 * A layer cannot be made there, so a shadow that needed one could not be drawn this way at
 * all. The layer is what this avoids: a shadow blurred as a layer is drawn offscreen at its
 * full size on every frame, and on Metal the textures that takes stay held while the shadow
 * is on screen.
 */
class ShadowBlurPaintTest {

    /**
     * An outline 80 wide, far taller than the blur, blurred with a deviation of 16 (a CSS
     * blur of 32). Across it, the darkness is the Gaussian's cumulative distribution from
     * the left edge minus the one from the right edge, so both edges stay where the outline
     * put them and the falloff is the CSS one, inside and outside.
     */
    @Test
    fun fr42_shadow_blur_is_drawn_by_its_paint_without_a_layer() {
        val width = 240
        val height = 120
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color.White)
            val outline = Path().apply { addRect(Rect(LEFT, -400f, RIGHT, 520f)) }
            drawContext.canvas.drawPath(outline, blurredShadowPaint(SolidColor(Color.Black), size, SIGMA))
        }
        val pixels = bitmap.toPixelMap()
        var checked = 0
        for (x in 0 until width step 2) {
            val centre = x + 0.5f
            val darkness = 1f - pixels[x, height / 2].red
            val expected = cdf((centre - LEFT) / SIGMA) - cdf((centre - RIGHT) / SIGMA)
            assertTrue(
                abs(darkness - expected) <= 0.02f,
                "at x = $x: darkness $darkness, the Gaussian across an outline from $LEFT to $RIGHT gives $expected",
            )
            checked++
        }
        assertTrue(checked == width / 2, "every other column was checked")
    }

    /** How much of a unit Gaussian lies below z. */
    private fun cdf(z: Float): Float = if (z >= 0f) 1f - tail(z) else tail(-z)

    /** erfc(z / sqrt 2) / 2, by Abramowitz and Stegun 7.1.26 (good to 1.5e-7). */
    private fun tail(z: Float): Float {
        val x = z / sqrt(2f)
        val t = 1f / (1f + 0.3275911f * x)
        val poly = t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f))))
        return 0.5f * poly * exp(-x * x)
    }

    private companion object {
        const val LEFT = 60f
        const val RIGHT = 140f
        const val SIGMA = 16f
    }
}
