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
import dev.darkpyonix.composerust.ui.platform.WindowBackend
import dev.darkpyonix.composerust.ui.platform.WindowFrameClock
import dev.darkpyonix.composerust.ui.platform.WindowManager
import dev.darkpyonix.composerust.ui.platform.androidTearOffSupported
import dev.darkpyonix.composerust.ui.platform.carriedWindowNodeId
import dev.darkpyonix.composerust.ui.platform.serveWindowFrames
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
    override fun close() = frames.close()
}

private class ClockBackend : WindowBackend {
    val opened = mutableListOf<ClockWindow>()
    override val supportsMultipleWindows: Boolean get() = true

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow =
        ClockWindow(config).also { opened += it }

    override fun setApplicationListener(listener: ApplicationListener?) {}

    fun window(nodeId: Int): ClockWindow = opened.last { it.config.nodeId == nodeId }
}

private class CountingConnection(private val initial: List<Mutation>) : HostConnection {
    var renderFrames = 0
    val frameBatches = ArrayDeque<List<Mutation>>()

    override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long = 0

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
        val connection = CountingConnection(
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
