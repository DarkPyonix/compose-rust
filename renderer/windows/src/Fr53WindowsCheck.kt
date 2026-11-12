@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import java.lang.System
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import org.thisisthepy.compose.window.windows.Win32Loop
import org.thisisthepy.compose.window.windows.Win32System

// The checks of several windows in one process on Windows, run by the renderer itself against real
// windows, and named after the requirement like the tests of the other platforms.
//
// They are here and not in a test source set because a Kotlin/Native test executable for
// mingwX64 is a MinGW link, and the Win32 window is C that only an MSVC link has (see
// win32_window.c in the fork's windows-shared). The renderer is the one thing that is linked
// with it, so it carries the checks and runs them when asked, as it does the Korean text check
// (`TextSelfCheck.kt`). `scripts/check-windows-consumer.sh` asks and reads the lines below.
//
// What runs is the real thing under a fake Host: the real window manager, the real Win32
// backend, real windows with real scenes drawn through Direct3D 12. Only the application is
// replaced, by a connection that answers with the mutations a test hands it.

/** Setting this runs the checks: `COMPOSE_RUST_FR53_WINDOWS_CHECK=1 consumer.exe --self-check`. */
internal const val FR53_WINDOWS_CHECK_ENV = "COMPOSE_RUST_FR53_WINDOWS_CHECK"

/** The names of the checks, in the order they run; the build script reads them back from the output. */
internal val FR53_WINDOWS_CHECK_NAMES = listOf(
    "fr53_windows_two_window_nodes_open_two_hwnds_and_close_one",
    "fr53_windows_closing_one_window_does_not_quit",
    "fr53_windows_close_request_reaches_the_app_and_window_stays",
    "fr53_windows_position_round_trips",
    "fr53_windows_one_render_frame_serves_every_window",
    "fr53_windows_focus_follows_the_real_activation",
    "fr53_windows_dragged_window_enters_moves_and_drops_on_a_subscribed_node",
    "fr53_windows_tear_off_ends_the_source_pointer_capture",
    // Last: a quit that was posted stays posted for the process.
    "fr53_windows_last_window_removed_exits_the_loop",
)

/**
 * Runs the checks when [FR53_WINDOWS_CHECK_ENV] is set. Null when not asked, true when every
 * one passed and false otherwise; each result is a line on standard error,
 * `compose-rust: <name>: ok` or `compose-rust: <name>: FAILED <why>`.
 */
internal fun runFr53WindowsChecksIfAsked(): Boolean? {
    if (System.getenv(FR53_WINDOWS_CHECK_ENV) == null) return null
    // In the order of the names above, one body for each.
    val bodies: List<() -> Unit> = listOf(
        ::twoWindowNodesOpenTwoHwndsAndCloseOne,
        ::closingOneWindowDoesNotQuit,
        ::closeRequestReachesTheAppAndWindowStays,
        ::positionRoundTrips,
        ::oneRenderFrameServesEveryWindow,
        ::focusFollowsTheRealActivation,
        ::draggedWindowEntersMovesAndDropsOnASubscribedNode,
        ::tearOffEndsTheSourcePointerCapture,
        ::lastWindowRemovedExitsTheLoop,
    )
    check(bodies.size == FR53_WINDOWS_CHECK_NAMES.size) {
        "${FR53_WINDOWS_CHECK_NAMES.size} checks are named and ${bodies.size} are written"
    }
    val checks = FR53_WINDOWS_CHECK_NAMES.zip(bodies)
    var passed = true
    for ((name, body) in checks) {
        try {
            body()
            System.err.println("compose-rust: $name: ok")
        } catch (error: Throwable) {
            passed = false
            System.err.println("compose-rust: $name: FAILED ${error.message}")
        }
    }
    System.err.println(
        if (passed) "compose-rust fr53 windows checks: ok" else "compose-rust fr53 windows checks: FAILED",
    )
    return passed
}

// ---- the checks ------------------------------------------------------------------------

