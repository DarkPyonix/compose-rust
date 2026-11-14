package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.UserSelect
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

private const val ROOT = 1
private const val PLAIN = 2
private const val GUARD = 3
private const val GUARDED = 4
private const val BUTTON = 5
private const val CLICKABLE = 6
private const val CLICKABLE_LABEL = 7
private const val WHOLE = 8
private const val WHOLE_FIRST = 9
private const val WHOLE_SECOND = 10
private const val BUTTON_HANDLER = 51L
private const val CLICKABLE_HANDLER = 52L

/**
 * What a copy produced, whichever clipboard it went through: the suspending one a
 * selection region copies with, or the clipboard manager.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class RecordingClipboards {
    var copied: String? = null

    val clipboard = object : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            copied = when (val native = clipEntry?.nativeClipEntry) {
                is String -> native
                is AnnotatedString -> native.text
                is Transferable -> native.getTransferData(DataFlavor.stringFlavor) as? String
                else -> return
            }
        }

        override val nativeClipboard: NativeClipboard =
            java.awt.datatransfer.Clipboard("user-select-test")
    }

    @Suppress("DEPRECATION")
    val manager = object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString.text
        }

        override fun getText(): AnnotatedString? = copied?.let(::AnnotatedString)
    }
}

private val onMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

private fun MutableList<Mutation>.text(id: Int, value: String, parent: Int, index: Int) {
    add(Mutation.Create(id, WidgetKind.Text))
    add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(value)))
    add(Mutation.Insert(parent, id, index))
}

private fun MutableList<Mutation>.userSelect(id: Int, value: UserSelect) {
    add(Mutation.SetProp(id, PropertyKind.UserSelect, PropertyValue.Integer(value.ordinal.toLong())))
}

private fun MutableList<Mutation>.theme() {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
}

/**
 * A region with a plain line of text in it, and a box that opted out of selection holding
 * a label the way a terminal tab holds its title, a button and a pressable container.
 */
private fun regionWithAGuard(region: WidgetKind, islandZoom: Float? = null): List<Mutation> = buildList {
    theme()
    add(Mutation.Create(ROOT, region))
    if (islandZoom != null) {
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.RequiredSize(400f, 300f)))
        add(Mutation.SetProp(ROOT, PropertyKind.IslandZoom, PropertyValue.Float(islandZoom)))
    }
    text(PLAIN, "plain words", ROOT, 0)
    add(Mutation.Create(GUARD, WidgetKind.Column))
    if (islandZoom != null) add(Mutation.SetModifier(GUARD, 0, ProtocolModifier.Offset(0f, 60f)))
    userSelect(GUARD, UserSelect.None)
    add(Mutation.Insert(ROOT, GUARD, 1))
    text(GUARDED, "zsh", GUARD, 0)
    add(Mutation.Create(BUTTON, WidgetKind.Button))
    add(Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text("Close")))
    add(Mutation.SetProp(BUTTON, PropertyKind.OnClick, PropertyValue.Integer(BUTTON_HANDLER)))
    add(Mutation.Insert(GUARD, BUTTON, 1))
    add(Mutation.Create(CLICKABLE, WidgetKind.Box))
    add(Mutation.SetProp(CLICKABLE, PropertyKind.OnClick, PropertyValue.Integer(CLICKABLE_HANDLER)))
    add(Mutation.Insert(GUARD, CLICKABLE, 2))
    text(CLICKABLE_LABEL, "tab", CLICKABLE, 0)
}

/**
 * Text selection driven the way CSS `user-select` asks: an HTML region selects by default,
 * `None` opts a subtree out, `Text` opts one in, and `All` takes a node whole.
 */
@OptIn(ExperimentalTestApi::class)
class UserSelectTest {

