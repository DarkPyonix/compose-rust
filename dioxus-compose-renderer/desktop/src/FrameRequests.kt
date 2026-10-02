package dioxus.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A counter that Host worker threads bump to ask for a frame.
 *
 * Domain work (network, file I/O, process management) runs on Host worker threads, never on
 * the UI thread. This counter is the one signal those threads send across: they update their
 * state and ask for a frame, and the UI thread does the rest.
 *
 * Any number of requests between two frames coalesce into one: the renderer observes the
 * counter from the UI thread and calls `dioxus_compose_host_render_frame` once per change
 * it sees inside the frame clock.
 */
internal class FrameRequestSource {
    private val requests = MutableStateFlow(0L)

    val counter: StateFlow<Long> = requests.asStateFlow()

    /** Thread-safe; called from `dioxus_compose_renderer_request_frame`. */
    fun request() = requests.update { it + 1 }
}

/**
 * The process-wide source, and the one the C entry point feeds.
 *
 * It is a singleton because `dioxus_compose_renderer_request_frame` takes no host handle:
 * at run time there is one process, one isolate and one host, so there is nothing to
 * disambiguate. A test process holds several hosts at once, which is what
 * [LocalFrameRequests] is for.
 */
internal object FrameRequests {
    val global = FrameRequestSource()

    /** Thread-safe; called from `dioxus_compose_renderer_request_frame`. */
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
