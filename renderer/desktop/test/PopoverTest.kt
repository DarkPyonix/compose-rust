@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.popoverAnchorTestTag
import dev.darkpyonix.composerust.foundation.popoverMaxContent
import dev.darkpyonix.composerust.foundation.popoverPosition
import dev.darkpyonix.composerust.foundation.popoverScrimTestTag
import dev.darkpyonix.composerust.protocol.Alignment
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PopoverAlign
import dev.darkpyonix.composerust.protocol.PopoverPlacement
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FRAME = 1
private const val POPOVER = 2
private const val ANCHOR = 3
private const val CONTENT = 4
private const val DISMISS = 77L
private const val MENU_COLUMN = 10
private const val MENU_HEADER = 11
private val MENU_ITEMS = listOf(12, 13, 14)
private const val DISABLED_MENU_ITEM = 15

private fun menuClickHandler(id: Int) = 200L + id

private const val WINDOW_WIDTH = 800
private const val WINDOW_HEIGHT = 600
private const val OFFSET = 10f

private val WINDOW = IntSize(WINDOW_WIDTH, WINDOW_HEIGHT)
private val BOX = IntSize(200, 120)

private fun tag(value: Enum<*>) = PropertyValue.Integer(value.ordinal.toLong() + 1L)

private fun near(expected: Dp, actual: Dp, what: String) {
    assertTrue(
        abs(expected.value - actual.value) <= 1f,
        "$what should be about ${expected.value}dp but was ${actual.value}dp",
    )
}

/**
 * A window-filling box with a popover in the corner [corner] names. The anchor is a text
 * field, so it can hold focus, and the content is one line of text.
 */
private fun popoverBatch(
    open: Boolean,
    corner: Alignment = Alignment.TopStart,
    extra: List<Mutation> = emptyList(),
) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(FRAME, WidgetKind.Box),
    Mutation.SetModifier(FRAME, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(FRAME, 1, ProtocolModifier.FillMaxHeight),
    Mutation.SetProp(FRAME, PropertyKind.Alignment, tag(corner)),
    Mutation.Create(POPOVER, WidgetKind.Popover),
    Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(open)),
    Mutation.SetProp(POPOVER, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS)),
    Mutation.SetProp(POPOVER, PropertyKind.OffsetDp, PropertyValue.Float(OFFSET)),
    Mutation.Create(ANCHOR, WidgetKind.TextField),
    Mutation.SetModifier(ANCHOR, 0, ProtocolModifier.Width(120f)),
    Mutation.Insert(POPOVER, ANCHOR, 0),
    Mutation.Create(CONTENT, WidgetKind.Text),
    Mutation.SetProp(CONTENT, PropertyKind.Text, PropertyValue.Text("Opus")),
    Mutation.Insert(POPOVER, CONTENT, 1),
    Mutation.Insert(FRAME, POPOVER, 0),
) + extra

/**
 * An open popover holding a menu: a header text followed by three pressable rows inside a
 * column, matching how an option menu popover is authored. [selected] marks the chosen row
 * through its `Semantics` modifier (`selected(true)` at bit 2).
 */
