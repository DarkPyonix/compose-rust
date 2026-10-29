@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.posix.getenv

/**
 * FR-53 on X11: the Linux backend over the fork's X11 window, against a real server (CI runs
 * these under Xvfb). Skipped where there is no display. Every test closes every window it
 * opened, because the windows of a process share one connection that closes with the last one
 * and `LinuxWindowTest` needs there to be none open when it looks for a display.
 */
class LinuxMultiWindowTest {

    private class Recorder : PlatformWindowListener {
        var closeRequests = 0
        val moves = ArrayList<ScreenBounds>()
        override fun onCloseRequested() { closeRequests++ }
        val focusEvents = ArrayList<Boolean>()
        override fun onFocusChanged(focused: Boolean) { focusEvents += focused }
        override fun onMoved(bounds: ScreenBounds) { moves += bounds }
        override fun onFollowEnded(cancelled: Boolean) {}
    }

    private fun hasDisplay(): Boolean {
        if (!getenv("DISPLAY")?.toKString().isNullOrEmpty()) return true
        assertTrue(getenv("CI") == null, "no DISPLAY under CI, so the backend was not exercised")
        return false
    }

    private fun backend() = LinuxWindowBackend(mainForTests()) { { } }

    private fun config(id: Int, width: Float = 320f, height: Float = 240f) =
        PlatformWindowConfig(nodeId = id, title = "window $id", width = width, height = height)

    /** Turns the loop until [done], at most [limit] times. */
    private fun LinuxWindowBackend.turnsUntil(limit: Int = 200, done: () -> Boolean): Boolean {
        repeat(limit) {
            turn()
            if (done()) return true
        }
        return done()
    }

    @Test
    fun fr53_linux_two_window_nodes_open_two_x_windows_and_close_one() {
        if (!hasDisplay()) return
        val backend = backend()
        try {
            val first = backend.open(config(1), Recorder())
            val second = backend.open(config(2), Recorder())
            assertNotNull(first, "the first window did not open")
            assertNotNull(second, "the second window did not open")
            assertEquals(2, backend.openCount)
            assertTrue(backend.supportsMultipleWindows)
            first.close()
            assertEquals(1, backend.openCount)
            assertNotNull(second.bounds(), "closing one window took the other with it")
            first.close()
            assertEquals(1, backend.openCount, "closing twice is not closing another window")
        } finally {
            backend.closeAll()
        }
        assertEquals(0, backend.openCount)
    }

    @Test
    fun fr53_linux_close_request_reaches_the_app_and_window_stays() {
        if (!hasDisplay()) return
        val backend = backend()
        try {
            val recorder = Recorder()
            val window = backend.open(config(1), recorder) as LinuxPlatformWindow
            // What the X11 layer does for the window manager's close message.
            val letClose = window.window.onCloseRequested()
            assertFalse(letClose, "the window must not close itself: only its node going away closes it")
            assertEquals(1, recorder.closeRequests)
            assertEquals(1, backend.openCount)
            assertNotNull(window.bounds())
        } finally {
            backend.closeAll()
        }
    }

    @Test
    fun fr53_linux_one_pump_services_both_windows() {
        if (!hasDisplay()) return
        val backend = backend()
        try {
            val first = backend.open(config(1, 300f, 200f), Recorder()) as LinuxPlatformWindow
            val second = backend.open(config(2, 300f, 200f), Recorder()) as LinuxPlatformWindow
            first.setContentSize(410f, 230f)
            second.setContentSize(450f, 270f)
            // The backend pumps one window's connection per turn; both resizes must be heard.
            val heard = backend.turnsUntil {
                val a = first.window.x11.measure()
                val b = second.window.x11.measure()
                a.width == 410 && a.height == 230 && b.width == 450 && b.height == 270
            }
            assertTrue(heard, "one pump per turn did not deliver the resize of both windows")
        } finally {
            backend.closeAll()
        }
    }

    @Test
    fun fr53_linux_position_round_trips() {
        if (!hasDisplay()) return
        val backend = backend()
        try {
            val recorder = Recorder()
            val window = backend.open(config(1), recorder) as LinuxPlatformWindow
            window.setPosition(120f, 90f)
            val moved = backend.turnsUntil {
                val bounds = window.bounds()
                bounds != null && bounds.x == 120f && bounds.y == 90f
            }
            assertTrue(moved, "the position set was not the position read back: ${window.bounds()}")
            assertTrue(recorder.moves.isNotEmpty(), "the move was not reported to the listener")
            assertTrue(window.pointerOnScreen() != null, "the pointer is on the screen")
        } finally {
            backend.closeAll()
        }
    }

    @Test
    fun fr53_linux_a_window_draws_the_frame_a_waiter_asked_for() {
        if (!hasDisplay()) return
        val work = mainForTests()
        val backend = LinuxWindowBackend(work) { { } }
        val scope = CoroutineScope(work + Job())
        try {
            val window = backend.open(config(1), Recorder())!!
            var answered = 0L
            scope.launch { window.awaitFrame { answered = it } }
            val drew = backend.turnsUntil { answered != 0L }
            assertTrue(drew, "an idle window never answered the frame clock the process waits on")
        } finally {
            scope.coroutineContext[Job]?.cancel()
            backend.closeAll()
        }
    }

    /**
     * Focus is A, then B, then A. Each step waits until the window is key and the other is not,
     * then checks that the window heard focus=true last and the other focus=false last, and that
     * the application was never told it lost focus while the keyboard moved between its windows.
     *
     * The focus is moved by `focus()`, which on an X server with no window manager (Xvfb in CI)
     * is an explicit `XSetInputFocus` on the window, retried each turn until the window is
     * mapped. So no window manager is needed for this to move.
     */
    @Test
    fun fr53_linux_focus_follows_the_key_window() {
        if (!hasDisplay()) return
        val backend = backend()
        try {
            val a = Recorder()
            val b = Recorder()
            val first = backend.open(config(1), a) as LinuxPlatformWindow
            val second = backend.open(config(2), b) as LinuxPlatformWindow
            assertTrue(backend.turnsUntil { first.focus(); first.isKey && !second.isKey }, "A never became key")
            // From here the application has the keyboard and must keep it.
            val told = ArrayList<Boolean>()
            backend.onApplicationFocus = { told += it }

            fun step(target: LinuxPlatformWindow, other: LinuxPlatformWindow, mine: Recorder, theirs: Recorder, name: String) {
                assertTrue(
                    backend.turnsUntil { target.focus(); target.isKey && !other.isKey },
                    "focus did not move to $name",
                )
                backend.turn()
                assertEquals(listOf(target), backend.keyWindows, "only $name is the key window")
                assertEquals(true, mine.focusEvents.lastOrNull(), "$name did not hear focus=true last")
                assertEquals(false, theirs.focusEvents.lastOrNull(), "the other window did not hear focus=false last")
                assertFalse(told.contains(false), "the application lost focus when the keyboard moved to $name")
            }
            step(second, first, b, a, "B")
            step(first, second, a, b, "A")
        } finally {
            backend.closeAll()
        }
    }
}
