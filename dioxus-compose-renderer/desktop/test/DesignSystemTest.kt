package dioxus.compose.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dioxus.compose.design.NavigationPresentation
import dioxus.compose.protocol.IconRole
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.protocol.WindowSizeClass
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.adaptiveSystem
import dioxus.compose.design.detectHostPlatform
import dioxus.compose.design.resolveTheme
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag

private const val ROW = 1
private const val LEFT = 2
private const val RIGHT = 3
private const val BUTTON = 4
private const val LABEL = 5

private fun theme(
    system: DesignSystem,
    scheme: ColorScheme = ColorScheme.Light,
    adaptive: Boolean = false,
    fallback: DesignSystem = DesignSystem.Material3,
) = Theme(system, fallback, scheme, adaptive)

private fun resolved(system: DesignSystem, dark: Boolean = false) =
    resolveTheme(theme(system, if (dark) ColorScheme.Dark else ColorScheme.Light), HostPlatform.MacOs, false)

class ThemeResolutionTest {
    @Test
    fun fr14_3_unified_uses_one_system_on_every_platform() {
        val unified = theme(DesignSystem.Material3)
        listOf(HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.LinuxGnome).forEach { platform ->
            assertEquals(
                DesignSystem.Material3,
                resolveTheme(unified, platform, systemDark = false).system,
                "unified must not follow $platform",
            )
        }
    }

    @Test
    fun fr14_3_adaptive_follows_the_host_platform() {
        val adaptive = theme(DesignSystem.Material3, adaptive = true, fallback = DesignSystem.Material3)
        assertEquals(DesignSystem.LiquidGlass, resolveTheme(adaptive, HostPlatform.MacOs, false).system)
        assertEquals(DesignSystem.LiquidGlass, resolveTheme(adaptive, HostPlatform.Ios, false).system)
        assertEquals(DesignSystem.Fluent, resolveTheme(adaptive, HostPlatform.Windows, false).system)
        assertEquals(DesignSystem.Material3, resolveTheme(adaptive, HostPlatform.Android, false).system)
        assertEquals(DesignSystem.Fluent, resolveTheme(adaptive, HostPlatform.Web, false).system)
    }

    @Test
    fun fr14_3_cupertino_is_still_reachable_by_name() {
        // Apple's two languages are both current. Adaptive answers with the one the
        // machine is actually running, and an app that wants the other one says so.
        val named = theme(DesignSystem.Cupertino)
        assertEquals(DesignSystem.Cupertino, resolveTheme(named, HostPlatform.MacOs, false).system)
        assertEquals(DesignSystem.Cupertino, resolveTheme(named, HostPlatform.Ios, false).system)
    }

    @Test
    fun fr14_3_adaptive_follows_the_linux_desktop_session() {
        val adaptive = theme(DesignSystem.Material3, adaptive = true, fallback = DesignSystem.Fluent)
        assertEquals(DesignSystem.Gnome, resolveTheme(adaptive, HostPlatform.LinuxGnome, false).system)
        assertEquals(DesignSystem.Breeze, resolveTheme(adaptive, HostPlatform.LinuxKde, false).system)
        assertEquals(DesignSystem.Deepin, resolveTheme(adaptive, HostPlatform.LinuxOther, false).system)
    }

    @Test
    fun fr14_3_adaptive_falls_back_where_a_platform_has_no_look_of_its_own() {
        val adaptive = theme(DesignSystem.Material3, adaptive = true, fallback = DesignSystem.Fluent)
        listOf(HostPlatform.Unknown)
            .forEach { platform ->
                assertEquals(DesignSystem.Fluent, resolveTheme(adaptive, platform, false).system)
            }
    }

