package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.CodeSelection
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
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
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_SELECTION = 81L

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

private fun p(line: Int, column: Int) = CodePosition(line, column)

/** Selection records as the Rust side writes them: anchor line and column, then active line and column. */
internal fun selectionBlob(vararg selections: CodeSelection): ByteArray {
    val buffer = ByteBuffer.allocate(selections.size * 16).order(ByteOrder.LITTLE_ENDIAN)
    for (selection in selections) {
        buffer.putInt(selection.anchor.line).putInt(selection.anchor.column)
        buffer.putInt(selection.caret.line).putInt(selection.caret.column)
    }
    return buffer.array()
}

/**
 * Criterion 26 of the code editor: several selections at once. Typing, deleting and pasting
 * happen at all of them as one step, Cmd or Ctrl and D adds the next match, and the Host
 * hears every selection. Runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class CodeEditorMultiCursorTest {
    @AfterTest
    fun reset() {
        defaultCodeEditorPath = CodeEditorPath.Windowed
        hostPlatformOverride = null
    }

    private fun ComposeUiTest.show(connection: FakeHostConnection): () -> ComposeRustHost {
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        return { host }
    }

    private fun page(text: String) = listOf(
        theme(),
        Mutation.Create(EDITOR, WidgetKind.CodeEditor),
        Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(360f, 200f)),
        Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)),
        Mutation.SetProp(EDITOR, PropertyKind.OnSelectionChange, PropertyValue.Integer(ON_SELECTION)),
    )

    private fun ComposeUiTest.focusEditor(path: CodeEditorPath): SemanticsNodeInteraction {
        val target = if (path == CodeEditorPath.Drawn) {
            onNodeWithTag(nodeTestTag(EDITOR)).also { it.performMouseInput { click(Offset(width - 4f, 4f)) } }
        } else {
            onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
        }
        waitForIdle()
        return target
    }

    private fun ComposeUiTest.send(connection: FakeHostConnection, host: () -> ComposeRustHost, vararg mutations: Mutation) {
        connection.scheduleFrame(mutations.toList())
        host().renderFrame(0L)
        waitForIdle()
    }

    /** The selections the Host was last told about, as anchor and active pairs. */
    private fun FakeHostConnection.lastSelections(): List<CodeSelection> {
        val values = nodeEvents.filterIsInstance<HostEvent.SelectionsChanged>().last().selections
        return (0 until values.size / 4).map {
            CodeSelection(p(values[it * 4], values[it * 4 + 1]), p(values[it * 4 + 2], values[it * 4 + 3]))
        }
    }

    /**
     * Criterion 26.1. Three carets, one on each line: typing `x` puts it on all three lines,
     * and one undo takes all three away. The Host hears all three carets, the primary first,
     * and hears the primary alone as it always did. Escape goes back to the primary.
     */
    @Test
    fun fr38_multi_cursor_types_at_every_caret() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            hostPlatformOverride = HostPlatform.MacOs
            runComposeUiTest {
                val connection = FakeHostConnection(page("aa\nbb\ncc"))
                val host = show(connection)
                val model = host().table.node(EDITOR)!!.code!!
                val target = focusEditor(path)
                val carets = arrayOf(CodeSelection.at(p(0, 1)), CodeSelection.at(p(1, 1)), CodeSelection.at(p(2, 1)))
                send(connection, host, Mutation.SetSelections(EDITOR, 0, selectionBlob(*carets)))
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                assertEquals(carets.toList(), model.selections, "$path")
                assertEquals(carets.toList(), connection.lastSelections(), "$path: the Host did not hear every caret")
                val primary = connection.nodeEvents.filterIsInstance<HostEvent.SelectionChanged>().last()
                assertEquals(listOf(0, 1, 0, 1), listOf(primary.anchorLine, primary.anchorColumn, primary.activeLine, primary.activeColumn))

                target.typeInto(path, "x")
                waitForIdle()
                assertEquals("axa\nbxb\ncxc", model.document.text(), "$path: x did not go in at every caret")
                assertEquals(listOf(p(0, 2), p(1, 2), p(2, 2)), model.selections.map { it.caret }, "$path")

                target.performKeyPress(KeyEvent(Key.Z, KeyEventType.KeyDown, isMetaPressed = true))
                waitForIdle()
                assertEquals("aa\nbb\ncc", model.document.text(), "$path: one undo did not take all three away")
                assertEquals(3, model.selections.size, "$path: undo did not bring the carets back")

                target.performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                assertEquals(1, model.selections.size, "$path: Escape did not go back to one caret")
                assertEquals(1, connection.lastSelections().size, "$path: the Host was not told it is one caret again")
            }
        }
    }

    /**
     * Criterion 26.2. With `foo` selected, Cmd and D selects the next `foo` as well, then the
     * third; once every one is selected it adds nothing.
     */
    @Test
    fun fr38_multi_cursor_add_next_match() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            hostPlatformOverride = HostPlatform.MacOs
            runComposeUiTest {
                val connection = FakeHostConnection(page("foo bar foo baz foo"))
                val host = show(connection)
                val model = host().table.node(EDITOR)!!.code!!
                val target = focusEditor(path)
                send(connection, host, Mutation.SetSelection(EDITOR, 0, 0, 0, 0, 3))
                fun next() {
                    target.performKeyPress(KeyEvent(Key.D, KeyEventType.KeyDown, isMetaPressed = true))
                    waitForIdle()
                }
                next()
                assertEquals(
                    listOf(CodeSelection(p(0, 0), p(0, 3)), CodeSelection(p(0, 8), p(0, 11))),
                    model.selections,
                    "$path: the next match was not added",
                )
                next()
                assertEquals(CodeSelection(p(0, 16), p(0, 19)), model.selections.last(), "$path")
                next()
                assertEquals(3, model.selections.size, "$path: a match already selected was added again")
                assertEquals(model.selections, connection.lastSelections(), "$path")

                // What is typed replaces all three.
                target.typeInto(path, "qux")
                waitForIdle()
                assertEquals("qux bar qux baz qux", model.document.text(), "$path")
            }
        }
    }

    /**
     * Alt and a press adds a caret, and Alt with Cmd and Down adds one on the line below, at
     * the same column where the line is long enough.
     */
    @Test
    fun fr38_multi_cursor_alt_click_and_alt_command_arrows_add_carets() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            hostPlatformOverride = HostPlatform.MacOs
            runComposeUiTest {
                val connection = FakeHostConnection(page("first line\nsecond\nthird line"))
                val host = show(connection)
                val model = host().table.node(EDITOR)!!.code!!
                val target = focusEditor(path)
                send(connection, host, Mutation.SetSelection(EDITOR, 0, 0, 8, 0, 8))
                target.performKeyPress(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown, isMetaPressed = true, isAltPressed = true))
                waitForIdle()
                assertEquals(listOf(p(0, 8), p(1, 6)), model.selections.map { it.caret }, "$path")

                // Where the third line's sixth character is, pressed with Alt held.
                connection.scheduleFrame(listOf(Mutation.QueryPositionRect(EDITOR, 1, 2, 6)))
                host().renderFrame(0L)
                waitForIdle()
                val rect = connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().single()
                val origin = onNodeWithTag(nodeTestTag(EDITOR)).fetchSemanticsNode().positionInRoot
                val at = Offset(rect.x - origin.x + 1f, rect.y - origin.y + rect.height / 2)
                onNodeWithTag(nodeTestTag(EDITOR)).performMultiModalInput {
                    key { keyDown(Key.AltLeft) }
                    mouse { click(at) }
                    key { keyUp(Key.AltLeft) }
                }
                waitForIdle()
                assertEquals(listOf(p(0, 8), p(1, 6), p(2, 6)), model.selections.map { it.caret }, "$path: Alt and a press did not add a caret")
                assertTrue(at.x.roundToInt() > 0)

                // Left moves every caret.
                target.performKeyInput { pressKey(Key.DirectionLeft) }
                waitForIdle()
                assertEquals(listOf(p(0, 7), p(1, 5), p(2, 5)), model.selections.map { it.caret }, "$path: Left did not move every caret")
            }
        }
    }

    /** The model's part: the Host's list, refused where it cannot be, and Backspace at every caret. */
    @Test
    fun fr38_multi_cursor_selections_in_the_model() {
        val model = CodeEditorModel("ab\ncd\nef")
        model.hostSetSelections(0, intArrayOf(0, 1, 0, 1, 1, 1, 1, 1, 2, 2, 2, 0))
        assertEquals(listOf(CodeSelection.at(p(0, 1)), CodeSelection.at(p(1, 1)), CodeSelection(p(2, 2), p(2, 0))), model.selections)
        assertTrue(model.editAtSelections("", before = 1))
        assertEquals("b\nd\n", model.document.text())
        assertTrue(model.undo())
        assertEquals("ab\ncd\nef", model.document.text())
        assertEquals(3, model.selections.size)
        model.drainOutput()
        // An end outside the document, or a list that is not whole selections, drops it all.
        model.hostSetSelections(0, intArrayOf(0, 0, 0, 0, 9, 0, 9, 0))
        model.hostSetSelections(0, intArrayOf(0, 0, 0))
        assertEquals(2, model.drainOutput().count { it is dev.darkpyonix.composerust.foundation.code.EditorOutput.Error })
        assertEquals(3, model.selections.size)
        assertTrue(model.dropSecondary())
        assertFalse(model.dropSecondary())

        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("ab")))
        table.drainErrors()
        table.apply(Mutation.SetSelections(EDITOR, 0, ByteArray(10)))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        table.apply(Mutation.SetSelections(EDITOR, 0, selectionBlob(CodeSelection.at(p(0, 1)), CodeSelection.at(p(0, 2)))))
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(2, table.node(EDITOR)!!.code!!.selections.size)
    }
}
