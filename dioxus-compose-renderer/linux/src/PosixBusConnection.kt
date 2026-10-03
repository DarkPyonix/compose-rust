@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dioxus.compose.ui.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.AF_UNIX
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.connect
import platform.posix.getenv
import platform.posix.getuid
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.readlink
import platform.posix.sa_family_tVar
import platform.posix.sockaddr_un
import platform.posix.socket
import platform.posix.write

/**
 * The session bus, for the Kotlin/Native renderer on Linux: a Unix socket read on the
 * window's own thread.
 *
 * There is no reader thread here, because this renderer has one thread and the window's loop
 * already turns on it several dozen times a second. Each turn asks for whatever the bus has
 * said without waiting, and a call waits for its own answer, so nothing on this socket is
 * ever read from two places.
 */
internal class PosixBusConnection private constructor(private val socket: Int) : BusConnection {
    /** Bytes read that do not yet make a whole message. */
    private var pending = ByteArray(0)
    private var open = true

    override fun send(message: ByteArray): Boolean {
        if (!open) return false
        var offset = 0
        while (offset < message.size) {
            val written = message.usePinned { pinned ->
                write(socket, pinned.addressOf(offset), (message.size - offset).convert())
            }
            if (written <= 0) {
                close()
                return false
            }
            offset += written.toInt()
        }
        return true
    }

    override fun receive(timeoutMillis: Int): ByteArray? {
        takeMessage()?.let { return it }
        if (!open) return null
        val ready = memScoped {
            val watched = alloc<pollfd>()
            watched.fd = socket
            watched.events = POLLIN.toShort()
            poll(watched.ptr, 1.convert(), timeoutMillis.coerceAtLeast(0))
        }
        if (ready <= 0) return null
        val chunk = ByteArray(CHUNK_BYTES)
        val count = chunk.usePinned { pinned -> read(socket, pinned.addressOf(0), CHUNK_BYTES.convert()) }
        if (count <= 0) {
            close()
            return null
        }
        pending += chunk.copyOf(count.toInt())
        return takeMessage()
    }

    override fun close() {
        if (!open) return
        open = false
        platform.posix.close(socket)
    }

    private fun takeMessage(): ByteArray? {
        if (pending.size < 16) return null
        val length = dbusMessageLength(pending.copyOf(16))
        if (length == null) {
            // Not a stream this side can cut into messages any more.
            close()
            return null
        }
        if (pending.size < length) return null
        val message = pending.copyOf(length)
        pending = pending.copyOfRange(length, pending.size)
        return message
    }

    companion object {
        private const val CHUNK_BYTES = 4096

        /** Connects and authenticates, or answers null where there is no session bus. */
        fun open(): PosixBusConnection? {
            val user = getuid().toLong()
            val address = BusAddress.parse(getenv("DBUS_SESSION_BUS_ADDRESS")?.toKString(), user)
                ?: return null
            val socket = socket(AF_UNIX, SOCK_STREAM, 0)
            if (socket < 0) return null
            val connected = memScoped {
                val target = alloc<sockaddr_un>()
                target.sun_family = AF_UNIX.convert()
                val path = address.path.encodeToByteArray()
                // An abstract name starts with a zero byte and is not terminated; a path is
                // terminated and does not start with one.
                val start = if (address.abstract) 1 else 0
                if (start + path.size + 1 > SUN_PATH_BYTES) return@memScoped -1
                if (address.abstract) target.sun_path[0] = 0
                for (index in path.indices) target.sun_path[start + index] = path[index]
                if (!address.abstract) target.sun_path[path.size] = 0
                val length = sizeOf<sa_family_tVar>() + start + path.size +
                    (if (address.abstract) 0 else 1)
                connect(socket, target.ptr.reinterpret(), length.convert())
            }
            if (connected != 0) {
                platform.posix.close(socket)
                return null
            }
            val connection = PosixBusConnection(socket)
            val authenticated = authenticateToBus(
                user,
                write = connection::send,
                // A byte at a time, so the line is all that is read: the messages after it
                // are the connection's to cut up.
                readLine = {
                    val line = StringBuilder()
                    val single = ByteArray(1)
                    var done = false
                    while (!done) {
                        val count = single.usePinned { read(socket, it.addressOf(0), 1.convert()) }
                        if (count <= 0) break
                        val character = single[0].toInt().toChar()
                        if (character == '\n') done = true else if (character != '\r') line.append(character)
                    }
                    if (done) line.toString() else null
                },
            )
            if (!authenticated) {
                connection.close()
                return null
            }
            return connection
        }

        /** The executable's name, which is what a desktop entry for it is usually called. */
        fun applicationName(): String = memScoped {
            val buffer = allocArray<ByteVar>(PATH_BYTES)
            val length = readlink("/proc/self/exe", buffer, (PATH_BYTES - 1).convert())
            if (length <= 0) return@memScoped ""
            buffer[length.toInt()] = 0
            buffer.toKString().substringAfterLast('/')
        }

        /** The size of `sun_path` in Linux's `sockaddr_un`. */
        private const val SUN_PATH_BYTES = 108
        private const val PATH_BYTES = 4096
    }
}
