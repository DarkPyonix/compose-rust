@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

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
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.MacosApplicationDelegate
import dev.darkpyonix.composerust.ui.platform.MacosPlatformWindow
import dev.darkpyonix.composerust.ui.platform.MacosSceneFrames
import dev.darkpyonix.composerust.ui.platform.MacosWindowBackend
import dev.darkpyonix.composerust.ui.platform.IdleSoakSetting
import dev.darkpyonix.composerust.ui.platform.KnIdleSoakReport
import dev.darkpyonix.composerust.ui.platform.MultiWindowStarts
import dev.darkpyonix.composerust.ui.platform.RestoreKeyStore
import dev.darkpyonix.composerust.ui.platform.WindowRestore
import dev.darkpyonix.composerust.ui.platform.MacosWindowZoom
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalWindowNodeId
import dev.darkpyonix.composerust.runtime.Zoom
import androidx.compose.runtime.CompositionLocalProvider
import dev.darkpyonix.composerust.ui.platform.PlatformWindow
import dev.darkpyonix.composerust.ui.platform.PlatformWindowConfig
import dev.darkpyonix.composerust.ui.platform.PlatformWindowListener
import dev.darkpyonix.composerust.ui.platform.ScreenBounds
import dev.darkpyonix.composerust.ui.platform.TopLeftRect
import dev.darkpyonix.composerust.ui.platform.WindowManager
import dev.darkpyonix.composerust.ui.platform.appKitRectToTopLeft
import dev.darkpyonix.composerust.ui.platform.flipPoint
import dev.darkpyonix.composerust.ui.platform.followFrameTopLeft
import dev.darkpyonix.composerust.ui.platform.primaryButtonDown
import dev.darkpyonix.composerust.ui.platform.toScreenBounds
import dev.darkpyonix.composerust.ui.platform.wholePoints
import org.thisisthepy.compose.window.WindowApplication
import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Multi-window tests on the Kotlin/Native macOS renderer: the Host's `Window` nodes as NSWindows, through the
// fork's window module. The coordinate arithmetic is tested as plain functions, because the
// runner has no pointer to move. The rest opens real windows, as the fork's own tests do.

private const val APPEND = -1

private class Recorder : PlatformWindowListener {
    var closeRequests = 0
    val focus = mutableListOf<Boolean>()
    val moved = mutableListOf<ScreenBounds>()
    var followEnded = 0

    override fun onCloseRequested() {
        closeRequests++
    }

    override fun onFocusChanged(focused: Boolean) {
        focus += focused
    }

    override fun onMoved(bounds: ScreenBounds) {
        moved += bounds
    }

    override fun onFollowEnded(cancelled: Boolean) {
        followEnded++
    }
}

/** Lets the main run loop run for a moment, where AppKit delivers notifications and queued blocks. */
private fun spin(seconds: Double = 0.05) {
    NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(seconds))
}

private fun spinUntil(turns: Int = 40, until: () -> Boolean) {
    var n = 0
    while (!until() && n < turns) {
        spin()
        n++
    }
}

private fun config(
    nodeId: Int,
    title: String = "fr53 macos",
    x: Float? = null,
    y: Float? = null,
    width: Float = 320f,
    height: Float = 200f,
) = PlatformWindowConfig(nodeId = nodeId, title = title, width = width, height = height, x = x, y = y)

private fun PlatformWindow.native() = (this as MacosPlatformWindow).nativeWindow

private fun PlatformWindow.visible() = native().visible

private class ScriptedHost(private val initial: List<Mutation>) : HostConnection {
    val events = mutableListOf<HostEvent>()
    private var next: List<Mutation> = emptyList()

    fun queue(mutations: List<Mutation>) {
        next = mutations
    }

    override fun init(onMutation: (Mutation) -> Unit) {
        initial.forEach(onMutation)
    }

    /** What the application answers a `WindowRequested` with, by its key. */
    val answers = mutableMapOf<String, List<Mutation>>()

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        events += event
        if (event is HostEvent.WindowRequested) answers[event.key]?.forEach(onMutation)
        if (event is HostEvent.Clicked) {
            val batch = next
            next = emptyList()
            batch.forEach(onMutation)
        }
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {}

