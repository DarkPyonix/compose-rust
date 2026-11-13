@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.usleep

/**
 * Work for the window's thread that arrives from another thread ends the loop's wait at once,
 * through the window's wake, so it runs on the turn that follows rather than when the frame's
 * wait would have run out. Work sent from the window's own thread wakes nothing: that thread
 * is not waiting.
 */
class LoopWakeTest {
    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(5_000) {
            if (condition()) return
            usleep(1_000u)
        }
        fail("waited five seconds for $what")
    }

    @Test
    fun pr3_work_from_another_thread_wakes_the_window_loop() {
        val work = mainForTests()
        work.runPending()
        work.takeWake()
        val wakes = AtomicInt(0)
        work.wakeLoop = { wakes.addAndFetch(1) }
        val worker = newSingleThreadContext("wake-worker")
        try {
            CoroutineScope(worker).launch { CoroutineScope(work).launch { } }
            waitFor("the worker's work to wake the loop") { wakes.load() == 1 }
            CoroutineScope(work).launch { }
            assertEquals(1, wakes.load(), "work from the window's own thread woke the loop")
            work.runPending()
        } finally {
            work.wakeLoop = {}
            worker.close()
            work.runPending()
            work.takeWake()
        }
    }
}
