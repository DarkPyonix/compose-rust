package dioxus.compose.test

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Key as ProtocolKey
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.foundation.protocolKey
import dioxus.compose.foundation.shouldDispatchKeyDown
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.ui.node.nodeTestTag

private const val FIELD = 1
private const val KEY_HANDLER = 41L
private const val SUBMIT_HANDLER = 42L

private fun keyField(vararg extra: Mutation) = listOf(
    Mutation.Create(FIELD, WidgetKind.TextField),
    Mutation.SetProp(FIELD, PropertyKind.Multiline, PropertyValue.Bool(true)),
    Mutation.SetProp(FIELD, PropertyKind.OnKeyDown, PropertyValue.Integer(KEY_HANDLER)),
    *extra,
)

@OptIn(ExperimentalTestApi::class)
class KeyEventTest {
    /**
     * The key reaches the Host handler and the Host's synchronous result decides
     * consumption, so Enter in a multiline field submits without inserting a newline.
     */
    @Test
    fun fr12_enter_reaches_the_host_and_a_consumed_key_inserts_no_newline() = runComposeUiTest {
        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 1) }
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(
            listOf(
                HostEvent.KeyDown(
                    nodeId = FIELD,
                    handlerId = KEY_HANDLER,
                    key = ProtocolKey.Enter,
                    shiftKey = false,
                    ctrlKey = false,
                    altKey = false,
                    metaKey = false,
                ),
            ),
            connection.nodeEvents,
        )
        onNodeWithTag(nodeTestTag(FIELD)).assertTextEquals("")
    }

    /**
     * The project's most important correctness rule. Enter while an
     * IME composition is in progress means "commit the composition", so nothing is sent to
     * the Host and the key is left to the editor. Driving a real IME is a manual check
     * (type Korean into a native build and watch); this pins the decision the key handler
     * makes.
     */
    @Test
    fun fr12_no_key_event_is_dispatched_while_an_ime_composition_is_in_progress() {
        assertFalse(
            shouldDispatchKeyDown(composing = true, isKeyDown = true, key = ProtocolKey.Enter),
            "Enter during a composition must not be offered to the Host",
        )
        assertTrue(
            shouldDispatchKeyDown(composing = false, isKeyDown = true, key = ProtocolKey.Enter),
        )
    }

    /** Key-up is not a key-down, and an unnameable key cannot cross the schema. */
    @Test
    fun fr12_only_schema_key_downs_cross_the_boundary() {
        assertFalse(
            shouldDispatchKeyDown(composing = false, isKeyDown = false, key = ProtocolKey.Enter),
        )
        assertFalse(shouldDispatchKeyDown(composing = false, isKeyDown = true, key = null))
        assertEquals(ProtocolKey.Enter, protocolKey(Key.Enter))
        assertEquals(ProtocolKey.Enter, protocolKey(Key.NumPadEnter))
        assertEquals(null, protocolKey(Key.A))
    }

    /** A Host that does not consume leaves the key to the editor. */
    @Test
    fun fr12_an_unconsumed_key_still_reaches_the_editor() = runComposeUiTest {
        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 0) }
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, connection.events.filterIsInstance<HostEvent.KeyDown>().size)
        onNodeWithTag(nodeTestTag(FIELD)).assertTextEquals("\n")
    }

    /** A field with only `on_submit` keeps the TextSubmitted path. */
    @Test
    fun fr12_a_field_without_a_key_handler_still_submits() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(FIELD, WidgetKind.TextField),
                Mutation.SetProp(
                    FIELD,
                    PropertyKind.OnSubmit,
                    PropertyValue.Integer(SUBMIT_HANDLER),
                ),
            ),
        )
        connection.respondWith { HostResponse(result = 1) }
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(
            listOf(HostEvent.TextSubmitted(FIELD, SUBMIT_HANDLER, "")),
            connection.nodeEvents,
        )
    }
}