    override fun shutdown() {}
}

private fun setWindow(last: LastWindowClosed = LastWindowClosed.Exit) = Mutation.SetWindow(
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
        lastWindowClosed = last,
    ),
)

private fun floats(vararg values: Float): ByteArray {
    val out = ByteArray(values.size * 4)
    values.forEachIndexed { i, value ->
        val bits = value.toRawBits()
        for (b in 0 until 4) out[i * 4 + b] = (bits shr (8 * b)).toByte()
    }
    return out
}

private fun windowNode(id: Int, vararg props: Pair<PropertyKind, PropertyValue>): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.Window))
    for ((kind, value) in props) add(Mutation.SetProp(id, kind, value))
    add(Mutation.Insert(0, id, APPEND))
}

private class Harness(initial: List<Mutation>, withScenes: Boolean = false) {
    val connection = ScriptedHost(initial)
    val host = ComposeRustHost(connection)
    val backend = if (!withScenes) {
        MacosWindowBackend()
    } else {
        // Each window draws its own node at its own zoom, as the renderer's windows do. The
        // zoom is not saved, so one test's level is not the next one's start.
        MacosWindowBackend(
            scene = { _, _, _, slot ->
                {
                    CompositionLocalProvider(LocalWindowNodeId provides slot.nodeId) {
                        MacosWindowZoom(slot.zoom) { keys -> ComposeRustContent(host, modifier = keys) }
                    }
                }
            },
            makeZoom = { Zoom() },
        )
    }
    var exits = 0
    val manager = WindowManager(host, backend, onExit = { exits++ })

    init {
        host.start(FrameRequestSource())
        manager.attach()
    }

    /** The application sends a batch: it answers the next event the renderer dispatches. */
    fun batch(vararg mutations: Mutation) {
        connection.queue(mutations.toList())
        host.dispatch(HostEvent.Clicked(nodeId = 0, handlerId = 0))
    }

    fun close() {
        manager.shutdown()
        spin()
    }
}

class MacosMultiWindowTest {
    @Test
    fun fr53_macos_screen_conversion_flips_y_and_is_its_own_inverse() {
        val rect = appKitRectToTopLeft(100.0, 700.0, 300.0, 200.0, primaryHeight = 1000.0)
        assertEquals(TopLeftRect(100.0, 100.0, 300.0, 200.0), rect)
        val there = flipPoint(40.0, 250.0, 1000.0)
        assertEquals(40.0 to 750.0, there)
        assertEquals(40.0 to 250.0, flipPoint(there.first, there.second, 1000.0))
        // Reported as whole points, so a position written back reads as the same number.
        assertEquals(ScreenBounds(100f, 100f, 300f, 200f), TopLeftRect(100.2, 99.8, 299.6, 200.4).toScreenBounds())
        assertEquals(3, wholePoints(2.6f))
    }

    @Test
    fun fr53_macos_follow_keeps_the_anchor_under_the_pointer() {
        val titleBar = 28.0
        val top = followFrameTopLeft(
            pointerX = 500.0, pointerY = 400.0, anchorX = 30.0, anchorY = 20.0, titleBarHeight = titleBar,
        )
        assertEquals(470.0 to 352.0, top)
        assertEquals(500.0, top.first + 30.0)
        assertEquals(400.0, top.second + titleBar + 20.0)
        // A window whose content runs under the title bar has none to step over.
        assertEquals(470.0 to 380.0, followFrameTopLeft(500.0, 400.0, 30.0, 20.0, 0.0))
    }

    @Test
    fun fr53_macos_primary_button_is_bit_zero_of_the_pressed_buttons() {
        assertTrue(primaryButtonDown(1uL))
        assertTrue(primaryButtonDown(3uL))
        assertFalse(primaryButtonDown(0uL))
        assertFalse(primaryButtonDown(2uL))
    }

