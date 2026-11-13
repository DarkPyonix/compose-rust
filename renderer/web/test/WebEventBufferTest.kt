package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.ProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The web connection's event buffer: the 4 KiB one in the block the Host lent at startup,
 * then larger ones it lends on request.
 *
 * The Host here is a stand-in that hands out addresses in its region and memory of this
 * test's own behind them, so what is checked is what the connection asks for, where it
 * encodes, and what the Host would read at the address it is given. The real Host's half,
 * the export and its refusal past the ceiling, is checked in [WebBoundaryTest].
 */
class WebEventBufferTest {
    /** A Host that lends whatever it is asked for, at addresses in its region. */
    private class LendingHost(private val refuseAbove: Int = Int.MAX_VALUE) {
        val requests = ArrayList<Int>()
        private val memory = HashMap<Int, ByteArray>()
        private var next = RUST_REGION_BASE + 1024 * 1024

        val first = RUST_REGION_BASE + EVENT_BUFFER_OFFSET

        fun lend(bytes: Int): Int {
            requests += bytes
            if (bytes > refuseAbove) return 0
            val address = next
            next += bytes
            memory[address] = ByteArray(bytes)
            return address
        }

        fun view(address: Int, bytes: Int): ByteBuffer {
            val backing = memory.getOrPut(address) { ByteArray(bytes) }
            assertTrue(backing.size >= bytes, "the view at $address reaches past what was lent there")
            return ByteBuffer.wrap(backing).order(ByteOrder.LITTLE_ENDIAN)
        }

        /** The bytes the Host reads at [address]: the first [length] of what it lent there. */
        fun read(address: Int, length: Int): ByteArray = memory.getValue(address).copyOf(length)

        fun buffer(maxBytes: Int = EVENT_BUFFER_MAX_BYTES) = WebHostConnection.lentEventBuffer(
            first = first,
            lend = ::lend,
            view = ::view,
            maxBytes = maxBytes,
        )
    }

    /** How long [event]'s record is in front of its payload, measured by encoding it empty. */
    private fun recordLength(empty: HostEvent): Int = LendingHost().buffer().encode(empty)

    /**
     * Encodes [event] and checks that the Host reads all of it at the address it is handed:
     * the record, then every byte of [payload], with nothing cut from the end.
     */
    private fun assertArrivesWhole(host: LendingHost, event: HostEvent, empty: HostEvent, payload: ByteArray) {
        val events = host.buffer()
        val length = events.encode(event)
        val record = recordLength(empty)
        assertEquals(record + payload.size, length, "the event is its record plus all of its payload")
        val address = events.backing.address
        assertTrue(address >= RUST_REGION_BASE, "the Host is handed an address in its own region")
        val read = host.read(address, length)
        assertContentEquals(payload, read.copyOfRange(record, length), "the Host reads what was sent")
    }

    @Test
    fun pr6_an_event_larger_than_the_lent_buffer_arrives_whole() {
        val clipboard = "가나다라마바사아".repeat(64 * 1024 / 24) + "x".repeat(64 * 1024 % 24)
        assertEquals(64 * 1024, clipboard.encodeToByteArray().size)
        val host = LendingHost()
        assertArrivesWhole(
            host,
            HostEvent.ClipboardText(3, 9L, request = 1, status = 0, length = clipboard.length, text = clipboard),
            HostEvent.ClipboardText(3, 9L, request = 1, status = 0, length = 0, text = ""),
            clipboard.encodeToByteArray(),
        )
        // Doubling from the first buffer until the event fits, and never more than that.
        assertEquals(listOf(8 * 1024, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024), host.requests)

        val edit = "line of a pasted document\n".repeat(32 * 1024 / 26 + 1).take(32 * 1024)
        assertArrivesWhole(
            LendingHost(),
            HostEvent.TextChanged(5, 2L, edit),
            HostEvent.TextChanged(5, 2L, ""),
            edit.encodeToByteArray(),
        )

        val value = ByteArray(1024 * 1024) { (it % 251).toByte() }
        assertArrivesWhole(
            LendingHost(),
            HostEvent.IoAnswer(0, 0L, request = 7, status = IoStatus.Ok, data = value),
            HostEvent.IoAnswer(0, 0L, request = 7, status = IoStatus.Ok, data = ByteArray(0)),
            value,
        )
    }

    @Test
    fun pr6_a_small_event_uses_the_first_buffer_and_a_grown_one_is_kept() {
        val host = LendingHost()
        val events = host.buffer()
        repeat(100) { events.encode(HostEvent.Clicked(1, 1L)) }
        assertEquals(host.first, events.backing.address, "a small event goes in the block lent at startup")
        assertEquals(emptyList(), host.requests, "and asks the Host for nothing")

        events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(10_000)))
        val grown = events.backing
        assertEquals(16 * 1024, events.capacity)
        repeat(100) { events.encode(HostEvent.Clicked(1, 1L)) }
        assertSame(grown, events.backing, "the grown buffer is kept, not asked for per event")
        assertEquals(listOf(8 * 1024, 16 * 1024), host.requests)
    }

    @Test
    fun pr6_an_event_past_the_ceiling_is_refused() {
        // The real ceiling is 64 MiB; a small one shows the same rule without a 64 MiB event.
        assertEquals(64 * 1024 * 1024, EVENT_BUFFER_MAX_BYTES)
        val host = LendingHost()
        val events = host.buffer(maxBytes = 64 * 1024)
        assertFailsWith<ProtocolException> {
            events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(64 * 1024 + 1)))
        }
        assertEquals(64 * 1024, events.capacity, "it grew as far as it may and no further")
        assertTrue(host.requests.all { it <= 64 * 1024 }, "nothing past the ceiling is asked for: ${host.requests}")

        // The buffer still works for the next event.
        val length = events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(60_000)))
        assertTrue(length in 60_001..64 * 1024)
    }

    @Test
    fun pr6_an_event_the_host_will_not_lend_for_is_refused() {
        val host = LendingHost(refuseAbove = 16 * 1024)
        val events = host.buffer()
        events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(10_000)))
        val kept = events.backing
        assertFailsWith<ProtocolException> {
            events.encode(HostEvent.TextChanged(1, 1L, "x".repeat(20_000)))
        }
        assertSame(kept, events.backing, "a refusal leaves the buffer the Host still keeps")
        val length = events.encode(HostEvent.TextChanged(1, 1L, "y".repeat(12_000)))
        assertEquals(kept.address, events.backing.address)
        assertTrue(length > 12_000)
    }
}
