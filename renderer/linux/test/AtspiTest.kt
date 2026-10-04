package dev.darkpyonix.composerust.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What a screen reader is told, asked the way a screen reader asks.
//
// Orca and Accerciser reach this process through the registry daemon: they call methods on the
// objects the window exports and listen for the signals it sends. Neither of them can run on
// the machines this is tested on, so the other end of the bus is a fake that does what the
// registry does (answers Hello, names the accessibility bus, accepts the embedding) and a test
// plays the client. What is asserted is the content of the answers, which is the part a
// reader's speech comes from. Whether Orca speaks them is a person at a desktop.

private const val ROOT = AtspiServer.ROOT_PATH
private const val WINDOW = AtspiServer.FRAME_PATH
private fun node(id: Int) = "${AtspiServer.NODE_PREFIX}$id"

/** One fake bus. [answer] is what its daemon says to each call made on it. */
private class FakeBus(private val answer: (DBusMessage) -> ByteArray?) : BusConnection {
    val incoming = ArrayDeque<ByteArray>()
    val sent = mutableListOf<ByteArray>()
    var closed = false

    override fun send(message: ByteArray): Boolean {
        sent += message
        val parsed = DBusMessage.parse(message) ?: return true
        if (parsed.type == DBusMessage.METHOD_CALL) answer(parsed)?.let { incoming.addLast(it) }
        return true
    }

    override fun receive(timeoutMillis: Int): ByteArray? = incoming.removeFirstOrNull()

    override fun close() {
        closed = true
    }

    /** The signals sent so far, with where from and what they said. */
    fun signals(): List<Signal> = sent.mapNotNull { Signal.of(it) }
}

private data class Signal(val path: String, val interfaceName: String, val member: String, val kind: String, val detail: Int) {
    companion object {
        fun of(bytes: ByteArray): Signal? {
            val header = Header.of(bytes) ?: return null
            if (header.type != DBusMessage.SIGNAL) return null
            val reader = header.body
            val kind = reader.string()
            val detail = reader.i32()
            return Signal(header.path ?: "", header.interfaceName ?: "", header.member ?: "", kind, detail)
        }
    }
}

/** The fields of a message, read by hand because the library's own parser does not keep them all. */
private class Header(
    val type: Int,
    val path: String?,
    val interfaceName: String?,
    val member: String?,
    val replySerial: Long?,
    val errorName: String?,
    val body: DBusReader,
) {
    companion object {
        fun of(bytes: ByteArray): Header? {
            val length = dbusMessageLength(bytes) ?: return null
            if (bytes.size < length) return null
            val fieldsEnd = 16 + DBusReader(bytes, 12).u32().toInt()
            val reader = DBusReader(bytes, 16)
            var path: String? = null
            var interfaceName: String? = null
            var member: String? = null
            var reply: Long? = null
            var error: String? = null
            while (reader.position < fieldsEnd) {
                reader.align(8)
                if (reader.position >= fieldsEnd) break
                val code = reader.byte()
                val value: Any = when (reader.signature()) {
                    "s", "o" -> reader.string()
                    "g" -> reader.signature()
                    "u" -> reader.u32()
                    else -> return null
                }
                when (code) {
                    1 -> path = value as String
                    2 -> interfaceName = value as String
                    3 -> member = value as String
                    4 -> error = value as String
                    5 -> reply = value as Long
                }
            }
            return Header(bytes[1].toInt(), path, interfaceName, member, reply, error, DBusReader(bytes, (fieldsEnd + 7) / 8 * 8))
        }
    }
}

private fun message(type: Int, serial: Long, signature: String?, body: ByteArray, fields: DBusWriter.() -> Unit): ByteArray {
    val out = DBusWriter()
    out.byte('l'.code)
    out.byte(type)
    out.byte(0)
    out.byte(1)
    out.u32(body.size.toLong())
    out.u32(serial)
    out.array(8) {
        fields()
        if (signature != null) {
            struct {
                byte(8)
                signature("g")
                signature(signature)
            }
        }
    }
    out.align(8)
    out.raw(body)
    return out.bytes()
}