    @Test
    fun fr53_macos_frame_waiters_are_served_once_per_frame() {
        val frames = MacosSceneFrames()
        val served = mutableListOf<Long>()
        val first: (Long) -> Unit = { served += it }
        val second: (Long) -> Unit = { served += it * 10 }
        frames.add(first)
        frames.add(second)
        assertEquals(2, frames.pending)
        assertTrue(frames.wake.tryReceive().isSuccess, "the scene is woken to wait for a frame")
        frames.draw(5L)
        assertEquals(listOf(5L, 50L), served)
        assertEquals(0, frames.pending)
        // A frame nobody waits for serves nobody; a waiter that gave up is not called.
        frames.draw(6L)
        frames.add(first)
        frames.remove(first)
        frames.draw(7L)
        assertEquals(listOf(5L, 50L), served)
    }

    @Test
    fun fr53_macos_position_round_trips_in_screen_points() {
        NSApplication.sharedApplication()
        val backend = MacosWindowBackend()
        val recorder = Recorder()
        val window = assertNotNull(backend.open(config(1, x = 100f, y = 120f), recorder))
        try {
            val opened = assertNotNull(window.bounds())
            assertEquals(100f, opened.x)
            assertEquals(120f, opened.y)
            recorder.moved.clear()
            window.setPosition(220f, 160f)
            val after = assertNotNull(window.bounds())
            assertEquals(220f, after.x)
            assertEquals(160f, after.y)
            assertEquals(opened.width, after.width)
            spinUntil { recorder.moved.isNotEmpty() }
            assertTrue(recorder.moved.isNotEmpty(), "onMoved reached the listener")
            assertTrue(abs(recorder.moved.last().x - 220f) <= 1f)
            assertTrue(abs(recorder.moved.last().y - 160f) <= 1f)
        } finally {
            window.close()
            spin()
        }
    }

    @Test
    fun fr53_macos_content_size_is_set_and_the_top_left_corner_stays() {
        NSApplication.sharedApplication()
        val backend = MacosWindowBackend()
        val window = assertNotNull(backend.open(config(1, x = 90f, y = 110f), Recorder()))
        try {
            window.setContentSize(400f, 260f)
            val bounds = assertNotNull(window.bounds())
            assertEquals(90f, bounds.x)
            assertEquals(110f, bounds.y)
            assertEquals(400f, bounds.width)
            assertTrue(bounds.height >= 260f, "the frame is the content plus the title bar")
            val origin = assertNotNull(window.contentOrigin())
            assertEquals(90f, origin.x)
            assertTrue(origin.y >= 110f)
        } finally {
            window.close()
            spin()
        }
    }

    @Test
    fun fr53_macos_close_button_reaches_the_app_and_window_stays() {
        NSApplication.sharedApplication()
        val backend = MacosWindowBackend()
        val recorder = Recorder()
        val window = assertNotNull(backend.open(config(1), recorder))
        try {
            window.native().performClose(null)
            assertEquals(1, recorder.closeRequests)
            assertTrue(window.visible(), "only the application removing the node closes the window")
            assertEquals(1, backend.windows.size)
            window.native().performClose(null)
            assertEquals(2, recorder.closeRequests)
            assertTrue(window.visible())
        } finally {
            window.close()
            spin()
        }
        assertFalse(window.visible())
        assertEquals(0, backend.windows.size)
        assertEquals(null, window.bounds(), "a closed window has no frame")
    }

    @Test
    fun fr53_macos_backend_reports_focus() {
        NSApplication.sharedApplication()
        val backend = MacosWindowBackend()
        val ra = Recorder()
        val rb = Recorder()
        val a = assertNotNull(backend.open(config(1, "fr53 a"), ra))
        val b = assertNotNull(backend.open(config(2, "fr53 b"), rb))
        try {
            assertTrue(backend.supportsMultipleWindows)
            b.focus()
            spinUntil(turns = 20) { b.isKey }
            // A runner without a logged-in session may refuse key status; what must hold is
            // that the reports and the state agree, one report per change.
            if (b.isKey) {
                assertEquals(true, rb.focus.lastOrNull())
                assertFalse(a.isKey)
                a.focus()
                spinUntil(turns = 20) { a.isKey }
                if (a.isKey) {
                    assertEquals(true, ra.focus.lastOrNull())
                    spinUntil(turns = 20) { rb.focus.lastOrNull() == false }
                    assertEquals(false, rb.focus.lastOrNull())
                    assertEquals(rb.focus.zipWithNext().count { it.first == it.second }, 0, "no repeated report")
                }
            }
        } finally {
            a.close()
            b.close()
            spin()
        }
    }

