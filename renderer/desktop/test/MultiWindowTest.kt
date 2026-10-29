package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.WindowSizeReporter
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.platform.ApplicationListener
import dev.darkpyonix.composerust.ui.platform.DropTarget
import dev.darkpyonix.composerust.ui.platform.DropTargets
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.PlatformWindow
import dev.darkpyonix.composerust.ui.platform.PlatformWindowConfig
import dev.darkpyonix.composerust.ui.platform.PlatformWindowListener
import dev.darkpyonix.composerust.ui.platform.ScreenBounds
import dev.darkpyonix.composerust.ui.platform.ScreenPointer
import dev.darkpyonix.composerust.ui.platform.WindowBackend
import dev.darkpyonix.composerust.ui.platform.WindowManager
import dev.darkpyonix.composerust.ui.platform.serveWindowFrames
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield

// Several windows in one process. The window system is a fake, so what is proved is the
// interpreter's and the manager's logic, which the three desktop layers share.

private const val APPEND = -1

// ---- the fake window system --------------------------------------------------------------

private class MwWindow(
    val backend: MwBackend,
    val config: PlatformWindowConfig,
    var sink: PlatformWindowListener,
) : PlatformWindow {
    var frame = ScreenBounds(
        config.x ?: (40f * (backend.opened.size + 1)),
        config.y ?: (30f * (backend.opened.size + 1)),
        if (config.width > 0f) config.width else 800f,
        if (config.height > 0f) config.height else 600f,
    )
    var shownTitle = config.title
    var key = false
    var closed = false
    var following = false
    var anchorX = 0f
    var anchorY = 0f
    var scheme = config.colorScheme
    val calls = mutableListOf<String>()
    private val frameWaiters = mutableListOf<(Long) -> Unit>()

    val pendingFrameWaiters: Int get() = frameWaiters.size

    override fun setListener(listener: PlatformWindowListener) {
        this.sink = listener
    }

    override fun setTitle(title: String) {
        calls += "setTitle"
        this.shownTitle = title
    }

    override fun setPosition(x: Float, y: Float) {
        calls += "setPosition"
        frame = frame.copy(x = x, y = y)
        sink.onMoved(frame)
    }

    override fun setContentSize(width: Float, height: Float) {
        calls += "setContentSize"
        frame = frame.copy(width = width, height = height)
        sink.onMoved(frame)
    }

    override fun bounds(): ScreenBounds? = frame

    override fun focus() {
        calls += "focus"
        backend.makeKey(this)
    }

    override val isKey: Boolean get() = key

    override fun pointerOnScreen(): ScreenPointer? = backend.pointer

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean {
        calls += "followPointer"
        if (backend.pointer?.primaryDown != true) return false
        following = true
        this.anchorX = anchorX
        this.anchorY = anchorY
        return true
    }

    override fun setColorScheme(scheme: ColorScheme) {
        this.scheme = scheme
    }

    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        var waiter: ((Long) -> Unit)? = null
        try {
            suspendCancellableCoroutine<Unit> { c ->
                val callback: (Long) -> Unit = { nanos ->
                    onFrame(nanos)
                    c.resumeWith(Result.success(Unit))
                }
                waiter = callback
                frameWaiters += callback
            }
        } finally {
            waiter?.let { frameWaiters.remove(it) }
        }
    }

    /** The window draws a frame: everything waiting on its clock is told. */
    fun drawFrame(nanos: Long) {
        val waiting = frameWaiters.toList()
        frameWaiters.clear()
        waiting.forEach { it(nanos) }
    }

    override fun close() {
        calls += "close"
        closed = true
        following = false
        backend.live.remove(this)
    }

    // What the user does.
    fun userMove(x: Float, y: Float) {
        frame = frame.copy(x = x, y = y)
        sink.onMoved(frame)
    }

    fun userClose() = sink.onCloseRequested()
}

