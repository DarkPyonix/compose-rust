package dev.darkpyonix.composerust.ui.platform

import android.util.Log
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LoopMode
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.runtime.HostConnection
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
 * which happens when the arena grows. The event buffer grows too, when one event is larger
 * than it: a 64 KiB paste, a long document or a long list of picked files arrives whole
 * instead of being refused.
 */
class AndroidHostConnection : HostConnection {
    // Direct, because the Host reads it through JNI without a copy.
    private val events = GrowingEventBuffer(
        GrowingEventBuffer.INITIAL_BYTES,
        GrowingEventBuffer.MAX_BYTES,
        allocate = { bytes -> ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN) },
        view = { it },
    )
    private val out = LongArray(OUT_SLOTS)
    private var arena: ByteBuffer? = null
    private var arenaAddress = 0L

    override fun init(onMutation: (Mutation) -> Unit) {
        // Kotlin owns the process and the frame loop here, which is what the handshake
        // has to say before the Host decides whether to run one.
        val length = Protocol.handshake(events.cleared(), LoopMode.Platform)
        checkStatus(nativeInit(events.buffer, length, out), "init")
        readBatch(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        val length = events.encode(event)
        checkStatus(nativeDispatchEvent(events.buffer, length, out), "dispatch_event", event)
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

    private fun checkStatus(status: Int, operation: String, event: HostEvent? = null) {
        if (status != STATUS_OK) {
            // Which event the Host refused. A bare status says a call failed and leaves the
            // one question that locates it unanswered; the desktop connection says it too.
            val about = event?.let { " for $it" } ?: ""
            throw HostCallException("compose_rust_host_$operation returned $status$about")
        }
    }

    private companion object {
        const val STATUS_OK = 0
        const val FRAME_TAG = "dxc-frame"
    }
}

/** A boundary call that returned a non-zero status. Reported, never fatal. */
class HostCallException(message: String) : RuntimeException(message)