private fun twoWindowNodesOpenTwoHwndsAndCloseOne() {
    val h = Fr53Harness(listOf(setWindow()) + windowNode(1, PropertyKind.WindowTitle to text("One")) + windowNode(2))
    try {
        check(h.loop.manager.openWindows == listOf(1, 2)) { "open windows are ${h.loop.manager.openWindows}, not [1, 2]" }
        check(h.backend.openCount == 2) { "${h.backend.openCount} windows are open, not 2" }
        val handles = h.backend.handles
        check(handles.size == 2 && handles[0] != handles[1]) { "the two windows share a handle" }
        check(handles.all { isWindow(it) }) { "a handle is not a window" }
        val first = handles[0]
        val second = handles[1]

        h.batch(Mutation.Remove(1))
        h.until("the first window to go") { !isWindow(first) }
        check(isWindow(second)) { "removing the first node took the second window with it" }
        check(h.backend.openCount == 1) { "${h.backend.openCount} windows are open after one was removed" }
        check(h.loop.manager.openWindows == listOf(2)) { "open windows are ${h.loop.manager.openWindows}, not [2]" }
    } finally {
        h.close()
    }
}

private fun closingOneWindowDoesNotQuit() {
    val h = Fr53Harness(listOf(setWindow()) + windowNode(1) + windowNode(2))
    try {
        val second = h.backend.handles[1]
        h.batch(Mutation.Remove(1))
        // Long enough for a quit that was going to be posted to come through the pump.
        h.forMillis(300)
        check(Win32System.windows.quitPosted() == null) { "a quit was posted when one of two windows closed" }
        check(h.exits == 0) { "the manager ended the loop with a window still open" }
        check(isWindow(second)) { "the other window is gone" }
        check(h.backend.openCount == 1) { "${h.backend.openCount} windows are open, not 1" }
    } finally {
        h.close()
    }
}

private fun closeRequestReachesTheAppAndWindowStays() {
    val h = Fr53Harness(
        listOf(setWindow()) +
            windowNode(1, PropertyKind.OnWindowCloseRequested to handler(11)) +
            windowNode(2, PropertyKind.OnWindowCloseRequested to handler(12)),
    )
    try {
        val first = h.backend.handles[0]
        // What the close button of the caption, Alt+F4 and the system menu send.
        selectWindow(first)
        Win32Loop.action(2)
        h.until("WindowCloseRequested") {
            h.connection.events.any { it is HostEvent.WindowCloseRequested && it.nodeId == 1 && it.handlerId == 11L }
        }
        h.forMillis(100)
        check(isWindow(first)) { "the window closed itself on a request to close; closing is the application's" }
        check(h.loop.manager.openWindows == listOf(1, 2)) { "open windows are ${h.loop.manager.openWindows}" }
        check(h.connection.events.none { it is HostEvent.WindowCloseRequested && it.nodeId == 2 }) {
            "the other window was told too"
        }
        check(h.connection.events.count { it is HostEvent.WindowCloseRequested } == 1) { "the request came more than once" }

        // The application answers by removing the node, and that closes it.
        h.batch(Mutation.Remove(1))
        h.until("the window to close once the node is removed") { !isWindow(first) }
    } finally {
        h.close()
    }
}

