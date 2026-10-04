package dev.darkpyonix.composerust.ui.platform

// The messages an accessibility client sends this process, and the ones this process answers
// with.
//
// `DBusNotifications.kt` already speaks the bus's wire format, but only as a caller: it builds
// method calls and reads the few header fields a reply has. An accessible object is the other
// end of a call, so what is needed here is the reverse: read the path, interface, member and
// signature a call arrived with, and build a return, an error or a signal. The reader and the
// writer are the same ones, so there is one implementation of the format's alignment rules.

/** A method call as it arrived. */
internal class IncomingCall(
    val serial: Long,
    val sender: String?,
    val path: String,
    val interfaceName: String?,
    val member: String,
    val signature: String,
    /** True where the caller said it wants no answer. */
    val noReply: Boolean,
    private val bytes: ByteArray,
    private val bodyStart: Int,
) {
    /** A reader at the start of the arguments. */
    fun arguments(): DBusReader = DBusReader(bytes, bodyStart)
}

internal object AtspiWire {
    private const val FLAG_NO_REPLY_EXPECTED = 1

    /** Null for anything that is not a method call this side can read. */
    fun parseCall(bytes: ByteArray): IncomingCall? {
        val length = dbusMessageLength(bytes) ?: return null
        if (bytes.size < length) return null
        if (bytes[1].toInt() != DBusMessage.METHOD_CALL) return null
        val flags = bytes[2].toInt()
        val serial = DBusReader(bytes, 8).u32()
        val fieldsLength = DBusReader(bytes, 12).u32().toInt()
        val fieldsEnd = 16 + fieldsLength
        val reader = DBusReader(bytes, 16)
        var path: String? = null
        var interfaceName: String? = null
        var member: String? = null
        var sender: String? = null
        var signature = ""
        try {
            while (reader.position < fieldsEnd) {
                reader.align(8)
                if (reader.position >= fieldsEnd) break
                val code = reader.byte()
                val kind = reader.signature()
                val value: Any? = when (kind) {
                    "s", "o" -> reader.string()
                    "g" -> reader.signature()
                    "u" -> reader.u32()
                    "y" -> reader.byte()
                    else -> return null
                }
                when (code) {
                    1 -> path = value as? String
                    2 -> interfaceName = value as? String
                    3 -> member = value as? String
                    7 -> sender = value as? String
                    8 -> signature = value as? String ?: ""
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return IncomingCall(
            serial = serial,
            sender = sender,
            path = path ?: return null,
            interfaceName = interfaceName,
            member = member ?: return null,
            signature = signature,
            noReply = flags and FLAG_NO_REPLY_EXPECTED != 0,
            bytes = bytes,
            bodyStart = (fieldsEnd + 7) / 8 * 8,
        )
    }

    /** The answer to [call], carrying whatever [body] writes, which [signature] describes. */
    fun methodReturn(
        serial: Int,
        call: IncomingCall,
        signature: String?,
        body: DBusWriter.() -> Unit,
    ): ByteArray = build(DBusMessage.METHOD_RETURN, serial, signature, body) {
        field(5, "u") { u32(call.serial) }
        call.sender?.let { field(6, "s") { string(it) } }
    }

    /** The refusal of [call]. [name] is the bus's own name for the kind of mistake. */
    fun error(serial: Int, call: IncomingCall, name: String, text: String): ByteArray =
        build(DBusMessage.ERROR, serial, "s", { string(text) }) {
            field(4, "s") { string(name) }
            field(5, "u") { u32(call.serial) }
            call.sender?.let { field(6, "s") { string(it) } }
        }

    /** A signal from [path], sent to nobody in particular: whoever asked for it receives it. */
    fun signal(
        serial: Int,
        path: String,
        interfaceName: String,
        member: String,
        signature: String?,
        body: DBusWriter.() -> Unit,
    ): ByteArray = build(DBusMessage.SIGNAL, serial, signature, body) {
        field(1, "o") { string(path) }
        field(2, "s") { string(interfaceName) }
        field(3, "s") { string(member) }
    }

    private fun build(
        type: Int,
        serial: Int,
        signature: String?,
        body: DBusWriter.() -> Unit,
        fields: DBusWriter.() -> Unit,
    ): ByteArray {
        val content = DBusWriter().apply(body).bytes()
        val message = DBusWriter()
        message.byte('l'.code)
        message.byte(type)
        // No reply is expected to a return, an error or a signal.
        message.byte(FLAG_NO_REPLY_EXPECTED)
        message.byte(1)
        message.u32(content.size.toLong())
        message.u32(serial.toLong())
        message.array(8) {
            fields()
            if (signature != null) field(8, "g") { signature(signature) }
        }
        message.align(8)
        message.raw(content)
        return message.bytes()
    }

    private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) {
        struct {
            byte(code)
            signature(signature)
            value()
        }
    }
}

/** An object reference as AT-SPI writes one: the bus name that owns it, and its path. */
internal fun DBusWriter.reference(name: String, path: String) = struct {
    string(name)
    string(path)
}

/** A boolean on the wire is a 32-bit word. */
internal fun DBusWriter.boolean(value: Boolean) = u32(if (value) 1L else 0L)

/** A 16-bit signed integer, which the writer has no method for. */
internal fun DBusWriter.i16(value: Int) {
    align(2)
    byte(value and 0xff)
    byte((value shr 8) and 0xff)
}

/** An IEEE double, eight bytes aligned to eight. */
internal fun DBusWriter.double(value: Double) {
    align(8)
    val bits = value.toRawBits()
    for (shift in 0 until 8) byte(((bits ushr (8 * shift)) and 0xff).toInt())
}

/** A variant: the type of what follows, then the value itself. */
internal fun DBusWriter.variant(signature: String, value: DBusWriter.() -> Unit) {
    signature(signature)
    value()
}

/** One entry of a dictionary of strings to variants. */
internal fun DBusWriter.entry(key: String, signature: String, value: DBusWriter.() -> Unit) {
    struct {
        string(key)
        variant(signature, value)
    }
}

/** Reads a 32-bit signed integer. */
internal fun DBusReader.i32(): Int = u32().toInt()
