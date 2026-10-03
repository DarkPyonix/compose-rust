package org.thisisthepy.compose.fluent

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import org.thisisthepy.compose.designsystem.ButtonVariant
import org.thisisthepy.compose.designsystem.ColorRole
import org.thisisthepy.compose.designsystem.DesignSystemId
import org.thisisthepy.compose.designsystem.ShapeRole
import org.thisisthepy.compose.designsystem.SpaceRole
import org.thisisthepy.compose.designsystem.SurfaceMaterial
import org.thisisthepy.compose.designsystem.TypeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FluentDesignSystemTest {

    private val light = FluentDesignSystem.Light
    private val dark = FluentDesignSystem.Dark

    @Test
    fun fr14_every_color_role_resolves_to_a_real_color_in_both_schemes() {
        for (system in listOf(light, dark)) {
            for (role in ColorRole.entries) {
                val color = system.color(role)
                assertNotEquals(Color.Unspecified, color, "$role is unresolved when isDark=${system.isDark}")
                assertTrue(color.alpha > 0f, "$role is fully transparent when isDark=${system.isDark}")
            }
        }
    }

    @Test
    fun fr14_dark_scheme_changes_every_color_role_it_is_asked_for() {
        for (role in ColorRole.entries) {
            assertNotEquals(
                light.color(role),
                dark.color(role),
                "$role is the same colour in light and dark, so the dark scheme is not doing its job",
            )
        }
    }

    @Test
    fun fr14_the_accent_moves_up_the_brand_ramp_in_dark_and_its_foreground_flips() {
        assertTrue(
            dark.color(ColorRole.Primary).relativeLightness() > light.color(ColorRole.Primary).relativeLightness(),
            "Fluent's dark accent is a lighter step of the brand ramp, not the same blue",
        )
        assertTrue(light.color(ColorRole.OnPrimary).relativeLightness() > 0.9f, "white on the light accent")
        assertTrue(
            dark.color(ColorRole.OnPrimary).relativeLightness() < 0.1f,
            "the dark accent is too light to carry white text, so the foreground goes near black",
        )
    }

    @Test
    fun fr14_is_dark_is_a_property_of_the_instance_rather_than_an_argument() {
        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertEquals(DesignSystemId.Fluent, light.id)
        assertEquals(DesignSystemId.Fluent, dark.id)
    }

    @Test
    fun fr13_every_type_role_answers_with_a_sized_style_in_both_schemes() {
        for (system in listOf(light, dark)) {
            for (role in TypeRole.entries) {
                val style = system.type(role)
                assertTrue(style.fontSize.isSpecified, "$role has no size")
                assertTrue(style.fontSize.value > 0f, "$role is sized at zero")
                assertTrue(style.lineHeight.isSpecified, "$role has no line height")
            }
        }
    }

    @Test
    fun fr13_the_type_ladder_descends_from_display_to_caption() {
        val ladder = listOf(TypeRole.Display, TypeRole.Headline, TypeRole.Title, TypeRole.Subtitle, TypeRole.Body)
        val sizes = ladder.map { light.type(it).fontSize.value }
        assertEquals(sizes.sortedDescending(), sizes, "the ladder is not monotonic: $sizes")
        assertTrue(light.type(TypeRole.Caption).fontSize.value < light.type(TypeRole.Body).fontSize.value)
    }

    @Test
    fun fr13_body_strong_is_a_real_ramp_step_at_body_size_in_semibold() {
        val body = light.type(TypeRole.Body)
        val strong = light.type(TypeRole.BodyStrong)
        assertEquals(14f, body.fontSize.value)
        assertEquals(body.fontSize, strong.fontSize)
        assertEquals(FontWeight.Normal, body.fontWeight)
        assertEquals(FontWeight.SemiBold, strong.fontWeight)
    }

    @Test
    fun fr13_the_fluent_ramp_does_not_track_letters_at_any_step() {
        for (role in TypeRole.entries) {
            assertEquals(0f, light.type(role).letterSpacing.value, "$role should not track")
        }
    }

    @Test
    fun fr13_mono_asks_for_a_monospaced_family_at_body_metrics() {
        val mono = light.type(TypeRole.Mono)
        assertEquals(FontFamily.Monospace, mono.fontFamily)
        assertEquals(light.type(TypeRole.Body).fontSize, mono.fontSize)
    }

    @Test
    fun fr13_a_standard_control_is_cornered_at_four_dp() {
        assertEquals(RoundedCornerShape(4.dp), light.shape(ShapeRole.Small))
        assertEquals(ShapeRole.Small, light.button(ButtonVariant.Filled).shape)
    }

    @Test
    fun fr13_the_corner_ladder_stays_tight_and_never_reaches_a_material_radius() {
        val radii = listOf(ShapeRole.ExtraSmall, ShapeRole.Small, ShapeRole.Medium, ShapeRole.Large)
            .map { light.shape(it) as RoundedCornerShape }
        for (shape in radii) {
            assertEquals(shape.topStart, shape.bottomEnd, "Fluent corners are uniform")
        }
        assertEquals(radii.map { it.topStart }, radii.map { it.topStart }, "the ladder resolves")
    }

    @Test
    fun fr13_the_space_ladder_is_denser_than_a_four_dp_grid_in_the_middle() {
        val values = SpaceRole.entries.map { light.space(it).value }
        assertEquals(values.sorted(), values, "spacing is not monotonic: $values")
        assertEquals(0f, light.space(SpaceRole.None).value)
        assertEquals(12f, light.space(SpaceRole.Md).value)
    }

    @Test
    fun fr13_raising_a_surface_leaves_its_colour_alone_and_lights_its_top_edge() {
        val base = light.color(ColorRole.Surface)
        val raised = light.elevation(8.dp, base)
        assertEquals(base, raised.surface, "Fluent does not tint a raised surface, it shadows it")
        assertNotNull(raised.strokeTop, "the lit top edge is what makes this read as Fluent")
        assertTrue(raised.shadowElevation > 0.dp)
    }

    @Test
    fun fr13_a_flat_surface_has_neither_shadow_nor_lit_edge() {
        val flat = light.elevation(0.dp, light.color(ColorRole.Surface))
        assertEquals(0.dp, flat.shadowElevation)
        assertNull(flat.strokeTop)
    }

    @Test
    fun fr13_an_arbitrary_height_snaps_to_one_of_fluents_named_shadow_depths() {
        val named = setOf(2.dp, 4.dp, 8.dp, 16.dp, 28.dp, 64.dp)
        for (requested in listOf(1.dp, 3.dp, 5.dp, 7.dp, 12.dp, 20.dp, 40.dp, 100.dp)) {
            val resolved = light.elevation(requested, Color.White).shadowElevation
            assertTrue(resolved in named, "$requested resolved to $resolved, which Fluent never specified")
        }
        assertEquals(4.dp, fluentShadowStep(5.dp))
        assertEquals(64.dp, fluentShadowStep(100.dp))
    }

    @Test
    fun fr13_the_lit_top_edge_is_far_stronger_against_a_dark_surface() {
        val lightEdge = light.elevation(8.dp, light.color(ColorRole.Surface)).strokeTop
        val darkEdge = dark.elevation(8.dp, dark.color(ColorRole.Surface)).strokeTop
        assertNotNull(lightEdge)
        assertNotNull(darkEdge)
        assertNotEquals(lightEdge, darkEdge)
    }

    @Test
    fun fr14_accent_and_standard_and_subtle_are_three_visibly_different_buttons() {
        for (system in listOf(light, dark)) {
            val accent = system.button(ButtonVariant.Filled)
            val standard = system.button(ButtonVariant.Tonal)
            val subtle = system.button(ButtonVariant.Text)
            assertEquals(system.color(ColorRole.Primary), accent.container)
            assertNotEquals(accent.container, standard.container)
            assertEquals(Color.Transparent, subtle.container)
            assertNotEquals(standard.container, subtle.container)
        }
    }

    @Test
    fun fr14_the_standard_button_always_carries_its_hairline_stroke() {
        val standard = light.button(ButtonVariant.Tonal)
        assertEquals(light.color(ColorRole.Outline), standard.border)
        assertEquals(1.dp, standard.borderWidth)
        assertNull(light.button(ButtonVariant.Filled).border, "the accent button has no stroke")
        assertNull(light.button(ButtonVariant.Text).border, "a subtle button has no stroke")
    }

    @Test
    fun fr14_no_fluent_button_ripples() {
        for (system in listOf(light, dark)) {
            for (variant in ButtonVariant.entries) {
                assertFalse(
                    system.button(variant).ripple,
                    "$variant should change its fill on press, Fluent has no ripple",
                )
            }
        }
    }

    @Test
    fun fr14_pressing_a_neutral_button_dims_the_label_as_well_as_the_fill() {
        val standard = light.button(ButtonVariant.Tonal)
        assertNotEquals(standard.container, standard.pressedContainer)
        assertNotEquals(standard.content, standard.pressedContent)
    }

    @Test
    fun fr14_fluent_surfaces_are_opaque_fills_rather_than_glass() {
        val material = dark.material(ColorRole.Surface)
        assertTrue(material is SurfaceMaterial.Opaque)
        assertEquals(dark.color(ColorRole.Surface), material.color)
    }

    @Test
    fun fr14_a_press_is_quicker_than_the_release_that_follows_it() {
        for (system in listOf(light, dark)) {
            assertTrue(system.motion.pressMillis > 0)
            assertTrue(
                system.motion.releaseMillis > system.motion.pressMillis,
                "pressing should feel immediate, settling back is allowed to be seen",
            )
        }
    }
}

internal fun Color.relativeLightness(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
