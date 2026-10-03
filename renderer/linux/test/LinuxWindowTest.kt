@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.unsetenv

/**
 * What the window does when there is no display server to open one on.
 *
 * The only thing about the window itself that can be asserted where no X server is running, which
 * is every continuous integration machine and most of the machines this is developed on. It is
 * worth asserting because the alternative behaviours are both bad and both have happened to
 * windowing code: a throw that unwinds into the C entry point, which is undefined, and a process
 * that carries on with a null display and dies at the first call that dereferences it.
 *
 * Everything else about this window needs a screen and a hand: whether the drawing arrives with
 * the moved edge is a fact about what a compositor showed. `ResizeSyncTest` and
 * `WindowEventLogTest` on the JVM cover the two ways the code can get that wrong.
 */
class LinuxWindowTest {

    /**
     * A window opens where there is a display server to open it on.
     *
     * The other test here is about the machine that has none. This is about the one that has,
     * and what it covers is everything `open` does before a frame is ever asked for: choosing a
     * visual with a depth buffer, making a colormap for it, creating the window, and making a
     * GLX context current on it. Every one of those can fail against a real server and none of
     * them can fail without one, which is why this could not be written until there was a Linux
     * to run it on.
     *
     * Skipped where there is no display. The machine that has one answers this; the machine that
     * has none is answered by the test below.
     */
    @Test
    fun nfr1_a_window_opens_where_there_is_a_display_server() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val window = LinuxWindow.open(title = "opens", width = 320, height = 240)
        assertNotNull(
            window,
            "a display server is present and the window did not open: the visual, the colormap, " +
                "the window or the GL context was refused",
        )
    }

    @Test
    fun nfr1_a_window_refuses_to_open_where_there_is_no_display_server() {
        // Taken away rather than pointed somewhere wrong. An empty DISPLAY and a DISPLAY naming a
        // server that is not there are different failures inside Xlib, and the one a machine with
        // no X server actually has is this one.
        unsetenv("DISPLAY")
        assertNull(
            LinuxWindow.open(title = "no display", width = 520, height = 360),
            "a window with no display server to open on answers null rather than throwing, " +
                "because what called in is a C entry point and a Kotlin exception must not " +
                "cross back over it",
        )
    }
}
