package dev.darkpyonix.composerust.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.swipeUp
import dev.darkpyonix.composerust.foundation.sheetContentTestTag
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
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
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.platformBackHandler
import dev.darkpyonix.composerust.foundation.ModalOverlayPositionProvider
import dev.darkpyonix.composerust.foundation.platformModalOverlayWindow
import dev.darkpyonix.composerust.foundation.sheetHandleTestTag
import dev.darkpyonix.composerust.foundation.sheetScrimTestTag
import dev.darkpyonix.composerust.runtime.LocalSystemBars
import dev.darkpyonix.composerust.runtime.SystemBars
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.darkpyonix.composerust.foundation.sheetTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SheetPresentation
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

private fun sheetBatchCentered(open: Boolean) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(SHEET, WidgetKind.Sheet),
    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(open)),
    Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
    Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
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
     * A sheet with a tallest height is as tall as its content up to that share of the
     * window, and the content scrolls once it is taller. Both edges a sheet comes from
     * follow it: the bottom of a narrow window and the side of a wide one. The test window
     * is 800dp tall at one pixel to the dp.
     */
    @Test
    fun fr21_sheet_max_height_fraction() {
        for (windowWidth in listOf(500.dp, 1100.dp)) {
            // Taller than the share: the sheet stops at 0.85 of 800, and the content scrolls.
            var tall = 0.dp
            var tallColumn = 0.dp
            runDesktopComposeUiTest(windowWidth.value.toInt(), 800) {
                showTallSheet(windowWidth, contentHeight = 1000f)
                val covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
                assertNear(800.dp, covered.bottom - covered.top, "the window the sheet covers")
                val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
                assertNear(680.dp, sheet.bottom - sheet.top, "a sheet at $windowWidth holding 1000dp")
                val column = onNodeWithTag(sheetContentTestTag(SHEET)).getBoundsInRoot()
                val before = onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top
                onNodeWithTag(sheetContentTestTag(SHEET)).performTouchInput { swipeUp() }
                waitForIdle()
                val after = onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top
                assertTrue(after < before - 50.dp, "the content did not scroll: $before to $after")
                tall = sheet.bottom - sheet.top
                tallColumn = column.bottom - column.top
            }
            assertTrue(tallColumn < tall, "the scrolling content is inside the sheet")
            // Shorter than the share: the sheet is as tall as its content and whatever the
            // design system puts round it, which is the same in both.
            runDesktopComposeUiTest(windowWidth.value.toInt(), 800) {
                showTallSheet(windowWidth, contentHeight = 200f)
                val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
                val column = onNodeWithTag(sheetContentTestTag(SHEET)).getBoundsInRoot()
                assertNear(200.dp, column.bottom - column.top, "the content at $windowWidth")
                assertNear(
                    200.dp + (tall - tallColumn),
                    sheet.bottom - sheet.top,
                    "a sheet at $windowWidth holding 200dp",
                )
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.showTallSheet(windowWidth: Dp, contentHeight: Float) {
        val batch = listOf(
            Mutation.SetTheme(
                Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
            ),
            Mutation.Create(SHEET, WidgetKind.Sheet),
            Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
            Mutation.SetProp(SHEET, PropertyKind.MaxHeightFraction, PropertyValue.Float(0.85f)),
            Mutation.Create(CONTENT, WidgetKind.Box),
            Mutation.SetModifier(CONTENT, 0, ProtocolModifier.FillMaxWidth),
            Mutation.SetModifier(CONTENT, 1, ProtocolModifier.Height(contentHeight)),
            Mutation.Insert(SHEET, CONTENT, 0),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(FakeHostConnection(batch)),
                    Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
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
     * Android's back reaches the renderer through the platform back hook. The test installs
     * one that records what the window registered, which is the same call Android's
     * BackHandler receives, and presses back by invoking it.
     */
    private class RecordedBack {
        var enabled = false
        var onBack: () -> Unit = {}
    }

    private fun <T> withRecordedBack(block: (RecordedBack) -> T): T {
        val previous = platformBackHandler
        val recorded = RecordedBack()
        // The window's own hook is the last one registered without a split pane on screen.
        platformBackHandler = { enabled, onBack ->
            recorded.enabled = enabled
            recorded.onBack = onBack
        }
        try {
            return block(recorded)
        } finally {
            platformBackHandler = previous
        }
    }

    /**
     * With a Dialog open over an open Sheet, one back dismisses only the Dialog; once the
     * Host closes it, the next back dismisses the Sheet.
     */
    @Test
    fun fr21_android_back_dismisses_the_topmost_overlay_once() = withRecordedBack { back ->
        runComposeUiTest {
            val dialog = 3
            val dialogText = 4
            val dialogHandler = 66L
            val connection = FakeHostConnection(
                sheetBatch(open = true) + listOf(
                    Mutation.Create(dialog, WidgetKind.Dialog),
                    Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true)),
                    Mutation.SetProp(dialog, PropertyKind.OnDismiss, PropertyValue.Integer(dialogHandler)),
                    Mutation.Create(dialogText, WidgetKind.Text),
                    Mutation.SetProp(dialogText, PropertyKind.Text, PropertyValue.Text("discard?")),
                    Mutation.Insert(dialog, dialogText, 0),
                    Mutation.Insert(SHEET, dialog, 1),
                ),
            )
            // The Host honours the Dialog's dismissal and leaves the Sheet to the next back.
            connection.respondWith { event ->
                if (event == HostEvent.Clicked(dialog, dialogHandler)) {
                    HostResponse(listOf(Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(false))))
                } else {
                    HostResponse()
                }
            }
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(dialogText)).assertTextEquals("discard?")
            assertTrue(back.enabled, "back is claimed while an overlay is open")

            runOnIdle { back.onBack() }
            waitForIdle()
            assertEquals(
                listOf(HostEvent.Clicked(dialog, dialogHandler)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )
            onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")
            assertTrue(back.enabled, "the Sheet is still open")

            runOnIdle { back.onBack() }
            waitForIdle()
            assertEquals(
                listOf(HostEvent.Clicked(dialog, dialogHandler), HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )
        }
    }

    /** With no overlay open, the window does not claim back, so it stays the app's. */
    @Test
    fun fr21_android_back_without_overlay_passes_through() = withRecordedBack { back ->
        runComposeUiTest {
            val connection = FakeHostConnection(sheetBatch(open = false))
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()
            assertEquals(false, back.enabled)
            assertTrue(connection.events.filterIsInstance<HostEvent.Clicked>().isEmpty())
        }
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

    /**
     * A centered sheet in an expanded window is placed in the center of the window with
     * the default width of 560dp, or an explicit max width when specified.
     * Its scrim is drawn with alpha about 0.4 in both light and dark themes.
     */
    @Test
    fun fr21_centered_sheet_geometry() {
        for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) {
            runDesktopComposeUiTest(1024, 800) {
                val batch = listOf(
                    Mutation.SetTheme(
                        Theme(DesignSystem.Material3, DesignSystem.Material3, scheme, adaptive = false),
                    ),
                    Mutation.Create(SHEET, WidgetKind.Sheet),
                    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
                    Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                    Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
                    Mutation.Create(CONTENT, WidgetKind.Box),
                    Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Height(200f)),
                    Mutation.Insert(SHEET, CONTENT, 0),
                )
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides frames,
                        LocalDensity provides Density(1f),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.fillMaxSize())
                        }
                    }
                }
                waitForIdle()
                val covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
                val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
                assertNear(1024.dp, covered.right - covered.left, "covered window width")
                assertNear(800.dp, covered.bottom - covered.top, "covered window height")
                assertNear(560.dp, sheet.right - sheet.left, "centered sheet default width")
                assertNear((1024.dp - 560.dp) / 2f, sheet.left - covered.left, "horizontal centering")
                assertNear((800.dp - (sheet.bottom - sheet.top)) / 2f, sheet.top - covered.top, "vertical centering")

                val picture = onNodeWithTag(sheetScrimTestTag(SHEET)).captureToImage().toAwtImage()
                val pixel = picture.getRGB(4, 4)
                val channels = listOf((pixel shr 16) and 0xff, (pixel shr 8) and 0xff, pixel and 0xff)
                val expected = if (scheme == ColorScheme.Light) 153 else 12
                assertTrue(
                    channels.all { abs(it - expected) <= 6 },
                    "scrim pixel channel should be about $expected for scheme $scheme but was $channels",
                )
            }
        }

        // Test explicit max_width(1360) in a wide 1600x800 window.
        runDesktopComposeUiTest(1600, 800) {
            val batch = listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                ),
                Mutation.Create(SHEET, WidgetKind.Sheet),
                Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
                Mutation.SetProp(SHEET, PropertyKind.MaxWidth, PropertyValue.Integer(1360L)),
                Mutation.Create(CONTENT, WidgetKind.Box),
                Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Height(200f)),
                Mutation.Insert(SHEET, CONTENT, 0),
            )
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            val covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
            val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertNear(1360.dp, sheet.right - sheet.left, "centered sheet explicit max width")
            assertNear((1600.dp - 1360.dp) / 2f, sheet.left - covered.left, "horizontal centering")
        }
    }

    /**
     * Clicking the scrim or pressing Escape on a centered sheet dispatches OnDismiss once.
     */
    @Test
    fun fr21_centered_sheet_dismisses_on_scrim_and_escape() {
        runDesktopComposeUiTest(1024, 800) {
            val connection = FakeHostConnection(sheetBatchCentered(open = true))
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            onNodeWithTag(sheetScrimTestTag(SHEET)).performTouchInput { click(Offset(20f, 20f)) }
            waitForIdle()
            val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(
                listOf(HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
                dismissals,
                "clicking scrim dispatches OnDismiss once",
            )
        }

        runDesktopComposeUiTest(1024, 800) {
            val connection = FakeHostConnection(sheetBatchCentered(open = true))
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(
                listOf(HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
                dismissals,
                "pressing Escape dispatches OnDismiss once",
            )
        }
    }

    /**
     * A centered sheet traps keyboard focus: Tab cycles within the sheet's focusable children
     * and never escapes to controls on the page. Closing the sheet restores focus to the opener.
     */
    @Test
    fun fr21_centered_sheet_focus_trap_and_tab_cycle() = runDesktopComposeUiTest(1024, 800) {
        val root = 10
        val opener = 11
        val field1 = 21
        val field2 = 22
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(opener, WidgetKind.Button),
                Mutation.SetProp(opener, PropertyKind.Text, PropertyValue.Text("Open settings")),
                Mutation.SetProp(opener, PropertyKind.OnClick, PropertyValue.Integer(88L)),
                Mutation.Insert(root, opener, 0),
                Mutation.Create(SHEET, WidgetKind.Sheet),
                Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(false)),
                Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
                Mutation.Create(field1, WidgetKind.TextField),
                Mutation.Insert(SHEET, field1, 0),
                Mutation.Create(field2, WidgetKind.TextField),
                Mutation.Insert(SHEET, field2, 1),
                Mutation.Insert(root, SHEET, 1),
            ),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(opener)).requestFocus()
        waitForIdle()
        onNodeWithTag(nodeTestTag(opener)).assertIsFocused()

        // Open the centered sheet.
        connection.scheduleFrame(
            listOf(Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true))),
        )
        frames.request()
        waitForIdle()

        // Tab moves focus to the first text field.
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(field1)).assertIsFocused()

        // Tab moves focus to the second text field.
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(field2)).assertIsFocused()

        // Tab at the end cycles back to the first text field without escaping to opener.
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(field1)).assertIsFocused()

        // Close the sheet: focus returns to the opener button.
        connection.scheduleFrame(
            listOf(Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(false))),
        )
        frames.request()
        waitForIdle()
        onNodeWithTag(nodeTestTag(opener)).assertIsFocused()
    }

    /**
     * If content exceeds available window height minus margins, the centered sheet clamps
     * its height to available height and content scrolls. When content is shorter, the
     * sheet is only as tall as its content.
     */
    @Test
    fun fr21_centered_sheet_scrolls_taller_content() {
        // Taller content (1000dp) in 800dp window: clamps to 800dp - 32dp = 768dp and scrolls.
        runDesktopComposeUiTest(1024, 800) {
            val batch = listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                ),
                Mutation.Create(SHEET, WidgetKind.Sheet),
                Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
                Mutation.Create(CONTENT, WidgetKind.Box),
                Mutation.SetModifier(CONTENT, 0, ProtocolModifier.FillMaxWidth),
                Mutation.SetModifier(CONTENT, 1, ProtocolModifier.Height(1000f)),
                Mutation.Insert(SHEET, CONTENT, 0),
            )
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertNear(768.dp, sheet.bottom - sheet.top, "sheet height clamped to 768dp")

            val before = onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top
            onNodeWithTag(sheetContentTestTag(SHEET)).performTouchInput { swipeUp() }
            waitForIdle()
            val after = onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top
            assertTrue(after < before - 50.dp, "content scrolled inside centered sheet")
        }

        // Shorter content (200dp) in 800dp window: content height is 200dp, sheet height < 768dp.
        runDesktopComposeUiTest(1024, 800) {
            val batch = listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                ),
                Mutation.Create(SHEET, WidgetKind.Sheet),
                Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
                Mutation.Create(CONTENT, WidgetKind.Box),
                Mutation.SetModifier(CONTENT, 0, ProtocolModifier.FillMaxWidth),
                Mutation.SetModifier(CONTENT, 1, ProtocolModifier.Height(200f)),
                Mutation.Insert(SHEET, CONTENT, 0),
            )
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            val column = onNodeWithTag(sheetContentTestTag(SHEET)).getBoundsInRoot()
            assertNear(200.dp, column.bottom - column.top, "content height is 200dp")
            val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertTrue(sheet.bottom - sheet.top < 768.dp, "sheet does not expand to maximum available height")
        }
    }

    /**
     * In a compact window (500dp wide), a centered sheet falls back to bottom sheet presentation
     * with drag-to-dismiss enabled.
     */
    @Test
    fun fr21_centered_sheet_falls_back_to_bottom_sheet_on_compact() = runDesktopComposeUiTest(500, 800) {
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                ),
                Mutation.Create(SHEET, WidgetKind.Sheet),
                Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
                Mutation.SetProp(SHEET, PropertyKind.Presentation, PropertyValue.Integer(SheetPresentation.Centered.ordinal.toLong())),
                Mutation.Create(CONTENT, WidgetKind.Text),
                Mutation.SetProp(CONTENT, PropertyKind.Text, PropertyValue.Text("Compact fallback")),
                Mutation.Insert(SHEET, CONTENT, 0),
            ),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(500.dp, 800.dp))
            }
        }
        waitForIdle()

        val covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
        val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
        assertNear(covered.right - covered.left, sheet.right - sheet.left, "fallback bottom sheet width")
        assertNear(covered.bottom, sheet.bottom, "fallback bottom sheet bottom edge")
        assertNear((covered.bottom - covered.top) / 2f, sheet.bottom - sheet.top, "fallback bottom sheet height")

        // Dragging down closes the compact fallback sheet.
        onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown() }
        waitForIdle()
        val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(1, dismissals.size, "drag down dismisses the fallback sheet once")
    }

    /** A bottom sheet in a compact window, with or without its handle. */
    private fun handleCount(handle: Boolean?): Int {
        var count = -1
        runDesktopComposeUiTest(400, 800) {
            val batch = sheetBatch(open = true) +
                listOfNotNull(handle?.let { Mutation.SetProp(SHEET, PropertyKind.SheetHandle, PropertyValue.Bool(it)) })
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.requiredSize(400.dp, 800.dp))
                }
            }
            waitForIdle()
            onNodeWithTag(sheetTestTag(SHEET)).assertExists()
            count = onAllNodesWithTag(sheetHandleTestTag(SHEET)).fetchSemanticsNodes().size
        }
        return count
    }

    /**
     * `SheetHandle = false` takes the handle away and nothing else: Material 3 draws one on
     * a bottom sheet by default, the property removes it, and the sheet still drags shut.
     */
    /** The handle's colour and the sheet surface beside it, in a light or dark Material3 theme. */
    private fun handleAndSurface(scheme: ColorScheme): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
        var result = androidx.compose.ui.graphics.Color.Unspecified to androidx.compose.ui.graphics.Color.Unspecified
        runDesktopComposeUiTest(400, 800) {
            val batch = listOf(
                Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, scheme, adaptive = false)),
            ) + sheetBatch(open = true).drop(1)
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.requiredSize(400.dp, 800.dp))
                }
            }
            waitForIdle()
            // The open sheet is in a root of its own, so the sheet node is captured and the
            // handle is found inside it.
            val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            val handle = onNodeWithTag(sheetHandleTestTag(SHEET)).getBoundsInRoot()
            val pixels = onNodeWithTag(sheetTestTag(SHEET)).captureToImage().toPixelMap()
            val x = ((handle.left + handle.right) / 2 - sheet.left).value.toInt()
            val y = ((handle.top + handle.bottom) / 2 - sheet.top).value.toInt()
            // 24 dp to the side of the handle is the sheet's own surface.
            result = pixels[x, y] to pixels[(handle.right - sheet.left).value.toInt() + 24, y]
        }
        return result
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun fr21_sheet_handle_is_visible_in_both_themes() {
        for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) {
            val (handle, surface) = handleAndSurface(scheme)
            val ratio = contrast(handle, surface)
            assertTrue(ratio >= 1.5f, "$scheme: the handle ($handle) stands out from the sheet ($surface) by only $ratio:1")
        }
    }

    @Test
    fun fr21_sheet_handle_can_be_hidden() {
        assertEquals(1, handleCount(null), "a bottom sheet draws its handle by default")
        assertEquals(1, handleCount(true), "asking for the handle keeps it")
        assertEquals(0, handleCount(false), "a sheet told not to draw its handle drew one")

        runDesktopComposeUiTest(400, 800) {
            val connection = FakeHostConnection(
                sheetBatch(open = true) + Mutation.SetProp(SHEET, PropertyKind.SheetHandle, PropertyValue.Bool(false)),
            )
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(400.dp, 800.dp))
                }
            }
            waitForIdle()
            onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown() }
            waitForIdle()
            val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(1, dismissals.size, "a sheet without its handle still drags shut once")
        }
    }

    /** Where a compact bottom sheet, its handle (null where none is drawn) and its first child are. */
    private class HandleLayout(val sheet: DpRect, val handle: DpRect?, val content: DpRect)

    private fun handleLayout(system: DesignSystem, handle: Boolean? = null): HandleLayout {
        var layout: HandleLayout? = null
        runDesktopComposeUiTest(400, 800) {
            val batch = listOf(
                Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
            ) + sheetBatch(open = true).drop(1) +
                listOfNotNull(handle?.let { Mutation.SetProp(SHEET, PropertyKind.SheetHandle, PropertyValue.Bool(it)) })
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.requiredSize(400.dp, 800.dp))
                }
            }
            waitForIdle()
            val handles = onAllNodesWithTag(sheetHandleTestTag(SHEET)).fetchSemanticsNodes()
            layout = HandleLayout(
                sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot(),
                handle = if (handles.isEmpty()) null else onNodeWithTag(sheetHandleTestTag(SHEET)).getBoundsInRoot(),
                content = onNodeWithTag(nodeTestTag(CONTENT)).getBoundsInRoot(),
            )
        }
        return layout!!
    }

    /**
     * Material 3's drag handle keeps 22 dp above and below its bar, as Compose's
     * `BottomSheetDefaults.DragHandle` does, so the first line of the sheet starts below the
     * handle instead of level with it.
     */
    @Test
    fun fr21_sheet_content_starts_below_the_handle() {
        val layout = handleLayout(DesignSystem.Material3)
        val handle = assertNotNull(layout.handle, "a Material 3 bottom sheet draws its handle")
        assertNear(22.dp, handle.top - layout.sheet.top, "the space above the handle")
        val below = layout.content.top - handle.bottom
        assertTrue(
            below.value >= 22f - 1f,
            "the first child starts ${below.value}dp below the handle, less than 22dp",
        )
    }

    /**
     * Outside Material 3 the gap below the handle is the sheet's own content padding, the
     * same distance the content keeps from the sheet's leading edge. GNOME, because Breeze
     * and Fluent draw no handle on a sheet at all.
     */
    @Test
    fun fr21_sheet_handle_gap_is_the_sheet_padding_outside_material3() {
        val layout = handleLayout(DesignSystem.Gnome)
        val handle = assertNotNull(layout.handle, "a GNOME bottom sheet draws its handle")
        val padding = layout.content.left - layout.sheet.left
        assertTrue(padding.value > 0f, "the GNOME sheet pads its content")
        assertNear(padding, layout.content.top - handle.bottom, "the gap below the handle")
        assertNear(padding, handle.top - layout.sheet.top, "the space above the handle")
    }

    /** Without its handle a sheet keeps no space for one: the content starts at its padding. */
    @Test
    fun fr21_sheet_without_handle_leaves_no_gap() {
        val layout = handleLayout(DesignSystem.Material3, handle = false)
        assertEquals(null, layout.handle, "a sheet told not to draw its handle drew one")
        val padding = layout.content.left - layout.sheet.left
        assertNear(padding, layout.content.top - layout.sheet.top, "the first child's distance from the sheet's top")
    }

    /**
     * Where the sheet's last row ends and where its surface ends, in a compact window whose
     * overlay is told there is a [bottomInset] strip at the bottom edge. The last row is a
     * box that fills what is left of the sheet's height, so it ends where the content does.
     */
    private fun bottomSheetEdges(bottomInset: Dp): Triple<Dp, Dp, Dp> {
        val filler = 3
        val previous = platformModalOverlayWindow
        platformModalOverlayWindow = { onDismissRequest, _, content ->
            Popup(
                popupPositionProvider = ModalOverlayPositionProvider,
                properties = PopupProperties(focusable = true),
                onDismissRequest = onDismissRequest,
            ) {
                CompositionLocalProvider(LocalSystemBars provides SystemBars(bottom = bottomInset)) { content() }
            }
        }
        var edges = Triple(0.dp, 0.dp, 0.dp)
        try {
            runDesktopComposeUiTest(400, 800) {
                val batch = sheetBatch(open = true) + listOf(
                    Mutation.Create(filler, WidgetKind.Box),
                    Mutation.SetModifier(filler, 0, ProtocolModifier.FillMaxWidth),
                    Mutation.SetModifier(filler, 1, ProtocolModifier.FillMaxHeight),
                    Mutation.Insert(SHEET, filler, 1),
                )
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides frames,
                        LocalDensity provides Density(1f),
                    ) {
                        ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.requiredSize(400.dp, 800.dp))
                    }
                }
                waitForIdle()
                val scrim = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
                val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
                val last = onNodeWithTag(nodeTestTag(filler)).getBoundsInRoot()
                edges = Triple(scrim.bottom, sheet.bottom, last.bottom)
            }
        } finally {
            platformModalOverlayWindow = previous
        }
        return edges
    }

    /**
     * A bottom sheet's content ends above the strip the system keeps at the bottom edge,
     * and its surface still runs to that edge underneath it.
     */
    @Test
    fun fr21_bottom_sheet_clears_the_bottom_safe_area() {
        val inset = 48.dp
        val (window, surface, last) = bottomSheetEdges(inset)
        assertNear(window, surface, "the sheet's surface stops short of the bottom edge")
        assertTrue(
            last.value <= (window - inset).value + 1f,
            "the last row ends at $last, inside the ${inset.value}dp strip that starts at ${window - inset}",
        )
        // Without the strip the same row reaches further down, so it is the strip that
        // moved it and not the design system's own padding.
        val (_, _, lastWithout) = bottomSheetEdges(0.dp)
        assertNear(lastWithout - inset, last, "the last row with the strip")
    }
}
