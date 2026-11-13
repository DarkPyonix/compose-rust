package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.FileDelivery
import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.protocol.FileDialogStatus
import dev.darkpyonix.composerust.protocol.FileOp
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.platform.FileCallCenter
import dev.darkpyonix.composerust.ui.platform.FileCallOutcome
import dev.darkpyonix.composerust.ui.platform.FileCallPlatform
import dev.darkpyonix.composerust.ui.platform.FileCalls
import dev.darkpyonix.composerust.ui.platform.FileDialogOutcome
import dev.darkpyonix.composerust.ui.platform.FileDialogPlatform
import dev.darkpyonix.composerust.ui.platform.FileDialogSpec
import dev.darkpyonix.composerust.ui.platform.FileDialogs
import dev.darkpyonix.composerust.ui.platform.HeldFile
import dev.darkpyonix.composerust.ui.platform.UnsupportedFileCalls
import dev.darkpyonix.composerust.ui.platform.UnsupportedFileDialogs
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val immediately: (suspend () -> Unit) -> Unit = { operation ->
    operation.startCoroutine(kotlin.coroutines.Continuation(kotlin.coroutines.EmptyCoroutineContext) { })
}

/**
 * A browser's files, without a browser: a table of handles numbered the way the web platform
 * numbers them, with what each holds. What the browser's own pickers and streams do is
 * checked in the web module's tests; this checks the rules every platform shares.
 */
private class FakeWebFiles(private val dialogAnswer: () -> FileDialogOutcome) : FileDialogPlatform, FileCallPlatform {
    class Held(val name: String, val folder: Boolean, var bytes: ByteArray = ByteArray(0), val children: MutableList<Int> = ArrayList())

    val held = HashMap<Int, Held>()
    val released = ArrayList<Int>()
    private var next = 1

    fun hold(name: String, folder: Boolean = false, bytes: ByteArray = ByteArray(0)): Int {
        val handle = next++
        held[handle] = Held(name, folder, bytes)
        return handle
    }

    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) =
        onResult(dialogAnswer())

    override fun discardHandles(firstHandle: Int, count: Int) {
        for (index in 0 until count) release(firstHandle + index)
    }

    override suspend fun read(handle: Int, limit: Long): FileCallOutcome {
        val file = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        if (file.bytes.size > limit) return FileCallOutcome.Failed(IoStatus.TooLarge)
        return FileCallOutcome.Bytes(file.bytes)
    }

    override suspend fun write(handle: Int, data: ByteArray): FileCallOutcome {
        val file = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        file.bytes = data
        return FileCallOutcome.Written
    }

    override suspend fun entries(handle: Int): FileCallOutcome {
        val folder = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        // Each listing hands out new handles, as a browser's does.
        val first = next
        val files = folder.children.map { child ->
            val original = held.getValue(child)
            hold(original.name, original.folder, original.bytes)
            HeldFile(original.name, original.folder)
        }
        return FileCallOutcome.Files(FileDelivery.Handle, first, files)
    }

    override suspend fun child(handle: Int, name: String, create: Boolean): FileCallOutcome {
        held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        val first = hold(name)
        return FileCallOutcome.Files(FileDelivery.Handle, first, listOf(HeldFile(name, false)))
    }

    override fun release(handle: Int) {
        if (held.remove(handle) != null) released += handle
    }
}

/**
 * The interpreter's half of `PickedFile` on the web: every request gets exactly one answer,
 * a handle that is gone is `NotFound`, and an answer the event buffer cannot hold fails
 * whole instead of arriving in part.
 */
class WebFileCallTest {
    @AfterTest
    fun restore() {
        FileDialogs.platform = UnsupportedFileDialogs
        FileDialogs.maxEventBytes = Int.MAX_VALUE
        FileCalls.platform = UnsupportedFileCalls
    }

    private fun hostWith(files: FakeWebFiles, maxEventBytes: Int = Int.MAX_VALUE): Pair<ComposeRustHost, FakeHostConnection> {
        FileDialogs.platform = files
        FileCalls.platform = files
        FileDialogs.maxEventBytes = maxEventBytes
        val connection = FakeHostConnection()
        val host = ComposeRustHost(connection)
        host.start()
        host.table.fileCalls.launcher = immediately
        return host to connection
    }

    private fun ComposeRustHost.frame() = renderFrame(0)

    private fun dialog(request: Int, kind: FileDialogKind = FileDialogKind.OpenFiles) = Mutation.ShowFileDialog(
        request = request,
        window = 0,
        kind = kind,
        defaultFilter = 0,
        title = "",
        directory = "",
        name = "",
        filters = "",
    )

    private fun call(request: Int, op: FileOp, handle: Int, name: String = "", create: Boolean = false, data: ByteArray = ByteArray(0)) =
        Mutation.FileCall(request, op, create, handle, name, data)

