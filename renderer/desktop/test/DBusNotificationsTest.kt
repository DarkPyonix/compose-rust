package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission
import dev.darkpyonix.composerust.ui.platform.BusAddress
import dev.darkpyonix.composerust.ui.platform.BusConnection
import dev.darkpyonix.composerust.ui.platform.DBusMessage
import dev.darkpyonix.composerust.ui.platform.DBusNotifications
import dev.darkpyonix.composerust.ui.platform.DBusWriter
import dev.darkpyonix.composerust.ui.platform.NotificationReports
import dev.darkpyonix.composerust.ui.platform.PlatformNotification
import dev.darkpyonix.composerust.ui.platform.authenticateToBus
import dev.darkpyonix.composerust.ui.platform.dbusMessageLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One Notify call as the daemon received it. */
private data class Notify(
    val replacesId: Long,
    val title: String,
    val body: String,
    val actions: List<String>,
    val hints: Map<String, Any>,
)

/**
 * A notification daemon on a bus, answering the calls the renderer makes the way
 * `org.freedesktop.Notifications` does.
 */
private class FakeDaemon(
    private val present: Boolean = true,
    private val capabilities: List<String> = listOf("body", "actions"),
) : BusConnection {
    private val outgoing = ArrayDeque<ByteArray>()
    private var serial = 100L
    private var nextId = 1L
    val notified = mutableListOf<Notify>()
    val closed = mutableListOf<Long>()

    override fun send(message: ByteArray): Boolean {
        val call = DBusMessage.parse(message) ?: return true
        val interfaceName = call.interfaceName
        when {
            interfaceName == "org.freedesktop.DBus" && call.member == "Hello" ->
                reply(call.serial, "s") { string(":1.42") }
            interfaceName == "org.freedesktop.DBus" -> reply(call.serial, null) {}
            !present -> fail(call.serial)
            call.member == "GetCapabilities" ->
                reply(call.serial, "as") { array(4) { capabilities.forEach { string(it) } } }
            call.member == "Notify" -> {
                val reader = call.body()
                reader.string()
                val replaces = reader.u32()
                reader.string()
                val title = reader.string()
                val body = reader.string()
                val actions = mutableListOf<String>()
                reader.array(4) { actions += reader.string() }
                val hints = mutableMapOf<String, Any>()
                reader.array(8) {
                    reader.align(8)
                    val name = reader.string()
                    hints[name] = when (reader.signature()) {
                        "y" -> reader.byte()
                        "s" -> reader.string()
                        else -> error("unexpected hint type")
                    }
                }
                notified += Notify(replaces, title, body, actions, hints)
                val id = if (replaces != 0L) replaces else nextId++
                reply(call.serial, "u") { u32(id) }
            }
            call.member == "CloseNotification" -> {
                val id = call.body().u32()
                closed += id
                reply(call.serial, null) {}
                signal("NotificationClosed", "uu") {
                    u32(id)
                    u32(3)
                }
            }
        }
        return true
    }

    override fun receive(timeoutMillis: Int): ByteArray? = outgoing.removeFirstOrNull()

    override fun close() = Unit

    /** The daemon saying the user pressed something. */
    fun press(id: Long, action: String) = signal("ActionInvoked", "us") {
        u32(id)
        string(action)
    }

    private fun reply(to: Long, signature: String?, body: DBusWriter.() -> Unit) =
        message(2, signature, DBusWriter().apply(body).bytes()) {
            field(5, "u") { u32(to) }
        }

    private fun fail(to: Long) = message(3, null, ByteArray(0)) {
        field(4, "s") { string("org.freedesktop.DBus.Error.ServiceUnknown") }
        field(5, "u") { u32(to) }
    }

    private fun signal(member: String, signature: String, body: DBusWriter.() -> Unit) =
        message(4, signature, DBusWriter().apply(body).bytes()) {
            field(1, "o") { string("/org/freedesktop/Notifications") }
            field(2, "s") { string("org.freedesktop.Notifications") }
            field(3, "s") { string(member) }
        }

    private fun message(
        type: Int,
        signature: String?,
        body: ByteArray,
        fields: DBusWriter.() -> Unit,
    ) {
        val message = DBusWriter()
        message.byte('l'.code)
        message.byte(type)
        message.byte(0)
        message.byte(1)
        message.u32(body.size.toLong())
        message.u32(serial++)
        message.array(8) {
            fields()
            if (signature != null) field(8, "g") { signature(signature) }
        }
        message.align(8)
        message.raw(body)
        outgoing.addLast(message.bytes())
    }

    private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) {
        struct {
            byte(code)
            signature(signature)
            value()
        }
    }
}

