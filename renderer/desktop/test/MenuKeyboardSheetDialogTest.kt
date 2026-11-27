package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CR6A_ROOT = 1
private const val CR6A_OVERLAY = 2
private const val CR6A_MENU_COL = 3
private val CR6A_ITEMS = listOf(10, 11, 12)
private const val CR6A_DISMISS = 99L

private fun cr6aMenuOverlayBatch(
    overlayWidget: WidgetKind,
    selected: Int = CR6A_ITEMS[1],
): List<Mutation> = buildList {
    add(Mutation.Create(CR6A_ROOT, WidgetKind.Box))
    add(Mutation.SetModifier(CR6A_ROOT, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.Insert(0, CR6A_ROOT, 0))

    add(Mutation.Create(CR6A_OVERLAY, overlayWidget))
    add(Mutation.SetProp(CR6A_OVERLAY, PropertyKind.Open, PropertyValue.Bool(true)))
    add(Mutation.SetProp(CR6A_OVERLAY, PropertyKind.OnDismiss, PropertyValue.Integer(CR6A_DISMISS)))
    add(Mutation.Insert(CR6A_ROOT, CR6A_OVERLAY, 0))

    add(Mutation.Create(CR6A_MENU_COL, WidgetKind.Column))
    add(Mutation.SetModifier(CR6A_MENU_COL, 0, ProtocolModifier.Semantics(SemanticsRole.Menu, 0)))
    add(Mutation.Insert(CR6A_OVERLAY, CR6A_MENU_COL, 0))

    listOf("First", "Second", "Third").forEachIndexed { i, label ->
        val id = CR6A_ITEMS[i]
        val textId = id + 100
        val state = (if (id == selected) 2 shl 2 else 1 shl 2) or (1 shl 11)
        add(Mutation.Create(id, WidgetKind.Box))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(1000L + id)))
        add(Mutation.SetModifier(id, 0, ProtocolModifier.FillMaxWidth))
        add(Mutation.SetModifier(id, 1, ProtocolModifier.Semantics(SemanticsRole.MenuItem, state)))
        add(Mutation.Create(textId, WidgetKind.Text))
        add(Mutation.SetProp(textId, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.Insert(id, textId, 0))
        add(Mutation.Insert(CR6A_MENU_COL, id, i))
    }
}

@OptIn(ExperimentalTestApi::class)
class MenuKeyboardSheetDialogTest {
    private val frames = FrameRequestSource()

    private fun ComposeUiTest.show(connection: FakeHostConnection) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
    }

    @Test
    fun cr6a_940_menu_in_sheet_gets_menu_keyboard_arrows_wrap_home_end_enter_escape() = runComposeUiTest {
        val connection = FakeHostConnection(cr6aMenuOverlayBatch(WidgetKind.Sheet))
        show(connection)

        // Selected item (second) is focused on open.
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).assertIsFocused()

        // ArrowDown moves to third, then wraps to first.
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).assertIsFocused()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).assertIsFocused()

        // End jumps to last, Home jumps to first.
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).performKeyInput { pressKey(Key.MoveEnd) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).assertIsFocused()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).performKeyInput { pressKey(Key.MoveHome) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).assertIsFocused()

        // Enter activates the focused row.
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(listOf(1000L + CR6A_ITEMS[0]), clicks.map { it.handlerId })

        // Escape dismisses the sheet.
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        val afterEscape = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertTrue(afterEscape.any { it.handlerId == CR6A_DISMISS })
    }

    @Test
    fun cr6a_940_menu_in_dialog_gets_menu_keyboard_arrows_wrap_home_end_enter_escape() = runComposeUiTest {
        val connection = FakeHostConnection(cr6aMenuOverlayBatch(WidgetKind.Dialog))
        show(connection)

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).assertIsFocused()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).assertIsFocused()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).assertIsFocused()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).performKeyInput { pressKey(Key.MoveEnd) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).assertIsFocused()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[2])).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(listOf(1000L + CR6A_ITEMS[2]), clicks.map { it.handlerId })
    }

    @Test
    fun cr6a_941_menu_keyboard_focused_row_draws_highlight_after_arrow_and_not_on_open() = runComposeUiTest {
        val connection = FakeHostConnection(cr6aMenuOverlayBatch(WidgetKind.Dialog, selected = CR6A_ITEMS[0]))
        show(connection)

        val before = onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).captureToImage().toPixelMap()

        onNodeWithTag(nodeTestTag(CR6A_ITEMS[0])).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).assertIsFocused()

        val after = onNodeWithTag(nodeTestTag(CR6A_ITEMS[1])).captureToImage().toPixelMap()
        var diffPixels = 0
        val w = minOf(before.width, after.width)
        val h = minOf(before.height, after.height)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (before[x, y] != after[x, y]) diffPixels++
            }
        }
        assertTrue(diffPixels > 0, "menu row must draw a visible focus highlight after ArrowDown")
    }
}
