package dev.darkpyonix.composerust.ui.platform

import kotlin.time.TimeSource

/**
 * The time of each frame a window draws, in nanoseconds of the monotonic clock.
 *
 * A frame's time is what Compose animates by and what the Host is handed as the frame time,
 * so it has to be how much time has really passed. A counter stepped once a frame is not:
 * on a 120 or 144 Hz display it runs ahead of the wall clock and every animation plays too
 * fast, and a frame that comes late does not move it any further than one that comes early.
 *
 * Every answer is later than the one before, even when the clock has not moved between two
 * asks, because the scene treats an equal time as no frame at all. The first frame is not
 * at zero: a scene that starts at zero takes its first frame for the start of time.
 *
 * Used by the Kotlin/Native Windows renderer, whose source set links this file. The native
 * image's Windows window takes its frame time from the Compose fork's window instead.
 */
class WindowFrameTime(
    /** The monotonic clock in nanoseconds; only differences between two reads mean anything. */
    private val clock: () -> Long = monotonicClock(),
) {
    private val origin = clock()
    private var last = 0L

    /** The time of the frame being drawn now. */
    fun next(): Long {
        val now = clock() - origin + FIRST_FRAME_NANOS
        last = if (now > last) now else last + 1
        return last
    }

    companion object {
        /** Where the first frame's time starts, one 60 Hz step after zero. */
        const val FIRST_FRAME_NANOS = 16_000_000L

        private fun monotonicClock(): () -> Long {
            val started = TimeSource.Monotonic.markNow()
            return { started.elapsedNow().inWholeNanoseconds }
        }
    }
}
