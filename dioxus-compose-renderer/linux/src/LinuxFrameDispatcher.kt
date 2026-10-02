package dioxus.compose.ui.platform

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * Where a scene's own work runs: on the thread that draws it, once a frame.
 *
 * The Kotlin/Native twin of the desktop renderer's dispatcher, and it exists for the same
 * reason. A scene left to choose for itself hands its work to a dispatcher of the library's
 * choosing, and the Host a renderer talks to belongs to one thread and is invisible from every
 * other: a lazy list asking for the rows it is about to show asked from the wrong thread, found
 * no Host there, and was told nothing had been initialised. The rows never arrived and the list
 * stayed empty.
 *
 * Written again rather than shared because the desktop one holds its work in a
 * `java.util.concurrent` queue, which Kotlin/Native has not got. It needs no concurrent queue
 * either: a scene dispatches from the composition, the composition runs where the frame is
 * drawn, and that is this window's own thread. The Host's worker threads reach this renderer
 * through the frame request counter and never through here.
 */
internal class FrameDispatcher : CoroutineDispatcher() {
    private val waiting = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        waiting.addLast(block)
    }

    /**
     * Runs what was waiting when this was called, and no more than that.
     *
     * The count is taken first so that work which asks for more work does not keep this frame
     * going: what it asks for waits for the next one. A frame that chased its own tail would be
     * a frame that never ended, and a window that never drew again.
     */
    fun runPending() {
        var remaining = waiting.size
        while (remaining > 0) {
            val next = waiting.removeFirstOrNull() ?: return
            remaining--
            next.run()
        }
    }
}
