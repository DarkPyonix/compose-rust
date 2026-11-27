package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.darkpyonix.composerust.foundation.hostTextFieldValueHook
import dev.darkpyonix.composerust.foundation.platformBackHandler
import dev.darkpyonix.composerust.foundation.platformUnhandledBack
import dev.darkpyonix.composerust.protocol.BackSource
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val COLUMN = 1
private const val BOX = 2
private const val EXTRA = 3
private const val COLUMN_BACK = 81L
private const val BOX_BACK = 82L
private const val BOX_CLICK = 83L
private const val KEY_HANDLER = 84L

/**
 * A column that hears back, and inside it a box that hears back too. The box can be pressed,
 * which makes it a focus target, so a key has somewhere to start from.
 */
private fun screen(vararg extra: Mutation) = listOf(
    Mutation.Create(COLUMN, WidgetKind.Column),
    Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Size(300f, 300f)),
    Mutation.SetProp(COLUMN, PropertyKind.OnBackRequested, PropertyValue.Integer(COLUMN_BACK)),
    Mutation.Create(BOX, WidgetKind.Box),
    Mutation.SetModifier(BOX, 0, ProtocolModifier.Size(100f, 100f)),
    Mutation.SetProp(BOX, PropertyKind.OnBackRequested, PropertyValue.Integer(BOX_BACK)),
    Mutation.SetProp(BOX, PropertyKind.OnClick, PropertyValue.Integer(BOX_CLICK)),
    Mutation.Insert(COLUMN, BOX, 0),
    *extra,
)

private val FakeHostConnection.backs: List<HostEvent.BackRequested>
    get() = events.filterIsInstance<HostEvent.BackRequested>()

