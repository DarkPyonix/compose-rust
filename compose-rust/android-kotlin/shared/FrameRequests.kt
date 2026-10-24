package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A counter that Host worker threads bump to ask for a frame.
 *
 * Domain work (network, file I/O, process management) runs on Host worker threads, never on
 * the UI thread. This counter is the one signal those threads send across: they update their
 * state and ask for a frame, and the UI thread does the rest.
 *
 * Any number of requests between two frames coalesce into one: the renderer observes the
 * counter from the UI thread and calls `compose_rust_host_render_frame` once per change
 * it sees inside the frame clock.
 */
internal class FrameRequestSource {
    private val requests = MutableStateFlow(0L)

    val counter: StateFlow<Long> = requests.asStateFlow()

    /** Thread-safe; called from `compose_rust_renderer_request_frame`. */
    fun request() = requests.update { it + 1 }
}

/**
 * The process-wide source, and the one the C entry point feeds.
 *
 * It is a singleton because `compose_rust_renderer_request_frame` takes no host handle:
 * at run time there is one process, one isolate and one host, so there is nothing to
 * disambiguate. A test process holds several hosts at once, which is what
 * [LocalFrameRequests] is for.
 */
internal object FrameRequests {
    val global = FrameRequestSource()

    /** Thread-safe; called from `compose_rust_renderer_request_frame`. */
    fun request() = global.request()
}

/**
 * The source the enclosing composition drives its frame loop from.
 *
 * Production leaves it at the global one. A test provides its own so that two hosts alive
 * in the same process cannot drive each other's frame loops: a stray request from one
 * otherwise keeps another's `withFrameNanos` running and its composition never goes idle.
 */
internal val LocalFrameRequests = staticCompositionLocalOf { FrameRequests.global }

/**
 * How long a request waits for the frame clock before the clock is taken to be stopped.
 *
 * A window that is minimised, covered or in the background may stop being drawn, and its
 * frame clock with it. A request a worker made then would wait for the next time the window
 * is drawn, which is exactly when it is least needed: a notification that the session has
 * finished matters while nobody is looking. A quarter of a second is long enough that a
 * clock which is running always answers first, and short enough that what was asked for
 * is out well inside a second.
 */
internal const val STOPPED_CLOCK_MILLIS = 250L

/**
 * Serves the Host's frame requests: inside the frame clock while it runs, and directly on
 * this thread when it has stopped.
 *
 * However many requests arrive, each pass serves one call: the counter is read when the
 * pass starts and anything that arrives during it is the next pass. [awaitFrame] is the
 * frame clock (`withFrameNanos` in a composition), and [serve] runs on the thread this
 * coroutine runs on, which is the thread the composition is applied on and so the Host's.
 *
 * Only how the call is scheduled changes when the clock has stopped. It is the same call,
 * with the same coalescing, and its batch is applied on the same call stack. The time it is
 * given continues the frame clock's own: the last frame's time plus what has passed since,
 * so the Host never sees time go backwards when the clock starts again.
 */
internal suspend fun serveFrameRequests(
    requests: StateFlow<Long>,
    awaitFrame: suspend (onFrame: (Long) -> Unit) -> Unit,
    serve: (Long) -> Unit,
    stoppedAfterMillis: Long = STOPPED_CLOCK_MILLIS,
    alreadyServed: Long = requests.value,
) {
    var applied = alreadyServed
    var lastFrameNanos = 0L
    var lastFrame: TimeMark = TimeSource.Monotonic.markNow()
    requests.collect { requested ->
        if (requested == applied) return@collect
        applied = requested
        var servedInFrame = false
        withTimeoutOrNull(stoppedAfterMillis) {
            awaitFrame { frameTimeNanos ->
                servedInFrame = true
                lastFrameNanos = frameTimeNanos
                lastFrame = TimeSource.Monotonic.markNow()
                serve(frameTimeNanos)
            }
        }
        // The clock did not answer. The frame callback may have run in the instant the
        // timeout fired, so it is asked rather than assumed, and the Host is called once.
        if (!servedInFrame) {
            val now = lastFrameNanos + lastFrame.elapsedNow().inWholeNanoseconds
            lastFrameNanos = now
            lastFrame = TimeSource.Monotonic.markNow()
            serve(now)
        }
    }
}
