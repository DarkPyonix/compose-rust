@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.dropdownPopupTestTag
import dev.darkpyonix.composerust.foundation.menuPopupTestTag
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
import dev.darkpyonix.composerust.ui.platform.NativeContextMenuRepresentation
import dev.darkpyonix.composerust.ui.platform.NativeMenuEntry
import dev.darkpyonix.composerust.ui.platform.PasteboardClipboard
import dev.darkpyonix.composerust.ui.platform.WindowClipboardImpl
import dev.darkpyonix.composerust.ui.platform.WindowClipboardManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Fr33MenuSpecTest {

    private class RecordingDrawnRepresentation(
        private val requests: MutableList<List<NativeMenuEntry>>,
    ) : ContextMenuRepresentation {
        @Composable
        override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
            if (state.status is ContextMenuState.Status.Open) {
                LaunchedEffect(state.status) {
                    requests += items().map { NativeMenuEntry(it.label, it.enabled) }
                    state.status = ContextMenuState.Status.Closed
                }
            }
        }
    }

    private fun textFieldBatch(): List<Mutation> = listOf(
        Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
        Mutation.Create(1, WidgetKind.Column),
        Mutation.Insert(0, 1, 0),
        Mutation.Create(2, WidgetKind.TextField),
        Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("hello world")),
        Mutation.Insert(1, 2, 0),
    )

    private fun selectionContainerBatch(): List<Mutation> = listOf(
        Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
        Mutation.Create(1, WidgetKind.Column),
        Mutation.Insert(0, 1, 0),
        Mutation.Create(2, WidgetKind.SelectionContainer),
        Mutation.Insert(1, 2, 0),
        Mutation.Create(3, WidgetKind.Text),
        Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("selectable paragraph")),
        Mutation.Insert(2, 3, 0),
    )

    @Suppress("DEPRECATION")
    @Test
    fun fr33_1_paste_enabled_state_follows_clipboard() = runComposeUiTest {
        var backing: String? = null
        val pasteboard = PasteboardClipboard(readText = { backing }, writeText = { backing = it })
        val clipboard = WindowClipboardImpl(pasteboard)
        val clipboardManager = WindowClipboardManager(pasteboard)
        val requests = mutableListOf<List<NativeMenuEntry>>()
        var localization: PlatformLocalization? = null
        val frames = FrameRequestSource()
        val connection = FakeHostConnection(textFieldBatch())

        setContent {
            localization = LocalLocalization.current
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
                LocalClipboard provides clipboard,
                LocalClipboardManager provides clipboardManager,
                LocalContextMenuRepresentation provides RecordingDrawnRepresentation(requests),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        val strings = assertNotNull(localization, "localization must be available")

        // Empty (null) clipboard -> Paste disabled.
        assertFalse(pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
        onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
        waitForIdle()
        val emptyEntries = requests.last()
        val emptyPaste = assertNotNull(emptyEntries.firstOrNull { it.label == strings.paste })
        assertFalse(emptyPaste.enabled, "Paste must be disabled when clipboard is empty")

        // Non-empty clipboard -> Paste enabled.
        pasteboard.setContents(StringSelection("hello"), null)
        assertTrue(pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
        onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
        waitForIdle()
        val filledEntries = requests.last()
        val filledPaste = assertNotNull(filledEntries.firstOrNull { it.label == strings.paste })
        assertTrue(filledPaste.enabled, "Paste must be enabled when clipboard holds text")

        // Empty string clipboard -> Paste disabled again.
        backing = ""
        assertFalse(pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
        onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
        waitForIdle()
        val clearedEntries = requests.last()
        val clearedPaste = assertNotNull(clearedEntries.firstOrNull { it.label == strings.paste })
        assertFalse(clearedPaste.enabled, "Paste must be disabled when clipboard text is empty")
    }

    @Suppress("DEPRECATION")
    @Test
    fun fr33_1_linux_drawn_text_menu_matches_items_and_enabled_state() {
        // 1. HostTextField: drawn menu vs native menu produce the same items (Cut, Copy, Paste, Select All)
        //    in the same order and with identical enabled states as clipboard toggles.
        runComposeUiTest {
            var backing: String? = "copied"
            val pasteboard = PasteboardClipboard(readText = { backing }, writeText = { backing = it })
            val clipboard = WindowClipboardImpl(pasteboard)
            val clipboardManager = WindowClipboardManager(pasteboard)
            val drawnRequests = mutableListOf<List<NativeMenuEntry>>()
            val nativeRequests = mutableListOf<List<NativeMenuEntry>>()
            val drawn = RecordingDrawnRepresentation(drawnRequests)
            val native = NativeContextMenuRepresentation { entries ->
                nativeRequests += entries
                -1
            }
            var useNative by mutableStateOf(false)
            var localization: PlatformLocalization? = null
            val frames = FrameRequestSource()
            val connection = FakeHostConnection(textFieldBatch())

            setContent {
                localization = LocalLocalization.current
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                    LocalClipboard provides clipboard,
                    LocalClipboardManager provides clipboardManager,
                    LocalContextMenuRepresentation provides if (useNative) native else drawn,
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            val strings = assertNotNull(localization)
            val expectedOrder = listOf(strings.cut, strings.copy, strings.paste, strings.selectAll)

            // Non-empty clipboard: drawn vs native match, Paste enabled.
            onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
            waitForIdle()
            useNative = true
            waitForIdle()
            onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
            waitForIdle()

            assertEquals(1, drawnRequests.size)
            assertEquals(1, nativeRequests.size)
            assertEquals(nativeRequests.last(), drawnRequests.last())
            val labels = drawnRequests.last().map { it.label }
            assertEquals(expectedOrder, labels.filter { it in expectedOrder })
            assertTrue(drawnRequests.last().first { it.label == strings.paste }.enabled)

            // Empty clipboard: drawn vs native still match, Paste disabled.
            backing = null
            useNative = false
            waitForIdle()
            onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
            waitForIdle()
            useNative = true
            waitForIdle()
            onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
            waitForIdle()

            assertEquals(nativeRequests.last(), drawnRequests.last())
            assertFalse(drawnRequests.last().first { it.label == strings.paste }.enabled)
        }

        // 2. SelectionContainer: selecting text and right-clicking offers Copy enabled
        //    with the same entries in both drawn and native representations.
        runComposeUiTest {
            val drawnRequests = mutableListOf<List<NativeMenuEntry>>()
            val nativeRequests = mutableListOf<List<NativeMenuEntry>>()
            val drawn = RecordingDrawnRepresentation(drawnRequests)
            val native = NativeContextMenuRepresentation { entries ->
                nativeRequests += entries
                -1
            }
            var useNative by mutableStateOf(false)
            var localization: PlatformLocalization? = null
            val frames = FrameRequestSource()
            val connection = FakeHostConnection(selectionContainerBatch())

            setContent {
                localization = LocalLocalization.current
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                    LocalContextMenuRepresentation provides if (useNative) native else drawn,
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
            waitForIdle()
            val strings = assertNotNull(localization)

            val bounds = onNodeWithTag(nodeTestTag(3)).fetchSemanticsNode().boundsInRoot
            onRoot().performMouseInput {
                moveTo(Offset(bounds.left + 1f, bounds.center.y))
                press()
                moveTo(Offset(bounds.right - 1f, bounds.center.y))
                release()
            }
            waitForIdle()

            onNodeWithTag(nodeTestTag(3)).performMouseInput { rightClick(center) }
            waitForIdle()
            useNative = true
            waitForIdle()
            onNodeWithTag(nodeTestTag(3)).performMouseInput { rightClick(center) }
            waitForIdle()

            assertEquals(1, drawnRequests.size)
            assertEquals(1, nativeRequests.size)
            assertEquals(nativeRequests.last(), drawnRequests.last())
            val copyEntry = assertNotNull(
                drawnRequests.last().firstOrNull { it.label == strings.copy },
                "SelectionContainer context menu must offer Copy: ${drawnRequests.last()}",
            )
            assertTrue(copyEntry.enabled, "Copy must be enabled when text is selected")
        }
    }

    private fun ComposeUiTest.pressMenuKey(key: Key) {
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.assertDropdownOptionFocused(id: Int, what: String) {
        val found = onAllNodes(isFocused() and hasAnyDescendant(hasTestTag(nodeTestTag(id))), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(found.isNotEmpty(), "$what: option $id does not have focus")
    }

    @Test
    fun fr33_1_menu_and_dropdown_popups_use_design_system_popup() {
        // 1. WidgetKind.Menu popup: design-system popup + Down/Up wrap, Home/End, Enter, Escape.
        runComposeUiTest {
            val dismissHandler = 900L
            val frames = FrameRequestSource()
            val batch = buildList {
                add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
                add(Mutation.Create(1, WidgetKind.Box))
                add(Mutation.Insert(0, 1, 0))
                add(Mutation.Create(2, WidgetKind.Menu))
                add(Mutation.SetProp(2, PropertyKind.Open, PropertyValue.Bool(true)))
                add(Mutation.SetProp(2, PropertyKind.OnDismiss, PropertyValue.Integer(dismissHandler)))
                add(Mutation.Insert(1, 2, 0))
                add(Mutation.Create(3, WidgetKind.Button))
                add(Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Open")))
                add(Mutation.Insert(2, 3, 0))
                listOf(10 to "One", 11 to "Two", 12 to "Three").forEachIndexed { idx, (id, label) ->
                    add(Mutation.Create(id, WidgetKind.Button))
                    add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
                    add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(100L + id)))
                    add(Mutation.Insert(2, id, idx + 1))
                }
            }
            val connection = FakeHostConnection(batch)
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
            waitForIdle()

            onNodeWithTag(menuPopupTestTag(2)).assertIsDisplayed()
            onNodeWithTag(nodeTestTag(10)).assertIsFocused()

            // Down wraps from last to first; Up wraps from first to last.
            pressMenuKey(Key.DirectionDown)
            onNodeWithTag(nodeTestTag(11)).assertIsFocused()
            pressMenuKey(Key.DirectionDown)
            onNodeWithTag(nodeTestTag(12)).assertIsFocused()
            pressMenuKey(Key.DirectionDown)
            onNodeWithTag(nodeTestTag(10)).assertIsFocused()
            pressMenuKey(Key.DirectionUp)
            onNodeWithTag(nodeTestTag(12)).assertIsFocused()

            // Home and End jump to first and last.
            pressMenuKey(Key.MoveHome)
            onNodeWithTag(nodeTestTag(10)).assertIsFocused()
            pressMenuKey(Key.MoveEnd)
            onNodeWithTag(nodeTestTag(12)).assertIsFocused()

            // Enter activates the focused item.
            pressMenuKey(Key.Enter)
            assertEquals(
                listOf(112L),
                connection.events.filterIsInstance<HostEvent.Clicked>().map { it.handlerId },
            )

            // Escape closes the popup and notifies OnDismiss.
            pressMenuKey(Key.Escape)
            assertTrue(
                onAllNodes(hasTestTag(menuPopupTestTag(2))).fetchSemanticsNodes().isEmpty(),
                "Escape must close the Menu popup",
            )
            assertTrue(
                connection.events.filterIsInstance<HostEvent.Clicked>().any { it.handlerId == dismissHandler },
                "Escape must fire OnDismiss",
            )
        }

        // 2. WidgetKind.Dropdown popup: design-system popup + Down/Up wrap, Home/End, Escape, Enter.
        runComposeUiTest {
            val valueHandler = 41L
            val options = listOf(2, 3, 4)
            val frames = FrameRequestSource()
            val batch = buildList {
                add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
                add(Mutation.Create(1, WidgetKind.Dropdown))
                add(Mutation.SetProp(1, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
                add(Mutation.SetProp(1, PropertyKind.Collapsible, PropertyValue.Bool(true)))
                add(Mutation.SetProp(1, PropertyKind.OnValueChange, PropertyValue.Integer(valueHandler)))
                options.forEachIndexed { index, id ->
                    add(Mutation.Create(id, WidgetKind.Text))
                    add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text("Option ${index + 1}")))
                    add(Mutation.Insert(1, id, index))
                }
            }
            val connection = FakeHostConnection(batch)
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
            waitForIdle()

            // Open the dropdown popup.
            onNodeWithText("Option 1").performClick()
            waitForIdle()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithTag(dropdownPopupTestTag(1)).assertIsDisplayed()
            assertDropdownOptionFocused(options[0], "on open, the chosen option")

            // Down wraps from last to first; Up wraps from first to last.
            pressMenuKey(Key.DirectionDown)
            assertDropdownOptionFocused(options[1], "Down moves to second")
            pressMenuKey(Key.DirectionDown)
            assertDropdownOptionFocused(options[2], "Down moves to third")
            pressMenuKey(Key.DirectionDown)
            assertDropdownOptionFocused(options[0], "Down wraps to first")
            pressMenuKey(Key.DirectionUp)
            assertDropdownOptionFocused(options[2], "Up wraps to last")

            // Home and End jump to first and last.
            pressMenuKey(Key.MoveHome)
            assertDropdownOptionFocused(options[0], "Home jumps to first")
            pressMenuKey(Key.MoveEnd)
            assertDropdownOptionFocused(options[2], "End jumps to last")

            // Escape closes without changing selection.
            pressMenuKey(Key.Escape)
            assertTrue(
                onAllNodes(hasTestTag(dropdownPopupTestTag(1))).fetchSemanticsNodes().isEmpty(),
                "Escape must close the Dropdown popup",
            )
            assertTrue(
                connection.events.filterIsInstance<HostEvent.ValueChanged>().isEmpty(),
                "Escape must not change the selected option",
            )

            // Re-open, move to second option, Enter activates and closes.
            onNodeWithText("Option 1").performClick()
            waitForIdle()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithTag(dropdownPopupTestTag(1)).assertIsDisplayed()
            pressMenuKey(Key.DirectionDown)
            assertDropdownOptionFocused(options[1], "Down selects second option")
            pressMenuKey(Key.Enter)
            assertEquals(
                listOf(HostEvent.ValueChanged(1, valueHandler, 1.0)),
                connection.events.filterIsInstance<HostEvent.ValueChanged>(),
                "Enter must activate the focused Dropdown option",
            )
            assertTrue(
                onAllNodes(hasTestTag(dropdownPopupTestTag(1))).fetchSemanticsNodes().isEmpty(),
                "Enter must close the Dropdown popup",
            )
        }
    }
}
