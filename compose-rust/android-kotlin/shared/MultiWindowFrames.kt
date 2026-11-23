package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
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

/** Serves [clock] from the frames of the window content this is composed in; nothing for null. */
@Composable
fun ServeWindowFrames(clock: WindowFrameClock?) {
    if (clock != null) LaunchedEffect(clock) { clock.serve() }
}
