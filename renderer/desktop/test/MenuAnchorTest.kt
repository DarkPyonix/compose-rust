package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.width
import dev.darkpyonix.composerust.foundation.BelowAnchorPositionProvider
import dev.darkpyonix.composerust.foundation.menuPopupTestTag
import dev.darkpyonix.composerust.protocol.Alignment
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FRAME = 1
private const val MENU = 2
private const val ANCHOR = 3
private const val WINDOW_WIDTH = 800
private const val WINDOW_HEIGHT = 600

private fun tag(value: Enum<*>) = PropertyValue.Integer(value.ordinal.toLong() + 1L)

/** One menu entry the way the notepad sample writes it: a text button filling the row. */
private fun entry(id: Int, index: Int, label: String) = listOf(
    Mutation.Create(id, WidgetKind.Button),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)),
    Mutation.SetProp(id, PropertyKind.Variant, tag(ButtonVariant.Text)),
    Mutation.SetModifier(id, 0, ProtocolModifier.FillMaxWidth),
    Mutation.Insert(MENU, id, index),
)

/**
 * A window-filling box with an open menu in the corner [corner] names. The anchor is a
 * narrow button, like a toolbar's More button, and the entries ask to fill the menu's
 * width, which is what turned the menu into a band as wide as the window.
 */
private fun menuBatch(corner: Alignment) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(FRAME, WidgetKind.Box),
    Mutation.SetModifier(FRAME, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(FRAME, 1, ProtocolModifier.FillMaxHeight),
    Mutation.SetProp(FRAME, PropertyKind.Alignment, tag(corner)),
    Mutation.Create(MENU, WidgetKind.Menu),
    Mutation.SetProp(MENU, PropertyKind.Open, PropertyValue.Bool(true)),
    Mutation.Create(ANCHOR, WidgetKind.Button),
    Mutation.SetProp(ANCHOR, PropertyKind.Text, PropertyValue.Text("More")),
    Mutation.SetProp(ANCHOR, PropertyKind.Variant, tag(ButtonVariant.Text)),
    Mutation.Insert(MENU, ANCHOR, 0),
) + entry(4, 1, "Open") + entry(5, 2, "Save as") + entry(6, 3, "Format") +
    listOf(Mutation.Insert(FRAME, MENU, 0))

private val KEY_ENTRIES = listOf(4, 5, 6)
private const val SEPARATOR = 7
private fun clickHandler(id: Int) = 100L + id

/**
 * An open menu of three pressable rows, [selected] marked as the chosen one, and [lead]
 * entries that cannot take focus ahead of them (a divider). [semanticsSelected] marks the
 * chosen row through a `Semantics` modifier instead of the row's own checked property.
 */
private fun keyboardBatch(
    selected: Int?,
    lead: Boolean = false,
    semanticsSelected: Boolean = false,
): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(FRAME, WidgetKind.Box))
    add(Mutation.SetModifier(FRAME, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.SetModifier(FRAME, 1, ProtocolModifier.FillMaxHeight))
    add(Mutation.Create(MENU, WidgetKind.Menu))
    add(Mutation.SetProp(MENU, PropertyKind.Open, PropertyValue.Bool(true)))
    add(Mutation.Create(ANCHOR, WidgetKind.Button))
    add(Mutation.SetProp(ANCHOR, PropertyKind.Text, PropertyValue.Text("More")))
    add(Mutation.SetProp(ANCHOR, PropertyKind.Variant, tag(ButtonVariant.Text)))
    add(Mutation.SetProp(ANCHOR, PropertyKind.OnClick, PropertyValue.Integer(clickHandler(ANCHOR))))
    add(Mutation.Insert(MENU, ANCHOR, 0))
    var index = 1
    if (lead) {
        add(Mutation.Create(SEPARATOR, WidgetKind.Divider))
        add(Mutation.Insert(MENU, SEPARATOR, index++))
    }
    listOf("Open", "Save as", "Format").forEachIndexed { i, label ->
        val id = KEY_ENTRIES[i]
        add(Mutation.Create(id, WidgetKind.Button))
        add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.SetProp(id, PropertyKind.Variant, tag(ButtonVariant.Text)))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(clickHandler(id))))
        add(Mutation.SetModifier(id, 0, ProtocolModifier.FillMaxWidth))
        if (id == selected) {
            if (semanticsSelected) {
                // The selected field of the state word: two bits from bit 2, "on" is 2.
                add(Mutation.SetModifier(id, 1, ProtocolModifier.Semantics(SemanticsRole.MenuItem, 2 shl 2)))
            } else {
                add(Mutation.SetProp(id, PropertyKind.Checked, PropertyValue.Bool(true)))
            }
        }
        add(Mutation.Insert(MENU, id, index++))
    }
    add(Mutation.Insert(FRAME, MENU, 0))
}