    @Test
    fun fr53_macos_follow_pointer_is_refused_while_the_button_is_up() {
        NSApplication.sharedApplication()
        val backend = MacosWindowBackend()
        val window = assertNotNull(backend.open(config(1), Recorder()))
        try {
            val pointer = assertNotNull(window.pointerOnScreen())
            assertTrue(pointer.x.isFinite() && pointer.y.isFinite())
            if (!pointer.primaryDown) assertFalse(window.followPointer(10f, 10f))
        } finally {
            window.close()
            spin()
        }
    }

    @Test
    fun fr53_macos_two_window_nodes_open_two_nswindows_and_close_one() {
        NSApplication.sharedApplication()
        val h = Harness(
            listOf(setWindow()) +
                windowNode(
                    1,
                    PropertyKind.WindowTitle to PropertyValue.Text("One"),
                    PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
                    PropertyKind.WindowPosition to PropertyValue.Bytes(floats(60f, 80f)),
                ) +
                windowNode(2, PropertyKind.WindowTitle to PropertyValue.Text("Two")),
        )
        try {
            assertEquals(listOf(1, 2), h.manager.openWindows)
            val windows = h.backend.windows
            assertEquals(2, windows.size)
            assertTrue(windows[0].native() !== windows[1].native(), "each node has its own NSWindow")
            assertTrue(windows[0].visible() && windows[1].visible())
            assertEquals("One", windows[0].native().title)
            val one = assertNotNull(windows[0].bounds())
            assertEquals(60f, one.x)
            assertEquals(80f, one.y)
            assertEquals(400f, one.width)

            // Removing a node closes that window and leaves the other alone.
            h.batch(Mutation.Remove(1))
            assertEquals(listOf(2), h.manager.openWindows)
            assertFalse(windows[0].visible())
            assertTrue(windows[1].visible())
            assertEquals(1, h.backend.windows.size)
            assertEquals(0, h.exits, "a window is left, so the loop goes on")

            // The last one under Exit ends the loop.
            h.batch(Mutation.Remove(2))
            assertEquals(1, h.exits)
            assertFalse(windows[1].visible())
            assertTrue(h.connection.events.none { it is HostEvent.ProtocolError })
        } finally {
            h.close()
        }
    }

    @Test
    fun fr53_macos_close_button_sends_the_request_and_the_node_decides() {
        NSApplication.sharedApplication()
        val h = Harness(
            listOf(setWindow()) +
                windowNode(1, PropertyKind.OnWindowCloseRequested to PropertyValue.Integer(7L)),
        )
        try {
            val window = h.backend.windows.single()
            window.native().performClose(null)
            val requests = h.connection.events.filterIsInstance<HostEvent.WindowCloseRequested>()
            assertEquals(listOf(HostEvent.WindowCloseRequested(1, 7L)), requests)
            assertTrue(window.visible())
            assertEquals(listOf(1), h.manager.openWindows)
        } finally {
            h.close()
        }
    }

