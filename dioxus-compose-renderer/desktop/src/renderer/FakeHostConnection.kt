package dioxus.compose.tooling

import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.runtime.HostConnection

/** What a scripted Host answers to one event: a diff batch plus the handler result. */
data class HostResponse(
    val mutations: List<Mutation> = emptyList(),
    val result: Long = 0,
)

/**
 * In-memory Host used by previews, the JVM dev shell, and tests. Working on the renderer
 * must not require building the Rust side or a native image.
 *
 * It is a fake, not a mock: it behaves like the real Host from the interpreter's point of
 * view - batches arrive on the same call stack, and events are recorded in arrival order.
 */
class FakeHostConnection(
    private val initialBatch: List<Mutation> = emptyList(),
) : HostConnection {
    private val recordedEvents = mutableListOf<HostEvent>()
    private val frameBatches = ArrayDeque<List<Mutation>>()
    private var responder: (HostEvent) -> HostResponse = { HostResponse() }

    var initialized: Boolean = false
        private set
    var shutdownCalled: Boolean = false
        private set

    /** Events the interpreter has sent, in order. */
    val events: List<HostEvent> get() = recordedEvents

    /**
     * Everything that happened inside the window, without what happened to the window.
     *
     * A few events are addressed to the Host rather than to a node: a resync, the
     * lifecycle pair, and the design system the Renderer resolved the theme to. They
     * carry node id 0 because there is no node they are about. A test that asks what a
     * click produced is not asking about those, and counting them made seven such tests
     * fail the day the resolved design system began to be reported.
     */
    val nodeEvents: List<HostEvent> get() = recordedEvents.filter { it.nodeId != 0 }

    /** Scripts the answer to every event. */
    fun respondWith(responder: (HostEvent) -> HostResponse) {
        this.responder = responder
    }

    /** Queues a batch to be returned by the next `renderFrame`. */
    fun scheduleFrame(mutations: List<Mutation>) {
        frameBatches.addLast(mutations)
    }

    override fun init(onMutation: (Mutation) -> Unit) {
        initialized = true
        initialBatch.forEach(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        recordedEvents += event
        // Recorded, and then left alone. A fixture's canned reply stands for a Host
        // answering something that happened in the window, and the resolved design system
        // is not that: it is the Renderer telling the Host what it decided, sent once when
        // the theme resolves. Answering it with the reply meant for a click applied that
        // reply before the click did, and three tests measured the difference rather than
        // what they were about.
        if (event is HostEvent.DesignSystemResolved) return 0
        val response = responder(event)
        response.mutations.forEach(onMutation)
        return response.result
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        frameBatches.removeFirstOrNull()?.forEach(onMutation)
    }

    override fun shutdown() {
        shutdownCalled = true
    }
}