@OptIn(ExperimentalTestApi::class)
class MenuAnchorTest {
    private val frames = FrameRequestSource()

    private fun ComposeUiTest.show(corner: Alignment): Pair<DpRect, DpRect> {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(FakeHostConnection(menuBatch(corner))),
                    Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val anchor = onNodeWithTag(nodeTestTag(ANCHOR)).getBoundsInRoot()
        val popup = onNodeWithTag(menuPopupTestTag(MENU)).getBoundsInRoot()
        return anchor to popup
    }

    private fun near(expected: Float, actual: Float, what: String) {
        assertTrue(kotlin.math.abs(expected - actual) <= 1f, "$what should be about $expected but was $actual")
    }

    private fun assertHangsInside(anchor: DpRect, popup: DpRect) {
        assertTrue(
            popup.top.value >= anchor.bottom.value - 1f,
            "the menu's top edge is at or below the anchor's bottom edge: anchor $anchor, menu $popup",
        )
        near(anchor.bottom.value, popup.top.value, "the menu's top edge")
        assertTrue(
            popup.width.value < WINDOW_WIDTH / 2f,
            "a menu of three short entries is not a band across the window: ${popup.width}",
        )
        assertTrue(
            popup.left.value >= 0f && popup.right.value <= WINDOW_WIDTH.toFloat(),
            "the menu stays inside the window: $popup",
        )
    }

