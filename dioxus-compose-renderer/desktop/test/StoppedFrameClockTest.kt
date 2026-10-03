package dioxus.compose.test

import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.serveFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A frame request made while the window is not being drawn.
 *
 * Minimised, covered or in the background, a window may stop drawing and its frame clock
 * stops with it. These run on real time with a clock that never ticks, which is what a
 * minimised window's clock does, and measure how long a worker's request waits.
 */
class StoppedFrameClockTest {
    @Test
    fun pr3_a_request_while_the_frame_clock_is_stopped_is_served_within_a_second() = runBlocking {
        val frames = FrameRequestSource()
        val served = mutableListOf<Long>()
        val loop = launch(Dispatchers.Default) {
            serveFrameRequests(
                frames.counter,
                awaitFrame = { awaitCancellation() },
                serve = { nanos -> synchronized(served) { served += nanos } },
            )
        }
        delay(20)
        val asked = TimeSource.Monotonic.markNow()
        frames.request()
        withTimeout(1_000) {
            while (synchronized(served) { served.isEmpty() }) delay(5)
        }
        val waited = asked.elapsedNow().inWholeMilliseconds
        delay(400)
        loop.cancelAndJoin()
        assertEquals(1, served.size, "one request, one call")
        assertTrue(waited < 1_000, "served after $waited ms")
    }

    @Test
    fun pr3_a_running_clock_serves_the_request_inside_the_frame() = runBlocking {
        val frames = FrameRequestSource()
        val served = mutableListOf<Long>()
        val loop = launch {
            serveFrameRequests(
                frames.counter,
                awaitFrame = { onFrame -> onFrame(42L) },
                serve = { nanos -> served += nanos },
            )
        }
        delay(20)
        frames.request()
        delay(50)
        loop.cancelAndJoin()
        assertEquals(listOf(42L), served, "served in the frame, with the frame's time")
    }

    @Test
    fun pr3_requests_made_while_the_clock_is_stopped_still_coalesce() = runBlocking {
        val frames = FrameRequestSource()
        var served = 0
        val loop = launch {
            serveFrameRequests(
                frames.counter,
                awaitFrame = { awaitCancellation() },
                serve = { served++ },
            )
        }
        delay(20)
        repeat(10) { frames.request() }
        delay(700)
        loop.cancelAndJoin()
        assertTrue(served in 1..2, "ten requests were served in $served calls")
    }

    @Test
    fun pr3_time_handed_to_the_host_continues_the_frame_clock() = runBlocking {
        val frames = FrameRequestSource()
        val served = mutableListOf<Long>()
        var clockRunning = true
        val loop = launch {
            serveFrameRequests(
                frames.counter,
                awaitFrame = { onFrame ->
                    if (clockRunning) onFrame(5_000_000_000L) else awaitCancellation()
                },
                serve = { nanos -> served += nanos },
            )
        }
        delay(20)
        frames.request()
        delay(50)
        clockRunning = false
        frames.request()
        delay(600)
        loop.cancelAndJoin()
        assertEquals(2, served.size)
        assertTrue(served[1] > served[0], "time went from ${served[0]} to ${served[1]}")
    }
}