    private fun ComposeUiTest.show(connection: FakeHostConnection, clipboards: RecordingClipboards) {
        setContent {
            @Suppress("DEPRECATION")
            CompositionLocalProvider(
                LocalClipboard provides clipboards.clipboard,
                LocalClipboardManager provides clipboards.manager,
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.copy() {
        onRoot().performKeyInput {
            withKeyDown(if (onMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.doubleClickOn(node: Int) {
        val bounds = onNodeWithTag(nodeTestTag(node), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        onRoot().performMouseInput { doubleClick(Offset(bounds.left + 4f, bounds.center.y)) }
        waitForIdle()
    }

    private fun ComposeUiTest.drag(node: Int) {
        val bounds = onNodeWithTag(nodeTestTag(node), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        onRoot().performMouseInput {
            moveTo(Offset(bounds.left + 1f, bounds.top + 1f))
            press()
            moveTo(bounds.center)
            moveTo(Offset(bounds.right - 1f, bounds.bottom - 1f))
            release()
        }
        waitForIdle()
    }

    /**
     * Double-clicking a label under `UserSelect::None` selects nothing, though a region is
     * open around it; the same double-click on plain text in that region selects its word.
     */
    @Test
    fun fr47_user_select_none_takes_no_part_in_selection() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        show(FakeHostConnection(regionWithAGuard(WidgetKind.SelectionContainer)), clipboards)

        doubleClickOn(GUARDED)
        copy()
        assertNull(clipboards.copied, "a label under UserSelect None was selected")

        doubleClickOn(PLAIN)
        copy()
        assertEquals("plain", clipboards.copied?.trim())
    }

    /** Text in an HTML region is selectable without a SelectionContainer around it. */
    @Test
    fun fr47_island_text_selectable_by_default() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        show(FakeHostConnection(regionWithAGuard(WidgetKind.AbsoluteBox, islandZoom = 1f)), clipboards)

        drag(PLAIN)
        copy()

        assertEquals("plain words", clipboards.copied?.trimEnd('\n'))
    }

    /** An HTML region whose root says `None` opens no selection at all. */
    @Test
    fun fr47_island_root_with_user_select_none_opens_no_region() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        val tree = regionWithAGuard(WidgetKind.AbsoluteBox, islandZoom = 1f).toMutableList()
        tree.userSelect(ROOT, UserSelect.None)
        show(FakeHostConnection(tree), clipboards)

        drag(PLAIN)
        copy()

        assertNull(clipboards.copied, "text in an island whose root said None was selected")
    }

    /**
     * Under `None` in an HTML region, text cannot be dragged over or double-clicked into a
     * selection, and the button and the pressable container there still answer a press.
     */
    @Test
    fun fr47_user_select_none_blocks_selection_but_not_controls() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        val connection = FakeHostConnection(regionWithAGuard(WidgetKind.AbsoluteBox, islandZoom = 1f))
        show(connection, clipboards)

        drag(GUARDED)
        copy()
        assertNull(clipboards.copied, "dragging over text under UserSelect None selected it")
        doubleClickOn(CLICKABLE_LABEL)
        copy()
        assertNull(clipboards.copied, "double-clicking a pressable container's label selected it")

        val before = connection.nodeEvents.size
        onNodeWithTag(nodeTestTag(BUTTON)).performClick()
        onNodeWithTag(nodeTestTag(CLICKABLE)).performClick()
        waitForIdle()

        val clicks = connection.nodeEvents.drop(before).filterIsInstance<HostEvent.Clicked>()
        assertEquals(listOf(BUTTON to BUTTON_HANDLER, CLICKABLE to CLICKABLE_HANDLER), clicks.map { it.nodeId to it.handlerId })
    }

    /** `UserSelect::Text` makes the text under it selectable where no region was open. */
    @Test
    fun fr47_user_select_text_opens_a_region() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        val tree = buildList {
            theme()
            add(Mutation.Create(ROOT, WidgetKind.Column))
            text(PLAIN, "not selectable", ROOT, 0)
            add(Mutation.Create(GUARD, WidgetKind.Column))
            userSelect(GUARD, UserSelect.Text)
            add(Mutation.Insert(ROOT, GUARD, 1))
            text(GUARDED, "selectable words", GUARD, 0)
        }
        show(FakeHostConnection(tree), clipboards)

        drag(PLAIN)
        copy()
        assertNull(clipboards.copied, "text outside the opted-in node was selected")

        drag(GUARDED)
        copy()
        assertEquals("selectable words", clipboards.copied?.trimEnd('\n'))
    }

    /**
     * One press on a node under `UserSelect::All` selects every piece of its text, and a
     * copy puts all of it on the clipboard. A drag over part of it cannot select less.
     */
    @Test
    fun fr47_user_select_all_selects_the_whole_node() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        val tree = buildList {
            theme()
            add(Mutation.Create(ROOT, WidgetKind.SelectionContainer))
            text(PLAIN, "outside", ROOT, 0)
            add(Mutation.Create(WHOLE, WidgetKind.Column))
            userSelect(WHOLE, UserSelect.All)
            add(Mutation.Insert(ROOT, WHOLE, 1))
            text(WHOLE_FIRST, "first part", WHOLE, 0)
            text(WHOLE_SECOND, "second part", WHOLE, 1)
        }
        val connection = FakeHostConnection(tree)
        show(connection, clipboards)

        onNodeWithTag(nodeTestTag(WHOLE_FIRST)).performClick()
        waitForIdle()
        copy()
        assertEquals("first part\nsecond part", clipboards.copied)

        clipboards.copied = null
        drag(WHOLE_SECOND)
        copy()
        assertEquals("first part\nsecond part", clipboards.copied, "a drag selected part of the node")
        assertEquals(emptyList(), connection.nodeEvents)
    }

    /** Selecting and copying in an HTML region sends the Host nothing. */
    @Test
    fun fr47_selection_sends_nothing_to_the_host() = runComposeUiTest {
        val clipboards = RecordingClipboards()
        val connection = FakeHostConnection(regionWithAGuard(WidgetKind.AbsoluteBox, islandZoom = 1f))
        show(connection, clipboards)

        drag(PLAIN)
        copy()

        assertNotNull(clipboards.copied, "the drag selected nothing, so this proves nothing")
        assertEquals(emptyList(), connection.nodeEvents)
    }

    /**
     * A container that answers a press is a button, so its text takes no part in the
     * selection region around it, in a SelectionContainer and in an HTML region alike. A
     * double-click there selects nothing and copies nothing, each press arrives once, and
     * the plain text beside it is still selected by the same double-click.
     */
    @Test
    fun fr47_pressable_container_text_is_not_selectable() {
        for (region in listOf(WidgetKind.SelectionContainer, WidgetKind.AbsoluteBox)) runComposeUiTest {
            val clipboards = RecordingClipboards()
            val tree = buildList {
                theme()
                add(Mutation.Create(ROOT, region))
                if (region == WidgetKind.AbsoluteBox) {
                    add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.RequiredSize(400f, 300f)))
                    add(Mutation.SetProp(ROOT, PropertyKind.IslandZoom, PropertyValue.Float(1f)))
                }
                text(PLAIN, "plain words", ROOT, 0)
                add(Mutation.Create(CLICKABLE, WidgetKind.Box))
                if (region == WidgetKind.AbsoluteBox) add(Mutation.SetModifier(CLICKABLE, 0, ProtocolModifier.Offset(0f, 60f)))
                add(Mutation.SetProp(CLICKABLE, PropertyKind.OnClick, PropertyValue.Integer(CLICKABLE_HANDLER)))
                add(Mutation.Insert(ROOT, CLICKABLE, 1))
                text(CLICKABLE_LABEL, "tab", CLICKABLE, 0)
                add(Mutation.Create(WHOLE, WidgetKind.Row))
                if (region == WidgetKind.AbsoluteBox) add(Mutation.SetModifier(WHOLE, 0, ProtocolModifier.Offset(0f, 120f)))
                add(Mutation.SetModifier(WHOLE, if (region == WidgetKind.AbsoluteBox) 1 else 0, ProtocolModifier.Clickable(BUTTON_HANDLER)))
                add(Mutation.Insert(ROOT, WHOLE, 2))
                text(WHOLE_FIRST, "row", WHOLE, 0)
            }
            val connection = FakeHostConnection(tree)
            show(connection, clipboards)

            doubleClickOn(CLICKABLE_LABEL)
            copy()
            assertNull(clipboards.copied, "double-clicking an on_click container's label in $region selected it")
            doubleClickOn(WHOLE_FIRST)
            copy()
            assertNull(clipboards.copied, "double-clicking a Clickable container's label in $region selected it")

            val clicks = connection.nodeEvents.filterIsInstance<HostEvent.Clicked>().map { it.nodeId to it.handlerId }
            assertEquals(
                List(2) { CLICKABLE to CLICKABLE_HANDLER } + List(2) { WHOLE to BUTTON_HANDLER },
                clicks,
                "each press of a double-click should arrive once in $region",
            )

            doubleClickOn(PLAIN)
            copy()
            assertEquals("plain", clipboards.copied?.trim(), "plain text beside the containers in $region")
        }
    }
}
