package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.ClipboardReadStatus
import dev.darkpyonix.composerust.ui.platform.ClipboardSink
import dev.darkpyonix.composerust.ui.platform.ClipboardSource
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A clipboard that remembers what it was given, in place of the platform's. */
private class FakeClipboard : ClipboardSink {
    val written = mutableListOf<String>()

    override fun setText(text: String) {
        written += text
    }
}

/** A clipboard to read from that holds [text], or nothing when it is null. */
private class FakeReadableClipboard(var text: String?) : ClipboardSource {
    var reads = 0

    override suspend fun getText(): String? {
        reads += 1
        return text
    }
}

/** A clipboard read that throws, as a browser does when permission is refused. */
private class DeniedClipboard(private val quote: String) : ClipboardSource {
    override suspend fun getText(): String? = throw IllegalStateException("denied: \"$quote\"")
}

/** A clipboard read that answers only when the test says so, as a browser does after asking. */
private class SlowClipboard : ClipboardSource {
    var pending: kotlin.coroutines.Continuation<String?>? = null

    override suspend fun getText(): String? =
        kotlin.coroutines.suspendCoroutine { continuation -> pending = continuation }
}

/** Runs a read at once on the calling thread, suspending where the source does. */
private val immediately: (suspend () -> Unit) -> Unit = { read ->
    read.startCoroutine(kotlin.coroutines.Continuation(kotlin.coroutines.EmptyCoroutineContext) { })
}

/** A clipboard that is there but refuses every write, as a headless platform's may. */
private class RefusingClipboard(private val quote: Boolean) : ClipboardSink {
    override fun setText(text: String) {
        throw IllegalStateException(if (quote) "cannot copy \"$text\"" else "no clipboard")
    }
}

/**
 * The interpreter's half of a clipboard write: the text reaches the clipboard whole, an
 * empty one empties it, a missing clipboard is no error, and the text never reaches a log.
 * Whether the system clipboard holds it afterwards is checked by running the app.
 */
class ClipboardTest {
    private fun host(): Pair<ComposeRustHost, FakeHostConnection> {
        val connection = FakeHostConnection()
        val host = ComposeRustHost(connection)
        host.start()
        host.table.clipboardReads.launcher = immediately
        return host to connection
    }

    private fun FakeHostConnection.errors() = events.filterIsInstance<HostEvent.ProtocolError>()

    @Test
    fun fr73_renderer_writes_the_text_to_the_clipboard() {
        val (host, connection) = host()
        val clipboard = FakeClipboard()
        host.table.clipboard.sink = clipboard

        val text = "초대 링크\nhttps://example.invalid/i/7 😀👩‍👩‍👧"
        host.table.apply(Mutation.SetClipboardText(text))

        assertEquals(listOf(text), clipboard.written, "the text arrives as it was sent")
        assertTrue(connection.errors().isEmpty())
    }

    @Test
    fun fr73_empty_text_clears_the_clipboard() {
        val (host, connection) = host()
        val clipboard = FakeClipboard()
        host.table.clipboard.sink = clipboard

        host.table.apply(Mutation.SetClipboardText("이전"))
        host.table.apply(Mutation.SetClipboardText(""))

        assertEquals(listOf("이전", ""), clipboard.written)
        assertEquals("", clipboard.written.last(), "the clipboard now holds empty text")
        assertTrue(connection.errors().isEmpty())
    }

    @Test
    fun fr73_unavailable_clipboard_is_silently_ignored() {
        val (host, connection) = host()
        val lines = mutableListOf<String>()
        host.table.clipboard.log = { lines += it }

        // No clipboard at all: nothing happens and nothing is said.
        host.table.apply(Mutation.SetClipboardText("초대 링크"))
        // A clipboard that throws: the process goes on, and the Host is not told.
        host.table.clipboard.sink = RefusingClipboard(quote = false)
        host.table.apply(Mutation.SetClipboardText("초대 링크"))

        assertTrue(connection.errors().isEmpty(), "a missing clipboard is not a protocol error")
        // The batch after it still applies.
        val clipboard = FakeClipboard()
        host.table.clipboard.sink = clipboard
        host.table.apply(Mutation.SetClipboardText("다음"))
        assertEquals(listOf("다음"), clipboard.written)
        host.renderFrame(0)
    }

