package dioxus.compose.ui.platform

import dioxus.compose.protocol.NotificationImportance
import dioxus.compose.protocol.NotificationPermission

/**
 * One connection to the session bus, already authenticated, that carries whole messages.
 *
 * Two renderers open one: the native image on Linux, through a Java socket channel, and the
 * Kotlin/Native renderer, through the C library's socket calls. Everything above this line
 * is the same for both, which is why it is written once here.
 */
interface BusConnection {
    /** Sends one complete message. False when the connection has gone. */
    fun send(message: ByteArray): Boolean

    /**
     * The next complete message, waiting at most [timeoutMillis]; null when none arrived in
     * that time or the connection has gone. Zero does not wait at all.
     */
    fun receive(timeoutMillis: Int): ByteArray?

    fun close()
}

/**
 * Notifications on a Linux desktop, through `org.freedesktop.Notifications` on the session
 * bus: the interface GNOME, KDE, and the notification daemons of the lighter desktops all
 * answer.
 *
 * There is no permission to ask for. A session with a daemon shows what it is given, so the
 * answer is granted where the name has an owner and unsupported where it has none.
 *
 * Buttons are offered only when the daemon says it shows them. The body is offered as the
 * action the specification calls "default", which is how a press on the notification itself
 * is reported.
 */
