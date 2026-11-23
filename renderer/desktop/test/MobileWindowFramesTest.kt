package dev.darkpyonix.composerust.test

import androidx.compose.runtime.MonotonicFrameClock
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
import dev.darkpyonix.composerust.ui.platform.ANDROID_TEAR_OFF_MIN_SDK
import dev.darkpyonix.composerust.ui.platform.ApplicationListener
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.PlatformWindow
import dev.darkpyonix.composerust.ui.platform.PlatformWindowConfig
import dev.darkpyonix.composerust.ui.platform.PlatformWindowListener
import dev.darkpyonix.composerust.ui.platform.ScreenBounds
import dev.darkpyonix.composerust.ui.platform.ScreenPointer
import dev.darkpyonix.composerust.ui.platform.StartedActivity
import dev.darkpyonix.composerust.ui.platform.WindowBackend
import dev.darkpyonix.composerust.ui.platform.WindowFrameClock
import dev.darkpyonix.composerust.ui.platform.WindowManager
import dev.darkpyonix.composerust.ui.platform.WindowsOnScreen
import dev.darkpyonix.composerust.ui.platform.androidTearOffSupported
import dev.darkpyonix.composerust.ui.platform.carriedWindowNodeId
import dev.darkpyonix.composerust.ui.platform.serveWindowFrames
import dev.darkpyonix.composerust.ui.platform.startedActivityWindow
import dev.darkpyonix.composerust.ui.platform.windowFrameDriverWanted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield

// The windows of a phone or a tablet each draw in a composition of their own (an Android
// Activity, an iOS scene), and the process has one frame driver for all of them. These are the
// decisions that make a second window draw at all, taken out of the platform code so they run
// here: the window's clock, which windows have a driver, and which carried node ids are trusted.

/** A display's frame clock that ticks only when the test says. */
private class ManualFrameClock : MonotonicFrameClock {
    private val pending = ArrayList<CancellableContinuation<Long>>()

    /** How many frames the content asked for and has not had. */
    val asked: Int get() = pending.size

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        val nanos = suspendCancellableCoroutine<Long> { continuation -> pending += continuation }
        return onFrame(nanos)
    }

    fun tick(nanos: Long) {
        val now = pending.toList()
        pending.clear()
        for (continuation in now) continuation.resumeWith(Result.success(nanos))
    }
}

private suspend fun settle() = repeat(20) { yield() }

private class ClockWindow(val config: PlatformWindowConfig) : PlatformWindow {
    val frames = WindowFrameClock()

    /** Whether the manager closed it. */
    var closed = false

    /** The `Window` node that adopted it, or 0. */
    var adoptedAs = 0

    override fun setListener(listener: PlatformWindowListener) {}
    override fun setTitle(title: String) {}
    override fun setPosition(x: Float, y: Float) {}
    override fun setContentSize(width: Float, height: Float) {}
    override fun bounds(): ScreenBounds? = null
    override fun focus() {}
    override val isKey: Boolean get() = false
    override fun pointerOnScreen(): ScreenPointer? = null
    override fun followPointer(anchorX: Float, anchorY: Float): Boolean = false
    override suspend fun awaitFrame(onFrame: (Long) -> Unit) = frames.awaitFrame(onFrame)
    override fun adoptedBy(nodeId: Int) {
        adoptedAs = nodeId
    }
    override fun close() {
        closed = true
        frames.close()
    }
}

private class ClockBackend : WindowBackend {
    val opened = mutableListOf<ClockWindow>()
    var application: ApplicationListener? = null
    override val supportsMultipleWindows: Boolean get() = true

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow =
        ClockWindow(config).also { opened += it }

    override fun setApplicationListener(listener: ApplicationListener?) {
        application = listener
    }

    fun window(nodeId: Int): ClockWindow = opened.last { it.config.nodeId == nodeId }
}

private class SecondWindowConnection(private val initial: List<Mutation>) : HostConnection {
    var renderFrames = 0
    val frameBatches = ArrayDeque<List<Mutation>>()

    /** What the application answers an event with, in the same batch. */
    var answer: (HostEvent) -> List<Mutation> = { emptyList() }

    /** The lifecycle events the Host heard, in order. */
    val lifecycle = mutableListOf<String>()

    override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        when (event) {
            is HostEvent.LifecycleStart -> lifecycle += "start"
            is HostEvent.LifecycleStop -> lifecycle += "stop"
            else -> answer(event).forEach(onMutation)
        }
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        renderFrames++
        frameBatches.removeFirstOrNull()?.forEach(onMutation)
    }

    override fun shutdown() {}
}