    @Test
    fun fr14_3_saying_nothing_follows_the_platform() {
        // No SetTheme at all follows the host platform. The default used to be unified
        // Material 3, which meant a Windows machine with no theme set drew a Material
        // window and nothing in the default path ever exercised platform adaptation.
        assertEquals(DesignSystem.Fluent, resolveTheme(null, HostPlatform.Windows, false).system)
        assertEquals(DesignSystem.LiquidGlass, resolveTheme(null, HostPlatform.MacOs, false).system)
        assertEquals(DesignSystem.Material3, resolveTheme(null, HostPlatform.Android, false).system)
        // Material 3 remains the fallback where a platform has no look of its own.
        assertEquals(DesignSystem.Material3, resolveTheme(null, HostPlatform.Unknown, false).system)
        assertEquals(false, resolveTheme(null, HostPlatform.Windows, false).dark)
    }

    @Test
    fun fr14_3_color_scheme_follows_the_host_choice_and_the_system() {
        assertTrue(resolveTheme(theme(DesignSystem.Material3, ColorScheme.Dark), HostPlatform.MacOs, false).dark)
        assertTrue(!resolveTheme(theme(DesignSystem.Material3, ColorScheme.Light), HostPlatform.MacOs, true).dark)
        assertTrue(
            resolveTheme(theme(DesignSystem.Material3, ColorScheme.FollowSystem), HostPlatform.MacOs, true).dark,
        )
    }

    @Test
    fun fr14_3_linux_desktop_comes_from_the_xdg_environment() {
        assertEquals(
            HostPlatform.LinuxGnome,
            detectHostPlatform("Linux", null, "ubuntu:GNOME", null),
        )
        assertEquals(HostPlatform.LinuxKde, detectHostPlatform("Linux", null, "KDE", null))
        assertEquals(HostPlatform.LinuxKde, detectHostPlatform("Linux", null, "", "plasmakde"))
        assertEquals(HostPlatform.LinuxOther, detectHostPlatform("Linux", null, null, null))
        assertEquals(HostPlatform.MacOs, detectHostPlatform("Mac OS X", null, null, null))
        assertEquals(HostPlatform.Windows, detectHostPlatform("Windows 11", null, null, null))
    }

    /**
     * A browser tab reports `web` and nothing else, because the machine underneath is not
     * what decides. The same page opened on a Mac and on a Windows box has to look the
     * same, so the platform is the browser rather than what it is running on.
     */
    @Test
    fun pr6_a_browser_reports_itself_as_the_web_platform() {
        assertEquals(HostPlatform.Web, detectHostPlatform("web", null, null, null))
        assertEquals(DesignSystem.Fluent, adaptiveSystem(HostPlatform.Web, DesignSystem.Material3))
    }

    /**
     * Android reports its operating system as Linux, so the runtime's vendor is what tells
     * the two apart. Reading the operating system first would put a phone on the Linux
     * branch and hand it whatever desktop the environment claimed.
     */
    @Test
    fun fr14_3_android_is_recognised_by_its_runtime_rather_than_its_operating_system() {
        assertEquals(
            HostPlatform.Android,
            detectHostPlatform("Linux", "The Android Project", null, null),
        )
    }
}

class DesignTokenWiringTest {
    @Test
    fun fr14_1_every_system_resolves_all_four_generated_tables() {
        DesignSystem.entries.forEach { system ->
            listOf(false, true).forEach { dark ->
                val theme = resolved(system, dark)
                ColorRole.entries.forEach { role ->
                    assertNotEquals(0, theme.color(role).alpha.compareTo(0f), "$system $role is transparent")
                }
                TypeRole.entries.forEach { role ->
                    assertTrue(theme.type(role).size > 0f, "$system $role has no size")
                }
                SpaceRole.entries.forEach { role -> assertTrue(theme.space(role).value >= 0f) }
                ShapeRole.entries.forEach { role -> assertTrue(theme.radius(role).value >= 0f) }
            }
        }
    }

    @Test
    fun fr14_3_light_and_dark_differ_for_every_system() {
        DesignSystem.entries.forEach { system ->
            assertNotEquals(
                resolved(system, dark = false).color(ColorRole.Surface),
                resolved(system, dark = true).color(ColorRole.Surface),
                "$system has the same Surface in light and dark",
            )
        }
    }

