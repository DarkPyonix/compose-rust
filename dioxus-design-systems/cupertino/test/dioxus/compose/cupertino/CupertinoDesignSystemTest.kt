package dioxus.compose.cupertino

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dioxus.compose.ButtonVariant
import dioxus.compose.ColorRole
import dioxus.compose.DesignSystemId
import dioxus.compose.ShapeRole
import dioxus.compose.SpaceRole
import dioxus.compose.SurfaceMaterial
import dioxus.compose.TypeRole
import dioxus.compose.liquidglass.ContinuousCornerShape
import dioxus.compose.liquidglass.LiquidGlass
import dioxus.compose.liquidglass.concentricRadius
import dioxus.compose.liquidglass.contrastRatio
import dioxus.compose.liquidglass.glassFill
import dioxus.compose.liquidglass.relativeLuminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CupertinoDesignSystemTest {

    private val light = CupertinoDesignSystem.Light
    private val dark = CupertinoDesignSystem.Dark
    private val schemes = listOf(light, dark)

    @Test
    fun fr14_every_color_role_resolves_to_a_real_color_in_both_schemes() {
        for (system in schemes) {
            for (role in ColorRole.entries) {
                val color = system.color(role)
                assertNotEquals(
                    Color.Unspecified,
                    color,
                    "$role is unresolved when isDark=${system.isDark}",
                )
                assertTrue(color.alpha > 0f, "$role is transparent when isDark=${system.isDark}")
            }
        }
    }

    /**
     * Not every role: white on a system blue button is white in both schemes, and forcing
     * it to differ would be inventing a colour Apple does not use. What has to move is
     * everything that describes a surface or the text on it, plus the tint itself.
     */
    @Test
    fun fr14_the_dark_scheme_moves_every_surface_tint_and_label_role() {
        val mustMove = ColorRole.entries - setOf(
            ColorRole.OnPrimary,
            ColorRole.OnSecondary,
            ColorRole.OnTertiary,
            ColorRole.OnError,
        )
        for (role in mustMove) {
            assertNotEquals(
                light.color(role),
                dark.color(role),
                "$role is the same in light and dark, so the dark scheme is not doing its job",
            )
        }
    }

    @Test
    fun fr14_the_system_tint_lightens_for_dark_so_it_survives_against_black() {
        assertTrue(
            relativeLuminance(dark.color(ColorRole.Primary)) >
                relativeLuminance(light.color(ColorRole.Primary)),
            "system blue moves up for dark rather than staying put",
        )
        assertTrue(
            contrastRatio(dark.color(ColorRole.Primary), dark.color(ColorRole.Background)) >= 3f,
            "the tint has to stay legible on the dark canvas",
        )
    }

    @Test
    fun fr14_body_text_is_readable_on_every_surface_in_both_schemes() {
        val pairs = listOf(
            ColorRole.Surface to ColorRole.OnSurface,
            ColorRole.Background to ColorRole.OnBackground,
            ColorRole.SurfaceVariant to ColorRole.OnSurfaceVariant,
        )
        for (system in schemes) {
            for ((container, content) in pairs) {
                val ratio = contrastRatio(system.color(container), system.color(content))
                assertTrue(
                    ratio >= 4.5f,
                    "$content on $container reads at $ratio when isDark=${system.isDark}",
                )
            }
        }
    }

    /**
     * Three rather than four and a half, and the difference is not a lowered bar.
     *
     * System blue under white and system red under white both land near four to one.
     * That is Apple's palette, not a transcription slip, and these roles are used for
     * short prominent labels on controls rather than for paragraphs, which is the case
     * the three to one threshold exists for. Raising system blue until a body paragraph
     * could sit on it would mean shipping a blue that is not Apple's, which fails the
     * requirement this system is here to meet. Long text goes on a surface role, and
     * those are held to the full body ratio above.
     */
    @Test
    fun fr14_a_tinted_control_label_clears_the_non_text_contrast_threshold() {
        val pairs = listOf(
            ColorRole.Primary to ColorRole.OnPrimary,
            ColorRole.Secondary to ColorRole.OnSecondary,
            ColorRole.Error to ColorRole.OnError,
        )
        for (system in schemes) {
            for ((container, content) in pairs) {
                val ratio = contrastRatio(system.color(container), system.color(content))
                assertTrue(
                    ratio >= 3f,
                    "$content on $container reads at $ratio when isDark=${system.isDark}",
                )
            }
        }
    }

    @Test
    fun fr14_is_dark_is_a_property_of_the_instance_rather_than_an_argument() {
        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        for (system in schemes) assertEquals(DesignSystemId.Cupertino, system.id)
    }

    @Test
    fun fr13_every_type_role_answers_with_a_sized_style_in_both_schemes() {
        for (system in schemes) {
            for (role in TypeRole.entries) {
                val style = system.type(role)
                assertTrue(style.fontSize.isSpecified, "$role has no size")
                assertTrue(style.fontSize.value > 0f, "$role is sized at zero")
                assertTrue(style.lineHeight.isSpecified, "$role has no line height")
                assertTrue(
                    style.lineHeight.value > style.fontSize.value,
                    "$role would set solid or tighter",
                )
            }
        }
    }

    @Test
    fun fr13_the_type_ladder_descends_from_large_title_to_footnote() {
        val ladder = listOf(
            TypeRole.Display,
            TypeRole.Headline,
            TypeRole.Title,
            TypeRole.Subtitle,
            TypeRole.Body,
        )
        val sizes = ladder.map { light.type(it).fontSize.value }
        assertEquals(sizes.sortedDescending(), sizes, "the ladder is not monotonic: $sizes")
        assertEquals(17f, light.type(TypeRole.Body).fontSize.value, "Apple's body size")
        assertTrue(
            light.type(TypeRole.Caption).fontSize.value < light.type(TypeRole.Body).fontSize.value,
        )
    }

    @Test
    fun fr13_headline_is_body_in_semibold_rather_than_a_size_of_its_own() {
        val body = light.type(TypeRole.Body)
        val strong = light.type(TypeRole.BodyStrong)
        assertEquals(body.fontSize, strong.fontSize)
        assertEquals(body.lineHeight, strong.lineHeight)
        assertEquals(FontWeight.Normal, body.fontWeight)
        assertEquals(FontWeight.SemiBold, strong.fontWeight)
    }

    @Test
    fun fr13_the_ramp_tracks_negatively_from_body_upwards_which_is_how_sf_sets() {
        for (role in listOf(TypeRole.Display, TypeRole.Headline, TypeRole.Title, TypeRole.Body)) {
            assertTrue(
                light.type(role).letterSpacing.value < 0f,
                "$role should tighten, tracking at zero is not San Francisco",
            )
        }
        assertTrue(
            light.type(TypeRole.Caption).letterSpacing.value >= 0f,
            "SF stops tightening at footnote size",
        )
    }

    @Test
    fun fr13_mono_asks_for_a_monospaced_family_at_body_metrics() {
        val mono = light.type(TypeRole.Mono)
        assertEquals(FontFamily.Monospace, mono.fontFamily)
        assertEquals(light.type(TypeRole.Body).fontSize, mono.fontSize)
    }

    @Test
    fun fr14_every_rounded_corner_is_continuous_rather_than_circular() {
        for (system in schemes) {
            for (role in ShapeRole.entries - ShapeRole.None) {
                val shape = system.shape(role)
                assertTrue(
                    shape is ContinuousCornerShape,
                    "$role came back as $shape, which joins its edges at a curvature jump",
                )
            }
            assertEquals(RectangleShape, system.shape(ShapeRole.None))
        }
    }

    @Test
    fun fr13_the_corner_ladder_climbs_and_a_control_is_cornered_at_twelve_dp() {
        val ladder = listOf(ShapeRole.ExtraSmall, ShapeRole.Small, ShapeRole.Medium, ShapeRole.Large)
            .map { (light.shape(it) as ContinuousCornerShape).topStart.value }
        assertEquals(ladder.sorted(), ladder, "the corner ladder is not monotonic: $ladder")
        assertEquals(12f, (light.shape(ShapeRole.Medium) as ContinuousCornerShape).topStart.value)
        assertEquals(ShapeRole.Medium, light.button(ButtonVariant.Filled).shape)
    }

    @Test
    fun fr14_an_element_inside_a_container_gets_a_concentric_radius_not_a_ladder_step() {
        val card = light.shape(ShapeRole.Large) as ContinuousCornerShape
        val inset = light.space(SpaceRole.Sm)
        assertEquals(
            concentricRadius(card.topStart, inset).value,
            (card.topStart.value - inset.value),
            1e-4f,
        )
        // The inset is what decides the inner radius, which is the thing a fixed ladder
        // step cannot express: move the element further in and its corner has to follow,
        // while a step off the ladder would stay where it was. A step that happens to
        // equal the answer for one particular inset is a coincidence, not a method, and
        // this is what shows the coincidence breaking.
        val deeper = light.space(SpaceRole.Md)
        assertNotEquals(
            concentricRadius(card.topStart, inset).value,
            concentricRadius(card.topStart, deeper).value,
            "a different inset has to give a different inner radius",
        )
        assertEquals(
            card.topStart.value - deeper.value,
            concentricRadius(card.topStart, deeper).value,
            1e-4f,
        )
    }

    @Test
    fun fr13_the_space_ladder_climbs_and_pads_content_at_apples_sixteen() {
        val values = SpaceRole.entries.map { light.space(it).value }
        assertEquals(values.sorted(), values, "spacing is not monotonic: $values")
        assertEquals(0f, light.space(SpaceRole.None).value)
        assertEquals(16f, light.space(SpaceRole.Md).value)
    }

    @Test
    fun fr13_raising_a_surface_leaves_its_colour_alone_and_draws_no_top_stroke() {
        for (system in schemes) {
            val base = system.color(ColorRole.Surface)
            val raised = system.elevation(8.dp, base)
            assertEquals(base, raised.surface, "Apple never tints a raised surface")
            assertNull(raised.strokeTop, "the lit hairline across the top belongs to Fluent")
            assertTrue(
                raised.shadowElevation > 8.dp,
                "an Apple shadow spreads wider than its nominal height",
            )
        }
    }

    @Test
    fun fr13_a_surface_at_rest_casts_nothing() {
        val flat = light.elevation(0.dp, light.color(ColorRole.Surface))
        assertEquals(0.dp, flat.shadowElevation)
        assertNull(flat.strokeTop)
    }

    @Test
    fun fr13_the_shadow_deepens_for_dark_where_a_faint_one_would_vanish() {
        assertTrue(
            dark.elevation(8.dp, Color.Black).shadowColor.alpha >
                light.elevation(8.dp, Color.White).shadowColor.alpha,
        )
    }

    @Test
    fun fr14_no_cupertino_button_ripples_in_either_scheme() {
        for (system in schemes) {
            for (variant in ButtonVariant.entries) {
                assertFalse(
                    system.button(variant).ripple,
                    "$variant should dim on press, Apple has no ripple",
                )
            }
        }
    }

    @Test
    fun fr14_pressing_any_button_dims_its_label() {
        for (system in schemes) {
            for (variant in ButtonVariant.entries) {
                val style = system.button(variant)
                assertTrue(
                    style.pressedContent.alpha < style.content.alpha,
                    "$variant does not dim when isDark=${system.isDark}",
                )
            }
        }
    }

    @Test
    fun fr14_the_four_weights_are_a_tint_fill_a_grey_fill_a_stroke_and_nothing() {
        for (system in schemes) {
            val filled = system.button(ButtonVariant.Filled)
            val tonal = system.button(ButtonVariant.Tonal)
            val outlined = system.button(ButtonVariant.Outlined)
            val text = system.button(ButtonVariant.Text)

            assertEquals(system.color(ColorRole.Primary), filled.container)
            assertEquals(system.color(ColorRole.OnPrimary), filled.content)
            assertNull(filled.border, "a prominent Apple button has no stroke")

            assertNotEquals(filled.container, tonal.container)
            assertEquals(
                system.color(ColorRole.Primary),
                tonal.content,
                "the grey button keeps the tint on its label",
            )
            assertNull(tonal.border)

            assertEquals(Color.Transparent, outlined.container)
            assertEquals(system.color(ColorRole.Primary), outlined.border)
            assertEquals(1.dp, outlined.borderWidth)

            assertEquals(Color.Transparent, text.container)
            assertNull(text.border, "a plain button is a label and nothing else")
        }
    }

    @Test
    fun fr14_a_press_is_quicker_than_the_release_that_follows_it() {
        for (system in schemes) {
            assertTrue(system.motion.pressMillis > 0)
            assertTrue(
                system.motion.releaseMillis > system.motion.pressMillis,
                "the press lands with the finger, the release is allowed to be seen",
            )
        }
    }

    @Test
    fun fr14_container_surfaces_are_glass_and_the_canvas_is_not() {
        for (system in schemes) {
            for (role in listOf(ColorRole.Surface, ColorRole.SurfaceVariant)) {
                assertTrue(
                    system.material(role) is SurfaceMaterial.Glass,
                    "$role should be a material when isDark=${system.isDark}",
                )
            }
            assertTrue(
                system.material(ColorRole.Background) is SurfaceMaterial.Opaque,
                "glass over nothing is a tinted rectangle that costs a blur pass",
            )
            assertTrue(system.material(ColorRole.Primary) is SurfaceMaterial.Opaque)
        }
    }

    @Test
    fun fr14_the_glass_fallback_meets_wcag_aa_body_contrast_in_both_schemes() {
        val contents = mapOf(
            ColorRole.Surface to ColorRole.OnSurface,
            ColorRole.SurfaceVariant to ColorRole.OnSurfaceVariant,
        )
        for (system in schemes) {
            for ((container, content) in contents) {
                val glass = system.material(container) as SurfaceMaterial.Glass
                val ratio = contrastRatio(glass.fallback, system.color(content))
                assertTrue(
                    ratio >= LiquidGlass.MIN_CONTRAST_BODY,
                    "$container falls back to a colour reading at $ratio against $content " +
                        "when isDark=${system.isDark}, below the 4.5 body minimum",
                )
                assertEquals(1f, glass.fallback.alpha, "a fallback that is see-through promises nothing")
            }
        }
    }

    @Test
    fun fr14_reduced_transparency_takes_the_opaque_path_on_every_cupertino_surface() {
        for (system in schemes) {
            for (role in listOf(ColorRole.Surface, ColorRole.SurfaceVariant)) {
                val glass = system.material(role) as SurfaceMaterial.Glass
                assertEquals(
                    glass.fallback,
                    glassFill(glass, reduceTransparency = true, blurAvailable = true),
                    "$role stayed translucent with reduced transparency asked for",
                )
                assertEquals(
                    glass.fallback,
                    glassFill(glass, reduceTransparency = false, blurAvailable = false),
                    "$role stayed translucent on a build that cannot blur",
                )
                assertTrue(
                    glassFill(glass, reduceTransparency = false, blurAvailable = true).alpha < 1f,
                    "$role is not translucent when nothing asked it to stop",
                )
            }
        }
    }

    @Test
    fun fr14_the_dark_glass_is_darker_and_both_are_edge_lit_from_above() {
        val lightGlass = light.material(ColorRole.Surface) as SurfaceMaterial.Glass
        val darkGlass = dark.material(ColorRole.Surface) as SurfaceMaterial.Glass
        assertTrue(relativeLuminance(darkGlass.tint) < relativeLuminance(lightGlass.tint))
        for (glass in listOf(lightGlass, darkGlass)) {
            assertTrue(
                relativeLuminance(glass.highlight) > relativeLuminance(glass.shade),
                "the top edge catches the light, the bottom falls into shadow",
            )
            assertTrue(glass.blurRadius > 0.dp)
        }
    }

    @Test
    fun fr14_the_recessed_region_lets_more_through_than_the_card_around_it() {
        for (system in schemes) {
            val card = system.material(ColorRole.Surface) as SurfaceMaterial.Glass
            val recessed = system.material(ColorRole.SurfaceVariant) as SurfaceMaterial.Glass
            assertTrue(
                recessed.tintAlpha < card.tintAlpha,
                "a region inside a card should read as a shade of it, not as a second card",
            )
        }
    }
}
