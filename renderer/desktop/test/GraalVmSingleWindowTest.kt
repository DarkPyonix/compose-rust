@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
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
import dev.darkpyonix.composerust.runtime.WindowCaption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val APPEND = -1

private class SingleWindowTestConnection(
    private val initial: List<Mutation>,
) : HostConnection {
    val events = mutableListOf<HostEvent>()
    private var nextBatch: List<Mutation>? = null

    fun queueBatch(batch: List<Mutation>) {
        nextBatch = batch
    }

    override fun init(onMutation: (Mutation) -> Unit) {
        initial.forEach(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        events += event
        val batch = nextBatch
        nextBatch = null
        batch?.forEach(onMutation)
        return 0L
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        val batch = nextBatch
        nextBatch = null
        batch?.forEach(onMutation)
    }

    override fun shutdown() {}

    inline fun <reified T : HostEvent> all(): List<T> = events.filterIsInstance<T>()
}

private fun setWindowMode(
    mode: WindowMode = WindowMode.Windows,
    last: LastWindowClosed = LastWindowClosed.Exit,
) = Mutation.SetWindow(
    Window(
        title = "app",
        width = 640,
        height = 480,
        minWidth = 0,
        minHeight = 0,
        resizable = true,
        chrome = Chrome.Modern,
        titleBar = TitleBar.Normal,
        icon = 0,
        zoomLevel = null,
        mode = mode,
        lastWindowClosed = last,
    ),
)

private fun windowNode(id: Int, vararg props: Pair<PropertyKind, PropertyValue>): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.Window))
    for ((k, v) in props) add(Mutation.SetProp(id, k, v))
    add(Mutation.Insert(0, id, APPEND))
}

private fun textChild(parent: Int, id: Int, label: String): List<Mutation> = listOf(
    Mutation.Create(id, WidgetKind.Text),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)),
    Mutation.Insert(parent, id, APPEND),
)

class GraalVmSingleWindowTest {
    @Test
    fun fr53_9_graalvm_single_window_renders_first_window_and_reports_unavailable_for_second() = runComposeUiTest {
        val connection = SingleWindowTestConnection(
            listOf(setWindowMode()) +
                windowNode(1) +
                textChild(1, 10, "First window") +
                windowNode(2, PropertyKind.OnWindowUnavailable to PropertyValue.Integer(9L)) +
                textChild(2, 20, "Second window"),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())

        setContent {
            NativeWindowContent(host, WindowCaption.None, actions = null)
        }
        waitForIdle()

        onNodeWithText("First window").assertExists()
        onNodeWithText("Second window").assertDoesNotExist()

        val unavailable = connection.all<HostEvent.WindowUnavailable>()
        assertEquals(1, unavailable.size, "expected exactly one WindowUnavailable event for the second Window node")
        assertEquals(2, unavailable[0].nodeId)
        assertEquals(9L, unavailable[0].handlerId)
    }

    @Test
    fun fr53_9_graalvm_single_window_second_window_added_later_emits_unavailable_once() = runComposeUiTest {
        val connection = SingleWindowTestConnection(
            listOf(setWindowMode()) +
                windowNode(1) +
                textChild(1, 10, "First window"),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())

        setContent {
            NativeWindowContent(host, WindowCaption.None, actions = null)
        }
        waitForIdle()

        onNodeWithText("First window").assertExists()
        assertEquals(0, connection.all<HostEvent.WindowUnavailable>().size)

        connection.queueBatch(
            windowNode(2, PropertyKind.OnWindowUnavailable to PropertyValue.Integer(11L)) +
                textChild(2, 20, "Second window"),
        )
        host.renderFrame(1_000_000L)
        waitForIdle()

        onNodeWithText("First window").assertExists()
        onNodeWithText("Second window").assertDoesNotExist()
        val unavailable = connection.all<HostEvent.WindowUnavailable>()
        assertEquals(1, unavailable.size, "second Window node added later must emit WindowUnavailable once")
        assertEquals(2, unavailable[0].nodeId)
        assertEquals(11L, unavailable[0].handlerId)

        // Subsequent updates to the unavailable node do not re-emit WindowUnavailable.
        connection.queueBatch(
            listOf(Mutation.SetProp(2, PropertyKind.WindowTitle, PropertyValue.Text("Still unavailable"))),
        )
        host.renderFrame(2_000_000L)
        waitForIdle()

        assertEquals(1, connection.all<HostEvent.WindowUnavailable>().size)
        onNodeWithText("Second window").assertDoesNotExist()
    }

    @Test
    fun fr53_9_graalvm_single_window_keeps_single_window_mode_unchanged() = runComposeUiTest {
        val connection = SingleWindowTestConnection(
            listOf(setWindowMode(WindowMode.Single)) +
                textChild(0, 10, "Single mode"),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())

        setContent {
            NativeWindowContent(host, WindowCaption.None, actions = null)
        }
        waitForIdle()

        assertNull(host.afterBatch, "single window mode must not attach a WindowManager")
        onNodeWithText("Single mode").assertExists()
        assertEquals(0, connection.all<HostEvent.WindowUnavailable>().size)
    }

    @Test
    fun fr53_9_graalvm_single_window_exit_and_keep_running_lifecycle() = runComposeUiTest {
        var exits = 0
        val connection = SingleWindowTestConnection(
            listOf(setWindowMode(last = LastWindowClosed.Exit)) +
                windowNode(1) +
                textChild(1, 10, "Primary"),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        val session = SingleWindowSession(host, onExit = { exits++ })
        session.attach()

        setContent {
            SingleWindowHostContent(session)
        }
        waitForIdle()

        assertEquals(1, session.activeWindowNodeId)
        onNodeWithText("Primary").assertExists()

        connection.queueBatch(listOf(Mutation.Remove(1)))
        host.renderFrame(1_000_000L)
        waitForIdle()

        assertEquals(0, session.activeWindowNodeId)
        assertEquals(1, exits)
        onNodeWithText("Primary").assertDoesNotExist()
        session.shutdown()
    }

    @Test
    fun fr53_9_graalvm_single_window_title_follows_first_window_only() {
        val titles = mutableListOf<String>()
        val connection = SingleWindowTestConnection(
            listOf(setWindowMode()) +
                windowNode(1, PropertyKind.WindowTitle to PropertyValue.Text("Main")) +
                windowNode(
                    2,
                    PropertyKind.WindowTitle to PropertyValue.Text("Secondary"),
                    PropertyKind.OnWindowUnavailable to PropertyValue.Integer(7L),
                ),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        val session = SingleWindowSession(host, onTitle = { titles += it })
        session.attach()

        assertEquals(listOf("Main"), titles)
        assertEquals(1, session.activeWindowNodeId)
        assertEquals(1, connection.all<HostEvent.WindowUnavailable>().size)

        connection.queueBatch(
            listOf(
                Mutation.SetProp(1, PropertyKind.WindowTitle, PropertyValue.Text("Main updated")),
                Mutation.SetProp(2, PropertyKind.WindowTitle, PropertyValue.Text("Secondary updated")),
            ),
        )
        host.renderFrame(1_000_000L)

        assertEquals(listOf("Main", "Main updated"), titles)
        assertEquals(1, connection.all<HostEvent.WindowUnavailable>().size)
        session.shutdown()
    }
}
