package dioxus.compose.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated codec, running on Kotlin/Native over the `java.nio` shim.
 *
 * The interpreter and the codec are the desktop sources compiled unchanged; the buffer under
 * them is not, so this is what needs a test of its own on this target.
 */
class ProtocolBufferTest {
    /** `Create(1, Column)` then `Create(2, Text)`, `SetProp(2, Text, "hi")`, `Insert`. */
    private fun batch(): ByteArray {
        val bytes = ByteArray(128)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun record(tag: Int, length: Int) {
            out.putShort(tag.toShort())
            out.putShort(length.toShort())
        }
        // Envelope: tag 0, length 12, then the record area length and the record count.
        record(0, 12)
        out.putInt(12 + 12 + 12 + 24 + 16)
        out.putInt(4)
        record(1, 12) // Create(1, Column)
        out.putInt(1)
        out.putShort(1)
        out.putShort(0)
        record(1, 12) // Create(2, Text)
        out.putInt(2)
        out.putShort(4)
        out.putShort(0)
        record(2, 24) // SetProp(2, Text, "hi"), the string living after the records
        out.putInt(2)
        out.putShort(1)
        out.putShort(1)
        out.putInt(12 + 12 + 12 + 24 + 16)
        out.putInt(2)
        out.putInt(0) // padding to the record's 24 bytes
        record(4, 16) // Insert(1, 2, 0)
        out.putInt(1)
        out.putInt(2)
        out.putInt(0)
        out.put("hi".encodeToByteArray())
        return bytes.copyOf(out.position())
    }

    @Test
    fun pr4_fixed_layout_records_decode_on_kotlin_native() {
        val decoded = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(batch()), decoded::add)

        assertEquals(4, decoded.size)
        assertEquals(Mutation.Create(1, WidgetKind.Column), decoded[0])
        assertEquals(Mutation.Create(2, WidgetKind.Text), decoded[1])
        assertEquals(
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("hi")),
            decoded[2],
        )
        assertEquals(Mutation.Insert(1, 2, 0), decoded[3])
    }

    /**
     * The codec restores the buffer's previous byte order when it is done, and a JDK buffer
     * starts out big-endian. A shim that only accepted little-endian turned every batch into
     * a `ProtocolError` on the way out, which is how this test came to exist.
     */
    @Test
    fun pr4_decoding_restores_the_buffers_previous_byte_order() {
        val buffer = ByteBuffer.wrap(batch())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        Protocol.decode(buffer) {}
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
    }

    @Test
    fun pr4_events_encode_little_endian_whatever_the_buffer_started_as() {
        val bytes = ByteArray(64)
        val length = Protocol.encodeEvent(
            HostEvent.Clicked(nodeId = 7, handlerId = 9),
            ByteBuffer.wrap(bytes),
        )

        assertEquals(16, length)
        assertEquals(1, bytes[0].toInt()) // tag 1, low byte first
        assertEquals(0, bytes[1].toInt())
        assertEquals(7, bytes[4].toInt()) // node id
        assertEquals(9, bytes[8].toInt()) // handler id
    }

    @Test
    fun pr4_handshake_carries_the_schema_hash_the_host_checks() {
        val bytes = ByteArray(32)
        val length = Protocol.handshake(ByteBuffer.wrap(bytes))

        assertEquals(12, length)
        val hash = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        assertEquals(Protocol.SCHEMA_HASH, hash)
        assertTrue(Protocol.PROTOCOL_VERSION > 0)
    }
}
