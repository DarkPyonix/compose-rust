package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.contrastRatio
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PaletteEntry
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

private const val BRAND_LIGHT = 0xFFE8590C.toInt()
private const val BRAND_DARK = 0xFFFF8A4C.toInt()

private val BRAND = listOf(
    PaletteEntry(ColorRole.Primary, dark = false, argb = BRAND_LIGHT),
    PaletteEntry(ColorRole.Primary, dark = true, argb = BRAND_DARK),
)

private fun themed(system: DesignSystem, palette: List<PaletteEntry>, scheme: ColorScheme) =
    Theme(system, system, scheme, false, palette = palette)

/**
 * An application's own colours: laid over every design system, read from both schemes
 * without the Host, left alone where they were not given, and set aside for high contrast.
 */
@OptIn(ExperimentalTestApi::class)
class ApplicationColourTest {

    /**
     * A filled button is the brand colour in all seven systems, and still each system's own
     * button: its shape and its pressed state are not the same seven times.
     */
    @Test
    fun fr14_10_a_filled_button_takes_the_application_primary_in_every_system() {
        val shapes = mutableSetOf<Any>()
        val pressed = mutableSetOf<Color>()
        for (system in DesignSystem.entries) {
            for (dark in listOf(false, true)) {
                val theme = resolveTheme(
                    themed(system, BRAND, ColorScheme.FollowSystem),
                    HostPlatform.Unknown,
                    systemDark = dark,
                    highContrast = { false },
                )
                val button = theme.rules.button(ButtonVariant.Filled, theme)
                assertEquals(
                    Color(if (dark) BRAND_DARK else BRAND_LIGHT),
                    button.container,
                    "$system dark=$dark did not fill its button with the application's primary",
                )
                if (!dark) {
                    shapes += button.shape
                    pressed += button.pressedContainer
                }
            }
        }
        assertTrue(shapes.size >= 2, "every system cut the brand button the same way")
        assertTrue(pressed.size >= 2, "every system pressed the brand button the same way")
    }

    /**
     * A role the application did not give is the system's, and a role given for light only
     * is the system's in dark.
     */
    @Test
    fun fr14_10_a_role_left_unsaid_is_the_systems_own() {
        val lightOnly = listOf(PaletteEntry(ColorRole.Secondary, dark = false, argb = 0xFF123456.toInt()))
        for (system in DesignSystem.entries) {
            for (dark in listOf(false, true)) {
                val plain = resolveTheme(
                    themed(system, emptyList(), ColorScheme.FollowSystem),
                    HostPlatform.Unknown,
                    systemDark = dark,
                    highContrast = { false },
                )
                val branded = resolveTheme(
                    themed(system, BRAND + lightOnly, ColorScheme.FollowSystem),
                    HostPlatform.Unknown,
                    systemDark = dark,
                    highContrast = { false },
                )
                assertEquals(plain.color(ColorRole.Outline), branded.color(ColorRole.Outline))
                assertEquals(
                    plain.color(ColorRole.SyntaxKeyword),
                    branded.color(ColorRole.SyntaxKeyword),
                )
                val secondary = branded.color(ColorRole.Secondary)
                if (dark) {
                    assertEquals(plain.color(ColorRole.Secondary), secondary, "$system leaked light into dark")
                } else {
                    assertEquals(Color(0xFF123456), secondary)
                }
            }
        }
    }

    /**
     * A primary given without its ink gets one that reads at 3:1: black on a light yellow,
     * white on a deep navy, where the system's own ink does not already read.
     */
    @Test
    fun fr14_10_an_ink_left_unsaid_is_chosen_to_read_on_the_new_fill() {
        val yellow = listOf(
            PaletteEntry(ColorRole.Primary, false, 0xFFFFE14D.toInt()),
            PaletteEntry(ColorRole.Primary, true, 0xFFFFE14D.toInt()),
        )
        val navy = listOf(
            PaletteEntry(ColorRole.Primary, false, 0xFF0B1F4B.toInt()),
            PaletteEntry(ColorRole.Primary, true, 0xFF0B1F4B.toInt()),
        )
        for (system in DesignSystem.entries) {
            for (dark in listOf(false, true)) {
                fun inkOn(palette: List<PaletteEntry>): Color = resolveTheme(
                    themed(system, palette, ColorScheme.FollowSystem),
                    HostPlatform.Unknown,
                    systemDark = dark,
                    highContrast = { false },
                ).color(ColorRole.OnPrimary)
                val onYellow = inkOn(yellow)
                val onNavy = inkOn(navy)
                assertTrue(contrastRatio(onYellow, Color(0xFFFFE14D)) >= 3.0, "$system $dark: $onYellow on yellow")
                assertTrue(contrastRatio(onNavy, Color(0xFF0B1F4B)) >= 3.0, "$system $dark: $onNavy on navy")
                assertNotEquals(onYellow, onNavy, "$system $dark put one ink on yellow and on navy")
            }
        }
        // Where the system's ink is white, the two answers are black and white.
        val material = { palette: List<PaletteEntry> ->
            resolveTheme(
                themed(DesignSystem.Material3, palette, ColorScheme.Light),
                HostPlatform.Unknown,
                systemDark = false,
                highContrast = { false },
            ).color(ColorRole.OnPrimary)
        }
        assertEquals(Color.Black, material(yellow))
        assertEquals(Color.White, material(navy))
    }

