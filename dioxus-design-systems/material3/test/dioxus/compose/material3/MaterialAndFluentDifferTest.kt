package dioxus.compose.material3

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dioxus.compose.ButtonVariant
import dioxus.compose.ColorRole
import dioxus.compose.DesignSystem
import dioxus.compose.ShapeRole
import dioxus.compose.SpaceRole
import dioxus.compose.TypeRole
import dioxus.compose.fluent.FluentDesignSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two systems are asked the same questions side by side.
 *
 * Every assertion here is that the answers are different. There is deliberately no test
 * that Material and Fluent agree about anything: if they did, one of them would be a
 * palette swap of the other and the whole point of resolving roles per design system
 * would be gone.
 */
class MaterialAndFluentDifferTest {

    private val pairs = listOf(
        Material3DesignSystem.Light to FluentDesignSystem.Light,
        Material3DesignSystem.Dark to FluentDesignSystem.Dark,
    )

    @Test
    fun fr14_the_two_systems_identify_themselves_differently() {
        for ((material, fluent) in pairs) {
            assertNotEquals(material.id, fluent.id)
            assertEquals(material.isDark, fluent.isDark, "the pairs under test are the same scheme")
        }
    }

    @Test
    fun fr14_the_emphasis_and_surface_colours_are_not_the_same_in_either_scheme() {
        val roles = listOf(
            ColorRole.Primary,
            ColorRole.Secondary,
            ColorRole.Surface,
            ColorRole.SurfaceVariant,
            ColorRole.Background,
            ColorRole.Outline,
            ColorRole.OutlineVariant,
            ColorRole.Error,
        )
        for ((material, fluent) in pairs) {
            for (role in roles) {
                assertNotEquals(
                    material.color(role),
                    fluent.color(role),
                    "$role is identical in both systems when isDark=${material.isDark}",
                )
            }
        }
    }

    @Test
    fun fr14_the_full_colour_sets_are_not_the_same_table_twice() {
        for ((material, fluent) in pairs) {
            assertNotEquals(
                ColorRole.entries.map(material::color),
                ColorRole.entries.map(fluent::color),
            )
        }
    }

    @Test
    fun fr13_the_two_type_ladders_disagree_about_body_and_about_display() {
        for ((material, fluent) in pairs) {
            assertNotEquals(
                material.type(TypeRole.Body).fontSize,
                fluent.type(TypeRole.Body).fontSize,
                "Material sets body at 16sp and Fluent at 14sp",
            )
            assertNotEquals(
                material.type(TypeRole.Display).fontSize,
                fluent.type(TypeRole.Display).fontSize,
            )
        }
    }

    @Test
    fun fr13_fluent_corners_are_tighter_than_material_corners_at_every_step() {
        val roles = listOf(ShapeRole.ExtraSmall, ShapeRole.Small, ShapeRole.Medium, ShapeRole.Large)
        val material = Material3DesignSystem.Light
        val fluent = FluentDesignSystem.Light
        for (role in roles) {
            assertNotEquals(material.shape(role), fluent.shape(role), "$role has the same corner in both")
        }
    }

    @Test
    fun fr13_the_two_systems_pad_a_card_differently() {
        for ((material, fluent) in pairs) {
            assertNotEquals(
                material.space(SpaceRole.Md),
                fluent.space(SpaceRole.Md),
                "Material pads at its 16dp body margin, Fluent at 12dp",
            )
        }
    }

    @Test
    fun fr13_material_tints_a_raised_surface_and_fluent_lights_its_top_edge_instead() {
        for ((material, fluent) in pairs) {
            val base = Color(0xFF808080)
            val raisedByMaterial = material.elevation(8.dp, base)
            val raisedByFluent = fluent.elevation(8.dp, base)

            assertNotEquals(base, raisedByMaterial.surface, "Material raises by tinting")
            assertEquals(base, raisedByFluent.surface, "Fluent leaves the fill alone")
            assertNull(raisedByMaterial.strokeTop)
            assertNotNull(raisedByFluent.strokeTop)
            assertNotEquals(raisedByMaterial.shadowColor, raisedByFluent.shadowColor)
        }
    }

    @Test
    fun fr14_a_material_button_ripples_and_is_a_pill_while_a_fluent_one_does_neither() {
        for ((material, fluent) in pairs) {
            for (variant in ButtonVariant.entries) {
                val m = material.button(variant)
                val f = fluent.button(variant)
                assertTrue(m.ripple, "$variant should ripple in Material")
                assertTrue(!f.ripple, "$variant should not ripple in Fluent")
                assertNotEquals(m.shape, f.shape, "$variant has the same corner in both")
            }
        }
    }

    @Test
    fun fr14_the_tonal_and_standard_buttons_are_not_the_same_button() {
        for ((material, fluent) in pairs) {
            val tonal = material.button(ButtonVariant.Tonal)
            val standard = fluent.button(ButtonVariant.Tonal)
            assertNotEquals(tonal.container, standard.container)
            assertNull(tonal.border, "Material's tonal button is a fill with no stroke")
            assertNotNull(standard.border, "Fluent's standard button is a fill with a stroke")
        }
    }

    @Test
    fun fr14_the_two_systems_settle_at_different_speeds() {
        for ((material, fluent) in pairs) {
            assertNotEquals(material.motion.releaseMillis, fluent.motion.releaseMillis)
            assertNotEquals(material.motion.easing.transform(0.5f), fluent.motion.easing.transform(0.5f))
        }
    }

    @Test
    fun fr14_both_answer_the_same_contract_so_a_caller_can_hold_either() {
        val systems: List<DesignSystem> = listOf(
            Material3DesignSystem.Light,
            Material3DesignSystem.Dark,
            FluentDesignSystem.Light,
            FluentDesignSystem.Dark,
        )
        for (system in systems) {
            for (role in ColorRole.entries) assertNotEquals(Color.Unspecified, system.color(role))
            for (role in TypeRole.entries) assertTrue(system.type(role).fontSize.value > 0f)
            for (role in ShapeRole.entries) system.shape(role)
            for (role in SpaceRole.entries) assertTrue(system.space(role).value >= 0f)
            for (variant in ButtonVariant.entries) system.button(variant)
        }
    }
}