private fun positionRoundTrips() {
    val h = Fr53Harness(
        listOf(setWindow()) +
            windowNode(
                1,
                PropertyKind.WindowPosition to PropertyValue.Bytes(floats(100f, 120f)),
                PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
                PropertyKind.OnWindowMoved to handler(5),
            ),
    )
    try {
        val window = checkNotNull(h.backend.find(1)) { "no window for node 1" }
        val opened = checkNotNull(window.bounds()) { "the window has no bounds" }
        check(near(opened.x, 100f) && near(opened.y, 120f)) { "opened at (${opened.x}, ${opened.y}), asked for (100, 120)" }

        // The application moves it: the window goes there and the real place comes back.
        h.batch(Mutation.SetProp(1, PropertyKind.WindowPosition, PropertyValue.Bytes(floats(300f, 200f))))
        h.until("the window to move to (300, 200)") {
            val at = window.bounds()
            at != null && near(at.x, 300f) && near(at.y, 200f)
        }
        h.until("WindowMoved to come back") {
            h.loop.manager.flushFrame()
            h.connection.events.filterIsInstance<HostEvent.WindowMoved>()
                .any { it.nodeId == 1 && it.handlerId == 5L && near(it.x, 300f) && near(it.y, 200f) }
        }

        // The user moves it. The application sending the same position again is no change, and
        // the window stays where the user put it.
        val scale = window.pixelsPerPoint
        selectWindow(window.handle)
        platform.windows.SetWindowPos(
            window.handle.reinterpret(),
            null,
            500,
            400,
            0,
            0,
            (platform.windows.SWP_NOSIZE or platform.windows.SWP_NOZORDER or platform.windows.SWP_NOACTIVATE).toUInt(),
        )
        val wantedX = 500f / scale
        val wantedY = 400f / scale
        h.until("the window to be where the user put it") {
            val at = window.bounds()
            at != null && near(at.x, wantedX) && near(at.y, wantedY)
        }
        h.batch(Mutation.SetProp(1, PropertyKind.WindowPosition, PropertyValue.Bytes(floats(300f, 200f))))
        h.forMillis(150)
        val after = checkNotNull(window.bounds())
        check(near(after.x, wantedX) && near(after.y, wantedY)) {
            "the window was put back to (${after.x}, ${after.y}) from where the user moved it"
        }
        h.until("WindowMoved for the user's move") {
            h.loop.manager.flushFrame()
            h.connection.events.filterIsInstance<HostEvent.WindowMoved>()
                .any { near(it.x, wantedX) && near(it.y, wantedY) }
        }
    } finally {
        h.close()
    }
}

private fun oneRenderFrameServesEveryWindow() {
    val h = Fr53Harness(listOf(setWindow()) + windowNode(1) + windowNode(2) + windowNode(3))
    try {
        // Let the windows paint their first frames, then see how often the Host is asked.
        h.forMillis(300)
        val before = h.connection.renderFrames
        repeat(5) { h.requests.request() }
        h.until("a render_frame for the requests") { h.connection.renderFrames > before }
        h.forMillis(300)
        val asked = h.connection.renderFrames - before
        check(asked == 1) { "five requests with three windows open asked the Host for $asked frames, not 1" }
    } finally {
        h.close()
    }
}

private fun focusFollowsTheRealActivation() {
    val h = Fr53Harness(
        listOf(setWindow()) +
            windowNode(1, PropertyKind.OnWindowFocusChanged to handler(1)) +
            windowNode(2, PropertyKind.OnWindowFocusChanged to handler(2)),
    )
    try {
        h.forMillis(200)
        val two = checkNotNull(h.backend.find(2)) { "no window for node 2" }
        two.focus()
        // The system may refuse to give a process the foreground (its lock), and then the
        // window flashes and nothing is reported. What must hold either way is that the node is
        // told exactly what the window's activation is.
        h.waitFor(2000) {
            two.isKey && h.connection.events.any { it is HostEvent.WindowFocusChanged && it.nodeId == 2 && it.focused }
        }
        h.forMillis(100)
        val told = h.connection.events.filterIsInstance<HostEvent.WindowFocusChanged>().filter { it.nodeId == 2 }
        if (two.isKey) {
            check(told.lastOrNull()?.focused == true) { "window 2 is the key window and its node was not told" }
        } else {
            check(told.none { it.focused }) { "its node was told window 2 gained focus, and it is not the key window" }
        }
        val one = checkNotNull(h.backend.find(1))
        val oneTold = h.connection.events.filterIsInstance<HostEvent.WindowFocusChanged>().filter { it.nodeId == 1 }
        if (one.isKey) {
            check(oneTold.lastOrNull()?.focused == true) { "window 1 is the key window and its node was not told" }
        } else {
            check(oneTold.lastOrNull()?.focused != true) { "its node was told window 1 has focus, and it is not the key window" }
        }
    } finally {
        h.close()
    }
}

