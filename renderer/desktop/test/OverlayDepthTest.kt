package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.GlassProminence
import dev.darkpyonix.composerust.design.LiquidGlass
import dev.darkpyonix.composerust.design.SurfaceMaterial
import dev.darkpyonix.composerust.design.liftsOffThePage
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A menu is a sheet over the page, and a sheet over a page has a shadow.
 *
 * Every glass surface the renderer drew got its tint and its lit edge, and only the ones
 * that asked for a lift by hand got a shadow. A menu asked for neither, so it sat on the
 * page as a tinted rectangle with a line round it, while the application it is drawn from
 * floats its menus clear of everything.
 */
class OverlayDepthTest {
    private val glass: SurfaceMaterial = LiquidGlass.material(
        dark = false,
        prominence = GlassProminence.Regular,
        backdrop = Color.White,
        content = Color.Black,
    )

    @Test
    fun fr14_1_a_glass_overlay_lifts_off_the_page() {
        assertTrue(
            liftsOffThePage(glass, overlay = true),
            "an overlay made of glass draws no shadow, so it is a tinted rectangle lying " +
                "on the page rather than a sheet over it",
        )
    }

    /**
     * And a surface that is part of the page does not.
     *
     * A card in the middle of a document is not floating over it, and a shadow under every
     * glass surface in the tree is what turns a page into a pile of cards.
     */
    @Test
    fun fr14_1_a_glass_surface_in_the_page_does_not() {
        assertFalse(
            liftsOffThePage(glass, overlay = false),
            "everything made of glass lifts, so the page is a pile of cards",
        )
    }

    /** An opaque system draws no shadow either way: it has no sheets to float. */
    @Test
    fun fr14_1_an_opaque_overlay_does_not_lift() {
        assertFalse(liftsOffThePage(SurfaceMaterial.Opaque(Color.White), overlay = true))
    }
}