    /** In a high contrast mode the palette is set aside and the system's colours draw. */
    @Test
    fun fr14_10_high_contrast_sets_the_palette_aside() {
        for (system in DesignSystem.entries) {
            val plain = resolveTheme(
                themed(system, emptyList(), ColorScheme.Light),
                HostPlatform.Unknown,
                systemDark = false,
                highContrast = { true },
            )
            val branded = resolveTheme(
                themed(system, BRAND, ColorScheme.Light),
                HostPlatform.Unknown,
                systemDark = false,
                highContrast = { true },
            )
            ColorRole.entries.forEach { role ->
                assertEquals(plain.color(role), branded.color(role), "$system $role under high contrast")
            }
        }
    }

    /**
     * The window follows the system from light to dark with the dark half of the palette it
     * already holds. No event goes to the Host and no new theme comes back.
     */
    @Test
    fun fr14_10_following_the_system_into_dark_needs_nothing_from_the_host() = runComposeUiTest {
        val swatch = 2
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(themed(DesignSystem.Fluent, BRAND, ColorScheme.FollowSystem)),
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(swatch, WidgetKind.Box),
                Mutation.SetModifier(swatch, 0, ProtocolModifier.Size(48f, 48f)),
                Mutation.SetModifier(swatch, 1, ProtocolModifier.Background(Paint.Role(ColorRole.Primary))),
                Mutation.Insert(1, swatch, 0),
            ),
        )
        var dark by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalSystemDarkObserver provides { dark }) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        fun middle(): Int {
            val picture = onNodeWithTag(nodeTestTag(swatch)).captureToImage().toAwtImage()
            return picture.getRGB(picture.width / 2, picture.height / 2)
        }
        waitForIdle()
        assertEquals(BRAND_LIGHT, middle())
        val before = connection.events.toList()

        dark = true
        waitForIdle()
        assertEquals(BRAND_DARK, middle())
        assertEquals(
            before,
            connection.events.toList(),
            "turning dark sent something to the Host, which already said everything it had to",
        )
    }

    /**
     * An unknown role, a scheme of following the system, a second value for the same role
     * and scheme: each is a protocol error, and every other entry still applies.
     */
    @Test
    fun fr14_10_a_bad_palette_entry_is_reported_and_the_rest_apply() = runComposeUiTest {
        fun entry(role: Int, scheme: Int, argb: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(role.toShort()).putShort(scheme.toShort()).putInt(argb).array()
        val bytes = entry(1, 1, BRAND_LIGHT) +
            entry(999, 1, 0xFF000000.toInt()) +
            entry(9, 3, 0xFF000000.toInt()) +
            entry(1, 1, 0xFF0000FF.toInt()) +
            entry(1, 2, BRAND_DARK)
        val (entries, problems) = Protocol.palette(bytes)
        assertEquals(3, problems.size, "$problems")
        assertEquals(
            listOf(
                PaletteEntry(ColorRole.Primary, false, BRAND_LIGHT),
                PaletteEntry(ColorRole.Primary, true, BRAND_DARK),
            ),
            entries,
        )

        val swatch = 2
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(
                    Theme(
                        DesignSystem.Fluent,
                        DesignSystem.Fluent,
                        ColorScheme.Light,
                        false,
                        palette = entries,
                        paletteProblems = problems,
                    ),
                ),
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(swatch, WidgetKind.Box),
                Mutation.SetModifier(swatch, 0, ProtocolModifier.Size(48f, 48f)),
                Mutation.SetModifier(swatch, 1, ProtocolModifier.Background(Paint.Role(ColorRole.Primary))),
                Mutation.Insert(1, swatch, 0),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(3, errors.size, "${errors.map { it.message }}")
        assertTrue(errors.all { it.message.contains("palette") })
        val picture = onNodeWithTag(nodeTestTag(swatch)).captureToImage().toAwtImage()
        assertEquals(BRAND_LIGHT, picture.getRGB(picture.width / 2, picture.height / 2))
    }

    /**
     * A highlighted keyword is drawn in a different colour in light and in dark, and in at
     * least two systems in a different colour from each other.
     */
    @Test
    fun fr13_1_3_a_keyword_is_drawn_differently_by_scheme_and_by_system() {
        val keywords = mutableSetOf<Int>()
        for (system in DesignSystem.entries) {
            fun keyword(dark: Boolean) = resolveTheme(
                themed(system, emptyList(), ColorScheme.FollowSystem),
                HostPlatform.Unknown,
                systemDark = dark,
            ).color(ColorRole.SyntaxKeyword)
            assertNotEquals(keyword(false), keyword(true), "$system draws keywords alike in both schemes")
            keywords += keyword(false).toArgb()
        }
        assertTrue(keywords.size >= 2, "every system draws keywords in one colour")
    }

    /** An application can give its own code colours through the same palette. */
    @Test
    fun fr13_1_3_an_application_can_give_its_own_code_colours() {
        val theme = resolveTheme(
            themed(
                DesignSystem.Gnome,
                listOf(PaletteEntry(ColorRole.SyntaxKeyword, false, 0xFF8800AA.toInt())),
                ColorScheme.Light,
            ),
            HostPlatform.Unknown,
            systemDark = false,
            highContrast = { false },
        )
        assertEquals(Color(0xFF8800AA), theme.color(ColorRole.SyntaxKeyword))
        assertEquals(Color(0xFF8800AA), theme.color(Paint.Role(ColorRole.SyntaxKeyword)))
    }
}
