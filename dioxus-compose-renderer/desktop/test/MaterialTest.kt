package dioxus.compose.test

import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.SurfaceMaterial
import dioxus.compose.design.drawsAsGlass
import dioxus.compose.design.glassBlurRadius
import dioxus.compose.design.glassFill
import dioxus.compose.design.rulesFor
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.DesignTokens
import dioxus.compose.protocol.MaterialRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same declaration, answered by each system in its own terms.
 *
 * A screen says what a surface is made of and never how much to blur, because four of the
 * seven systems here do not blur at all and a radius handed to them would be an
 * instruction they have to ignore.
 */
class MaterialTest {

    private fun themeFor(system: DesignSystem) = ResolvedTheme(
        system = system,
        tokens = DesignTokens.of(system),
        rules = rulesFor(system),
        dark = false,
    )

    private fun themes() = DesignSystem.entries.map { it to themeFor(it) }

    @Test
    fun fr23_the_same_material_gives_a_different_fill_in_each_system() {
        val fills = themes().map { (_, theme) ->
            glassFill(
                material = theme.rules.material(MaterialRole.Regular, theme),
                reduceTransparency = false,
                blurAvailable = true,
            )
        }
        assertEquals(
            fills.size,
            fills.toSet().size,
            "two systems painted the same pixels: ${DesignSystem.entries.zip(fills)}",
        )
    }

    @Test
    fun fr23_thickness_separates_a_surface_further_from_its_page() {
        for ((_, theme) in themes()) {
            val radii = MaterialRole.entries.map {
                glassBlurRadius(theme.rules.material(it, theme), false, true).value
            }
            // Either the system blurs, and thickness is more blur, or it does not blur at
            // all and every role answers zero. What it must not do is disagree with
            // itself about which of those it is.
            assertTrue(radii == radii.sorted(), "thickness ran backwards")
        }
    }

    @Test
    fun fr23_a_surface_that_cannot_blur_falls_back_to_an_opaque_fill() {
        for ((system, theme) in themes()) {
            val material = theme.rules.material(MaterialRole.Regular, theme)
            val fallback = glassFill(material, reduceTransparency = true, blurAvailable = true)
            assertEquals(1f, fallback.alpha, "$system left a see-through fallback")
            assertEquals(
                0f,
                glassBlurRadius(material, reduceTransparency = true, blurAvailable = true).value,
                "$system kept paying for a blur nobody can see",
            )
        }
        assertTrue(!drawsAsGlass(reduceTransparency = true, blurAvailable = true))
        assertTrue(!drawsAsGlass(reduceTransparency = false, blurAvailable = false))
    }

    @Test
    fun fr23_the_systems_that_have_translucency_use_it_and_the_others_do_not() {
        // Three do: both Apple systems, and Windows, whose acrylic predates them. The
        // GNOME and KDE panels are opaque, and Material 3 raises and tints instead.
        val translucent = setOf(
            DesignSystem.LiquidGlass,
            DesignSystem.Cupertino,
            DesignSystem.Fluent,
        )
        for ((system, theme) in themes()) {
            val material = theme.rules.material(MaterialRole.Regular, theme)
            assertEquals(
                system in translucent,
                material is SurfaceMaterial.Glass,
                "$system answered with the wrong kind of material",
            )
        }
    }

    @Test
    fun fr23_an_opaque_material_stays_between_the_systems_own_two_colours() {
        val theme = themeFor(DesignSystem.Gnome)
        val surface = theme.color(ColorRole.Surface)
        val variant = theme.color(ColorRole.SurfaceVariant)
        for (role in MaterialRole.entries) {
            val fill = (theme.rules.material(role, theme) as SurfaceMaterial.Opaque).color
            assertTrue(between(fill.red, surface.red, variant.red))
            assertTrue(between(fill.green, surface.green, variant.green))
            assertTrue(between(fill.blue, surface.blue, variant.blue))
        }
    }

    private fun between(value: Float, first: Float, second: Float): Boolean =
        value >= minOf(first, second) - TOLERANCE && value <= maxOf(first, second) + TOLERANCE

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
