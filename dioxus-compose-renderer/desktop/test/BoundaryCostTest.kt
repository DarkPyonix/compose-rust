package dioxus.compose.test

import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue

/**
 * What the Renderer spends to read one changed string out of the Host's arena.
 *
 * The arena is the Host's memory, read where it lies, so the only thing this side should
 * have to build is the string Compose is given. These measurements say how close that is
 * to true, and they are the only cheap way to ask: allocation is not visible in the
 * interpreter's output, so without a number here a copy can be added and nothing notices.
 *
 * Measured on the JVM development shell, which is the same bytecode the native image is
 * compiled from. Figures are bytes allocated by this thread, averaged over a run long
 * enough that the measurement's own cost disappears.
 */
class BoundaryCostTest {
    private val threads =
        ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private var sink: Mutation? = null
    private val collect: (Mutation) -> Unit = { sink = it }

    /** Kept in a field so building a string cannot be optimised away while it is measured. */
    private var stringSink: String? = null

    /**
     * One `SetProp(node, Text, text)` record, with `trailing` unused bytes after the
     * string so the same record can be read out of arenas of different sizes.
     */
    private fun textChange(text: String, trailing: Int = 0): ByteBuffer {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        val records = ENVELOPE_BYTES + SET_PROP_BYTES
        val bytes = ByteArray(records + utf8.size + trailing)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0)
        out.putShort(ENVELOPE_BYTES.toShort())
        out.putInt(records)
        out.putInt(1)
        out.putShort(2)
        out.putShort(SET_PROP_BYTES.toShort())
        out.putInt(7)
        out.putShort(1)
        out.putShort(1)
        out.putInt(records)
        out.putInt(utf8.size)
        out.putInt(0)
        out.put(utf8)
        return ByteBuffer.wrap(bytes)
    }

    /**
     * The same record shape carrying a boolean instead of a string, so the cost of the
     * record itself can be told apart from the cost of the text in it.
     */
    private fun boolChange(): ByteBuffer {
        val records = ENVELOPE_BYTES + SET_PROP_BYTES
        val out = ByteBuffer.allocate(records).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0)
        out.putShort(ENVELOPE_BYTES.toShort())
        out.putInt(records)
        out.putInt(1)
        out.putShort(2)
        out.putShort(SET_PROP_BYTES.toShort())
        out.putInt(7)
        out.putShort(1)
        out.putShort(2)
        out.putLong(1)
        out.putInt(0)
        out.rewind()
        return out
    }

    /** Bytes this thread allocates for one decode of `batch`, once everything is warm. */
    private fun bytesPerDecode(batch: ByteBuffer): Long {
        repeat(WARMUP) { Protocol.decode(batch, collect) }
        val before = threads.currentThreadAllocatedBytes
        repeat(MEASURED) { Protocol.decode(batch, collect) }
        val after = threads.currentThreadAllocatedBytes
        return (after - before) / MEASURED
    }

    /**
     * The arena is read in place. A decoder that copied the buffer, or scanned past the
     * records it was given, would cost more for the same record in a larger arena.
     */
    @Test
    fun pr4_reading_a_record_does_not_cost_what_the_arena_around_it_costs() {
        val short = bytesPerDecode(textChange("a keystroke's worth", trailing = 0))
        val long = bytesPerDecode(textChange("a keystroke's worth", trailing = 64 * 1024))
        println("pr4 one text change: ${short}B in a small arena, ${long}B in a 64KB arena")
        assertEquals(
            PropertyKind.Text,
            (sink as Mutation.SetProp).property,
            "the measurement decoded something other than the text change",
        )
        assertTrue(
            long <= short + 16,
            "the same record cost ${long}B out of a 64KB arena and ${short}B out of a small " +
                "one. The arena is the Host's memory and is meant to be read where it lies; " +
                "a cost that follows its size means something copied it.",
        )
    }

    /**
     * How much of a text change's cost is the text. One copy of the text means the cost
     * grows by about one byte per character; each further copy adds the same again, and a
     * copy that goes through `char` adds two.
     */
    @Test
    fun pr4_a_text_change_costs_one_pass_over_the_text() {
        val text = "x".repeat(SHORT_TEXT)
        val longer = "x".repeat(SHORT_TEXT + EXTRA_TEXT)
        val small = bytesPerDecode(textChange(text))
        val large = bytesPerDecode(textChange(longer))
        val perCharacter = (large - small).toDouble() / EXTRA_TEXT
        println(
            "pr4 text change: ${small}B for $SHORT_TEXT characters, ${large}B for " +
                "${SHORT_TEXT + EXTRA_TEXT}, ${"%.2f".format(perCharacter)}B per character",
        )
        assertEquals(
            PropertyValue.Text(longer),
            (sink as Mutation.SetProp).value,
            "the measurement decoded something other than the text change",
        )
        assertTrue(
            perCharacter <= BYTES_PER_CHARACTER_CEILING,
            "one ASCII character of changed text costs " +
                "${"%.2f".format(perCharacter)} bytes on this side, and the ceiling is " +
                "$BYTES_PER_CHARACTER_CEILING. One byte is the string Compose is handed; " +
                "anything above that was copied on the way to it.",
        )
    }

    /**
     * What one `String` of this text costs, measured rather than worked out from the
     * runtime's object layout, so the comparison below stays right on a runtime that lays
     * objects out differently.
     */
    private fun bytesPerString(text: String): Long {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        repeat(WARMUP) { stringSink = String(utf8, 0, utf8.size, StandardCharsets.UTF_8) }
        val before = threads.currentThreadAllocatedBytes
        repeat(MEASURED) { stringSink = String(utf8, 0, utf8.size, StandardCharsets.UTF_8) }
        val after = threads.currentThreadAllocatedBytes
        return (after - before) / MEASURED
    }

    /**
     * The acceptance criterion itself: a text change allocates the string Compose is handed
     * and nothing else made out of the text.
     *
     * Two records of the same shape are decoded, one carrying the text and one carrying a
     * boolean, so what is left over is the record the interpreter is handed rather than
     * anything to do with the string. The difference between them is compared against a
     * `String` of the same text built directly, which is the allowance.
     */
    @Test
    fun pr4_a_text_change_allocates_the_string_and_nothing_else_made_of_it() {
        val text = "a keystroke's worth"
        val withText = bytesPerDecode(textChange(text))
        val withoutText = bytesPerDecode(boolChange())
        val string = bytesPerString(text)
        println(
            "pr4 text change: ${withText}B, the same record carrying a boolean instead " +
                "${withoutText}B, one String of the text ${string}B",
        )
        assertTrue(
            withText - withoutText <= string + ROUNDING,
            "the text in a ${text.length} character change costs " +
                "${withText - withoutText}B beyond the record that carries it, and one " +
                "String of it costs ${string}B. The arena is read where it lies, so the " +
                "string is the only thing this side has to build out of the text.",
        )
    }

    private companion object {
        const val ENVELOPE_BYTES = 12
        const val SET_PROP_BYTES = 24
        const val WARMUP = 20_000
        const val MEASURED = 20_000
        const val SHORT_TEXT = 16
        const val EXTRA_TEXT = 4_000

        /** One object's worth of slack, for the size the runtime rounds an array up to. */
        const val ROUNDING = 8

        /**
         * Bytes per character of changed text.
         *
         * One byte is the string itself, and one byte is what it costs: the decoder checks
         * the arena is UTF-8 where it lies, which allocates nothing, then copies the bytes
         * once through a buffer it keeps and builds the `String` from them. The margin is
         * for the array size the runtime rounds up to, and nothing else. A second copy of
         * the text would add another byte a character and fail here, which is the whole
         * job of this number: allocation is invisible in the interpreter's output, so
         * without it a copy can be added and nothing notices.
         */
        const val BYTES_PER_CHARACTER_CEILING = 1.05
    }
}
