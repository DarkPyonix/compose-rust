package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.FrameDispatcher
import dev.darkpyonix.composerust.ui.platform.FrameMainDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Work for the window's thread that arrives from another thread wakes the window's loop, so
 * it runs on the turn that follows at once rather than when the loop's wait runs out. Work
 * sent from the loop's own thread wakes nothing: that thread is not waiting.
 */
class LoopWakeTest {
    @Test
    fun pr3_work_from_another_thread_wakes_the_loop() {
        val work = FrameDispatcher()
        val wakes = AtomicInteger()
        work.loopWake.attach { wakes.incrementAndGet() }
        try {
            Thread { CoroutineScope(work).launch { } }.apply { start() }.join()
            assertEquals(1, wakes.get(), "work from a worker did not wake the loop")
            CoroutineScope(work).launch { }
            assertEquals(1, wakes.get(), "work from the loop's own thread woke it")
            work.runPending()
        } finally {
            work.loopWake.detach()
        }
    }

    @Test
    fun pr3_main_work_from_another_thread_wakes_the_loop() {
        val wakes = AtomicInteger()
        FrameMainDispatcher.loopWake.attach { wakes.incrementAndGet() }
        try {
            Thread { CoroutineScope(FrameMainDispatcher).launch { } }.apply { start() }.join()
            assertEquals(1, wakes.get(), "main work from a worker did not wake the loop")
            FrameMainDispatcher.runPending()
        } finally {
            FrameMainDispatcher.loopWake.detach()
        }
    }

    @Test
    fun pr3_a_detached_wake_is_not_called() {
        val work = FrameDispatcher()
        val wakes = AtomicInteger()
        work.loopWake.attach { wakes.incrementAndGet() }
        work.loopWake.detach()
        Thread { CoroutineScope(work).launch { } }.apply { start() }.join()
        assertEquals(0, wakes.get())
        work.runPending()
    }
}
