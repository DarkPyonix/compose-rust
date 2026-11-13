package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.FileDelivery
import dev.darkpyonix.composerust.protocol.FileDialogStatus
import dev.darkpyonix.composerust.protocol.FileOp
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.Mutation

/** One file or folder a platform holds a handle for: what the Host is told about it. */
data class HeldFile(val name: String, val folder: Boolean)

/** How one operation on a held file ended, as a platform reports it. */
sealed interface FileCallOutcome {
    /** A read that succeeded: the whole file. */
    class Bytes(val data: ByteArray) : FileCallOutcome

    /** A write that succeeded. */
    data object Written : FileCallOutcome

    /**
     * Files the platform now holds handles for: a folder's listing, or the one child asked
     * for. The handles are [firstHandle], [firstHandle] + 1 and so on, one per entry of
     * [files], numbered by the platform.
     */
    class Files(val delivery: FileDelivery, val firstHandle: Int, val files: List<HeldFile>) : FileCallOutcome

    /** It did not happen, for the reason [status] gives. Never [IoStatus.Ok]. */
    class Failed(val status: IoStatus) : FileCallOutcome
}

/** The sizes file answers are held to. */
object FileCallLimits {
    /** The longest file a read carries, the Host's own limit. A longer one is too large before it is read. */
    const val MAX_READ_BYTES: Long = 256L * 1024 * 1024

    /** `IoAnswer`'s record in front of the bytes it carries. */
    const val IO_ANSWER_RECORD_BYTES = 32

    /** `FileHandles`'s record in front of the names and kinds it carries. */
    const val FILE_HANDLES_RECORD_BYTES = 52
}

/**
 * The files one platform holds handles for, and what can be done with them.
 *
 * Only a platform with no paths needs this: a browser. Everywhere else the Host reads and
 * writes paths itself and never sends a file operation. Every function answers; one that
 * throws is answered as [IoStatus.Failed].
 */
interface FileCallPlatform {
    /** Reads the whole file. One longer than [limit] bytes is [IoStatus.TooLarge] without being read. */
    suspend fun read(handle: Int, limit: Long): FileCallOutcome

    /** Replaces the file's contents with [data]. */
    suspend fun write(handle: Int, data: ByteArray): FileCallOutcome

    /** What the folder holds directly, each as a new handle. */
    suspend fun entries(handle: Int): FileCallOutcome

    /** The child called [name], as a new handle; with [create], a new empty file when there is none. */
    suspend fun child(handle: Int, name: String, create: Boolean): FileCallOutcome

    /** The Host names [handle] nowhere any more. A handle this platform does not hold is ignored. */
    fun release(handle: Int) {}
}

/** A platform that holds no handles: every operation is [IoStatus.Unavailable]. */
object UnsupportedFileCalls : FileCallPlatform {
    private val unavailable = FileCallOutcome.Failed(IoStatus.Unavailable)

    override suspend fun read(handle: Int, limit: Long): FileCallOutcome = unavailable

    override suspend fun write(handle: Int, data: ByteArray): FileCallOutcome = unavailable

    override suspend fun entries(handle: Int): FileCallOutcome = unavailable

    override suspend fun child(handle: Int, name: String, create: Boolean): FileCallOutcome = unavailable
}

/** The file operations this process uses. Installed by the web entry point; elsewhere none. */
object FileCalls {
    var platform: FileCallPlatform = UnsupportedFileCalls
}

/**
 * What the interpreter does with file operations, and the one route their answers take back.
 *
 * The rules that do not depend on a platform live here: a request number waits for one
 * answer only, every request is answered exactly once, and an answer that would not fit the
 * event buffer is never cut short: a read is [IoStatus.TooLarge] and a listing is too, with
 * the handles it numbered let go of. Operations run on [launcher] (the composition's
 * coroutine scope, so the UI thread), and a finished one asks for a frame through [wake];
 * the frame delivers it.
 */
