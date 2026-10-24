package dev.darkpyonix.composerust.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.GlassProminence
import dev.darkpyonix.composerust.design.LiquidGlass
import dev.darkpyonix.composerust.design.glassLift
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shadow a floating surface casts, which is what says it is floating.
 *
 * Two things about a real one, and the rings this used to be drawn in had neither. Light
 * comes from above, so there is more shadow under a surface than over it; and a shadow
 * falls away smoothly, so a line of samples walking out from the edge gets lighter every
 * step rather than in six visible stops.
 */
@OptIn(ExperimentalTestApi::class)
class LiquidGlassLiftTest {
    @Test
    fun fr14_1_the_shadow_is_heavier_under_the_surface_than_over_it() = runComposeUiTest {
        val pixels = liftOnWhite()
        val middle = pixels.width / 2
        val above = darkness(pixels, middle, TOP - SAMPLE)
        val below = darkness(pixels, middle, BOTTOM + SAMPLE)
        assertTrue(
            below > above * HEAVIER,
            "the shadow is $below under the surface and $above over it, which is the same " +
                "shadow in every direction: a surface lit from nowhere",
        )
    }

    @Test
    fun fr14_1_the_shadow_falls_away_rather_than_stepping() = runComposeUiTest {
        val pixels = liftOnWhite()
        val middle = pixels.width / 2
        // Walking down and out from the surface's foot. Two things have to hold, and they
        // are not the same thing: the walk never gets darker as it goes out, and no one
        // step in it drops much more than the rest. A stack of rings holds its value right
        // across each ring and drops at the seam, so its walk is flat, flat, flat, cliff.
        //
        // Said this way rather than by counting repeats or measuring the longest run of
        // them, which is how it was written twice. Both fail a shadow that is merely faint:
        // under a level of fall per step the eighth bit rounds neighbouring samples
        // together, so the repeats and the runs grow with no band anywhere, and the test
        // starts reporting the shadow's weight as its shape. How steep the steepest step is
        // against the average does not move when the whole shadow is scaled.
        val walk = (1..WALK).map { step -> darkness(pixels, middle, BOTTOM + step) }
        assertTrue(seam(walk) == null, "${seam(walk)}: $walk")
    }

    /**
     * The same judgement run over walks written by hand, so that what it rejects is on the
     * record rather than inferred.
     *
     * Worth its own test because the criterion has now been written three ways. The first
     * two counted how often the walk held its value, and both passed a ring stack and
     * failed a gaussian once the gaussian was faint enough for the eighth bit to round
     * neighbouring samples together: they were reporting the shadow's weight as its shape.
     */
    @Test
    fun fr14_1_a_banded_walk_is_told_from_a_smooth_one() {
        // What the renderer draws, and what it drew when the shadow was six rings: the same
        // total fall, put into six steps instead of spread across twenty.
        assertEquals(null, seam(listOf(14, 13, 13, 12, 12, 11, 11, 10, 10, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 5)))
        assertEquals(null, seam(listOf(19, 19, 18, 17, 17, 16, 15, 14, 14, 13, 12, 12, 11, 10, 9, 9, 8, 7, 7, 6)))
        assertTrue(
            seam(listOf(14, 14, 14, 12, 12, 12, 11, 11, 11, 9, 9, 9, 8, 8, 8, 6, 6, 6, 5, 5)) != null,
            "a walk that holds flat and then drops twice the average is a stack of rings",
        )
        assertTrue(
            seam(listOf(14, 14, 13, 13, 14, 12, 12, 11, 10, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 5)) != null,
            "a walk that gets darker again on the way out is not a shadow falling away",
        )
    }

    /**
     * What is wrong with a walk out from a surface's foot, or null where nothing is.
     *
     * Two things have to hold, and they are not the same thing: the walk never gets darker
     * as it goes out, and no one step in it drops much more than the rest. A stack of rings
     * holds its value right across each ring and drops at the seam, so its walk is flat,
     * flat, flat, cliff. How steep the steepest step is against the average does not move
     * when the whole shadow is scaled, which is the point: this has to say the same thing
     * about a faint shadow and a heavy one.
     */
    private fun seam(walk: List<Int>): String? {
        val rose = walk.zipWithNext().count { (near, far) -> far > near }
        if (rose > 0) return "the shadow gets darker again $rose times on the way out"
        val fall = walk.first() - walk.last()
        if (fall <= 0) return "the shadow does not fall away at all across the walk"
        val average = fall.toFloat() / (walk.size - 1)
        val steepest = walk.zipWithNext().maxOf { (near, far) -> near - far }
        // One level is a rounding step and can never be a seam, whatever the average is.
        val cliff = maxOf(1, ceil(CLIFF_IN_AVERAGES * average).toInt())
        if (steepest > cliff) {
            return "the shadow's steepest step drops $steepest where the average is " +
                "$average, so it falls in bands with seams between them rather than smoothly"
        }
        return null
    }

    @Test
    fun fr14_1_the_shadow_stays_outside_the_surface() = runComposeUiTest {
        val pixels = liftOnWhite()
        val inside = darkness(pixels, pixels.width / 2, (TOP + BOTTOM) / 2)
        assertTrue(
            inside == 0,
            "the surface has $inside of shadow under it, and a shadow under something you " +
                "can see through greys it from the inside",
        )
    }

    /** How dark this pixel is against the white page, nought to 255. */
    private fun darkness(pixels: androidx.compose.ui.graphics.PixelMap, x: Int, y: Int): Int =
        255 - (pixels[x, y].red * 255f).toInt()

    private fun ComposeUiTest.liftOnWhite(): androidx.compose.ui.graphics.PixelMap {
        setContent {
            Box(Modifier.requiredSize(WIDTH.dp, HEIGHT.dp).background(Color.White)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(SURFACE_WIDTH.dp, SURFACE_HEIGHT.dp)
                        .glassLift(
                            LiquidGlass.material(
                                dark = false,
                                prominence = GlassProminence.Regular,
                                backdrop = Color.White,
                                content = Color.Black,
                            ),
                            RectangleShape,
                        )
                        .background(Color.White),
                )
            }
        }
        waitForIdle()
        return onRoot().captureToImage().toPixelMap()
    }

    private companion object {
        const val WIDTH = 300
        const val HEIGHT = 300
        const val SURFACE_WIDTH = 160
        const val SURFACE_HEIGHT = 80
        val TOP = (HEIGHT - SURFACE_HEIGHT) / 2
        val BOTTOM = TOP + SURFACE_HEIGHT

        /** How far out the over and under samples are taken. Inside the shadow's reach. */
        const val SAMPLE = 6

        /** How much heavier the shadow under has to be than the shadow over. */
        const val HEAVIER = 1.5f

        /** How many steps out the falloff is walked. */
        const val WALK = 20

        /**
         * How many times the average fall the steepest step may be before it is a seam.
         *
         * Twice. A gaussian's steepest point is near the surface and is not far off its
         * own average across a walk this short; a ring stack puts its whole fall into one
         * step per ring and nothing into the rest.
         */
        const val CLIFF_IN_AVERAGES = 2f
    }
}