private class Heard : NotificationReports {
    val permissions = mutableListOf<NotificationPermission>()
    val presses = mutableListOf<Pair<String, Int>>()

    override fun permission(state: NotificationPermission) {
        permissions += state
    }

    override fun activated(key: String, action: Int) {
        presses += key to action
    }
}

private fun notification(
    key: String,
    title: String = "Session finished",
    channel: String = "",
    action1: String = "",
    action2: String = "",
    importance: NotificationImportance = NotificationImportance.Normal,
) = PlatformNotification(key, title, "214 tests passed", channel, action1, action2, importance)

/**
 * The Linux notification centre, against a daemon that answers the way the interface says.
 *
 * Whether GNOME draws what it is sent is a manual check. Everything up to the bus is here:
 * that a key is one notification, that a withdrawal closes it, that a press comes back as the
 * key it was posted under, and that a session without a daemon is unsupported rather than
 * broken.
 */
class DBusNotificationsTest {
    private fun centre(daemon: FakeDaemon, raised: () -> Unit = {}): Pair<DBusNotifications, Heard> {
        val heard = Heard()
        val centre = DBusNotifications(open = { daemon }, bringToFront = raised, applicationName = "ember")
        centre.start(heard)
        return centre to heard
    }

    @Test
    fun fr36_linux_a_session_with_a_daemon_is_granted_and_one_without_is_unsupported() {
        assertEquals(listOf(NotificationPermission.Granted), centre(FakeDaemon()).second.permissions)
        assertEquals(
            listOf(NotificationPermission.Unsupported),
            centre(FakeDaemon(present = false)).second.permissions,
        )
        val heard = Heard()
        DBusNotifications(open = { null }, bringToFront = {}, applicationName = "").start(heard)
        assertEquals(listOf(NotificationPermission.Unsupported), heard.permissions)
    }

    @Test
    fun fr36_linux_the_same_key_replaces_the_notification_it_posted() {
        val daemon = FakeDaemon()
        val (centre, _) = centre(daemon)
        centre.post(notification("session/7", title = "first"))
        centre.post(notification("session/7", title = "second"))
        assertEquals(listOf(0L, 1L), daemon.notified.map { it.replacesId })
        assertEquals("second", daemon.notified.last().title)
    }

    @Test
    fun fr36_linux_withdrawing_closes_the_notification() {
        val daemon = FakeDaemon()
        val (centre, _) = centre(daemon)
        centre.post(notification("approval/3"))
        centre.withdraw("approval/3")
        assertEquals(listOf(1L), daemon.closed)
        centre.withdraw("approval/3")
        assertEquals(listOf(1L), daemon.closed, "nothing is left to close the second time")
    }

    @Test
    fun fr36_linux_buttons_are_offered_only_where_the_daemon_shows_them() {
        val daemon = FakeDaemon()
        centre(daemon).first.post(notification("k", action1 = "Open", action2 = "Approve"))
        assertEquals(
            listOf(
                "default", "",
                "compose-rust.action.1", "Open",
                "compose-rust.action.2", "Approve",
            ),
            daemon.notified.single().actions,
        )

        val plain = FakeDaemon(capabilities = listOf("body"))
        centre(plain).first.post(notification("k", action1 = "Open"))
        assertEquals(listOf("default", ""), plain.notified.single().actions)
    }

