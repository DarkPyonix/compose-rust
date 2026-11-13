@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.ui.platform.WindowAppearanceFollower
import dev.darkpyonix.composerust.ui.platform.windowAppearanceFor
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.AppKit.NSView
import platform.AppKit.NSVisualEffectView
import platform.AppKit.appearance
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the window's own appearance, and so the material behind the scene, is the colour
 * scheme the application's theme resolves to.
 *
 * The fork's window backs the scene with an `NSVisualEffectView`, which draws light or dark
 * by the window's appearance and knows nothing of the theme. When the single window was
 * never told, a light theme on a Mac set to dark laid its translucent chrome over a dark
 * material and came out grey: the page about #C4C4C4, a darker title strip, a dark sidebar.
 *
 * The cases that matter are the crossed ones, so these set the application's appearance
 * to the opposite of the theme and put it back afterwards.
 */
class WindowAppearanceTest {
    private fun theme(scheme: ColorScheme) =
        Theme(DesignSystem.LiquidGlass, DesignSystem.Material3, scheme, false)

    /**
     * Which of light and dark [view] is drawn in now, after a short turn of the run loop so
     * an appearance AppKit settles on its next pass has settled.
     */
    private fun drawnIn(view: NSView): String? {
        NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05))
        return view.effectiveAppearance.bestMatchFromAppearancesWithNames(
            listOf(NSAppearanceNameAqua, NSAppearanceNameDarkAqua),
        )
    }

    /** Runs [block] with the application's appearance set to [system], then restores it. */
    private fun onSystem(system: String, block: () -> Unit) {
        val application = NSApplication.sharedApplication()
        val before = application.appearance
        application.appearance = NSAppearance.appearanceNamed(system)
        try {
            block()
        } finally {
            application.appearance = before
        }
    }

    /** A real window of the fork's, with its material installed, closed after [block]. */
    private fun withWindow(block: (MacosWindow, NSView) -> Unit) {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "appearance", width = 320, height = 240)
        window.initWindow()
        try {
            val backdrop = assertNotNull(window.window.contentView, "the window has a content view")
            assertTrue(
                backdrop is NSVisualEffectView,
                "the window's content view is the material this test is about",
            )
            block(window, backdrop)
        } finally {
            window.window.orderOut(null)
            window.window.close()
        }
    }

    @Test
    fun fr14_window_appearance_is_the_theme_scheme() {
        assertEquals(NSAppearanceNameAqua, windowAppearanceFor(ColorScheme.Light)?.name)
        assertEquals(NSAppearanceNameDarkAqua, windowAppearanceFor(ColorScheme.Dark)?.name)
        assertNull(
            windowAppearanceFor(ColorScheme.FollowSystem),
            "a theme that follows the system leaves the window on the application's appearance",
        )
    }

    @Test
    fun fr29_light_theme_on_a_dark_system_draws_a_light_material() = withWindow { window, backdrop ->
        onSystem(NSAppearanceNameDarkAqua) {
            assertEquals(NSAppearanceNameDarkAqua, drawnIn(backdrop), "before: the system's dark")
            WindowAppearanceFollower(window.window).follow(theme(ColorScheme.Light))
            assertEquals(
                NSAppearanceNameAqua,
                drawnIn(backdrop),
                "a light theme on a dark Mac lays its translucent chrome over a dark material, " +
                    "and the page, title strip and sidebar come out grey",
            )
        }
    }

    @Test
    fun fr29_dark_theme_on_a_light_system_draws_a_dark_material() = withWindow { window, backdrop ->
        onSystem(NSAppearanceNameAqua) {
            WindowAppearanceFollower(window.window).follow(theme(ColorScheme.Dark))
            assertEquals(NSAppearanceNameDarkAqua, drawnIn(backdrop))
        }
    }

    @Test
    fun fr14_follow_system_tracks_the_system_live() = withWindow { window, backdrop ->
        val follower = WindowAppearanceFollower(window.window)
        onSystem(NSAppearanceNameDarkAqua) {
            follower.follow(theme(ColorScheme.Light))
            // The application goes back to following the system: the window holds no
            // appearance of its own, so a change of the system's reaches it.
            follower.follow(theme(ColorScheme.FollowSystem))
            assertNull(window.window.appearance, "the window keeps no appearance of its own")
            assertEquals(NSAppearanceNameDarkAqua, drawnIn(backdrop))
        }
        onSystem(NSAppearanceNameAqua) {
            assertEquals(NSAppearanceNameAqua, drawnIn(backdrop), "the system turned light and so did the window")
        }
    }

    @Test
    fun fr14_theme_change_moves_the_appearance_once() {
        val applied = mutableListOf<String?>()
        val follower = WindowAppearanceFollower { applied += it?.name }
        // No theme yet is the system's.
        follower.follow(null)
        assertEquals(ColorScheme.FollowSystem, follower.scheme)
        follower.follow(theme(ColorScheme.Light))
        // The same scheme again, as every unrelated theme change sends it, is no change:
        // setting an appearance redraws the whole window.
        follower.follow(theme(ColorScheme.Light).copy(designSystem = DesignSystem.Cupertino))
        follower.follow(theme(ColorScheme.Dark))
        follower.follow(theme(ColorScheme.FollowSystem))
        assertEquals(listOf(null, NSAppearanceNameAqua, NSAppearanceNameDarkAqua, null), applied)
    }
}