private fun draggedWindowEntersMovesAndDropsOnASubscribedNode() {
    val h = Fr53Harness(
        listOf(setWindow()) +
            windowNode(
                2,
                PropertyKind.WindowChrome to SYSTEM_CHROME,
                PropertyKind.WindowPosition to PropertyValue.Bytes(floats(600f, 120f)),
                PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
            ) + strip(2, 20, PropertyKind.OnWindowDragOver to handler(33)),
        scriptPointer = true,
    )
    try {
        val pointer = checkNotNull(h.scripted) { "the harness has no scripted pointer" }
        val over = checkNotNull(h.backend.find(2)) { "no window for node 2" }
        // Where the strip was laid out, found by asking rather than assumed.
        var inside: ScreenPoint? = null
        h.until("the subscribed node to report where it was laid out") {
            inside = pointInsideTarget(h, 2)
            inside != null
        }
        val at = checkNotNull(inside)
        val first = checkNotNull(h.loop.dropTargets.targetAt(2, at.x, at.y))
        val second = checkNotNull(h.loop.dropTargets.targetAt(2, at.x + 3f, at.y + 1f)) {
            "the subscribed node is smaller than a few dp"
        }
        check(first.nodeId == 20 && second.nodeId == 20) { "the node under the point is ${first.nodeId}, not 20" }
        check(near(second.x - first.x, 3f) && near(second.y - first.y, 1f)) {
            "3 by 1 dp on the screen is ${second.x - first.x} by ${second.y - first.y} in the node"
        }
        val origin = checkNotNull(over.contentOrigin()) { "window 2 has no content origin" }

        // A window is torn off with the pointer held, away from window 2. It has a strip of
        // its own in the same place, which a dragged window must not hit.
        pointer.at = ScreenPointer(origin.x - 300f, origin.y - 60f, true)
        h.batch(
            windowNode(
                1,
                PropertyKind.WindowChrome to SYSTEM_CHROME,
                PropertyKind.DragFromPointer to PropertyValue.Bytes(dragFrom(1, 0f, 0f)),
                PropertyKind.OnWindowDrag to handler(11),
            ) + strip(1, 10, PropertyKind.OnWindowDragOver to handler(44)),
        )
        check(1 in pointer.following) { "the window opened with DragFromPointer was not asked to follow the pointer" }
        fun overs() = h.connection.events.filterIsInstance<HostEvent.WindowDragOver>()
        fun kinds() = overs().map { it.kind }
        h.loop.manager.flushFrame()
        check(overs().isEmpty()) { "a drag away from every other window reported ${kinds()}" }

        // Over the strip of window 2: Enter, in the node's own coordinates.
        pointer.at = ScreenPointer(origin.x + at.x, origin.y + at.y, true)
        h.loop.manager.flushFrame()
        check(kinds() == listOf(OVER_ENTER)) { "entering the node reported ${kinds()}, not Enter" }
        val enter = overs()[0]
        check(enter.nodeId == 20 && enter.handlerId == 33L) {
            "Enter went to node ${enter.nodeId} handler ${enter.handlerId}, not node 20 handler 33"
        }
        check(enter.window == 1) { "Enter names window ${enter.window} as the dragged one, not 1" }
        check(near(enter.x, first.x) && near(enter.y, first.y)) {
            "Enter is at (${enter.x}, ${enter.y}) of the node, not (${first.x}, ${first.y})"
        }
        val drag = h.connection.events.filterIsInstance<HostEvent.WindowDrag>().lastOrNull()
        check(drag != null && drag.nodeId == 1 && drag.handlerId == 11L && drag.overWindow == 2) {
            "the dragged window was not told it is over window 2: $drag"
        }

        // Further inside: Move.
        pointer.at = ScreenPointer(origin.x + at.x + 3f, origin.y + at.y + 1f, true)
        h.loop.manager.flushFrame()
        check(kinds() == listOf(OVER_ENTER, OVER_MOVE)) { "moving inside the node reported ${kinds()}" }
        val move = overs()[1]
        check(near(move.x, second.x) && near(move.y, second.y)) {
            "Move is at (${move.x}, ${move.y}) of the node, not (${second.x}, ${second.y})"
        }

        // The button is released there: Drop, and the drag ends.
        checkNotNull(pointer.listeners[1]) { "window 1 was opened with no listener" }.onFollowEnded(false)
        check(kinds() == listOf(OVER_ENTER, OVER_MOVE, OVER_DROP)) { "releasing over the node reported ${kinds()}" }
        val drop = overs()[2]
        check(drop.nodeId == 20 && drop.window == 1 && near(drop.x, second.x) && near(drop.y, second.y)) {
            "Drop is $drop, not on node 20 at (${second.x}, ${second.y}) from window 1"
        }
        check(overs().none { it.nodeId == 10 }) { "the dragged window's own node was hit" }
        val end = h.connection.events.filterIsInstance<HostEvent.WindowDrag>().last()
        check(end.phase == DRAG_END && end.overWindow == 2) { "the drag ended with $end" }
    } finally {
        h.close()
    }
}

