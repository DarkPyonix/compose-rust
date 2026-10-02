package dioxus.compose.ui.platform

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where a scene's own work runs: on the thread that draws it, once a frame.
 *
 * A scene left to choose for itself hands its work to the toolkit's queue, which is a
 * thread this renderer otherwise has nothing on. That matters because the Host a renderer
 * talks to belongs to one thread and is invisible from every other: a lazy list asking for
 * the rows it is about to show asked from the toolkit's thread, found no Host there, and
 * was told nothing had been initialised. The rows never arrived and the list stayed empty.
 *
 * Holding the work instead of running it is the whole of this. Whatever is waiting is run
 * by the frame that asks for it, on the thread the frame is drawn from, which is the
 * thread the Host is on.
 */
internal class FrameDispatcher : CoroutineDispatcher() {
    private val waiting = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        waiting.add(block)
    }

    /**
     * Runs what was waiting when this was called, and no more than that.
     *
     * The count is taken first so that work which asks for more work does not keep this
     * frame going: what it asks for waits for the next one. A frame that chased its own
     * tail would be a frame that never ended, and a window that never drew again.
     */
    fun runPending() {
        var remaining = waiting.size
        while (remaining > 0) {
            val next = waiting.poll() ?: return
            remaining--
            next.run()
        }
    }
}