    @Test
    fun fr73_clipboard_text_is_never_logged() {
        val (host, connection) = host()
        val secret = "초대-7f3a9c"
        val lines = mutableListOf<String>()
        host.table.clipboard.log = { lines += it }
        // A platform whose own error message quotes the text it was handed.
        host.table.clipboard.sink = RefusingClipboard(quote = true)

        val mutation = Mutation.SetClipboardText(secret)
        host.table.apply(mutation)
        lines += mutation.toString()
        lines += connection.errors().map { it.message }

        assertTrue(lines.isNotEmpty(), "the refused write and the record were described")
        assertTrue(lines.none { secret in it }, "no line carries the text: $lines")
        assertTrue(lines.any { "${secret.length}" in it }, "a line may say how long it was: $lines")
    }

    private fun FakeHostConnection.reads() = events.filterIsInstance<HostEvent.ClipboardText>()

    @Test
    fun fr78_read_clipboard_round_trips() {
        val (host, connection) = host()
        val text = "한글\n줄 🙂"
        val clipboard = FakeReadableClipboard(text)
        host.table.clipboardReads.source = clipboard

        host.table.apply(Mutation.ReadClipboard(9))
        assertTrue(connection.reads().isEmpty(), "a read is answered outside the batch that asked")
        host.renderFrame(0)

        assertEquals(
            listOf(
                HostEvent.ClipboardText(
                    nodeId = 0,
                    handlerId = 0,
                    request = 9,
                    status = ClipboardReadStatus.TEXT,
                    length = text.encodeToByteArray().size,
                    text = text,
                ),
            ),
            connection.reads(),
        )
        assertEquals(1, clipboard.reads)
        // Answered once: another frame says nothing more.
        host.renderFrame(0)
        assertEquals(1, connection.reads().size)
        assertTrue(connection.errors().isEmpty())

        // Two reads in one batch get one answer each, with their own numbers, in order.
        host.table.apply(Mutation.ReadClipboard(10))
        host.table.apply(Mutation.ReadClipboard(11))
        host.renderFrame(0)
        assertEquals(listOf(9, 10, 11), connection.reads().map { it.request })
    }

    @Test
    fun fr78_empty_clipboard_reads_none() {
        val (host, connection) = host()
        val clipboard = FakeReadableClipboard("")
        host.table.clipboardReads.source = clipboard

        // Empty text, then no text at all (an image, say, which has no plain text).
        host.table.apply(Mutation.ReadClipboard(1))
        host.renderFrame(0)
        clipboard.text = null
        host.table.apply(Mutation.ReadClipboard(2))
        host.renderFrame(0)

        assertEquals(
            listOf(
                HostEvent.ClipboardText(0, 0, 1, ClipboardReadStatus.NONE, 0, ""),
                HostEvent.ClipboardText(0, 0, 2, ClipboardReadStatus.NONE, 0, ""),
            ),
            connection.reads(),
        )
    }

    @Test
    fun fr78_unavailable_clipboard_reads_none() {
        val (host, connection) = host()
        val lines = mutableListOf<String>()
        host.table.clipboardReads.log = { lines += it }

        // No clipboard at all: still exactly one answer, and it is none.
        host.table.apply(Mutation.ReadClipboard(3))
        host.renderFrame(0)
        // A clipboard that refuses, as a browser does when permission is denied.
        host.table.clipboardReads.source = DeniedClipboard("비밀")
        host.table.apply(Mutation.ReadClipboard(4))
        host.renderFrame(0)

        assertEquals(
            listOf(
                HostEvent.ClipboardText(0, 0, 3, ClipboardReadStatus.NONE, 0, ""),
                HostEvent.ClipboardText(0, 0, 4, ClipboardReadStatus.NONE, 0, ""),
            ),
            connection.reads(),
        )
        assertTrue(connection.errors().isEmpty(), "a missing clipboard is not a protocol error")
        assertTrue(lines.none { "비밀" in it }, "the platform's message is not repeated: $lines")
        // The process goes on: the next read answers normally.
        host.table.clipboardReads.source = FakeReadableClipboard("다음")
        host.table.apply(Mutation.ReadClipboard(5))
        host.renderFrame(0)
        assertEquals("다음", connection.reads().last().text)
    }

    @Test
    fun fr78_oversized_clipboard_text_is_reported_not_truncated() {
        val (host, connection) = host()
        val limit = ClipboardReadStatus.MAX_TEXT_BYTES
        host.table.clipboardReads.source = FakeReadableClipboard("a".repeat(limit + 1))
        host.table.apply(Mutation.ReadClipboard(6))
        host.renderFrame(0)
        host.table.clipboardReads.source = FakeReadableClipboard("a".repeat(limit))
        host.table.apply(Mutation.ReadClipboard(7))
        host.renderFrame(0)

        val (over, at) = connection.reads()
        assertEquals(HostEvent.ClipboardText(0, 0, 6, ClipboardReadStatus.TOO_LONG, limit + 1, ""), over)
        assertEquals(ClipboardReadStatus.TEXT, at.status)
        assertEquals(limit, at.text.length, "the limit itself is carried whole")
    }

