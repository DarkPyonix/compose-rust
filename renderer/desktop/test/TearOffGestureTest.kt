package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalTearOffDrag
import dev.darkpyonix.composerust.runtime.TearOffDrag
import dev.darkpyonix.composerust.runtime.TearOffRequest
import dev.darkpyonix.composerust.runtime.TearOffTargets
import dev.darkpyonix.composerust.runtime.TearOffTrigger
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The gesture that pulls a node carrying a `TearOffKey` out into a window of its own. The
// platform's drag is a fake: what is proved is when the interpreter asks for it, and that it
// asks once, with the node's key, and never for a tap, a scroll or a node without a key.

private const val WIN = 10
private const val TAB = 11
private const val TAB_LABEL = 12
private const val PLAIN = 13
private const val PLAIN_LABEL = 14
private const val COLUMN = 15
private const val APPEND = -1
private const val TAB_CLICK = 7L
private const val PLAIN_CLICK = 8L

private class FakeTearOff(override val trigger: TearOffTrigger) : TearOffDrag {
    val requests = mutableListOf<TearOffRequest>()

    override fun start(request: TearOffRequest): Boolean {
        requests += request
        return true
    }
}

@OptIn(ExperimentalTestApi::class)
class TearOffGestureTest {
    private val frames = FrameRequestSource()

