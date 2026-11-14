package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.ui.platform.WindowSchemeFollower
import dev.darkpyonix.composerust.ui.platform.windowAppearanceCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether the native image's AppKit window is put on the colour scheme the application's
 * theme resolves to, which is what the material behind the scene is drawn in.
 *
 * The window backs the scene with an `NSVisualEffectView` that takes light or dark from
 * the window's appearance and knows nothing of the theme. Never told, a light theme on a
 * Mac set to dark came out grey. The window itself is the fork's and is checked there; what
 * is checked here is which code it is handed and when.
 */
class WindowColorSchemeTest {
    private fun theme(scheme: ColorScheme) =
        Theme(DesignSystem.LiquidGlass, DesignSystem.Material3, scheme, false)

    @Test
    fun fr29_light_theme_asks_the_window_for_aqua() {
        // The fork's window reads one as Aqua and two as DarkAqua; anything else is nil,
        // which follows the system.
        assertEquals(1, windowAppearanceCode(ColorScheme.Light))
    }

    @Test
    fun fr29_dark_theme_asks_the_window_for_dark_aqua() {
        assertEquals(2, windowAppearanceCode(ColorScheme.Dark))
    }

    @Test
    fun fr29_follow_system_leaves_the_window_on_the_system() {
        assertEquals(0, windowAppearanceCode(ColorScheme.FollowSystem))
    }

    @Test
    fun fr29_window_takes_the_first_theme_before_any_frame() {
        val sent = mutableListOf<Int>()
        val follower = WindowSchemeFollower { sent += windowAppearanceCode(it) }
        assertNull(follower.scheme)
        follower.follow(theme(ColorScheme.Light))
        assertEquals(listOf(1), sent)
        assertEquals(ColorScheme.Light, follower.scheme)
    }

    @Test
    fun fr29_no_theme_yet_is_the_system() {
        val sent = mutableListOf<Int>()
        val follower = WindowSchemeFollower { sent += windowAppearanceCode(it) }
        follower.follow(null)
        assertEquals(listOf(0), sent)
    }

    @Test
    fun fr29_theme_change_moves_the_appearance_once() {
        val sent = mutableListOf<Int>()
        val follower = WindowSchemeFollower { sent += windowAppearanceCode(it) }
        follower.follow(theme(ColorScheme.Light))
        // The same scheme again, as every later batch that carries the theme does, is not
        // a second call: setting the appearance redraws the whole window.
        follower.follow(theme(ColorScheme.Light))
        follower.follow(theme(ColorScheme.Dark))
        follower.follow(theme(ColorScheme.Dark))
        follower.follow(theme(ColorScheme.FollowSystem))
        follower.follow(null)
        assertEquals(listOf(1, 2, 0), sent)
    }
}
