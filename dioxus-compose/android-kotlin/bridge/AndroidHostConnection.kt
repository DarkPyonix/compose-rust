package dioxus.compose.ui.platform

import android.util.Log
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.LoopMode
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import dioxus.compose.runtime.HostConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Host as seen from Android.
 *
 * Every call below runs on the Renderer UI thread and returns on the same call stack. It
 * crosses one generated JNI shim, and nothing is copied on the way back: the shim reports
 * where the batch sits inside the Host's arena, and this class reads it through a direct
 * `ByteBuffer` made once over that arena.
 *
 * Steady state allocates nothing. The event buffer, the `out` array and the arena view are
 * all reused, and the arena view is only remade when the Host reports a new arena address,
 * which happens when the arena grows.
 */
class AndroidHostConnection : HostConnection {
    private val eventBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(EVENT_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val out = LongArray(OUT_SLOTS)
    private var arena: ByteBuffer? = null
    private var arenaAddress = 0L

    override fun init(onMutation: (Mutation) -> Unit) {
        eventBuffer.clear()
        // Kotlin owns the process and the frame loop here, which is what the handshake
        // has to say before the Host decides whether to run one.
        val length = Protocol.handshake(eventBuffer, LoopMode.Platform)
        checkStatus(nativeInit(eventBuffer, length, out), "init")
        readBatch(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        eventBuffer.clear()
        val length = Protocol.encodeEvent(event, eventBuffer)
        checkStatus(nativeDispatchEvent(eventBuffer, length, out), "dispatch_event")
        readBatch(onMutation)
        return out[OUT_RESULT]
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        // The other half of the evidence the generated upcall logs: this line says the
        // request reached the frame clock, and the thread it names is the UI thread.
        if (Log.isLoggable(FRAME_TAG, Log.VERBOSE)) {
            Log.v(FRAME_TAG, "frame applied on ${Thread.currentThread().name}")
        }
        checkStatus(nativeRenderFrame(frameTimeNanos, out), "render_frame")
        readBatch(onMutation)
    }

    override fun shutdown() {
        nativeShutdown()
        arena = null
        arenaAddress = 0L
    }

    private fun readBatch(onMutation: (Mutation) -> Unit) {
        try {
            val length = out[OUT_BATCH_LENGTH].toInt()
            if (length <= 0) return
            val view = arenaView() ?: return
            val offset = out[OUT_BATCH_OFFSET].toInt()
            view.clear()
            view.position(offset)
            view.limit(offset + length)
            Protocol.decode(view, onMutation)
        } finally {
            // Released on the same call stack, always: the batch points into an arena the
            // Host reuses, so it is invalid the moment this call returns.
            nativeReleaseBatch()
        }
    }

    /**
     * The arena, mapped once.
     *
     * A grown arena is a different allocation, so the address the call reported is what
     * decides whether the cached view still points at live memory.
     */
    private fun arenaView(): ByteBuffer? {
        val address = out[OUT_ARENA_ADDRESS]
        val cached = arena
        if (cached != null && address == arenaAddress) return cached
        val created = nativeArenaBuffer() ?: return null
        created.order(ByteOrder.LITTLE_ENDIAN)
        arena = created
        arenaAddress = address
        return created
    }

    private fun checkStatus(status: Int, operation: String) {
        if (status != STATUS_OK) {
            throw HostCallException("dioxus_compose_host_$operation returned $status")
        }
    }

    private companion object {
        const val STATUS_OK = 0
        const val EVENT_BUFFER_BYTES = 4096
        const val FRAME_TAG = "dxc-frame"
    }
}

/** A boundary call that returned a non-zero status. Reported, never fatal. */
class HostCallException(message: String) : RuntimeException(message)