class FileCallCenter(
    private val platform: FileCallPlatform = FileCalls.platform,
    private val maxEventBytes: Int = FileDialogs.maxEventBytes,
) {
    enum class Accepted { Accepted, Duplicate }

    /** Runs an operation. Without one, nothing can suspend, and every operation is unavailable. */
    var launcher: ((suspend () -> Unit) -> Unit)? = null

    /** How a request or a finished operation asks for the frame that answers it. */
    var wake: () -> Unit = {}

    private val waiting = ArrayList<Mutation.FileCall>()
    private val running = HashSet<Int>()
    private val finished = ArrayList<HostEvent>()
    private var dispatcher: dev.darkpyonix.composerust.runtime.EventDispatcher? = null
    private var draining = false

    /** How many operations are waiting for their answer. */
    internal val pendingCount: Int get() = waiting.size + running.size + finished.size

    /** Takes one operation unless its number is already waiting. It starts at the next [drain]. */
    fun request(call: Mutation.FileCall): Accepted {
        if (waiting.any { it.request == call.request } || call.request in running ||
            finished.any { (it as? HostEvent.IoAnswer)?.request == call.request || (it as? HostEvent.FileHandles)?.request == call.request }
        ) {
            return Accepted.Duplicate
        }
        waiting += call
        wake()
        return Accepted.Accepted
    }

    /** The Host let go of [handle]. */
    fun release(handle: Int) {
        try {
            platform.release(handle)
        } catch (failure: Exception) {
            // Nothing to answer, and a handle that could not be let go of is only memory.
        }
    }

    fun attach(host: dev.darkpyonix.composerust.runtime.EventDispatcher) {
        dispatcher = host
        drain()
    }

    /** The composition whose scope ran the operations is gone: what it was running is unavailable now. */
    fun detach() {
        launcher = null
        for (request in running.toList()) finish(request, failed(request, IoStatus.Unavailable))
    }

    fun shutdown() {
        dispatcher = null
        waiting.clear()
        running.clear()
        finished.clear()
    }

    /** Starts every waiting operation and delivers every finished one, oldest first. */
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

    private fun start(call: Mutation.FileCall) {
        val run = launcher
        if (run == null) {
            finished += failed(call.request, IoStatus.Unavailable)
            return
        }
        running += call.request
        try {
            run {
                val outcome = try {
                    when (call.op) {
                        FileOp.Read -> platform.read(call.handle, readLimit())
                        FileOp.Write -> platform.write(call.handle, call.data)
                        FileOp.Entries -> platform.entries(call.handle)
                        FileOp.Child -> platform.child(call.handle, call.name, call.create)
                    }
                } catch (failure: Throwable) {
                    FileCallOutcome.Failed(IoStatus.Failed)
                }
                finish(call.request, answer(call, outcome))
            }
        } catch (failure: Throwable) {
            finish(call.request, failed(call.request, IoStatus.Failed))
        }
    }

    /** The longest read whose answer fits one event, and never more than the Host reads. */
    private fun readLimit(): Long =
        minOf(FileCallLimits.MAX_READ_BYTES, (maxEventBytes - FileCallLimits.IO_ANSWER_RECORD_BYTES).toLong())

    private fun finish(request: Int, event: HostEvent) {
        if (!running.remove(request)) return
        finished += event
        wake()
    }

    private fun failed(request: Int, status: IoStatus): HostEvent =
        HostEvent.IoAnswer(0, 0, request, status, ByteArray(0))

    /** The one event an outcome becomes for [call]. */
    private fun answer(call: Mutation.FileCall, outcome: FileCallOutcome): HostEvent = when (outcome) {
        is FileCallOutcome.Failed -> failed(
            call.request,
            if (outcome.status == IoStatus.Ok) IoStatus.Failed else outcome.status,
        )
        is FileCallOutcome.Bytes -> when {
            call.op != FileOp.Read -> failed(call.request, IoStatus.Failed)
            outcome.data.size.toLong() > readLimit() -> failed(call.request, IoStatus.TooLarge)
            else -> HostEvent.IoAnswer(0, 0, call.request, IoStatus.Ok, outcome.data)
        }
        FileCallOutcome.Written ->
            if (call.op == FileOp.Write) {
                HostEvent.IoAnswer(0, 0, call.request, IoStatus.Ok, ByteArray(0))
            } else {
                failed(call.request, IoStatus.Failed)
            }
        is FileCallOutcome.Files -> files(call, outcome)
    }

    private fun files(call: Mutation.FileCall, outcome: FileCallOutcome.Files): HostEvent {
        val wrongShape = (call.op != FileOp.Entries && call.op != FileOp.Child) ||
            (call.op == FileOp.Child && outcome.files.size != 1)
        val event = if (wrongShape) null else handlesEvent(call.request, outcome.delivery, outcome.firstHandle, outcome.files, -1)
        if (event == null || encodedSize(event) > maxEventBytes) {
            // Not delivered, so nothing on the Host will ever name these handles.
            discard(outcome.firstHandle, outcome.files.size)
            return failed(call.request, if (wrongShape || event == null) IoStatus.Failed else IoStatus.TooLarge)
        }
        return event
    }

    private fun discard(firstHandle: Int, count: Int) {
        for (index in 0 until count) release(firstHandle + index)
    }

    companion object {
        /**
         * The `FileHandles` event for [files], or null when a name cannot travel: an empty
         * one, or one holding the NUL that separates them.
         */
        fun handlesEvent(
            request: Int,
            delivery: FileDelivery,
            firstHandle: Int,
            files: List<HeldFile>,
            filter: Int,
        ): HostEvent.FileHandles? {
            if (files.any { it.name.isEmpty() || it.name.contains('\u0000') }) return null
            return HostEvent.FileHandles(
                nodeId = 0,
                handlerId = 0,
                request = request,
                status = FileDialogStatus.Chosen,
                filter = filter,
                delivery = delivery,
                firstHandle = firstHandle,
                names = files.joinToString("\u0000") { it.name },
                kinds = files.joinToString("") { if (it.folder) "d" else "f" },
            )
        }

        /** The bytes [event] takes in the event buffer. */
        fun encodedSize(event: HostEvent.FileHandles): Int =
            FileCallLimits.FILE_HANDLES_RECORD_BYTES + event.names.encodeToByteArray().size + event.kinds.length
    }
}
