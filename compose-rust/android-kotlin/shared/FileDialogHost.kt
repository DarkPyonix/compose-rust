package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.FileDelivery
import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.protocol.FileDialogStatus
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.runtime.EventDispatcher
import kotlinx.coroutines.channels.Channel

/** The limits a request and its answer are held to. */
object FileDialogLimits {
    /** Filters kept from one request; the rest are dropped. */
    const val MAX_FILTERS = 16

    /** Extensions kept from one filter; the rest are dropped. */
    const val MAX_EXTENSIONS = 32

    /** Paths one answer may carry. More is not delivered as a shorter list: it is unavailable. */
    const val MAX_PATHS = 10_000

    /**
     * Bytes of paths one answer may carry, separators included.
     *
     * Half of what the largest event buffer the desktop connection will grow to, so the
     * record around the paths always fits. A platform with a smaller buffer sets a smaller
     * limit on its [FileDialogCenter].
     */
    const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
}

/** One filter of a dialog: what the user sees, and the extensions it lets through. */
data class FileDialogFilter(
    val label: String,
    /** Lower case, without a leading dot or `*.`. Empty means every file. */
    val extensions: List<String>,
)

/**
 * One dialog, as a platform is asked to show it, after the request was checked and
 * normalised.
 *
 * [windowNode] is the `Window` node the dialog belongs to, or 0 for the implicit root
 * window. [name] is a bare file name, and only a save dialog has one. [filters] is empty for
 * the folder kinds. [defaultFilter] is a position inside [filters], or 0.
 */
data class FileDialogSpec(
    val request: Int,
    val windowNode: Int,
    val kind: FileDialogKind,
    val title: String,
    val directory: String,
    val name: String,
    val filters: List<FileDialogFilter>,
    val defaultFilter: Int,
    /**
     * For each entry of [filters], its position in the list the Host sent. A filter dropped
     * on the way makes this differ from the identity; the result's `filter` is mapped back
     * through it. Empty means the identity.
     */
    val sentPositions: List<Int> = emptyList(),
)

/**
 * One path a dialog returned. A platform that hands over text passes [Text]; one whose paths
 * are bytes (a Linux file system does not promise UTF-8) passes [Bytes]. One that is not UTF-8
 * makes the whole choice unavailable.
 */
sealed interface FileDialogPath {
    class Text(val value: String) : FileDialogPath
    class Bytes(val value: ByteArray) : FileDialogPath
}

/** How a platform's dialog ended. */
sealed interface FileDialogOutcome {
    /**
     * The user chose. [filter] is the position of the filter that was selected, or null when
     * the dialog had none.
     */
    class Chosen(val paths: List<FileDialogPath>, val filter: Int? = null) : FileDialogOutcome {
        companion object {
            fun ofText(paths: List<String>, filter: Int? = null): Chosen =
                Chosen(paths.map { FileDialogPath.Text(it) }, filter)
        }
    }

    /**
     * The user chose, on a platform with no paths: the platform holds a handle for each
     * entry of [files], numbered [firstHandle], [firstHandle] + 1 and so on, and the Host is
     * told their names. [filter] is as for [Chosen].
     */
    class Handles(
        val delivery: FileDelivery,
        val firstHandle: Int,
        val files: List<HeldFile>,
        val filter: Int? = null,
    ) : FileDialogOutcome

    /** The user closed the dialog. */
    data object Cancelled : FileDialogOutcome

    /** The dialog could not be shown. */
    data object Unavailable : FileDialogOutcome
}

/**
 * One platform's file dialogs.
 *
 * [show] is called on the UI thread and must return without waiting for the user. The
 * outcome goes to [onResult], from whichever thread the platform answers on, once. Calling
 * it again is harmless: only the first counts.
 */
interface FileDialogPlatform {
    /**
     * Shows [request] on [window], the handle for the window it belongs to. The handle is
     * whatever [FileDialogCenter.windowResolver] gave for the request's window node, and is
     * null for the implicit root window or when nothing resolves one.
     */
    fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit)

    /**
     * The window a dialog was shown on is going away. The platform closes that window's
     * dialog if it has one open. The request ends with a cancel whether or not it does.
     */
    fun cancelWindow(window: Any?) {}

    /**
     * The handles a [FileDialogOutcome.Handles] numbered were never delivered (the answer did
     * not fit, or a name could not travel), so nothing will ever name them. A platform that
     * holds handles lets go of them.
     */
    fun discardHandles(firstHandle: Int, count: Int) {}
}

/**
 * A platform with no file dialog yet, or a run that cannot show one. Every request is
 * answered at once with [FileDialogOutcome.Unavailable]; that is not a protocol error.
 */
