package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.runtime.ComposeRustHost

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
