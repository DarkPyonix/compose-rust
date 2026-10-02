@file:Suppress("PackageDirectoryMismatch")

package java.nio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.get
import kotlinx.cinterop.set

/**
 * The slice of `java.nio` that the generated protocol codec uses.
 *
 * `Protocol.gen.kt` is generated from the Rust schema by `cargo run -p dioxus-compose --bin
 * codegen` and is written against `java.nio.ByteBuffer`. Kotlin/Native has no JDK, so this
 * file supplies the same names and the same semantics for the operations the codec performs.
 * Compiling the generated file unchanged is the point: the iOS renderer must decode exactly
 * what the desktop renderer decodes, and a second hand-written decoder would drift.
 *
 * This is a stopgap. The proper fix is for codegen to emit a buffer-neutral codec, which is a
 * change to `dioxus-compose/src/codegen.rs` on the Rust side.
 *
 * Two backings, both read in place, because the batch is never copied:
 * - a Kotlin `ByteArray`, for the event buffer the renderer fills and hands to the Host;
 * - a raw `CPointer`, for the Host's arena, which this side only reads.
 *
 * Both byte orders are honoured even though the protocol is little-endian: the codec
 * reads the buffer's previous order, sets its own, and restores the old one when it is done,
 * and a JDK buffer starts out big-endian.
 */
class ByteOrder private constructor(private val label: String) {
    override fun toString(): String = label

    companion object {
        val LITTLE_ENDIAN = ByteOrder("LITTLE_ENDIAN")
        val BIG_ENDIAN = ByteOrder("BIG_ENDIAN")
    }
}

class ByteBuffer private constructor(
    private val array: ByteArray?,
    private val pointer: CPointer<ByteVar>?,
    private val offset: Int,
    private val capacity: Int,
) {
    private var position: Int = 0
    private var limit: Int = capacity
    private var order: ByteOrder = ByteOrder.BIG_ENDIAN

    companion object {
        fun wrap(array: ByteArray): ByteBuffer = ByteBuffer(array, null, 0, array.size)

        /** Wraps `length` bytes of foreign memory. The caller keeps ownership. */
        fun wrapPointer(pointer: CPointer<ByteVar>, length: Int): ByteBuffer =
            ByteBuffer(null, pointer, 0, length)
    }

    fun order(): ByteOrder = order

    fun order(value: ByteOrder): ByteBuffer {
        order = value
        return this
    }

    fun position(): Int = position

    fun position(value: Int): ByteBuffer {
        require(value in 0..limit) { "position $value out of bounds [0, $limit]" }
        position = value
        return this
    }

    fun limit(): Int = limit

    fun limit(value: Int): ByteBuffer {
        require(value in 0..capacity) { "limit $value out of bounds [0, $capacity]" }
        limit = value
        if (position > limit) position = limit
        return this
    }

    fun remaining(): Int = limit - position

    fun clear(): ByteBuffer {
        position = 0
        limit = capacity
        return this
    }

    private fun byteAt(index: Int): Byte {
        require(index in 0 until capacity) { "index $index out of bounds [0, $capacity)" }
        val absolute = offset + index
        return array?.get(absolute) ?: pointer!![absolute]
    }

    private fun setByteAt(index: Int, value: Byte) {
        require(index in 0 until capacity) { "index $index out of bounds [0, $capacity)" }
        val absolute = offset + index
        if (array != null) array[absolute] = value else pointer!![absolute] = value
    }

    fun get(index: Int): Byte = byteAt(index)

    /**
     * Bulk read from an absolute index, leaving the position alone. Every copy the codec
     * makes out of the arena goes through this: the buffer a string's `String` is built
     * from, and an asset's bytes. Absolute because the position belongs to the record
     * walk, while the payload sits wherever in the arena its offset points.
     */
    fun get(index: Int, destination: ByteArray, destinationOffset: Int, length: Int): ByteBuffer {
        require(index >= 0 && length >= 0 && index + length <= capacity) {
            "reading $length bytes at $index passes the end of a $capacity byte buffer"
        }
        require(destinationOffset >= 0 && destinationOffset + length <= destination.size) {
            "$length bytes at $destinationOffset do not fit a ${destination.size} byte array"
        }
        for (step in 0 until length) {
            destination[destinationOffset + step] = byteAt(index + step)
        }
        return this
    }

    /**
     * Bulk read from the position, which is the form every runtime has.
     *
     * The absolute one above says the same thing in one call and is the better fit for a
     * codec whose position belongs to the record walk, but it arrived in Java 13 and is
     * not on every Android runtime the generated code has to compile against. So the
     * generated code uses this one and moves the position itself.
     */
    fun get(destination: ByteArray, destinationOffset: Int, length: Int): ByteBuffer {
        get(position, destination, destinationOffset, length)
        position += length
        return this
    }

    private fun readBits(index: Int, width: Int): Long {
        var value = 0L
        for (step in 0 until width) {
            val byte = if (order === ByteOrder.LITTLE_ENDIAN) width - 1 - step else step
            value = (value shl 8) or (byteAt(index + byte).toLong() and 0xff)
        }
        return value
    }

    private fun writeBits(value: Long, width: Int) {
        requireSpace(width)
        for (step in 0 until width) {
            val byte = if (order === ByteOrder.LITTLE_ENDIAN) step else width - 1 - step
            setByteAt(position + byte, ((value ushr (8 * step)) and 0xff).toByte())
        }
        position += width
    }

    fun getShort(index: Int): Short = readBits(index, 2).toShort()

    fun getInt(index: Int): Int = readBits(index, 4).toInt()

    fun getLong(index: Int): Long = readBits(index, 8)

    fun put(value: Byte): ByteBuffer {
        requireSpace(1)
        setByteAt(position, value)
        position += 1
        return this
    }

    fun put(values: ByteArray): ByteBuffer {
        requireSpace(values.size)
        for (index in values.indices) {
            setByteAt(position + index, values[index])
        }
        position += values.size
        return this
    }

    fun putShort(value: Short): ByteBuffer {
        writeBits(value.toLong() and 0xffffL, 2)
        return this
    }

    fun putInt(value: Int): ByteBuffer {
        writeBits(value.toLong() and 0xffff_ffffL, 4)
        return this
    }

    fun putFloat(value: Float): ByteBuffer {
        writeBits(value.toRawBits().toLong() and 0xffff_ffffL, 4)
        return this
    }

    fun putLong(value: Long): ByteBuffer {
        writeBits(value, 8)
        return this
    }

    /** Writes the IEEE 754 bit pattern, which is what the protocol carries for a double. */
    fun putDouble(value: Double): ByteBuffer {
        writeBits(value.toRawBits(), 8)
        return this
    }

    private fun requireSpace(bytes: Int) {
        if (remaining() < bytes) {
            throw IndexOutOfBoundsException("buffer overflow: $bytes bytes into ${remaining()}")
        }
    }
}
