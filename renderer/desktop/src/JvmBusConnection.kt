package dev.darkpyonix.composerust.ui.platform

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The session bus, for the native image on Linux: a Unix socket channel and a thread that
 * reads it.
 *
 * The thread only reads. It cuts the stream into messages, puts them on a queue and asks the
 * renderer for a frame; the frame takes them off the queue on the UI thread, where the
 * notification centre acts on them. Calls are written from the UI thread directly.
 */
internal class JvmBusConnection private constructor(
    private val channel: SocketChannel,
    private val wake: () -> Unit,
) : BusConnection {
    private val incoming = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var open = true

    private val reader = Thread(::readMessages, "compose-rust-dbus").apply { isDaemon = true }

    override fun send(message: ByteArray): Boolean = synchronized(channel) {
        if (!open) return false
        return try {
            val buffer = ByteBuffer.wrap(message)
            while (buffer.hasRemaining()) channel.write(buffer)
            true
        } catch (_: java.io.IOException) {
            close()
            false
        }
    }

    override fun receive(timeoutMillis: Int): ByteArray? =
        if (timeoutMillis <= 0) {
            incoming.poll()
        } else {
            incoming.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        }

    override fun close() {
        open = false
        try {
            channel.close()
        } catch (_: java.io.IOException) {
            // Already gone, which is what closing was for.
        }
    }

    private fun readMessages() {
        val header = ByteBuffer.allocate(16)
        try {
            while (open) {
                header.clear()
                if (!readFully(header)) break
                val length = dbusMessageLength(header.array()) ?: break
                val message = ByteBuffer.allocate(length)
                message.put(header.array())
                if (!readFully(message)) break
                incoming.put(message.array())
                // The frame is where the UI thread is, and the UI thread is where this is
                // acted on.
                wake()
            }
        } catch (_: java.io.IOException) {
            // The bus went away. What is already queued is still delivered.
        } finally {
            open = false
        }
    }

    private fun readFully(buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return false
        }
        return true
    }

    companion object {
        /** Connects and authenticates, or answers null where there is no session bus. */
        fun open(wake: () -> Unit): JvmBusConnection? {
            val user = userId() ?: return null
            val address = BusAddress.parse(System.getenv("DBUS_SESSION_BUS_ADDRESS"), user)
                ?: return null
            // The Java socket API has no way to name an abstract socket. Every current
            // desktop session publishes a path, so this costs the rare older one only.
            if (address.abstract) return null
            return try {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(address.path))
                val single = ByteBuffer.allocate(1)
                val authenticated = authenticateToBus(
                    user,
                    write = { bytes ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        true
                    },
                    // A byte at a time, so the line is all that is read: the messages after
                    // it belong to the reader.
                    readLine = {
                        val line = StringBuilder()
                        var done = false
                        while (!done) {
                            single.clear()
                            if (channel.read(single) < 0) break
                            val character = single.get(0).toInt().toChar()
                            if (character == '\n') done = true else if (character != '\r') line.append(character)
                        }
                        if (done) line.toString() else null
                    },
                )
                if (!authenticated) {
                    channel.close()
                    null
                } else {
                    JvmBusConnection(channel, wake).also { it.reader.start() }
                }
            } catch (_: Exception) {
                null
            }
        }

        /** This process's user id, from the kernel's own description of it. */
        private fun userId(): Long? = try {
            Files.readAllLines(Path.of("/proc/self/status"))
                .firstOrNull { it.startsWith("Uid:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
        } catch (_: Exception) {
            null
        }

        /** The executable's name, which is what a desktop entry for it is usually called. */
        fun applicationName(): String = try {
            Files.readSymbolicLink(Path.of("/proc/self/exe")).fileName.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
