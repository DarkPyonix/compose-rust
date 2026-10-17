@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.linux.X11Window
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.getenv
import platform.posix.timespec
import platform.posix.usleep

/**
 * The whole path from work dispatched on another thread to it running on the window's thread,
 * on the renderer's own frame dispatcher and the fork's X11 window.
 *
 * The window's thread is the loop with nothing happening: blocked in the pump with a long
 * timeout, then running what the dispatcher holds, as [LinuxWindow.run] does each turn. A
 * worker waits until it is blocked, notes the monotonic clock and dispatches a block that
 * notes the clock when it runs. 200 dispatches. Prints the median, the 99th percentile and
 * the worst, and fails when the 99th percentile is 1 ms or more; the worst is reported, not
 * asserted, because a shared runner can deschedule a thread for longer than that. Skipped
 * where there is no display (CI runs these under Xvfb).
 */
class WakeLatencyTest {
    @Test
    fun pr3_work_dispatched_from_a_worker_runs_on_the_window_thread_within_a_millisecond() {
        if (getenv("DISPLAY")?.toKString().isNullOrEmpty()) return
        val work = mainForTests()
        work.runPending()
        work.takeWake()
        val x11 = X11Window()
        val listener = object : WindowListener {
            override fun onEvent(event: WindowEvent) {}
        }
        assertTrue(x11.open(WindowConfig("wake latency", 320, 240), listener), "no window on a display that is there")
        repeat(30) { x11.pump(10) }
        work.wakeLoop = x11::wake
        val waiting = AtomicInt(0)
        val postedAt = AtomicLong(0L)
        val ranAt = AtomicLong(0L)
        val samples = LongArray(POSTS)
        val worker = newSingleThreadContext("wake-latency-worker")
        try {
            val posting = CoroutineScope(worker).launch {
                repeat(POSTS) {
                    while (waiting.load() == 0) usleep(50u)
                    // Long enough that the window's thread is blocked in its wait.
                    usleep(3_000u)
                    waiting.store(0)
                    postedAt.store(monotonicNanos())
                    CoroutineScope(work).launch { ranAt.store(monotonicNanos()) }
                }
            }
            var taken = 0
            while (taken < POSTS) {
                ranAt.store(0L)
                waiting.store(1)
                x11.pump(5_000)
                work.takeWake()
                work.runPending()
                val ran = ranAt.load()
                if (ran == 0L) continue
                samples[taken++] = ran - postedAt.load()
            }
            runBlocking { posting.join() }
        } finally {
            work.wakeLoop = {}
            worker.close()
            x11.close()
            work.runPending()
            work.takeWake()
        }
        samples.sort()
        val median = samples[POSTS / 2] / 1e6
        val p99 = samples[POSTS * 99 / 100 - 1] / 1e6
        val worst = samples[POSTS - 1] / 1e6
        println(
            "linux renderer dispatch-to-run latency over $POSTS dispatches: median=${fixed(median, 3)} ms " +
                "p99=${fixed(p99, 3)} ms max=${fixed(worst, 3)} ms",
        )
        assertTrue(p99 < 1.0, "the 99th percentile is ${fixed(p99, 3)} ms; work dispatched from a worker waits too long")
    }

    private companion object {
        const val POSTS = 200

        fun monotonicNanos(): Long = memScoped {
            val now = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC, now.ptr)
            now.tv_sec * 1_000_000_000L + now.tv_nsec
        }
    }
}
