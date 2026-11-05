@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.WindowDropTargets
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

    private fun bytesOf(vararg values: Float): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, value ->
            val bits = value.toRawBits()
            for (b in 0 until 4) out[i * 4 + b] = (bits shr (8 * b)).toByte()
        }
        return out
    }

    /** A window whose frame and pointer are scripted, so the test does not depend on a window manager. */
    private class ScriptedWindow(
        private val inner: PlatformWindow,
        private val frame: ScreenBounds,
        private val pointer: () -> ScreenPointer,
    ) : PlatformWindow by inner {
        override fun bounds(): ScreenBounds = frame
        override fun contentOrigin(): ScreenPoint = ScreenPoint(frame.x, frame.y)
        override fun pointerOnScreen(): ScreenPointer = pointer()
        override fun followPointer(anchorX: Float, anchorY: Float): Boolean = true
    }

    private class ScriptedBackend(
        private val inner: LinuxWindowBackend,
        private val frames: Map<Int, ScreenBounds>,
        private val pointer: () -> ScreenPointer,
    ) : WindowBackend by inner {
        override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
            val opened = inner.open(config, listener) ?: return null
            return ScriptedWindow(opened, frames.getValue(config.nodeId), pointer)
        }
    }

    @Test
    fun fr53_53_7_linux_registered_drop_targets_follow_bounds_and_subscription() {
        WindowDropTargets.update(1, 10, 20f, 30f, 120f, 130f, 2f, 1)
        WindowDropTargets.update(1, 11, 40f, 50f, 80f, 90f, 2f, 2)
        WindowDropTargets.update(2, 20, 20f, 30f, 120f, 130f, 1f, 1)
        try {
            assertEquals(DropTarget(11, 5f, 5f), DropTargets.Registered.targetAt(1, 50f, 60f))
            assertEquals(DropTarget(20, 30f, 30f), DropTargets.Registered.targetAt(2, 50f, 60f))
            WindowDropTargets.remove(1, 11)
            assertEquals(DropTarget(10, 15f, 15f), DropTargets.Registered.targetAt(1, 50f, 60f))
            WindowDropTargets.update(1, 10, 200f, 200f, 300f, 300f, 2f, 1)
            assertEquals(null, DropTargets.Registered.targetAt(1, 50f, 60f))
        } finally {
            WindowDropTargets.remove(1, 10)
            WindowDropTargets.remove(1, 11)
            WindowDropTargets.remove(2, 20)
        }
    }

    /**
     * The whole path of a dragged window: the Host's `DragFromPointer`, a real X window drawing a
     * real `Text` node that subscribed with `OnWindowDragOver` (its bounds come from layout, not
     * from the test), the manager polling the pointer, and the `WindowDragOver` events the Host
     * receives. Only the pointer position and the window frames are scripted.
     */
    @Test
    fun fr53_53_7_linux_drag_over_reaches_the_host_through_a_real_window() {
        if (!hasDisplay()) return
        var pointer = ScreenPointer(0f, 0f, true)
        val frames = mapOf(
            1 to ScreenBounds(0f, 600f, 300f, 200f),
            2 to ScreenBounds(1000f, 0f, 300f, 200f),
        )
        val main = mainForTests()
        lateinit var host: ComposeRustHost
        val real = LinuxWindowBackend(main) { nodeId -> { ComposeRustContent(host, windowNodeId = nodeId) } }
        val backend = ScriptedBackend(real, frames) { pointer }
        fun window(id: Int, vararg props: Pair<PropertyKind, PropertyValue>) = buildList {
            add(Mutation.Create(id, WidgetKind.Window))
            add(Mutation.SetProp(id, PropertyKind.WindowSize, PropertyValue.Bytes(bytesOf(300f, 200f))))
            for ((kind, value) in props) add(Mutation.SetProp(id, kind, value))
            add(Mutation.Insert(0, id, -1))
        }
        fun strip(parent: Int, id: Int, handler: Long) = listOf(
            Mutation.Create(id, WidgetKind.Text),
            Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text("tab strip")),
            Mutation.SetProp(id, PropertyKind.OnWindowDragOver, PropertyValue.Integer(handler)),
            Mutation.Insert(parent, id, -1),
        )
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
        ) + window(2) + strip(2, 20, 33L) +
            window(
                1,
                PropertyKind.DragFromPointer to PropertyValue.Bytes(
                    ByteArray(12).also {
                        it[0] = 1
                        bytesOf(0f, 0f).copyInto(it, 4)
                    },
                ),
                PropertyKind.OnWindowDrag to PropertyValue.Integer(11L),
            ) + strip(1, 10, 44L)
        val conn = FakeHostConnection(initial)
        host = ComposeRustHost(conn)
        host.start()
        val manager = WindowManager(host, backend, onExit = {}, dropTargets = DropTargets.Registered)
        try {
            manager.attach()
            // The subscribed node of window 2 has been laid out and has reported its bounds.
            assertTrue(
                real.turnsUntil { WindowDropTargets.find(2, 2f, 4f) != null },
                "the subscribed node never registered its bounds from layout",
            )
            fun overs() = conn.events.filterIsInstance<HostEvent.WindowDragOver>()

            pointer = ScreenPointer(1002f, 4f, true)
            manager.flushFrame()
            assertEquals(listOf(1), overs().map { it.kind }, "no Enter reached the Host")
            val enter = overs().first()
            assertEquals(20, enter.nodeId)
            assertEquals(33L, enter.handlerId)
            assertEquals(1, enter.window)

            pointer = ScreenPointer(1004f, 5f, true)
            manager.flushFrame()
            assertEquals(listOf(1, 2), overs().map { it.kind })

            pointer = ScreenPointer(1250f, 190f, true)
            manager.flushFrame()
            assertEquals(listOf(1, 2, 3), overs().map { it.kind })

            pointer = ScreenPointer(1003f, 4f, true)
            manager.flushFrame()
            assertTrue(overs().all { it.nodeId == 20 }, "the dragged window's own strip was hit")
            assertEquals(1, overs().last().kind, "re-entering the strip did not Enter again")
        } finally {
            manager.shutdown()
            real.closeAll()
            host.shutdown()
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
}
