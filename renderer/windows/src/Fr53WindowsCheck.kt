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
    val checks: List<Pair<String, () -> Unit>> = listOf(
        FR53_WINDOWS_CHECK_NAMES[0] to ::twoWindowNodesOpenTwoHwndsAndCloseOne,
        FR53_WINDOWS_CHECK_NAMES[1] to ::closingOneWindowDoesNotQuit,
        FR53_WINDOWS_CHECK_NAMES[2] to ::closeRequestReachesTheAppAndWindowStays,
        FR53_WINDOWS_CHECK_NAMES[3] to ::positionRoundTrips,
        FR53_WINDOWS_CHECK_NAMES[4] to ::oneRenderFrameServesEveryWindow,
        FR53_WINDOWS_CHECK_NAMES[5] to ::focusFollowsTheRealActivation,
        FR53_WINDOWS_CHECK_NAMES[6] to ::lastWindowRemovedExitsTheLoop,
    )
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
    val h = Harness(listOf(setWindow()) + windowNode(1, PropertyKind.WindowTitle to text("One")) + windowNode(2))
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
    val h = Harness(listOf(setWindow()) + windowNode(1) + windowNode(2))
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
    val h = Harness(
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
    val h = Harness(
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
    val h = Harness(listOf(setWindow()) + windowNode(1) + windowNode(2) + windowNode(3))
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
    val h = Harness(
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

private fun lastWindowRemovedExitsTheLoop() {
    val h = Harness(listOf(setWindow(LastWindowClosed.Exit)) + windowNode(1) + windowNode(2))
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

private class Connection(private val initial: List<Mutation>) : HostConnection {
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

/** A Host that is a script, the real window manager and loop, real windows. */
private class Harness(initial: List<Mutation>) {
    val requests = FrameRequestSource()
    val connection = Connection(initial)
    val host = ComposeRustHost(connection)
    var exits = 0
    val loop = MultiWindowLoop(host, requests) {
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
    fun batch(vararg mutations: Mutation) = batch(mutations.toList())

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
