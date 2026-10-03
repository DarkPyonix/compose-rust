package org.thisisthepy.compose.liquidglass

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What this module promises in numbers rather than in pixels.
 *
 * Two of the four acceptance criteria for the Apple design language can be settled
 * without a screen, and both are about what happens when the effect is unavailable or
 * unwanted: the opaque fallback has to be readable, and the reduced transparency setting
 * has to be obeyed. The other two, that the surface reads as glass and that it responds
 * to what is behind it, are judged by looking.
 */
class GlassMaterialTest {

    private val lightContent = Color.Black
    private val darkContent = Color.White

    @Test
    fun fr14_the_opaque_fallback_meets_wcag_aa_body_contrast_in_both_schemes() {
        for (dark in listOf(false, true)) {
            val content = if (dark) darkContent else lightContent
            for (prominence in GlassProminence.entries) {
                val material = LiquidGlass.material(
                    dark = dark,
                    prominence = prominence,
                    backdrop = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7),
                    content = content,
                )
                val ratio = contrastRatio(material.fallback, content)
                assertTrue(
                    ratio >= LiquidGlass.MIN_CONTRAST_BODY,
                    "$prominence in ${if (dark) "dark" else "light"} falls back to a colour " +
                        "that reads at $ratio against its content, below the 4.5 body minimum",
                )
            }
        }
    }

    @Test
    fun fr14_the_fallback_is_fully_opaque_because_a_translucent_one_guarantees_nothing() {
        val material = LiquidGlass.material(
            dark = false,
            backdrop = Color.White,
            content = Color.Black,
        )
        assertEquals(1f, material.fallback.alpha)
    }

    @Test
    fun fr14_the_fallback_holds_even_when_the_backdrop_would_make_the_surface_unreadable() {
        // A pale tint over a pale backdrop composites to something black text can live
        // with and white text cannot. The fallback has to move for the second case.
        val material = LiquidGlass.material(
            dark = false,
            backdrop = Color.White,
            content = Color.White,
        )
        assertTrue(contrastRatio(material.fallback, Color.White) >= LiquidGlass.MIN_CONTRAST_BODY)
        assertTrue(
            relativeLuminance(material.fallback) < 0.5f,
            "the only way to carry white text is to get darker",
        )
    }

    @Test
    fun fr14_reduced_transparency_takes_the_opaque_path() {
        val material = LiquidGlass.material(
            dark = false,
            backdrop = Color(0xFFF2F2F7),
            content = Color.Black,
        )
        assertEquals(
            material.fallback,
            glassFill(material, reduceTransparency = true, blurAvailable = true),
        )
        assertFalse(drawsAsGlass(reduceTransparency = true, blurAvailable = true))
    }

    @Test
    fun fr14_a_build_without_blur_takes_the_opaque_path_too() {
        val material = LiquidGlass.material(
            dark = true,
            backdrop = Color.Black,
            content = Color.White,
        )
        assertEquals(
            material.fallback,
            glassFill(material, reduceTransparency = false, blurAvailable = false),
        )
        assertFalse(drawsAsGlass(reduceTransparency = false, blurAvailable = false))
    }

    @Test
    fun fr14_the_unreduced_path_is_translucent_so_app_content_shows_through() {
        val material = LiquidGlass.material(
            dark = false,
            backdrop = Color(0xFFF2F2F7),
            content = Color.Black,
        )
        val fill = glassFill(material, reduceTransparency = false, blurAvailable = true)
        assertTrue(fill.alpha < 1f, "glass that is opaque is not glass")
        // The alpha makes a round trip through a packed sRGB colour, which holds eight
        // bits per channel, so it comes back on the nearest 1/255 step rather than on the
        // float it went in as.
        assertEquals(material.tintAlpha, fill.alpha, 1f / 255f)
    }

    @Test
    fun fr14_a_clear_material_lets_more_through_and_blurs_less_than_a_regular_one() {
        val backdrop = Color(0xFFF2F2F7)
        val regular = LiquidGlass.material(
            dark = false,
            prominence = GlassProminence.Regular,
            backdrop = backdrop,
            content = Color.Black,
        )
        val clear = LiquidGlass.material(
            dark = false,
            prominence = GlassProminence.Clear,
            backdrop = backdrop,
            content = Color.Black,
        )
        assertTrue(clear.tintAlpha < regular.tintAlpha)
        assertTrue(clear.blurRadius < regular.blurRadius)
    }

    @Test
    fun fr14_glass_is_coloured_by_what_is_behind_it() {
        // At least a quarter of the backdrop reaches the eye through a surface that
        // carries text. At 0.85 of a grey tint a composer over a blue page and one over a
        // white page came out the same grey, which is the flat look this module exists
        // not to have.
        for (dark in listOf(false, true)) {
            val material = LiquidGlass.material(
                dark = dark,
                backdrop = if (dark) Color.Black else Color.White,
                content = if (dark) Color.White else Color.Black,
            )
            assertTrue(material.tintAlpha <= 0.75f, "regular glass is ${material.tintAlpha} opaque")
        }
        val material = LiquidGlass.material(dark = false, backdrop = Color.White, content = Color.Black)
        val fill = material.tint.copy(alpha = material.tintAlpha)
        val overWhite = compositeOver(fill, Color.White)
        val overBlue = compositeOver(fill, Color(0xFFD6E4FF))
        assertTrue(
            (overWhite.red - overBlue.red) * 255f >= 10f,
            "the same glass over white and over blue should not come out the same colour",
        )
    }

    @Test
    fun fr29_window_material_is_mostly_the_window_behind_it() {
        // Chrome that sits straight on a platform material leaves most of the desktop
        // showing, and still has an opaque fallback whose contrast is guaranteed.
        for (dark in listOf(false, true)) {
            val content = if (dark) Color.White else Color.Black
            val window = LiquidGlass.windowMaterial(
                dark = dark,
                backdrop = if (dark) Color(0xFF2C2C2E) else Color.White,
                content = content,
            )
            val regular = LiquidGlass.material(
                dark = dark,
                backdrop = if (dark) Color(0xFF2C2C2E) else Color.White,
                content = content,
            )
            assertTrue(window.tintAlpha <= 0.35f, "window chrome covers ${window.tintAlpha} of the desktop")
            assertTrue(window.tintAlpha < regular.tintAlpha)
            assertEquals(1f, window.fallback.alpha)
            assertTrue(contrastRatio(window.fallback, content) >= LiquidGlass.MIN_CONTRAST_BODY)
        }
    }

    @Test
    fun fr14_the_edge_is_lit_along_the_top_and_shaded_along_the_bottom() {
        for (dark in listOf(false, true)) {
            val material = LiquidGlass.material(
                dark = dark,
                backdrop = if (dark) Color.Black else Color.White,
                content = if (dark) Color.White else Color.Black,
            )
            assertTrue(
                relativeLuminance(material.highlight) > relativeLuminance(material.shade),
                "light arrives from above, so the top edge cannot be the darker one",
            )
            assertTrue(material.highlight.alpha > 0f)
            assertTrue(material.shade.alpha > 0f)
        }
    }

    @Test
    fun fr14_stacking_layers_adds_tint_rather_than_shadow() {
        val material = LiquidGlass.material(
            dark = false,
            backdrop = Color.White,
            content = Color.Black,
        )
        val alphas = (0..4).map { material.atDepth(it).tintAlpha }
        assertEquals(alphas.sorted(), alphas, "each layer should see less through it: $alphas")
        assertEquals(material.tintAlpha, alphas[0], "the bottom layer is unchanged")
        assertTrue(alphas.last() < 1f, "a deep layer is still glass, not a flat fill")
        assertTrue(LiquidGlass.depthShadow(2) > LiquidGlass.depthShadow(1))
        assertEquals(0f, LiquidGlass.depthShadow(0).value, "a layer on the floor casts nothing")
    }

    @Test
    fun fr14_the_dark_material_is_dark_and_the_light_one_is_light() {
        val light = LiquidGlass.material(dark = false, backdrop = Color.White, content = Color.Black)
        val dark = LiquidGlass.material(dark = true, backdrop = Color.Black, content = Color.White)
        assertTrue(relativeLuminance(dark.tint) < relativeLuminance(light.tint))
        assertTrue(relativeLuminance(dark.fallback) < relativeLuminance(light.fallback))
    }
}
