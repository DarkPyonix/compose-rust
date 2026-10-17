@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.pthread_self
import platform.posix.usleep

/**
 * The process's main dispatcher for these tests: made and installed once, on the thread the
 * tests run on, which plays the window's thread. The renderer does the same at start, on the
 * thread that then runs the window.
 */
private val testMain: FrameDispatcher by lazy { FrameDispatcher().also { installMainDispatcher(it) } }

internal fun mainForTests(): FrameDispatcher = testMain

/**
 * `Dispatchers.Main` on the Linux renderer is the window's frame dispatcher: what is sent to it
 * from any thread runs on the window's thread, in the frame after it was sent, and
 * `Dispatchers.Main.immediate` runs in place when it is already there.
 *
 * kotlinx.coroutines has no main dispatcher for Linux. Before the renderer installed one,
 * `withContext(Dispatchers.Main)` in application code threw here, at the first dispatch, and
 * worked on every other renderer.
 */
class FrameMainDispatcherTest {

    private fun thisThread(): Long = pthread_self().toLong()

    /** Waits, a millisecond at a time, for up to five seconds. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(5_000) {
            if (condition()) return
            usleep(1_000u)
        }
        fail("waited five seconds for $what")
    }

    @Test
    fun pr3_dispatchers_main_is_the_frame_dispatcher() {
        val main = mainForTests()
        assertSame<Any>(main, Dispatchers.Main, "Dispatchers.Main is the window's frame dispatcher")
    }

    @Test
    fun pr3_with_context_main_from_another_thread_runs_on_the_window_thread_in_the_next_frame() {
        val main = mainForTests()
        main.runPending()
        main.takeWake()
        val windowThread = thisThread()
        val ranOn = AtomicLong(0L)
        val resumedOn = AtomicLong(0L)
        val finished = AtomicInt(0)
        val worker = newSingleThreadContext("pr3-worker")
        try {
            val workerThread = AtomicLong(0L)
            CoroutineScope(worker).launch {
                workerThread.store(thisThread())
                // What application code on a Host worker writes to touch the UI.
                withContext(Dispatchers.Main) { ranOn.store(thisThread()) }
                resumedOn.store(thisThread())
                finished.store(1)
            }
            waitFor("the worker to send its work to Dispatchers.Main") { main.hasPending }
            assertEquals(0L, ranOn.load(), "work sent to Main waits for the frame rather than running on the sender")
            assertTrue(main.takeWake(), "work sent from another thread wakes the frame loop")

            // One frame.
            main.runPending()
            assertEquals(windowThread, ranOn.load(), "work sent to Main runs on the window's thread")

            waitFor("the worker to resume after withContext") { finished.load() == 1 }
            assertEquals(workerThread.load(), resumedOn.load(), "withContext returns to the thread that called it")
            assertTrue(windowThread != workerThread.load(), "the worker is another thread")
        } finally {
            worker.close()
        }
    }

    @Test
    fun pr3_launch_on_main_from_the_window_thread_runs_in_the_next_frame_without_a_wake() {
        val main = mainForTests()
        main.runPending()
        main.takeWake()
        val ranOn = AtomicLong(0L)
        CoroutineScope(Dispatchers.Main).launch { ranOn.store(thisThread()) }
        assertEquals(0L, ranOn.load(), "Dispatchers.Main always dispatches, even from its own thread")
        assertFalse(main.takeWake(), "work sent from the window's own thread needs no wake")
        main.runPending()
        assertEquals(thisThread(), ranOn.load(), "work sent to Main runs on the window's thread")
    }

    @Test
    fun pr3_main_immediate_does_not_hop_on_the_window_thread() {
        val main = mainForTests()
        main.runPending()
        val ranOn = AtomicLong(0L)
        assertFalse(
            Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext),
            "on the window's thread, Main.immediate needs no dispatch",
        )
        CoroutineScope(Dispatchers.Main.immediate).launch { ranOn.store(thisThread()) }
        assertEquals(thisThread(), ranOn.load(), "Main.immediate runs in place on the window's thread")
        assertFalse(main.hasPending, "nothing was queued for the next frame")
    }

    @Test
    fun pr3_main_immediate_dispatches_from_another_thread() {
        val main = mainForTests()
        main.runPending()
        val needed = AtomicInt(-1)
        val worker = newSingleThreadContext("pr3-immediate")
        try {
            runBlocking(worker) {
                needed.store(if (Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)) 1 else 0)
            }
        } finally {
            worker.close()
        }
        assertEquals(1, needed.load(), "off the window's thread, Main.immediate sends the work to it")
    }

    @Test
    fun pr3_a_frame_runs_only_what_was_waiting_when_it_started() {
        val main = mainForTests()
        main.runPending()
        val runs = AtomicInt(0)
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            runs.addAndFetch(1)
            scope.launch { runs.addAndFetch(1) }
        }
        main.runPending()
        assertEquals(1, runs.load(), "work sent during a frame waits for the next one")
        main.runPending()
        assertEquals(2, runs.load())
    }
}
