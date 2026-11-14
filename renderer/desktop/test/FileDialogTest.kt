package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.protocol.FileDialogStatus
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.platform.FileDialogCenter
import dev.darkpyonix.composerust.ui.platform.FileDialogOutcome
import dev.darkpyonix.composerust.ui.platform.FileDialogPath
import dev.darkpyonix.composerust.ui.platform.FileDialogPlatform
import dev.darkpyonix.composerust.ui.platform.FileDialogSpec
import dev.darkpyonix.composerust.ui.platform.FileDialogs
import dev.darkpyonix.composerust.ui.platform.UnsupportedFileDialogs
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A platform that remembers what it was asked and answers when the test says so. */
private class FakeFileDialogs : FileDialogPlatform {
    class Shown(val window: Any?, val spec: FileDialogSpec, val onResult: (FileDialogOutcome) -> Unit)

    val shown = mutableListOf<Shown>()
    val cancelledWindows = mutableListOf<Any?>()

    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) {
        shown += Shown(window, request, onResult)
    }

    override fun cancelWindow(window: Any?) {
        cancelledWindows += window
    }

    fun forRequest(request: Int): Shown = shown.single { it.spec.request == request }
}

private fun dialog(
    request: Int,
    window: Int = 0,
    kind: FileDialogKind = FileDialogKind.OpenFiles,
    defaultFilter: Int = 0,
    name: String = "",
    filters: String = "",
) = Mutation.ShowFileDialog(
    request = request,
    window = window,
    kind = kind,
    defaultFilter = defaultFilter,
    title = "파일 고르기",
    directory = "/home/한글",
    name = name,
    filters = filters,
)

/**
 * The interpreter's half of file dialogs: what it checks, what it normalises, and the one
 * way an answer reaches the Host. Whether a real platform panel opens is a manual check per
 * platform; everything on this side of it is checked here against a platform that only
 * remembers.
 */
class FileDialogTest {
    @AfterTest
    fun restore() {
        FileDialogs.platform = UnsupportedFileDialogs
    }

    private fun hostWith(platform: FileDialogPlatform): Pair<ComposeRustHost, FakeHostConnection> {
        FileDialogs.platform = platform
        val connection = FakeHostConnection()
        val host = ComposeRustHost(connection)
        host.start()
        return host to connection
    }

    /** Delivers what the platform reported, the way the frame an answer asks for does. */
    private fun ComposeRustHost.frame() = renderFrame(0)

    /**
     * Asks for a dialog on a window of its own, so that requests made together are all shown
     * at once and the test does not depend on the order a window shows them in.
     */
    private fun ComposeRustHost.ask(
        request: Int,
        kind: FileDialogKind = FileDialogKind.OpenFiles,
        defaultFilter: Int = 0,
        name: String = "",
        filters: String = "",
    ) {
        table.apply(Mutation.Create(100 + request, WidgetKind.Window))
        table.apply(dialog(request, 100 + request, kind, defaultFilter, name, filters))
    }

    private fun FakeHostConnection.results() = events.filterIsInstance<HostEvent.FileDialogResult>()

    private fun FakeHostConnection.errorCodes() =
        events.filterIsInstance<HostEvent.ProtocolError>().map { it.code }