    @Test
    fun fr13_1_a_role_and_a_literal_both_paint() {
        val theme = resolved(DesignSystem.Material3)
        assertEquals(theme.color(ColorRole.Primary), theme.color(Paint.Role(ColorRole.Primary)))
        assertEquals(Color(0xff123456.toInt()), theme.color(Paint.Literal(0xff123456.toInt())))
    }

    @Test
    fun fr14_2_button_variants_differ_in_shape_weight_and_feedback_between_systems() {
        val material = resolved(DesignSystem.Material3)
        val hig = resolved(DesignSystem.Cupertino)
        val fluent = resolved(DesignSystem.Fluent)
        val variant = ButtonVariant.Filled
        val m = material.rules.button(variant, material)
        val h = hig.rules.button(variant, hig)
        val f = fluent.rules.button(variant, fluent)

        // Shape: Material is a pill, HIG and Fluent are rounded rectangles of their own radius.
        assertNotEquals(m.shape, h.shape)
        assertNotEquals(h.shape, f.shape)

        // Weight: the label rung differs, so the same text is not set the same way.
        assertNotEquals(
            material.type(m.typeRole).weight,
            hig.type(h.typeRole).weight,
        )

        // Feedback: Material layers a colour and rises, HIG dims, Fluent only shades.
        assertTrue(m.pressedElevation > m.restElevation, "Material press does not rise")
        assertEquals(0.dp, h.pressedElevation)
        assertTrue(h.pressedContentAlpha < 1f, "HIG press does not dim")
        assertEquals(1f, f.pressedContentAlpha)
        assertNotEquals(f.container, f.pressedContainer)
        assertTrue(f.topHighlight != null, "Fluent has no top edge highlight")
        assertTrue(m.topHighlight == null && h.topHighlight == null)

        // Motion is a design system rule too: transition duration and easing are part of
        // the table each system fills in, not something a caller sends.
        assertTrue(fluent.rules.motion.pressMillis < material.rules.motion.pressMillis)
    }

    @Test
    fun fr14_2_every_variant_is_distinct_within_a_system() {
        DesignSystem.entries.forEach { system ->
            val theme = resolved(system)
            val styles = ButtonVariant.entries
                .filterNot { it == ButtonVariant.Operator }
                .map { theme.rules.button(it, theme) }
            assertEquals(styles.size, styles.distinct().size, "$system draws two variants identically")
        }
    }

    @Test
    fun fr22_operator_keys_follow_the_calculator_language_of_each_system() {
        val cupertino = resolved(DesignSystem.Cupertino)
        val cupertinoOperator = cupertino.rules.button(ButtonVariant.Operator, cupertino)
        assertEquals(
            cupertino.rules.button(ButtonVariant.Filled, cupertino),
            cupertinoOperator,
        )

        val fluent = resolved(DesignSystem.Fluent)
        val fluentOperator = fluent.rules.button(ButtonVariant.Operator, fluent)
        assertEquals(fluent.rules.button(ButtonVariant.Tonal, fluent), fluentOperator)
        assertNotEquals(fluent.rules.button(ButtonVariant.Filled, fluent).container, fluentOperator.container)

        val deepin = resolved(DesignSystem.Deepin)
        val deepinOperator = deepin.rules.button(ButtonVariant.Operator, deepin)
        val deepinNumber = deepin.rules.button(ButtonVariant.Tonal, deepin)
        assertEquals(deepinNumber.container, deepinOperator.container)
        assertEquals(deepin.color(ColorRole.Primary), deepinOperator.content)
        assertNotEquals(deepinNumber.content, deepinOperator.content)
    }

    /**
     * The sidebar's measurements, all four read off the reference window.
     *
     * Asserted together because they are one measurement: the icon column is where the
     * strip's own inset and the room inside a row add up to, and the row pitch is the icon
     * plus the room above and below it with nothing between one row and the next.
     */
    @Test
    fun fr21_2_1_a_glass_sidebar_is_measured_off_the_reference() {
        val glass = resolved(DesignSystem.LiquidGlass)
        val style = glass.rules.navigation(WindowSizeClass.Expanded, glass)
        assertEquals(NavigationPresentation.Drawer, style.presentation)
        val icon = glass.rules.icon(IconRole.Search, glass).size
        val padding = style.itemPadding
        assertEquals(32.dp, icon + padding * 2, "a row is not as tall as the reference's")
        assertEquals(0.dp, style.destinationGap, "the rows do not touch")
        val column = (style.stripPadding ?: padding) + (style.destinationInset ?: padding)
        assertEquals(18.dp, column, "the icon column is not where the reference's is")
        assertEquals(12.dp, style.itemSpacing, "the label does not clear its icon")
    }