private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) = struct {
    byte(code)
    signature(signature)
    value()
}

private var nextSerial = 900L

/** What the daemon answers a call with. */
private fun returnTo(call: DBusMessage, signature: String?, body: DBusWriter.() -> Unit): ByteArray =
    message(DBusMessage.METHOD_RETURN, nextSerial++, signature, DBusWriter().apply(body).bytes()) {
        field(5, "u") { u32(call.serial) }
    }

private fun failTo(call: DBusMessage): ByteArray =
    message(DBusMessage.ERROR, nextSerial++, null, ByteArray(0)) {
        field(4, "s") { string("org.freedesktop.DBus.Error.ServiceUnknown") }
        field(5, "u") { u32(call.serial) }
    }

/** A call from a client, as the registry or Orca would make it. */
private fun clientCall(
    serial: Long,
    path: String,
    interfaceName: String,
    member: String,
    signature: String? = null,
    body: DBusWriter.() -> Unit = {},
    noReply: Boolean = false,
): ByteArray {
    val content = DBusWriter().apply(body).bytes()
    val out = DBusWriter()
    out.byte('l'.code)
    out.byte(DBusMessage.METHOD_CALL)
    out.byte(if (noReply) 1 else 0)
    out.byte(1)
    out.u32(content.size.toLong())
    out.u32(serial)
    out.array(8) {
        field(1, "o") { string(path) }
        field(2, "s") { string(interfaceName) }
        field(3, "s") { string(member) }
        field(7, "s") { string(":1.99") }
        if (signature != null) field(8, "g") { signature(signature) }
    }
    out.align(8)
    out.raw(content)
    return out.bytes()
}

private class Harness(
    sessionAnswers: Boolean = true,
    registryAnswers: Boolean = true,
) {
    val clicked = mutableListOf<Int>()
    val focusRequests = mutableListOf<Int>()
    var origin = 100 to 50

    val a11y = FakeBus { call ->
        when {
            call.interfaceName == "org.freedesktop.DBus" && call.member == "Hello" ->
                returnTo(call, "s") { string(":1.42") }
            call.member == "Embed" && registryAnswers -> {
                val reader = call.body()
                reader.align(8)
                embedded = reader.string() to reader.string()
                returnTo(call, "(so)") { reference(":1.1", "/org/a11y/atspi/accessible/root") }
            }
            else -> failTo(call)
        }
    }

    val session = FakeBus { call ->
        when {
            call.member == "Hello" -> returnTo(call, "s") { string(":1.7") }
            call.member == "GetAddress" && sessionAnswers ->
                returnTo(call, "s") { string("unix:path=/run/user/1000/at-spi/bus_0,guid=abc") }
            else -> failTo(call)
        }
    }

    var embedded: Pair<String, String>? = null
    var openedAddress: BusAddress? = null

    private val actions = object : AtspiActions {
        override fun click(id: Int): Boolean {
            clicked += id
            return true
        }

        override fun focus(id: Int): Boolean {
            focusRequests += id
            return true
        }

        override fun windowOrigin() = origin
    }

    val bridge = AtspiBridge(
        openSession = { session },
        openAccessibility = { address ->
            openedAddress = address
            a11y
        },
        userId = 1000,
        applicationName = "notepad",
        actions = actions,
    )

    private var serial = 1000L

    /** Asks the window something and returns what it answered. */
    fun ask(
        path: String,
        interfaceName: String,
        member: String,
        signature: String? = null,
        body: DBusWriter.() -> Unit = {},
    ): Header {
        val id = serial++
        a11y.incoming.addLast(clientCall(id, path, interfaceName, member, signature, body))
        bridge.pump()
        return a11y.sent.mapNotNull { Header.of(it) }.last { it.replySerial == id }
    }

    fun references(reply: Header): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        reply.body.array(8) {
            reply.body.align(8)
            found += reply.body.string() to reply.body.string()
        }
        return found
    }
}

