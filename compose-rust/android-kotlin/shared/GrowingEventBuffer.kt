package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.ProtocolException
import java.nio.ByteBuffer

/**
 * The buffer a connection encodes one event into before the call that carries it.
 *
 * Reused so that steady-state dispatch allocates nothing: the frame budget allows no
 * allocation on the encode path, and a fresh buffer per event would be one.
 *
 * It grows when one event is larger than it, a field holding a pasted document or a long
 * list of picked files for instance, and keeps the larger size, so only that rare event
 * allocates. It doubles until the event fits, up to [maxBytes]. An event larger than that is
 * refused with the encoder's own [ProtocolException], which the caller reports instead of
 * sending half of it.
 *
 * Each platform keeps its own kind of memory behind the buffer: Android needs a direct
 * buffer the Host can read through JNI, the native renderers need a `ByteArray` they can
 * pin. [allocate] makes that backing and [view] gives the codec a little-endian view of it.
 */
class GrowingEventBuffer<T : Any>(
    initialBytes: Int,
    private val maxBytes: Int,
    private val allocate: (Int) -> T,
    private val view: (T) -> ByteBuffer,
) {
    init {
        require(initialBytes in 1..maxBytes) {
            "the event buffer starts at $initialBytes bytes, which is not between 1 and $maxBytes"
        }
    }

    /** How many bytes the buffer holds now. */
    var capacity: Int = initialBytes
        private set

    /** What the Host is handed: the memory the last event was encoded into. */
    var backing: T = allocate(initialBytes)
        private set

    /** The codec's view of [backing]. Replaced, never resized, when the buffer grows. */
    var buffer: ByteBuffer = view(backing)
        private set

    /** The buffer, cleared, for something that knows it fits, like the handshake. */
    fun cleared(): ByteBuffer = buffer.clear()

    /** Encodes [event] from the start of the buffer, growing it first if it has to. */
    fun encode(event: HostEvent): Int {
        while (true) {
            buffer.clear()
            try {
                return Protocol.encodeEvent(event, buffer)
            } catch (tooSmall: ProtocolException) {
                // The encoder checks the whole length before it writes a byte, so a refusal
                // leaves nothing half written, and trying again in a larger buffer is safe.
                if (capacity >= maxBytes) throw tooSmall
                val grown = if (capacity > maxBytes / 2) maxBytes else capacity * 2
                backing = allocate(grown)
                buffer = view(backing)
                capacity = grown
            }
        }
    }

    companion object {
        /** Where every connection starts: room for any event that is not carrying a document. */
        const val INITIAL_BYTES: Int = 4096

        /**
         * The largest event a connection will build. Past this the event is refused, not cut
         * short: a single event that large is a bug in whatever produced it, and allocating
         * without a ceiling would turn that bug into running out of memory.
         */
        const val MAX_BYTES: Int = 64 * 1024 * 1024
    }
}
