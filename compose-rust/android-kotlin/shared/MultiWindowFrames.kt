package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.ui.node.NodeTable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The one frame driver of a multi-window process.
 *
 * [FrameRequests.global] is served by exactly one collector, here, however many windows are
 * open. A request waits for the first frame **any** window draws (a hidden window's stopped
 * clock holds nothing up), then `render_frame` is called once, its batch is applied to the
 * one node table that every window reads (so every window sees the same snapshot), and
 * the manager sends what the frame gathered. Only when no window's clock answers within
 * [stoppedAfterMillis] is the Host called directly, as [serveFrameRequests] does for one
 * window.
 *
 * Never started for a single window application, which keeps the driver it always had.
 */
internal suspend fun serveWindowFrames(
    host: ComposeRustHost,
    manager: WindowManager,
    requests: FrameRequestSource = FrameRequests.global,
    stoppedAfterMillis: Long = STOPPED_CLOCK_MILLIS,
    alreadyServed: Long = requests.counter.value,
) {
    serveFrameRequests(
        requests = requests.counter,
        awaitFrame = { onFrame -> manager.awaitFrame(onFrame) },
        serve = { frameTimeNanos ->
            host.renderFrame(frameTimeNanos)
            manager.flushFrame()
        },
        stoppedAfterMillis = stoppedAfterMillis,
        alreadyServed = alreadyServed,
    )
}

/**
 * Whether a platform whose windows each draw in a composition of their own (Android's
 * Activities, iOS' scenes) runs the process's one driver, [serveWindowFrames]: an application
 * of several windows ([mode]), with a platform window open for a `Window` node
 * ([openWindows]) and a window on screen to draw it ([attachedWindows]).
 *
 * Otherwise the one window's content serves the Host's frame requests itself, or, with no
 * window attached, the platform's loop for a process with no window does. Two of them
 * serving the same requests would call the Host twice for one request.
 */
fun windowFrameDriverWanted(mode: WindowMode?, openWindows: Int, attachedWindows: Int): Boolean =
    mode == WindowMode.Windows && openWindows > 0 && attachedWindows > 0

/** What the Activity an Android application was started with does, in an application of several windows. */
sealed interface StartedActivity<out W> {
    /** It draws [window]: the first window, or the one the application answered with. */
    class Draw<W>(val window: W) : StartedActivity<W>

    /** It draws the tree's root: a `Window` node exists that has no platform window. */
    object DrawRoot : StartedActivity<Nothing>

    /**
     * The application answered with no window. This Activity alone is finished; no other
     * window is closed and no task is removed, so an Activity stacked on the task of a window
     * that is still open leaves that task as it was.
     */
    object FinishOnlyThis : StartedActivity<Nothing>
}

/**
 * What the Activity an Android application was started with draws, in an application of
 * several windows. An Activity the renderer launched as a window is bound by the node id or the
 * key it carries; this is the one the launcher started.
 *
 * It draws the application's first `Window` node when it may: when [mayDraw] says the platform
 * window [windowFor] has for it is this Activity's (the one it claimed when the process started)
 * or has no live Activity left. A window that another live Activity draws is never taken from
 * it: the user closed the first window, another stayed open, and the launcher started the
 * application again, or a second instance was started on top of the first.
 *
 * Otherwise the start is the user asking for a window, as when the user closed every window and
 * the process stayed. The application is asked for one through [requestWindow], as for any
 * window the system made with no node for it (an empty restore key), which gives back the
 * window it answered with, or null. The Activity draws that window, or, with no answer, is
 * finished alone ([StartedActivity.FinishOnlyThis]). Drawing the tree's root instead left a
 * blank window that nothing filled, because the application never heard that the user was
 * back; drawing the open window took it from the Activity that showed it.
 */
fun <W : PlatformWindow> startedActivityWindow(
    table: NodeTable,
    windowFor: (nodeId: Int) -> W?,
    mayDraw: (window: W) -> Boolean,
    requestWindow: () -> W?,
): StartedActivity<W> {
    val first = table.roots.firstOrNull { table.node(it)?.widget == WidgetKind.Window }
    if (first != null) {
        val window = windowFor(first) ?: return StartedActivity.DrawRoot
        if (mayDraw(window)) return StartedActivity.Draw(window)
    }
    val answered = requestWindow() ?: return StartedActivity.FinishOnlyThis
    return StartedActivity.Draw(answered)
}