private class MwBackend(
    override var supportsMultipleWindows: Boolean = true,
    var refuse: Boolean = false,
) : WindowBackend {
    val opened = mutableListOf<MwWindow>()
    val live = mutableListOf<MwWindow>()
    var pointer: ScreenPointer? = null
    var application: ApplicationListener? = null

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        if (refuse) return null
        val window = MwWindow(this, config, listener)
        opened += window
        live += window
        return window
    }

    override fun setApplicationListener(listener: ApplicationListener?) {
        application = listener
    }

    fun windowFor(nodeId: Int): MwWindow = opened.last { it.config.nodeId == nodeId }

    fun makeKey(window: MwWindow) {
        for (other in live) {
            if (other !== window && other.key) {
                other.key = false
                other.sink.onFocusChanged(false)
            }
        }
        if (!window.key) {
            window.key = true
            window.sink.onFocusChanged(true)
        }
    }

    /** The pointer moves; a window being dragged follows it. */
    fun movePointer(x: Float, y: Float, down: Boolean = true) {
        pointer = ScreenPointer(x, y, down)
        for (window in live.toList()) {
            if (window.following) {
                window.frame = window.frame.copy(x = x - window.anchorX, y = y - window.anchorY)
                window.sink.onMoved(window.frame)
            }
        }
    }

    fun releasePointer(cancelled: Boolean = false) {
        val last = pointer
        if (last != null) pointer = last.copy(primaryDown = false)
        for (window in live.toList()) {
            if (window.following) {
                window.following = false
                window.sink.onFollowEnded(cancelled)
            }
        }
    }
}

// ---- the fake Host -----------------------------------------------------------------------

private class MwConnection(private val initial: List<Mutation>) : HostConnection {
    val events = mutableListOf<HostEvent>()
    var renderFrames = 0
    val frameBatches = ArrayDeque<List<Mutation>>()
    private var next: List<Mutation> = emptyList()
    val answers = mutableMapOf<Class<*>, (HostEvent) -> List<Mutation>>()

    /** What the next dispatched `Clicked` answers with: a batch from the application. */
    fun queue(mutations: List<Mutation>) {
        next = mutations
    }

    override fun init(onMutation: (Mutation) -> Unit) {
        initial.forEach(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        events += event
        if (event is HostEvent.Clicked) {
            val batch = next
            next = emptyList()
            batch.forEach(onMutation)
        } else {
            answers[event::class.java]?.invoke(event)?.forEach(onMutation)
        }
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        renderFrames++
        frameBatches.removeFirstOrNull()?.forEach(onMutation)
    }

    override fun shutdown() {}

    inline fun <reified T : HostEvent> all(): List<T> = events.filterIsInstance<T>()
}

private fun setWindow(mode: WindowMode = WindowMode.Windows, last: LastWindowClosed = LastWindowClosed.Exit) =
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
            mode = mode,
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

private fun dragBytes(pointerId: Int, anchorX: Float, anchorY: Float): ByteArray {
    val out = ByteArray(12)
    for (b in 0 until 4) out[b] = (pointerId shr (8 * b)).toByte()
    floats(anchorX, anchorY).copyInto(out, 4)
    return out
}

private fun text(value: String) = PropertyValue.Text(value)
private fun handler(id: Long) = PropertyValue.Integer(id)

/** A `Window` node under the root with its properties. */
private fun window(id: Int, vararg props: Pair<PropertyKind, PropertyValue>): List<Mutation> =
    buildList {
        add(Mutation.Create(id, WidgetKind.Window))
        for ((kind, value) in props) add(Mutation.SetProp(id, kind, value))
        add(Mutation.Insert(0, id, APPEND))
    }

private fun child(parent: Int, id: Int, widget: WidgetKind = WidgetKind.Box, vararg props: Pair<PropertyKind, PropertyValue>): List<Mutation> =
    buildList {
        add(Mutation.Create(id, widget))
        for ((kind, value) in props) add(Mutation.SetProp(id, kind, value))
        add(Mutation.Insert(parent, id, APPEND))
    }

private class MwHarness(
    initial: List<Mutation>,
    val backend: MwBackend = MwBackend(),
    targets: DropTargets = DropTargets.None,
) {
    val connection = MwConnection(initial)
    val host = ComposeRustHost(connection)
    val captured = mutableListOf<Int>()
    var exits = 0
    val manager = WindowManager(host, backend, onExit = { exits++ }, dropTargets = targets, cancelCapture = { captured += it })

    init {
        host.start(FrameRequestSource())
        manager.attach()
    }

    /** The application sends a batch: it answers the next event the renderer dispatches. */
    fun batch(vararg groups: List<Mutation>) {
        connection.queue(groups.flatMap { it })
        host.dispatch(HostEvent.Clicked(nodeId = 0, handlerId = 0))
    }

    fun batch(single: Mutation) = batch(listOf(single))

    fun errors(): List<HostEvent.ProtocolError> = connection.all<HostEvent.ProtocolError>()
}

class MultiWindowTest {

    @Test
    fun fr53_window_nodes_open_and_close_platform_windows() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(
                    1,
                    PropertyKind.WindowTitle to text("One"),
                    PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
                    PropertyKind.WindowPosition to PropertyValue.Bytes(floats(10f, 20f)),
                ) +
                window(2, PropertyKind.WindowTitle to text("Two")),
        )
        assertEquals(listOf(1, 2), h.manager.openWindows)
        val one = h.backend.windowFor(1).config
        assertEquals("One", one.title)
        assertEquals(400f, one.width)
        assertEquals(300f, one.height)
        assertEquals(10f, one.x)
        assertEquals(20f, one.y)
        assertEquals("Two", h.backend.windowFor(2).config.title)
        assertNull(h.backend.windowFor(2).config.x)

