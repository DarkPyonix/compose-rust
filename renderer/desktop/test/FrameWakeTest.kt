package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.FrameDispatcher
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.serveFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A request for a frame is served by the next turn of the window's loop, not by a timer.
 *
 * The window's loop runs what the scene has queued once per turn and draws when something
 * is waiting. A request made between two turns has to be served by the turn that follows
 * it, which is at most one frame away. Serving it only after the quarter second the frame
 * clock is given before it is taken to be stopped is what the typed text trailed by.
 */
class FrameWakeTest {

    @Test
    fun pr3_a_frame_request_is_served_by_the_next_turn_of_the_loop() {
        val work = FrameDispatcher()
        val requests = FrameRequestSource()
        val served = mutableListOf<Long>()
        var frameCallbacks = mutableListOf<(Long) -> Unit>()
        runBlocking {
            val job = launch(work, start = CoroutineStart.UNDISPATCHED) {
                serveFrameRequests(
                    requests.counter,
                    awaitFrame = { onFrame ->
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                            frameCallbacks.add { nanos ->
                                onFrame(nanos)
                                continuation.resume(Unit) {}
                            }
                        }
                    },
                    serve = { served.add(it) },
                    alreadyServed = 0,
                )
            }
            // One request, then the loop's turns: run what is queued, draw the frame.
            requests.request()
            work.runPending()
            assertEquals(1, frameCallbacks.size, "the request did not reach the frame clock on the next turn")
            frameCallbacks.removeAt(0)(16_000_000L)
            assertEquals(listOf(16_000_000L), served, "the frame was not served by the turn that drew it")
            job.cancel()
            work.runPending()
        }
    }
}
