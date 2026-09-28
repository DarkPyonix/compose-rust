package dioxus.compose.test

import dioxus.compose.runtime.platformBacksWindowWithMaterial
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Whether this renderer has put a material behind its window.
 *
 * It has not. The visual effect view that would do it is in the native image's C entry
 * point; this renderer opens its own `NSWindow` and puts nothing behind the Compose
 * surface. The answer used to be taken from the operating system's name, which is true of
 * both builds and describes only one of them, so this one told the design system the
 * desktop was showing through when the window was opaque.
 *
 * What that looked like: chrome became the recipe meant to sit on the desktop, a tint at
 * 0.18 with a rim, and the page gradient was made translucent to let the desktop reach it.
 * With nothing behind either of them, a window in light mode came out with a grey page and
 * a grey sidebar. Nothing was transparent. Everything was drawn as though it were.
 */
class WindowBackdropTest {
    @Test
    fun fr29_this_renderer_does_not_claim_a_backdrop_it_has_not_got() {
        assertFalse(
            platformBacksWindowWithMaterial(),
            "the macOS renderer puts no visual effect view behind its window, and a " +
                "design system told otherwise draws its chrome and its page for a " +
                "desktop that never shows through",
        )
    }
}