/** A form: a window holding a heading, a text field with something in it, a checkbox and a button. */
private fun form(focused: Int? = null, checked: Boolean = false): AtspiTree {
    val button = describeFacts(NodeFacts(composeRole = "Button", label = "Save", clickable = true, canTakeFocus = true))!!
    val entry = describeFacts(NodeFacts(label = "Name", editableText = "한글", focused = focused == 11))!!
    val box = describeFacts(
        NodeFacts(composeRole = "Checkbox", label = "Remember", toggle = if (checked) "On" else "Off", clickable = true),
    )!!
    val heading = describeFacts(NodeFacts(label = "Profile", heading = true))!!
    val nodes = listOf(
        AtspiNode(10, AtspiTree.FRAME, emptyList(), heading.role, heading.name, heading.states, heading.actions, 10, 5, 100, 20, null),
        AtspiNode(11, AtspiTree.FRAME, emptyList(), entry.role, entry.name, entry.states, entry.actions, 10, 30, 200, 24, "한글", 2),
        AtspiNode(12, AtspiTree.FRAME, emptyList(), box.role, box.name, box.states, box.actions, 10, 60, 120, 24, null),
        AtspiNode(13, AtspiTree.FRAME, emptyList(), button.role, button.name, button.states, button.actions, 10, 90, 80, 32, null),
    )
    return AtspiTree("Profile", nodes.associateBy { it.id }, nodes.map { it.id }, focused, 400, 300)
}

class AtspiTest {

    // ---- the rule --------------------------------------------------------------------

    @Test
    fun nfr8_linux_the_roles_are_the_registrys_own_numbers() {
        // From atspi-constants.h. A role off by one is a button announced as something else.
        assertEquals(43, AtspiRole.PUSH_BUTTON)
        assertEquals(79, AtspiRole.ENTRY)
        assertEquals(29, AtspiRole.LABEL)
        assertEquals(23, AtspiRole.FRAME)
        assertEquals(75, AtspiRole.APPLICATION)
        assertEquals(7, AtspiRole.CHECK_BOX)
        assertEquals("push button", AtspiRole.nameOf(AtspiRole.PUSH_BUTTON))
    }

    @Test
    fun nfr8_linux_a_button_is_a_push_button_named_by_its_text_that_can_be_clicked() {
        val described = describeFacts(NodeFacts(composeRole = "Button", label = "Save", clickable = true))!!
        assertEquals(AtspiRole.PUSH_BUTTON, described.role)
        assertEquals("Save", described.name)
        assertEquals(listOf(ACTION_CLICK), described.actions)
        assertTrue(described.states and AtspiState.bit(AtspiState.FOCUSABLE) != 0L)
        assertTrue(described.states and AtspiState.bit(AtspiState.ENABLED) != 0L)
    }

    @Test
    fun nfr8_linux_a_clickable_with_no_role_is_still_a_button() {
        assertEquals(AtspiRole.PUSH_BUTTON, describeFacts(NodeFacts(label = "Go", clickable = true))!!.role)
    }

    @Test
    fun nfr8_linux_a_disabled_button_is_not_sensitive_and_offers_no_action() {
        val described = describeFacts(
            NodeFacts(composeRole = "Button", label = "Save", clickable = true, disabled = true),
        )!!
        assertEquals(0L, described.states and AtspiState.bit(AtspiState.ENABLED))
        assertEquals(0L, described.states and AtspiState.bit(AtspiState.SENSITIVE))
        assertTrue(described.actions.isEmpty())
    }

    @Test
    fun nfr8_linux_text_is_a_label_and_a_heading_is_a_heading() {
        assertEquals(AtspiRole.LABEL, describeFacts(NodeFacts(label = "Hello"))!!.role)
        assertEquals(AtspiRole.HEADING, describeFacts(NodeFacts(label = "Hello", heading = true))!!.role)
    }