    /**
     * The mark behind the row you are on is the quietest state in the system, and quieter
     * than any button. A fifth of black over a white panel came out a solid grey slab.
     */
    @Test
    fun fr21_2_1_the_selected_sidebar_row_is_quieter_than_a_button() {
        val glass = resolved(DesignSystem.LiquidGlass)
        val style = glass.rules.navigation(WindowSizeClass.Expanded, glass)
        val tonal = glass.rules.button(ButtonVariant.Tonal, glass)
        assertTrue(
            style.indicator.alpha < tonal.container.alpha,
            "the selected row is as loud as a tinted button",
        )
    }

    @Test
    fun fr22_liquid_glass_navigation_has_a_translucent_gradient_and_search_pill() {
        val glass = resolved(DesignSystem.LiquidGlass)
        val style = glass.rules.navigation(WindowSizeClass.Expanded, glass)
        assertTrue(style.container.alpha < 1f, "the drawer hides the page behind it")
        assertEquals(glass.color(ColorRole.Background), style.pageGradientStart)
        // The foot of the page is the container role carried back towards the accent it is
        // a tint of, so it is deeper than the container on its own and still short of the
        // accent: the palest tint alone reads as a fill rather than as light.
        val container = glass.color(ColorRole.PrimaryContainer)
        val accent = glass.color(ColorRole.Primary)
        val foot = style.pageGradientEnd
        assertTrue(foot != null, "the page has no foot colour")
        assertTrue(
            foot!!.red < container.red && foot.red > accent.red,
            "the foot is not between the container and the accent",
        )
        assertTrue(
            foot.green < container.green && foot.green > accent.green,
            "the foot is not between the container and the accent",
        )
        // And the light that rises from it, without which the wash is one colour across the
        // whole window and reads as a fill.
        assertTrue(style.pageCornerGlow != null, "the page has no light at its corners")
        assertTrue(style.searchContainer != null, "the search destination has no pill fill")
        // And it is not the mark behind the row you are on. A drawer says "selected" with a
        // fill, so a field drawn as a fill and nothing else is a second thing in the list
        // wearing the same state.
        assertTrue(
            style.searchContainer!!.alpha < style.indicator.alpha,
            "the search field is as heavy as the selected row's mark",
        )
        assertTrue(style.searchOutline != null, "nothing tells the search field from a row")

        val fluent = resolved(DesignSystem.Fluent)
        val flat = fluent.rules.navigation(WindowSizeClass.Expanded, fluent)
        assertEquals(null, flat.pageGradientStart)
        assertEquals(null, flat.pageGradientEnd)
        assertEquals(null, flat.pageCornerGlow)
        assertEquals(null, flat.searchContainer)
        assertEquals(null, flat.searchOutline)
    }
}

