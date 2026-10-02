package dioxus.compose.runtime

import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation

/**
 * Synchronous, same-thread view of the Host.
 *
 * Every call runs on the Renderer UI thread and returns on the same call stack: the batch a
 * call produces is handed to `onMutation` before the call returns, and is released by the
 * implementation immediately afterwards. There is no queue between the two sides.
 */
interface HostConnection {
    /** Performs the handshake and streams the initial tree batch. */
    fun init(onMutation: (Mutation) -> Unit)

    /**
     * Dispatches one event and streams the resulting diff batch.
     *
     * @return the handler's synchronous result. Non-zero means "consumed".
     */
    fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long

    /** Applies state changes scheduled by Host workers before the current frame. */
    fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit)

    fun shutdown()
}