    /** The answers the Host was sent, by request number, in the order they went. */
    private fun FakeHostConnection.answers(): List<Pair<Int, String>> = events.mapNotNull { event ->
        when (event) {
            is HostEvent.FileHandles -> event.request to "handles ${event.names.replace('\u0000', '|')}"
            is HostEvent.FileDialogResult -> event.request to "result ${event.status}"
            is HostEvent.IoAnswer -> event.request to "io ${event.status} ${event.data.size}"
            else -> null
        }
    }

    @Test
    fun fr72_web_every_request_gets_exactly_one_answer() {
        val outcomes = ArrayDeque<FileDialogOutcome>()
        val files = FakeWebFiles { outcomes.removeFirst() }
        val (host, connection) = hostWith(files)
        val first = files.hold("한글 문서.txt")
        val second = files.hold("사진", folder = true)
        outcomes += FileDialogOutcome.Handles(FileDelivery.Handle, first, listOf(HeldFile("한글 문서.txt", false), HeldFile("사진", true)))
        outcomes += FileDialogOutcome.Cancelled
        outcomes += FileDialogOutcome.Unavailable
        // A choice of nothing is a cancel, never an empty choice.
        outcomes += FileDialogOutcome.Handles(FileDelivery.Upload, 99, emptyList())

        for (request in 1..4) host.table.apply(dialog(request))
        host.frame()
        host.frame()

        assertEquals(
            listOf(
                1 to "handles 한글 문서.txt|사진",
                2 to "result Cancelled",
                3 to "result Unavailable",
                4 to "result Cancelled",
            ),
            connection.answers(),
        )
        val chosen = connection.events.filterIsInstance<HostEvent.FileHandles>().single()
        assertEquals(FileDialogStatus.Chosen, chosen.status)
        assertEquals(FileDelivery.Handle, chosen.delivery)
        assertEquals(first, chosen.firstHandle)
        assertEquals("fd", chosen.kinds)
        assertEquals(second, chosen.firstHandle + 1)

        // Operations: one answer each, whatever they found.
        files.held.getValue(first).bytes = "안녕".encodeToByteArray()
        host.table.apply(call(0x8000_0001.toInt(), FileOp.Read, first))
        host.table.apply(call(0x8000_0002.toInt(), FileOp.Write, first, data = byteArrayOf(1, 2, 3)))
        host.table.apply(call(0x8000_0003.toInt(), FileOp.Child, second, name = "새 파일.txt", create = true))
        host.table.apply(call(0x8000_0004.toInt(), FileOp.Read, 12345))
        host.frame()
        assertEquals(
            listOf(
                0x8000_0001.toInt() to "io Ok 6",
                0x8000_0002.toInt() to "io Ok 0",
                0x8000_0003.toInt() to "handles 새 파일.txt",
                0x8000_0004.toInt() to "io NotFound 0",
            ),
            connection.answers().drop(4),
        )
        assertEquals(listOf<Byte>(1, 2, 3), files.held.getValue(first).bytes.toList())
        assertEquals(0, host.table.fileCalls.pendingCount)
    }

    @Test
    fun fr72_web_a_duplicate_pending_file_call_is_a_protocol_error() {
        val files = FakeWebFiles { FileDialogOutcome.Cancelled }
        val (host, connection) = hostWith(files)
        val handle = files.hold("a.txt", bytes = byteArrayOf(7))
        // Not started yet: the frame starts it, so the second one finds the first waiting.
        host.table.apply(call(0x8000_0010.toInt(), FileOp.Read, handle))
        host.table.apply(call(0x8000_0010.toInt(), FileOp.Write, handle, data = byteArrayOf(9)))
        host.frame()
        assertEquals(
            listOf(TableError.DUPLICATE_FILE_CALL),
            connection.events.filterIsInstance<HostEvent.ProtocolError>().map { it.code },
        )
        assertEquals(listOf(0x8000_0010.toInt() to "io Ok 1"), connection.answers())
        assertEquals(listOf<Byte>(7), files.held.getValue(handle).bytes.toList(), "the dropped write never ran")
    }

    @Test
    fun fr72_web_an_unknown_handle_is_not_found() {
        val files = FakeWebFiles { FileDialogOutcome.Cancelled }
        val (host, connection) = hostWith(files)
        val handle = files.hold("a.txt", bytes = byteArrayOf(1))
        host.table.apply(Mutation.ReleaseFileHandle(handle))
        // A release for a handle nobody holds is ignored, not an error.
        host.table.apply(Mutation.ReleaseFileHandle(handle))
        host.table.apply(Mutation.ReleaseFileHandle(4242))
        for ((index, op) in FileOp.entries.withIndex()) {
            host.table.apply(call(0x8000_0020.toInt() + index, op, handle, name = "x"))
        }
        host.table.apply(call(0x8000_0030.toInt(), FileOp.Read, 4242))
        host.frame()
        assertEquals(listOf(handle), files.released)
        assertTrue(connection.events.none { it is HostEvent.ProtocolError }, "a handle that is gone is no protocol error")
        assertEquals(5, connection.answers().size)
        assertTrue(connection.answers().all { it.second == "io NotFound 0" }, "${connection.answers()}")
    }

