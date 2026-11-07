@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import dev.darkpyonix.composerust.runtime.appZoomScale
import dev.darkpyonix.composerust.tooling.FakeHostConnection
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
 * Multi-window tests on X11: the Linux backend over the fork's X11 window, against a real server (CI runs
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
        val zoomEvents = ArrayList<Triple<Float, Float, Int>>()
        override fun onFocusChanged(focused: Boolean) { focusEvents += focused }
        override fun onMoved(bounds: ScreenBounds) { moves += bounds }
        override fun onFollowEnded(cancelled: Boolean) {}
        override fun onZoomChanged(k: Float, os: Float, level: Int) { zoomEvents += Triple(k, os, level) }
    }

    private fun hasDisplay(): Boolean {
        if (!getenv("DISPLAY")?.toKString().isNullOrEmpty()) return true
        assertTrue(getenv("CI") == null, "no DISPLAY under CI, so the backend was not exercised")
        return false
    }

    private fun backend() = LinuxWindowBackend(mainForTests(), makeZoom = { Zoom() }) { { } }

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
    fun fr53_53_3_linux_zoom_at_open_and_after_open_is_local_to_each_window() {
        if (!hasDisplay()) return
        val densities = mutableMapOf<Int, Float>()
        val backend = LinuxWindowBackend(mainForTests(), makeZoom = { Zoom() }) { id ->
            {
                val density = LocalDensity.current.density
                SideEffect { densities[id] = density }
            }
        }
        try {
            val firstEvents = Recorder()
            val secondEvents = Recorder()
            val first = backend.open(config(1).copy(zoomLevel = 2), firstEvents)!!
            val second = backend.open(config(2).copy(zoomLevel = 0), secondEvents)!!
            assertEquals(2, firstEvents.zoomEvents.last().third)
            assertEquals(0, secondEvents.zoomEvents.last().third)
            assertTrue(backend.turnsUntil { densities.size == 2 })
            val base = densities.getValue(2)
            assertEquals(base * appZoomScale(2), densities.getValue(1), 0.001f)

            first.setZoomLevel(-1)
            assertEquals(-1, firstEvents.zoomEvents.last().third)
            assertTrue(backend.turnsUntil { kotlin.math.abs(densities.getValue(1) - base * appZoomScale(-1)) < 0.001f })
            assertEquals(base, densities.getValue(2), 0.001f)
            assertEquals(listOf(0), secondEvents.zoomEvents.map { it.third })

            first.setZoomLevel(100)
            assertEquals(8, firstEvents.zoomEvents.last().third)
            val count = firstEvents.zoomEvents.size
            first.setZoomLevel(9)
            assertEquals(count, firstEvents.zoomEvents.size)
            second.close()
            first.close()
            first.setZoomLevel(0)
            assertEquals(count, firstEvents.zoomEvents.size)
        } finally {
            backend.closeAll()
        }
    }

    @Test
    fun fr53_53_3_linux_new_window_reads_the_last_saved_zoom() {
        if (!hasDisplay()) return
        val store = object : ZoomLevelStore {
            var saved: Int? = 3
            override fun load(): Int? = saved
            override fun save(level: Int) { saved = level }
        }
        val backend = LinuxWindowBackend(mainForTests(), makeZoom = { Zoom(store = store) }) { { } }
        try {
            val firstEvents = Recorder()
            val first = backend.open(config(1), firstEvents)!!
            assertEquals(3, firstEvents.zoomEvents.last().third)
            first.setZoomLevel(-2)
            val secondEvents = Recorder()
            backend.open(config(2), secondEvents)!!
            assertEquals(-2, secondEvents.zoomEvents.last().third)
            assertEquals(-2, store.saved)
        } finally {
            backend.closeAll()
        }
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

    @Test
    fun fr53_53_3_linux_color_scheme_overrides_one_window_only() {
        if (!hasDisplay()) return
        val darkByWindow = mutableMapOf<Int, Boolean?>()
        val work = mainForTests()
        val backend = LinuxWindowBackend(work) { nodeId ->
            {
                darkByWindow[nodeId] = LocalSystemDarkObserver.current?.invoke()
            }
        }
        val initial = listOf(
            Mutation.SetWindow(
                Window(
                    chrome = Chrome.Modern,
                    titleBar = TitleBar.Normal,
                    title = "",
                    icon = 0,
                    width = 0,
                    height = 0,
                    minWidth = 0,
                    minHeight = 0,
                    resizable = true,
                    zoomLevel = null,
                    mode = WindowMode.Windows,
                    lastWindowClosed = LastWindowClosed.KeepRunning,
                ),
            ),
            Mutation.Create(10, WidgetKind.Window),
            Mutation.SetProp(10, PropertyKind.WindowTitle, PropertyValue.Text("Window A")),
            Mutation.SetProp(10, PropertyKind.WindowSize, PropertyValue.Pair(300f, 200f)),
            Mutation.Insert(0, 10, -1),
            Mutation.SetWindowTheme(10, Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Dark, false)),
            Mutation.Create(20, WidgetKind.Window),
            Mutation.SetProp(20, PropertyKind.WindowTitle, PropertyValue.Text("Window B")),
            Mutation.SetProp(20, PropertyKind.WindowSize, PropertyValue.Pair(300f, 200f)),
            Mutation.Insert(0, 20, -1),
            Mutation.SetWindowTheme(20, Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
        )
        val conn = FakeHostConnection(initial)
        val host = ComposeRustHost(conn)
        host.start()
        val manager = WindowManager(host, backend, onExit = {})
        try {
            manager.attach()
            assertTrue(
                backend.turnsUntil { darkByWindow[10] != null && darkByWindow[20] != null },
                "both windows did not render",
            )
            assertEquals(true, darkByWindow[10])
            assertEquals(false, darkByWindow[20])

            conn.scheduleFrame(
                listOf(
                    Mutation.SetWindowTheme(
                        10,
                        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false),
                    ),
                ),
            )
            host.renderFrame(1L)
            assertTrue(
                backend.turnsUntil { darkByWindow[10] == false },
                "window A did not update to light",
            )
            assertEquals(false, darkByWindow[10])
            assertEquals(false, darkByWindow[20])

            conn.scheduleFrame(
                listOf(
                    Mutation.SetWindowTheme(
                        20,
                        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Dark, false),
                    ),
                ),
            )
            host.renderFrame(2L)
            assertTrue(
                backend.turnsUntil { darkByWindow[20] == true },
                "window B did not update to dark",
            )
            assertEquals(false, darkByWindow[10])
            assertEquals(true, darkByWindow[20])
        } finally {
            manager.shutdown()
            backend.closeAll()
            host.shutdown()
        }
    }
}