private fun tearOffEndsTheSourcePointerCapture() {
    // The node is a control, and it asks for drag-over only so that it says where it is.
    val h = Fr53Harness(
        listOf(setWindow()) +
            windowNode(
                1,
                PropertyKind.WindowChrome to SYSTEM_CHROME,
                PropertyKind.WindowSize to PropertyValue.Bytes(floats(400f, 300f)),
            ) + strip(1, 10, PropertyKind.OnClick to handler(77), PropertyKind.OnWindowDragOver to handler(78)),
        scriptPointer = true,
    )
    try {
        val pointer = checkNotNull(h.scripted) { "the harness has no scripted pointer" }
        val source = checkNotNull(h.backend.find(1)) { "no window for node 1" }
        var inside: ScreenPoint? = null
        h.until("the node to report where it was laid out") {
            inside = pointInsideTarget(h, 1)
            inside != null
        }
        val at = checkNotNull(inside)
        val scale = source.pixelsPerPoint
        val x = ((at.x + 3f) * scale).toInt()
        val y = ((at.y + 3f) * scale).toInt()
        fun clicks() = h.connection.events.count { it is HostEvent.Clicked && it.nodeId == 10 && it.handlerId == 77L }

        // A press and a release on the node is a click, so the messages below reach it at all.
        postButton(source.handle, down = true, x, y)
        postButton(source.handle, down = false, x, y)
        h.until("the click of a press and a release on the node") { clicks() == 1 }

        // Pressed again, and a window is torn off under the pointer while it is held.
        postButton(source.handle, down = true, x, y)
        h.forMillis(150)
        pointer.at = ScreenPointer(0f, 0f, true)
        h.batch(
            windowNode(
                2,
                PropertyKind.WindowChrome to SYSTEM_CHROME,
                PropertyKind.DragFromPointer to PropertyValue.Bytes(dragFrom(1, 0f, 0f)),
            ),
        )
        check(2 in pointer.following) { "the window opened with DragFromPointer was not asked to follow the pointer" }
        // The release still arrives at the window the button went down in, which holds the
        // mouse. It is no longer the node's: the gesture ended when the window was torn off.
        postButton(source.handle, down = false, x, y)
        h.forMillis(300)
        check(clicks() == 1) { "the node the pointer was torn off from still took the release as a click" }

        // The next press is a gesture of its own.
        postButton(source.handle, down = true, x, y)
        postButton(source.handle, down = false, x, y)
        h.until("a click after the torn-off gesture") { clicks() == 2 }
    } finally {
        h.close()
    }
}

private fun lastWindowRemovedExitsTheLoop() {
    val h = Fr53Harness(listOf(setWindow(LastWindowClosed.Exit)) + windowNode(1) + windowNode(2))
    try {
        h.batch(Mutation.Remove(1))
        h.forMillis(100)
        check(h.exits == 0 && Win32System.windows.quitPosted() == null) { "the loop ended with a window still open" }
        h.batch(Mutation.Remove(2))
        check(h.exits == 1) { "the manager asked to end the loop ${h.exits} times after the last window was removed" }
        h.until("the quit to come through the pump") { Win32System.windows.quitPosted() != null }
        check(Win32System.windows.quitPosted() == 0) { "the quit carried code ${Win32System.windows.quitPosted()}" }
        check(h.backend.openCount == 0) { "${h.backend.openCount} windows are still open" }
    } finally {
        h.close()
    }
}