    @Test
    fun fr53_macos_dock_reopen_sends_application_reopen() {
        NSApplication.sharedApplication()
        val h = Harness(listOf(setWindow(LastWindowClosed.KeepRunning)) + windowNode(1))
        try {
            val delegate = MacosApplicationDelegate()
            assertFalse(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApplication.sharedApplication()))
            delegate.applicationShouldHandleReopen(NSApplication.sharedApplication(), hasVisibleWindows = false)
            assertEquals(1, h.connection.events.count { it is HostEvent.ApplicationReopen })
            // KeepRunning: the last window going leaves the loop running.
            h.batch(Mutation.Remove(1))
            assertEquals(0, h.exits)
            assertTrue(h.backend.windows.isEmpty())
        } finally {
            h.close()
            assertEquals(null, WindowApplication.onReopen, "shutting the manager down removes the listener")
        }
    }

    @Test
    fun fr53_macos_multi_window_start_report_goes_with_the_first_window() {
        val starts = MultiWindowStarts(parityWindow = 1)
        assertEquals(MultiWindowStarts.Duties(startReport = true, parityRun = true), starts.next())
        assertEquals(MultiWindowStarts.Duties(startReport = false, parityRun = false), starts.next())
        assertEquals(MultiWindowStarts.Duties(startReport = false, parityRun = false), starts.next())
    }

    @Test
    fun fr53_macos_parity_run_goes_with_the_chosen_window() {
        assertEquals(1, MultiWindowStarts.parityWindowFrom(null))
        assertEquals(1, MultiWindowStarts.parityWindowFrom(""))
        assertEquals(1, MultiWindowStarts.parityWindowFrom("0"))
        assertEquals(1, MultiWindowStarts.parityWindowFrom("first"))
        assertEquals(3, MultiWindowStarts.parityWindowFrom(" 3 "))
        val starts = MultiWindowStarts(parityWindow = 2)
        val duties = List(3) { starts.next() }
        assertEquals(listOf(true, false, false), duties.map { it.startReport })
        assertEquals(listOf(false, true, false), duties.map { it.parityRun })
    }

    @Test
    fun fr53_macos_backend_tells_of_every_window_it_opens() {
        NSApplication.sharedApplication()
        val backend = MacosWindowBackend()
        val opened = mutableListOf<platform.AppKit.NSWindow>()
        backend.onOpened = { opened += it.window }
        val a = assertNotNull(backend.open(config(1, "fr53 a"), Recorder()))
        val b = assertNotNull(backend.open(config(2, "fr53 b"), Recorder()))
        try {
            assertEquals(listOf(a.native(), b.native()), opened)
        } finally {
            a.close()
            b.close()
            spin()
        }
    }

    @Test
    fun fr53_macos_zoom_is_per_window() {
        NSApplication.sharedApplication()
        val h = Harness(
            listOf(setWindow()) +
                windowNode(1, PropertyKind.WindowZoomLevel to PropertyValue.Integer(2L)) +
                windowNode(2),
            withScenes = true,
        )
        fun zoomOf(index: Int) = (h.backend.windows[index] as MacosPlatformWindow).zoom
        fun reported(node: Int) = h.connection.events.filterIsInstance<HostEvent.ZoomChanged>().filter { it.nodeId == node }
        try {
            assertEquals(listOf(1, 2), h.manager.openWindows)
            assertEquals(2, zoomOf(0).level, "the level on node 1 is window 1's")
            assertEquals(0, zoomOf(1).level, "window 2 asked for none and keeps its own")
            spinUntil(turns = 100) { reported(1).isNotEmpty() && reported(2).isNotEmpty() }
            assertEquals(2, reported(1).last().level)
            assertEquals(0, reported(2).last().level)
            assertTrue(
                h.connection.events.filterIsInstance<HostEvent.ZoomChanged>().none { it.nodeId !in setOf(1, 2) },
                "every ZoomChanged names the window's node",
            )

            // A level set on node 2 changes window 2 and leaves window 1 where it was.
            h.batch(Mutation.SetProp(2, PropertyKind.WindowZoomLevel, PropertyValue.Integer(-1L)))
            assertEquals(-1, zoomOf(1).level)
            assertEquals(2, zoomOf(0).level)
            spinUntil(turns = 100) { reported(2).last().level == -1 }
            assertEquals(-1, reported(2).last().level)
            assertTrue(reported(1).all { it.level == 2 }, "window 1 reported no other level")
            assertTrue(reported(2).last().k < reported(1).last().k)
        } finally {
            h.close()
        }
    }

    @Test
    fun fr53_macos_window_requested_matches_restore_key() {
        NSApplication.sharedApplication()
        val h = Harness(listOf(setWindow(LastWindowClosed.KeepRunning)) + windowNode(1))
        try {
            // The application answers the key with a node of that key: the window the system
            // made is that node's window, and no second one opens.
            h.connection.answers["ws:1"] = windowNode(
                8,
                PropertyKind.WindowRestoreKey to PropertyValue.Text("ws:1"),
                PropertyKind.WindowTitle to PropertyValue.Text("Restored"),
            )
            assertTrue(h.backend.requestWindow("ws:1"))
            val requested = h.connection.events.filterIsInstance<HostEvent.WindowRequested>()
            assertEquals(listOf("ws:1"), requested.map { it.key })
            assertEquals(0, requested.single().nodeId, "the request goes to the root")
            assertEquals(listOf(1, 8), h.manager.openWindows)
            assertEquals(2, h.backend.windows.size)
            val adopted = h.backend.windows[1] as MacosPlatformWindow
            assertEquals(8, adopted.nodeId, "the window draws the node that answered")
            assertEquals("Restored", adopted.nativeWindow.title)
            assertTrue(adopted.nativeWindow.visible)

            // A key the application does not answer leaves no window behind.
            assertFalse(h.backend.requestWindow("gone"))
            spin()
            assertEquals(listOf(1, 8), h.manager.openWindows)
            assertEquals(2, h.backend.windows.size)

            // The new tab button asks with an empty key.
            h.connection.answers[""] = windowNode(9)
            MacosApplicationDelegate().newWindowForTab(h.backend.windows[0].native())
            assertEquals("", h.connection.events.filterIsInstance<HostEvent.WindowRequested>().last().key)
            assertEquals(listOf(1, 8, 9), h.manager.openWindows)
            assertEquals(3, h.backend.windows.size)
            assertTrue(h.connection.events.none { it is HostEvent.ProtocolError })
        } finally {
            h.close()
        }
    }

    @Test
    fun fr53_macos_restore_asks_for_the_windows_the_last_quit_left() {
        val store = object : RestoreKeyStore {
            var saved = listOf<String>()
            override fun load() = saved
            override fun save(keys: List<String>) {
                saved = keys
            }
        }
        var keeps = true
        val restore = WindowRestore(store, keepsWindows = { keeps })
        restore.remember(listOf("ws:1", "", "ws:2", "ws:1"))
        assertEquals(listOf("ws:1", "ws:2"), store.saved, "empty and repeated keys are not kept")
        assertEquals(listOf("ws:1"), restore.keysToRequest(listOf("ws:2")), "a window already open is not asked for again")
        keeps = false
        assertEquals(emptyList(), restore.keysToRequest(emptyList()), "a system set to close windows at a quit restores none")
        restore.remember(emptyList())
        keeps = true
        assertEquals(emptyList(), restore.keysToRequest(emptyList()))
    }

    @Test
    fun fr53_macos_idle_soak_ends_once_while_the_process_waits_to_quit() {
        val second = 1_000_000_000L
        val soak = KnIdleSoakReport(seconds = 30, openedAt = 0L)
        assertFalse(soak.tick(1 * second), "the soak waits for the windows to settle first")
        assertFalse(soak.tick(9 * second), "the soak starts here")
        assertFalse(soak.tick(20 * second))
        assertTrue(soak.tick(39 * second), "thirty seconds after the start the soak ends")
        // The process lives a moment longer so a script can read its CPU time; the turns in
        // that moment keep calling tick and must not end the soak, or print its end, again.
        for (turn in 1..60) assertFalse(soak.tick(39 * second + turn * 16_000_000L))
    }

    @Test
    fun fr53_macos_idle_soak_runs_once_for_the_selected_window() {
        // Only the chosen window carries the soak, however many open.
        val starts = MultiWindowStarts(MultiWindowStarts.parityWindowFrom("2"))
        assertEquals(listOf(false, true, false), List(3) { starts.next().parityRun })

        // The setting is read once and taken out of the environment, so the window library
        // finds nothing to start a soak of its own with in any window.
        IdleSoakSetting.resetForTest()
        platform.posix.setenv("DXC_IDLE_SOAK_SECONDS", "30", 1)
        assertEquals(30, IdleSoakSetting.seconds())
        assertEquals(null, platform.posix.getenv("DXC_IDLE_SOAK_SECONDS"))
        assertEquals(30, IdleSoakSetting.seconds())
        assertTrue(IdleSoakSetting.requested())
        IdleSoakSetting.resetForTest()
        assertEquals(null, IdleSoakSetting.seconds())
        assertEquals(null, IdleSoakSetting.parse("0"))
        assertEquals(null, IdleSoakSetting.parse("soon"))
        assertEquals(12, IdleSoakSetting.parse(" 12 "))
        IdleSoakSetting.resetForTest()
    }
}
