package dev.darkpyonix.composerust.ui.platform

/**
 * This window's presence on the accessibility bus.
 *
 * A desktop with a screen reader runs a second bus for it, apart from the session bus, and an
 * application that wants to be read joins it: the session bus is asked where it is
 * (`org.a11y.Bus.GetAddress`), the application connects there, and tells the registry daemon
 * it exists (`org.a11y.atspi.Socket.Embed`). From then on the registry and its clients call
 * methods on this process's objects, and this process sends signals when something changes.
 *
 * Everything is on the window's own thread. The connection is read without waiting once a turn
 * of the loop, the way the notification centre's is, so a question from Orca is answered in the
 * same turn it was asked in and costs a frame nothing it was not already spending.
 *
 * Failing to join is not an error. A machine with no accessibility bus has no screen reader
 * to serve, and the window carries on without one.
 */
internal class AtspiBridge(
    private val openSession: () -> BusConnection?,
    private val openAccessibility: (BusAddress) -> BusConnection?,
    private val userId: Long,
    applicationName: String,
    actions: AtspiActions,
) {
    private val server = AtspiServer(applicationName, actions)
    private var connection: BusConnection? = null
    private var serial = 0
    private var hasTree = false

    val connected: Boolean get() = connection != null

    /** What this process is called on the accessibility bus; empty until it has joined. */
    val busName: String get() = server.busName

    /** Joins the accessibility bus. False where there is none, or the registry did not answer. */
    fun start(): Boolean {
        val session = openSession() ?: return false
        val address = try {
            if (call(session, BUS, "Hello", null, ByteArray(0), CALL_TIMEOUT_MILLIS) == null) return false
            val reply = call(session, A11Y_BUS, "GetAddress", null, ByteArray(0), ACTIVATION_TIMEOUT_MILLIS)
                ?: return false
            try {
                BusAddress.parse(reply.body().string(), userId)
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        } finally {
            session.close()
        }
        if (address == null) return false
        val bus = openAccessibility(address) ?: return false
        val hello = call(bus, BUS, "Hello", null, ByteArray(0), CALL_TIMEOUT_MILLIS)
        val name = try {
            hello?.body()?.string()
        } catch (_: IndexOutOfBoundsException) {
            null
        }
        if (name == null) {
            bus.close()
            return false
        }
        server.busName = name
        val embedded = call(
            bus,
            REGISTRY,
            "Embed",
            "(so)",
            DBusWriter().apply { reference(name, AtspiServer.ROOT_PATH) }.bytes(),
            CALL_TIMEOUT_MILLIS,
        )
        val parent = try {
            embedded?.body()?.let { reader ->
                reader.align(8)
                reader.string() to reader.string()
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        }
        if (parent == null) {
            bus.close()
            return false
        }
        server.registryParent = parent
        connection = bus
        return true
    }

    /**
     * Answers whatever has been asked since the last turn, without waiting.
     *
     * A call is answered in the turn it arrives in. Anything on the bus that is not a call to
     * one of these objects, such as the bus's own notices of who came and went, is not for us.
     */
    fun pump() {
        val bus = connection ?: return
        while (true) {
            val bytes = bus.receive(0) ?: return
            val call = AtspiWire.parseCall(bytes) ?: continue
            val reply = server.handle(call) { ++serial } ?: continue
            if (!bus.send(reply)) {
                lost()
                return
            }
        }
    }

    /**
     * Publishes the tree the window has now, and says what changed since the last one.
     *
     * The tree is replaced first and the signals follow, because a client that hears of a new
     * control asks about it at once and must be answered from the tree that has it.
     */
    fun update(tree: AtspiTree) {
        val old = server.tree
        server.tree = tree
        val bus = connection
        val announce = hasTree
        hasTree = true
        if (bus == null || !announce) return
        val signals = ArrayList<ByteArray>()
        diff(old, tree, signals)
        for (signal in signals) {
            if (!bus.send(signal)) {
                lost()
                return
            }
        }
    }

    /** The window gained or lost the keyboard. */
    fun windowActive(active: Boolean) {
        if (server.windowActive == active) return
        server.windowActive = active
        val bus = connection ?: return
        val ok = bus.send(server.windowEvent(++serial, if (active) "Activate" else "Deactivate")) &&
            bus.send(server.stateChanged(++serial, AtspiServer.FRAME, AtspiState.ACTIVE, active))
        if (!ok) lost()
    }

    fun close() {
        connection?.close()
        connection = null
    }

    private fun lost() {
        connection?.close()
        connection = null
    }

    private fun diff(old: AtspiTree, tree: AtspiTree, signals: MutableList<ByteArray>) {
        val added = tree.nodes.keys - old.nodes.keys
        val removed = old.nodes.keys - tree.nodes.keys
        // Only the top of what appeared or disappeared is announced: a client that hears a
        // panel arrived asks for its children, and one signal per leaf would be a flood.
        for (id in removed) {
            val parent = old.nodes.getValue(id).parent
            if (parent in removed) continue
            val index = siblings(old, parent).indexOf(id)
            signals += server.childrenChanged(++serial, parent, index, id, added = false)
        }
        for (id in added) {
            val parent = tree.nodes.getValue(id).parent
            if (parent in added) continue
            val index = siblings(tree, parent).indexOf(id)
            signals += server.childrenChanged(++serial, parent, index, id, added = true)
        }
        if (old.focused != tree.focused) {
            val lost = old.focused
            if (lost != null && lost in tree.nodes) {
                signals += server.stateChanged(++serial, lost, AtspiState.FOCUSED, false)
            }
            val gained = tree.focused
            if (gained != null) {
                signals += server.stateChanged(++serial, gained, AtspiState.FOCUSED, true)
            }
        }
        for ((id, node) in tree.nodes) {
            val before = old.nodes[id] ?: continue
            if (before.name != node.name) signals += server.nameChanged(++serial, id, node.name)
            for (state in TRACKED_STATES) {
                val was = before.states and AtspiState.bit(state) != 0L
                val now = node.states and AtspiState.bit(state) != 0L
                if (was != now) signals += server.stateChanged(++serial, id, state, now)
            }
        }
    }

    private fun siblings(tree: AtspiTree, parent: Int): List<Int> =
        if (parent == AtspiTree.FRAME) tree.roots else tree.nodes[parent]?.children ?: emptyList()

    /** Sends one call and waits for its answer, setting aside anything else that arrives. */
    private fun call(
        bus: BusConnection,
        destination: Destination,
        member: String,
        signature: String?,
        body: ByteArray,
        timeoutMillis: Int,
    ): DBusMessage? {
        serial += 1
        val sent = serial
        if (!bus.send(DBusMessage.methodCall(sent, destination, member, signature, body))) return null
        var waits = timeoutMillis / RECEIVE_SLICE_MILLIS
        while (waits-- > 0) {
            val bytes = bus.receive(RECEIVE_SLICE_MILLIS)
            val message = DBusMessage.parse(bytes ?: continue) ?: continue
            if (message.replySerial == sent.toLong()) {
                return if (message.type == DBusMessage.METHOD_RETURN) message else null
            }
        }
        return null
    }

    private companion object {
        const val CALL_TIMEOUT_MILLIS = 2_000

        /** The session bus starts the accessibility bus the first time it is asked for. */
        const val ACTIVATION_TIMEOUT_MILLIS = 5_000
        const val RECEIVE_SLICE_MILLIS = 100

        /** The states a client subscribes to changes of. */
        val TRACKED_STATES = listOf(
            AtspiState.CHECKED,
            AtspiState.SELECTED,
            AtspiState.ENABLED,
            AtspiState.INDETERMINATE,
        )

        val BUS = Destination("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")
        val A11Y_BUS = Destination("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus")
        val REGISTRY = Destination(
            "org.a11y.atspi.Registry",
            "/org/a11y/atspi/accessible/root",
            "org.a11y.atspi.Socket",
        )
    }
}