class DBusNotifications(
    private val open: () -> BusConnection?,
    private val bringToFront: () -> Unit,
    /** What the daemon is told the notifications are from, and the desktop entry it names. */
    private val applicationName: String,
) : NotificationPlatform {
    private var connection: BusConnection? = null
    private var reports: NotificationReports? = null
    private var serial = 0
    private var buttons = false
    private val idOfKey = mutableMapOf<String, Long>()
    private val keyOfId = mutableMapOf<Long, String>()

    override fun start(reports: NotificationReports) {
        this.reports = reports
        reports.permission(connect())
    }

    override fun requestPermission() {
        reports?.permission(if (connection != null) NotificationPermission.Granted else connect())
    }

    override fun refreshPermission() {
        val reports = reports ?: return
        val alive = connection?.let { capabilities(it) } != null
        reports.permission(if (alive) NotificationPermission.Granted else connect())
    }

    override fun post(notification: PlatformNotification) {
        val connection = connection ?: return
        val body = DBusWriter().apply {
            string(applicationName)
            u32(idOfKey[notification.key] ?: 0L)
            string("")
            string(notification.title)
            string(notification.body)
            array(4) {
                // The body as an action, so a press on it is reported. Its label is never
                // shown; the specification says the default action has none of its own.
                string(ACTION_BODY)
                string("")
                if (buttons) {
                    if (notification.action1.isNotEmpty()) {
                        string(ACTION_1)
                        string(notification.action1)
                    }
                    if (notification.action2.isNotEmpty()) {
                        string(ACTION_2)
                        string(notification.action2)
                    }
                }
            }
            array(8) {
                hint("urgency", "y") {
                    byte(if (notification.importance == NotificationImportance.Urgent) 2 else 1)
                }
                // The nearest this interface has to a channel. A daemon that groups by it,
                // or lets a user silence one, does it by this.
                if (notification.channel.isNotEmpty()) {
                    hint("category", "s") { string(notification.channel) }
                }
                if (applicationName.isNotEmpty()) {
                    hint("desktop-entry", "s") { string(applicationName) }
                }
            }
            // The daemon's own default for how long it stays on screen. Where it goes after
            // that is the daemon's history, which is where a notification waits to be read.
            i32(-1)
        }
        val reply = call(connection, NOTIFICATIONS, "Notify", "susssasa{sv}i", body.bytes())
            ?: return
        val id = try {
            reply.body().u32()
        } catch (_: IndexOutOfBoundsException) {
            return
        }
        idOfKey[notification.key]?.let { keyOfId.remove(it) }
        idOfKey[notification.key] = id
        keyOfId[id] = notification.key
    }

    override fun withdraw(key: String) {
        val connection = connection ?: return
        val id = idOfKey.remove(key) ?: return
        keyOfId.remove(id)
        call(connection, NOTIFICATIONS, "CloseNotification", "u", DBusWriter().apply { u32(id) }.bytes())
    }

    override fun processEnding() {
        val connection = connection ?: return
        // Taken back with the process: a notification left behind would point at work no
        // process knows about any more.
        for (id in keyOfId.keys.toList()) {
            call(connection, NOTIFICATIONS, "CloseNotification", "u", DBusWriter().apply { u32(id) }.bytes())
        }
        idOfKey.clear()
        keyOfId.clear()
        connection.close()
        this.connection = null
    }

    /** Reads whatever the daemon has said since the last turn, without waiting. */
    override fun pump() {
        val connection = connection ?: return
        while (true) {
            val message = connection.receive(0) ?: return
            handle(DBusMessage.parse(message) ?: continue)
        }
    }

    private fun connect(): NotificationPermission {
        connection?.close()
        connection = null
        val opened = open() ?: return NotificationPermission.Unsupported
        connection = opened
        val hello = call(opened, BUS, "Hello", null, ByteArray(0))
        val capabilities = hello?.let { capabilities(opened) }
        if (capabilities == null) {
            opened.close()
            connection = null
            return NotificationPermission.Unsupported
        }
        buttons = "actions" in capabilities
        // Presses and closings are signals. Asked for by interface, and only those this
        // process posted are acted on.
        call(
            opened,
            BUS,
            "AddMatch",
            "s",
            DBusWriter().apply {
                string("type='signal',interface='org.freedesktop.Notifications'")
            }.bytes(),
        )
        return NotificationPermission.Granted
    }

    /**
     * What the daemon can do, or null where there is no daemon: the bus answers a call to a
     * name with no owner with an error, and an error is what this reads as "unsupported".
     */
    private fun capabilities(connection: BusConnection): List<String>? {
        val reply = call(connection, NOTIFICATIONS, "GetCapabilities", null, ByteArray(0))
            ?: return null
        val reader = reply.body()
        val strings = mutableListOf<String>()
        return try {
            reader.array(4) { strings += reader.string() }
            strings
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /**
     * Sends one call and waits for its answer, handling whatever else arrives meanwhile.
     *
     * The wait is short. A daemon answers in milliseconds, and this is the UI thread; one
     * that does not answer within the limit is treated as not being there.
     */
    private fun call(
        connection: BusConnection,
        destination: Destination,
        member: String,
        signature: String?,
        body: ByteArray,
    ): DBusMessage? {
        serial += 1
        val sent = serial
        if (!connection.send(DBusMessage.methodCall(sent, destination, member, signature, body))) {
            dropConnection()
            return null
        }
        // Counted in waits rather than measured, because nothing here owns a clock that both
        // targets share. A wait that ends early because a signal arrived still counts, which
        // makes the limit an upper bound on the time spent and not an exact one.
        var waits = CALL_TIMEOUT_MILLIS / RECEIVE_SLICE_MILLIS
        while (waits-- > 0) {
            val bytes = connection.receive(RECEIVE_SLICE_MILLIS)
            val message = DBusMessage.parse(bytes ?: continue) ?: continue
            if (message.replySerial == sent.toLong()) {
                return if (message.type == DBusMessage.METHOD_RETURN) message else null
            }
            handle(message)
        }
        return null
    }

    private fun dropConnection() {
        connection?.close()
        connection = null
        reports?.permission(NotificationPermission.Unsupported)
    }

    private fun handle(message: DBusMessage) {
        try {
            handleSignal(message)
        } catch (_: IndexOutOfBoundsException) {
            // A signal whose body is not the shape the interface says. Not ours to act on.
        }
    }

    private fun handleSignal(message: DBusMessage) {
        if (message.type != DBusMessage.SIGNAL || message.interfaceName != NOTIFICATIONS.interfaceName) return
        val reader = message.body()
        when (message.member) {
            "ActionInvoked" -> {
                val id = reader.u32()
                val action = when (reader.string()) {
                    ACTION_BODY -> 0
                    ACTION_1 -> 1
                    ACTION_2 -> 2
                    else -> return
                }
                val key = keyOfId[id] ?: return
                if (action == 0) bringToFront()
                reports?.activated(key, action)
            }
            "NotificationClosed" -> {
                val id = reader.u32()
                keyOfId.remove(id)?.let { idOfKey.remove(it) }
            }
        }
    }

    private fun DBusWriter.hint(name: String, signature: String, value: DBusWriter.() -> Unit) {
        struct {
            string(name)
            signature(signature)
            value()
        }
    }

    private companion object {
        const val ACTION_BODY = "default"
        const val ACTION_1 = "dioxus-compose.action.1"
        const val ACTION_2 = "dioxus-compose.action.2"
        const val CALL_TIMEOUT_MILLIS = 2_000
        const val RECEIVE_SLICE_MILLIS = 100
        val BUS = Destination("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")
        val NOTIFICATIONS = Destination(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications",
        )
    }
}

/** Who a call goes to. */
class Destination(val name: String, val path: String, val interfaceName: String)

/**
 * The address of the session bus: a socket path, and whether it is in the abstract namespace.
 *
 * Read from `DBUS_SESSION_BUS_ADDRESS`, which can list several addresses and several kinds of
 * transport. The first Unix socket is taken; anything else is skipped, because a desktop
 * session always offers one. With no address at all, systemd's per-user socket is where the
 * bus is.
 */
data class BusAddress(val path: String, val abstract: Boolean) {
    companion object {
        fun parse(value: String?, userId: Long): BusAddress? {
            if (value.isNullOrEmpty()) return BusAddress("/run/user/$userId/bus", abstract = false)
            for (entry in value.split(';')) {
                if (!entry.startsWith("unix:")) continue
                for (pair in entry.removePrefix("unix:").split(',')) {
                    val (key, raw) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    when (key) {
                        "path" -> return BusAddress(unescape(raw), abstract = false)
                        "abstract" -> return BusAddress(unescape(raw), abstract = true)
                    }
                }
            }
            return null
        }

        /** Addresses escape bytes as `%xx`. */
        private fun unescape(raw: String): String {
            if ('%' !in raw) return raw
            val out = mutableListOf<Byte>()
            var index = 0
            val bytes = raw.encodeToByteArray()
            while (index < bytes.size) {
                if (bytes[index] == '%'.code.toByte() && index + 2 < bytes.size) {
                    out += raw.substring(index + 1, index + 3).toInt(16).toByte()
                    index += 3
                } else {
                    out += bytes[index]
                    index += 1
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}

/**
 * The bus's authentication, done once before the first message: this process says who it is
 * by its user id, which the bus checks against the socket's peer credentials.
 *
 * [write] sends bytes and [readLine] answers one line without its line ending, or null when
 * the connection closed.
 */
fun authenticateToBus(userId: Long, write: (ByteArray) -> Boolean, readLine: () -> String?): Boolean {
    val hexUser = userId.toString().encodeToByteArray().joinToString("") {
        it.toInt().and(0xff).toString(16).padStart(2, '0')
    }
    if (!write(byteArrayOf(0))) return false
    if (!write("AUTH EXTERNAL $hexUser\r\n".encodeToByteArray())) return false
    val answer = readLine() ?: return false
    if (!answer.startsWith("OK ")) return false
    return write("BEGIN\r\n".encodeToByteArray())
}

/**
 * How long the message starting with these sixteen bytes is, or null when they are not the
 * start of one this side reads. Used by both connections to cut a stream into messages.
 */
fun dbusMessageLength(header: ByteArray): Int? {
    if (header.size < 16 || header[0] != 'l'.code.toByte()) return null
    val body = DBusReader(header, 4).u32()
    val fields = DBusReader(header, 12).u32()
    val end = 16 + fields
    val padded = (end + 7) / 8 * 8
    val total = padded + body
    return if (total in 16..MAX_MESSAGE_BYTES) total.toInt() else null
}

/** The bus refuses messages larger than this, so a header that claims more is garbage. */
private const val MAX_MESSAGE_BYTES = 128L * 1024 * 1024

/** One message, parsed as far as this side needs. */
class DBusMessage private constructor(
    private val bytes: ByteArray,
    val type: Int,
    val serial: Long,
    val replySerial: Long?,
    val interfaceName: String?,
    val member: String?,
    private val bodyStart: Int,
) {
    /** A reader at the start of the body. */
    fun body(): DBusReader = DBusReader(bytes, bodyStart)

    companion object {
        const val METHOD_CALL = 1
        const val METHOD_RETURN = 2
        const val ERROR = 3
        const val SIGNAL = 4

        fun methodCall(
            serial: Int,
            destination: Destination,
            member: String,
            signature: String?,
            body: ByteArray,
        ): ByteArray {
            val message = DBusWriter()
            message.byte('l'.code)
            message.byte(METHOD_CALL)
            message.byte(0)
            message.byte(1)
            message.u32(body.size.toLong())
            message.u32(serial.toLong())
            message.array(8) {
                field(1, "o") { string(destination.path) }
                field(2, "s") { string(destination.interfaceName) }
                field(3, "s") { string(member) }
                field(6, "s") { string(destination.name) }
                if (signature != null) field(8, "g") { signature(signature) }
            }
            message.align(8)
            message.raw(body)
            return message.bytes()
        }

        private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) {
            struct {
                byte(code)
                signature(signature)
                value()
            }
        }

        /** Null for anything this side does not read: the wrong byte order, or a truncation. */
        fun parse(bytes: ByteArray): DBusMessage? {
            val length = dbusMessageLength(bytes) ?: return null
            if (bytes.size < length) return null
            val type = bytes[1].toInt()
            val reader = DBusReader(bytes, 12)
            val fieldsLength = reader.u32().toInt()
            val fieldsEnd = 16 + fieldsLength
            var replySerial: Long? = null
            var interfaceName: String? = null
            var member: String? = null
            reader.position = 16
            try {
                while (reader.position < fieldsEnd) {
                    reader.align(8)
                    if (reader.position >= fieldsEnd) break
                    val code = reader.byte()
                    val signature = reader.signature()
                    val value: Any? = when (signature) {
                        "s", "o" -> reader.string()
                        "g" -> reader.signature()
                        "u" -> reader.u32()
                        "y" -> reader.byte()
                        else -> return null
                    }
                    when (code) {
                        2 -> interfaceName = value as? String
                        3 -> member = value as? String
                        5 -> replySerial = value as? Long
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                return null
            }
            val bodyStart = (fieldsEnd + 7) / 8 * 8
            return DBusMessage(
                bytes,
                type,
                DBusReader(bytes, 8).u32(),
                replySerial,
                interfaceName,
                member,
                bodyStart,
            )
        }
    }
}

/** Writes the bus's little-endian wire format, with its alignment rules. */
class DBusWriter {
    private var buffer = ByteArray(256)
    private var size = 0

    fun bytes(): ByteArray = buffer.copyOf(size)

    fun raw(bytes: ByteArray) {
        bytes.forEach { put(it) }
    }

    fun align(boundary: Int) {
        while (size % boundary != 0) put(0)
    }

    fun byte(value: Int) = put(value.toByte())

    fun u32(value: Long) {
        align(4)
        for (shift in 0 until 4) put((value ushr (8 * shift)).toByte())
    }

    fun i32(value: Int) = u32(value.toLong() and 0xffff_ffffL)

    fun string(value: String) {
        val bytes = value.encodeToByteArray()
        u32(bytes.size.toLong())
        raw(bytes)
        put(0)
    }

    fun signature(value: String) {
        val bytes = value.encodeToByteArray()
        put(bytes.size.toByte())
        raw(bytes)
        put(0)
    }

    /** An array whose elements align to [elementAlignment]. The length excludes the padding. */
    fun array(elementAlignment: Int, elements: DBusWriter.() -> Unit) {
        align(4)
        val lengthAt = size
        u32(0)
        align(elementAlignment)
        val start = size
        elements()
        val length = (size - start).toLong()
        for (shift in 0 until 4) buffer[lengthAt + shift] = (length ushr (8 * shift)).toByte()
    }

    fun struct(fields: DBusWriter.() -> Unit) {
        align(8)
        fields()
    }

    private fun put(value: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = value
    }
}

/** Reads the bus's little-endian wire format from [position] on. */
class DBusReader(private val bytes: ByteArray, var position: Int) {
    fun align(boundary: Int) {
        while (position % boundary != 0) position++
    }

    fun byte(): Int = bytes[position++].toInt() and 0xff

    fun u32(): Long {
        align(4)
        var value = 0L
        for (shift in 0 until 4) value = value or ((bytes[position + shift].toLong() and 0xff) shl (8 * shift))
        position += 4
        return value
    }

    fun string(): String {
        val length = u32().toInt()
        val value = bytes.copyOfRange(position, position + length).decodeToString()
        position += length + 1
        return value
    }

    fun signature(): String {
        val length = byte()
        val value = bytes.copyOfRange(position, position + length).decodeToString()
        position += length + 1
        return value
    }

    fun array(elementAlignment: Int, element: () -> Unit) {
        val length = u32().toInt()
        align(elementAlignment)
        val end = position + length
        while (position < end) element()
    }
}
