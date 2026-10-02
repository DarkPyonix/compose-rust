package dioxus.compose.test

import androidx.compose.ui.text.font.FontFamily
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.platformUiFamily
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.TypeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A font the application shipped, resolved through a role.
 *
 * Registration and not a name, because a name puts the check that the font exists on the
 * reader's machine, where a missing one is a screen in the wrong typeface with nobody to
 * tell. Per theme and not per node: an application changes what `Display` is made of and
 * every title changes with it.
 */
class FontAssetTest {

    private val shipped = FontFamily.Cursive

    private fun theme(asked: Theme, fontOf: (Int) -> FontFamily? = { null }) = resolveTheme(
        theme = asked,
        platform = HostPlatform.Unknown,
        systemDark = false,
        fontOf = fontOf,
    )

    @Test
    fun fr23_a_role_resolved_to_a_registered_font_is_written_in_it() {
        val asked = Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
        ).withFont(TypeRole.Display, 4)
        val resolved = theme(asked) { if (it == 4) shipped else null }
        assertSame(shipped, resolved.family(TypeRole.Display))
    }

    @Test
    fun fr23_naming_a_font_for_one_role_leaves_the_others_alone() {
        val asked = Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
        ).withFont(TypeRole.Display, 4)
        val resolved = theme(asked) { if (it == 4) shipped else null }
        for (role in TypeRole.entries - TypeRole.Display - TypeRole.Mono) {
            assertSame(platformUiFamily, resolved.family(role), "$role changed as well")
        }
    }

    @Test
    fun fr23_an_id_that_names_nothing_leaves_the_role_on_the_system_face() {
        val asked = Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
        ).withFont(TypeRole.Display, 99)
        assertSame(platformUiFamily, theme(asked).family(TypeRole.Display))
    }

    @Test
    fun fr23_code_stays_monospaced_unless_a_font_was_named_for_it() {
        val plain = Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
        )
        assertEquals(FontFamily.Monospace, theme(plain).family(TypeRole.Mono))
        val asked = plain.withFont(TypeRole.Mono, 4)
        assertSame(shipped, theme(asked) { shipped }.family(TypeRole.Mono))
    }

    @Test
    fun fr23_a_theme_that_named_no_font_carries_none() {
        val plain = Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
        )
        assertEquals(emptyMap(), theme(plain).fonts)
        for (role in TypeRole.entries) {
            assertEquals(null, plain.font(role))
        }
    }
}

/** The Kotlin side reads the slots; this is the same assignment said from a test. */
private fun Theme.withFont(role: TypeRole, asset: Int): Theme =
    copy(fonts = fonts.toMutableList().also { it[role.ordinal] = asset })