    @Test
    fun nfr8_linux_a_field_is_an_editable_entry_and_a_password_field_says_so() {
        val field = describeFacts(NodeFacts(label = "Name", editableText = "abc", focused = true))!!
        assertEquals(AtspiRole.ENTRY, field.role)
        assertTrue(field.states and AtspiState.bit(AtspiState.EDITABLE) != 0L)
        assertTrue(field.states and AtspiState.bit(AtspiState.SINGLE_LINE) != 0L)
        assertTrue(field.states and AtspiState.bit(AtspiState.FOCUSED) != 0L)
        assertEquals(AtspiRole.PASSWORD_TEXT, describeFacts(NodeFacts(editableText = "", password = true))!!.role)
        val many = describeFacts(NodeFacts(editableText = "", multiline = true))!!
        assertTrue(many.states and AtspiState.bit(AtspiState.MULTI_LINE) != 0L)
    }

    @Test
    fun nfr8_linux_a_checkbox_reports_whether_it_is_checked() {
        val on = describeFacts(NodeFacts(composeRole = "Checkbox", label = "x", toggle = "On", clickable = true))!!
        val off = describeFacts(NodeFacts(composeRole = "Checkbox", label = "x", toggle = "Off", clickable = true))!!
        assertEquals(AtspiRole.CHECK_BOX, on.role)
        assertTrue(on.states and AtspiState.bit(AtspiState.CHECKED) != 0L)
        assertEquals(0L, off.states and AtspiState.bit(AtspiState.CHECKED))
        val partial = describeFacts(NodeFacts(composeRole = "Checkbox", label = "x", toggle = "Indeterminate"))!!
        assertTrue(partial.states and AtspiState.bit(AtspiState.INDETERMINATE) != 0L)
    }

    @Test
    fun nfr8_linux_a_switch_is_a_toggle_button_and_a_tab_is_a_page_tab() {
        assertEquals(AtspiRole.TOGGLE_BUTTON, describeFacts(NodeFacts(composeRole = "Switch", label = "w", toggle = "On"))!!.role)
        assertEquals(AtspiRole.PAGE_TAB, describeFacts(NodeFacts(composeRole = "Tab", label = "t", selected = true))!!.role)
        val tab = describeFacts(NodeFacts(composeRole = "Tab", label = "t", selected = true))!!
        assertTrue(tab.states and AtspiState.bit(AtspiState.SELECTED) != 0L)
    }

    @Test
    fun nfr8_linux_a_range_is_a_progress_bar_until_it_can_be_moved() {
        assertEquals(AtspiRole.PROGRESS_BAR, describeFacts(NodeFacts(label = "p", ranged = true))!!.role)
        assertEquals(AtspiRole.SLIDER, describeFacts(NodeFacts(label = "v", ranged = true, adjustable = true))!!.role)
    }

    @Test
    fun nfr8_linux_layout_with_nothing_to_say_is_left_out() {
        assertNull(describeFacts(NodeFacts()))
        assertNull(describeFacts(NodeFacts(focused = true, canTakeFocus = false)))
    }

    @Test
    fun nfr8_linux_the_deepest_control_under_a_point_is_found() {
        val tree = form()
        assertEquals(11, tree.nodeAt(20, 40))
        assertEquals(13, tree.nodeAt(15, 100))
        assertNull(tree.nodeAt(390, 290))
    }

    // ---- joining the bus -------------------------------------------------------------

    @Test
    fun nfr8_linux_the_window_joins_the_accessibility_bus_the_session_bus_names() {
        val harness = Harness()
        assertTrue(harness.bridge.start())
        assertEquals(":1.42", harness.bridge.busName)
        assertEquals(
            BusAddress("/run/user/1000/at-spi/bus_0", abstract = false),
            harness.openedAddress,
            "the address the session bus gave is where the window connects",
        )
        assertEquals(
            ":1.42" to ROOT,
            harness.embedded,
            "the registry is told this process's name on its bus and where the application object is",
        )
        assertTrue(harness.session.closed, "the session bus is not needed after it has named the other")
        assertTrue(harness.bridge.connected)
    }

