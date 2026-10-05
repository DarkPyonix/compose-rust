package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROOT = 1
private const val OUTSIDE = 2
private const val REGION = 3
private const val PARAGRAPH = 4
private const val ITEM = 5
private const val SCROLLER = 6
private const val CODE = 7
private const val BUTTON = 8
private const val BUTTON_HANDLER = 41L

private fun text(id: Int, value: String): List<Mutation> = listOf(
    Mutation.Create(id, WidgetKind.Text),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(value)),
)

/**
 * A page with one line of text outside a selectable region, and inside it a paragraph, a
 * list item, a line of code in a list that scrolls sideways, and a button.
 */
private fun pageTree(): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
    add(Mutation.Create(ROOT, WidgetKind.Column))
    addAll(text(OUTSIDE, "Not selectable"))
    add(Mutation.Insert(ROOT, OUTSIDE, 0))
    add(Mutation.Create(REGION, WidgetKind.SelectionContainer))
    add(Mutation.Insert(ROOT, REGION, 1))
    addAll(text(PARAGRAPH, "A paragraph."))
    add(Mutation.Insert(REGION, PARAGRAPH, 0))
    addAll(text(ITEM, "- a list item"))
    add(Mutation.Insert(REGION, ITEM, 1))
    add(Mutation.Create(SCROLLER, WidgetKind.LazyRow))
    add(Mutation.Insert(REGION, SCROLLER, 2))
    addAll(text(CODE, "let code = 1;"))
    add(Mutation.Insert(SCROLLER, CODE, 0))
    add(Mutation.Create(BUTTON, WidgetKind.Button))
    add(Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text("Press me")))
    add(Mutation.SetProp(BUTTON, PropertyKind.OnClick, PropertyValue.Integer(BUTTON_HANDLER)))
    add(Mutation.Insert(REGION, BUTTON, 3))
}

/**
 * A clipboard that keeps what it was given rather than handing it to the machine, so a test
 * can read what a copy produced without touching the clipboard of whoever runs it.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class RecordingClipboard : Clipboard {
    var copied: String? = null
        private set

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        // The toolkit-free Compose answers a copy with plain text or an AnnotatedString, and
        // upstream's with a Transferable. Either is what a copy produced.
        copied = when (val native = clipEntry?.nativeClipEntry) {
            is String -> native
            is AnnotatedString -> native.text
            is Transferable -> native.getTransferData(DataFlavor.stringFlavor) as? String
            else -> return
        }
    }

    override val nativeClipboard: NativeClipboard =
        java.awt.datatransfer.Clipboard("selection-test")
}

private val onMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * Drags from the top left of [from] to the bottom right of [to], then presses the copy
 * shortcut this machine uses.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.dragAndCopy(from: Int, to: Int) {
    val start = onNodeWithTag(nodeTestTag(from)).fetchSemanticsNode().boundsInRoot
    val end = onNodeWithTag(nodeTestTag(to)).fetchSemanticsNode().boundsInRoot
    onRoot().performMouseInput {
        moveTo(Offset(start.left + 1f, start.top + 1f))
        press()
        moveTo(Offset((start.left + end.right) / 2f, (start.top + end.bottom) / 2f))
        moveTo(Offset(end.right - 1f, end.bottom - 1f))
        release()
    }
    waitForIdle()
    onRoot().performKeyInput {
        withKeyDown(if (onMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) }
    }
    waitForIdle()
}

/**
 * Text that can be dragged over and copied, in one region, with nothing about it crossing
 * to the Host.
 */
@OptIn(ExperimentalTestApi::class)
class SelectionContainerTest {

    /** The region carries no property of its own beyond the ones every widget takes. */
    @Test
    fun fr33_a_selection_region_carries_nothing_of_its_own() {
        for (property in listOf(PropertyKind.Text, PropertyKind.Count, PropertyKind.Checked)) {
            assertFalse(NodeTable.supportsProperty(WidgetKind.SelectionContainer, property))
        }
    }

    /**
     * One drag across a paragraph, a list item and code in a sideways list, then the copy
     * shortcut, puts the three on the clipboard with a line break between each. The button
     * in the region is not part of what was copied.
     */
    @Test
    fun fr33_one_drag_copies_three_blocks_on_their_own_lines() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        val connection = FakeHostConnection(pageTree())
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        dragAndCopy(PARAGRAPH, BUTTON)

        val copied = assertNotNull(clipboard.copied, "nothing reached the clipboard")
        assertEquals("A paragraph.\n- a list item\nlet code = 1;", copied.trimEnd('\n'))
        assertFalse("Press me" in copied, "the button's label was selected with the text")
    }

    /** Text outside a region does not select, so a drag over it copies nothing. */
    @Test
    fun fr33_text_outside_a_region_is_not_selectable() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        val connection = FakeHostConnection(pageTree())
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        dragAndCopy(OUTSIDE, OUTSIDE)

        assertNull(clipboard.copied, "text outside the region was selected and copied")
    }

    /** Neither selecting nor copying sends the Host anything. */
    @Test
    fun fr33_selecting_and_copying_send_no_events() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        val connection = FakeHostConnection(pageTree())
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        dragAndCopy(PARAGRAPH, CODE)

        assertNotNull(clipboard.copied, "the drag selected nothing, so this proves nothing")
        assertEquals(emptyList(), connection.nodeEvents)
    }

    /** A button inside a region is still a button. */
    @Test
    fun fr33_a_button_inside_a_region_is_pressed() = runComposeUiTest {
        val connection = FakeHostConnection(pageTree())
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        onNodeWithTag(nodeTestTag(BUTTON)).performClick()
        waitForIdle()

        val clicks = connection.nodeEvents.filterIsInstance<HostEvent.Clicked>()
        assertEquals(1, clicks.size)
        assertEquals(BUTTON, clicks.single().nodeId)
        assertEquals(BUTTON_HANDLER, clicks.single().handlerId)
    }

    /**
     * A Text that grows while a selection stands, the way the last message grows while it
     * streams in, leaves the selection of the Text that did not change where it was.
     */
    @Test
    fun fr33_a_growing_text_leaves_the_selection_of_the_others() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        val connection = FakeHostConnection(pageTree())
        connection.respondWith {
            HostResponse(
                listOf(Mutation.AppendText(CODE, " // more")),
            )
        }
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        dragAndCopy(PARAGRAPH, ITEM)
        assertTrue(clipboard.copied.orEmpty().startsWith("A paragraph."))

        host.dispatch(HostEvent.Clicked(ROOT, 0))
        waitForIdle()
        onRoot().performKeyInput {
            withKeyDown(if (onMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) }
        }
        waitForIdle()

        assertEquals("A paragraph.\n- a list item", clipboard.copied?.trimEnd('\n'))
    }
}
