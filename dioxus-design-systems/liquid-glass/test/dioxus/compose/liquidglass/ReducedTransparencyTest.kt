package dioxus.compose.liquidglass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dioxus.compose.SurfaceMaterial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReducedTransparencyTest {

    private val glass = LiquidGlass.material(
        dark = false,
        backdrop = Color(0xFFF2F2F7),
        content = Color.Black,
    )

    @Test
    fun fr14_reduced_transparency_selects_the_opaque_fallback() {
        val fill = glassFill(glass, reduceTransparency = true, blurAvailable = true)
        assertEquals(glass.fallback, fill)
        assertEquals(1f, fill.alpha, "reduced transparency has to mean opaque")
    }

    @Test
    fun fr14_missing_blur_selects_the_opaque_fallback_too() {
        // Translucency without blur is not a softened backdrop, it is text over whatever
        // was underneath at reduced contrast, so it takes the same path.
        val fill = glassFill(glass, reduceTransparency = false, blurAvailable = false)
        assertEquals(glass.fallback, fill)
        assertEquals(1f, fill.alpha)
    }

    @Test
    fun fr14_the_opaque_path_is_readable() {
        for (reduce in listOf(true, false)) {
            for (blur in listOf(true, false)) {
                if (drawsAsGlass(reduce, blur)) continue
                val fill = glassFill(glass, reduce, blur)
                assertTrue(
                    contrastRatio(fill, Color.Black) >= LiquidGlass.MIN_CONTRAST_BODY,
                    "reduce=$reduce blur=$blur gave $fill, which body text cannot be read " +
                        "on",
                )
            }
        }
    }

    @Test
    fun fr14_the_translucent_path_is_taken_only_when_both_conditions_allow_it() {
        assertTrue(drawsAsGlass(reduceTransparency = false, blurAvailable = true))
        assertTrue(!drawsAsGlass(reduceTransparency = true, blurAvailable = true))
        assertTrue(!drawsAsGlass(reduceTransparency = false, blurAvailable = false))
        assertTrue(!drawsAsGlass(reduceTransparency = true, blurAvailable = false))

        val translucent = glassFill(glass, reduceTransparency = false, blurAvailable = true)
        // Compose packs sRGB at eight bits per channel, so a stored 0.70 reads back as
        // 0.7019608. One step of 1/255 is the resolution of the format, not a drift worth
        // failing over.
        assertEquals(glass.tintAlpha, translucent.alpha, 1f / 255f)
        assertTrue(translucent.alpha < 1f)
    }

    @Test
    fun fr14_the_opaque_path_costs_nothing_to_blur() {
        // Turning transparency off has to remove the render pass as well as the look;
        // blurring a backdrop nobody can see through is pure cost.
        assertEquals(
            0.dp,
            glassBlurRadius(glass, reduceTransparency = true, blurAvailable = true),
        )
        assertEquals(
            0.dp,
            glassBlurRadius(glass, reduceTransparency = false, blurAvailable = false),
        )
        assertEquals(
            glass.blurRadius,
            glassBlurRadius(glass, reduceTransparency = false, blurAvailable = true),
        )
    }

    @Test
    fun fr14_an_opaque_material_is_unaffected_by_either_setting() {
        // Material 3 and Fluent are not degraded glass; a reduced transparency setting has
        // nothing to take away from them.
        val opaque: SurfaceMaterial = SurfaceMaterial.Opaque(Color(0xFF1B1B1F))
        for (reduce in listOf(true, false)) {
            for (blur in listOf(true, false)) {
                assertEquals(Color(0xFF1B1B1F), glassFill(opaque, reduce, blur))
                assertEquals(0.dp, glassBlurRadius(opaque, reduce, blur))
            }
        }
    }

    @Test
    fun fr14_depth_still_applies_on_the_translucent_path_only() {
        val shallow = glassFill(glass, false, true, depth = 0)
        val deep = glassFill(glass, false, true, depth = 2)
        assertTrue(deep.alpha > shallow.alpha)

        // The fallback is one colour whatever the stack looks like: it was computed to
        // meet contrast, and deepening it would break that without anyone noticing.
        assertEquals(
            glassFill(glass, true, true, depth = 0),
            glassFill(glass, true, true, depth = 3),
        )
    }
}
