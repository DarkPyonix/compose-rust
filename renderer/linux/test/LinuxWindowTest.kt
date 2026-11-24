@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.scene.CanvasLayersComposeScene
import dev.darkpyonix.composerust.runtime.LocalWindowActions
import dev.darkpyonix.composerust.runtime.LocalWindowDrag
import dev.darkpyonix.composerust.runtime.WindowActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.TitleBarStyle
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.WindowConfig
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import x11.XFree
import x11.XGetWindowProperty
import x11.XInternAtom
import x11.glXGetCurrentDisplay
import x11.glXGetCurrentDrawable

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
        // Closed, because the windows of a process share one display connection and the test
        // below needs there to be none open to see what a machine with no display server does.
        window.close()
    }

    @Test
    fun nfr1_a_window_refuses_to_open_where_there_is_no_display_server() {
        // Taken away rather than pointed somewhere wrong. An empty DISPLAY and a DISPLAY naming a
        // server that is not there are different failures inside Xlib, and the one a machine with
        // no X server actually has is this one.
        // Put back afterwards, because the tests that need a display run in this process too.
        val display = getenv("DISPLAY")?.toKString()
        unsetenv("DISPLAY")
        try {
            assertNull(
                LinuxWindow.open(title = "no display", width = 520, height = 360),
                "a window with no display server to open on answers null rather than throwing, " +
                    "because what called in is a C entry point and a Kotlin exception must not " +
                    "cross back over it",
            )
        } finally {
            if (display != null) setenv("DISPLAY", display, 1)
        }
    }

    /**
     * A node the application made a drag region moves a Linux window: content in a
     * [LinuxWindow] is given a window drag that hands the press to the window manager
     * (`X11Window.startMove`, then `_NET_WM_MOVERESIZE`), not the composition local's default,
     * which does nothing. The move itself needs a window manager and a real press, and is the
     * hand check on darkpyonix#141.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun fr19_9_content_in_a_linux_window_can_move_it() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        var unprovided: (() -> Unit)? = null
        val bare = CanvasLayersComposeScene()
        bare.setContent { unprovided = LocalWindowDrag.current }
        bare.close()
        val window = assertNotNull(LinuxWindow.open(title = "drag", width = 320, height = 240))
        try {
            var provided: (() -> Unit)? = null
            window.setContent { provided = LocalWindowDrag.current }
            assertNotNull(unprovided, "the bare scene composed")
            assertNotNull(provided, "the window composed its content")
            assertNotSame(unprovided, provided, "the window gives its content a drag that moves it")
        } finally {
            window.close()
        }
    }

    @Test
    fun fr19_7_modern_linux_window_provides_window_actions_and_system_chrome_does_not() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val modern = assertNotNull(
            LinuxWindow.open(
                WindowConfig(
                    title = "modern",
                    width = 320,
                    height = 240,
                    chrome = WindowChrome.Modern,
                    titleBar = TitleBarStyle.Normal,
                ),
            ),
        )
        try {
            var actions: WindowActions? = null
            modern.setContent { actions = LocalWindowActions.current }
            assertNotNull(actions, "a modern window gives its scene caption actions")
        } finally {
            modern.close()
        }

        val system = assertNotNull(
            LinuxWindow.open(
                WindowConfig(
                    title = "system",
                    width = 320,
                    height = 240,
                    chrome = WindowChrome.System,
                    titleBar = TitleBarStyle.Simple,
                ),
            ),
        )
        try {
            var actions: WindowActions? = WindowActions({}, {}, {})
            system.setContent { actions = LocalWindowActions.current }
            assertNull(actions, "a system-chrome window leaves the buttons to the window manager")
        } finally {
            system.close()
        }
    }

    @Test
    fun fr19_3_linux_window_sets_net_wm_icon_property() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val window = assertNotNull(LinuxWindow.open(title = "icon", width = 320, height = 240))
        try {
            val rgba = byteArrayOf(
                0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
                0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            )
            val data = assertNotNull(netWmIconData(rgba, width = 2, height = 1))
            assertTrue(applyNetWmIcon(window.x11, data), "setting _NET_WM_ICON failed")
            val display = assertNotNull(glXGetCurrentDisplay())
            val drawable = glXGetCurrentDrawable()
            val atom = XInternAtom(display, "_NET_WM_ICON", 0)
            val cardinal = XInternAtom(display, "CARDINAL", 0)
            memScoped {
                val actualType = alloc<ULongVar>()
                val actualFormat = alloc<IntVar>()
                val nitems = alloc<ULongVar>()
                val bytesAfter = alloc<ULongVar>()
                val prop = alloc<CPointerVar<UByteVar>>()
                val status = XGetWindowProperty(
                    display,
                    drawable,
                    atom,
                    0,
                    16,
                    0,
                    0uL,
                    actualType.ptr,
                    actualFormat.ptr,
                    nitems.ptr,
                    bytesAfter.ptr,
                    prop.ptr,
                )
                assertEquals(0, status, "XGetWindowProperty failed")
                try {
                    assertEquals(cardinal, actualType.value)
                    assertEquals(32, actualFormat.value)
                    assertEquals(4uL, nitems.value)
                    val words = assertNotNull(prop.value).reinterpret<ULongVar>()
                    assertEquals(2uL, words[0])
                    assertEquals(1uL, words[1])
                    assertEquals(0xFFFF0000uL, words[2])
                    assertEquals(0xFF0000FFuL, words[3])
                } finally {
                    prop.value?.let { XFree(it) }
                }
            }
        } finally {
            window.close()
        }
    }
}
