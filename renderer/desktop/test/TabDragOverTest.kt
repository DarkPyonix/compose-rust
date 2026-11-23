package dev.darkpyonix.composerust.test

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
import dev.darkpyonix.composerust.ui.platform.ApplicationListener
import dev.darkpyonix.composerust.ui.platform.DropTarget
import dev.darkpyonix.composerust.ui.platform.DropTargets
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.PlatformWindow
import dev.darkpyonix.composerust.ui.platform.PlatformWindowConfig
import dev.darkpyonix.composerust.ui.platform.PlatformWindowListener
import dev.darkpyonix.composerust.ui.platform.ScreenBounds
import dev.darkpyonix.composerust.ui.platform.ScreenPointer
import dev.darkpyonix.composerust.ui.platform.WindowBackend
import dev.darkpyonix.composerust.ui.platform.WindowManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation

// A tab dragged by a phone's or a tablet's own drag and drop over another window of the same
// application. The window under it is told the way a desktop window is told about a dragged
// window: Enter, Move once per frame, Leave and Drop, to the node that registered for them,
// at the place in that node. The event names the window the tab is over.

private const val SOURCE = 1
private const val RECEIVER = 2
private const val SOURCE_TARGET = 10
private const val TARGET = 20
private const val UNREGISTERED = 21
private const val SOURCE_HANDLER = 5L
private const val TARGET_HANDLER = 6L

private class TdWindow : PlatformWindow {
    override fun setListener(listener: PlatformWindowListener) {}
    override fun setTitle(title: String) {}
    override fun setPosition(x: Float, y: Float) {}
    override fun setContentSize(width: Float, height: Float) {}
    override fun bounds(): ScreenBounds? = null
    override fun focus() {}
    override val isKey: Boolean get() = false
    override fun pointerOnScreen(): ScreenPointer? = null
    override fun followPointer(anchorX: Float, anchorY: Float): Boolean = false
    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        awaitCancellation()
    }
    override fun close() {}
}

private class TdBackend : WindowBackend {
    override val supportsMultipleWindows: Boolean get() = true
    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow = TdWindow()
    override fun setApplicationListener(listener: ApplicationListener?) {}
}

private class TdConnection(private val initial: List<Mutation>) : HostConnection {
    val overs = mutableListOf<HostEvent.WindowDragOver>()

    override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        if (event is HostEvent.WindowDragOver) overs += event
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {}

    override fun shutdown() {}
}

/** The left half of the receiving window's strip is a registered node, the right half is not. */
private val targets = DropTargets { window, x, y ->
    when {
        window == RECEIVER && y < 50f && x < 100f -> DropTarget(TARGET, x / 2f, y / 2f)
        window == RECEIVER && y < 50f -> DropTarget(UNREGISTERED, x / 2f, y / 2f)
        window == SOURCE && y < 50f -> DropTarget(SOURCE_TARGET, x / 2f, y / 2f)
        else -> null
    }
}

class TabDragOverTest {
    private fun child(parent: Int, id: Int, handler: Long?): List<Mutation> = buildList {
        add(Mutation.Create(id, WidgetKind.Box))
        if (handler != null) add(Mutation.SetProp(id, PropertyKind.OnWindowDragOver, PropertyValue.Integer(handler)))
        add(Mutation.Insert(parent, id, -1))
    }