    /**
     * A menu at the trailing end of a bar hangs from its anchor, its trailing edge in line
     * with the anchor's, rather than spanning the window under the bar.
     */
    @Test
    fun fr64_menu_hangs_from_its_anchor() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val (anchor, popup) = show(Alignment.TopEnd)
        assertHangsInside(anchor, popup)
        near(anchor.right.value, popup.right.value, "the menu's right edge, under an anchor at the end")
    }

    /** With room after the anchor, the menu lines up with the anchor's leading edge. */
    @Test
    fun fr64_menu_hangs_from_its_anchor_at_the_start() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            val (anchor, popup) = show(Alignment.TopStart)
            assertHangsInside(anchor, popup)
            near(anchor.left.value, popup.left.value, "the menu's left edge, under an anchor at the start")
        }

    /**
     * The placement rule itself: leading edges together when that fits, trailing edges
     * together when it would run past the window, and above the anchor only when below
     * has no room.
     */
    @Test
    fun fr64_menu_aligns_to_the_anchor_s_end_when_its_start_has_no_room() {
        val window = IntSize(400, 600)
        val menu = IntSize(180, 120)
        fun at(anchor: IntRect, direction: LayoutDirection = LayoutDirection.Ltr) =
            BelowAnchorPositionProvider.calculatePosition(anchor, window, direction, menu)

        assertEquals(IntOffset(40, 56), at(IntRect(40, 24, 88, 56)), "room after the anchor")
        assertEquals(IntOffset(352 - 180, 56), at(IntRect(304, 24, 352, 56)), "an anchor near the right edge")
        assertEquals(IntOffset(352 - 180, 520 - 120), at(IntRect(304, 520, 352, 568)), "no room below, so above")
        assertEquals(
            IntOffset(352 - 180, 56),
            at(IntRect(304, 24, 352, 56), LayoutDirection.Rtl),
            "right to left: the anchor's right edge leads",
        )
        assertEquals(
            IntOffset(48, 56),
            at(IntRect(48, 24, 96, 56), LayoutDirection.Rtl),
            "right to left with no room before the anchor: left edges together",
        )
    }

    // ----- the keyboard ------------------------------------------------------------------

    private fun ComposeUiTest.showKeyboardMenu(connection: FakeHostConnection) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /** Presses [key] in the menu's popup, which is the topmost window. */
    private fun ComposeUiTest.press(key: Key) {
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.assertFocused(id: Int, what: String) {
        try {
            onNodeWithTag(nodeTestTag(id)).assertIsFocused()
        } catch (failure: AssertionError) {
            throw AssertionError("$what: entry $id does not have focus", failure)
        }
    }

    /**
     * Of three entries, the second is selected: opening puts focus on it, Down twice goes
     * round to the first, and Enter is that entry's click, once. No key reaches the Host.
     */
    @Test
    fun fr64_menu_arrow_keys_move_focus() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val connection = FakeHostConnection(keyboardBatch(selected = KEY_ENTRIES[1]))
        showKeyboardMenu(connection)
        assertFocused(KEY_ENTRIES[1], "on open, the selected entry")

        press(Key.DirectionDown)
        assertFocused(KEY_ENTRIES[2], "after one Down")
        press(Key.DirectionDown)
        assertFocused(KEY_ENTRIES[0], "after a second Down, round to the first")
        press(Key.DirectionUp)
        assertFocused(KEY_ENTRIES[2], "Up from the first goes round to the last")
        press(Key.DirectionDown)
        assertFocused(KEY_ENTRIES[0], "and Down comes back")

        press(Key.Enter)
        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(
            listOf(HostEvent.Clicked(KEY_ENTRIES[0], clickHandler(KEY_ENTRIES[0]))),
            clicks,
            "Enter is one click of the focused entry",
        )
        assertTrue(
            connection.events.none { it is HostEvent.KeyDown || it is HostEvent.KeyUp },
            "a menu's keys reached the Host: ${connection.events}",
        )
    }

    /** Space is the focused entry's press too. */
    @Test
    fun fr64_menu_space_presses_the_focused_entry() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val connection = FakeHostConnection(keyboardBatch(selected = KEY_ENTRIES[2]))
        showKeyboardMenu(connection)
        press(Key.Spacebar)
        assertEquals(
            listOf(HostEvent.Clicked(KEY_ENTRIES[2], clickHandler(KEY_ENTRIES[2]))),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
            "Space is one click of the focused entry",
        )
    }

    /** Home and End go to the first and the last entry, from wherever focus is. */
    @Test
    fun fr64_menu_home_and_end_go_to_the_ends() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        showKeyboardMenu(FakeHostConnection(keyboardBatch(selected = KEY_ENTRIES[1])))
        press(Key.MoveEnd)
        assertFocused(KEY_ENTRIES[2], "End")
        press(Key.MoveHome)
        assertFocused(KEY_ENTRIES[0], "Home")
        press(Key.MoveHome)
        assertFocused(KEY_ENTRIES[0], "Home at the first stays there")
    }

    /**
     * With nothing selected, focus goes to the first entry that can take it. A divider ahead
     * of it is passed over, by the arrows and by Home as well.
     */
    @Test
    fun fr64_menu_with_no_selection_focuses_the_first_entry() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            showKeyboardMenu(FakeHostConnection(keyboardBatch(selected = null, lead = true)))
            assertFocused(KEY_ENTRIES[0], "on open with nothing selected, the first focusable entry")
            press(Key.DirectionUp)
            assertFocused(KEY_ENTRIES[2], "Up past the divider goes round to the last")
            press(Key.DirectionDown)
            assertFocused(KEY_ENTRIES[0], "Down past the end skips the divider")
            press(Key.MoveEnd)
            press(Key.MoveHome)
            assertFocused(KEY_ENTRIES[0], "Home skips the divider")
        }

    /** A row marked selected through its `Semantics` modifier is the one focused on open. */
    @Test
    fun fr64_menu_focuses_a_row_selected_through_semantics() =
        runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
            showKeyboardMenu(FakeHostConnection(keyboardBatch(selected = KEY_ENTRIES[2], semanticsSelected = true)))
            assertFocused(KEY_ENTRIES[2], "on open, the entry the Semantics modifier selects")
        }

    /** Escape closes the menu and gives focus back to its anchor. */
    @Test
    fun fr64_menu_escape_returns_focus_to_the_anchor() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val connection = FakeHostConnection(keyboardBatch(selected = KEY_ENTRIES[1]))
        showKeyboardMenu(connection)
        assertFocused(KEY_ENTRIES[1], "on open")
        press(Key.Escape)
        assertTrue(
            onAllNodes(androidx.compose.ui.test.hasTestTag(menuPopupTestTag(MENU))).fetchSemanticsNodes().isEmpty(),
            "Escape left the menu open",
        )
        onNodeWithTag(nodeTestTag(ANCHOR)).assertIsFocused()
        assertTrue(
            connection.events.none { it is HostEvent.Clicked },
            "Escape pressed an entry: ${connection.events}",
        )
    }
}
