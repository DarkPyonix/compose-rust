package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.ProtocolException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The 3D transform record against the bytes the Rust Host writes: one 92 byte `SetModifier`
 * on node 3, the column-major matrix, the origin and the backface flag.
 */
class Transform3DProtocolTest {

    private val expected = ProtocolModifier.Transform3D(
        0.5f, -0.25f, 1.5f, 0.125f,
        -2.0f, 3.0f, 0.75f, -0.5f,
        4.0f, -1.25f, 2.5f, -0.0625f,
        12.0f, -6.0f, 8.0f, 1.0f,
        0.5f, 0.25f,
        true,
    )

    @Test
    fun fr41_3_transform3d_round_trips() {
        val bytes = vector()
        assertEquals(104, bytes.size)
        assertEquals(listOf(Mutation.SetModifier(3, 0, expected)), decode(bytes))
        // The layout the Host writes: modifier tag 27 at offset 22 of the file, the
        // matrix from offset 24, the origin at 88 and 92, the flag as a whole word at 96.
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(92, buffer.getShort(14).toInt())
        assertEquals(27, buffer.getShort(22).toInt())
        assertEquals(0.5f, buffer.getFloat(24))
        assertEquals(1.0f, buffer.getFloat(24 + 4 * 15))
        assertEquals(0.5f, buffer.getFloat(88))
        assertEquals(0.25f, buffer.getFloat(92))
        assertEquals(1L, buffer.getLong(96))
    }

    @Test
    fun fr41_3_non_finite_is_a_protocol_error() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (field in 0 until 18) {
                val bytes = vector()
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(24 + 4 * field, bad)
                assertFailsWith<ProtocolException>("$bad in field $field") { decode(bytes) }
            }
        }
        // A flag that is neither 0 nor 1.
        val flag = vector()
        ByteBuffer.wrap(flag).order(ByteOrder.LITTLE_ENDIAN).putLong(96, 2L)
        assertFailsWith<ProtocolException>("a flag of 2") { decode(flag) }
        // A record whose length is not the one the tag fixes.
        val shortRecord = vector()
        ByteBuffer.wrap(shortRecord).order(ByteOrder.LITTLE_ENDIAN).putShort(14, 44)
        assertFailsWith<ProtocolException>("a length of 44") { decode(shortRecord) }
    }

    private fun decode(bytes: ByteArray): List<Mutation> {
        val mutations = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), mutations::add)
        return mutations
    }

    private fun vector(): ByteArray {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "compose-rust/tests/vectors/transform3d.bin")
            if (candidate.isFile) return candidate.readBytes()
            directory = directory.parentFile
        }
        error("protocol vector transform3d.bin not found above ${System.getProperty("user.dir")}")
    }
}
