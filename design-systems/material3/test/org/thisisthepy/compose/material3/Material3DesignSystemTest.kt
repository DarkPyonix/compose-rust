package org.thisisthepy.compose.material3

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Material3DesignSystemTest {

    private val light = Material3DesignSystem.Light
    private val dark = Material3DesignSystem.Dark

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
    fun fr14_surface_is_dark_and_its_foreground_is_light_only_in_the_dark_instance() {
        assertTrue(light.color(ColorRole.Surface).relativeLightness() > light.color(ColorRole.OnSurface).relativeLightness())
        assertTrue(dark.color(ColorRole.Surface).relativeLightness() < dark.color(ColorRole.OnSurface).relativeLightness())
    }

    @Test
    fun fr14_is_dark_is_a_property_of_the_instance_rather_than_an_argument() {
        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertEquals(DesignSystemId.Material3, light.id)
        assertEquals(DesignSystemId.Material3, dark.id)
    }

    @Test
    fun fr13_every_type_role_answers_with_a_sized_style_in_both_schemes() {
        for (system in listOf(light, dark)) {
            for (role in TypeRole.entries) {
                val style = system.type(role)
                assertTrue(style.fontSize.isSpecified, "$role has no size")
                assertTrue(style.fontSize.value > 0f, "$role is sized at zero")
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
    fun fr13_body_strong_is_body_at_a_heavier_weight_and_the_same_size() {
        val body = light.type(TypeRole.Body)
        val strong = light.type(TypeRole.BodyStrong)
        assertEquals(body.fontSize, strong.fontSize)
        assertTrue((strong.fontWeight?.weight ?: 0) > (body.fontWeight?.weight ?: FontWeight.Normal.weight))
    }

    @Test
    fun fr13_mono_asks_for_a_monospaced_family_because_material_names_no_mono_style() {
        assertEquals(FontFamily.Monospace, light.type(TypeRole.Mono).fontFamily)
    }

    @Test
    fun fr13_every_shape_role_answers_and_none_is_square() {
        for (role in ShapeRole.entries) {
            light.shape(role)
        }
        assertEquals(light.shape(ShapeRole.None), dark.shape(ShapeRole.None))
    }

    @Test
    fun fr13_the_space_ladder_is_the_four_dp_grid_and_increases() {
        val values = SpaceRole.entries.map { light.space(it).value }
        assertEquals(values.sorted(), values, "spacing is not monotonic: $values")
        assertEquals(0f, light.space(SpaceRole.None).value)
        for (value in values) {
            assertEquals(0f, value % 4f, "$value dp is off the four dp grid")
        }
    }

    @Test
    fun fr13_raising_a_surface_tints_it_toward_primary_and_casts_a_shadow() {
        val base = light.color(ColorRole.Surface)
        val flat = light.elevation(0.dp, base)
        val raised = light.elevation(8.dp, base)
        assertEquals(base, flat.surface, "a surface at zero height must not be tinted")
        assertNotEquals(base, raised.surface, "Material raises by tinting, and this surface was left alone")
        assertEquals(8.dp, raised.shadowElevation)
        assertEquals(Color.Black, raised.shadowColor)
    }

    @Test
    fun fr13_the_tint_deepens_as_the_surface_rises() {
        val base = light.color(ColorRole.Surface)
        val low = light.elevation(1.dp, base).surface
        val high = light.elevation(12.dp, base).surface
        assertNotEquals(low, high)
        assertTrue(
            distance(base, high) > distance(base, low),
            "12dp should be tinted further from the base than 1dp",
        )
    }

    @Test
    fun fr14_material_never_draws_a_lit_top_edge() {
        assertNull(light.elevation(8.dp, light.color(ColorRole.Surface)).strokeTop)
        assertNull(dark.elevation(24.dp, dark.color(ColorRole.Surface)).strokeTop)
    }

    @Test
    fun fr14_the_two_filled_variants_differ_and_the_other_two_carry_no_fill() {
        for (system in listOf(light, dark)) {
            assertNotEquals(
                system.button(ButtonVariant.Filled).container,
                system.button(ButtonVariant.Tonal).container,
            )
            assertEquals(Color.Transparent, system.button(ButtonVariant.Outlined).container)
            assertEquals(Color.Transparent, system.button(ButtonVariant.Text).container)
            // An operator key borrows the tonal fill rather than inventing a fifth one.
            // Material has no operator button, and a colour made up for one would be a
            // colour this table does not hold.
            assertEquals(
                system.button(ButtonVariant.Tonal).container,
                system.button(ButtonVariant.Operator).container,
            )
        }
    }

    @Test
    fun fr14_only_the_outlined_variant_carries_a_border() {
        assertNull(light.button(ButtonVariant.Filled).border)
        assertNull(light.button(ButtonVariant.Tonal).border)
        assertNull(light.button(ButtonVariant.Text).border)
        val outlined = light.button(ButtonVariant.Outlined)
        assertNotEquals(null, outlined.border)
        assertEquals(1.dp, outlined.borderWidth)
    }

    @Test
    fun fr14_every_material_button_ripples_and_is_fully_rounded() {
        for (variant in ButtonVariant.entries) {
            val style = light.button(variant)
            assertTrue(style.ripple, "$variant should ripple, that is how Material reports a press")
            assertEquals(ShapeRole.Full, style.shape)
        }
    }

    @Test
    fun fr14_pressing_changes_the_container_and_leaves_the_label_alone() {
        val filled = light.button(ButtonVariant.Filled)
        assertNotEquals(filled.container, filled.pressedContainer)
        assertEquals(filled.content, filled.pressedContent)
    }

    @Test
    fun fr14_material_surfaces_are_opaque_fills_rather_than_glass() {
        val material = light.material(ColorRole.Surface)
        assertTrue(material is SurfaceMaterial.Opaque)
        assertEquals(light.color(ColorRole.Surface), material.color)
    }

    @Test
    fun fr14_motion_has_a_duration_and_an_easing_in_both_schemes() {
        for (system in listOf(light, dark)) {
            assertTrue(system.motion.pressMillis > 0)
            assertTrue(system.motion.releaseMillis > 0)
            assertNotEquals(0f, system.motion.easing.transform(0.5f))
        }
    }
}

internal fun Color.relativeLightness(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun distance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return dr * dr + dg * dg + db * db
}
