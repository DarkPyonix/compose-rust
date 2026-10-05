@file:OptIn(InternalCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * Compose's main-thread work on a window that has no toolkit.
 *
 * Compose sends the apply notifications of its snapshots and a few timers to a main
 * dispatcher, and on a desktop that is Swing's event queue by default. Loading it loads the
 * Java toolkit and the native library under it, which a window opened by this renderer has no
 * other use for. The Compose fork can send that work to `Dispatchers.Main` instead, when the
 * system property `compose.main.dispatcher` says `coroutines`, and this is what answers there:
 * the work waits, and the frame loop that owns the window runs it once per frame, on its own
 * thread, ahead of the events of that frame. [runPending] is that run.
 */
internal object FrameMainDispatcher : MainCoroutineDispatcher() {
    private val waiting = ConcurrentLinkedQueue<Runnable>()

    override val immediate: MainCoroutineDispatcher get() = this

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        waiting.add(block)
    }

    /**
     * Runs what was waiting when this was called, and no more than that, so that work which
     * asks for more work does not keep a frame from ending.
     */
    fun runPending() {
        var remaining = waiting.size
        while (remaining > 0) {
            val next = waiting.poll() ?: return
            remaining--
            next.run()
        }
    }

    /** Turns the dispatcher on for this process. Before Compose asks for its main dispatcher. */
    fun install() {
        System.setProperty("compose.main.dispatcher", "coroutines")
    }
}

/**
 * The provider kotlinx.coroutines finds through `META-INF/services`.
 *
 * It outranks the others only once [FrameMainDispatcher.install] has run. A run on a JVM
 * with the toolkit's window never calls it, so Swing's provider keeps `Dispatchers.Main`
 * there.
 */
internal class FrameMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int
        get() = if (System.getProperty("compose.main.dispatcher") == "coroutines") Int.MAX_VALUE else Int.MIN_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher =
        FrameMainDispatcher

    override fun hintOnError(): String? = null
}
