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
    var log: (String) -> Unit = { line -> println(line) }

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

/**
 * Where a clipboard read comes from. In an application it is Compose's clipboard.
 *
 * It suspends because a browser only answers a read later, after it has asked the user.
 * A desktop clipboard answers at once.
 */
fun interface ClipboardSource {
    /** The plain text on the clipboard, or null when there is none or it was refused. */
    suspend fun getText(): String?
}

/**
 * How a platform reads plain text from Compose's clipboard, when it has a way better than
 * the clipboard manager every target shares. The web sets it at start: the browser has no
 * synchronous read, so its clipboard manager always answers nothing.
 */
object ClipboardReads {
    var platform: (suspend (androidx.compose.ui.platform.Clipboard) -> String?)? = null
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
 * A request arrives inside a batch, where the Host cannot be entered, so it waits here.
 * [drain] starts each waiting read on [launcher], which runs it on the UI thread (in an
 * application, the composition's coroutine scope), and hands every finished answer to the
 * Host. A read that finishes asks for a frame through [wake], and the frame drains it.
 *
 * The answer is no text when the clipboard is empty, holds something other than text, is
 * missing (a headless run, a test, a composition that has not handed over [source] yet), or
 * the read throws or is cancelled, which is how a browser that was refused permission
 * answers. Text longer than [ClipboardReadStatus.MAX_TEXT_BYTES] is reported as too long
 * and not sent: cutting it would paste part of what the user copied without saying so.
 *
 * The text is whatever the user copied, so it is never written to a log; a failure says
 * which request it was and nothing about the text.
 */
class ClipboardReader {
    var source: ClipboardSource? = null

    /**
     * Runs a read. Without one, a read cannot suspend and is answered as no text. The
     * composition installs its coroutine scope here.
     */
    var launcher: ((suspend () -> Unit) -> Unit)? = null

    /** Where a failed read is reported. Tests replace it to read what was said. */
    var log: (String) -> Unit = { line -> println(line) }

    /** How a request or a finished read asks for the frame that answers it. */
    var wake: () -> Unit = {}

    /** Requests not started yet, in the order they came. */
    private val waiting = ArrayList<Int>()

    /** Requests started and not answered yet. */
    private val running = HashSet<Int>()

    /** Answers ready to go to the Host, in the order they finished. */
    private val finished = ArrayList<HostEvent.ClipboardText>()

    private var dispatcher: dev.darkpyonix.composerust.runtime.EventDispatcher? = null
    private var draining = false

    /** How many reads are waiting for their answer. */
    internal val pendingCount: Int get() = waiting.size + running.size + finished.size

    /** Takes one read request. It is started by the next [drain]. */
    fun request(request: Int) {
        waiting += request
        wake()
    }

    /** Connects the reader to the Host, once its first batch has been applied. */
    fun attach(host: dev.darkpyonix.composerust.runtime.EventDispatcher) {
        dispatcher = host
        drain()
    }

    /**
     * The composition whose scope ran the reads is gone. A read it had not started, or not
     * finished, never will be, so each is answered now as no text, once. Later reads answer
     * none too until a composition installs a launcher again.
     */
    fun detach() {
        launcher = null
        source = null
        for (request in running.toList()) finish(request, null)
    }

    /** The process is ending. Nothing is answered any more. */
    fun shutdown() {
        dispatcher = null
        waiting.clear()
        running.clear()
        finished.clear()
    }

    /** Starts every waiting read and answers every finished one, oldest first. */
    fun drain() {
        val host = dispatcher ?: return
        if (draining) return
        draining = true
        try {
            while (waiting.isNotEmpty() || finished.isNotEmpty()) {
                while (waiting.isNotEmpty()) start(waiting.removeAt(0))
                while (finished.isNotEmpty()) host.dispatch(finished.removeAt(0))
            }
        } finally {
            draining = false
        }
    }

    private fun start(request: Int) {
        val from = source
        val run = launcher
        if (from == null || run == null) {
            finished += answer(request, null)
            return
        }
        running += request
        try {
            run {
                val text = try {
                    from.getText()
                } catch (failure: Throwable) {
                    // A cancel and a refusal are the same answer. The exception's message is
                    // left out: a platform may quote what it holds.
                    log("compose-rust: clipboard read $request failed (${failure::class.simpleName})")
                    null
                }
                finish(request, text)
            }
        } catch (failure: Throwable) {
            log("compose-rust: clipboard read $request could not start (${failure::class.simpleName})")
            finish(request, null)
        }
    }

    /** Records the one answer [request] gets. A second call for it finds nothing running. */
    private fun finish(request: Int, text: String?) {
        if (!running.remove(request)) return
        finished += answer(request, text)
        wake()
        // A read that answered at once, inside the drain that started it, goes out in that
        // same drain; the loop there picks it up.
    }

    private fun answer(request: Int, text: String?): HostEvent.ClipboardText {
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