private fun popoverMenuBatch(
    selected: Int?,
    leadHeader: Boolean = true,
): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(FRAME, WidgetKind.Box))
    add(Mutation.SetModifier(FRAME, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.SetModifier(FRAME, 1, ProtocolModifier.FillMaxHeight))
    add(Mutation.Create(POPOVER, WidgetKind.Popover))
    add(Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)))
    add(Mutation.SetProp(POPOVER, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS)))
    add(Mutation.SetModifier(POPOVER, 0, ProtocolModifier.Semantics(SemanticsRole.Menu, 0)))
    add(Mutation.Create(ANCHOR, WidgetKind.Button))
    add(Mutation.SetProp(ANCHOR, PropertyKind.Text, PropertyValue.Text("IDE")))
    add(Mutation.SetProp(ANCHOR, PropertyKind.OnClick, PropertyValue.Integer(menuClickHandler(ANCHOR))))
    add(Mutation.Insert(POPOVER, ANCHOR, 0))
    add(Mutation.Create(MENU_COLUMN, WidgetKind.Column))
    add(Mutation.Insert(POPOVER, MENU_COLUMN, 1))
    var childIndex = 0
    if (leadHeader) {
        add(Mutation.Create(MENU_HEADER, WidgetKind.Text))
        add(Mutation.SetProp(MENU_HEADER, PropertyKind.Text, PropertyValue.Text("Open in IDE")))
        add(Mutation.Insert(MENU_COLUMN, MENU_HEADER, childIndex++))
    }
    listOf("Ember", "VS Code", "Gateway").forEachIndexed { i, label ->
        val id = MENU_ITEMS[i]
        val textId = id + 20
        val state = (if (id == selected) 2 shl 2 else 1 shl 2) or (1 shl 11)
        add(Mutation.Create(id, WidgetKind.Box))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(menuClickHandler(id))))
        add(Mutation.SetModifier(id, 0, ProtocolModifier.FillMaxWidth))
        add(Mutation.SetModifier(id, 1, ProtocolModifier.Semantics(SemanticsRole.MenuItem, state)))
        add(Mutation.Create(textId, WidgetKind.Text))
        add(Mutation.SetProp(textId, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.Insert(id, textId, 0))
        add(Mutation.Insert(MENU_COLUMN, id, childIndex++))
        if (i == 1) {
            val disabledTextId = DISABLED_MENU_ITEM + 20
            val disabledState = (1 shl 2) or (2 shl 6) or (1 shl 11)
            add(Mutation.Create(DISABLED_MENU_ITEM, WidgetKind.Box))
            add(Mutation.SetProp(DISABLED_MENU_ITEM, PropertyKind.Enabled, PropertyValue.Bool(false)))
            add(Mutation.SetProp(DISABLED_MENU_ITEM, PropertyKind.OnClick, PropertyValue.Integer(menuClickHandler(DISABLED_MENU_ITEM))))
            add(Mutation.SetModifier(DISABLED_MENU_ITEM, 0, ProtocolModifier.FillMaxWidth))
            add(Mutation.SetModifier(DISABLED_MENU_ITEM, 1, ProtocolModifier.Semantics(SemanticsRole.MenuItem, disabledState)))
            add(Mutation.Create(disabledTextId, WidgetKind.Text))
            add(Mutation.SetProp(disabledTextId, PropertyKind.Text, PropertyValue.Text("Xcode (unavailable)")))
            add(Mutation.Insert(DISABLED_MENU_ITEM, disabledTextId, 0))
            add(Mutation.Insert(MENU_COLUMN, DISABLED_MENU_ITEM, childIndex++))
        }
    }
    add(Mutation.Insert(FRAME, POPOVER, 0))
}

@OptIn(ExperimentalTestApi::class)
class PopoverTest {
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