    @Test
    fun nfr8_linux_a_desktop_with_no_accessibility_bus_is_left_alone() {
        val harness = Harness(sessionAnswers = false)
        assertFalse(harness.bridge.start())
        assertFalse(harness.bridge.connected)
        assertNull(harness.openedAddress)
        val noSession = AtspiBridge({ null }, { null }, 1000, "x", object : AtspiActions {
            override fun click(id: Int) = false
            override fun focus(id: Int) = false
            override fun windowOrigin() = 0 to 0
        })
        assertFalse(noSession.start())
    }

    @Test
    fun nfr8_linux_a_registry_that_will_not_take_the_window_is_not_joined() {
        val harness = Harness(registryAnswers = false)
        assertFalse(harness.bridge.start())
        assertTrue(harness.a11y.closed)
    }

    // ---- what a client is told -------------------------------------------------------

    private fun joined(): Harness = Harness().also {
        assertTrue(it.bridge.start())
        it.bridge.update(form())
    }

    @Test
    fun nfr8_linux_a_reader_walks_from_the_application_to_the_window_to_the_controls() {
        val harness = joined()
        val root = harness.ask(ROOT, AtspiServer.ACCESSIBLE, "GetChildren")
        assertEquals(listOf(":1.42" to WINDOW), harness.references(root))
        val window = harness.ask(WINDOW, AtspiServer.ACCESSIBLE, "GetChildren")
        assertEquals((10..13).map { ":1.42" to node(it) }, harness.references(window))
    }

    @Test
    fun nfr8_linux_the_application_and_window_have_their_roles_and_names() {
        val harness = joined()
        fun role(path: String) = harness.ask(path, AtspiServer.ACCESSIBLE, "GetRole").body.u32()
        assertEquals(AtspiRole.APPLICATION.toLong(), role(ROOT))
        assertEquals(AtspiRole.FRAME.toLong(), role(WINDOW))
        fun name(path: String): String {
            val reply = harness.ask(path, "org.freedesktop.DBus.Properties", "Get", "ss") {
                string(AtspiServer.ACCESSIBLE)
                string("Name")
            }
            val reader = reply.body
            assertEquals("s", reader.signature())
            return reader.string()
        }
        assertEquals("notepad", name(ROOT))
        assertEquals("Profile", name(WINDOW))
        assertEquals("Save", name(node(13)))
    }