    private fun setWindow(mode: WindowMode) = Mutation.SetWindow(
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
            mode = mode,
            lastWindowClosed = LastWindowClosed.Exit,
        ),
    )

    /** A window holding a keyed tab and a plain box, both pressable, one above the other. */
    private fun mutations(mode: WindowMode) = buildList {
        add(setWindow(mode))
        add(Mutation.Create(WIN, WidgetKind.Window))
        add(Mutation.Insert(0, WIN, APPEND))
        add(Mutation.Create(COLUMN, WidgetKind.Column))
        add(Mutation.Insert(WIN, COLUMN, APPEND))
        add(Mutation.Create(TAB, WidgetKind.Box))
        add(Mutation.SetModifier(TAB, 0, ProtocolModifier.Size(120f, 40f)))
        add(Mutation.SetProp(TAB, PropertyKind.TearOffKey, PropertyValue.Text("tab-a")))
        add(Mutation.SetProp(TAB, PropertyKind.OnClick, PropertyValue.Integer(TAB_CLICK)))
        add(Mutation.Create(TAB_LABEL, WidgetKind.Text))
        add(Mutation.SetProp(TAB_LABEL, PropertyKind.Text, PropertyValue.Text("tab")))
        add(Mutation.Insert(TAB, TAB_LABEL, APPEND))
        add(Mutation.Insert(COLUMN, TAB, APPEND))
        add(Mutation.Create(PLAIN, WidgetKind.Box))
        add(Mutation.SetModifier(PLAIN, 0, ProtocolModifier.Size(120f, 40f)))
        add(Mutation.SetProp(PLAIN, PropertyKind.OnClick, PropertyValue.Integer(PLAIN_CLICK)))
        add(Mutation.Create(PLAIN_LABEL, WidgetKind.Text))
        add(Mutation.SetProp(PLAIN_LABEL, PropertyKind.Text, PropertyValue.Text("plain")))
        add(Mutation.Insert(PLAIN, PLAIN_LABEL, APPEND))
        add(Mutation.Insert(COLUMN, PLAIN, APPEND))
    }

    private class Scene(val connection: FakeHostConnection, val host: ComposeRustHost)

    private fun scene(mode: WindowMode = WindowMode.Windows): Scene {
        val connection = FakeHostConnection(mutations(mode))
        val host = ComposeRustHost(connection)
        host.start(frames)
        return Scene(connection, host)
    }

    private fun clicks(scene: Scene, handler: Long) =
        scene.connection.events.filterIsInstance<HostEvent.Clicked>().count { it.handlerId == handler }

    @Test
    fun fr53_long_press_and_drag_on_a_tear_off_node_starts_the_platform_drag_once() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LongPressThenDrag)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); release()
        }
        assertEquals(1, drag.requests.size, "one gesture asks for one drag")
        val request = drag.requests.single()
        assertEquals("tab-a", request.key)
        assertEquals(TAB, request.nodeId)
        assertEquals(WIN, request.windowId)
        assertEquals(1, request.pointerId, "the mouse is pointer 1, as the Host's pointer events name it")
        assertTrue(request.width > 0f && request.height > 0f)
    }

    @Test
    fun fr53_a_tap_on_a_tear_off_node_is_a_click_and_starts_nothing() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LongPressThenDrag)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, drag.requests.size)
        assertEquals(1, clicks(s, TAB_CLICK), "the tap still reaches the node's own click")
    }

    @Test
    fun fr53_a_drag_that_moves_before_the_long_press_starts_nothing() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LongPressThenDrag)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        // A scroll or a drag of something else moves at once; it is not a tear-off.
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); moveBy(Offset(60f, 0f)); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); release()
        }
        assertEquals(0, drag.requests.size)
    }

    @Test
    fun fr53_tear_off_needs_the_windows_mode() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LongPressThenDrag)
        val s = scene(WindowMode.Single)
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); release()
        }
        assertEquals(0, drag.requests.size, "a single window application has no window to pull a node into")
    }

    @Test
    fun fr53_without_a_starter_a_tear_off_node_is_an_ordinary_node() = runComposeUiTest {
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); release()
        }
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(1, clicks(s, TAB_CLICK))
    }

    @Test
    fun fr53_a_starter_the_window_manager_sets_later_is_used() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LongPressThenDrag)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        waitForIdle()
        // A platform sets it on the node table once its window manager exists, which can be
        // after the window's content was first composed.
        s.host.table.tearOffDrag = drag
        waitForIdle()
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); release()
        }
        assertEquals(listOf("tab-a"), drag.requests.map { it.key })
    }

    @Test
    fun fr53_a_node_without_the_key_is_untouched() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LongPressThenDrag)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        onNodeWithText("plain", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); release()
        }
        onNodeWithText("plain", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, drag.requests.size)
        assertEquals(1, clicks(s, PLAIN_CLICK))
    }

    @Test
    fun fr53_a_press_that_leaves_a_desktop_window_tears_the_node_off() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.LeaveWindow)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        // A drag that stays inside the window is not a tear-off.
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); moveBy(Offset(40f, 40f)); release()
        }
        assertEquals(0, drag.requests.size)
        // The tab sits at the window's top left corner, so a move up and to the left leaves it.
        onRoot().performMouseInput {
            moveTo(Offset(20f, 10f)); press(); moveTo(Offset(-40f, 10f)); moveTo(Offset(-80f, 10f)); release()
        }
        assertEquals(1, drag.requests.size)
        val request = drag.requests.single()
        assertEquals("tab-a", request.key)
        assertEquals(1, request.pointerId)
    }

    @Test
    fun fr53_a_platform_gesture_finds_the_keyed_node_under_it() = runComposeUiTest {
        val drag = FakeTearOff(TearOffTrigger.PlatformGesture)
        val s = scene()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalTearOffDrag provides drag) {
                ComposeRustContent(s.host, Modifier.requiredSize(300.dp, 300.dp), windowNodeId = WIN)
            }
        }
        waitForIdle()
        assertEquals("tab-a", TearOffTargets.keyAt(WIN, 10f, 10f), "the tab is at the window's top left corner")
        assertNull(TearOffTargets.keyAt(WIN, 10f, 70f), "the plain box below it carries no key")
        assertNull(TearOffTargets.keyAt(WIN + 1, 10f, 10f), "another window's point is another window's")
        // The platform's own recogniser starts that drag; the interpreter never does.
        onNodeWithText("tab", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); advanceEventTime(1_000); moveBy(Offset(60f, 0f)); release()
        }
        assertEquals(0, drag.requests.size)
    }
}
