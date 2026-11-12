package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.BatchBytes
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.StorageOp
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.platform.NoStorage
import dev.darkpyonix.composerust.ui.platform.Storage
import dev.darkpyonix.composerust.ui.platform.StorageAnswer
import dev.darkpyonix.composerust.ui.platform.StorageCenter
import dev.darkpyonix.composerust.ui.platform.StoragePlatform
import dev.darkpyonix.composerust.ui.platform.StorageRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A storage that answers only when the test says so, as a browser does later. */
private class HeldStorage : StoragePlatform {
    val started = mutableListOf<Pair<StorageRequest, ByteArray>>()
    val answers = mutableMapOf<Int, (StorageAnswer) -> Unit>()

    override fun start(request: StorageRequest, answer: (StorageAnswer) -> Unit) {
        // The value is only readable now, while the record is applied, so it is copied here.
        started += request to request.data.copy()
        answers[request.request] = answer
    }
}

private class ThrowingStorage : StoragePlatform {
    override fun start(request: StorageRequest, answer: (StorageAnswer) -> Unit) =
        throw IllegalStateException("the value was ${request.data.copy().decodeToString()}")
}

private fun call(request: Int, op: StorageOp = StorageOp.Read, key: String = "draft", data: String = "") =
    Mutation.StorageCall(request, op, "dev.darkpyonix.composerust.samples.todo", key, BatchBytes.of(data.encodeToByteArray()))

/**
 * The interpreter's half of a storage request: each one is started while its value can be
 * read, answered once whatever the platform does, and delivered to the Host at the top of a
 * frame. A number still waiting is refused and reported, and leaves the first alone.
 */
class StorageTest {
    private class Collected : (HostEvent) -> Boolean {
        val events = mutableListOf<HostEvent.IoAnswer>()

        override fun invoke(event: HostEvent): Boolean {
            events += event as HostEvent.IoAnswer
            return false
        }
    }

    private fun center(platform: StoragePlatform, limit: Int = Int.MAX_VALUE): Pair<StorageCenter, Collected> {
        val center = StorageCenter(platform = { platform }, maxAnswerBytes = { limit })
        val collected = Collected()
        center.attach { event -> collected(event) }
        return center to collected
    }

    @Test
    fun fr79_every_storage_request_gets_exactly_one_answer() {
        val storage = HeldStorage()
        val (center, collected) = center(storage)
        var woken = 0
        center.wake = { woken += 1 }

        assertEquals(StorageCenter.Accepted.Accepted, center.request(call(1, StorageOp.Write, data = "할 일\u0000")))
        assertEquals("할 일\u0000", storage.started.single().second.decodeToString(), "the value reached the platform")
        assertEquals(StorageOp.Write, storage.started.single().first.op)

        // The same number again, while the first waits: refused, and not started twice.
        assertEquals(StorageCenter.Accepted.Duplicate, center.request(call(1)))
        assertEquals(1, storage.started.size)

        // Nothing goes to the Host before the platform answers, and then exactly once.
        center.drain()
        assertTrue(collected.events.isEmpty())
        storage.answers.getValue(1)(StorageAnswer(IoStatus.Ok))
        storage.answers.getValue(1)(StorageAnswer(IoStatus.Failed))
        assertEquals(1, woken, "the answer asked for the frame that delivers it")
        center.drain()
        center.drain()
        assertEquals(listOf(1), collected.events.map { it.request })
        assertEquals(IoStatus.Ok, collected.events.single().status)
        assertEquals(0, center.pendingCount)

        // Once answered, the number can be used again.
        assertEquals(StorageCenter.Accepted.Accepted, center.request(call(1, StorageOp.Keys, key = "")))
        storage.answers.getValue(1)(StorageAnswer(IoStatus.Ok, "a\u0000b".encodeToByteArray()))
        center.drain()
        assertEquals("a\u0000b", collected.events.last().data.decodeToString())
    }

    @Test
    fun fr79_a_request_number_already_waiting_is_a_protocol_error() {
        val previous = Storage.platform
        val storage = HeldStorage()
        Storage.platform = storage
        try {
            val table = NodeTable()
            table.apply(call(7))
            table.apply(call(7))
            val errors = table.drainErrors()
            assertEquals(listOf(TableError.DUPLICATE_IO_REQUEST), errors.map { it.code })
            assertEquals(1, storage.started.size, "the first request is left alone")
            assertEquals(1, table.storage.pendingCount)
        } finally {
            Storage.platform = previous
        }
    }

    /** A Renderer whose platform keeps its storage in the Host's files still answers. */
    @Test
    fun fr79_a_renderer_without_storage_answers_unavailable() {
        val (center, collected) = center(NoStorage)
        center.request(call(3, StorageOp.Write, data = "x"))
        center.drain()
        val answer = collected.events.single()
        assertEquals(3, answer.request)
        assertEquals(IoStatus.Unavailable, answer.status)
        assertTrue(answer.data.decodeToString().isNotEmpty(), "it says why")
    }

    @Test
    fun fr79_web_an_answer_over_the_event_buffer_is_too_large() {
        val storage = HeldStorage()
        val (center, collected) = center(storage, limit = 4096 - 32)
        center.request(call(4))
        storage.answers.getValue(4)(StorageAnswer(IoStatus.Ok, ByteArray(1024 * 1024)))
        center.drain()
        val answer = collected.events.single()
        assertEquals(IoStatus.TooLarge, answer.status)
        assertEquals(4, answer.data.size, "only the length travels")
        val length = (answer.data[0].toInt() and 0xff) or
            ((answer.data[1].toInt() and 0xff) shl 8) or
            ((answer.data[2].toInt() and 0xff) shl 16) or
            ((answer.data[3].toInt() and 0xff) shl 24)
        assertEquals(1024 * 1024, length)
    }

    /** A platform that throws is an answer too, and what it said about the value is not logged. */
    @Test
    fun fr79_a_platform_that_throws_still_answers_once() {
        val (center, collected) = center(ThrowingStorage())
        val logged = mutableListOf<String>()
        center.log = { line -> logged += line }
        center.request(call(5, StorageOp.Write, data = "secret"))
        center.drain()
        assertEquals(IoStatus.Failed, collected.events.single().status)
        assertTrue(logged.none { "secret" in it }, "the value never reaches a log: $logged")
    }
}
