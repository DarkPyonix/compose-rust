package dioxus.compose.test

import androidx.compose.ui.unit.dp
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.TitleBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two title bars, and what each platform's language does with the choice.
 *
 * `Chrome` says who draws the caption. This says, inside that, what kind of window the
 * caption belongs to, and the answer is not the same shape on every platform: on macOS the
 * window's own buttons come in from the corner and the window is rounded more, and on
 * Windows and Linux the caption is a different height and the buttons stay where that
 * platform puts them.
 */
class TitleBarModeTest {
    @Test
    fun fr19_7_the_apple_systems_move_the_buttons_in_and_round_the_window() {
        for (system in listOf(DesignSystem.LiquidGlass, DesignSystem.Cupertino)) {
            val theme = themeFor(system, HostPlatform.MacOs)
            val normal = theme.rules.caption(theme, TitleBar.Normal)
            val simple = theme.rules.caption(theme, TitleBar.Simple)
            assertTrue(
                normal.platformButtonInset > simple.platformButtonInset,
                "$system draws the same buttons in the same place in both modes, so there " +
                    "is nothing to tell the two windows apart",
            )
            assertTrue(
                normal.windowCornerRadius > simple.windowCornerRadius,
                "$system rounds both windows the same",
            )
            assertEquals(
                0.dp,
                simple.platformButtonInset,
                "an ordinary window moves the system's buttons, which is the one thing it " +
                    "is for not doing",
            )
        }
    }

    /**
     * The other five say nothing about either, at either mode.
     *
     * A window's outline belongs to the compositor on those platforms and the caption's
     * buttons are drawn by the application at the end the system puts them, so there is no
     * corner to come in from.
     */
    @Test
    fun fr19_7_the_other_systems_move_neither() {
        val others = DesignSystem.entries.filter {
            it != DesignSystem.LiquidGlass && it != DesignSystem.Cupertino
        }
        for (system in others) {
            val theme = themeFor(system, HostPlatform.Windows)
            for (mode in TitleBar.entries) {
                val style = theme.rules.caption(theme, mode)
                assertEquals(0.dp, style.platformButtonInset, "$system at $mode insets the buttons")
                assertEquals(0.dp, style.windowCornerRadius, "$system at $mode rounds the window")
            }
        }
    }

    /** And what they do answer is the height. */
    @Test
    fun fr19_7_the_other_systems_change_the_captions_height() {
        val others = DesignSystem.entries.filter {
            it != DesignSystem.LiquidGlass && it != DesignSystem.Cupertino
        }
        for (system in others) {
            val theme = themeFor(system, HostPlatform.Windows)
            assertTrue(
                theme.rules.caption(theme, TitleBar.Normal).height !=
                    theme.rules.caption(theme, TitleBar.Simple).height,
                "$system draws the same caption at both modes, so the choice does nothing",
            )
        }
    }

    private fun themeFor(system: DesignSystem, platform: HostPlatform): ResolvedTheme =
        resolveTheme(
            theme = Theme(system, system, ColorScheme.Light, adaptive = false),
            platform = platform,
            systemDark = false,
        )
}
