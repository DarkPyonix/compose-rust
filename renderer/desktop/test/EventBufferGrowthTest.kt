package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.ProtocolException
import dev.darkpyonix.composerust.ui.platform.GrowingEventBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * The buffer every connection encodes an event into before handing it to the Host.
 *
 * It starts at 4 KiB, which used to be all there was: a paste, a large edit or a long list of
 * picked files did not fit, the encoder refused it, and the event never reached the Host.
 * These go through the same buffer the Android connection lends JNI (a direct buffer) and the
 * one the native connections pin (a byte array), and read back what the Host would read.
 */
class EventBufferGrowthTest {

    private fun direct() = GrowingEventBuffer(
        GrowingEventBuffer.INITIAL_BYTES,
        GrowingEventBuffer.MAX_BYTES,
        allocate = { bytes -> ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN) },
        view = { it },
    )

    private fun pinned() = GrowingEventBuffer(
        GrowingEventBuffer.INITIAL_BYTES,
        GrowingEventBuffer.MAX_BYTES,
        allocate = { bytes -> ByteArray(bytes) },
        view = { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) },
    )

    /** The bytes the Host is handed: the first `length` of the buffer, read from the start. */
    private fun handed(buffer: ByteBuffer, length: Int): ByteArray {
        val bytes = ByteArray(length)
        for (index in 0 until length) bytes[index] = buffer.get(index)
        return bytes
    }

    /**
     * Checks that the event arrived whole: the record in front, then every byte of its text,
     * and nothing cut from the end.
     */
    private fun <T : Any> assertWhole(
        events: GrowingEventBuffer<T>,
        event: HostEvent,
        empty: HostEvent,
        text: String,
    ) {
        val record = pinned().encode(empty)
        val length = events.encode(event)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        assertEquals(record + bytes.size, length, "the event is its record plus all of its text")
        val tail = handed(events.buffer, length).copyOfRange(record, length)
        assertContentEquals(bytes, tail, "the text the Host reads is the text that was sent")
    }

    @Test
    fun fr78_a_64_kib_clipboard_text_is_delivered_whole() {
        val text = "가나다라마바사아".repeat(64 * 1024 / 24) + "x".repeat(64 * 1024 % 24)
        assertEquals(64 * 1024, text.toByteArray(StandardCharsets.UTF_8).size)
        val events = direct()
        assertWhole(
            events,
            HostEvent.ClipboardText(3, 9L, request = 1, status = 0, length = text.length, text = text),
            HostEvent.ClipboardText(3, 9L, request = 1, status = 0, length = 0, text = ""),
            text,
        )
        assertEquals(true, events.backing.isDirect, "Android hands JNI a direct buffer")
    }

    @Test
    fun nfr7_a_32_kib_text_change_is_delivered_whole() {
        val text = "line of a pasted document\n".repeat(32 * 1024 / 26 + 1).take(32 * 1024)
        for (events in listOf(direct(), pinned())) {
            assertWhole(
                events,
                HostEvent.TextChanged(5, 2L, text),
                HostEvent.TextChanged(5, 2L, ""),
                text,
            )
        }
    }

    @Test
    fun nfr7_a_large_code_change_is_delivered_whole_by_the_native_buffer() {
        val text = "fn main() {}\n".repeat(80_000)
        val events = pinned()
        val event = HostEvent.CodeChanged(4, 6L, 7, 0, 0, 0, 0, text)
        val empty = HostEvent.CodeChanged(4, 6L, 7, 0, 0, 0, 0, "")
        assertWhole(events, event, empty, text)
    }

    @Test
    fun nfr9_a_small_event_reuses_the_buffer_and_a_grown_one_keeps_its_size() {
        val events = direct()
        val first = events.backing
        repeat(100) { events.encode(HostEvent.Clicked(1, 1L)) }
        assertSame(first, events.backing, "a small event allocates nothing")
        assertEquals(GrowingEventBuffer.INITIAL_BYTES, events.capacity)

        events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(10_000)))
        val grown = events.backing
        assertEquals(16 * 1024, events.capacity, "the buffer doubles until the event fits")
        repeat(100) { events.encode(HostEvent.Clicked(1, 1L)) }
        assertSame(grown, events.backing, "the grown buffer is kept, not remade per event")
    }

    @Test
    fun nfr7_an_event_past_the_ceiling_is_refused_not_cut_short() {
        val events = GrowingEventBuffer(
            initialBytes = 64,
            maxBytes = 1024,
            allocate = { bytes -> ByteArray(bytes) },
            view = { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) },
        )
        assertFailsWith<ProtocolException> {
            events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(2048)))
        }
        assertEquals(1024, events.capacity, "it grew as far as it may and no further")
        // The buffer still works for the next event.
        val length = events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(900)))
        assertEquals(true, length in 901..1024)
    }
}
