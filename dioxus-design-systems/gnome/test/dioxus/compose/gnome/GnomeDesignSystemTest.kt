package dioxus.compose.gnome

import dioxus.compose.ButtonVariant
import dioxus.compose.ColorRole
import dioxus.compose.DesignSystem
import dioxus.compose.ShapeRole
import dioxus.compose.SpaceRole
import dioxus.compose.TypeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The roles this system has to answer, and the marks that make it itself.
 */
class GnomeDesignSystemTest {

    private val schemes = listOf(GnomeDesignSystem.Light, GnomeDesignSystem.Dark)

    @Test
    fun fr14_every_color_role_answers_in_light_and_dark() {
        for (system in schemes) {
            for (role in ColorRole.entries) {
                val color = system.color(role)
                assertTrue(
                    color.alpha > 0f,
                    "$role resolved to a fully transparent colour in the " +
                        "${schemeName(system)} scheme, which would draw nothing",
                )
            }
        }
    }

    @Test
    fun fr14_every_type_role_answers_in_light_and_dark() {
        for (system in schemes) {
            for (role in TypeRole.entries) {
                val style = system.type(role)
                assertTrue(style.fontSize.value > 0f, "$role has no font size")
                assertTrue(style.lineHeight.value >= style.fontSize.value, "$role sets a line height under its font size")
            }
        }
    }

    @Test
    fun fr14_every_shape_and_space_role_answers_in_light_and_dark() {
        for (system in schemes) {
            val shapes = ShapeRole.entries.map { system.shape(it) }
            assertEquals(ShapeRole.entries.size, shapes.size)
            for (role in SpaceRole.entries) {
                assertTrue(system.space(role).value >= 0f, "$role resolved to a negative gap")
            }
        }
    }

    @Test
    fun fr14_every_button_variant_answers_in_light_and_dark() {
        for (system in schemes) {
            for (variant in ButtonVariant.entries) {
                val style = system.button(variant)
                assertTrue(
                    style.content.alpha > 0f,
                    "a $variant button would draw invisible text in the ${schemeName(system)} scheme",
                )
                val resting = listOf(style.container, style.content, style.border)
                val pressed = listOf(style.pressedContainer, style.pressedContent, style.pressedBorder)
                assertNotEquals(
                    resting,
                    pressed,
                    "a $variant button looks the same pressed as it does at rest, so a click " +
                        "would give no feedback",
                )
            }
        }
    }

    @Test
    fun fr14_dark_scheme_is_not_the_light_scheme() {
        val differing = ColorRole.entries.count {
            GnomeDesignSystem.Light.color(it) != GnomeDesignSystem.Dark.color(it)
        }
        assertTrue(differing >= ColorRole.entries.size / 2, "only $differing colour roles change between schemes")
        assertTrue(GnomeDesignSystem.Dark.isDark)
        assertTrue(!GnomeDesignSystem.Light.isDark)
    }

    @Test
    fun fr14_space_ladder_grows_with_each_step() {
        val ladder = SpaceRole.entries.map { GnomeDesignSystem.Light.space(it).value }
        assertEquals(ladder.sorted(), ladder, "the spacing ladder is not monotonic")
        assertTrue(ladder.last() > ladder.first(), "the spacing ladder does not grow")
    }

    @Test
    fun fr14_type_ladder_shrinks_from_display_to_caption() {
        val system = GnomeDesignSystem.Light
        val descending = listOf(TypeRole.Display, TypeRole.Headline, TypeRole.Title, TypeRole.Subtitle, TypeRole.Body, TypeRole.Caption)
            .map { system.type(it).fontSize.value }
        assertEquals(descending.sortedDescending(), descending, "the type ladder is not monotonic")
    }

    private fun schemeName(system: DesignSystem): String = if (system.isDark) "dark" else "light"
}
