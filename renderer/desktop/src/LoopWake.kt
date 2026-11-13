package dev.darkpyonix.composerust.ui.platform

/**
 * Ends a window loop's wait when work for its thread arrives from another one.
 *
 * A window loop waits on its display connection for up to a frame between turns. Work sent
 * to its thread from elsewhere (a Host worker's frame request reaching the scene's
 * dispatcher, a coroutine resumed on `Dispatchers.Main`) is run on the next turn, and
 * without a wake that turn comes only when the wait runs out. The window says how it is
 * woken; work sent from the loop's own thread needs no wake, because that thread is not
 * waiting.
 */
internal class LoopWake {
    @Volatile
    private var wake: (() -> Unit)? = null

    @Volatile
    private var loop: Thread? = null

    /** From now on, work from any thread but this one calls [wake]. */
    fun attach(wake: () -> Unit) {
        loop = Thread.currentThread()
        this.wake = wake
    }

    fun detach() {
        wake = null
        loop = null
    }

    /** Called where work was just handed over: wakes the loop unless this is its thread. */
    fun workArrived() {
        val wake = wake ?: return
        if (Thread.currentThread() !== loop) wake()
    }
}
