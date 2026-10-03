package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.runtime.platformBacksWindowWithMaterial
import dev.darkpyonix.composerust.ui.platform.declareWindowBackdrop
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether this renderer has put a material behind its window, and whether it says so.
 *
 * It has: the window's content view is an `NSVisualEffectView` and the window itself is
 * neither opaque nor painted, so what a page or a piece of chrome leaves transparent is
 * the desktop. Until that was true this renderer claimed a backdrop it had not got, and
 * the claim was taken from the operating system's name, which is true of both builds for
 * this platform and describes only one of them.
 *
 * What a claim that does not match the window costs is the whole look of it. Chrome takes
 * the recipe meant to sit on the desktop, a tint at 0.18 with a rim, and the page gradient
 * is made translucent so the desktop can reach it. With nothing behind either, a window in
 * light mode comes out with a grey page and a grey sidebar: nothing transparent, and
 * everything drawn as though it were. With a material behind and the claim withheld, the
 * opposite: a sidebar of white glass over a white page, told apart from it by nothing but
 * its shadow.
 */
class WindowBackdropTest {
    @Test
    fun fr29_this_renderer_says_it_has_the_backdrop_it_puts_there() {
        declareWindowBackdrop()
        assertTrue(
            platformBacksWindowWithMaterial(),
            "the macOS renderer puts a visual effect view behind its window and then tells " +
                "the design system it has not, so every surface that would have shown the " +
                "desktop through it is drawn as though the window were opaque",
        )
    }
}
