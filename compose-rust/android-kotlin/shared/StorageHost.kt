package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.BatchBytes
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.StorageOp
import dev.darkpyonix.composerust.runtime.EventDispatcher

/**
 * One storage operation the Host asked this side to carry out.
 *
 * [store] is the application identifier and names the database, [key] the value (empty for
 * [StorageOp.Keys]). [data] is the value to write, read in place in the Host's batch: it is
 * valid only during [StoragePlatform.start], so a platform hands it on there or copies it.
 */
class StorageRequest(
    val request: Int,
    val op: StorageOp,
    val store: String,
    val key: String,
    val data: BatchBytes,
)

/**
 * How one operation ended. [data] is the value read, or the keys joined by NUL, and empty
 * for an operation that only writes. For [IoStatus.Unavailable] and [IoStatus.Failed] it may
 * hold a sentence in UTF-8 that says why, which the Host shows the application.
 */
class StorageAnswer(val status: IoStatus, val data: ByteArray = ByteArray(0)) {
    companion object {
        fun saying(status: IoStatus, reason: String): StorageAnswer =
            StorageAnswer(status, reason.encodeToByteArray())

        /**
         * A value too large to carry back. Its length travels instead of it, as four
         * little-endian bytes, so the Host can say how large it was.
         */
        fun tooLarge(length: Int): StorageAnswer = StorageAnswer(
            IoStatus.TooLarge,
            byteArrayOf(
                length.toByte(),
                (length ushr 8).toByte(),
                (length ushr 16).toByte(),
                (length ushr 24).toByte(),
            ),
        )
    }
}

/**
 * One platform's way of carrying out storage operations for the Host.
 *
 * Only the web has one: there the browser's storage can only be reached from this side. On
 * every other platform the Host reads and writes its own files and never sends a request
 * here.
 */
interface StoragePlatform {
    /**
     * Starts [request]. Called on the UI thread while the batch that carries it is being
     * applied, which is the only time [StorageRequest.data] can be read. [answer] is called
     * once, at once or later, on the UI thread; a second call is ignored.
     *
     * Operations must be carried out in the order they are started, at least for one key:
     * the Host promises an application that a read sees the write asked before it.
     */
    fun start(request: StorageRequest, answer: (StorageAnswer) -> Unit)
}

/**
 * A Renderer with no storage of its own: every request is answered unavailable. Not a
 * protocol error, because the Host still gets its one answer and the application its error.
 */
object NoStorage : StoragePlatform {
    override fun start(request: StorageRequest, answer: (StorageAnswer) -> Unit) = answer(
        StorageAnswer.saying(
            IoStatus.Unavailable,
            "this Renderer keeps no storage; on this platform the Host keeps it in files",
        ),
    )
}

/**
 * The storage this process carries out operations with. The web's entry point installs its
 * own before the Host starts.
 */
object Storage {
    var platform: StoragePlatform = NoStorage

    /**
     * The most bytes one answer may carry. An answer with more becomes a too-large answer
     * that carries the length instead. The web sets it to what its event buffer holds.
     */
    var maxAnswerBytes: Int = Int.MAX_VALUE
}

/**
 * The storage requests the Host made, and the one route their answers take back.
 *
 * A request number waits for one answer only, and a second request under a number still
 * waiting is refused and reported without disturbing the first. Answers are delivered at
 * the top of a frame, never inside the batch that asked: the Host cannot be entered while it
 * is being applied. [wake] asks for that frame.
 */
class StorageCenter(
    private val platform: () -> StoragePlatform = { Storage.platform },
    private val maxAnswerBytes: () -> Int = { Storage.maxAnswerBytes },
) {
    enum class Accepted { Accepted, Duplicate }

    /** Requests started and not answered yet. */
    private val running = HashSet<Int>()

    /** Answers ready to go to the Host, in the order they finished. */
    private val finished = ArrayList<HostEvent.IoAnswer>()

    private var dispatcher: EventDispatcher? = null
    private var draining = false

    /** How an answer asks for the frame that delivers it. */
    var wake: () -> Unit = {}

    /** Where a platform that threw is reported. Tests replace it to read what was said. */
    var log: (String) -> Unit = { line -> println(line) }

    /** How many requests are waiting for their answer, or for it to be delivered. */
    internal val pendingCount: Int get() = running.size + finished.size

    /** Starts the operation, unless its number is already waiting for an answer. */
    fun request(mutation: Mutation.StorageCall): Accepted {
        val number = mutation.request
        if (!running.add(number)) return Accepted.Duplicate
        val request = StorageRequest(number, mutation.op, mutation.store, mutation.key, mutation.data)
        try {
            platform().start(request) { answer -> finish(number, answer) }
        } catch (failure: Throwable) {
            // The key and the value are the application's data, so only the request is named.
            log("compose-rust: storage request $number could not start (${failure::class.simpleName})")
            finish(number, StorageAnswer.saying(IoStatus.Failed, "the Renderer could not start it"))
        }
        return Accepted.Accepted
    }

    /** Connects the centre to the Host, once its first batch has been applied. */
    fun attach(host: EventDispatcher) {
        dispatcher = host
        drain()
    }

    /** The process is ending. Nothing is answered any more. */
    fun shutdown() {
        dispatcher = null
        running.clear()
        finished.clear()
    }

    /** Hands every finished answer to the Host, oldest first. */
    fun drain() {
        val host = dispatcher ?: return
        if (draining) return
        draining = true
        try {
            while (finished.isNotEmpty()) host.dispatch(finished.removeAt(0))
        } finally {
            draining = false
        }
    }

    /** Records the one answer [request] gets. A second call for it finds nothing running. */
    private fun finish(request: Int, answer: StorageAnswer) {
        if (!running.remove(request)) return
        val carried = if (answer.data.size > maxAnswerBytes()) StorageAnswer.tooLarge(answer.data.size) else answer
        finished += HostEvent.IoAnswer(0, 0, request, carried.status, carried.data)
        wake()
    }
}