    @Test
    fun nfr8_linux_a_control_answers_its_role_its_states_and_its_actions() {
        val harness = joined()
        assertEquals(AtspiRole.PUSH_BUTTON.toLong(), harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetRole").body.u32())
        val states = harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetState").body
        val words = mutableListOf<Long>()
        states.array(4) { words += states.u32() }
        assertEquals(2, words.size, "states are two 32-bit words")
        assertTrue(words[0] and (1L shl AtspiState.FOCUSABLE) != 0L)
        assertTrue(words[0] and (1L shl AtspiState.ENABLED) != 0L)
        assertEquals(1L, harness.ask(node(13), AtspiServer.ACTION, "GetNActions").body.u32())
        val name = harness.ask(node(13), AtspiServer.ACTION, "GetName", "i") { u32(0) }.body.string()
        assertEquals(ACTION_CLICK, name)
        val interfaces = mutableListOf<String>()
        val reply = harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetInterfaces").body
        reply.array(4) { interfaces += reply.string() }
        assertTrue(AtspiServer.ACTION in interfaces)
        assertTrue(AtspiServer.COMPONENT in interfaces)
        assertFalse(AtspiServer.TEXT in interfaces, "a button has no text interface")
    }

    @Test
    fun nfr8_linux_doing_the_action_clicks_the_control() {
        val harness = joined()
        val done = harness.ask(node(13), AtspiServer.ACTION, "DoAction", "i") { u32(0) }.body.u32()
        assertEquals(1L, done)
        assertEquals(listOf(13), harness.clicked)
    }

    @Test
    fun nfr8_linux_grabbing_focus_asks_the_control_to_take_it() {
        val harness = joined()
        assertEquals(1L, harness.ask(node(11), AtspiServer.COMPONENT, "GrabFocus").body.u32())
        assertEquals(listOf(11), harness.focusRequests)
    }

    @Test
    fun nfr8_linux_a_text_field_gives_what_it_holds_and_where_the_caret_is() {
        val harness = joined()
        assertEquals(
            "한글",
            harness.ask(node(11), AtspiServer.TEXT, "GetText", "ii") { u32(0); u32(0xffff_ffffL) }.body.string(),
        )
        assertEquals(
            "글",
            harness.ask(node(11), AtspiServer.TEXT, "GetText", "ii") { u32(1); u32(2) }.body.string(),
            "offsets count characters",
        )
        assertEquals(2L, harness.ask(node(11), AtspiServer.TEXT, "GetCaretOffset").body.u32())
        val count = harness.ask(node(11), "org.freedesktop.DBus.Properties", "Get", "ss") {
            string(AtspiServer.TEXT)
            string("CharacterCount")
        }.body
        assertEquals("i", count.signature())
        assertEquals(2L, count.u32())
    }

    @Test
    fun nfr8_linux_a_field_with_no_text_interface_refuses_the_question_and_the_window_stays_up() {
        val harness = joined()
        val reply = harness.ask(node(13), AtspiServer.TEXT, "GetText", "ii") { u32(0); u32(0) }
        assertEquals(DBusMessage.ERROR, reply.type)
        assertEquals("org.freedesktop.DBus.Error.UnknownInterface", reply.errorName)
        assertEquals(AtspiRole.PUSH_BUTTON.toLong(), harness.ask(node(13), AtspiServer.ACCESSIBLE, "GetRole").body.u32())
    }

    @Test
    fun nfr8_linux_a_control_that_is_not_there_is_an_unknown_object() {
        val harness = joined()
        val reply = harness.ask(node(99), AtspiServer.ACCESSIBLE, "GetRole")
        assertEquals(DBusMessage.ERROR, reply.type)
        assertEquals("org.freedesktop.DBus.Error.UnknownObject", reply.errorName)
        val other = harness.ask("/somewhere/else", AtspiServer.ACCESSIBLE, "GetRole")
        assertEquals("org.freedesktop.DBus.Error.UnknownObject", other.errorName)
    }

    @Test
    fun nfr8_linux_a_method_the_interface_does_not_have_is_an_unknown_method() {
        val harness = joined()
        val reply = harness.ask(node(13), AtspiServer.ACCESSIBLE, "Explode")
        assertEquals("org.freedesktop.DBus.Error.UnknownMethod", reply.errorName)
    }

    @Test
    fun nfr8_linux_extents_are_in_the_system_the_client_asks_in() {
        val harness = joined()
        fun extents(type: Long): List<Long> {
            val reader = harness.ask(node(13), AtspiServer.COMPONENT, "GetExtents", "u") { u32(type) }.body
            reader.align(8)
            return List(4) { reader.u32() }
        }
        assertEquals(listOf(10L, 90L, 80L, 32L), extents(1), "window coordinates")
        assertEquals(listOf(110L, 140L, 80L, 32L), extents(0), "screen coordinates add the window's place")
        harness.origin = 0 to 0
        assertEquals(listOf(10L, 90L, 80L, 32L), extents(0))
    }

    @Test
    fun nfr8_linux_a_point_finds_the_control_under_it() {
        val harness = joined()
        val reply = harness.ask(WINDOW, AtspiServer.COMPONENT, "GetAccessibleAtPoint", "iiu") {
            u32(120)
            u32(145)
            u32(0)
        }
        reply.body.align(8)
        assertEquals(":1.42" to node(13), reply.body.string() to reply.body.string())
    }

    @Test
    fun nfr8_linux_the_properties_of_a_control_come_back_whole() {
        val harness = joined()
        val reply = harness.ask(node(13), "org.freedesktop.DBus.Properties", "GetAll", "s") {
            string(AtspiServer.ACCESSIBLE)
        }.body
        val names = mutableListOf<String>()
        reply.array(8) {
            reply.align(8)
            names += reply.string()
            val signature = reply.signature()
            when (signature) {
                "s" -> reply.string()
                "i" -> reply.u32()
                "(so)" -> {
                    reply.align(8)
                    reply.string()
                    reply.string()
                }
                else -> error("unexpected $signature")
            }
        }
        assertTrue(names.containsAll(listOf("Name", "Parent", "ChildCount")), names.toString())
    }

    @Test
    fun nfr8_linux_a_call_that_wants_no_answer_gets_none() {
        val harness = joined()
        val before = harness.a11y.sent.size
        harness.a11y.incoming.addLast(
            clientCall(5000, node(13), AtspiServer.ACTION, "DoAction", "i", { u32(0) }, noReply = true),
        )
        harness.bridge.pump()
        assertEquals(before, harness.a11y.sent.size)
        assertEquals(listOf(13), harness.clicked, "the action still happened")
    }

    // ---- what changes -----------------------------------------------------------------

    @Test
    fun nfr8_linux_the_first_tree_is_not_announced_control_by_control() {
        val harness = Harness()
        harness.bridge.start()
        harness.bridge.update(form())
        assertTrue(harness.a11y.signals().isEmpty(), "a client asks a fresh window; nothing was there to change")
    }

    @Test
    fun nfr8_linux_moving_focus_says_which_control_lost_it_and_which_gained_it() {
        val harness = joined()
        harness.bridge.update(form(focused = 11))
        harness.bridge.update(form(focused = null))
        val focus = harness.a11y.signals().filter { it.member == "StateChanged" && it.kind == "focused" }
        assertEquals(
            listOf(node(11) to 1, node(11) to 0),
            focus.map { it.path to it.detail },
        )
        assertTrue(focus.all { it.interfaceName == AtspiServer.EVENT_OBJECT })
    }

    @Test
    fun nfr8_linux_checking_a_box_says_so() {
        val harness = joined()
        harness.bridge.update(form(checked = true))
        val checked = harness.a11y.signals().single { it.kind == "checked" }
        assertEquals(node(12), checked.path)
        assertEquals(1, checked.detail)
    }

    @Test
    fun nfr8_linux_a_control_that_appears_or_goes_is_announced_on_its_parent() {
        val harness = joined()
        val smaller = form().let { tree ->
            tree.copy(nodes = tree.nodes - 13, roots = tree.roots - 13)
        }
        harness.bridge.update(smaller)
        harness.bridge.update(form())
        val changes = harness.a11y.signals().filter { it.member == "ChildrenChanged" }
        assertEquals(listOf("remove" to 3, "add" to 3), changes.map { it.kind to it.detail })
        assertTrue(changes.all { it.path == WINDOW }, "children of the window are announced on the window")
    }

    @Test
    fun nfr8_linux_renaming_a_control_says_so() {
        val harness = joined()
        val tree = form()
        val renamed = tree.copy(nodes = tree.nodes + (13 to tree.nodes.getValue(13).copy(name = "Saved")))
        harness.bridge.update(renamed)
        val change = harness.a11y.signals().single { it.member == "PropertyChange" }
        assertEquals("accessible-name", change.kind)
        assertEquals(node(13), change.path)
    }

    @Test
    fun nfr8_linux_the_window_announces_when_it_gains_and_loses_the_keyboard() {
        val harness = joined()
        harness.bridge.windowActive(true)
        harness.bridge.windowActive(true)
        harness.bridge.windowActive(false)
        val window = harness.a11y.signals().filter { it.interfaceName == AtspiServer.EVENT_WINDOW }
        assertEquals(listOf("Activate", "Deactivate"), window.map { it.member })
        assertTrue(window.all { it.path == WINDOW })
        val active = harness.ask(WINDOW, AtspiServer.ACCESSIBLE, "GetState").body
        val words = mutableListOf<Long>()
        active.array(4) { words += active.u32() }
        assertEquals(0L, words[0] and (1L shl AtspiState.ACTIVE), "deactivated windows are not active")
    }

    @Test
    fun nfr8_linux_an_unchanged_tree_says_nothing() {
        val harness = joined()
        val before = harness.a11y.sent.size
        harness.bridge.update(form())
        assertEquals(before, harness.a11y.sent.size)
        assertTrue(harness.a11y.signals().isEmpty())
    }
}
