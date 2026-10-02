@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package dioxus.compose.ui.platform

import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.LoopMode
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import dioxus.compose.runtime.HostConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * The Host as seen from the browser.
 *
 * Both modules read and write one `WebAssembly.Memory`, so a batch is decoded where the
 * Host wrote it and no byte of it is copied. What crosses is the address and the length,
 * through a generated JavaScript arrow function that passes them on: about 12ns a call, and
 * the only reason it is there at all is that a wasm import has to be supplied before the
 * module that would define the shared memory exists.
 *
 * Every call runs on the frame loop's thread and returns on the same call stack, and one
 * event costs two calls: `dispatchEvent` and the `releaseBatch` that follows it.
 *
 * Steady state allocates nothing. The event buffer and the batch record belong to the Host,
 * because `kotlin.wasm.unsafe` only hands out addresses that are valid inside the scope that
 * asked for them, and the view over the Host's half of the memory is made once and remade
 * only when the arena outgrows it.
 */
class WebHostConnection private constructor(block: Int) : HostConnection {
    /** Where a call reports what it did. Little-endian, like everything on the wire. */
    private val out: ByteBuffer =
        ByteBuffer.wrapPointer(pointerAt(block), BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** Where one event is encoded before the call that carries it. */
    private val eventBuffer: ByteBuffer =
        ByteBuffer.wrapPointer(pointerAt(block + EVENT_BUFFER_OFFSET), EVENT_BUFFER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

    private val outAddress = block
    private val eventAddress = block + EVENT_BUFFER_OFFSET

    /**
     * The address of the record a call reports into, for the test that times a call.
     *
     * `ReleaseBatch` is the cheapest real boundary function there is, which makes it the one
     * to time, and timing it needs the address it takes.
     */
    internal val outAddressForMeasurement: Int get() = outAddress

    /** A view over the Host's half of the memory, and how far it reaches. */
    private var region: ByteBuffer? = null
    private var regionEnd = 0

    override fun init(onMutation: (Mutation) -> Unit) {
        eventBuffer.clear()
        // The page owns the loop here, which is what the handshake has to say before the
        // Host decides whether to run one.
        val length = Protocol.handshake(eventBuffer, LoopMode.Platform)
        checkStatus(hostInit(eventAddress, length, outAddress), "init")
        readBatch(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        eventBuffer.clear()
        val length = Protocol.encodeEvent(event, eventBuffer)
        checkStatus(hostDispatchEvent(eventAddress, length, outAddress), "dispatch_event")
        val result = out.getLong(BATCH_RESULT_OFFSET)
        readBatch(onMutation)
        return result
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        // Split rather than passed whole: a 64-bit argument reaches the forwarder as a
        // BigInt, and that would be a heap allocation on the call that happens every frame.
        val low = frameTimeNanos.toInt()
        val high = (frameTimeNanos ushr 32).toInt()
        checkStatus(hostRenderFrame(low, high, outAddress), "render_frame")
        readBatch(onMutation)
    }

    override fun shutdown() {
        hostShutdown()
        region = null
        regionEnd = 0
    }

    /**
     * Decodes in place, then releases on the same call stack, always.
     *
     * The batch points into an arena the Host reuses, so it is only valid until the call
     * that produced it returns. Keeping it, or putting it on a queue, reads memory the next
     * frame has already written over.
     */
    private fun readBatch(onMutation: (Mutation) -> Unit) {
        try {
            val length = out.getInt(BATCH_LENGTH_OFFSET)
            if (length <= 0) return
            val address = out.getInt(BATCH_ADDRESS_OFFSET)
            if (address < RUST_REGION_BASE) {
                throw HostCallException(
                    "the Host reported a batch at $address, which is below the region at " +
                        "$RUST_REGION_BASE that belongs to it. Either the Host was linked " +
                        "without --global-base, or the two allocators sharing this memory " +
                        "have grown into each other.",
                )
            }
            val view = regionView(address + length)
            view.clear()
            view.position(address - RUST_REGION_BASE)
            view.limit(address - RUST_REGION_BASE + length)
            Protocol.decode(view, onMutation)
        } finally {
            hostReleaseBatch(outAddress)
        }
    }

    /**
     * The view the batch is read through, covering at least up to `end`.
     *
     * Remade when the arena has grown past what the last one reached, which happens while
     * the tree is settling and then stops. Rounded up to whole pages so that growing by a
     * record does not remake it.
     */
    private fun regionView(end: Int): ByteBuffer {
        val cached = region
        if (cached != null && end <= regionEnd) return cached
        val needed = end - RUST_REGION_BASE
        val length = (needed + PAGE_BYTES - 1) / PAGE_BYTES * PAGE_BYTES
        val created = ByteBuffer.wrapPointer(pointerAt(RUST_REGION_BASE), length)
            .order(ByteOrder.LITTLE_ENDIAN)
        region = created
        regionEnd = RUST_REGION_BASE + length
        return created
    }

    private fun checkStatus(status: Int, operation: String) {
        if (status != STATUS_OK) {
            throw HostCallException("dioxus_compose_host_$operation returned $status")
        }
    }

    companion object {
        /**
         * Installs the Host on this module's memory and takes the block it lends back.
         *
         * Null when there is no Host on this page, which is how the renderer is served on
         * its own while the other side is not being built. The caller draws its development
         * host instead.
         *
         * Called once, from `main`: it is the first moment at which both halves exist, and
         * the Host's `web_start` registers the application's root component on the way.
         */
        fun install(): WebHostConnection? {
            requireSeparateRegions()
            val block = installHost()
            if (block < RUST_REGION_BASE) return null
            return WebHostConnection(block)
        }

        /**
         * Checks that this module's allocator is on its own side of the line.
         *
         * Two allocators hand out addresses in one memory. If they overlap, nothing traps:
         * the Host writes a batch over something Compose was using, or the other way round,
         * and the screen is quietly wrong. The check costs one allocation at startup and is
         * the only place the overlap can be caught before it has happened.
         */
        private fun requireSeparateRegions() {
            val address = withScopedMemoryAllocator { allocator ->
                allocator.allocate(PROBE_BYTES).address
            }
            if (address >= RUST_REGION_BASE.toUInt()) {
                throw HostCallException(
                    "this module's allocator handed out address $address, which is inside " +
                        "the region at $RUST_REGION_BASE that the Host was linked into. " +
                        "The two would write over each other, so no boundary call is made.",
                )
            }
        }

        /**
         * Names an address in the shared memory.
         *
         * `Pointer`'s constructor is internal to the standard library, so an address the
         * allocator did not hand out cannot be named directly. The way in is arithmetic
         * from one it did: `plus` is unsigned, so it reaches below the allocation as well as
         * above it. The anchor is only ever used as a base, never read or written, so it
         * does not matter that its scope has closed by then.
         */
        private fun pointerAt(address: Int): Pointer =
            withScopedMemoryAllocator { allocator ->
                val anchor = allocator.allocate(PROBE_BYTES)
                anchor + (address.toUInt() - anchor.address)
            }

        private const val STATUS_OK = 0
        private const val PAGE_BYTES = 65536
        private const val PROBE_BYTES = 4
    }
}

/** A boundary call that returned a non-zero status. Reported, never fatal. */
class HostCallException(message: String) : RuntimeException(message)