    /**
     * Until an event can be longer than the web's 4096 byte buffer, an answer that would be is
     * not cut short: a choice is unavailable and a read is too large, one answer each, and the
     * handles of a choice that was not delivered are let go of.
     */
    @Test
    fun fr72_web_an_answer_over_the_event_buffer_fails_whole() {
        val names = (1..500).map { HeldFile("파일 $it.txt", false) }
        val files = FakeWebFiles { FileDialogOutcome.Handles(FileDelivery.Handle, 1000, names) }
        val (host, connection) = hostWith(files, maxEventBytes = 4096)
        for (index in names.indices) files.held[1000 + index] = FakeWebFiles.Held(names[index].name, false)
        val big = files.hold("big.bin", bytes = ByteArray(1024 * 1024))

        host.table.apply(dialog(1))
        host.table.apply(call(0x8000_0040.toInt(), FileOp.Read, big))
        host.frame()

        assertEquals(listOf(1 to "result Unavailable", 0x8000_0040.toInt() to "io TooLarge 0"), connection.answers())
        assertEquals((1000 until 1500).toList(), files.released, "an answer that was not delivered keeps no handles")

        // A choice and a read that fit go through whole at the same limit.
        val small = files.hold("small.txt", bytes = ByteArray(4096 - 32))
        host.table.apply(call(0x8000_0041.toInt(), FileOp.Read, small))
        host.frame()
        assertEquals(0x8000_0041.toInt() to "io Ok 4064", connection.answers().last())
    }

    /** Waits for events over the event buffer to reach the Host (#583). */
    @Ignore
    @Test
    fun fr72_web_a_long_name_list_arrives_whole() {
        val names = (1..500).map { HeldFile("파일 $it.txt", false) }
        val files = FakeWebFiles { FileDialogOutcome.Handles(FileDelivery.Handle, 1000, names) }
        val (host, connection) = hostWith(files, maxEventBytes = 4096)
        host.table.apply(dialog(1))
        host.frame()
        val chosen = connection.events.filterIsInstance<HostEvent.FileHandles>().single()
        assertEquals(names.map { it.name }, chosen.names.split('\u0000'))
    }

    /** Waits for events over the event buffer to reach the Host (#583). */
    @Ignore
    @Test
    fun fr72_web_reads_a_file_larger_than_the_event_buffer() {
        val files = FakeWebFiles { FileDialogOutcome.Cancelled }
        val (host, connection) = hostWith(files, maxEventBytes = 4096)
        val big = files.hold("big.bin", bytes = ByteArray(1024 * 1024) { it.toByte() })
        host.table.apply(call(0x8000_0050.toInt(), FileOp.Read, big))
        host.frame()
        val answer = connection.events.filterIsInstance<HostEvent.IoAnswer>().single()
        assertEquals(IoStatus.Ok, answer.status)
        assertEquals(1024 * 1024, answer.data.size)
    }

    /** Where nothing holds handles, an operation is still answered, as unavailable. */
    @Test
    fun fr72_unsupported_platform_answers_file_calls_unavailable() {
        val connection = FakeHostConnection()
        val host = ComposeRustHost(connection)
        host.start()
        host.table.fileCalls.launcher = immediately
        host.table.apply(call(0x8000_0060.toInt(), FileOp.Read, 1))
        host.frame()
        // Without a launcher nothing can run, and the answer is the same.
        host.table.fileCalls.launcher = null
        host.table.apply(call(0x8000_0061.toInt(), FileOp.Entries, 1))
        host.frame()
        assertEquals(
            listOf(0x8000_0060.toInt() to "io Unavailable 0", 0x8000_0061.toInt() to "io Unavailable 0"),
            connection.answers(),
        )
        assertTrue(connection.events.none { it is HostEvent.ProtocolError })
    }

    @Test
    fun fr72_a_held_file_name_that_cannot_travel_is_refused() {
        assertEquals(null, FileCallCenter.handlesEvent(1, FileDelivery.Handle, 1, listOf(HeldFile("a\u0000b", false)), -1))
        assertEquals(null, FileCallCenter.handlesEvent(1, FileDelivery.Handle, 1, listOf(HeldFile("", false)), -1))
        val event = FileCallCenter.handlesEvent(1, FileDelivery.Upload, 5, listOf(HeldFile("한", true)), 2)!!
        assertEquals(52 + 3 + 1, FileCallCenter.encodedSize(event))
    }
}