    @Test
    fun fr72_unknown_window_reports_and_answers_unavailable() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)

        host.table.apply(dialog(1, window = 42))
        // A node that exists but is not a window is as unknown as one that does not exist.
        host.table.apply(Mutation.Create(7, WidgetKind.Box))
        host.table.apply(dialog(2, window = 7))
        host.frame()

        assertTrue(platform.shown.isEmpty(), "no platform dialog is shown for a window that is not there")
        assertEquals(
            listOf(1 to FileDialogStatus.Unavailable, 2 to FileDialogStatus.Unavailable),
            connection.results().map { it.request to it.status },
        )
        assertTrue(connection.results().all { it.paths.isEmpty() && it.filter == -1 })
        assertEquals(listOf(TableError.UNKNOWN_NODE, TableError.UNKNOWN_NODE), connection.errorCodes())
    }

    @Test
    fun fr72_duplicate_pending_request_is_a_protocol_error() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)

        host.table.apply(dialog(5))
        host.table.apply(dialog(5, kind = FileDialogKind.Save))
        host.frame()

        assertEquals(1, platform.shown.size, "the second command is dropped")
        assertEquals(FileDialogKind.OpenFiles, platform.shown.single().spec.kind)
        assertEquals(listOf(TableError.DUPLICATE_FILE_DIALOG), connection.errorCodes())
        assertTrue(connection.results().isEmpty(), "the first request is still waiting")

        platform.forRequest(5).onResult(FileDialogOutcome.Chosen.ofText(listOf("/a/b.txt")))
        host.frame()
        assertEquals(
            listOf(5 to FileDialogStatus.Chosen),
            connection.results().map { it.request to it.status },
            "the first request keeps its single answer",
        )
        assertEquals(1, connection.errorCodes().size, "and the duplicate was reported once")

        // The number is free again once it was answered.
        host.table.apply(dialog(5))
        host.frame()
        assertEquals(2, platform.shown.size)
        assertEquals(1, connection.errorCodes().size)
    }

    @Test
    fun fr72_filters_over_the_limit_are_trimmed_not_rejected() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)

        val encoded = (0 until 20).joinToString("\u0000") { filter ->
            val extensions = (0 until 40).joinToString(",") { extension ->
                when (extension % 3) {
                    0 -> "*.EXT$extension"
                    1 -> ".Ext$extension"
                    else -> "EXT$extension"
                }
            }
            "F$filter\u0000$extensions"
        }
        host.ask(1, defaultFilter = 99, filters = encoded)
        host.ask(2, defaultFilter = 3, filters = encoded)
        host.ask(3, kind = FileDialogKind.OpenFolder, filters = encoded)
        host.ask(4, kind = FileDialogKind.Save, name = "dir/sub\\report.txt", filters = "Text\u0000TXT")
        host.ask(5, kind = FileDialogKind.OpenFile, name = "ignored.txt")
        host.ask(6, defaultFilter = 2, filters = "Dots\u0000.,*.\u0000All\u0000\u0000Txt\u0000txt")
        host.frame()

        assertTrue(connection.errorCodes().isEmpty(), "trimming is not an error")
        assertEquals(6, platform.shown.size, "every request is shown")
        val first = platform.forRequest(1).spec
        assertEquals(16, first.filters.size)
        assertEquals("F0", first.filters.first().label)
        assertEquals("F15", first.filters.last().label)
        assertTrue(first.filters.all { it.extensions.size == 32 })
        assertEquals(listOf("ext0", "ext1", "ext2"), first.filters.first().extensions.take(3))
        assertEquals("ext31", first.filters.first().extensions.last())
        assertEquals(0, first.defaultFilter, "a default filter out of range is the first")
        val dots = platform.forRequest(6).spec
        assertEquals(listOf("All", "Txt"), dots.filters.map { it.label }, "a filter of only dots is dropped, an empty one is kept")
        assertEquals(1, dots.defaultFilter, "the default follows its filter after an earlier one was dropped")
        assertEquals(3, platform.forRequest(2).spec.defaultFilter)
        assertTrue(platform.forRequest(3).spec.filters.isEmpty(), "a folder dialog has no filters")
        assertEquals("report.txt", platform.forRequest(4).spec.name)
        assertEquals(listOf("txt"), platform.forRequest(4).spec.filters.single().extensions)
        assertEquals("", platform.forRequest(5).spec.name, "an open dialog has no file name")
    }

    @Test
    fun fr72_result_filter_is_the_position_in_the_list_the_host_sent() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        // The filter at position 0 keeps no extension and is dropped, so "All" and "Txt"
        // are positions 1 and 2 in what the Host sent and 0 and 1 in what the platform shows.
        host.ask(1, filters = "Dots\u0000.\u0000All\u0000\u0000Txt\u0000txt")
        host.ask(2, filters = "Dots\u0000.\u0000All\u0000\u0000Txt\u0000txt")
        host.frame()
        platform.forRequest(1).onResult(FileDialogOutcome.Chosen.ofText(listOf("/a.txt"), filter = 1))
        host.frame()
        platform.forRequest(2).onResult(FileDialogOutcome.Chosen.ofText(listOf("/b"), filter = 0))
        host.frame()

        val results = connection.results().associateBy { it.request }
        assertEquals(2, results.getValue(1).filter, "Txt was sent at position 2")
        assertEquals(1, results.getValue(2).filter, "All was sent at position 1")
    }

    @Test
    fun fr72_paths_arrive_whole_utf8_and_a_non_utf8_path_answers_unavailable() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        val long = "/" + "a".repeat(300)
        val korean = "/home/한글/문서 1.txt"

        host.ask(1, filters = "Docs\u0000txt")
        host.ask(2)
        host.ask(3)
        host.ask(4)
        host.frame()
        platform.forRequest(1).onResult(
            FileDialogOutcome.Chosen(
                listOf(
                    FileDialogPath.Text(korean),
                    FileDialogPath.Text(long),
                    FileDialogPath.Bytes("/tmp/\u00e9".encodeToByteArray()),
                ),
                filter = 0,
            ),
        )
        platform.forRequest(2).onResult(
            FileDialogOutcome.Chosen(listOf(FileDialogPath.Bytes(byteArrayOf(0xC3.toByte())))),
        )
        platform.forRequest(3).onResult(FileDialogOutcome.Chosen(emptyList()))
        platform.forRequest(4).onResult(FileDialogOutcome.Cancelled)
        host.frame()

        val results = connection.results().associateBy { it.request }
        assertEquals(FileDialogStatus.Chosen, results.getValue(1).status)
        assertEquals(listOf(korean, long, "/tmp/\u00e9"), results.getValue(1).paths.split('\u0000'))
        assertEquals(0, results.getValue(1).filter)
        // One path is not text, so the whole choice is unavailable and the others are not sent.
        assertEquals(FileDialogStatus.Unavailable, results.getValue(2).status)
        assertEquals("", results.getValue(2).paths)
        // Nothing was chosen at all, which is what a cancel is.
        assertEquals(FileDialogStatus.Cancelled, results.getValue(3).status)
        assertEquals(FileDialogStatus.Cancelled, results.getValue(4).status)
    }

    @Test
    fun fr72_more_than_ten_thousand_paths_answers_unavailable() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        host.ask(1)
        host.ask(2)
        host.frame()

        platform.forRequest(1).onResult(
            FileDialogOutcome.Chosen.ofText((0..10_000).map { "/files/$it" }),
        )
        platform.forRequest(2).onResult(
            FileDialogOutcome.Chosen.ofText((1..10_000).map { "/files/$it" }),
        )
        host.frame()

        val results = connection.results().associateBy { it.request }
        assertEquals(FileDialogStatus.Unavailable, results.getValue(1).status, "10001 paths")
        assertEquals("", results.getValue(1).paths, "never a shorter list under Chosen")
        assertEquals(FileDialogStatus.Chosen, results.getValue(2).status, "10000 paths still fit")
        assertEquals(10_000, results.getValue(2).paths.split('\u0000').size)

        // Too many bytes of paths is unavailable for the same reason.
        val answers = mutableListOf<HostEvent>()
        val fake = FakeFileDialogs()
        val limited = FileDialogCenter(fake, maxPayloadBytes = 64)
        limited.attach(EventDispatcher { event -> answers += event; true })
        limited.request(dialog(9), windowKnown = true)
        limited.drain()
        fake.forRequest(9).onResult(FileDialogOutcome.Chosen.ofText(listOf("/" + "x".repeat(40), "/" + "y".repeat(40))))
        limited.drain()
        val bytes = answers.filterIsInstance<HostEvent.FileDialogResult>().single()
        assertEquals(FileDialogStatus.Unavailable, bytes.status)
        assertEquals("", bytes.paths)
    }

    @Test
    fun fr72_closing_the_owner_window_cancels_the_dialog() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        host.table.fileDialogs.windowResolver = { node -> "window-$node" }
        host.table.apply(Mutation.Create(5, WidgetKind.Window))
        host.table.apply(dialog(1, window = 5))
        host.table.apply(dialog(2, window = 5))
        host.frame()
        assertEquals(listOf("window-5"), platform.shown.map { it.window })
        assertEquals(1, platform.shown.size, "the second request on the window waits for the first")

        host.table.apply(Mutation.Remove(5))
        host.frame()

        assertEquals(listOf<Any?>("window-5"), platform.cancelledWindows, "the open dialog is told to close")
        assertEquals(
            listOf(1 to FileDialogStatus.Cancelled, 2 to FileDialogStatus.Cancelled),
            connection.results().map { it.request to it.status },
            "each request ends with one cancel",
        )
        assertEquals(1, platform.shown.size, "the waiting request was never shown")

        // The platform answering late changes nothing: the request was already answered.
        platform.forRequest(1).onResult(FileDialogOutcome.Chosen.ofText(listOf("/late")))
        host.frame()
        assertEquals(2, connection.results().size)
        assertTrue(connection.errorCodes().isEmpty())
    }

    @Test
    fun fr72_unsupported_platform_answers_unavailable() {
        val (host, connection) = hostWith(UnsupportedFileDialogs)
        host.table.apply(dialog(1))
        host.table.apply(dialog(2, kind = FileDialogKind.Save))
        host.frame()
        host.frame()

        assertEquals(
            listOf(1 to FileDialogStatus.Unavailable, 2 to FileDialogStatus.Unavailable),
            connection.results().map { it.request to it.status },
        )
        assertTrue(connection.errorCodes().isEmpty(), "an unsupported platform is not a protocol error")
    }

    @Test
    fun fr72_every_request_gets_exactly_one_result() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        host.table.apply(dialog(1))
        host.table.apply(Mutation.Create(5, WidgetKind.Window))
        host.table.apply(dialog(2, window = 5))
        host.table.apply(dialog(3, window = 6))
        host.table.apply(dialog(4, window = 5))
        host.frame()

        // Chosen, then answered again and differently: only the first counts.
        platform.forRequest(1).onResult(FileDialogOutcome.Chosen.ofText(listOf("/a")))
        platform.forRequest(1).onResult(FileDialogOutcome.Cancelled)
        // Cancelled, from a thread of the platform's own.
        val worker = thread { platform.forRequest(2).onResult(FileDialogOutcome.Cancelled) }
        worker.join()
        host.frame()
        // Request 4 was waiting behind request 2 and is shown now.
        host.frame()
        platform.forRequest(4).onResult(FileDialogOutcome.Unavailable)
        host.frame()
        host.frame()

        val byRequest = connection.results().groupBy { it.request }
        assertEquals(setOf(1, 2, 3, 4), byRequest.keys)
        assertTrue(byRequest.values.all { it.size == 1 }, "one answer each: ${connection.results()}")
        assertEquals(FileDialogStatus.Chosen, byRequest.getValue(1).single().status)
        assertEquals(FileDialogStatus.Cancelled, byRequest.getValue(2).single().status)
        assertEquals(FileDialogStatus.Unavailable, byRequest.getValue(3).single().status)
        assertEquals(FileDialogStatus.Unavailable, byRequest.getValue(4).single().status)
        assertTrue(connection.results().all { it.nodeId == 0 && it.handlerId == 0L })
    }

    @Test
    fun fr72_requests_on_one_window_are_shown_in_order() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        host.table.apply(Mutation.Create(5, WidgetKind.Window))
        host.table.apply(dialog(1))
        host.table.apply(dialog(2))
        host.table.apply(dialog(3, window = 5))
        host.frame()

        assertEquals(
            listOf(1, 3),
            platform.shown.map { it.spec.request },
            "the second request on the root window waits, the other window does not",
        )

        platform.forRequest(1).onResult(FileDialogOutcome.Cancelled)
        host.frame()
        assertEquals(listOf(1, 3, 2), platform.shown.map { it.spec.request })
        platform.forRequest(2).onResult(FileDialogOutcome.Cancelled)
        platform.forRequest(3).onResult(FileDialogOutcome.Cancelled)
        host.frame()
        assertEquals(listOf(1, 2, 3), connection.results().map { it.request })
    }

    @Test
    fun fr72_ui_loop_keeps_running_while_a_dialog_is_open() {
        val platform = FakeFileDialogs()
        val (host, connection) = hostWith(platform)
        var wakeCalls = 0
        host.table.fileDialogs.wake = { wakeCalls++ }

        host.table.apply(Mutation.Create(1, WidgetKind.Window))
        host.table.apply(dialog(1, window = 1))
        host.frame()

        assertEquals(1, platform.shown.size, "the dialog is shown")
        assertTrue(connection.results().isEmpty(), "the dialog is open and has not answered")

        // While the dialog is open, the host continues to process mutations and render frames.
        host.table.apply(Mutation.Create(2, WidgetKind.Box))
        host.frame()
        assertTrue(connection.results().isEmpty(), "dialog is still open")

        // When answered from a worker thread, wake is triggered and result is delivered on frame.
        val worker = thread {
            platform.forRequest(1).onResult(FileDialogOutcome.Chosen.ofText(listOf("/selected/file.txt")))
        }
        worker.join()
        assertTrue(wakeCalls > 0, "answering a dialog wakes the frame loop")

        host.frame()
        assertEquals(
            listOf(1 to FileDialogStatus.Chosen),
            connection.results().map { it.request to it.status },
        )
    }
}