class MobileWindowFramesTest {
    private fun windows() = Mutation.SetWindow(
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
            lastWindowClosed = LastWindowClosed.Exit,
        ),
    )

    @Test
    fun fr53_a_second_mobile_window_draws_from_its_own_frames() = runBlocking {
        val connection = SecondWindowConnection(
            listOf(
                windows(),
                Mutation.Create(1, WidgetKind.Window),
                Mutation.Insert(0, 1, -1),
                Mutation.Create(2, WidgetKind.Window),
                Mutation.Insert(0, 2, -1),
                Mutation.Create(20, WidgetKind.Text),
                Mutation.SetProp(20, PropertyKind.Text, PropertyValue.Text("before")),
                Mutation.Insert(2, 20, -1),
            ),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        val backend = ClockBackend()
        val manager = WindowManager(host, backend, onExit = {})
        manager.attach()
        connection.frameBatches.addLast(listOf(Mutation.SetProp(20, PropertyKind.Text, PropertyValue.Text("after"))))

        val requests = FrameRequestSource()
        val driver = launch { serveWindowFrames(host, manager, requests, stoppedAfterMillis = 60_000, alreadyServed = 0) }
        // Only the second window is on screen; the first one's content is stopped and draws nothing.
        val display = ManualFrameClock()
        val content = launch(display) { backend.window(2).frames.serve() }
        settle()
        assertEquals(0, display.asked, "an idle window asks for no frame")

        requests.request()
        settle()
        assertEquals(1, display.asked, "the Host's request wakes the second window's content")
        assertEquals(0, connection.renderFrames)

        display.tick(16_000_000L)
        settle()
        assertEquals(1, connection.renderFrames, "the Host is asked once, inside the second window's frame")
        assertEquals("after", host.table.node(20)?.text(PropertyKind.Text))
        assertEquals(0, backend.window(1).frames.waiting, "nothing is left waiting on the first window")

        content.cancel()
        driver.cancel()
    }

    private fun lifecycleHost(): Pair<ComposeRustHost, SecondWindowConnection> {
        val connection = SecondWindowConnection(listOf(windows()))
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        return host to connection
    }

    // The Flip 4 run: the system opened a torn-off tab full screen, the first window went
    // behind it and stopped after the new one had started. Each Activity's stop used to go
    // to the Host as it came, so the Host heard start, start, stop and stayed stopped under
    // the window in front, whose count of seconds stood at 0.
    @Test
    fun fr53_a_window_going_behind_another_keeps_the_host_started() {
        val (host, connection) = lifecycleHost()
        val onScreen = WindowsOnScreen { event -> host.dispatch(event) }
        onScreen.started() // the first window
        onScreen.started() // the torn-off window, in front
        onScreen.stopped() // the first window, now behind it
        assertEquals(listOf("start"), connection.lifecycle, "a window is still on screen, so the Host is not stopped")
        assertTrue(onScreen.onScreen)
        onScreen.stopped() // the torn-off window goes home too
        assertEquals(listOf("start", "stop"), connection.lifecycle)
        assertFalse(onScreen.onScreen)
    }

    // The emulator run: two windows side by side, and the torn-off one closed with Back. Its
    // stop used to stop the Host under the first window, which stayed on screen and so was
    // never started again: its count of seconds stood still and a tap did not move it.
    @Test
    fun fr53_closing_a_window_leaves_the_host_started_for_the_one_on_screen() {
        val (host, connection) = lifecycleHost()
        val onScreen = WindowsOnScreen { event -> host.dispatch(event) }
        onScreen.started() // the first window
        onScreen.started() // the torn-off window, beside it
        onScreen.stopped() // the torn-off window closes
        assertEquals(listOf("start"), connection.lifecycle, "the first window is still on screen")
        onScreen.stopped() // the first window goes home
        onScreen.started() // and comes back
        assertEquals(listOf("start", "stop", "start"), connection.lifecycle)
    }

    // One window keeps what it always had: a configuration change stops the old Activity and
    // starts the new one, and a stop with no start before it is not a second stop.
    @Test
    fun fr53_one_window_starts_and_stops_the_host_as_before() {
        val (host, connection) = lifecycleHost()
        val onScreen = WindowsOnScreen { event -> host.dispatch(event) }
        onScreen.stopped()
        assertEquals(emptyList<String>(), connection.lifecycle)
        onScreen.started()
        onScreen.stopped() // the old Activity of a configuration change
        onScreen.started() // the new one
        assertEquals(listOf("start", "stop", "start"), connection.lifecycle)
    }

    // A window whose content stopped drawing (an Activity behind another) and a window that
    // closed hold up none of the frames the other window draws.
    @Test
    fun fr53_closing_a_window_leaves_the_remaining_window_served() = runBlocking {
        val connection = SecondWindowConnection(
            listOf(
                windows(),
                Mutation.Create(1, WidgetKind.Window),
                Mutation.Insert(0, 1, -1),
                Mutation.Create(2, WidgetKind.Window),
                Mutation.Insert(0, 2, -1),
                Mutation.Create(10, WidgetKind.Text),
                Mutation.SetProp(10, PropertyKind.Text, PropertyValue.Text("0")),
                Mutation.Insert(1, 10, -1),
            ),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        val backend = ClockBackend()
        val manager = WindowManager(host, backend, onExit = {})
        manager.attach()
        val requests = FrameRequestSource()
        val driver = launch { serveWindowFrames(host, manager, requests, stoppedAfterMillis = 60_000, alreadyServed = 0) }
        val first = ManualFrameClock()
        val second = ManualFrameClock()
        val firstContent = launch(first) { backend.window(1).frames.serve() }
        val secondContent = launch(second) { backend.window(2).frames.serve() }

        // The second window's frame carries the batch that removes it.
        connection.frameBatches.addLast(listOf(Mutation.Remove(2)))
        requests.request()
        settle()
        second.tick(16_000_000L)
        settle()
        assertEquals(1, connection.renderFrames)
        assertEquals(listOf(1), manager.openWindows)
        secondContent.cancel()
        // The first window's frame came after; it answers nobody and is dropped.
        first.tick(16_000_001L)
        settle()

        connection.frameBatches.addLast(listOf(Mutation.SetProp(10, PropertyKind.Text, PropertyValue.Text("1"))))
        requests.request()
        settle()
        assertEquals(1, first.asked, "the remaining window is asked to draw")
        first.tick(32_000_000L)
        settle()
        assertEquals(2, connection.renderFrames, "the remaining window's frame serves the Host")
        assertEquals("1", host.table.node(10)?.text(PropertyKind.Text))

        firstContent.cancel()
        driver.cancel()
    }

    // The system made a window for a dropped tab before the application had a node for it.
    // The window is adopted by the node the application puts in, and its frames serve the
    // Host like those of any other window.
    @Test
    fun fr53_a_window_adopted_after_it_was_made_is_served() = runBlocking {
        val connection = SecondWindowConnection(
            listOf(windows(), Mutation.Create(1, WidgetKind.Window), Mutation.Insert(0, 1, -1)),
        )
        connection.answer = { event ->
            if (event is HostEvent.WindowRequested) {
                listOf(
                    Mutation.Create(5, WidgetKind.Window),
                    Mutation.SetProp(5, PropertyKind.WindowRestoreKey, PropertyValue.Text(event.key)),
                    Mutation.Insert(0, 5, -1),
                    Mutation.Create(50, WidgetKind.Text),
                    Mutation.SetProp(50, PropertyKind.Text, PropertyValue.Text("0")),
                    Mutation.Insert(5, 50, -1),
                )
            } else {
                emptyList()
            }
        }
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        val backend = ClockBackend()
        val manager = WindowManager(host, backend, onExit = {})
        manager.attach()
        val requests = FrameRequestSource()
        val driver = launch { serveWindowFrames(host, manager, requests, stoppedAfterMillis = 60_000, alreadyServed = 0) }

        val made = ClockWindow(PlatformWindowConfig(nodeId = 0, title = "", width = 0f, height = 0f))
        backend.application?.onWindowRequested("tab:a", made)
        assertEquals(listOf(1, 5), manager.openWindows, "the made window is the node the application put in")

        // Only the adopted window is on screen; the first one is behind it.
        val display = ManualFrameClock()
        val content = launch(display) { made.frames.serve() }
        connection.frameBatches.addLast(listOf(Mutation.SetProp(50, PropertyKind.Text, PropertyValue.Text("1"))))
        requests.request()
        settle()
        assertEquals(1, display.asked, "the adopted window is asked to draw")
        display.tick(16_000_000L)
        settle()
        assertEquals(1, connection.renderFrames)
        assertEquals("1", host.table.node(50)?.text(PropertyKind.Text))

        content.cancel()
        driver.cancel()
    }

    /**
     * An Activity the launcher started, in a process whose tree has the `Window` nodes
     * [windowIds]. The application answers `WindowRequested` with `Window` node 7 under the
     * key it was asked with when [answers], and with nothing otherwise.
     */
    private class LauncherStart(windowIds: List<Int>, answers: Boolean, initial: Mutation.SetWindow) {
        val connection = SecondWindowConnection(
            buildList<Mutation> {
                add(initial)
                for (id in windowIds) {
                    add(Mutation.Create(id, WidgetKind.Window))
                    add(Mutation.Insert(0, id, -1))
                }
            },
        )
        val requested = mutableListOf<String>()
        val host = ComposeRustHost(connection)
        val backend = ClockBackend()
        val manager = WindowManager(host, backend, onExit = {})

        /** The platform windows that another live Activity shows. */
        val shownElsewhere = HashSet<ClockWindow>()

        /** The window the started Activity offers the application. */
        val made = ClockWindow(PlatformWindowConfig(nodeId = 0, title = "", width = 0f, height = 0f))

        init {
            connection.answer = { event ->
                if (event is HostEvent.WindowRequested) {
                    requested += event.key
                    if (answers) {
                        listOf(
                            Mutation.Create(7, WidgetKind.Window),
                            Mutation.SetProp(7, PropertyKind.WindowRestoreKey, PropertyValue.Text(event.key)),
                            Mutation.Insert(0, 7, -1),
                        )
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
            host.start(FrameRequestSource())
            manager.attach()
        }

        fun window(nodeId: Int): ClockWindow = backend.window(nodeId)

        fun start(): StartedActivity<ClockWindow> = startedActivityWindow(
            host.table,
            windowFor = { id -> backend.opened.lastOrNull { it.config.nodeId == id } },
            mayDraw = { window -> !window.closed && window !in shownElsewhere },
            requestWindow = {
                backend.application?.onWindowRequested("", made)
                made.takeIf { it.adoptedAs != 0 }
            },
        )
    }

    private fun drawn(started: StartedActivity<ClockWindow>): ClockWindow? = when (started) {
        is StartedActivity.Draw -> started.window
        else -> null
    }

    // The Flip 4 run: Back on the first window finished its Activity, the application heard
    // WindowClosed and removed the node, and the process stayed. Started again from the
    // launcher, the new Activity found no `Window` node, drew the tree's root and stayed
    // blank, because the application never heard that the user was back.
    @Test
    fun fr53_a_start_after_the_last_window_closed_asks_the_application_for_a_window() {
        val app = LauncherStart(listOf(1), answers = true, initial = windows())

        // While the first window is there, the started Activity draws it and nothing is asked.
        assertTrue(drawn(app.start()) === app.window(1))
        assertEquals(emptyList<String>(), app.requested)

        // The user closed it, and the application removed its node.
        app.connection.frameBatches.addLast(listOf(Mutation.Remove(1)))
        app.host.renderFrame(16_000_000L)
        assertEquals(emptyList<Int>(), app.manager.openWindows)

        // Started from the launcher again: the application is asked, with an empty key, and the
        // window it answers with is the started Activity's.
        val started = app.start()
        assertEquals(listOf(""), app.requested, "the application hears that the user started it again")
        assertTrue(drawn(started) === app.made)
        assertEquals(listOf(7), app.manager.openWindows, "the started Activity draws the node the application put in")
        assertEquals(1, app.backend.opened.size, "no second Activity is launched for that node")
    }

    // The first window was closed and the second stayed open in its own Activity. A start from
    // the launcher used to bind the new Activity to the second window, the first `Window` node
    // left, and took it from the Activity that showed it.
    @Test
    fun fr53_a_launcher_start_leaves_a_window_another_activity_shows() {
        val app = LauncherStart(listOf(2), answers = false, initial = windows())
        app.shownElsewhere += app.window(2)

        val started = app.start()
        assertTrue(drawn(started) !== app.window(2), "the open window stays with its own Activity")
        assertEquals(listOf(""), app.requested, "the application is asked, with an empty key")
        assertFalse(app.window(2).closed)
        assertEquals(listOf(2), app.manager.openWindows)
    }

    // The application answers that start with a window: the started Activity draws it, and the
    // open window stays where it is.
    @Test
    fun fr53_a_launcher_start_draws_the_window_the_application_answers_with() {
        val app = LauncherStart(listOf(2), answers = true, initial = windows())
        app.shownElsewhere += app.window(2)

        val started = app.start()
        assertTrue(drawn(started) === app.made, "the started Activity draws the answer")
        assertEquals(7, app.made.adoptedAs)
        assertEquals(listOf(2, 7), app.manager.openWindows)
        assertFalse(app.window(2).closed)
        assertEquals(1, app.backend.opened.size, "no Activity is launched for the answer")
    }

    // With no answer, only the started Activity ends: no window is closed or removed. The same
    // holds for a second instance started on top of the first window's own task (`am start`),
    // whose task must stay.
    @Test
    fun fr53_a_launcher_start_with_no_answer_finishes_only_that_activity() {
        val other = LauncherStart(listOf(2), answers = false, initial = windows())
        other.shownElsewhere += other.window(2)
        assertTrue(other.start() === StartedActivity.FinishOnlyThis)
        assertFalse(other.window(2).closed, "the open window is not closed")
        assertEquals(listOf(2), other.manager.openWindows)
        assertEquals(1, other.backend.opened.size)

        val stacked = LauncherStart(listOf(1), answers = false, initial = windows())
        stacked.shownElsewhere += stacked.window(1)
        assertTrue(stacked.start() === StartedActivity.FinishOnlyThis)
        assertEquals(listOf(""), stacked.requested)
        assertFalse(stacked.window(1).closed, "the first window and its task stay")
        assertEquals(listOf(1), stacked.manager.openWindows)
    }

    @Test
    fun fr53_a_recreated_window_keeps_its_waiters_for_its_next_content() = runBlocking {
        val clock = WindowFrameClock()
        val stopped = ManualFrameClock()
        val first = launch(stopped) { clock.serve() }
        var answered = -1L
        val waiter = launch { clock.awaitFrame { answered = it } }
        settle()
        assertEquals(1, stopped.asked)
        // The Activity is recreated for a configuration change before its frame came.
        first.cancel()
        settle()
        val next = ManualFrameClock()
        val second = launch(next) { clock.serve() }
        settle()
        next.tick(42L)
        settle()
        assertEquals(42L, answered)
        assertTrue(waiter.isCompleted)
        second.cancel()
    }

    @Test
    fun fr53_a_closed_window_clock_answers_nothing() = runBlocking {
        val clock = WindowFrameClock()
        clock.close()
        var answered = false
        val waiter = launch { clock.awaitFrame { answered = true } }
        settle()
        clock.draw(1L)
        settle()
        assertFalse(answered)
        assertFalse(waiter.isCompleted, "the driver waits on the other windows, or serves the Host directly")
        waiter.cancel()
        // A content still composed after its window closed asks for no frame.
        val display = ManualFrameClock()
        val content = launch(display) { clock.serve() }
        settle()
        assertEquals(0, display.asked)
        content.cancel()
    }

    @Test
    fun fr53_one_frame_driver_runs_only_for_windows_on_screen() {
        assertTrue(windowFrameDriverWanted(WindowMode.Windows, openWindows = 2, attachedWindows = 1))
        assertFalse(windowFrameDriverWanted(WindowMode.Single, openWindows = 0, attachedWindows = 1), "one window serves its own frames")
        assertFalse(windowFrameDriverWanted(null, openWindows = 0, attachedWindows = 1))
        assertFalse(windowFrameDriverWanted(WindowMode.Windows, openWindows = 0, attachedWindows = 1), "a window not bound to a node serves its own")
        assertFalse(windowFrameDriverWanted(WindowMode.Windows, openWindows = 2, attachedWindows = 0), "with nothing on screen the process' own loop serves")
    }

    @Test
    fun fr53_a_node_id_carried_from_a_dead_process_is_not_trusted() {
        val now = 7_000_001L
        assertEquals(3, carriedWindowNodeId(3, carriedProcess = now, thisProcess = now), "a configuration change keeps the window")
        assertEquals(0, carriedWindowNodeId(3, carriedProcess = 6_999_999L, thisProcess = now), "another process' id names another node here")
        assertEquals(0, carriedWindowNodeId(3, carriedProcess = 0L, thisProcess = now), "an id carried with no token is not trusted")
        assertEquals(0, carriedWindowNodeId(0, carriedProcess = now, thisProcess = now))
    }

    @Test
    fun fr53_android_tear_off_drags_start_from_android_15() {
        assertEquals(35, ANDROID_TEAR_OFF_MIN_SDK)
        assertFalse(androidTearOffSupported(32), "Android 12L has no public way to start an Activity from a drag")
        assertFalse(androidTearOffSupported(34))
        assertTrue(androidTearOffSupported(35))
        assertTrue(androidTearOffSupported(36))
    }
}