    private fun start(): Pair<TdConnection, WindowManager> {
        val connection = TdConnection(
            buildList {
                add(
                    Mutation.SetWindow(
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
                            lastWindowClosed = LastWindowClosed.Exit,
                        ),
                    ),
                )
                for (window in listOf(SOURCE, RECEIVER)) {
                    add(Mutation.Create(window, WidgetKind.Window))
                    add(Mutation.Insert(0, window, -1))
                }
                addAll(child(SOURCE, SOURCE_TARGET, SOURCE_HANDLER))
                addAll(child(RECEIVER, TARGET, TARGET_HANDLER))
                addAll(child(RECEIVER, UNREGISTERED, null))
            },
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())
        val manager = WindowManager(host, TdBackend(), onExit = {}, dropTargets = targets)
        manager.attach()
        return connection to manager
    }

    private fun HostEvent.WindowDragOver.line() = "$nodeId kind=$kind window=$window at ${x.toInt()},${y.toInt()}"

    @Test
    fun fr53_a_tab_over_another_window_reaches_its_drop_target() {
        val (connection, manager) = start()
        manager.tabDragMoved(RECEIVER, SOURCE, 10f, 10f)
        manager.flushFrame()
        // Two moves in one frame are one Move, at the last place.
        manager.tabDragMoved(RECEIVER, SOURCE, 20f, 20f)
        manager.tabDragMoved(RECEIVER, SOURCE, 30f, 30f)
        manager.flushFrame()
        manager.flushFrame()
        manager.tabDropped(RECEIVER, SOURCE, 40f, 40f)
        manager.tabDragEnded()
        assertEquals(
            listOf(
                "$TARGET kind=1 window=$RECEIVER at 5,5",
                "$TARGET kind=2 window=$RECEIVER at 15,15",
                "$TARGET kind=4 window=$RECEIVER at 20,20",
            ),
            connection.overs.map { it.line() },
        )
        assertEquals(TARGET_HANDLER, connection.overs.first().handlerId)
    }

    @Test
    fun fr53_a_tab_moving_off_its_target_or_out_of_the_window_hears_leave() {
        val (connection, manager) = start()
        manager.tabDragMoved(RECEIVER, SOURCE, 10f, 10f)
        manager.flushFrame()
        // Onto the half of the strip that registered nothing.
        manager.tabDragMoved(RECEIVER, SOURCE, 150f, 10f)
        manager.flushFrame()
        manager.tabDragMoved(RECEIVER, SOURCE, 10f, 10f)
        manager.flushFrame()
        manager.tabDragExited(RECEIVER)
        manager.tabDragExited(RECEIVER)
        assertEquals(
            listOf(
                "$TARGET kind=1 window=$RECEIVER at 5,5",
                "$TARGET kind=3 window=$RECEIVER at 5,5",
                "$TARGET kind=1 window=$RECEIVER at 5,5",
                "$TARGET kind=3 window=$RECEIVER at 5,5",
            ),
            connection.overs.map { it.line() },
        )
    }

    @Test
    fun fr53_a_tab_lands_only_on_a_registered_node_of_another_window() {
        val (connection, manager) = start()
        assertEquals(true, manager.tabDragLands(RECEIVER, SOURCE, 10f, 10f))
        assertEquals(false, manager.tabDragLands(RECEIVER, SOURCE, 150f, 10f), "a node that registered nothing")
        assertEquals(false, manager.tabDragLands(RECEIVER, SOURCE, 10f, 80f), "no node at all")
        assertEquals(false, manager.tabDragLands(SOURCE, SOURCE, 10f, 10f), "the window the tab came from")
        assertEquals(emptyList<String>(), connection.overs.map { it.line() }, "asking sends nothing")
    }

    @Test
    fun fr53_a_tab_over_its_own_window_is_not_a_drop_target() {
        val (connection, manager) = start()
        manager.tabDragMoved(SOURCE, SOURCE, 10f, 10f)
        manager.flushFrame()
        manager.tabDropped(SOURCE, SOURCE, 10f, 10f)
        assertEquals(emptyList<String>(), connection.overs.map { it.line() })
    }

    @Test
    fun fr53_a_tab_drag_that_ends_elsewhere_leaves_its_target() {
        val (connection, manager) = start()
        manager.tabDragMoved(RECEIVER, SOURCE, 10f, 10f)
        manager.flushFrame()
        // Dropped where nothing is registered, then the system ends the drag everywhere.
        manager.tabDropped(RECEIVER, SOURCE, 150f, 10f)
        manager.tabDragEnded()
        assertEquals(
            listOf(
                "$TARGET kind=1 window=$RECEIVER at 5,5",
                "$TARGET kind=3 window=$RECEIVER at 5,5",
            ),
            connection.overs.map { it.line() },
        )
    }
}
