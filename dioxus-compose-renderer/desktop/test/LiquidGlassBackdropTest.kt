package dioxus.compose.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dioxus.compose.design.GlassProminence
import dioxus.compose.design.LiquidGlass
import dioxus.compose.design.GlassBackdrop
import dioxus.compose.design.glassSurface
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What makes glass glass: the backdrop is blurred by the surface over it.
 *
 * Every other part of the material was here already. The tint takes the colour of what is
 * behind, the rim is lit at the top and shaded at the foot, the surface lifts off the page
 * with a shadow drawn outside its outline, and the corners are concentric. None of that
 * reads as glass on its own, because a translucent fill with a line round it is a
 * translucent fill with a line round it, which is what a flat design system draws.
 *
 * So the thing to defend is the one thing that was missing. A page with a hard edge in it,
 * a glass surface laid across that edge, and the question is what the edge looks like
 * underneath: a step means the surface is a sheet of tinted plastic, and a run of
 * intermediate colours means it is a lens.
 */
@OptIn(ExperimentalTestApi::class)
class LiquidGlassBackdropTest {
    @Test
    fun fr14_1_glass_blurs_the_edge_in_the_page_behind_it() = runComposeUiTest {
        setContent {
            Box(Modifier.requiredSize(WIDTH.dp, HEIGHT.dp)) {
                // What shows through. Two flat halves meeting in a straight line down the
                // middle, chosen because a blur of a straight edge is the easiest thing in
                // the world to see and to count.
                GlassBackdrop(
                    Modifier.fillMaxSize(),
                    over = {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .requiredSize(STRIP_WIDTH.dp, STRIP_HEIGHT.dp)
                                .glassSurface(
                                    LiquidGlass.material(
                                        dark = false,
                                        prominence = GlassProminence.Regular,
                                        backdrop = Color.White,
                                        content = Color.Black,
                                    ),
                                    RectangleShape,
                                ),
                        )
                    },
                ) {
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .requiredSize((WIDTH / 2).dp, HEIGHT.dp)
                                .background(Color.Red),
                        )
                        Box(
                            Modifier
                                .requiredSize((WIDTH / 2).dp, HEIGHT.dp)
                                .fillMaxHeight()
                                .background(Color.Blue),
                        )
                    }
                }
            }
        }
        waitForIdle()

        val pixels = onRoot().captureToImage().toPixelMap()
        val middle = pixels.height / 2
        val seam = pixels.width / 2
        // A window either side of the seam, well inside the strip so nothing but the glass
        // is being read.
        val colours = (seam - SEAM_WINDOW..seam + SEAM_WINDOW)
            .map { x -> pixels[x, middle].toArgb() }
            .toSet()
        assertTrue(
            colours.size > STEP_AND_ITS_TWO_SIDES,
            "the page's hard edge is still a hard edge under the glass: ${colours.size} " +
                "colours across ${SEAM_WINDOW * 2 + 1} pixels means the surface laid a " +
                "tint over the page and did not blur it, which is a sheet of tinted " +
                "plastic rather than a lens",
        )
    }

    /**
     * Glass inside the page does not try to blur the page it is part of.
     *
     * A surface under the recording would be asked to draw the layer that is at that moment
     * being recorded into, which is a call to draw the thing being drawn. It does not smear
     * and it does not come out wrong: it runs out of stack and takes the window with it,
     * which is what the first wiring of this did the moment a sample opened.
     */
    @Test
    fun fr14_1_glass_inside_the_page_does_not_blur_the_page_it_is_in() = runComposeUiTest {
        setContent {
            Box(Modifier.requiredSize(WIDTH.dp, HEIGHT.dp)) {
                GlassBackdrop(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        // A card in the middle of the page, made of the same glass the
                        // chrome is made of. It is inside the recording.
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .requiredSize(STRIP_WIDTH.dp, STRIP_HEIGHT.dp)
                                .glassSurface(
                                    LiquidGlass.material(
                                        dark = false,
                                        prominence = GlassProminence.Regular,
                                        backdrop = Color.White,
                                        content = Color.Black,
                                    ),
                                    RectangleShape,
                                ),
                        )
                    }
                }
            }
        }
        waitForIdle()
        // Reaching this line at all is the assertion: the draw above either returns or
        // never returns. Reading a pixel keeps it honest about having drawn something.
        val pixels = onRoot().captureToImage().toPixelMap()
        assertTrue(pixels.width > 0, "nothing was drawn")
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 160
        const val STRIP_WIDTH = 200
        const val STRIP_HEIGHT = 60

        /** How far either side of the seam is read. Inside the blur's own reach. */
        const val SEAM_WINDOW = 10

        /**
         * A step has two colours, and antialiasing on the boundary can add one more. More
         * than that is a gradient, and a gradient is the blur.
         */
        const val STEP_AND_ITS_TWO_SIDES = 3
    }
}