/** Requests to go back that reach the application: who hears them, in which order, and when nobody does. */
@OptIn(ExperimentalTestApi::class)
class BackRequestedTest {
    private fun ComposeUiTest.show(connection: FakeHostConnection): ComposeRustHost {
        lateinit var host: ComposeRustHost
        setContent {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
        waitForIdle()
        return host
    }

    /** Sends one more batch the way a frame does. */
    private fun ComposeUiTest.send(connection: FakeHostConnection, host: ComposeRustHost, vararg mutations: Mutation) {
        runOnIdle {
            connection.scheduleFrame(mutations.toList())
            host.renderFrame(0L)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.pressEscapeOn(nodeId: Int) {
        onNodeWithTag(nodeTestTag(nodeId)).requestFocus()
        waitForIdle()
        onNodeWithTag(nodeTestTag(nodeId)).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
    }

    /** What the window gave the platform's back hook, and the back it handed on. */
    private class RecordedBack {
        var enabled = false
        var onBack: () -> Unit = {}
        var passed = 0
        var enabledWhenPassed: Boolean? = null
    }

    private fun <T> withRecordedBack(block: (RecordedBack) -> T): T {
        val previousHandler = platformBackHandler
        val previousUnhandled = platformUnhandledBack
        val recorded = RecordedBack()
        platformBackHandler = { enabled, onBack ->
            recorded.enabled = enabled
            recorded.onBack = onBack
        }
        platformUnhandledBack = {
            val pass: () -> Unit = {
                recorded.passed++
                recorded.enabledWhenPassed = recorded.enabled
            }
            pass
        }
        try {
            return block(recorded)
        } finally {
            platformBackHandler = previousHandler
            platformUnhandledBack = previousUnhandled
        }
    }

    /**
     * Escape goes to the box first. Left there, it goes on to the column, and left there
     * too it ends: each handler is asked once and nothing else happens.
     */
    @Test
    fun fr91_back_goes_to_the_innermost_handler_first() {
        // The box consumes it: the column never hears.
        runComposeUiTest {
            val connection = FakeHostConnection(screen())
            connection.respondWith { event ->
                HostResponse(result = if (event is HostEvent.BackRequested && event.nodeId == BOX) 1 else 0)
            }
            show(connection)
            pressEscapeOn(BOX)
            assertEquals(listOf(HostEvent.BackRequested(BOX, BOX_BACK, BackSource.Escape)), connection.backs)
        }
        // The box leaves it: the column hears next, and consumes it.
        runComposeUiTest {
            val connection = FakeHostConnection(screen())
            connection.respondWith { event ->
                HostResponse(result = if (event is HostEvent.BackRequested && event.nodeId == COLUMN) 1 else 0)
            }
            show(connection)
            pressEscapeOn(BOX)
            assertEquals(
                listOf(
                    HostEvent.BackRequested(BOX, BOX_BACK, BackSource.Escape),
                    HostEvent.BackRequested(COLUMN, COLUMN_BACK, BackSource.Escape),
                ),
                connection.backs,
            )
        }
        // Neither consumes it: both heard once, and that is the end of it.
        runComposeUiTest {
            val connection = FakeHostConnection(screen())
            show(connection)
            pressEscapeOn(BOX)
            assertEquals(listOf(BOX, COLUMN), connection.backs.map { it.nodeId })
        }
    }

    /** Of two handlers equally deep, the one that comes later in the tree is the innermost. */
    @Test
    fun fr91_of_two_equally_deep_handlers_the_later_one_hears_first() = runComposeUiTest {
        val connection = FakeHostConnection(
            screen(
                Mutation.Create(EXTRA, WidgetKind.Box),
                Mutation.SetModifier(EXTRA, 0, ProtocolModifier.Size(100f, 100f)),
                Mutation.SetProp(EXTRA, PropertyKind.OnBackRequested, PropertyValue.Integer(90L)),
                Mutation.Insert(COLUMN, EXTRA, 1),
            ),
        )
        show(connection)
        pressEscapeOn(BOX)
        assertEquals(listOf(EXTRA, COLUMN), connection.backs.map { it.nodeId })
    }

    /** An open sheet takes Escape and the platform's back: its dismissal is sent and no back request. */
    @Test
    fun fr91_an_open_overlay_takes_back_first() = withRecordedBack { back ->
        runComposeUiTest {
            val sheet = 10
            val label = 11
            val dismiss = 91L
            val connection = FakeHostConnection(
                screen(
                    Mutation.Create(sheet, WidgetKind.Sheet),
                    Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(true)),
                    Mutation.SetProp(sheet, PropertyKind.OnDismiss, PropertyValue.Integer(dismiss)),
                    Mutation.Create(label, WidgetKind.Text),
                    Mutation.SetProp(label, PropertyKind.Text, PropertyValue.Text("filter by")),
                    Mutation.Insert(sheet, label, 0),
                    Mutation.Insert(COLUMN, sheet, 1),
                ),
            )
            show(connection)

            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(listOf(HostEvent.Clicked(sheet, dismiss)), connection.events.filterIsInstance<HostEvent.Clicked>())
            assertTrue(connection.backs.isEmpty(), "Escape with a sheet open: ${connection.backs}")

            runOnIdle { back.onBack() }
            waitForIdle()
            assertEquals(
                listOf(HostEvent.Clicked(sheet, dismiss), HostEvent.Clicked(sheet, dismiss)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )
            assertTrue(connection.backs.isEmpty(), "the platform's back with a sheet open: ${connection.backs}")
            assertEquals(0, back.passed, "a back the sheet took is not handed on")
        }
    }

    /**
     * Escape that a control used is not a request to go back: it cancels a composition in a
     * focused field, and a key handler that consumed it kept it.
     */
    @Test
    fun fr91_escape_used_by_a_control_is_not_back() {
        // A field in the middle of a composition.
        runComposeUiTest {
            val field = 20
            var editorUpdate: ((TextFieldValue) -> Unit)? = null
            hostTextFieldValueHook = { update -> editorUpdate = update }
            try {
                val connection = FakeHostConnection(
                    screen(
                        Mutation.Create(field, WidgetKind.TextField),
                        Mutation.SetProp(field, PropertyKind.OnValueChange, PropertyValue.Integer(92L)),
                        Mutation.Insert(COLUMN, field, 1),
                    ),
                )
                show(connection)
                onNodeWithTag(nodeTestTag(field)).requestFocus()
                waitForIdle()
                val update = requireNotNull(editorUpdate) { "the field did not install its value hook" }
                runOnIdle {
                    update(TextFieldValue(text = "ㅎ", selection = TextRange(1), composition = TextRange(0, 1)))
                }
                waitForIdle()
                onNodeWithTag(nodeTestTag(field)).performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                assertTrue(connection.backs.isEmpty(), "Escape during a composition: ${connection.backs}")
            } finally {
                hostTextFieldValueHook = null
            }
        }
        // A key handler on the box that consumes Escape.
        runComposeUiTest {
            val connection = FakeHostConnection(
                screen(Mutation.SetProp(BOX, PropertyKind.OnKeyDown, PropertyValue.Integer(KEY_HANDLER))),
            )
            connection.respondWith { event -> HostResponse(result = if (event is HostEvent.KeyDown) 1 else 0) }
            show(connection)
            pressEscapeOn(BOX)
            assertEquals(1, connection.events.filterIsInstance<HostEvent.KeyDown>().size)
            assertTrue(connection.backs.isEmpty(), "Escape a key handler consumed: ${connection.backs}")
        }
        // The same handler leaving it: now it is a request to go back.
        runComposeUiTest {
            val connection = FakeHostConnection(
                screen(Mutation.SetProp(BOX, PropertyKind.OnKeyDown, PropertyValue.Integer(KEY_HANDLER))),
            )
            show(connection)
            pressEscapeOn(BOX)
            assertEquals(listOf(BOX, COLUMN), connection.backs.map { it.nodeId })
        }
    }

    /** A mouse's back button is a request to go back, and says so. */
    @Test
    fun fr91_pointer_back_button_is_back() = runComposeUiTest {
        val connection = FakeHostConnection(screen())
        connection.respondWith { event -> HostResponse(result = if (event is HostEvent.BackRequested) 1 else 0) }
        show(connection)
        onNodeWithTag(nodeTestTag(COLUMN)).performMouseInput {
            moveTo(Offset(200f, 200f))
            // Compose numbers the back button 3.
            press(MouseButton(3))
            release(MouseButton(3))
        }
        waitForIdle()
        assertEquals(listOf(HostEvent.BackRequested(BOX, BOX_BACK, BackSource.PointerBack)), connection.backs)
        // The primary button is not one.
        onNodeWithTag(nodeTestTag(COLUMN)).performMouseInput {
            press(MouseButton.Primary)
            release(MouseButton.Primary)
        }
        waitForIdle()
        assertEquals(1, connection.backs.size)
    }

    /**
     * The platform's back, as Android delivers it. A window with no handler does not claim
     * it. A back a handler consumed stops there. A back every handler left is handed to the
     * platform once, with the window's own claim off while it is.
     */
    @Test
    fun fr91_android_unhandled_back_falls_through() {
        // No node hears back: the window does not claim it.
        withRecordedBack { back ->
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(Mutation.Create(COLUMN, WidgetKind.Column)),
                )
                show(connection)
                assertFalse(back.enabled, "a window with no back handler leaves back to the system")
            }
        }
        // A handler consumes it: nothing is handed on.
        withRecordedBack { back ->
            runComposeUiTest {
                val connection = FakeHostConnection(screen())
                connection.respondWith { event -> HostResponse(result = if (event is HostEvent.BackRequested) 1 else 0) }
                show(connection)
                assertTrue(back.enabled, "a window with a back handler claims back")
                runOnIdle { back.onBack() }
                waitForIdle()
                assertEquals(listOf(HostEvent.BackRequested(BOX, BOX_BACK, BackSource.SystemBack)), connection.backs)
                assertEquals(0, back.passed, "a consumed back is not handed to the system")
                assertTrue(back.enabled)
            }
        }
        // Nobody consumes it: it is handed on once, while the window does not claim back.
        withRecordedBack { back ->
            runComposeUiTest {
                val connection = FakeHostConnection(screen())
                show(connection)
                runOnIdle { back.onBack() }
                waitForIdle()
                assertEquals(listOf(BOX, COLUMN), connection.backs.map { it.nodeId })
                assertEquals(1, back.passed, "an unconsumed back is handed to the system once")
                assertEquals(false, back.enabledWhenPassed, "the window's claim is off while the system takes it")
                assertTrue(back.enabled, "and on again afterwards")
            }
        }
    }

    /** A handler whose node was removed is not asked, and one whose handler was taken away is not either. */
    @Test
    fun fr91_a_removed_handler_no_longer_hears_back() = withRecordedBack { back ->
        runComposeUiTest {
            val connection = FakeHostConnection(screen())
            val host = show(connection)
            send(connection, host, Mutation.Remove(BOX))
            runOnIdle { back.onBack() }
            waitForIdle()
            assertEquals(listOf(COLUMN), connection.backs.map { it.nodeId })

            send(connection, host, Mutation.SetProp(COLUMN, PropertyKind.OnBackRequested, PropertyValue.None))
            assertFalse(back.enabled, "nobody is left to hear back")
        }
    }
}
