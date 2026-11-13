package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowFrameTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frame time the Windows windows hand to their scene and the Host.
 *
 * It has to be the time that has passed, read from the monotonic clock, not a counter that
 * steps a 60 Hz frame each time: at 120 or 144 Hz such a counter runs ahead and every
 * animation plays faster than it was written to.
 */
class WindowFrameTimeTest {

    /** A clock the test moves by hand. */
    private class HandClock(var nanos: Long = 5_000_000_000L) {
        fun read(): Long = nanos
    }

    @Test
    fun pr3_windows_frame_time_follows_the_monotonic_clock_at_120_hz() {
        val clock = HandClock()
        val time = WindowFrameTime(clock::read)
        val step = 1_000_000_000L / 120
        val first = time.next()
        repeat(120) { clock.nanos += step }
        val second = time.next()
        assertEquals(120 * step, second - first, "120 frames of 120 Hz are one second, not 120 steps of 16 ms")
    }

    @Test
    fun pr3_windows_frame_time_follows_a_late_frame() {
        val clock = HandClock()
        val time = WindowFrameTime(clock::read)
        val first = time.next()
        clock.nanos += 50_000_000L
        assertEquals(50_000_000L, time.next() - first, "a frame 50 ms late is 50 ms later")
    }

    @Test
    fun pr3_windows_frame_time_starts_after_zero_and_always_moves_forward() {
        val clock = HandClock()
        val time = WindowFrameTime(clock::read)
        val first = time.next()
        assertEquals(WindowFrameTime.FIRST_FRAME_NANOS, first, "the first frame is one step after zero")
        val again = time.next()
        assertTrue(again > first, "two frames without the clock moving are still in order")
    }

    @Test
    fun pr3_windows_frame_time_reads_the_real_clock_by_default() {
        val time = WindowFrameTime()
        val first = time.next()
        val started = System.nanoTime()
        while (System.nanoTime() - started < 20_000_000L) Thread.sleep(1)
        val passed = time.next() - first
        assertTrue(passed >= 20_000_000L, "20 ms of wall time moved the frame time by only $passed ns")
    }
}
