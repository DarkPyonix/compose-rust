package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.SHEET_LARGE_DETENT_MARGIN
import dev.darkpyonix.composerust.foundation.sheetScrimTestTag
import dev.darkpyonix.composerust.foundation.sheetTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SHEET = 1
private const val CONTENT = 2
private const val DISMISS_HANDLER = 55L
private const val MEDIUM = 1L
private const val LARGE = 2L

private fun assertNear(expected: Dp, actual: Dp, what: String) {
    assertTrue(abs(expected.value - actual.value) <= 1f, "$what: expected $expected, was $actual")
}

/** An open Material sheet holding one 200dp box, with whatever else the test sets on it. */
private fun sheet(vararg extra: Mutation) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(SHEET, WidgetKind.Sheet),
    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
    Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
    *extra,
    Mutation.Create(CONTENT, WidgetKind.Box),
    Mutation.SetModifier(CONTENT, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(CONTENT, 1, ProtocolModifier.Height(200f)),
    Mutation.Insert(SHEET, CONTENT, 0),
)

/** The heights a bottom sheet rests at, and whether a pull closes it. */
@OptIn(ExperimentalTestApi::class)
class SheetDetentsTest {
    private val frames = FrameRequestSource()

    /**
     * Shows the batch in a box [width] wide and 800dp tall, at one pixel to the dp.
     *
     * A sheet is its own window and is measured against the test's window, not against
     * this box. A test that asserts a sheet's height in dp therefore runs in a test window
     * of the same 500 by 800dp; the default one is 768dp tall, and a half-window sheet in it
     * is 384dp rather than 400.
     */
    private fun ComposeUiTest.show(connection: FakeHostConnection, width: Dp = 500.dp) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(width, 800.dp))
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.sheetHeight(): Dp {
        val bounds = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
        return bounds.bottom - bounds.top
    }

    /** Bits that name no resting height are refused, and the sheet keeps the design system's height. */
    @Test
    fun fr21_3_4_unknown_detent_bits_are_a_protocol_error() {
        val table = NodeTable()
        table.apply(Mutation.Create(SHEET, WidgetKind.Sheet))
        table.apply(Mutation.SetProp(SHEET, PropertyKind.SheetDetents, PropertyValue.Integer(MEDIUM or 4L)))
        assertEquals(listOf(TableError.UNSUPPORTED_PROPERTY), table.drainErrors().map { it.code })
        assertNull(table.node(SHEET)?.property(PropertyKind.SheetDetents))
        // The two it knows, and a sheet that says whether a pull closes it, are taken.
        table.apply(Mutation.SetProp(SHEET, PropertyKind.SheetDetents, PropertyValue.Integer(MEDIUM or LARGE)))
        table.apply(Mutation.SetProp(SHEET, PropertyKind.SheetDragToDismiss, PropertyValue.Bool(false)))
        assertEquals(emptyList(), table.drainErrors())
        // Neither belongs on anything but a sheet.
        table.apply(Mutation.Create(CONTENT, WidgetKind.Dialog))
        table.apply(Mutation.SetProp(CONTENT, PropertyKind.SheetDetents, PropertyValue.Integer(MEDIUM)))
        table.apply(Mutation.SetProp(CONTENT, PropertyKind.SheetDragToDismiss, PropertyValue.Bool(false)))
        assertEquals(
            listOf(TableError.UNSUPPORTED_PROPERTY, TableError.UNSUPPORTED_PROPERTY),
            table.drainErrors().map { it.code },
        )
    }

    /**
     * A sheet with both resting heights opens at half the window, is pulled up to the large
     * height and stops there, and pulled down again stops at half. The Host hears none of it.
     */
    @Test
    fun fr21_3_4_a_sheet_rests_at_its_detents() = runDesktopComposeUiTest(500, 800) {
        val connection = FakeHostConnection(
            sheet(Mutation.SetProp(SHEET, PropertyKind.SheetDetents, PropertyValue.Integer(MEDIUM or LARGE))),
        )
        show(connection)
        assertNear(400.dp, sheetHeight(), "the sheet opens at half the window")

        onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeUp(startY = top + 4f, endY = top - 300f) }
        waitForIdle()
        // No top safe area in this window, so the large height is the window less the
        // design system's margin.
        assertNear(800.dp - SHEET_LARGE_DETENT_MARGIN, sheetHeight(), "pulled up, it rests at the large height")
        val scrim = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
        assertNear(scrim.bottom, onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot().bottom, "its bottom edge")

        onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown(startY = top + 4f, endY = top + 300f) }
        waitForIdle()
        assertNear(400.dp, sheetHeight(), "pulled down, it rests at half again")
        onNodeWithTag(nodeTestTag(CONTENT)).assertExists()

        assertEquals(emptyList(), connection.nodeEvents, "moving between resting heights says nothing to the Host")
    }

    /** With one resting height the sheet has that height, and a pull down closes it as before. */
    @Test
    fun fr21_3_4_a_sheet_with_one_detent_has_that_height() = runDesktopComposeUiTest(500, 800) {
        val connection = FakeHostConnection(
            sheet(Mutation.SetProp(SHEET, PropertyKind.SheetDetents, PropertyValue.Integer(LARGE))),
        )
        show(connection)
        assertNear(800.dp - SHEET_LARGE_DETENT_MARGIN, sheetHeight(), "a large sheet")
        onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown(startY = top + 4f, endY = top + 300f) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
        )
    }

    /**
     * A sheet a pull may not close stays where it is however far it is pulled, and sends no
     * dismissal for the pull. Escape still asks to close it, once.
     */
    @Test
    fun fr21_3_4_drag_to_dismiss_off_keeps_the_sheet() = runComposeUiTest {
        val connection = FakeHostConnection(
            sheet(Mutation.SetProp(SHEET, PropertyKind.SheetDragToDismiss, PropertyValue.Bool(false))),
        )
        show(connection)
        val before = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()

        onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown(startY = top + 4f, endY = top + 380f) }
        waitForIdle()
        assertEquals(emptyList(), connection.nodeEvents, "a pull that may not close the sheet says nothing")
        onNodeWithTag(nodeTestTag(CONTENT)).assertExists()
        assertNear(before.top, onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot().top, "the sheet did not move")

        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.Clicked(SHEET, DISMISS_HANDLER)),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
        )
    }

    /**
     * A sheet that comes from the side has no resting heights: both properties are taken
     * without an error and change nothing about it.
     */
    @Test
    fun fr21_3_4_a_side_sheet_ignores_detents() = runComposeUiTest {
        val connection = FakeHostConnection(
            sheet(
                Mutation.SetProp(SHEET, PropertyKind.SheetDetents, PropertyValue.Integer(MEDIUM or LARGE)),
                Mutation.SetProp(SHEET, PropertyKind.SheetDragToDismiss, PropertyValue.Bool(false)),
            ),
        )
        show(connection, width = 1100.dp)
        val covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
        val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
        assertNear(covered.bottom - covered.top, sheet.bottom - sheet.top, "a side sheet's height")
        assertNear((covered.right - covered.left) * 0.4f, sheet.right - sheet.left, "its width")
        assertTrue(connection.events.none { it is HostEvent.ProtocolError }, "${connection.events}")
    }
}