    @Test
    fun fr78_clipboard_read_text_is_never_logged() {
        val (host, connection) = host()
        val secret = "붙여넣기-7f3a9c"
        val lines = mutableListOf<String>()
        host.table.clipboardReads.log = { lines += it }
        host.table.clipboardReads.source = FakeReadableClipboard(secret)
        host.table.apply(Mutation.ReadClipboard(8))
        host.renderFrame(0)
        host.table.clipboardReads.source = DeniedClipboard(secret)
        host.table.apply(Mutation.ReadClipboard(9))
        host.renderFrame(0)

        lines += connection.reads().map { it.toString() }
        assertTrue(lines.none { secret in it }, "no line carries the text: $lines")
        assertTrue(lines.any { "length=${secret.encodeToByteArray().size}" in it }, "the length may be said: $lines")
    }

    @Test
    fun fr78_a_delayed_read_is_answered_once_when_it_finishes() {
        val (host, connection) = host()
        val clipboard = SlowClipboard()
        var wakes = 0
        host.table.clipboardReads.wake = { wakes += 1 }
        host.table.clipboardReads.source = clipboard

        host.table.apply(Mutation.ReadClipboard(12))
        host.renderFrame(0)
        assertTrue(connection.reads().isEmpty(), "nothing is answered while the platform asks")
        host.renderFrame(0)
        assertTrue(connection.reads().isEmpty())

        val wakesBefore = wakes
        clipboard.pending!!.resume("늦은 글자")
        assertTrue(wakes > wakesBefore, "a finished read asks for the frame that answers it")
        host.renderFrame(0)
        host.renderFrame(0)
        assertEquals(
            listOf(HostEvent.ClipboardText(0, 0, 12, ClipboardReadStatus.TEXT, "늦은 글자".encodeToByteArray().size, "늦은 글자")),
            connection.reads(),
        )
    }

    @Test
    fun fr78_a_read_that_throws_after_suspending_answers_none() {
        val (host, connection) = host()
        val clipboard = SlowClipboard()
        host.table.clipboardReads.source = clipboard
        val lines = mutableListOf<String>()
        host.table.clipboardReads.log = { lines += it }

        host.table.apply(Mutation.ReadClipboard(13))
        host.renderFrame(0)
        // The browser refuses after asking the user.
        clipboard.pending!!.resumeWith(Result.failure(IllegalStateException("denied: 비밀")))
        host.renderFrame(0)

        assertEquals(listOf(HostEvent.ClipboardText(0, 0, 13, ClipboardReadStatus.NONE, 0, "")), connection.reads())
        assertTrue(lines.none { "비밀" in it }, "the platform's message is not repeated: $lines")
    }

    @Test
    fun fr78_without_a_launcher_a_read_answers_none() {
        val (host, connection) = host()
        host.table.clipboardReads.launcher = null
        host.table.clipboardReads.source = FakeReadableClipboard("있음")
        host.table.apply(Mutation.ReadClipboard(14))
        host.renderFrame(0)
        assertEquals(listOf(HostEvent.ClipboardText(0, 0, 14, ClipboardReadStatus.NONE, 0, "")), connection.reads())
    }

    @Test
    fun fr78_a_read_whose_composition_leaves_before_it_starts_answers_none_once() {
        val (host, connection) = host()
        // A launcher whose scope is already cancelled: the read is handed over and never runs.
        host.table.clipboardReads.launcher = { _ -> }
        host.table.clipboardReads.source = FakeReadableClipboard("안 읽힘")

        host.table.apply(Mutation.ReadClipboard(15))
        host.renderFrame(0)
        assertTrue(connection.reads().isEmpty())

        host.table.clipboardReads.detach()
        host.renderFrame(0)
        host.table.clipboardReads.detach()
        host.renderFrame(0)
        assertEquals(listOf(HostEvent.ClipboardText(0, 0, 15, ClipboardReadStatus.NONE, 0, "")), connection.reads())

        // After it, a read answers none at once rather than waiting for a scope that is gone.
        host.table.apply(Mutation.ReadClipboard(16))
        host.renderFrame(0)
        assertEquals(listOf(15, 16), connection.reads().map { it.request })
    }
}
