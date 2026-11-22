package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.AccessibilityRequest
import dev.darkpyonix.composerust.ui.platform.accessibilityRequest
import dev.darkpyonix.composerust.ui.platform.performAccessibleAction
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reader's request on Windows arrives as a window event, not as a call: UI Automation asks
 * the window, the window writes the request to its queue, and the renderer hands it to the
 * control when it drains the queue, on the thread a click comes in on. These check that
 * the event becomes the request, and that a press requested that way is the Host's click.
 */
@OptIn(ExperimentalTestApi::class)
class WindowsAccessibilityActionTest {

    private fun request(id: Int, action: Int, text: String = "") = WindowEvent(
        kind = WindowEvent.ACCESSIBILITY_ACTION, x = 0f, y = 0f, buttons = 0, modifiers = 0,
        keyCode = id, codePoint = action, text = text,
    )

    @Test
    fun nfr8_windows_request_event_names_the_element_and_the_action() {
        assertEquals(AccessibilityRequest(7, AccessibleAction.PRESS, null), accessibilityRequest(request(7, AccessibleAction.PRESS)))
        assertEquals(AccessibilityRequest(9, AccessibleAction.FOCUS, null), accessibilityRequest(request(9, AccessibleAction.FOCUS)))
        // A new value is the event's text, however long, and an empty one is a field cleared.
        val long = "한글 " + "x".repeat(300)
        assertEquals(
            AccessibilityRequest(9, AccessibleAction.SET_VALUE, long),
            accessibilityRequest(request(9, AccessibleAction.SET_VALUE, long)),
        )
        assertEquals(
            AccessibilityRequest(9, AccessibleAction.SET_VALUE, ""),
            accessibilityRequest(request(9, AccessibleAction.SET_VALUE)),
        )
    }

    @Test
    fun nfr8_windows_other_events_are_not_requests() {
        val press = WindowEvent(WindowEvent.POINTER_DOWN, 1f, 1f, WindowEvent.PRIMARY_HELD, 0, 7, 0, "")
        assertNull(accessibilityRequest(press))
        assertNull(accessibilityRequest(WindowEvent.TextScaleChanged(1.5f)))
    }

    /** The completion criterion's last step: an invoked button is the Host's `Clicked`. */
    @Test
    fun nfr8_windows_invoke_reaches_the_host_as_a_click() {
        val button = 1
        val label = 2
        val handler = 41L
        runComposeUiTest {
            val connection = FakeHostConnection(
                listOf(
                    Mutation.Create(button, WidgetKind.Button),
                    Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(handler)),
                    Mutation.Create(label, WidgetKind.Text),
                    Mutation.SetProp(label, PropertyKind.Text, PropertyValue.Text("Save")),
                    Mutation.Insert(button, label, 0),
                ),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            waitForIdle()
            val node = onNodeWithTag(nodeTestTag(button)).fetchSemanticsNode()
            val asked = accessibilityRequest(request(node.id, AccessibleAction.PRESS))!!
            assertEquals(node.id, asked.id)
            var done = false
            runOnIdle { done = node.performAccessibleAction(asked.action, asked.argument) }
            waitForIdle()
            assertTrue(done, "the button offers a press")
            assertEquals(listOf(HostEvent.Clicked(button, handler)), connection.events.filterIsInstance<HostEvent.Clicked>())
        }
    }
}