    @Test
    fun fr36_linux_the_channel_and_the_importance_travel_as_hints() {
        val daemon = FakeDaemon()
        val (centre, _) = centre(daemon)
        centre.post(notification("a", channel = "Sessions", importance = NotificationImportance.Urgent))
        centre.post(notification("b"))
        val (urgent, normal) = daemon.notified
        assertEquals("Sessions", urgent.hints["category"])
        assertEquals(2, urgent.hints["urgency"])
        assertEquals(1, normal.hints["urgency"])
        assertTrue("category" !in normal.hints)
        assertEquals("ember", normal.hints["desktop-entry"])
    }

    @Test
    fun fr36_linux_a_press_comes_back_as_its_key_and_only_the_body_raises_the_window() {
        val daemon = FakeDaemon()
        var raised = 0
        val (centre, heard) = centre(daemon) { raised++ }
        centre.post(notification("session/7", action1 = "Open"))
        centre.post(notification("approval/3", action1 = "Approve"))

        daemon.press(1, "default")
        daemon.press(2, "compose-rust.action.1")
        // Somebody else's notification: not ours to report.
        daemon.press(99, "default")
        centre.pump()

        assertEquals(listOf("session/7" to 0, "approval/3" to 1), heard.presses)
        assertEquals(1, raised, "only the body brings the window up")
    }

    @Test
    fun fr36_linux_ending_the_process_closes_what_it_showed() {
        val daemon = FakeDaemon()
        val (centre, _) = centre(daemon)
        centre.post(notification("a"))
        centre.post(notification("b"))
        centre.processEnding()
        assertEquals(setOf(1L, 2L), daemon.closed.toSet())
    }

    @Test
    fun fr36_linux_the_session_bus_address_is_read_the_way_the_bus_writes_it() {
        assertEquals(
            BusAddress("/run/user/1000/bus", abstract = false),
            BusAddress.parse("unix:path=/run/user/1000/bus,guid=abc", 1000),
        )
        assertEquals(
            BusAddress("/tmp/dbus-XyZ", abstract = true),
            BusAddress.parse("tcp:host=x,port=1;unix:abstract=/tmp/dbus-XyZ", 1000),
        )
        assertEquals(
            BusAddress("/run/user/501/bus", abstract = false),
            BusAddress.parse(null, 501),
        )
        assertEquals(
            BusAddress("/tmp/a b", abstract = false),
            BusAddress.parse("unix:path=/tmp/a%20b", 0),
        )
        assertNull(BusAddress.parse("tcp:host=localhost,port=4", 0))
    }

    @Test
    fun fr36_linux_the_bus_is_told_the_user_in_hex_and_then_begins() {
        val written = StringBuilder()
        val ok = authenticateToBus(
            1000,
            write = { bytes -> written.append(bytes.decodeToString()); true },
            readLine = { "OK 1234deadbeef" },
        )
        assertTrue(ok)
        assertEquals("\u0000AUTH EXTERNAL 31303030\r\nBEGIN\r\n", written.toString())
        assertTrue(!authenticateToBus(1000, write = { true }, readLine = { "REJECTED EXTERNAL" }))
    }

    @Test
    fun fr36_linux_a_message_is_as_long_as_its_header_says() {
        val call = DBusMessage.methodCall(
            7,
            dev.darkpyonix.composerust.ui.platform.Destination("a.b", "/a/b", "a.b"),
            "Ping",
            "s",
            DBusWriter().apply { string("hello") }.bytes(),
        )
        assertEquals(call.size, dbusMessageLength(call.copyOf(16)))
        val parsed = DBusMessage.parse(call)!!
        assertEquals(7L, parsed.serial)
        assertEquals("Ping", parsed.member)
        assertEquals("hello", parsed.body().string())
    }
}
