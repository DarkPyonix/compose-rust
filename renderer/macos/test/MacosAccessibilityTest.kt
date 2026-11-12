@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.MacosWindowBackend
import dev.darkpyonix.composerust.ui.platform.PlatformWindowConfig
import dev.darkpyonix.composerust.ui.platform.PlatformWindowListener
import dev.darkpyonix.composerust.ui.platform.ScreenBounds
import dev.darkpyonix.composerust.ui.platform.startSingleWindowTurns
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.MacosAx
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.macos.AxElement
import org.thisisthepy.compose.window.macos.MacosWindow
import org.thisisthepy.compose.window.macos.accessibilitySnapshot
import platform.AppKit.NSAccessibilityProtocol
import platform.AppKit.NSApplication
import platform.AppKit.NSWindow
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val APPEND_CHILD = -1

private class AccessibilityScriptedHost(private val initial: List<Mutation>) : HostConnection {
    val events = mutableListOf<HostEvent>()
    private val responses = HashMap<Long, List<Mutation>>()

    fun onClick(handlerId: Long, vararg mutations: Mutation) {
        responses[handlerId] = mutations.toList()
    }

    override fun init(onMutation: (Mutation) -> Unit) {
        initial.forEach(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        events += event
        if (event is HostEvent.Clicked) {
            responses.remove(event.handlerId)?.forEach(onMutation)
        }
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {}

    override fun shutdown() {}
}

private object NoopWindowListener : PlatformWindowListener {
    override fun onCloseRequested() {}
    override fun onFocusChanged(focused: Boolean) {}
    override fun onMoved(bounds: ScreenBounds) {}
    override fun onFollowEnded(cancelled: Boolean) {}
}

private fun spinLoop(seconds: Double = 0.05) {
    NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(seconds))
}

private fun spinLoopUntil(turns: Int = 60, until: () -> Boolean) {
    var n = 0
    while (!until() && n < turns) {
        spinLoop()
        n++
    }
}

private fun findAxElements(root: Any?, into: MutableList<AxElement> = ArrayList()): List<AxElement> {
    if (root is AxElement) into += root
    val children = (root as? NSAccessibilityProtocol)?.accessibilityChildren().orEmpty()
    for (child in children) findAxElements(child, into)
    return into
}

/**
 * Verifies that the Kotlin/Native macOS renderer exposes the Compose semantics tree in its
 * AppKit accessibility hierarchy from the first frame, and that both `AXPress`
 * (`accessibilityPerformPress`) and pointer clicks reach the Host on `Button` and clickable
 * container nodes (`Row` with `OnClick`).
 */
class MacosAccessibilityTest {
    @Test
    fun nfr8_single_window_exposes_ax_tree_and_delivers_ax_press_and_pointer_clicks() {
        NSApplication.sharedApplication()
        val initial = listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Insert(0, 1, APPEND_CHILD),
            // A Button control (id 2, handler 10).
            Mutation.Create(2, WidgetKind.Button),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("메뉴 열기")),
            Mutation.SetProp(2, PropertyKind.OnClick, PropertyValue.Integer(10L)),
            Mutation.Insert(1, 2, APPEND_CHILD),
            // A clickable Row container (id 3, handler 20) holding a Text child (id 4),
            // matching a navigation item row.
            Mutation.Create(3, WidgetKind.Row),
            Mutation.SetProp(3, PropertyKind.OnClick, PropertyValue.Integer(20L)),
            Mutation.Insert(1, 3, APPEND_CHILD),
            Mutation.Create(4, WidgetKind.Text),
            Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("프로젝트")),
            Mutation.Insert(3, 4, APPEND_CHILD),
            // Status text (id 5) that changes when a button or row is activated.
            Mutation.Create(5, WidgetKind.Text),
            Mutation.SetProp(5, PropertyKind.Text, PropertyValue.Text("화면: 홈")),
            Mutation.Insert(1, 5, APPEND_CHILD),
        )
        val connection = AccessibilityScriptedHost(initial)
        connection.onClick(
            20L,
            Mutation.SetProp(5, PropertyKind.Text, PropertyValue.Text("화면: 프로젝트")),
        )
        connection.onClick(
            10L,
            Mutation.SetProp(5, PropertyKind.Text, PropertyValue.Text("화면: 메뉴")),
        )
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())

        val window = MacosWindow(name = "ax single", width = 420, height = 300)
        @Suppress("DEPRECATION")
        window.setContent {
            ComposeRustContent(host)
        }
        val turns = startSingleWindowTurns(window)
        try {
            // Wait for the single-window turn loop to run without any in-process caller
            // touching accessibilityChildren() first. Once turns run, accessibilityActive is
            // true and the controls have non-zero measured bounds in accessibilitySnapshot().
            spinLoopUntil { window.accessibilityActive }
            assertTrue(
                window.accessibilityActive,
                "Single-window turn loop must activate accessibility from the first frame so external System Events / AX clients see the tree",
            )

            spinLoopUntil {
                accessibilitySnapshot().nodes.any { it.role == MacosAx.BUTTON && it.label == "프로젝트" && it.width > 0.0 }
            }
            val snapshot = accessibilitySnapshot()
            val buttons = snapshot.nodes.filter { it.role == MacosAx.BUTTON }
            assertTrue(
                buttons.any { it.label == "메뉴 열기" && it.width > 0.0 },
                "Button node must appear as AXButton with measured bounds in accessibilitySnapshot, found: ${snapshot.nodes}",
            )
            assertTrue(
                buttons.any { it.label == "프로젝트" && it.width > 0.0 },
                "Clickable Row container must appear as AXButton with its child text and measured bounds, found: ${snapshot.nodes}",
            )
            assertTrue(
                snapshot.nodes.any { it.role == MacosAx.STATIC_TEXT && it.label == "화면: 홈" },
                "Initial status text must appear in accessibilitySnapshot, found: ${snapshot.nodes}",
            )

            // 1. Invoke AXPress on the clickable Row container ("프로젝트") via NSAccessibility.
            val rowButton = assertNotNull(
                findAxElements(window.window.contentView).firstOrNull {
                    it.accessibilityRole() == MacosAx.BUTTON && it.accessibilityLabel() == "프로젝트"
                },
                "Clickable Row AXButton must be reachable from contentView.accessibilityChildren",
            )
            assertTrue(rowButton.accessibilityPerformPress(), "AXPress on clickable Row must succeed")
            assertEquals(
                listOf<HostEvent>(HostEvent.Clicked(nodeId = 3, handlerId = 20L)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )

            // The screen and the accessibility tree update after the click mutation batch.
            spinLoopUntil {
                accessibilitySnapshot().nodes.any { it.role == MacosAx.STATIC_TEXT && it.label == "화면: 프로젝트" }
            }
            assertTrue(
                accessibilitySnapshot().nodes.any { it.role == MacosAx.STATIC_TEXT && it.label == "화면: 프로젝트" },
                "AXPress mutation must update the rendered accessibility tree",
            )

            // 2. Inject a real pointer click on the Button ("메뉴 열기") and verify single-window
            // turns drain and deliver it to the Host.
            val menuButton = assertNotNull(
                findAxElements(window.window.contentView).firstOrNull {
                    it.accessibilityRole() == MacosAx.BUTTON && it.accessibilityLabel() == "메뉴 열기"
                },
            )
            val scale = window.window.backingScaleFactor.toFloat()
            val cx = (menuButton.element.x + menuButton.element.width / 2f) * scale
            val cy = (menuButton.element.y + menuButton.element.height / 2f) * scale
            window.onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.POINTER_DOWN,
                    x = cx,
                    y = cy,
                    buttons = 1,
                    modifiers = 0,
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )
            window.onEvent?.invoke(
                WindowEvent(
                    kind = WindowEvent.POINTER_UP,
                    x = cx,
                    y = cy,
                    buttons = 0,
                    modifiers = 0,
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )
            spinLoopUntil {
                connection.events.filterIsInstance<HostEvent.Clicked>().any { it.handlerId == 10L }
            }
            assertTrue(
                connection.events.filterIsInstance<HostEvent.Clicked>().any { it.nodeId == 2 && it.handlerId == 10L },
                "Pointer click in single-window mode must be drained by pumpHost and reach Host, events: ${connection.events}",
            )
            spinLoopUntil {
                accessibilitySnapshot().nodes.any { it.role == MacosAx.STATIC_TEXT && it.label == "화면: 메뉴" }
            }
            assertTrue(
                accessibilitySnapshot().nodes.any { it.role == MacosAx.STATIC_TEXT && it.label == "화면: 메뉴" },
            )
        } finally {
            turns.invalidate()
            window.host?.close()
            window.window.close()
            spinLoop()
        }
    }

    @Test
    fun nfr8_multi_window_backend_populates_ax_tree_on_open() {
        NSApplication.sharedApplication()
        val initial = listOf(
            Mutation.Create(1, WidgetKind.Row),
            Mutation.SetProp(1, PropertyKind.OnClick, PropertyValue.Integer(30L)),
            Mutation.Insert(0, 1, APPEND_CHILD),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("세션")),
            Mutation.Insert(1, 2, APPEND_CHILD),
        )
        val connection = AccessibilityScriptedHost(initial)
        val host = ComposeRustHost(connection)
        host.start(FrameRequestSource())

        val backend = MacosWindowBackend { _, _, _, _ ->
            { ComposeRustContent(host) }
        }
        val opened = assertNotNull(
            backend.open(
                PlatformWindowConfig(nodeId = 0, title = "ax multi", width = 360f, height = 240f),
                NoopWindowListener,
            ),
        )
        try {
            val platformWindow = opened as dev.darkpyonix.composerust.ui.platform.MacosPlatformWindow
            val nsWindow: NSWindow = platformWindow.nativeWindow
            assertTrue(
                platformWindow.accessibilityActive,
                "Multi-window NSWindow must activate accessibility on open so external System Events / AX clients see the tree",
            )
            spinLoopUntil {
                findAxElements(nsWindow.contentView).any {
                    it.accessibilityRole() == MacosAx.BUTTON && it.accessibilityLabel() == "세션" &&
                        it.element.width > 0f
                }
            }
            val item = assertNotNull(
                findAxElements(nsWindow.contentView).firstOrNull {
                    it.accessibilityRole() == MacosAx.BUTTON && it.accessibilityLabel() == "세션" &&
                        it.element.width > 0f
                },
                "Multi-window NSWindow must populate its AX tree with measured bounds",
            )
            assertTrue(item.accessibilityPerformPress())
            assertEquals(
                listOf<HostEvent>(HostEvent.Clicked(nodeId = 1, handlerId = 30L)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )
        } finally {
            opened.close()
            spinLoop()
        }
    }
}
