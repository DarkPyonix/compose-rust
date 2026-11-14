package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Key as ProtocolKey
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.foundation.HeldKeys
import dev.darkpyonix.composerust.foundation.keyName
import dev.darkpyonix.composerust.foundation.protocolKey
import dev.darkpyonix.composerust.foundation.shouldDispatchKeyDown
import dev.darkpyonix.composerust.foundation.shouldDispatchKeyUp
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.composeKey
import dev.darkpyonix.composerust.ui.platform.win32ComposeKey

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
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

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
                    repeat = false,
                    code = 0x28,
                    text = "Enter",
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

    /** Key-up is not a key-down, and a key the enum does not name travels as `Other`. */
    @Test
    fun fr12_only_schema_key_downs_cross_the_boundary() {
        assertFalse(
            shouldDispatchKeyDown(composing = false, isKeyDown = false, key = ProtocolKey.Enter),
        )
        assertFalse(shouldDispatchKeyDown(composing = false, isKeyDown = true, key = null))
        assertEquals(ProtocolKey.Enter, protocolKey(Key.Enter))
        assertEquals(ProtocolKey.Enter, protocolKey(Key.NumPadEnter))
        assertEquals(ProtocolKey.Other, protocolKey(Key.A))
    }

    /** A Host that does not consume leaves the key to the editor. */
    @Test
    fun fr12_an_unconsumed_key_still_reaches_the_editor() = runComposeUiTest {
        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 0) }
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

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
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(
            listOf(HostEvent.TextSubmitted(FIELD, SUBMIT_HANDLER, "")),
            connection.nodeEvents,
        )
    }

    /**
     * A focused `TextField` sends `FocusGained` once when focus arrives and `FocusLost`
     * when focus moves to another control.
     */
    @Test
    fun fr45_focus_gained_and_lost_pair() = runComposeUiTest {
        val otherField = 2
        val gainedHandler = 51L
        val lostHandler = 52L
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(10, WidgetKind.Column),
                Mutation.Create(FIELD, WidgetKind.TextField),
                Mutation.SetProp(FIELD, PropertyKind.OnFocusGained, PropertyValue.Integer(gainedHandler)),
                Mutation.SetProp(FIELD, PropertyKind.OnFocusLost, PropertyValue.Integer(lostHandler)),
                Mutation.Create(otherField, WidgetKind.TextField),
                Mutation.Insert(10, FIELD, 0),
                Mutation.Insert(10, otherField, 1),
                Mutation.Insert(0, 10, 0),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        waitForIdle()
        assertEquals(
            listOf(HostEvent.FocusGained(FIELD, gainedHandler)),
            connection.nodeEvents,
        )

        onNodeWithTag(nodeTestTag(otherField)).requestFocus()
        waitForIdle()
        assertEquals(
            listOf(
                HostEvent.FocusGained(FIELD, gainedHandler),
                HostEvent.FocusLost(FIELD, lostHandler),
            ),
            connection.nodeEvents,
        )
    }

    /**
     * Pressing and releasing a key sends `KeyDown` followed by `KeyUp` with the same key
     * and modifiers, and never while an IME composition is in progress.
     */
    @Test
    fun fr45_key_up_follows_key_down() = runComposeUiTest {
        val keyUpHandler = 43L
        assertFalse(
            shouldDispatchKeyUp(composing = true, isKeyUp = true, key = ProtocolKey.Enter),
            "Key-up during a composition must not be offered to the Host",
        )
        assertTrue(
            shouldDispatchKeyUp(composing = false, isKeyUp = true, key = ProtocolKey.Enter),
        )

        val connection = FakeHostConnection(
            keyField(
                Mutation.SetProp(FIELD, PropertyKind.OnKeyUp, PropertyValue.Integer(keyUpHandler)),
            ),
        )
        connection.respondWith { HostResponse(result = 1) }
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

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
                    repeat = false,
                    code = 0x28,
                    text = "Enter",
                ),
                HostEvent.KeyUp(
                    nodeId = FIELD,
                    handlerId = keyUpHandler,
                    key = ProtocolKey.Enter,
                    shiftKey = false,
                    ctrlKey = false,
                    altKey = false,
                    metaKey = false,
                    repeat = false,
                    code = 0x28,
                    text = "Enter",
                ),
            ),
            connection.nodeEvents,
        )
    }

    /**
     * A node with neither focus nor key handlers registered sends nothing across the boundary
     * when focused, blurred, or typed into.
     */
    @Test
    fun fr45_unregistered_nodes_get_no_events() = runComposeUiTest {
        val otherField = 2
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(10, WidgetKind.Column),
                Mutation.Create(FIELD, WidgetKind.TextField),
                Mutation.SetProp(FIELD, PropertyKind.Multiline, PropertyValue.Bool(true)),
                Mutation.Create(otherField, WidgetKind.TextField),
                Mutation.Insert(10, FIELD, 0),
                Mutation.Insert(10, otherField, 1),
                Mutation.Insert(0, 10, 0),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(nodeTestTag(otherField)).requestFocus()
        waitForIdle()

        assertEquals(emptyList(), connection.nodeEvents)
    }

    /** Arrows, Escape, Tab and letters each arrive with their HID usage and DOM key string. */
    @Test
    fun fr12_every_key_reaches_the_host_with_code_and_text() = runComposeUiTest {
        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 1) }
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        val pressed = listOf(
            Key.DirectionDown to (0x51 to "ArrowDown"),
            Key.DirectionUp to (0x52 to "ArrowUp"),
            Key.Escape to (0x29 to "Escape"),
            Key.Tab to (0x2B to "Tab"),
            Key.A to (0x04 to "a"),
        )
        for ((key, _) in pressed) {
            onNodeWithTag(nodeTestTag(FIELD)).performKeyInput { pressKey(key) }
        }
        waitForIdle()

        val events = connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>()
        assertEquals(pressed.map { it.second }, events.map { it.code to it.text })
        assertTrue(events.all { !it.repeat })
        assertEquals(ProtocolKey.ArrowDown, events.first().key)
        assertEquals("A", keyName(Key.A, shift = true).text)
    }

    /** Only the first down of a held key is a first press; the rest are repeats. */
    @Test
    fun fr12_held_key_marks_repeat() = runComposeUiTest {
        val held = HeldKeys()
        assertFalse(held.observe(KeyEventType.KeyDown, Key.DirectionDown))
        assertTrue(held.observe(KeyEventType.KeyDown, Key.DirectionDown))
        assertTrue(held.observe(KeyEventType.KeyDown, Key.DirectionDown))
        assertFalse(held.observe(KeyEventType.KeyUp, Key.DirectionDown))
        assertFalse(held.observe(KeyEventType.KeyDown, Key.DirectionDown), "a new press after release")

        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 1) }
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput {
            keyDown(Key.DirectionDown)
            advanceEventTime(1_000)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()

        val events = connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>()
        assertEquals(false, events.first().repeat)
        assertTrue(events.drop(1).isNotEmpty() && events.drop(1).all { it.repeat }, "held key produced no repeats: ${events.map { it.repeat }}")
    }

    /** No key of any kind is offered to the Host while a composition is in progress. */
    @Test
    fun fr12_no_key_reaches_the_host_during_composition() {
        for (key in listOf(Key.Enter, Key.DirectionDown, Key.Escape, Key.Tab, Key.A)) {
            assertFalse(
                shouldDispatchKeyDown(composing = true, isKeyDown = true, key = protocolKey(key)),
                "$key reached the Host during a composition",
            )
            assertTrue(
                shouldDispatchKeyDown(composing = false, isKeyDown = true, key = protocolKey(key)),
            )
        }
    }

    /** A key the Host consumes is not handled by the editor, whatever key it is. */
    @Test
    fun fr12_consumed_key_is_not_handled_by_the_renderer() = runComposeUiTest {
        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 1) }
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput {
            pressKey(Key.A)
            pressKey(Key.Enter)
        }
        waitForIdle()

        assertEquals(2, connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>().size)
        onNodeWithTag(nodeTestTag(FIELD)).assertTextEquals("")
    }

    /**
     * A focusable box with `OnKeyDown` receives the keys pressed while the box itself has
     * focus, not only keys that bubble up from a focused descendant (issue 101). The box has
     * no children, so the only way a key can reach the handler is through the box's own
     * focus target.
     */
    @Test
    fun fr12_a_focused_box_receives_its_own_keys() = runComposeUiTest {
        val box = 2
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(box, WidgetKind.Box),
                Mutation.SetModifier(box, 0, ProtocolModifier.Size(40f, 40f)),
                Mutation.SetModifier(box, 1, ProtocolModifier.Clickable(77L)),
                Mutation.SetProp(box, PropertyKind.OnKeyDown, PropertyValue.Integer(KEY_HANDLER)),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        // A box with a key handler is made focusable but may expose no RequestFocus action,
        // so it is focused the way a keyboard user does it.
        onNodeWithTag(nodeTestTag(box)).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(box)).assertIsFocused()
        onNodeWithTag(nodeTestTag(box)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(
            listOf(
                HostEvent.KeyDown(
                    nodeId = box,
                    handlerId = KEY_HANDLER,
                    key = ProtocolKey.Enter,
                    shiftKey = false,
                    ctrlKey = false,
                    altKey = false,
                    metaKey = false,
                    repeat = false,
                    code = 0x28,
                    text = "Enter",
                ),
            ),
            connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>().filter { it.key == ProtocolKey.Enter },
        )
    }

    /**
     * A drawer or popup menu that is a plain container closes on Escape through its own key
     * handler. The Host moves focus into it with `RequestFocus`, so the drawer receives Escape
     * even with nothing focusable inside, and moves focus back to the control that opened it
     * the same way once it has closed.
     */
    @Test
    fun fr65_a_container_given_focus_receives_escape_and_focus_returns_to_the_opener() = runComposeUiTest {
        val root = 10
        val opener = 2
        val drawer = 3
        val drawerKeys = 61L
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(opener, WidgetKind.Box),
                Mutation.SetModifier(opener, 0, ProtocolModifier.Size(40f, 40f)),
                Mutation.SetModifier(opener, 1, ProtocolModifier.Clickable(77L)),
                Mutation.Create(drawer, WidgetKind.Column),
                Mutation.SetModifier(drawer, 0, ProtocolModifier.Size(200f, 200f)),
                Mutation.SetProp(drawer, PropertyKind.OnKeyDown, PropertyValue.Integer(drawerKeys)),
                Mutation.Insert(root, opener, 0),
                Mutation.Insert(root, drawer, 1),
                Mutation.Insert(0, root, 0),
            ),
        )
        lateinit var host: ComposeRustHost
        setContent { host = rememberComposeRustHost(connection); ComposeRustContent(host) }
        waitForIdle()

        connection.respondWith { HostResponse(listOf(Mutation.RequestFocus(drawer))) }
        host.dispatch(HostEvent.Clicked(opener, 77L))
        waitForIdle()
        onNodeWithTag(nodeTestTag(drawer)).assertIsFocused()

        connection.respondWith { HostResponse(listOf(Mutation.RequestFocus(opener)), result = 1) }
        onNodeWithTag(nodeTestTag(drawer)).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        val keys = connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>()
        assertEquals(listOf(Triple(drawer, drawerKeys, 0x29 to "Escape")), keys.map { Triple(it.nodeId, it.handlerId, it.code to it.text) })
        assertEquals(ProtocolKey.Escape, keys.single().key)
        onNodeWithTag(nodeTestTag(opener)).assertIsFocused()
    }

    /** Escape, Tab, the arrows and the other keys menus and lists act on have their own name. */
    @Test
    fun fr12_named_keys_map_to_their_protocol_key() {
        val named = listOf(
            Key.Escape to ProtocolKey.Escape,
            Key.Tab to ProtocolKey.Tab,
            Key.Backspace to ProtocolKey.Backspace,
            Key.Delete to ProtocolKey.Delete,
            Key.DirectionUp to ProtocolKey.ArrowUp,
            Key.DirectionDown to ProtocolKey.ArrowDown,
            Key.DirectionLeft to ProtocolKey.ArrowLeft,
            Key.DirectionRight to ProtocolKey.ArrowRight,
            Key.MoveHome to ProtocolKey.Home,
            Key.MoveEnd to ProtocolKey.End,
            Key.PageUp to ProtocolKey.PageUp,
            Key.PageDown to ProtocolKey.PageDown,
        )
        for ((key, expected) in named) assertEquals(expected, protocolKey(key), "$key")
        assertEquals(ProtocolKey.Enter, protocolKey(Key.NumPadEnter))
        assertEquals(ProtocolKey.Other, protocolKey(Key.Insert))
    }

    /**
     * F1 to F12 and the context-menu key have their own name, and Shift+F10, the other way
     * to open a context menu, is F10 with Shift held. Each arrives with its HID usage and
     * DOM key string, and the platform key numbers of the AppKit and Win32 windows reach
     * the same Compose keys.
     */
    @Test
    fun fr12_function_and_context_menu_keys() = runComposeUiTest {
        val functions = listOf(
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10,
            Key.F11, Key.F12,
        )
        val protocolFunctions = listOf(
            ProtocolKey.F1, ProtocolKey.F2, ProtocolKey.F3, ProtocolKey.F4, ProtocolKey.F5,
            ProtocolKey.F6, ProtocolKey.F7, ProtocolKey.F8, ProtocolKey.F9, ProtocolKey.F10,
            ProtocolKey.F11, ProtocolKey.F12,
        )
        for ((key, expected) in functions.zip(protocolFunctions)) {
            assertEquals(expected, protocolKey(key), "$key")
        }
        assertEquals(ProtocolKey.ContextMenu, protocolKey(Key.Menu))
        // The JVM shell's AWT window reports the menu key as VK_CONTEXT_MENU.
        assertEquals(ProtocolKey.ContextMenu, protocolKey(Key((1L shl 32) or 525L)))

        val appKit = listOf(0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F)
        assertEquals(functions, appKit.map { composeKey(it) })
        assertEquals(Key.Menu, composeKey(0x6E))
        assertEquals(functions, (0x70..0x7B).map { win32ComposeKey(it) })
        assertEquals(Key.Menu, win32ComposeKey(0x5D))

        val connection = FakeHostConnection(keyField())
        connection.respondWith { HostResponse(result = 1) }
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        onNodeWithTag(nodeTestTag(FIELD)).performKeyInput {
            pressKey(Key.F2)
            pressKey(Key.F12)
            withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) }
            pressKey(Key.Menu)
        }
        waitForIdle()

        val events = connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>()
            .filter { it.key != ProtocolKey.Other }
        assertEquals(
            listOf(
                Triple(ProtocolKey.F2, false, 0x3B to "F2"),
                Triple(ProtocolKey.F12, false, 0x45 to "F12"),
                Triple(ProtocolKey.F10, true, 0x43 to "F10"),
                Triple(ProtocolKey.ContextMenu, false, 0x65 to "ContextMenu"),
            ),
            events.map { Triple(it.key, it.shiftKey, it.code to it.text) },
        )
    }
}