    private fun ComposeUiTest.send(connection: FakeHostConnection, vararg records: Mutation) {
        connection.scheduleFrame(records.toList())
        frames.request()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    private fun dismissals(connection: FakeHostConnection) =
        connection.events.filterIsInstance<HostEvent.Clicked>()

    // ----- where it goes: the rule itself ----------------------------------------------

    /**
     * Below the anchor, the asked-for gap away, lined up with the anchor's leading edge.
     * That is what a popover does when nothing is in its way, and what it does when the
     * Host said nothing about a side or an edge.
     */
    @Test
    fun fr77_popover_opens_below_anchor() {
        val anchor = IntRect(left = 100, top = 100, right = 180, bottom = 140)
        fun at(
            placement: PopoverPlacement = PopoverPlacement.Bottom,
            align: PopoverAlign = PopoverAlign.Start,
            direction: LayoutDirection = LayoutDirection.Ltr,
            from: IntRect = anchor,
        ) = popoverPosition(from, WINDOW, BOX, placement, align, offset = 8, margin = 8, direction)

        assertEquals(IntOffset(100, 148), at())
        // The other two edges of the same side.
        assertEquals(IntOffset(100 + (80 - 200) / 2, 148), at(align = PopoverAlign.Center))
        // Its end would line up with the anchor's, 20 before the window starts, so it is
        // slid back to the margin.
        assertEquals(IntOffset(8, 148), at(align = PopoverAlign.End))

        // The other three sides, from an anchor with room all round it.
        val middle = IntRect(left = 360, top = 280, right = 440, bottom = 320)
        assertEquals(IntOffset(360, 280 - 8 - 120), at(PopoverPlacement.Top, from = middle))
        assertEquals(IntOffset(440 + 8, 280), at(PopoverPlacement.End, from = middle))
        assertEquals(IntOffset(360 - 8 - 200, 280), at(PopoverPlacement.Start, from = middle))
        assertEquals(
            IntOffset(448, 320 - 120),
            at(PopoverPlacement.End, PopoverAlign.End, from = middle),
        )

        // Right to left, the leading edge is the right one and `End` is the left side.
        assertEquals(
            IntOffset(440 - 200, 328),
            at(direction = LayoutDirection.Rtl, from = middle),
        )
        assertEquals(
            IntOffset(360 - 8 - 200, 280),
            at(PopoverPlacement.End, direction = LayoutDirection.Rtl, from = middle),
        )
    }

    /**
     * An anchor at the bottom of the window opens its popover upwards, and one at the
     * right edge has its popover slid back inside. Both at once in the corner.
     */
    @Test
    fun fr77_popover_flips_and_shifts() {
        fun at(anchor: IntRect, placement: PopoverPlacement = PopoverPlacement.Bottom) =
            popoverPosition(
                anchor, WINDOW, BOX, placement, PopoverAlign.Start,
                offset = 8, margin = 8, LayoutDirection.Ltr,
            )

        // No room below: 588 + 120 runs past 592. Above there is.
        val low = IntRect(left = 100, top = 540, right = 180, bottom = 580)
        assertEquals(IntOffset(100, 540 - 8 - 120), at(low))

        // Room below, but 700 + 200 runs past the right edge.
        val right = IntRect(left = 700, top = 100, right = 780, bottom = 140)
        assertEquals(IntOffset(WINDOW_WIDTH - 8 - 200, 148), at(right))

        // Both, in the bottom right corner.
        val corner = IntRect(left = 700, top = 540, right = 780, bottom = 580)
        assertEquals(IntOffset(592, 412), at(corner))

        // The same the other way up and sideways.
        val high = IntRect(left = 100, top = 20, right = 180, bottom = 60)
        assertEquals(IntOffset(100, 68), at(high, PopoverPlacement.Top))
        assertEquals(
            IntOffset(580 - 8 - 200, 100),
            at(IntRect(left = 580, top = 100, right = 780, bottom = 140), PopoverPlacement.End),
        )
        assertEquals(
            IntOffset(188, 100),
            at(IntRect(left = 20, top = 100, right = 180, bottom = 140), PopoverPlacement.Start),
        )

        // Room on neither side: it takes the side with more of it and is slid back inside,
        // over the anchor if it has to be.
        val tall = IntSize(200, 400)
        val middle = IntRect(left = 100, top = 250, right = 180, bottom = 290)
        val placed = popoverPosition(
            middle, WINDOW, tall, PopoverPlacement.Top, PopoverAlign.Start,
            offset = 8, margin = 8, LayoutDirection.Ltr,
        )
        assertEquals(IntOffset(100, WINDOW_HEIGHT - 8 - 400), placed)
    }

    /** Content may be as large as the window less the margin on every side, and no larger. */
    @Test
    fun fr77_popover_content_is_capped_to_the_window_less_the_margin() {
        assertEquals(IntSize(784, 584), popoverMaxContent(WINDOW, margin = 8))
        assertEquals(IntSize(0, 0), popoverMaxContent(IntSize(10, 12), margin = 8))
    }

    // ----- the interpreter ---------------------------------------------------------------

    /** The anchor is always drawn. The content exists only while the Host says open. */
    @Test
    fun fr77_popover_draws_its_anchor_always_and_its_content_while_open() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val connection = FakeHostConnection(popoverBatch(open = false))
            show(connection)
            onNodeWithTag(nodeTestTag(ANCHOR)).assertExists()
            onNodeWithTag(nodeTestTag(CONTENT)).assertDoesNotExist()

            send(connection, Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)))
            onNodeWithTag(nodeTestTag(ANCHOR)).assertExists()
            onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("Opus")

            send(connection, Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)))
            onNodeWithTag(nodeTestTag(CONTENT)).assertDoesNotExist()
            assertEquals(emptyList(), dismissals(connection), "the Host closing it is not a dismissal")
        }

    /** On screen: under the anchor, the gap the Host named away, leading edges together. */
    @Test
    fun fr77_popover_opens_below_anchor_on_screen() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            show(FakeHostConnection(popoverBatch(open = true)))
            val anchor = onNodeWithTag(popoverAnchorTestTag(POPOVER)).getBoundsInRoot()
            val box = onNodeWithTag(nodeTestTag(POPOVER)).getBoundsInRoot()
            near(anchor.bottom + OFFSET.dp, box.top, "the box's top")
            // The anchor starts at the window's edge, so the box is held the margin clear
            // of it rather than flush.
            assertTrue(box.left >= anchor.left, "the box starts at or after the anchor: $box")
            assertTrue(box.left.value <= 16f, "and no further in than the margin: $box")
        }

    /** The popover surface is a popup group to assistive technology, not a dialog. */
    @Test
    fun fr77_popover_is_exposed_as_a_popup_group() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            show(FakeHostConnection(popoverBatch(open = true)))
            val config = onNodeWithTag(nodeTestTag(POPOVER), useUnmergedTree = true)
                .fetchSemanticsNode().config
            assertTrue(config.contains(SemanticsProperties.IsPopup), "it is a popup: $config")
            assertEquals(true, config.getOrNull(SemanticsProperties.IsTraversalGroup))
            assertTrue(!config.contains(SemanticsProperties.IsDialog), "it is not a dialog: $config")
            assertTrue(!config.contains(SemanticsProperties.PaneTitle), "it announces no pane: $config")
        }

    /**
     * A press outside the box is one dismissal, and so is Escape. Neither closes it: the
     * Host said it is open and has not said otherwise.
     */
    @Test
    fun fr77_popover_dismisses_once() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val connection = FakeHostConnection(popoverBatch(open = true))
        show(connection)

        // The far corner of the window's own content, which the box does not reach.
        onNodeWithTag(nodeTestTag(FRAME)).performMouseInput {
            click(androidx.compose.ui.geometry.Offset(width - 20f, height - 20f))
        }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.Clicked(POPOVER, DISMISS)),
            dismissals(connection),
            "one press outside is one dismissal",
        )
        onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("Opus")

        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.Clicked(POPOVER, DISMISS), HostEvent.Clicked(POPOVER, DISMISS)),
            dismissals(connection),
            "one Escape is one more",
        )
        onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("Opus")
    }

    /** A press inside the box is not a dismissal. */
    @Test
    fun fr77_popover_a_press_inside_does_not_dismiss() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val connection = FakeHostConnection(popoverBatch(open = true))
            show(connection)
            onNodeWithTag(nodeTestTag(CONTENT)).performMouseInput { click() }
            waitForIdle()
            assertEquals(emptyList(), dismissals(connection))
        }

    /** Under an open popover inside an open dialog, Escape is the popover's alone. */
    @Test
    fun fr77_popover_escape_goes_to_the_topmost_overlay() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val dialog = 20
            val dialogDismiss = 91L
            val connection = FakeHostConnection(
                listOf(
                    Mutation.Create(dialog, WidgetKind.Dialog),
                    Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true)),
                    Mutation.SetProp(dialog, PropertyKind.OnDismiss, PropertyValue.Integer(dialogDismiss)),
                    Mutation.Create(POPOVER, WidgetKind.Popover),
                    Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)),
                    Mutation.SetProp(POPOVER, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS)),
                    Mutation.Create(ANCHOR, WidgetKind.Text),
                    Mutation.SetProp(ANCHOR, PropertyKind.Text, PropertyValue.Text("Model")),
                    Mutation.Insert(POPOVER, ANCHOR, 0),
                    Mutation.Create(CONTENT, WidgetKind.Text),
                    Mutation.SetProp(CONTENT, PropertyKind.Text, PropertyValue.Text("Opus")),
                    Mutation.Insert(POPOVER, CONTENT, 1),
                    Mutation.Insert(dialog, POPOVER, 0),
                ),
            )
            show(connection)
            send(connection, Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)))

            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(listOf(HostEvent.Clicked(POPOVER, DISMISS)), dismissals(connection))
        }

    /**
     * Opening moves focus into the box, so Escape reaches a box with nothing focusable in
     * it. Closing gives focus back to the anchor.
     */
    @Test
    fun fr77_popover_restores_focus() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val connection = FakeHostConnection(popoverBatch(open = false))
        show(connection)
        onNodeWithTag(nodeTestTag(ANCHOR)).requestFocus()
        waitForIdle()
        onNodeWithTag(nodeTestTag(ANCHOR)).assertIsFocused()

        send(connection, Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)))

        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(listOf(HostEvent.Clicked(POPOVER, DISMISS)), dismissals(connection))

        send(connection, Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)))
        onNodeWithTag(nodeTestTag(ANCHOR)).assertIsFocused()
    }

    /**
     * An anchor in the bottom right corner has its popover above it and inside the right
     * edge, and still has after the window shrinks under it and after it grows past the
     * size it opened in.
     */
    @Test
    fun fr77_popover_follows_resize() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        show(FakeHostConnection(popoverBatch(open = true, corner = Alignment.BottomEnd)))

        fun check(width: Int, height: Int) {
            val anchor = onNodeWithTag(popoverAnchorTestTag(POPOVER)).getBoundsInRoot()
            val box = onNodeWithTag(nodeTestTag(POPOVER)).getBoundsInRoot()
            near(width.dp, anchor.right, "the anchor's right edge in a $width wide window")
            near(height.dp, anchor.bottom, "the anchor's bottom edge in a $height tall window")
            near(anchor.top - OFFSET.dp, box.bottom, "the flipped box's bottom at $width x $height")
            assertTrue(
                box.right.value <= width.toFloat() && box.left.value >= 0f,
                "the box is inside a $width wide window: $box",
            )
        }
        check(WINDOW_WIDTH, WINDOW_HEIGHT)

        scene.size = IntSize(500, 400)
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        check(500, 400)

        scene.size = IntSize(900, 700)
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        check(900, 700)
    }

    /** A scrim is drawn only when the node asked for one, and covers the window. */
    @Test
    fun fr77_popover_draws_a_scrim_only_when_asked() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val connection = FakeHostConnection(popoverBatch(open = true))
            show(connection)
            onNodeWithTag(popoverScrimTestTag(POPOVER)).assertDoesNotExist()

            send(connection, Mutation.SetProp(POPOVER, PropertyKind.Scrim, PropertyValue.Bool(true)))
            val scrim = onNodeWithTag(popoverScrimTestTag(POPOVER)).getBoundsInRoot()
            near(WINDOW_WIDTH.dp, scrim.right - scrim.left, "the scrim's width")
            near(WINDOW_HEIGHT.dp, scrim.bottom - scrim.top, "the scrim's height")
            // The scrim is behind the box, and a press on it is a press outside the box.
            onNodeWithTag(popoverScrimTestTag(POPOVER)).performMouseInput {
                click(androidx.compose.ui.geometry.Offset(width - 20f, height - 20f))
            }
            waitForIdle()
            assertEquals(listOf(HostEvent.Clicked(POPOVER, DISMISS)), dismissals(connection))
        }

    // ----- a popover holding a menu ------------------------------------------------------

    private fun ComposeUiTest.press(key: Key) {
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.assertFocused(id: Int, what: String) {
        try {
            onNodeWithTag(nodeTestTag(id)).assertIsFocused()
        } catch (failure: AssertionError) {
            throw AssertionError("$what: item $id does not have focus", failure)
        }
    }

    /**
     * When a popover holds a menu, opening focuses the item marked selected, Up and Down move
     * between items and wrap at the ends, and Enter or Space presses the focused item once
     * without leaking keys to the Host.
     */
    @Test
    fun fr77_popover_menu_arrow_keys_move_focus_and_activate_the_current_item() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val connection = FakeHostConnection(popoverMenuBatch(selected = MENU_ITEMS[1]))
            show(connection)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertFocused(MENU_ITEMS[1], "on open, the selected menu item")

            press(Key.DirectionDown)
            assertFocused(MENU_ITEMS[2], "after one Down, skipping the disabled item")
            press(Key.DirectionUp)
            assertFocused(MENU_ITEMS[1], "Up from the last skips the disabled item back to the second")
            press(Key.DirectionDown)
            assertFocused(MENU_ITEMS[2], "Down again skips the disabled item to the last")
            press(Key.DirectionDown)
            assertFocused(MENU_ITEMS[0], "after another Down, round to the first")
            press(Key.DirectionUp)
            assertFocused(MENU_ITEMS[2], "Up from the first goes round to the last")
            press(Key.DirectionDown)
            assertFocused(MENU_ITEMS[0], "and Down comes back")

            press(Key.Enter)
            assertEquals(
                listOf(HostEvent.Clicked(MENU_ITEMS[0], menuClickHandler(MENU_ITEMS[0]))),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
                "Enter is one click of the focused menu item",
            )
            press(Key.Spacebar)
            assertEquals(
                listOf(
                    HostEvent.Clicked(MENU_ITEMS[0], menuClickHandler(MENU_ITEMS[0])),
                    HostEvent.Clicked(MENU_ITEMS[0], menuClickHandler(MENU_ITEMS[0])),
                ),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
                "Space is one click of the focused menu item",
            )
            assertTrue(
                connection.events.none { it is HostEvent.KeyDown || it is HostEvent.KeyUp },
                "a menu popover's navigation keys must not reach the Host: ${connection.events}",
            )
        }

    /**
     * Home and End jump to the first and last menu items, and when no item is marked
     * selected, opening focuses the first focusable item past any non-focusable header.
     */
    @Test
    fun fr77_popover_menu_home_and_end_and_default_focus_on_first_item() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val connection = FakeHostConnection(popoverMenuBatch(selected = null, leadHeader = true))
            show(connection)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertFocused(MENU_ITEMS[0], "on open with no selection, the first menu item")

            press(Key.MoveEnd)
            assertFocused(MENU_ITEMS[2], "End moves to the last menu item")
            press(Key.MoveHome)
            assertFocused(MENU_ITEMS[0], "Home moves to the first menu item")

            press(Key.Escape)
            assertEquals(
                listOf(HostEvent.Clicked(POPOVER, DISMISS)),
                dismissals(connection),
                "Escape in a menu popover dispatches one dismissal to the Host",
            )
        }
}