// ---- the harness -----------------------------------------------------------------------

private class Fr53Connection(private val initial: List<Mutation>) : HostConnection {
    val events = ArrayList<HostEvent>()
    var renderFrames = 0
    private var next: List<Mutation> = emptyList()

    /** What the application answers to the next event it is sent. */
    fun queue(mutations: List<Mutation>) {
        next = mutations
    }

    override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        events += event
        if (event is HostEvent.Clicked) {
            val batch = next
            next = emptyList()
            batch.forEach(onMutation)
        }
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        renderFrames++
    }

    override fun shutdown() {}
}

/**
 * The real window system with the pointer of a script in front of it.
 *
 * The windows are the real ones in every other way. Where the pointer is, and whether its
 * button is held, is what the check says, because the real pointer is wherever the person at
 * the machine left it; a window asked to follow it is written down instead of moved, and the
 * check ends the follow through the listener the window was opened with.
 */
private class ScriptedPointerBackend(private val real: WindowBackend) : WindowBackend {
    var at = ScreenPointer(0f, 0f, true)
    val following = HashSet<Int>()
    val listeners = HashMap<Int, PlatformWindowListener>()

    override val supportsMultipleWindows: Boolean get() = real.supportsMultipleWindows

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        val window = real.open(config, listener) ?: return null
        val nodeId = config.nodeId
        listeners[nodeId] = listener
        return object : PlatformWindow by window {
            override fun pointerOnScreen(): ScreenPointer? = at

            override fun followPointer(anchorX: Float, anchorY: Float): Boolean {
                following += nodeId
                return true
            }
        }
    }

    override fun setApplicationListener(listener: ApplicationListener?) = real.setApplicationListener(listener)
}

/** A Host that is a script, the real window manager and loop, real windows. */
private class Fr53Harness(initial: List<Mutation>, scriptPointer: Boolean = false) {
    val requests = FrameRequestSource()
    val connection = Fr53Connection(initial)
    val host = ComposeRustHost(connection)
    var exits = 0

    /** The pointer the manager sees, for a check that asked to script it. */
    var scripted: ScriptedPointerBackend? = null
        private set
    val loop = MultiWindowLoop(
        host,
        requests,
        windowSystem = { real ->
            if (scriptPointer) ScriptedPointerBackend(real).also { scripted = it } else real
        },
    ) {
        exits++
        Win32System.windows.postQuit(0)
    }
    val backend: WindowsWindowBackend get() = loop.backend

    init {
        host.start(requests)
        loop.attach()
        check(backend.openCount > 0) {
            "no window opened; a machine without a graphics card needs DXC_D3D12_WARP=1"
        }
    }

    /** The application sends [mutations] as the answer to the next event, which this sends. */
    fun batch(vararg mutations: Mutation) {
        batch(mutations.toList())
    }

    fun batch(mutations: List<Mutation>) {
        connection.queue(mutations)
        host.dispatch(HostEvent.Clicked(nodeId = 0, handlerId = 0))
    }

    private var busy = true

    private fun step() {
        busy = loop.step(rest = !busy)
    }

    /** Runs the loop until [condition] holds, or fails after [timeoutMillis]. */
    fun until(what: String, timeoutMillis: Long = 5000, condition: () -> Boolean) {
        check(waitFor(timeoutMillis, condition)) { "timed out after $timeoutMillis ms waiting for $what" }
    }

    /** Runs the loop until [condition] holds. False when [timeoutMillis] passed first. */
    fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMillis) return false
            step()
        }
        return true
    }

    /** Runs the loop for [millis]. */
    fun forMillis(millis: Long) {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow().inWholeMilliseconds < millis) step()
    }

    fun close() {
        loop.stop()
    }
}

/**
 * A point of window [windowId]'s content, in dp, a little inside the first node that asked to
 * hear a dragged window pass over it, or null while no such node has been laid out. Looked for
 * on a grid, so a check does not have to know where a design system puts a window's content.
 */
