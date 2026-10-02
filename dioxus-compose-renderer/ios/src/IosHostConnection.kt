package dioxus.compose.ui.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym
import dioxus.compose.runtime.HostConnection

/**
 * The real Host boundary on iOS. The counterpart of `NativeHostConnection`.
 *
 * Same five `dioxus_compose_host_*` functions, same `MutationBatch` record, same rule that a
 * batch is decoded and released inside the call that produced it. Two things differ from the
 * desktop renderer, both consequences of Kotlin/Native rather than GraalVM:
 *
 * - The Host's symbols are found with `dlsym` on the running image instead of GraalVM's
 *   `@CFunction`. Kotlin/Native can only declare a C function through cinterop, which needs a
 *   `.def` file and a header at build time; the renderer is a static library linked into a
 *   Host executable that does not exist yet when this compiles. `dlsym(RTLD_NOW image, name)`
 *   resolves against whatever finally links it, which is the same late binding the macOS
 *   build gets from `-undefined dynamic_lookup`. The lookup happens once, not per call.
 * - Foreign memory is read through `CPointer`, not `Pointer`.
 */
class IosHostConnection : HostConnection {
    // Reused so that steady-state event dispatch allocates nothing. The frame budget
    // allows no allocation on the encode path, and a fresh buffer per event would be one.
    private val eventBytes = ByteArray(EVENT_BUFFER_BYTES)
    private val eventBuffer: ByteBuffer =
        ByteBuffer.wrap(eventBytes).order(ByteOrder.LITTLE_ENDIAN)

    override fun init(onMutation: (Mutation) -> Unit) {
        eventBuffer.clear()
        val length = Protocol.handshake(eventBuffer)
        eventBytes.usePinned { pinned ->
            memScoped {
                val batch = allocArray<ByteVar>(BATCH_BYTES)
                checkStatus(
                    HostSymbols.init(pinned.addressOf(0), length.toUInt(), batch),
                    "init",
                )
                readBatch(batch, onMutation)
            }
        }
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        eventBuffer.clear()
        val length = Protocol.encodeEvent(event, eventBuffer)
        var result = 0L
        eventBytes.usePinned { pinned ->
            memScoped {
                val batch = allocArray<ByteVar>(BATCH_BYTES)
                checkStatus(
                    HostSymbols.dispatchEvent(pinned.addressOf(0), length.toUInt(), batch),
                    "dispatch_event",
                )
                result = batch.resultField()
                readBatch(batch, onMutation)
            }
        }
        return result
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        memScoped {
            val batch = allocArray<ByteVar>(BATCH_BYTES)
            checkStatus(
                HostSymbols.renderFrame(frameTimeNanos.toULong(), batch),
                "render_frame",
            )
            readBatch(batch, onMutation)
        }
    }

    override fun shutdown() = HostSymbols.shutdown()

    /**
     * Decodes in place, then releases on the same call stack, always.
     *
     * The batch points into an arena the Host reuses, so it is only valid until the call
     * that produced it returns. Holding on to it, or queueing it, reads freed memory.
     */
    private fun readBatch(batch: CPointer<ByteVar>, onMutation: (Mutation) -> Unit) {
        try {
            val pointer = batch.reinterpret<CPointerVar<ByteVar>>()[0]
            val length = (batch + LENGTH_OFFSET)!!.reinterpret<UIntVar>()[0].toInt()
            if (pointer != null && length > 0) {
                Protocol.decode(ByteBuffer.wrapPointer(pointer, length), onMutation)
            }
        } finally {
            HostSymbols.releaseBatch(batch)
        }
    }

    private fun CPointer<ByteVar>.resultField(): Long =
        (this + RESULT_OFFSET)!!.reinterpret<LongVar>()[0]

    private fun checkStatus(status: Int, operation: String) {
        if (status != STATUS_OK) {
            throw HostCallException("dioxus_compose_host_$operation returned $status")
        }
    }

    private companion object {
        const val STATUS_OK = 0
        const val BATCH_BYTES = 24
        const val LENGTH_OFFSET = 8L
        const val RESULT_OFFSET = 16L
        const val EVENT_BUFFER_BYTES = 4096
    }
}

private typealias BufferCall =
    CFunction<(CPointer<ByteVar>?, UInt, CPointer<ByteVar>?) -> Int>
private typealias FrameCall = CFunction<(ULong, CPointer<ByteVar>?) -> Int>
private typealias BatchCall = CFunction<(CPointer<ByteVar>?) -> Unit>
private typealias VoidCall = CFunction<() -> Unit>

/**
 * The Host's exports, resolved once from the image this library was linked into.
 *
 * A missing symbol is a link-time mistake on the Host side, so it fails loudly and early
 * rather than at the first event.
 */
private object HostSymbols {
    private val image = dlopen(null, RTLD_NOW)

    private val initFn = lookup<BufferCall>("dioxus_compose_host_init")
    private val dispatchEventFn = lookup<BufferCall>("dioxus_compose_host_dispatch_event")
    private val renderFrameFn = lookup<FrameCall>("dioxus_compose_host_render_frame")
    private val releaseBatchFn = lookup<BatchCall>("dioxus_compose_host_release_batch")
    private val shutdownFn = lookup<VoidCall>("dioxus_compose_host_shutdown")

    fun init(handshake: CPointer<ByteVar>?, length: UInt, out: CPointer<ByteVar>?): Int =
        initFn(handshake, length, out)

    fun dispatchEvent(event: CPointer<ByteVar>?, length: UInt, out: CPointer<ByteVar>?): Int =
        dispatchEventFn(event, length, out)

    fun renderFrame(frameTimeNanos: ULong, out: CPointer<ByteVar>?): Int =
        renderFrameFn(frameTimeNanos, out)

    fun releaseBatch(batch: CPointer<ByteVar>?) = releaseBatchFn(batch)

    fun shutdown() = shutdownFn()

    private fun <T : CFunction<*>> lookup(name: String): CPointer<T> =
        dlsym(image, name)?.reinterpret()
            ?: throw HostCallException(
                "$name is not in this image. The renderer resolves the Host's functions by " +
                    "name at startup, so the Host executable has to export all five of " +
                    "dioxus_compose_host_init, _dispatch_event, _render_frame, " +
                    "_release_batch and _shutdown. If they are present but not found, they " +
                    "are probably missing from the dynamic symbol table: build the Host " +
                    "with -Wl,-export_dynamic.",
            )
}
