package dioxus.compose.test

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
import dioxus.compose.design.GlassProminence
import dioxus.compose.design.LiquidGlass
import dioxus.compose.design.glassLift
import kotlin.test.Test
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
        // Walking down and out from the surface's foot. Every sample has to be lighter
        // than the one before it: a stack of rings holds its value across each ring and
        // drops at the seam, which shows up here as two samples reading the same.
        val walk = (1..WALK).map { step -> darkness(pixels, middle, BOTTOM + step) }
        val held = walk.zipWithNext().count { (near, far) -> far >= near }
        assertTrue(
            held <= WALK / 4,
            "the shadow holds its value for $held of ${walk.size - 1} steps out, so it is " +
                "drawn in bands rather than falling away: $walk",
        )
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
    }
}