private fun pointInsideTarget(h: Fr53Harness, windowId: Int): ScreenPoint? {
    var y = 0f
    while (y < 300f) {
        var x = 0f
        while (x < 400f) {
            if (h.loop.dropTargets.targetAt(windowId, x, y) != null) return ScreenPoint(x + 1f, y + 1f)
            x += 4f
        }
        y += 4f
    }
    return null
}

/**
 * Posts what the system posts when the left button goes down or comes up at ([x], [y]) of the
 * window's client area, in pixels. The window's own procedure hears it like any other.
 */
private fun postButton(handle: COpaquePointer, down: Boolean, x: Int, y: Int) {
    val where = ((y and 0xFFFF) shl 16) or (x and 0xFFFF)
    val posted = platform.windows.PostMessageW(
        handle.reinterpret(),
        if (down) WM_LBUTTONDOWN else WM_LBUTTONUP,
        if (down) MK_LBUTTON else 0uL,
        where.toLong(),
    )
    check(posted != 0) { "the button message could not be posted to the window" }
}

private const val WM_LBUTTONDOWN = 0x0201u
private const val WM_LBUTTONUP = 0x0202u
private const val MK_LBUTTON = 1uL

/** `WindowDragOver` kinds and the `WindowDrag` phase a release ends with, as the protocol numbers them. */
private const val OVER_ENTER = 1
private const val OVER_MOVE = 2
private const val OVER_DROP = 4
private const val DRAG_END = 2

/** `Chrome::System`: the content starts at the client area's corner, under the system's caption. */
private val SYSTEM_CHROME = PropertyValue.Integer(2)

/** `DragFromPointer`: the pointer's id, then the anchor in the new window's content. */
private fun dragFrom(pointerId: Int, anchorX: Float, anchorY: Float): ByteArray {
    val out = ByteArray(12)
    for (byte in 0 until 4) out[byte] = (pointerId shr (8 * byte)).toByte()
    floats(anchorX, anchorY).copyInto(out, 4)
    return out
}

/** A container with a line of text in window or node [parent], with [props] on the container. */
private fun strip(parent: Int, id: Int, vararg props: Pair<PropertyKind, PropertyValue>): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.Box))
    for ((kind, value) in props) add(Mutation.SetProp(id, kind, value))
    add(Mutation.Create(id + 1000, WidgetKind.Text))
    add(Mutation.SetProp(id + 1000, PropertyKind.Text, PropertyValue.Text("a strip of tabs")))
    add(Mutation.Insert(id, id + 1000, APPEND))
    add(Mutation.Insert(parent, id, APPEND))
}

private fun isWindow(handle: COpaquePointer): Boolean = platform.windows.IsWindow(handle.reinterpret()) != 0

private fun near(actual: Float, expected: Float): Boolean = abs(actual - expected) <= 1f

private fun text(value: String) = PropertyValue.Text(value)

private fun handler(id: Long) = PropertyValue.Integer(id)

private fun floats(vararg values: Float): ByteArray {
    val out = ByteArray(values.size * 4)
    values.forEachIndexed { index, value ->
        val bits = value.toRawBits()
        for (byte in 0 until 4) out[index * 4 + byte] = (bits shr (8 * byte)).toByte()
    }
    return out
}

private fun setWindow(last: LastWindowClosed = LastWindowClosed.Exit) = Mutation.SetWindow(
    Window(
        chrome = Chrome.Modern,
        titleBar = TitleBar.Normal,
        title = "",
        icon = 0,
        width = 0,
        height = 0,
        minWidth = 0,
        minHeight = 0,
        resizable = true,
        zoomLevel = null,
        mode = WindowMode.Windows,
        lastWindowClosed = last,
    ),
)

/** A `Window` node under the root with its properties. */
private fun windowNode(id: Int, vararg props: Pair<PropertyKind, PropertyValue>): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.Window))
    for ((kind, value) in props) add(Mutation.SetProp(id, kind, value))
    add(Mutation.Insert(0, id, APPEND))
}

private const val APPEND = -1
