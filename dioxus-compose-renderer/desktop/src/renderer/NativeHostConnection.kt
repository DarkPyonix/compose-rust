package dioxus.compose.ui.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.graalvm.nativeimage.PinnedObject
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.word.Pointer
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import dioxus.compose.runtime.HostConnection

// Word-typed parameters are declared nullable on purpose: Kotlin emits a null check for a
// non-null reference parameter, and that check would hand the word value to a method that
// expects an Object, which native-image rejects.
//
// The only file that mentions GraalVM types, so a JVM development run never loads them
// and can still hot reload. The `dioxus_compose_host_*` symbols are resolved from the Rust
// executable this library is loaded into; the library is linked with `-undefined
// dynamic_lookup`.

@CFunction("dioxus_compose_host_init")
private external fun hostInit(handshake: CCharPointer?, length: Int, out: Pointer?): Int

@CFunction("dioxus_compose_host_dispatch_event")
private external fun hostDispatchEvent(event: CCharPointer?, length: Int, out: Pointer?): Int

@CFunction("dioxus_compose_host_render_frame")
private external fun hostRenderFrame(frameTimeNanos: Long, out: Pointer?): Int

@CFunction("dioxus_compose_host_release_batch")
private external fun hostReleaseBatch(batch: Pointer?)

@CFunction("dioxus_compose_host_shutdown")
private external fun hostShutdown()

/**
 * The real Host boundary.
 *
 * Each call writes a `MutationBatch { const uint8_t* ptr; uint32_t len; int64_t result; }`
 * into stack storage, the batch is decoded in place and applied on the same call stack, and
 * `release_batch` runs before returning. No batch is ever kept.
 */
class NativeHostConnection : HostConnection {
    // Reused so that steady-state event dispatch allocates nothing. The frame budget allows
    // no allocation on the encode path, and a fresh buffer per event would be one.
    private val eventBytes = ByteArray(EVENT_BUFFER_BYTES)
    private val eventBuffer: ByteBuffer =
        ByteBuffer.wrap(eventBytes).order(ByteOrder.LITTLE_ENDIAN)

    override fun init(onMutation: (Mutation) -> Unit) {
        eventBuffer.clear()
        val length = Protocol.handshake(eventBuffer)
        // Word values never cross a method or lambda boundary here: native-image only
        // accepts them in straight-line code inside a single method.
        val pinned = PinnedObject.create(eventBytes)
        try {
            val batch = StackValue.get<Pointer>(BATCH_BYTES)
            checkStatus(hostInit(pinned.addressOfArrayElement(0), length, batch), "init")
            try {
                val pointer = batch.readWord<Pointer>(PTR_OFFSET)
                val batchLength = batch.readInt(LENGTH_OFFSET)
                if (pointer.isNonNull && batchLength > 0) {
                    Protocol.decode(CTypeConversion.asByteBuffer(pointer, batchLength), onMutation)
                }
            } finally {
                hostReleaseBatch(batch)
            }
        } finally {
            pinned.close()
        }
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        eventBuffer.clear()
        val length = Protocol.encodeEvent(event, eventBuffer)
        var result = 0L
        val pinned = PinnedObject.create(eventBytes)
        try {
            val batch = StackValue.get<Pointer>(BATCH_BYTES)
            checkStatus(
                hostDispatchEvent(pinned.addressOfArrayElement(0), length, batch),
                "dispatch_event",
                event,
            )
            try {
                result = batch.readLong(RESULT_OFFSET)
                val pointer = batch.readWord<Pointer>(PTR_OFFSET)
                val batchLength = batch.readInt(LENGTH_OFFSET)
                if (pointer.isNonNull && batchLength > 0) {
                    Protocol.decode(CTypeConversion.asByteBuffer(pointer, batchLength), onMutation)
                }
            } finally {
                hostReleaseBatch(batch)
            }
        } finally {
            pinned.close()
        }
        return result
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        val batch = StackValue.get<Pointer>(BATCH_BYTES)
        checkStatus(hostRenderFrame(frameTimeNanos, batch), "render_frame")
        try {
            val pointer = batch.readWord<Pointer>(PTR_OFFSET)
            val batchLength = batch.readInt(LENGTH_OFFSET)
            if (pointer.isNonNull && batchLength > 0) {
                Protocol.decode(CTypeConversion.asByteBuffer(pointer, batchLength), onMutation)
            }
        } finally {
            // Released on the same call stack, always: the batch points into an arena the
            // Host reuses, so it is invalid the moment this call returns.
            hostReleaseBatch(batch)
        }
    }

    override fun shutdown() = hostShutdown()

    private fun checkStatus(status: Int, operation: String, event: HostEvent? = null) {
        if (status != STATUS_OK) {
            // Which event, and from where. A bare status says a call was refused and
            // leaves the two questions that actually locate it unanswered: -2 is the Host
            // saying it has no state on this thread, so the thread is half the answer.
            val about = event?.let { " for $it on thread ${Thread.currentThread().name}" } ?: ""
            throw HostCallException("dioxus_compose_host_$operation returned $status$about")
        }
    }

    private companion object {
        const val STATUS_OK = 0
        const val BATCH_BYTES = 24
        const val PTR_OFFSET = 0
        const val LENGTH_OFFSET = 8
        const val RESULT_OFFSET = 16
        const val EVENT_BUFFER_BYTES = 4096
    }
}

/** A boundary call that returned a non-zero status. Reported, never fatal. */
class HostCallException(message: String) : RuntimeException(message)