/**
 * One window's frame clock, for a platform where each window draws in a composition of its
 * own (an Android Activity, an iOS scene).
 *
 * [awaitFrame] is the window's [PlatformWindow.awaitFrame]: it leaves a waiter and wakes the
 * window's content, whose loop ([serve], through [ServeWindowFrames]) answers every waiter
 * from inside its next frame. Its frame clock ticks with the display and stops while the
 * window is not on screen, and then this clock answers nothing: the driver waits for another
 * window's frame or, with none, serves the Host directly. A window between two compositions
 * (an Activity recreated for a configuration change) keeps its waiters for the next one.
 * After [close] the clock answers nothing again.
 *
 * Every call is made on the UI thread, where the Host and the compositions run, so the
 * waiters need no lock and nothing is queued between the Host and the Renderer.
 */
class WindowFrameClock {
    private val waiters = ArrayList<(Long) -> Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    /** How many callers are waiting for this window's next frame. */
    val waiting: Int get() = waiters.size

    suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        if (closed) awaitCancellation()
        var waiter: ((Long) -> Unit)? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val callback: (Long) -> Unit = { nanos ->
                    onFrame(nanos)
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
                waiter = callback
                waiters += callback
                wake.trySend(Unit)
            }
        } finally {
            waiter?.let { waiters.remove(it) }
        }
    }

    /**
     * Answers the waiters from inside the frames of the composition it is called in, for as
     * long as that composition runs. Asks for a frame only while someone waits, so an idle
     * window draws nothing.
     */
    suspend fun serve() {
        while (true) {
            if (waiters.isEmpty()) wake.receive()
            if (closed) awaitCancellation()
            withFrameNanos { nanos -> draw(nanos) }
        }
    }

    /** Answers every waiter with the frame time [nanos]. */
    fun draw(nanos: Long) {
        if (waiters.isEmpty()) return
        val answered = waiters.toList()
        waiters.clear()
        for (callback in answered) callback(nanos)
    }

    /** The window is gone: its waiters are dropped and it answers nothing again. */
    fun close() {
        closed = true
        waiters.clear()
    }
}

/**
 * The Host's one lifecycle, for a platform where every window has a lifecycle of its own
 * (Android's Activities).
 *
 * The Host is started and stopped for the whole process: while it is stopped it holds back
 * the frames its timers and animations ask for, in every window at once. So it hears a stop
 * only when the last window leaves the screen, and a start only when the first one comes
 * back. Passed on one window at a time, the window that stopped last decided for all of
 * them: a window that went behind another, or one closed while another stayed on screen,
 * stopped the Host under the window in front, and nothing started it again, because a
 * window that stays on screen is never started a second time.
 *
 * Every call is made on the UI thread, where the Host runs.
 */
class WindowsOnScreen(private val dispatch: (HostEvent) -> Unit) {
    private var started = 0

    /** Whether any window is on screen. */
    val onScreen: Boolean get() = started > 0

    /** A window came on screen (an Activity's `onStart`). */
    fun started() {
        started += 1
        if (started == 1) dispatch(HostEvent.LifecycleStart(NO_NODE, NO_HANDLER))
    }

    /** A window left the screen (an Activity's `onStop`). A stop with no start before it changes nothing. */
    fun stopped() {
        if (started == 0) return
        started -= 1
        if (started == 0) dispatch(HostEvent.LifecycleStop(NO_NODE, NO_HANDLER))
    }

    private companion object {
        /** A lifecycle event addresses the Host itself, so it carries no node and no handler. */
        const val NO_NODE = 0
        const val NO_HANDLER = 0L
    }
}

/** Serves [clock] from the frames of the window content this is composed in; nothing for null. */
@Composable
fun ServeWindowFrames(clock: WindowFrameClock?) {
    if (clock != null) LaunchedEffect(clock) { clock.serve() }
}
