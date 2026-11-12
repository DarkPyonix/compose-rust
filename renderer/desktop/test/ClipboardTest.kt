package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.ClipboardSink
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
}