        // Removing a node closes that window and leaves the other alone.
        h.batch(Mutation.Remove(1))
        assertTrue(h.backend.windowFor(1).closed)
        assertFalse(h.backend.windowFor(2).closed)
        assertEquals(listOf(2), h.manager.openWindows)

        // A window opened later is one more.
        h.batch(window(3, PropertyKind.WindowTitle to text("Three")))
        assertEquals(listOf(2, 3), h.manager.openWindows)
        assertTrue(h.errors().isEmpty())
    }

    @Test
    fun fr53_one_render_frame_serves_every_window() = runBlocking {
        val h = MwHarness(listOf(setWindow()) + window(1) + window(2) + window(3))
        val textNodes = child(1, 10, WidgetKind.Text, PropertyKind.Text to text("a")) +
            child(2, 20, WidgetKind.Text, PropertyKind.Text to text("b"))
        h.batch(textNodes)
        h.connection.frameBatches.addLast(
            listOf(
                Mutation.SetProp(10, PropertyKind.Text, text("a2")),
                Mutation.SetProp(20, PropertyKind.Text, text("b2")),
            ),
        )
        val requests = FrameRequestSource()
        val job = launch {
            serveWindowFrames(h.host, h.manager, requests, stoppedAfterMillis = 60_000, alreadyServed = 0)
        }
        settle()
        // A worker asks many times; three windows are open.
        repeat(5) { requests.request() }
        settle()
        for (nodeId in 1..3) assertEquals(1, h.backend.windowFor(nodeId).pendingFrameWaiters, "window $nodeId waits for its clock")
        assertEquals(0, h.connection.renderFrames)

        // The second window draws first. One call, and nothing is left waiting on the others.
        h.backend.windowFor(2).drawFrame(16_000_000L)
        settle()
        assertEquals(1, h.connection.renderFrames)
        for (nodeId in 1..3) assertEquals(0, h.backend.windowFor(nodeId).pendingFrameWaiters)
        // Both windows' changes arrived in that one batch.
        assertEquals("a2", h.host.table.node(10)?.text(PropertyKind.Text))
        assertEquals("b2", h.host.table.node(20)?.text(PropertyKind.Text))

        requests.request()
        settle()
        h.backend.windowFor(3).drawFrame(32_000_000L)
        settle()
        assertEquals(2, h.connection.renderFrames)
        job.cancel()
    }

    @Test
    fun fr53_stopped_window_clocks_are_served_directly() = runBlocking {
        val h = MwHarness(listOf(setWindow()) + window(1) + window(2))
        val requests = FrameRequestSource()
        val job = launch {
            serveWindowFrames(h.host, h.manager, requests, stoppedAfterMillis = 20, alreadyServed = 0)
        }
        settle()
        repeat(3) { requests.request() }
        // No window draws: all are hidden. The Host is still called, once.
        delay(300)
        assertEquals(1, h.connection.renderFrames)
        job.cancel()
    }

    @Test
    fun fr53_window_events_name_their_window() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(1) +
                window(2, PropertyKind.OnWindowFocusChanged to handler(22)),
        )
        val first = h.backend.windowFor(1).sink
        val second = h.backend.windowFor(2).sink

        // Size class: only the window that crossed a class boundary says so, by its node id.
        second.onContentSizeChanged(1400f, 900f)
        val sizes = h.connection.all<HostEvent.WindowSizeChanged>()
        assertEquals(1, sizes.size)
        assertEquals(2, sizes[0].nodeId)
        assertTrue(sizes[0].sizeClass != WindowSizeClass.Compact)
        first.onContentSizeChanged(300f, 400f)
        assertEquals(1, h.connection.all<HostEvent.WindowSizeChanged>().size)

        // Zoom: named by window, and not repeated when it did not change.
        second.onZoomChanged(1.25f, 1f, 2)
        second.onZoomChanged(1.25f, 1f, 2)
        val zooms = h.connection.all<HostEvent.ZoomChanged>()
        assertEquals(1, zooms.size)
        assertEquals(2, zooms[0].nodeId)
        assertEquals(2, zooms[0].level)

        // Focus reaches the window whose node registered for it, and only that one.
        h.backend.makeKey(h.backend.windowFor(1))
        assertTrue(h.connection.all<HostEvent.WindowFocusChanged>().isEmpty())
        h.backend.makeKey(h.backend.windowFor(2))
        val focus = h.connection.all<HostEvent.WindowFocusChanged>()
        assertEquals(1, focus.size)
        assertEquals(2, focus[0].nodeId)
        assertEquals(22L, focus[0].handlerId)
        assertTrue(focus[0].focused)
    }

    @Test
    fun fr53_close_request_leaves_the_window_to_the_app() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(1, PropertyKind.OnWindowCloseRequested to handler(11)) +
                window(2),
        )
        h.backend.windowFor(1).userClose()
        val requests = h.connection.all<HostEvent.WindowCloseRequested>()
        assertEquals(1, requests.size)
        assertEquals(1, requests[0].nodeId)
        assertEquals(11L, requests[0].handlerId)
        // Asked, not closed: the window is open until the app removes its node.
        assertFalse(h.backend.windowFor(1).closed)
        assertEquals(listOf(1, 2), h.manager.openWindows)

        // A window with no handler for it ignores the request and stays.
        h.backend.windowFor(2).userClose()
        assertEquals(1, h.connection.all<HostEvent.WindowCloseRequested>().size)
        assertFalse(h.backend.windowFor(2).closed)

        h.batch(Mutation.Remove(1))
        assertTrue(h.backend.windowFor(1).closed)
    }

    @Test
    fun fr53_last_window_closed_policy() {
        val exit = MwHarness(listOf(setWindow(last = LastWindowClosed.Exit)) + window(1) + window(2))
        exit.batch(Mutation.Remove(1))
        assertEquals(0, exit.exits)
        exit.batch(Mutation.Remove(2))
        assertEquals(1, exit.exits, "the loop ends after the batch that removed the last window")
        assertTrue(exit.backend.windowFor(2).closed)
        exit.batch(emptyList())
        assertEquals(1, exit.exits)

        val keep = MwHarness(listOf(setWindow(last = LastWindowClosed.KeepRunning)) + window(1))
        keep.batch(Mutation.Remove(1))
        assertEquals(0, keep.exits)
        assertTrue(keep.manager.openWindows.isEmpty())
        // The app answers a Dock click by putting a window up.
        keep.connection.answers[HostEvent.ApplicationReopen::class.java] = { window(7) }
        keep.backend.application!!.onReopen()
        assertEquals(1, keep.connection.all<HostEvent.ApplicationReopen>().size)
        val reopen = keep.connection.all<HostEvent.ApplicationReopen>()[0]
        assertEquals(0, reopen.nodeId)
        assertEquals(0L, reopen.handlerId)
        assertEquals(listOf(7), keep.manager.openWindows)
        assertEquals(0, keep.exits)

        // Never having had a window is not the last one closing.
        val empty = MwHarness(listOf(setWindow()))
        empty.batch(emptyList())
        assertEquals(0, empty.exits)
    }

    @Test
    fun fr53_focus_moves_between_windows() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(1, PropertyKind.OnWindowFocusChanged to handler(1)) +
                window(2, PropertyKind.OnWindowFocusChanged to handler(2)),
        )
        h.backend.makeKey(h.backend.windowFor(1))
        // FocusWindow brings the second one forward and makes it the key window.
        h.batch(Mutation.FocusWindow(2))
        assertTrue(h.backend.windowFor(2).calls.contains("focus"))
        assertTrue(h.backend.windowFor(2).key)
        assertFalse(h.backend.windowFor(1).key)
        val changes = h.connection.all<HostEvent.WindowFocusChanged>().map { Triple(it.nodeId, it.focused, it.handlerId) }
        assertEquals(
            listOf(Triple(1, true, 1L), Triple(1, false, 1L), Triple(2, true, 2L)),
            changes,
            "the one that lost it and the one that gained it hear once each",
        )
        // Asking again changes nothing and says nothing.
        h.batch(Mutation.FocusWindow(2))
        assertEquals(3, h.connection.all<HostEvent.WindowFocusChanged>().size)

        // A command that names no window is a protocol error, and the process goes on.
        h.batch(Mutation.FocusWindow(99))
        assertEquals(TableError.UNKNOWN_NODE, h.errors().last().code)
    }

    @Test
    fun fr53_programmatic_move_and_user_move() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(
                    1,
                    PropertyKind.WindowPosition to PropertyValue.Bytes(floats(10f, 20f)),
                    PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
                    PropertyKind.OnWindowMoved to handler(5),
                ),
        )
        val fake = h.backend.windowFor(1)
        assertEquals(10f, fake.frame.x)

        // The user drags the window twice within a frame: one WindowMoved, with the last place.
        fake.userMove(100f, 120f)
        fake.userMove(300f, 310f)
        assertTrue(h.connection.all<HostEvent.WindowMoved>().isEmpty())
        h.manager.flushFrame()
        h.manager.flushFrame()
        val moved = h.connection.all<HostEvent.WindowMoved>()
        assertEquals(1, moved.size)
        assertEquals(1, moved[0].nodeId)
        assertEquals(5L, moved[0].handlerId)
        assertEquals(300f, moved[0].x)
        assertEquals(310f, moved[0].y)

        // The app sends the same Position again: it is not a change, the window stays where
        // the user put it.
        h.batch(Mutation.SetProp(1, PropertyKind.WindowPosition, PropertyValue.Bytes(floats(10f, 20f))))
        assertFalse(fake.calls.contains("setPosition"))
        assertEquals(300f, fake.frame.x)

        // A new value moves it, and the real place comes back as WindowMoved.
        h.batch(Mutation.SetProp(1, PropertyKind.WindowPosition, PropertyValue.Bytes(floats(50f, 60f))))
        assertEquals(1, fake.calls.count { it == "setPosition" })
        h.manager.flushFrame()
        val last = h.connection.all<HostEvent.WindowMoved>().last()
        assertTrue(abs(last.x - 50f) < 1f && abs(last.y - 60f) < 1f)

        // Size and title follow the same rule: applied when they change.
        h.batch(Mutation.SetProp(1, PropertyKind.WindowSize, PropertyValue.Bytes(floats(400f, 300f))))
        assertFalse(fake.calls.contains("setContentSize"))
        h.batch(Mutation.SetProp(1, PropertyKind.WindowSize, PropertyValue.Bytes(floats(500f, 350f))))
        assertEquals(1, fake.calls.count { it == "setContentSize" })
        h.batch(Mutation.SetProp(1, PropertyKind.WindowTitle, text("New")))
        assertEquals("New", fake.shownTitle)
    }

    @Test
    fun fr53_torn_off_window_follows_the_pointer() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(
                    1,
                    PropertyKind.WindowPosition to PropertyValue.Bytes(floats(0f, 0f)),
                    PropertyKind.WindowSize to PropertyValue.Bytes(floats(50f, 50f)),
                ) +
                child(1, 10, WidgetKind.Box, PropertyKind.TearOffKey to text("tab:a")),
        )
        h.backend.pointer = ScreenPointer(100f, 100f, true)

        // The Host decides to tear a tab off: its new window carries DragFromPointer.
        h.batch(
            window(
                5,
                PropertyKind.DragFromPointer to PropertyValue.Bytes(dragBytes(3, 30f, 10f)),
                PropertyKind.OnWindowDrag to handler(55),
            ),
        )
        val torn = h.backend.windowFor(5)
        assertTrue(torn.following)
        assertEquals(listOf(3), h.captured, "the node that held the pointer is let go of")

        h.backend.movePointer(200f, 150f)
        // Every frame the anchor point is under the pointer, within 1dp.
        assertTrue(abs(torn.frame.x + 30f - 200f) < 1f)
        assertTrue(abs(torn.frame.y + 10f - 150f) < 1f)
        h.manager.flushFrame()
        h.manager.flushFrame()
        var drags = h.connection.all<HostEvent.WindowDrag>()
        assertEquals(1, drags.size, "one Move per frame, none when the pointer did not move")
        assertEquals(1, drags[0].phase)
        assertEquals(5, drags[0].nodeId)
        assertEquals(55L, drags[0].handlerId)
        assertEquals(200f, drags[0].screenX)
        assertEquals(150f, drags[0].screenY)
        assertEquals(0, drags[0].overWindow)

        h.backend.movePointer(260f, 180f)
        h.manager.flushFrame()
        h.backend.releasePointer()
        drags = h.connection.all<HostEvent.WindowDrag>()
        assertEquals(listOf(1, 1, 2), drags.map { it.phase })
        assertEquals(260f, drags.last().screenX)
        assertEquals(180f, drags.last().screenY)
        // After the end nothing more is sent.
        h.manager.flushFrame()
        assertEquals(3, h.connection.all<HostEvent.WindowDrag>().size)

        // A pointer already released: the window opens in place and does not follow.
        h.batch(window(6, PropertyKind.DragFromPointer to PropertyValue.Bytes(dragBytes(4, 0f, 0f))))
        assertFalse(h.backend.windowFor(6).following)

        // The property is read when the window opens only: added later, it is ignored.
        h.backend.pointer = ScreenPointer(10f, 10f, true)
        h.batch(Mutation.SetProp(6, PropertyKind.DragFromPointer, PropertyValue.Bytes(dragBytes(4, 5f, 5f))))
        assertEquals(1, h.backend.windowFor(6).calls.count { it == "followPointer" })
    }

    @Test
    fun fr53_tear_off_key_asks_the_app_for_a_window() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(1) +
                child(1, 10, WidgetKind.Box, PropertyKind.TearOffKey to text("tab:a")) +
                child(1, 11, WidgetKind.Box),
        )
        h.backend.pointer = ScreenPointer(300f, 200f, true)
        // The app answers the request with a window under the key; the renderer adds the drag.
        h.connection.answers[HostEvent.WindowRequested::class.java] = { event ->
            val key = (event as HostEvent.WindowRequested).key
            window(
                6,
                PropertyKind.WindowRestoreKey to text(key),
                PropertyKind.OnWindowDrag to handler(66),
            )
        }
        assertFalse(h.manager.tearOff(11, pointerId = 2, anchorX = 5f, anchorY = 5f), "a node without a key is not torn off")
        assertTrue(h.connection.all<HostEvent.WindowRequested>().isEmpty())

        assertTrue(h.manager.tearOff(10, pointerId = 2, anchorX = 12f, anchorY = 8f))
        val asked = h.connection.all<HostEvent.WindowRequested>()
        assertEquals(1, asked.size)
        assertEquals("tab:a", asked[0].key)
        assertEquals(0, asked[0].nodeId)
        assertEquals(0L, asked[0].handlerId)
        assertNotNull(h.host.table.node(6)?.bytes(PropertyKind.DragFromPointer), "the renderer adds DragFromPointer")
        val torn = h.backend.windowFor(6)
        assertTrue(torn.following)
        h.backend.movePointer(400f, 260f)
        assertTrue(abs(torn.frame.x + 12f - 400f) < 1f)
        assertTrue(abs(torn.frame.y + 8f - 260f) < 1f)

        // A request nobody answers opens nothing (the tab stays where it was).
        h.connection.answers.clear()
        assertTrue(h.manager.tearOff(10, pointerId = 2, anchorX = 12f, anchorY = 8f))
        assertEquals(listOf(1, 6), h.manager.openWindows)
    }

    @Test
    fun fr53_system_made_window_is_matched_by_restore_key() {
        val h = MwHarness(listOf(setWindow()) + window(1))
        val system = MwWindow(
            h.backend,
            PlatformWindowConfig(nodeId = 0),
            object : PlatformWindowListener {
                override fun onCloseRequested() {}
                override fun onFocusChanged(focused: Boolean) {}
                override fun onMoved(bounds: ScreenBounds) {}
                override fun onFollowEnded(cancelled: Boolean) {}
            },
        )
        h.backend.live += system

        // The app puts a Window up under that key: the system's window is its window.
        h.connection.answers[HostEvent.WindowRequested::class.java] = {
            window(8, PropertyKind.WindowRestoreKey to text("ws:1"), PropertyKind.WindowTitle to text("Restored"))
        }
        h.backend.application!!.onWindowRequested("ws:1", system)
        assertFalse(system.closed)
        assertEquals("Restored", system.shownTitle)
        assertEquals(listOf(1, 8), h.manager.openWindows)

        // One the app does not answer with a node is closed.
        h.connection.answers.clear()
        val stray = MwWindow(h.backend, PlatformWindowConfig(nodeId = 0), system.sink)
        h.backend.live += stray
        h.backend.application!!.onWindowRequested("gone", stray)
        assertTrue(stray.closed)
        assertEquals(listOf(1, 8), h.manager.openWindows)
    }

    @Test
    fun fr53_drag_over_reaches_the_drop_target() {
        // Window 2 has a tab strip at (10..110, 10..60) of its content; window 1 has one at the
        // same place of its own, which the dragged window must not hit.
        val targets = DropTargets { windowId, x, y ->
            if (x in 10f..110f && y in 10f..60f) {
                DropTarget(if (windowId == 2) 20 else 10, x - 10f, y - 10f)
            } else {
                null
            }
        }
        val h = MwHarness(
            initial = listOf(setWindow()) +
                window(2, PropertyKind.WindowPosition to PropertyValue.Bytes(floats(500f, 0f)), PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f))) +
                child(2, 20, WidgetKind.Box, PropertyKind.OnWindowDragOver to handler(33)),
            targets = targets,
        )
        h.backend.pointer = ScreenPointer(520f, 400f, true)
        h.batch(
            window(
                1,
                PropertyKind.WindowPosition to PropertyValue.Bytes(floats(0f, 350f)),
                PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
                PropertyKind.DragFromPointer to PropertyValue.Bytes(dragBytes(1, 20f, 10f)),
                PropertyKind.OnWindowDrag to handler(11),
            ) + child(1, 10, WidgetKind.Box, PropertyKind.OnWindowDragOver to handler(44)),
        )
        assertTrue(h.backend.windowFor(1).following)

        fun overKinds() = h.connection.all<HostEvent.WindowDragOver>().map { it.kind }

        // Into window 2's strip: Enter, in the node's own coordinates, naming the dragged window.
        h.backend.movePointer(520f, 30f)
        h.manager.flushFrame()
        assertEquals(listOf(1), overKinds())
        val enter = h.connection.all<HostEvent.WindowDragOver>()[0]
        assertEquals(20, enter.nodeId)
        assertEquals(33L, enter.handlerId)
        assertEquals(1, enter.window)
        assertEquals(10f, enter.x)
        assertEquals(20f, enter.y)
        assertEquals(2, h.connection.all<HostEvent.WindowDrag>().last().overWindow)

        // Moving inside: Move.
        h.backend.movePointer(530f, 40f)
        h.manager.flushFrame()
        assertEquals(listOf(1, 2), overKinds())
        assertEquals(20f, h.connection.all<HostEvent.WindowDragOver>()[1].x)

        // Out of the strip but over the window: Leave. Back in: Enter.
        h.backend.movePointer(700f, 200f)
        h.manager.flushFrame()
        h.backend.movePointer(540f, 20f)
        h.manager.flushFrame()
        assertEquals(listOf(1, 2, 3, 1), overKinds())

        // Released over it: Drop, then the end of the drag.
        h.backend.releasePointer()
        assertEquals(listOf(1, 2, 3, 1, 4), overKinds())
        val drops = h.connection.all<HostEvent.WindowDragOver>()
        assertTrue(drops.all { it.nodeId == 20 }, "the dragged window's own strip is never hit")
        assertEquals(2, h.connection.all<HostEvent.WindowDrag>().last().phase)
        assertEquals(2, h.connection.all<HostEvent.WindowDrag>().last().overWindow)
    }

    @Test
    fun fr53_unsupported_platform_reports_unavailable() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(1) +
                window(2, PropertyKind.OnWindowUnavailable to handler(9)),
            backend = MwBackend(supportsMultipleWindows = false),
        )
        assertEquals(listOf(1), h.manager.openWindows)
        val unavailable = h.connection.all<HostEvent.WindowUnavailable>()
        assertEquals(1, unavailable.size)
        assertEquals(2, unavailable[0].nodeId)
        assertEquals(9L, unavailable[0].handlerId)
        // Said once, however many batches follow.
        h.batch(emptyList())
        h.batch(Mutation.SetProp(2, PropertyKind.WindowTitle, text("still here")))
        assertEquals(1, h.connection.all<HostEvent.WindowUnavailable>().size)
        assertEquals(1, h.backend.opened.size)

        // A platform that supports many windows but refuses one says the same.
        val refusing = MwHarness(
            listOf(setWindow()) + window(3, PropertyKind.OnWindowUnavailable to handler(10)),
            backend = MwBackend(refuse = true),
        )
        assertEquals(1, refusing.connection.all<HostEvent.WindowUnavailable>().size)
        assertTrue(refusing.manager.openWindows.isEmpty())
        // The node is still the app's: removing it is fine and ends the loop under Exit.
        refusing.batch(Mutation.Remove(3))
        assertEquals(1, refusing.exits)
    }

    @Test
    fun fr53_system_closed_window_keeps_its_node_until_the_app_removes_it() {
        val h = MwHarness(listOf(setWindow()) + window(1, PropertyKind.OnWindowClosed to handler(3)) + window(2))
        h.backend.windowFor(1).sink.onClosedBySystem()
        val closed = h.connection.all<HostEvent.WindowClosed>()
        assertEquals(1, closed.size)
        assertEquals(1, closed[0].nodeId)
        // Mutations to its content apply to the table and are not an error.
        h.batch(child(1, 10, WidgetKind.Text, PropertyKind.Text to text("x")))
        assertTrue(h.errors().isEmpty())
        assertEquals(listOf(2), h.manager.openWindows)
        h.batch(Mutation.Remove(1))
        assertEquals(listOf(2), h.manager.openWindows)
        assertEquals(0, h.exits)
    }

    @Test
    fun fr53_window_theme_overrides_one_window() {
        val app = Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)
        val dark = Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Dark, false)
        val h = MwHarness(listOf(setWindow(), Mutation.SetTheme(app)) + window(1) + window(2))
        h.batch(Mutation.SetWindowTheme(2, dark))
        assertEquals(ColorScheme.Light, h.host.table.themeFor(1)?.colorScheme)
        assertEquals(ColorScheme.Dark, h.host.table.themeFor(2)?.colorScheme)
        assertEquals(ColorScheme.Dark, h.backend.windowFor(2).scheme)
        assertEquals(ColorScheme.Light, h.host.table.themeFor(0)?.colorScheme)
        h.batch(Mutation.SetWindowTheme(1, dark))
        assertEquals(ColorScheme.Dark, h.backend.windowFor(1).scheme)
        h.batch(Mutation.Remove(2))
        assertEquals(1, h.host.table.windowThemeCount)
        // A theme for a node that is not a window is refused.
        h.batch(child(1, 10))
        h.batch(Mutation.SetWindowTheme(10, dark))
        assertEquals(TableError.UNSUPPORTED_PROPERTY, h.errors().last().code)
    }

    @Test
    fun nfr7_misplaced_window_is_a_protocol_error() {
        val h = MwHarness(
            listOf(setWindow()) +
                window(1) +
                window(2) +
                child(1, 11) +
                child(2, 12) +
                child(11, 111),
        )
        // A Window anywhere but under the root; it stays where it was.
        h.batch(Mutation.Move(11, 2, 0))
        assertEquals(TableError.MISPLACED_WINDOW, h.errors().last().code)
        assertEquals(listOf(111), h.host.table.node(11)?.children?.toList())
        assertEquals(listOf(1, 2), h.manager.openWindows)

        // A node moved from one window's content into the other's.
        h.batch(Mutation.Move(2, 11, 0))
        assertEquals(TableError.MOVE_BETWEEN_WINDOWS, h.errors().last().code)
        assertEquals(listOf(11), h.host.table.node(1)?.children?.toList())
        assertEquals(listOf(12), h.host.table.node(2)?.children?.toList())
        // And a Window moved under another window.
        h.batch(Mutation.Move(1, 2, 0))
        assertEquals(TableError.MISPLACED_WINDOW, h.errors().last().code)
        assertEquals(3, h.errors().size)

        // Moving within a window is a move, and the process goes on drawing.
        h.batch(Mutation.Move(1, 111, 0))
        assertEquals(3, h.errors().size)
        assertEquals(listOf(111, 11), h.host.table.node(1)?.children?.toList())
        assertEquals(listOf(1, 2), h.manager.openWindows)
    }

    @Test
    fun fr53_single_window_app_keeps_node_id_zero() {
        // The size reporter of a single window names node 0, as it always did.
        val sent = mutableListOf<HostEvent>()
        WindowSizeReporter().report(1400f, 900f, EventDispatcher { sent += it; false })
        assertEquals(0, (sent.single() as HostEvent.WindowSizeChanged).nodeId)

        // A single window application has no manager at all: no hook on the Host, and the
        // table answers for node 0 with the application's own theme and window.
        val connection = MwConnection(listOf(setWindow(WindowMode.Single)) + window(1))
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        assertNull(host.afterBatch)
        assertEquals(host.table.theme, host.table.themeFor(0))
        assertNull(host.table.zoomLevelFor(0))

        // Even with a manager attached, a node of a single window application opens nothing.
        val backend = MwBackend()
        val manager = WindowManager(host, backend, onExit = {})
        manager.attach()
        assertTrue(backend.opened.isEmpty())
        assertTrue(manager.openWindows.isEmpty())
    }

    @Test
    fun nfr17_opening_and_closing_many_windows_leaves_nothing_behind() {
        val dark = Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Dark, false)
        val h = MwHarness(listOf(setWindow(last = LastWindowClosed.KeepRunning)) + window(1))
        for (id in 100 until 600) {
            h.batch(window(id, PropertyKind.OnWindowCloseRequested to handler(1)) + listOf(Mutation.SetWindowTheme(id, dark)))
            h.backend.windowFor(id).userMove(1f, 1f)
            h.backend.windowFor(id).sink.onZoomChanged(1f, 1f, 0)
            h.batch(Mutation.Remove(id))
        }
        assertEquals(500, h.backend.opened.size - 1)
        assertEquals(listOf(h.backend.windowFor(1)), h.backend.live)
        assertEquals(1, h.manager.retained, "only the one window still open")
        assertEquals(0, h.host.table.windowThemeCount)
        h.batch(Mutation.Remove(1))
        assertEquals(0, h.manager.retained)
        assertTrue(h.backend.live.isEmpty())
        assertEquals(0, h.exits)
        // Windows requested and answered, or not, leave no request behind either.
        h.manager.shutdown()
        assertEquals(0, h.manager.retained)
    }

    private suspend fun settle() {
        repeat(30) { yield() }
    }
}
