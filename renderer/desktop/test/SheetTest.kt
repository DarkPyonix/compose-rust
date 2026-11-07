package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.sheetScrimTestTag
import dev.darkpyonix.composerust.foundation.sheetTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertNear(expected: Dp, actual: Dp, what: String) {
    assertTrue(
        abs(expected.value - actual.value) <= 1f,
        "$what should be about ${expected.value}dp but was ${actual.value}dp",
    )
}

private const val SHEET = 1
private const val CONTENT = 2
private const val DISMISS_HANDLER = 55L

private fun sheetBatch(open: Boolean) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(SHEET, WidgetKind.Sheet),
    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(open)),
    Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
    Mutation.Create(CONTENT, WidgetKind.Text),
    Mutation.SetProp(CONTENT, PropertyKind.Text, PropertyValue.Text("filter by")),
    Mutation.Insert(SHEET, CONTENT, 0),
)

@OptIn(ExperimentalTestApi::class)
class SheetTest {
    private val frames = FrameRequestSource()

    /** A sheet that was never opened draws nothing; the Host property is what opens it. */
    @Test
    fun fr21_a_sheet_shows_only_while_the_host_property_says_it_is_open() = runComposeUiTest {
        val connection = FakeHostConnection(sheetBatch(open = false))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CONTENT)).assertDoesNotExist()

        connection.scheduleFrame(
            listOf(Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true))),
        )
        frames.request()
        waitForIdle()
        onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")
    }

    /**
     * Pulling a sheet shut reports one dismissal, and reports nothing while it is being
     * pulled. How far it has travelled is this side's state: a Host that heard every frame
     * of a drag would be running the VirtualDom for an animation it cannot see.
     */
    @Test
    fun fr21_dragging_a_sheet_shut_reports_one_dismissal_and_nothing_during_the_drag() =
        runComposeUiTest {
            val connection = FakeHostConnection(sheetBatch(open = true))
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(connection),
                        Modifier.requiredSize(500.dp, 800.dp),
                    )
                }
            }
            waitForIdle()

            // A pull that stops short of the threshold changes nothing and says nothing.
            onNodeWithTag(sheetTestTag(SHEET)).performTouchInput {
                swipeDown(startY = top + 4f, endY = top + 40f)
            }
            waitForIdle()
            assertEquals(
                0,
                connection.nodeEvents.size,
                "a drag that did not close the sheet must not reach the Host: " +
                    "${connection.nodeEvents}",
            )
            onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")

            // A pull that carries it past the threshold closes it, once.
            onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown() }
            waitForIdle()
            val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(1, dismissals.size, "one dismissal, not ${connection.nodeEvents}")
            assertEquals(SHEET, dismissals[0].nodeId)
            assertEquals(DISMISS_HANDLER, dismissals[0].handlerId)
            // And the Renderer closed it without waiting to be told to.
            onNodeWithTag(nodeTestTag(CONTENT)).assertDoesNotExist()
        }

    /**
     * The same records come in from the bottom of a narrow window and from the side of a
     * wide one. The Host has no property that could have asked for either.
     *
     * The measurements are taken against what the sheet covers rather than against the
     * content, because a sheet is its own window and covers all of it.
     */
    @Test
    fun fr21_a_sheet_comes_from_the_bottom_when_narrow_and_from_the_side_when_wide() =
        runComposeUiTest {
            var width by mutableStateOf(500.dp)
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(sheetBatch(open = true))),
                        Modifier.requiredSize(width, 800.dp),
                    )
                }
            }
            waitForIdle()
            var covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
            var sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertNear(covered.right - covered.left, sheet.right - sheet.left, "a bottom sheet's width")
            assertNear((covered.bottom - covered.top) / 2f, sheet.bottom - sheet.top, "its height")
            assertNear(covered.bottom, sheet.bottom, "its bottom edge")

            // Wide enough for a side sheet: the same records, the other edge.
            width = 1100.dp
            waitForIdle()
            covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
            sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertNear(covered.bottom - covered.top, sheet.bottom - sheet.top, "a side sheet's height")
            assertNear(
                (covered.right - covered.left) * 0.4f,
                sheet.right - sheet.left,
                "its width",
            )
            assertNear(covered.right, sheet.right, "its trailing edge")
        }

    /**
     * Pressing Escape on an open Dialog dispatches OnDismiss once, and leaves the Dialog
     * open when the Host keeps Open true.
     */
    @Test
    fun fr21_escape_dismisses_an_open_dialog_once() = runComposeUiTest {
        val dialog = 1
        val label = 2
        val handler = 61L
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(dialog, WidgetKind.Dialog),
                Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(dialog, PropertyKind.OnDismiss, PropertyValue.Integer(handler)),
                Mutation.Create(label, WidgetKind.Text),
                Mutation.SetProp(label, PropertyKind.Text, PropertyValue.Text("unsaved changes")),
                Mutation.Insert(dialog, label, 0),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(label)).assertTextEquals("unsaved changes")

        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(listOf(HostEvent.Clicked(dialog, handler)), dismissals)
        // The Host did not send Open=false, so the Dialog stays open.
        onNodeWithTag(nodeTestTag(label)).assertTextEquals("unsaved changes")
    }

    /**
     * Pressing Escape on an open Sheet dispatches OnDismiss once in both narrow and wide
     * windows.
     */
    @Test
    fun fr21_escape_dismisses_an_open_sheet_once() = runComposeUiTest {
        for (windowWidth in listOf(500.dp, 1100.dp)) {
            val connection = FakeHostConnection(sheetBatch(open = true))
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(connection),
                        Modifier.requiredSize(windowWidth, 800.dp),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")

            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(
                listOf(HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
                dismissals,
                "width=$windowWidth",
            )
            onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")
        }
    }

    /**
     * An overlay with no focusable controls inside it still receives Escape immediately
     * after opening, even when a control on the page held focus before.
     */
    @Test
    fun fr21_escape_reaches_an_overlay_with_no_focusable_content() = runComposeUiTest {
        val root = 10
        val opener = 11
        val sheet = 12
        val text = 13
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(opener, WidgetKind.Button),
                Mutation.SetProp(opener, PropertyKind.Text, PropertyValue.Text("Settings")),
                Mutation.SetProp(opener, PropertyKind.OnClick, PropertyValue.Integer(70L)),
                Mutation.Insert(root, opener, 0),
                Mutation.Create(sheet, WidgetKind.Sheet),
                Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(false)),
                Mutation.SetProp(sheet, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                Mutation.Create(text, WidgetKind.Text),
                Mutation.SetProp(text, PropertyKind.Text, PropertyValue.Text("Read-only info")),
                Mutation.Insert(sheet, text, 0),
                Mutation.Insert(root, sheet, 1),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(opener)).requestFocus()
        waitForIdle()

        connection.scheduleFrame(
            listOf(Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(true))),
        )
        frames.request()
        waitForIdle()

        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(listOf(HostEvent.Clicked(sheet, DISMISS_HANDLER)), dismissals)
    }

    /**
     * When a focused TextField inside an overlay has an active IME composition, Escape
     * cancels the composition and does not dispatch OnDismiss.
     */
    @Test
    fun fr21_escape_in_a_composing_text_field_does_not_dismiss() = runComposeUiTest {
        val field = 20
        val compositionHandler = 81L
        var editorUpdate: ((androidx.compose.ui.text.input.TextFieldValue) -> Unit)? = null
        dev.darkpyonix.composerust.foundation.hostTextFieldValueHook = { update -> editorUpdate = update }
        try {
            val connection = FakeHostConnection(
                listOf(
                    Mutation.Create(SHEET, WidgetKind.Sheet),
                    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
                    Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                    Mutation.Create(field, WidgetKind.TextField),
                    Mutation.SetProp(
                        field,
                        PropertyKind.OnCompositionChange,
                        PropertyValue.Integer(compositionHandler),
                    ),
                    Mutation.Insert(SHEET, field, 0),
                ),
            )
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(field)).requestFocus()
            waitForIdle()

            val update = requireNotNull(editorUpdate) { "TextField did not install value hook" }
            runOnIdle {
                update(
                    androidx.compose.ui.text.input.TextFieldValue(
                        text = "한",
                        selection = androidx.compose.ui.text.TextRange(1),
                        composition = androidx.compose.ui.text.TextRange(0, 1),
                    ),
                )
            }
            waitForIdle()

            // First Escape cancels the active IME composition without dismissing the sheet.
            onNodeWithTag(nodeTestTag(field)).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertEquals(
                emptyList(),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
                "Escape during IME composition must not dismiss the overlay",
            )
            assertEquals(
                listOf(
                    HostEvent.CompositionChanged(field, compositionHandler, true),
                    HostEvent.CompositionChanged(field, compositionHandler, false),
                ),
                connection.events.filterIsInstance<HostEvent.CompositionChanged>(),
            )

            // Once composition is gone, a subsequent Escape dismisses the sheet.
            onNodeWithTag(nodeTestTag(field)).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertEquals(
                listOf(HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )
        } finally {
            dev.darkpyonix.composerust.foundation.hostTextFieldValueHook = null
        }
    }

    /**
     * When a Dialog is open on top of a Sheet, Escape dispatches OnDismiss only for the
     * topmost overlay (the Dialog), leaving the Sheet's handler untouched.
     */
    @Test
    fun fr21_escape_goes_to_the_topmost_overlay() = runComposeUiTest {
        val root = 10
        val sheet = 11
        val sheetText = 12
        val dialog = 13
        val dialogText = 14
        val sheetDismiss = 91L
        val dialogDismiss = 92L
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(sheet, WidgetKind.Sheet),
                Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(sheet, PropertyKind.OnDismiss, PropertyValue.Integer(sheetDismiss)),
                Mutation.Create(sheetText, WidgetKind.Text),
                Mutation.SetProp(sheetText, PropertyKind.Text, PropertyValue.Text("Project settings")),
                Mutation.Insert(sheet, sheetText, 0),
                Mutation.Insert(root, sheet, 0),
                Mutation.Create(dialog, WidgetKind.Dialog),
                Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(false)),
                Mutation.SetProp(dialog, PropertyKind.OnDismiss, PropertyValue.Integer(dialogDismiss)),
                Mutation.Create(dialogText, WidgetKind.Text),
                Mutation.SetProp(dialogText, PropertyKind.Text, PropertyValue.Text("Discard changes?")),
                Mutation.Insert(dialog, dialogText, 0),
                Mutation.Insert(root, dialog, 1),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        connection.scheduleFrame(
            listOf(Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true))),
        )
        frames.request()
        waitForIdle()

        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(
            listOf(HostEvent.Clicked(dialog, dialogDismiss)),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
        )
    }

    /**
     * When an overlay closes, keyboard focus returns to the control that held focus before
     * the overlay opened.
     */
    @Test
    fun fr21_escape_returns_focus_to_the_opener() = runComposeUiTest {
        val root = 10
        val opener = 11
        val sheet = 12
        val sheetText = 13
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(opener, WidgetKind.TextField),
                Mutation.Insert(root, opener, 0),
                Mutation.Create(sheet, WidgetKind.Sheet),
                Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(false)),
                Mutation.SetProp(sheet, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                Mutation.Create(sheetText, WidgetKind.Text),
                Mutation.SetProp(sheetText, PropertyKind.Text, PropertyValue.Text("Details")),
                Mutation.Insert(sheet, sheetText, 0),
                Mutation.Insert(root, sheet, 1),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(opener)).requestFocus()
        waitForIdle()
        onNodeWithTag(nodeTestTag(opener)).assertIsFocused()

        // Open the sheet.
        connection.scheduleFrame(
            listOf(Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(true))),
        )
        frames.request()
        waitForIdle()

        // Escape asks the Host to dismiss; the Host closes the sheet by setting Open=false.
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.Clicked(sheet, DISMISS_HANDLER)),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
        )
        connection.scheduleFrame(
            listOf(Mutation.SetProp(sheet, PropertyKind.Open, PropertyValue.Bool(false))),
        )
        frames.request()
        waitForIdle()

        onNodeWithTag(nodeTestTag(opener)).assertIsFocused()
    }
}
