package dev.darkpyonix.composerust.ui.platform

// The objects a reader asks questions of, answered from the tree the window last published.
//
// AT-SPI is a conversation the other way round from the notification daemon: this process owns
// objects, a registry and its clients call methods on them, and every answer is made from what
// the window said last. Nothing here reads the scene or the bus; it is handed a call and a tree
// and returns the bytes of the reply, which is what lets a test ask it anything a screen reader
// would and read the answer back.
//
// The object paths are three kinds. The application is `.../root`, which is what the registry
// is told about and where a reader starts. Its only child is the window, `.../window`, and the
// window's children are the controls, `.../n<id>` under Compose's own id for each.

internal interface AtspiActions {
    /** Presses the control. False where it is gone or cannot be pressed. */
    fun click(id: Int): Boolean

    /** Gives the control the keyboard. */
    fun focus(id: Int): Boolean

    /** Where the window's top left corner is on the screen. */
    fun windowOrigin(): Pair<Int, Int>
}

internal class AtspiServer(
    private val applicationName: String,
    private val actions: AtspiActions,
) {
    /** This process's name on the accessibility bus. */
    var busName: String = ""

    /** What the registry said the application's parent is, once it has said. */
    var registryParent: Pair<String, String> = "" to NULL_PATH

    var tree: AtspiTree = AtspiTree.EMPTY

    /** Whether the window is the one the keyboard is in. */
    var windowActive: Boolean = false

    /** The answer to a call, or null where the caller asked for none. */
    fun handle(call: IncomingCall, serial: () -> Int): ByteArray? {
        val reply = try {
            dispatch(call, serial)
        } catch (_: IndexOutOfBoundsException) {
            // Arguments shorter than the member says they are.
            AtspiWire.error(serial(), call, INVALID_ARGS, "the arguments of ${call.member} are short")
        }
        return if (call.noReply) null else reply
    }

    // ---- the signals this process sends -------------------------------------------------

    fun stateChanged(serial: Int, id: Int, state: Int, on: Boolean): ByteArray =
        event(serial, id, EVENT_OBJECT, "StateChanged", AtspiState.nameOf(state), if (on) 1 else 0, "i") {
            u32(0)
        }

    fun childrenChanged(serial: Int, parent: Int, index: Int, child: Int, added: Boolean): ByteArray =
        event(
            serial, parent, EVENT_OBJECT, "ChildrenChanged", if (added) "add" else "remove", index, "(so)",
        ) { reference(busName, pathOf(child)) }

    fun nameChanged(serial: Int, id: Int, name: String): ByteArray =
        event(serial, id, EVENT_OBJECT, "PropertyChange", "accessible-name", 0, "s") { string(name) }

    fun windowEvent(serial: Int, member: String): ByteArray =
        event(serial, FRAME, EVENT_WINDOW, member, "", 0, "i") { u32(0) }

    private fun event(
        serial: Int,
        id: Int,
        interfaceName: String,
        member: String,
        kind: String,
        detail: Int,
        valueSignature: String,
        value: DBusWriter.() -> Unit,
    ): ByteArray = AtspiWire.signal(serial, pathOf(id), interfaceName, member, "siiva{sv}") {
        string(kind)
        u32(detail.toLong() and 0xffff_ffffL)
        u32(0)
        variant(valueSignature, value)
        array(8) {}
    }

    // ---- objects ------------------------------------------------------------------------

    fun pathOf(id: Int): String = when (id) {
        ROOT -> ROOT_PATH
        FRAME -> FRAME_PATH
        else -> "$NODE_PREFIX$id"
    }

    /** The object a path names, or null for one that is not one of ours. */
    fun idOf(path: String): Int? = when {
        path == ROOT_PATH -> ROOT
        path == FRAME_PATH -> FRAME
        path.startsWith(NODE_PREFIX) -> path.removePrefix(NODE_PREFIX).toIntOrNull()?.takeIf { it in tree.nodes }
        else -> null
    }

    private fun childrenOf(id: Int): List<Int> = when (id) {
        ROOT -> listOf(FRAME)
        FRAME -> tree.roots
        else -> tree.nodes[id]?.children ?: emptyList()
    }

    private fun parentOf(id: Int): Int? = when (id) {
        ROOT -> null
        FRAME -> ROOT
        else -> tree.nodes[id]?.parent?.let { if (it == AtspiTree.FRAME) FRAME else it }
    }

    private fun roleOf(id: Int): Int = when (id) {
        ROOT -> AtspiRole.APPLICATION
        FRAME -> AtspiRole.FRAME
        else -> tree.nodes[id]?.role ?: 0
    }

    private fun nameOf(id: Int): String = when (id) {
        ROOT -> applicationName
        FRAME -> tree.title
        else -> tree.nodes[id]?.name ?: ""
    }

    private fun statesOf(id: Int): Long = when (id) {
        ROOT -> 0L
        FRAME -> {
            var states = AtspiState.bit(AtspiState.ENABLED) or AtspiState.bit(AtspiState.SENSITIVE) or
                AtspiState.bit(AtspiState.SHOWING) or AtspiState.bit(AtspiState.VISIBLE)
            if (windowActive) states = states or AtspiState.bit(AtspiState.ACTIVE)
            states
        }
        else -> tree.nodes[id]?.states ?: 0L
    }

    private fun actionsOf(id: Int): List<String> = tree.nodes[id]?.actions ?: emptyList()

    private fun textOf(id: Int): String? = tree.nodes[id]?.text

    private fun interfacesOf(id: Int): List<String> = buildList {
        add(ACCESSIBLE)
        if (id == ROOT) {
            add(APPLICATION)
        } else {
            add(COMPONENT)
            if (actionsOf(id).isNotEmpty()) add(ACTION)
            if (textOf(id) != null) add(TEXT)
        }
    }

    /** Position and size in the window's own pixels. */
    private fun boundsOf(id: Int): IntArray = when (id) {
        ROOT -> intArrayOf(0, 0, 0, 0)
        FRAME -> intArrayOf(0, 0, tree.width, tree.height)
        else -> tree.nodes[id]?.let { intArrayOf(it.x, it.y, it.width, it.height) } ?: intArrayOf(0, 0, 0, 0)
    }

    /** The bounds in one of the three coordinate systems a client may ask for. */
    private fun extentsIn(id: Int, coordinateType: Long): IntArray {
        val bounds = boundsOf(id)
        return when (coordinateType) {
            COORD_SCREEN -> {
                val (originX, originY) = actions.windowOrigin()
                intArrayOf(bounds[0] + originX, bounds[1] + originY, bounds[2], bounds[3])
            }
            COORD_PARENT -> {
                val parent = parentOf(id)?.takeIf { it != ROOT }?.let { boundsOf(it) }
                if (parent == null || id == FRAME) {
                    bounds
                } else {
                    intArrayOf(bounds[0] - parent[0], bounds[1] - parent[1], bounds[2], bounds[3])
                }
            }
            else -> bounds
        }
    }

    private fun DBusWriter.referenceTo(id: Int?) {
        if (id == null) reference("", NULL_PATH) else reference(busName, pathOf(id))
    }

    private fun DBusWriter.parentReference(id: Int) {
        val parent = parentOf(id)
        if (parent == null) reference(registryParent.first, registryParent.second) else referenceTo(parent)
    }

    private fun DBusWriter.stateWords(id: Int) {
        val states = statesOf(id)
        array(4) {
            u32(states and 0xffff_ffffL)
            u32((states ushr 32) and 0xffff_ffffL)
        }
    }

    // ---- dispatch -----------------------------------------------------------------------

    private fun dispatch(call: IncomingCall, serial: () -> Int): ByteArray {
        val interfaceName = call.interfaceName
        if (interfaceName == PEER) {
            return when (call.member) {
                "Ping" -> AtspiWire.methodReturn(serial(), call, null) {}
                "GetMachineId" -> AtspiWire.methodReturn(serial(), call, "s") { string(MACHINE_ID) }
                else -> unknownMethod(serial, call)
            }
        }
        val id = idOf(call.path)
            ?: return AtspiWire.error(serial(), call, UNKNOWN_OBJECT, "no object at ${call.path}")
        return when (interfaceName) {
            INTROSPECTABLE -> when (call.member) {
                "Introspect" -> AtspiWire.methodReturn(serial(), call, "s") { string(introspection(id)) }
                else -> unknownMethod(serial, call)
            }
            PROPERTIES -> properties(call, id, serial)
            ACCESSIBLE -> accessible(call, id, serial)
            COMPONENT -> if (id == ROOT) unknownInterface(serial, call) else component(call, id, serial)
            ACTION -> if (actionsOf(id).isEmpty()) unknownInterface(serial, call) else action(call, id, serial)
            TEXT -> if (textOf(id) == null) unknownInterface(serial, call) else text(call, id, serial)
            APPLICATION -> if (id == ROOT) application(call, serial) else unknownInterface(serial, call)
            else -> unknownInterface(serial, call)
        }
    }

    private fun unknownMethod(serial: () -> Int, call: IncomingCall) =
        AtspiWire.error(serial(), call, UNKNOWN_METHOD, "${call.member} is not a method here")

    private fun unknownInterface(serial: () -> Int, call: IncomingCall) =
        AtspiWire.error(serial(), call, UNKNOWN_INTERFACE, "${call.interfaceName} is not an interface of ${call.path}")

    private fun introspection(id: Int): String = buildString {
        append("<node>")
        for (name in interfacesOf(id)) append("<interface name=\"").append(name).append("\"/>")
        append("</node>")
    }

    private class Property(val name: String, val signature: String, val write: DBusWriter.() -> Unit)

    private fun propertiesOf(id: Int, interfaceName: String): List<Property> = when (interfaceName) {
        ACCESSIBLE -> listOf(
            Property("Name", "s") { string(nameOf(id)) },
            Property("Description", "s") { string("") },
            Property("Parent", "(so)") { parentReference(id) },
            Property("ChildCount", "i") { u32(childrenOf(id).size.toLong()) },
            Property("Locale", "s") { string("") },
            Property("AccessibleId", "s") { string("") },
            Property("HelpText", "s") { string("") },
        )
        APPLICATION -> if (id != ROOT) emptyList() else listOf(
            Property("ToolkitName", "s") { string(TOOLKIT) },
            Property("Version", "s") { string(TOOLKIT_VERSION) },
            Property("AtspiVersion", "s") { string("2.1") },
            Property("Id", "i") { u32(0) },
        )
        ACTION -> listOf(Property("NActions", "i") { u32(actionsOf(id).size.toLong()) })
        TEXT -> listOf(
            Property("CharacterCount", "i") { u32(codePointsOf(textOf(id) ?: "").size.toLong()) },
            Property("CaretOffset", "i") { u32((tree.nodes[id]?.caret ?: 0).toLong()) },
        )
        else -> emptyList()
    }

    private fun properties(call: IncomingCall, id: Int, serial: () -> Int): ByteArray {
        val arguments = call.arguments()
        return when (call.member) {
            "Get" -> {
                val interfaceName = arguments.string()
                val name = arguments.string()
                val found = propertiesOf(id, interfaceName).firstOrNull { it.name == name }
                    ?: return AtspiWire.error(serial(), call, UNKNOWN_PROPERTY, "$interfaceName has no property $name")
                AtspiWire.methodReturn(serial(), call, "v") { variant(found.signature, found.write) }
            }
            "GetAll" -> {
                val list = propertiesOf(id, arguments.string())
                AtspiWire.methodReturn(serial(), call, "a{sv}") {
                    array(8) { for (property in list) entry(property.name, property.signature, property.write) }
                }
            }
            "Set" -> AtspiWire.error(serial(), call, PROPERTY_READ_ONLY, "nothing here can be set")
            else -> unknownMethod(serial, call)
        }
    }

    private fun application(call: IncomingCall, serial: () -> Int): ByteArray = when (call.member) {
        "GetLocale" -> AtspiWire.methodReturn(serial(), call, "s") { string("") }
        "GetApplicationBusAddress" -> AtspiWire.methodReturn(serial(), call, "s") { string("") }
        else -> unknownMethod(serial, call)
    }

    private fun accessible(call: IncomingCall, id: Int, serial: () -> Int): ByteArray {
        val arguments = call.arguments()
        return when (call.member) {
            "GetRole" -> AtspiWire.methodReturn(serial(), call, "u") { u32(roleOf(id).toLong()) }
            "GetRoleName", "GetLocalizedRoleName" ->
                AtspiWire.methodReturn(serial(), call, "s") { string(AtspiRole.nameOf(roleOf(id))) }
            "GetState" -> AtspiWire.methodReturn(serial(), call, "au") { stateWords(id) }
            "GetAttributes" -> AtspiWire.methodReturn(serial(), call, "a{ss}") { array(8) {} }
            "GetRelationSet" -> AtspiWire.methodReturn(serial(), call, "a(ua(so))") { array(8) {} }
            "GetInterfaces" -> AtspiWire.methodReturn(serial(), call, "as") {
                array(4) { for (name in interfacesOf(id)) string(name) }
            }
            "GetApplication" -> AtspiWire.methodReturn(serial(), call, "(so)") { referenceTo(ROOT) }
            "GetChildren" -> AtspiWire.methodReturn(serial(), call, "a(so)") {
                array(8) { for (child in childrenOf(id)) referenceTo(child) }
            }
            "GetChildAtIndex" -> {
                val index = arguments.i32()
                AtspiWire.methodReturn(serial(), call, "(so)") { referenceTo(childrenOf(id).getOrNull(index)) }
            }
            "GetIndexInParent" -> AtspiWire.methodReturn(serial(), call, "i") {
                val parent = parentOf(id)
                u32((if (parent == null) -1 else childrenOf(parent).indexOf(id)).toLong() and 0xffff_ffffL)
            }
            else -> unknownMethod(serial, call)
        }
    }

    private fun component(call: IncomingCall, id: Int, serial: () -> Int): ByteArray {
        val arguments = call.arguments()
        return when (call.member) {
            "GetExtents" -> {
                val bounds = extentsIn(id, arguments.u32())
                AtspiWire.methodReturn(serial(), call, "(iiii)") {
                    struct { for (value in bounds) u32(value.toLong() and 0xffff_ffffL) }
                }
            }
            "GetPosition" -> {
                val bounds = extentsIn(id, arguments.u32())
                AtspiWire.methodReturn(serial(), call, "(ii)") {
                    struct { u32(bounds[0].toLong() and 0xffff_ffffL); u32(bounds[1].toLong() and 0xffff_ffffL) }
                }
            }
            "GetSize" -> {
                val bounds = boundsOf(id)
                AtspiWire.methodReturn(serial(), call, "(ii)") {
                    struct { u32(bounds[2].toLong() and 0xffff_ffffL); u32(bounds[3].toLong() and 0xffff_ffffL) }
                }
            }
            "Contains" -> {
                val x = arguments.i32()
                val y = arguments.i32()
                val bounds = extentsIn(id, arguments.u32())
                AtspiWire.methodReturn(serial(), call, "b") {
                    boolean(x >= bounds[0] && x < bounds[0] + bounds[2] && y >= bounds[1] && y < bounds[1] + bounds[3])
                }
            }
            "GetAccessibleAtPoint" -> {
                val x = arguments.i32()
                val y = arguments.i32()
                val coordinateType = arguments.u32()
                // The point arrives in the system the client asked in; the tree is searched in
                // the window's own.
                val origin = extentsIn(FRAME, coordinateType)
                val hit = tree.nodeAt(x - origin[0], y - origin[1])
                AtspiWire.methodReturn(serial(), call, "(so)") { referenceTo(hit) }
            }
            "GetLayer" -> AtspiWire.methodReturn(serial(), call, "u") {
                u32(if (id == FRAME) LAYER_WINDOW else LAYER_WIDGET)
            }
            "GetMDIZOrder" -> AtspiWire.methodReturn(serial(), call, "n") { i16(0) }
            "GetAlpha" -> AtspiWire.methodReturn(serial(), call, "d") { double(1.0) }
            "GrabFocus" -> {
                val taken = id != FRAME && actions.focus(id)
                AtspiWire.methodReturn(serial(), call, "b") { boolean(taken) }
            }
            "SetExtents", "SetPosition", "SetSize", "ScrollTo" ->
                AtspiWire.methodReturn(serial(), call, "b") { boolean(false) }
            else -> unknownMethod(serial, call)
        }
    }

    private fun action(call: IncomingCall, id: Int, serial: () -> Int): ByteArray {
        val arguments = call.arguments()
        val available = actionsOf(id)
        return when (call.member) {
            "GetNActions" -> AtspiWire.methodReturn(serial(), call, "i") { u32(available.size.toLong()) }
            "GetName", "GetLocalizedName" -> {
                val name = available.getOrNull(arguments.i32()) ?: ""
                AtspiWire.methodReturn(serial(), call, "s") { string(name) }
            }
            "GetDescription" -> {
                val name = available.getOrNull(arguments.i32()) ?: ""
                AtspiWire.methodReturn(serial(), call, "s") { string(name) }
            }
            "GetKeyBinding" -> AtspiWire.methodReturn(serial(), call, "s") { string("") }
            "GetActions" -> AtspiWire.methodReturn(serial(), call, "a(sss)") {
                array(8) {
                    for (name in available) struct {
                        string(name)
                        string(name)
                        string("")
                    }
                }
            }
            "DoAction" -> {
                val name = available.getOrNull(arguments.i32())
                val done = name == ACTION_CLICK && actions.click(id)
                AtspiWire.methodReturn(serial(), call, "b") { boolean(done) }
            }
            else -> unknownMethod(serial, call)
        }
    }

    private fun text(call: IncomingCall, id: Int, serial: () -> Int): ByteArray {
        val arguments = call.arguments()
        val points = codePointsOf(textOf(id) ?: "")
        fun slice(start: Int, end: Int): String {
            val from = start.coerceIn(0, points.size)
            val to = (if (end < 0) points.size else end).coerceIn(from, points.size)
            return buildString { for (index in from until to) appendPoint(points[index]) }
        }
        return when (call.member) {
            "GetText" -> {
                val start = arguments.i32()
                val end = arguments.i32()
                AtspiWire.methodReturn(serial(), call, "s") { string(slice(start, end)) }
            }
            "GetCaretOffset" -> AtspiWire.methodReturn(serial(), call, "i") {
                u32((tree.nodes[id]?.caret ?: 0).toLong())
            }
            "SetCaretOffset" -> AtspiWire.methodReturn(serial(), call, "b") { boolean(false) }
            "GetCharacterAtOffset" -> {
                val offset = arguments.i32()
                AtspiWire.methodReturn(serial(), call, "i") {
                    u32((points.getOrNull(offset) ?: 0).toLong() and 0xffff_ffffL)
                }
            }
            "GetNSelections" -> AtspiWire.methodReturn(serial(), call, "i") { u32(0) }
            "GetSelection" -> AtspiWire.methodReturn(serial(), call, "(ii)") {
                struct { u32(0); u32(0) }
            }
            "GetOffsetAtPoint" -> AtspiWire.methodReturn(serial(), call, "i") { u32(0xffff_ffffL) }
            "GetTextAtOffset" -> {
                val offset = arguments.i32().coerceIn(0, points.size)
                val granularity = arguments.u32()
                val range = textRangeAt(points, offset, granularity)
                AtspiWire.methodReturn(serial(), call, "(sii)") {
                    struct {
                        string(slice(range.first, range.second))
                        u32(range.first.toLong())
                        u32(range.second.toLong())
                    }
                }
            }
            else -> unknownMethod(serial, call)
        }
    }

    companion object {
        /** The ids the application and the window have, which no control can. */
        const val ROOT = -2
        const val FRAME = AtspiTree.FRAME

        const val ROOT_PATH = "/org/a11y/atspi/accessible/root"
        const val FRAME_PATH = "/org/a11y/atspi/accessible/window"
        const val NODE_PREFIX = "/org/a11y/atspi/accessible/n"
        const val NULL_PATH = "/org/a11y/atspi/null"

        const val ACCESSIBLE = "org.a11y.atspi.Accessible"
        const val COMPONENT = "org.a11y.atspi.Component"
        const val ACTION = "org.a11y.atspi.Action"
        const val TEXT = "org.a11y.atspi.Text"
        const val APPLICATION = "org.a11y.atspi.Application"
        const val EVENT_OBJECT = "org.a11y.atspi.Event.Object"
        const val EVENT_WINDOW = "org.a11y.atspi.Event.Window"

        private const val PEER = "org.freedesktop.DBus.Peer"
        private const val INTROSPECTABLE = "org.freedesktop.DBus.Introspectable"
        private const val PROPERTIES = "org.freedesktop.DBus.Properties"

        private const val UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject"
        private const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val UNKNOWN_INTERFACE = "org.freedesktop.DBus.Error.UnknownInterface"
        private const val UNKNOWN_PROPERTY = "org.freedesktop.DBus.Error.UnknownProperty"
        private const val PROPERTY_READ_ONLY = "org.freedesktop.DBus.Error.PropertyReadOnly"
        private const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"

        private const val TOOLKIT = "compose-rust"
        private const val TOOLKIT_VERSION = "1"
        private const val MACHINE_ID = "0123456789abcdef0123456789abcdef"

        private const val COORD_SCREEN = 0L
        private const val COORD_PARENT = 2L
        private const val LAYER_WIDGET = 3L
        private const val LAYER_WINDOW = 7L
    }
}

/**
 * The run of text a reader asked for at an offset: one character, one word, or the whole of
 * what a single line field holds for anything larger, which a line, a sentence and a paragraph
 * all are there.
 */
internal fun textRangeAt(points: List<Int>, offset: Int, granularity: Long): Pair<Int, Int> {
    if (points.isEmpty()) return 0 to 0
    val at = offset.coerceIn(0, points.size - 1)
    return when (granularity) {
        0L -> at to at + 1
        1L -> {
            // A word runs between spaces.
            var start = at
            while (start > 0 && !isSpace(points[start - 1])) start -= 1
            var end = at
            while (end < points.size && !isSpace(points[end])) end += 1
            start to end
        }
        else -> 0 to points.size
    }
}

private fun isSpace(point: Int) = point == ' '.code || point == '\t'.code || point == '\n'.code
