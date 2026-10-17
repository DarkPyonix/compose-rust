@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.posix.pthread_equal
import platform.posix.pthread_self

/**
 * Where a scene's own work runs, and what `Dispatchers.Main` is on this renderer: the thread
 * that draws the window, once a frame.
 *
 * The Kotlin/Native twin of the desktop renderer's dispatcher, and it exists for the same
 * reason. A scene left to choose for itself hands its work to a dispatcher of the library's
 * choosing, and the Host a renderer talks to belongs to one thread and is invisible from every
 * other: a lazy list asking for the rows it is about to show asked from the wrong thread, found
 * no Host there, and was told nothing had been initialised. The rows never arrived and the list
 * stayed empty.
 *
 * It is also `Dispatchers.Main` (see [installMainDispatcher]). kotlinx.coroutines has no main
 * dispatcher for Linux: it answers with a stand-in that throws at the first dispatch, so
 * `withContext(Dispatchers.Main)` in application code worked on macOS and threw here. One
 * dispatcher serves both, so the scene's work and the application's main-thread work run in
 * the order they were sent, in the same place in the frame.
 *
 * Being main means being sent work from any thread: a Host worker that finished reading a file
 * and resumes on Main dispatches from the worker. So the queue is guarded by a lock, and a
 * dispatch from another thread wakes the frame loop: [takeWake] answers true on the window's
 * next turn, and the window draws a frame for what that work changed. The loop never sleeps
 * longer than one frame (it waits on the display connection with a frame's timeout), so what is
 * sent from anywhere runs on the next turn.
 *
 * Written again rather than shared because the desktop one holds its work in a
 * `java.util.concurrent` queue, which Kotlin/Native has not got.
 *
 * The thread that makes it is the window's thread: [runPending] must be called there, and
 * [immediate] runs work in place there.
 */
internal class FrameDispatcher : MainCoroutineDispatcher() {
    private val owner = pthread_self()

    /** Each platform's own lock (`FrameLock.kt`): a pthread mutex on Linux, an SRW lock on Windows. */
    private val lock = FrameLock()

    /** What has been sent and not yet run. Read and written only under [lock]. */
    private val waiting = ArrayDeque<Runnable>()

    /** Whether work arrived from another thread since [takeWake] last answered. Under [lock]. */
    private var woken = false

    /** What this frame runs, taken out of [waiting]. Touched only on the window's thread. */
    private val running = ArrayDeque<Runnable>()

    /** Whether this is the window's thread. */
    val isWindowThread: Boolean get() = pthread_equal(owner, pthread_self()) != 0

    /** Whether anything is waiting to be run. Any thread may ask. */
    val hasPending: Boolean get() = locked { waiting.isNotEmpty() }

    override val immediate: MainCoroutineDispatcher = Immediate()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val elsewhere = !isWindowThread
        locked {
            waiting.addLast(block)
            if (elsewhere) woken = true
        }
    }

    /**
     * Whether work was sent from another thread since the last time this answered true, and
     * clears it. The window asks once a turn and draws a frame when it is true, because that
     * work changed something nothing on the window's thread knows about.
     */
    fun takeWake(): Boolean = locked {
        val was = woken
        woken = false
        was
    }

    /**
     * Runs what was waiting when this was called, and no more than that.
     *
     * The count is taken first so that work which asks for more work does not keep this frame
     * going: what it asks for waits for the next one. A frame that chased its own tail would be
     * a frame that never ended, and a window that never drew again.
     *
     * The work is taken out under the lock and run outside it, because what runs may dispatch
     * again, and a worker sending work must never wait for a frame to finish.
     */
    fun runPending() {
        check(isWindowThread) { "the frame's work runs on the window's thread, and this is another one" }
        locked {
            while (waiting.isNotEmpty()) running.addLast(waiting.removeFirst())
        }
        while (true) {
            val next = running.removeFirstOrNull() ?: return
            next.run()
        }
    }

    override fun toString(): String = "FrameDispatcher"

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * `Dispatchers.Main.immediate`: already on the window's thread, the work runs where it is
     * rather than waiting for the next frame. Anywhere else it is sent like any other.
     */
    private inner class Immediate : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isWindowThread

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            this@FrameDispatcher.dispatch(context, block)

        override fun toString(): String = "FrameDispatcher.immediate"
    }
}