object UnsupportedFileDialogs : FileDialogPlatform {
    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) =
        onResult(FileDialogOutcome.Unavailable)
}

/**
 * The file dialogs this process uses.
 *
 * Installed by each platform's entry point before the Host starts, the way the notification
 * centre is. A run nobody installed anything for answers every request as unavailable.
 */
object FileDialogs {
    var platform: FileDialogPlatform = UnsupportedFileDialogs

    /**
     * The largest event this process can hand the Host, record and strings together. A
     * connection whose event buffer stops growing at a known size (the web's, at the largest
     * buffer its Host lends) sets it, and an answer that would not fit is unavailable rather
     * than cut short.
     */
    var maxEventBytes: Int = Int.MAX_VALUE
}

/**
 * What the interpreter does with file dialog requests, and the one route the answers take
 * back to the Host.
 *
 * It owns the rules that do not depend on a platform: a request number waits for one answer
 * only, requests on one window are shown one after the other in the order they came, a
 * window that goes away ends its dialog with a cancel, and an answer is delivered on the UI
 * thread inside a frame, whole or not at all. The platform underneath only shows and reports.
 */
class FileDialogCenter(
    private val platform: FileDialogPlatform = FileDialogs.platform,
    private val maxPayloadBytes: Int = FileDialogLimits.MAX_PAYLOAD_BYTES,
    private val maxEventBytes: Int = FileDialogs.maxEventBytes,
) {
    /** What `request` made of the request, for the table to report. */
    enum class Accepted { Accepted, Duplicate, UnknownWindow }

    private class Entry(val spec: FileDialogSpec) {
        /** The platform was asked, or the request is already answered and only waits to be delivered. */
        var started = false

        /** An answer is on its way to being delivered, so nothing else starts on this window yet. */
        var finishing = false
        var handle: Any? = null
    }

    private class Completion(val request: Int, val outcome: FileDialogOutcome)

    /**
     * Answers from the platform, from any thread. A channel because it is the one queue every
     * target can share across threads without a lock of its own, and unlimited because what
     * goes in is a handful of answers.
     */
    private val inbox = Channel<Completion>(Channel.UNLIMITED)

    /** Requests that have not been delivered an answer yet, in the order they came. */
    private val pending = LinkedHashMap<Int, Entry>()

    private var dispatcher: EventDispatcher? = null
    private var draining = false

    /** How an answer asks for the frame that delivers it. */
    var wake: () -> Unit = {}

    /**
     * Gives the platform's handle for a `Window` node, read when the dialog is shown. Node 0
     * has none. The window manager of a multi-window application installs this.
     */
    var windowResolver: (Int) -> Any? = { null }

    /** How many requests are waiting for an answer. */
    internal val pendingCount: Int get() = pending.size

    /**
     * Takes the request, normalised, unless its number is already waiting for an answer.
     *
     * [windowKnown] says whether the window it names is the implicit root or an existing
     * `Window` node. A request on an unknown window is still answered, as unavailable.
     */
    fun request(mutation: Mutation.ShowFileDialog, windowKnown: Boolean): Accepted {
        if (pending.containsKey(mutation.request)) return Accepted.Duplicate
        val entry = Entry(normalize(mutation))
        pending[mutation.request] = entry
        if (!windowKnown) {
            entry.started = true
            entry.finishing = true
            inbox.trySend(Completion(mutation.request, FileDialogOutcome.Unavailable))
            wake()
            return Accepted.UnknownWindow
        }
        wake()
        return Accepted.Accepted
    }

    /**
     * The `Window` node [windowNode] was removed. Its dialog is closed and every request on it
     * ends with one cancel.
     */
    fun windowRemoved(windowNode: Int) {
        if (windowNode == 0) return
        var any = false
        for (entry in pending.values.toList()) {
            if (entry.spec.windowNode != windowNode || entry.finishing) continue
            any = true
            entry.finishing = true
            if (entry.started) platform.cancelWindow(entry.handle)
            inbox.trySend(Completion(entry.spec.request, FileDialogOutcome.Cancelled))
        }
        if (any) wake()
    }

    /** Connects the center to the Host, once its first batch has been applied. */
    fun attach(host: EventDispatcher) {
        dispatcher = host
        drain()
    }

    /** The process is ending. */
    fun shutdown() {
        dispatcher = null
        pending.clear()
        while (inbox.tryReceive().getOrNull() != null) Unit
    }

    /**
     * Starts what may start and hands every answer to the Host, on the UI thread.
     *
     * Called where the Host can be entered: after its start, and at the top of every frame
     * an answer asked for. A request is started here and not where it arrives, because the
     * batch that carried it may also have created the window it belongs to, and the platform
     * window exists only once the batch is through.
     */
    fun drain() {
        val host = dispatcher ?: return
        if (draining) return
        draining = true
        try {
            var progress = true
            while (progress) {
                progress = false
                while (true) {
                    val done = inbox.tryReceive().getOrNull() ?: break
                    // Only the first answer to a request counts; a second finds nothing waiting.
                    val entry = pending.remove(done.request) ?: continue
                    progress = true
                    host.dispatch(resultEvent(entry.spec, done.outcome))
                }
                for (entry in pending.values.toList()) {
                    if (entry.started || entry.finishing) continue
                    if (pending.values.any { it !== entry && it.spec.windowNode == entry.spec.windowNode && (it.started || it.finishing) }) continue
                    start(entry)
                    progress = true
                }
            }
        } finally {
            draining = false
        }
    }

    private fun start(entry: Entry) {
        entry.started = true
        val request = entry.spec.request
        val handle = windowResolver(entry.spec.windowNode)
        entry.handle = handle
        try {
            platform.show(handle, entry.spec) { outcome ->
                inbox.trySend(Completion(request, outcome))
                wake()
            }
        } catch (failure: Exception) {
            // A platform that throws has no dialog to show, which is the same answer.
            inbox.trySend(Completion(request, FileDialogOutcome.Unavailable))
        }
    }

    private fun resultEvent(spec: FileDialogSpec, outcome: FileDialogOutcome): HostEvent {
        val none = -1
        if (outcome is FileDialogOutcome.Handles) return handlesResult(spec, outcome)
        return when (val checked = judge(outcome)) {
            is Checked.Paths -> HostEvent.FileDialogResult(
                nodeId = 0,
                handlerId = 0,
                request = spec.request,
                status = FileDialogStatus.Chosen,
                // The platform answers with a position in the cleaned list; the Host is told the
                // position in the list it sent.
                filter = checked.filter
                    ?.takeIf { chosen -> chosen in spec.filters.indices }
                    ?.let { chosen -> spec.sentPositions.getOrElse(chosen) { chosen } }
                    ?: none,
                paths = checked.joined,
            )
            is Checked.Ended -> HostEvent.FileDialogResult(
                nodeId = 0,
                handlerId = 0,
                request = spec.request,
                status = checked.status,
                filter = none,
                paths = "",
            )
        }
    }

    /**
     * A choice on a platform with no paths, as a `FileHandles` event, or as an unavailable
     * result when it cannot be delivered whole: more files than one answer may carry, a name
     * that cannot travel, or more bytes than the event buffer holds. The handles of an answer
     * that is not delivered are let go of, because nothing will ever name them.
     */
    private fun handlesResult(spec: FileDialogSpec, outcome: FileDialogOutcome.Handles): HostEvent {
        val ended = { status: FileDialogStatus ->
            platform.discardHandles(outcome.firstHandle, outcome.files.size)
            HostEvent.FileDialogResult(0, 0, spec.request, status, -1, "")
        }
        if (outcome.files.isEmpty()) return ended(FileDialogStatus.Cancelled)
        if (outcome.files.size > FileDialogLimits.MAX_PATHS) return ended(FileDialogStatus.Unavailable)
        val filter = outcome.filter
            ?.takeIf { chosen -> chosen in spec.filters.indices }
            ?.let { chosen -> spec.sentPositions.getOrElse(chosen) { chosen } }
            ?: -1
        val event = FileCallCenter.handlesEvent(spec.request, outcome.delivery, outcome.firstHandle, outcome.files, filter)
            ?: return ended(FileDialogStatus.Unavailable)
        val size = FileCallCenter.encodedSize(event)
        if (size > maxEventBytes || size > maxPayloadBytes) return ended(FileDialogStatus.Unavailable)
        return event
    }

    private sealed interface Checked {
        class Paths(val joined: String, val filter: Int?) : Checked
        class Ended(val status: FileDialogStatus) : Checked
    }

    /**
     * What the Host is told about an outcome.
     *
     * A path that is not well formed text makes the whole choice unavailable: the other paths
     * are not sent either, because the application could not tell that the list is shorter
     * than what the user picked, and a cancel would be false. A choice with too many paths, or too many bytes of them, is
     * unavailable too, never a shorter list: the application would not know it was short.
     */
    private fun judge(outcome: FileDialogOutcome): Checked = when (outcome) {
        FileDialogOutcome.Cancelled -> Checked.Ended(FileDialogStatus.Cancelled)
        FileDialogOutcome.Unavailable -> Checked.Ended(FileDialogStatus.Unavailable)
        // Answered by handlesResult before it gets here.
        is FileDialogOutcome.Handles -> Checked.Ended(FileDialogStatus.Unavailable)
        is FileDialogOutcome.Chosen -> when {
            outcome.paths.isEmpty() -> Checked.Ended(FileDialogStatus.Cancelled)
            outcome.paths.size > FileDialogLimits.MAX_PATHS -> Checked.Ended(FileDialogStatus.Unavailable)
            else -> {
                val checked = outcome.paths.map { wellFormedPath(it) }
                val texts = checked.filterNotNull()
                when {
                    texts.size != checked.size -> Checked.Ended(FileDialogStatus.Unavailable)
                    texts.sumOf { it.encodeToByteArray().size.toLong() + 1L } > maxPayloadBytes.toLong() ->
                        Checked.Ended(FileDialogStatus.Unavailable)
                    // The record in front of the paths, and the paths, in a buffer that does not grow.
                    texts.sumOf { it.encodeToByteArray().size.toLong() + 1L } - 1L + FILE_DIALOG_RESULT_RECORD_BYTES >
                        maxEventBytes.toLong() -> Checked.Ended(FileDialogStatus.Unavailable)
                    else -> Checked.Paths(texts.joinToString("\u0000"), outcome.filter)
                }
            }
        }
    }

    companion object {
        /** `FileDialogResult`'s record in front of the paths it carries. */
        private const val FILE_DIALOG_RESULT_RECORD_BYTES = 36L

        /**
         * Checks a request and puts it in the form a platform can rely on: filters and
         * extensions within their limits, extensions lower case without a leading dot or
         * `*.`, a default filter that exists, a bare file name, and nothing in the fields a
         * kind does not use.
         */
        fun normalize(mutation: Mutation.ShowFileDialog): FileDialogSpec {
            val folders = mutation.kind == FileDialogKind.OpenFolder || mutation.kind == FileDialogKind.OpenFolders
            val parsed = if (folders) emptyList() else parseFilters(mutation.filters)
            val filters = parsed.map { it.second }
            val name = if (mutation.kind == FileDialogKind.Save) {
                mutation.name.substringAfterLast('/').substringAfterLast('\\')
            } else {
                ""
            }
            return FileDialogSpec(
                request = mutation.request,
                windowNode = mutation.window,
                kind = mutation.kind,
                title = mutation.title,
                directory = mutation.directory,
                name = name,
                filters = filters,
                // The default is a position in the list the Host sent, so it follows its filter
                // when an earlier one was dropped.
                defaultFilter = parsed.indexOfFirst { it.first == mutation.defaultFilter }.takeIf { it >= 0 } ?: 0,
                sentPositions = parsed.map { it.first },
            )
        }

        private fun parseFilters(encoded: String): List<Pair<Int, FileDialogFilter>> {
            if (encoded.isEmpty()) return emptyList()
            // Label, extensions, label, extensions. A label with nothing after it lets every
            // file through, like one with no extensions.
            val parts = encoded.split('\u0000')
            val filters = ArrayList<Pair<Int, FileDialogFilter>>()
            var index = 0
            var position = 0
            while (index < parts.size && position < FileDialogLimits.MAX_FILTERS) {
                val named = (parts.getOrNull(index + 1) ?: "").split(',').filter { it.isNotBlank() }
                val extensions = named
                    .map { it.trim().removePrefix("*.").removePrefix(".").lowercase() }
                    .filter { it.isNotEmpty() }
                    .take(FileDialogLimits.MAX_EXTENSIONS)
                // A filter that names extensions and keeps none of them (a lone ".") would
                // otherwise mean every file, the opposite of what it asked for.
                if (named.isEmpty() || extensions.isNotEmpty()) {
                    filters += position to FileDialogFilter(parts[index], extensions)
                }
                position++
                index += 2
            }
            return filters
        }

        /** The path as text, or null for one that is empty, holds a NUL, or is not valid Unicode text. */
        private fun wellFormedPath(path: FileDialogPath): String? {
            val text = when (path) {
                is FileDialogPath.Text -> path.value
                is FileDialogPath.Bytes -> try {
                    path.value.decodeToString(throwOnInvalidSequence = true)
                } catch (failure: Exception) {
                    return null
                }
            }
            if (text.isEmpty()) return null
            var index = 0
            while (index < text.length) {
                val unit = text[index]
                when {
                    unit == '\u0000' -> return null
                    unit.isHighSurrogate() -> {
                        if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return null
                        index++
                    }
                    unit.isLowSurrogate() -> return null
                }
                index++
            }
            return text
        }
    }
}
