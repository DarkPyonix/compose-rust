package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.HostEvent

/** Where a clipboard write goes. In an application it is Compose's clipboard. */
fun interface ClipboardSink {
    fun setText(text: String)
}

/**
 * Puts the text the Host asked for on the clipboard.
 *
 * The composition hands over [sink] once it can see a clipboard. Until then, and wherever
 * there is none at all (a headless run, a test), a write does nothing: nothing answers it
 * and it is not a protocol error, because no application needs to know.
 *
 * The text is whatever the application copied, and an invite link or a snippet can carry
 * something private, so it is never written to a log. A failure says how long the text was
 * and nothing else.
 */
class ClipboardWriter {
    var sink: ClipboardSink? = null

    /** Where a failed write is reported. Tests replace it to read what was said. */
    var log: (String) -> Unit = { line -> System.err.println(line) }

    fun write(text: String) {
        val target = sink ?: return
        try {
            target.setText(text)
        } catch (failure: Throwable) {
            // The exception's own message is left out: a platform may quote what it was given.
            log(
                "compose-rust: the clipboard refused ${text.length} characters " +
                    "(${failure::class.simpleName})",
            )
        }
    }
}

/** Where a clipboard read comes from. In an application it is Compose's clipboard. */
fun interface ClipboardSource {
    /** The plain text on the clipboard, or null when there is none. */
    fun getText(): String?
}

/** The status words a `ClipboardText` event carries, and the most text one may carry. */
object ClipboardReadStatus {
    /** No plain text: empty, something else, no clipboard, or the platform refused. */
    const val NONE = 0

    /** Plain text, carried whole. */
    const val TEXT = 1

    /** Plain text longer than [MAX_TEXT_BYTES]; none of it is carried, only its length. */
    const val TOO_LONG = 2

    /** The longest text one read carries, in UTF-8 bytes. The Host refuses longer writes too. */
    const val MAX_TEXT_BYTES = 64 * 1024
}

/**
 * Reads the clipboard for the Host and answers every request exactly once.
 *
 * A request arrives inside a batch, where the Host cannot be entered, so it waits here and
 * is answered from [drain]: once the Host has started, and at the top of every frame a
 * request asked for. The clipboard is read at that moment, on the UI thread.
 *
 * The answer is no text when the clipboard is empty, holds something other than text, is
 * missing (a headless run, a test, a composition that has not handed over [source] yet), or
 * throws, which is how a browser that was refused permission answers. Text longer than
 * [ClipboardReadStatus.MAX_TEXT_BYTES] is reported as too long and not sent: cutting it
 * would paste part of what the user copied without saying so.
 *
 * The text is whatever the user copied, so it is never written to a log; a failure says
 * which request it was and nothing about the text.
 */
class ClipboardReader {
    var source: ClipboardSource? = null

    /** Where a failed read is reported. Tests replace it to read what was said. */
    var log: (String) -> Unit = { line -> System.err.println(line) }

    /** How a request asks for the frame that answers it. */
    var wake: () -> Unit = {}

    private val pending = ArrayList<Int>()
    private var dispatcher: dev.darkpyonix.composerust.runtime.EventDispatcher? = null
    private var draining = false

    /** How many reads are waiting for their answer. */
    internal val pendingCount: Int get() = pending.size

    /** Takes one read request. It is answered by the next [drain]. */
    fun request(request: Int) {
        pending += request
        wake()
    }

    /** Connects the reader to the Host, once its first batch has been applied. */
    fun attach(host: dev.darkpyonix.composerust.runtime.EventDispatcher) {
        dispatcher = host
        drain()
    }

    /** The process is ending. Nothing is answered any more. */
    fun shutdown() {
        dispatcher = null
        pending.clear()
    }

    /** Answers every waiting read, oldest first, including any an answer's batch asked for. */
    fun drain() {
        val host = dispatcher ?: return
        if (draining) return
        draining = true
        try {
            while (pending.isNotEmpty()) {
                val request = pending.removeAt(0)
                host.dispatch(answer(request))
            }
        } finally {
            draining = false
        }
    }

    private fun answer(request: Int): HostEvent.ClipboardText {
        val text = try {
            source?.getText()
        } catch (failure: Throwable) {
            // The exception's own message is left out: a platform may quote what it holds.
            log("compose-rust: clipboard read $request failed (${failure::class.simpleName})")
            null
        }
        if (text.isNullOrEmpty()) {
            return HostEvent.ClipboardText(0, 0, request, ClipboardReadStatus.NONE, 0, "")
        }
        val bytes = text.encodeToByteArray().size
        if (bytes > ClipboardReadStatus.MAX_TEXT_BYTES) {
            log("compose-rust: clipboard read $request found $bytes bytes, more than one read carries")
            return HostEvent.ClipboardText(0, 0, request, ClipboardReadStatus.TOO_LONG, bytes, "")
        }
        return HostEvent.ClipboardText(0, 0, request, ClipboardReadStatus.TEXT, bytes, text)
    }
}