@OptIn(ExperimentalTestApi::class)
class DesignRenderTest {
    @Test
    fun fr13_4_weight_splits_a_row_between_its_children() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(theme(DesignSystem.Material3)),
                Mutation.Create(ROW, WidgetKind.Row),
                Mutation.SetModifier(ROW, 0, ProtocolModifier.Width(300f)),
                Mutation.Create(LEFT, WidgetKind.Box),
                Mutation.SetModifier(LEFT, 0, ProtocolModifier.Weight(1f)),
                Mutation.Insert(ROW, LEFT, 0),
                Mutation.Create(RIGHT, WidgetKind.Box),
                Mutation.SetModifier(RIGHT, 0, ProtocolModifier.Weight(3f)),
                Mutation.Insert(ROW, RIGHT, 1),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        onNodeWithTag(nodeTestTag(LEFT)).assertWidthIsEqualTo(75.dp)
        onNodeWithTag(nodeTestTag(RIGHT)).assertWidthIsEqualTo(225.dp)
    }

    @Test
    fun fr13_4_padding_role_measures_by_the_design_system() = runComposeUiTest {
        // The same PaddingRole is a different number of dp per system, which is exactly
        // what a role is for: density is where the three systems disagree, so a caller names
        // the step and the design system picks the dp.
        fun insetFor(system: DesignSystem): Float {
            var inset = 0f
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        Mutation.SetTheme(theme(system)),
                        Mutation.Create(ROW, WidgetKind.Box),
                        Mutation.SetModifier(ROW, 0, ProtocolModifier.PaddingRole(SpaceRole.Xl)),
                        Mutation.Create(LEFT, WidgetKind.Box),
                        Mutation.SetModifier(LEFT, 0, ProtocolModifier.Size(10f, 10f)),
                        Mutation.Insert(ROW, LEFT, 0),
                    ),
                )
                setContent { DioxusContent(rememberDioxusHost(connection)) }
                // The child starts where the resolved padding ends.
                inset = onNodeWithTag(nodeTestTag(LEFT)).getUnclippedBoundsInRoot().left.value
            }
            return inset
        }
        assertNotEquals(insetFor(DesignSystem.Material3), insetFor(DesignSystem.Fluent))
        assertEquals(32f, insetFor(DesignSystem.Material3))
        assertEquals(20f, insetFor(DesignSystem.Fluent))
    }

    @Test
    fun fr14_2_a_button_takes_its_size_from_the_design_system() {
        fun heightFor(system: DesignSystem): Float {
            var height = 0f
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        Mutation.SetTheme(theme(system)),
                        Mutation.Create(BUTTON, WidgetKind.Button),
                        Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text("ok")),
                        Mutation.SetProp(
                            BUTTON,
                            PropertyKind.Variant,
                            PropertyValue.Integer(ButtonVariant.Filled.ordinal + 1L),
                        ),
                    ),
                )
                setContent { DioxusContent(rememberDioxusHost(connection)) }
                height = onNodeWithTag(nodeTestTag(BUTTON)).getUnclippedBoundsInRoot().height.value
            }
            return height
        }
        val material = heightFor(DesignSystem.Material3)
        val fluent = heightFor(DesignSystem.Fluent)
        assertTrue(material >= 40f, "Material button is $material dp tall")
        assertTrue(fluent < material, "Fluent button ($fluent) is not denser than Material ($material)")
    }

    @Test
    fun fr13_2_a_type_role_sizes_the_text() {
        fun heightFor(system: DesignSystem): Float {
            var height = 0f
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        Mutation.SetTheme(theme(system)),
                        Mutation.Create(LABEL, WidgetKind.Text),
                        Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Title")),
                        Mutation.SetProp(
                            LABEL,
                            PropertyKind.TypeRole,
                            PropertyValue.Integer(TypeRole.Display.ordinal + 1L),
                        ),
                    ),
                )
                setContent { DioxusContent(rememberDioxusHost(connection)) }
                height = onNodeWithTag(nodeTestTag(LABEL)).getUnclippedBoundsInRoot().height.value
            }
            return height
        }
        // Material's Display rung is 57 sp, Apple's is 34 sp, so the same Text is not the
        // same size once the design system resolves the role.
        assertTrue(heightFor(DesignSystem.Material3) > heightFor(DesignSystem.Cupertino))
    }

    @Test
    fun fr13_2_an_override_replaces_only_one_axis() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(theme(DesignSystem.Material3)),
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Title")),
                Mutation.SetProp(
                    LABEL,
                    PropertyKind.TypeRole,
                    PropertyValue.Integer(TypeRole.Caption.ordinal + 1L),
                ),
                Mutation.SetProp(LABEL, PropertyKind.FontSize, PropertyValue.Float(40f)),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        // The Caption rung is 12 sp; the override makes it 40 sp and leaves the rest alone.
        onNodeWithTag(nodeTestTag(LABEL)).assertHeightIsAtLeast(30.dp)
    }
}
