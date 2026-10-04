package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TitleBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two title bars, and what each platform's language does with the choice.
 *
 * `Chrome` says who draws the caption. This says, inside that, what kind of window the
 * caption belongs to, and the answer is not the same shape on every platform: on macOS the
 * window's own buttons and corner are the system's for the style the window asked for, and on
 * Windows and Linux the caption is a different height and the buttons stay where that
 * platform puts them.
 */
class TitleBarModeTest {
    /**
     * The Apple systems leave the window's corner and its buttons to macOS.
     *
     * Both are the system's, read from the window once it is open, and they differ between
     * a window with a toolbar and one without. A design system that wrote either down
     * would be right for one style on one release, so the caption the two systems answer
     * is the same at both modes and the window says the rest.
     */
    @Test
    fun fr19_7_the_apple_systems_leave_the_window_corner_and_buttons_to_the_system() {
        for (system in listOf(DesignSystem.LiquidGlass, DesignSystem.Cupertino)) {
            val theme = themeFor(system, HostPlatform.MacOs)
            assertEquals(
                theme.rules.caption(theme, TitleBar.Normal),
                theme.rules.caption(theme, TitleBar.Simple),
                "$system answers something about the window that only macOS knows",
            )
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

    /**
     * A glass sidebar is cut concentric with the window's corner, and that corner is the
     * system's, so the rules say how the sidebar's corner is cut and leave its radius to
     * the window. A bar along the bottom has no corner to follow.
     */
    @Test
    fun fr19_7_the_glass_sidebar_follows_the_window_corner_the_system_reports() {
        val theme = themeFor(DesignSystem.LiquidGlass, HostPlatform.MacOs)
        val sidebar = theme.rules.navigation(
            dev.darkpyonix.composerust.protocol.WindowSizeClass.Expanded,
            theme,
        )
        assertTrue(sidebar.stripCornerExponent != null, "the sidebar keeps a corner of its own")
        val bar = theme.rules.navigation(
            dev.darkpyonix.composerust.protocol.WindowSizeClass.Compact,
            theme,
        )
        assertEquals(null, bar.stripCornerExponent, "a bar follows the window's corner")
    }

    private fun themeFor(system: DesignSystem, platform: HostPlatform): ResolvedTheme =
        resolveTheme(
            theme = Theme(system, system, ColorScheme.Light, adaptive = false),
            platform = platform,
            systemDark = false,
        )
}
