package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.RowMap
import dev.darkpyonix.composerust.foundation.code.WordWrapRule
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.foundation.code.wrapBreaks
import dev.darkpyonix.composerust.foundation.code.wrapColumnsFor
import dev.darkpyonix.composerust.foundation.wordWrapRule
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WordWrapMode
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_VISIBLE = 91L

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** The `WordWrap` property's integer: the mode's tag in the low 16 bits, the columns above. */
internal fun wordWrapValue(mode: WordWrapMode, columns: Int = 0): Long = (mode.ordinal + 1).toLong() or (columns.toLong() shl 16)

/**
 * Criterion 25 of the code editor: a long line breaks onto rows below it, Up and Down follow
 * the rows, and the lines on screen are still the document's lines. Runs on the field path
 * and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorWordWrapTest {
    @AfterTest
    fun windowedPath() {
        defaultCodeEditorPath = CodeEditorPath.Windowed
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

    private fun page(text: String, width: Float, wrap: Long, height: Float = 240f) = listOf(
        theme(),
        Mutation.Create(EDITOR, WidgetKind.CodeEditor),
        Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(width, height)),
        Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)),
        Mutation.SetProp(EDITOR, PropertyKind.OnVisibleRangeChange, PropertyValue.Integer(ON_VISIBLE)),
        Mutation.SetProp(EDITOR, PropertyKind.WordWrap, PropertyValue.Integer(wrap)),
    )

    private var request = 0

    /** Where a caret at [line], [column] is drawn, in whole pixels. */
    private fun ComposeUiTest.place(connection: FakeHostConnection, host: () -> ComposeRustHost, line: Int, column: Int): Pair<Int, Int> {
        request += 1
        val asked = request
        connection.scheduleFrame(listOf(Mutation.QueryPositionRect(EDITOR, asked, line, column)))
        host().renderFrame(0L)
        waitForIdle()
        val rect = connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().single { it.request == asked }
        assertTrue(rect.found, "line $line, column $column is on screen")
        return rect.x.roundToInt() to rect.y.roundToInt()
    }

    private fun ComposeUiTest.focusEditor(path: CodeEditorPath): SemanticsNodeInteraction {
        val target = if (path == CodeEditorPath.Drawn) {
            onNodeWithTag(nodeTestTag(EDITOR)).also { it.performMouseInput { click(Offset(width - 4f, height - 4f)) } }
        } else {
            onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
        }
        waitForIdle()
        return target
    }

    /**
     * Criterion 25.1. In an editor 200dp wide with `Viewport`, a line of 500 characters is
     * drawn on several rows: the first character of its second row is a row below the first
     * character of the line and level with it, and the next line starts below all of them.
     * Down from the start of the line goes to the start of its second row, on the same line.
     */
    @Test
    fun fr38_word_wrap_viewport_breaks_long_lines() {
        installDrawnCodeEditor()
        val long = buildString { while (length < 500) append("value") .append(length % 7).append(' ') }.take(500)
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(page("$long\nnext", 200f, wordWrapValue(WordWrapMode.Viewport)))
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val model = host().table.node(EDITOR)!!.code!!
                assertTrue(model.wrapColumns in 5 until 100, "$path: the line breaks at ${model.wrapColumns} columns")
                val breaks = model.wrapBreaksOf(0)
                assertTrue(breaks.size >= 3, "$path: 500 characters are on ${breaks.size + 1} rows")

                val (startX, startY) = place(connection, host, 0, 0)
                val (secondX, secondY) = place(connection, host, 0, breaks[0])
                val (_, thirdY) = place(connection, host, 0, breaks[1])
                val row = secondY - startY
                assertTrue(row > 10, "$path: the second row is $row pixels below the first")
                assertEquals(2 * row, thirdY - startY, "$path: the third row is not below the second")
                assertTrue(abs(secondX - startX) <= 1, "$path: the second row starts at $secondX, the first at $startX")
                // The next line starts below every row of this one.
                assertEquals(breaks.size + 1, RowMap.of(model, wholeZoneRows = path == CodeEditorPath.Windowed).textRow(1), "$path")

                val target = focusEditor(path)
                connection.scheduleFrame(listOf(Mutation.SetSelection(EDITOR, 0, 0, 0, 0, 0)))
                host().renderFrame(0L)
                waitForIdle()
                target.performKeyInput { pressKey(Key.DirectionDown) }
                waitForIdle()
                assertEquals(CodePosition(0, breaks[0]), model.selection.caret, "$path: Down did not go to the next row of the line")
                target.performKeyInput { pressKey(Key.DirectionUp) }
                waitForIdle()
                assertEquals(CodePosition(0, 0), model.selection.caret, "$path: Up did not come back")

                // The rows of the first line fill the view, and the lines on screen are the
                // document's: only the first, however many rows it is drawn on.
                val visible = connection.nodeEvents.filterIsInstance<HostEvent.VisibleRangeChanged>().last()
                assertEquals(0 to 0, visible.firstLine to visible.lastLine, "$path: the lines on screen are not the document's")
            }
        }
    }

    /** Criterion 25.2. With `Column(40)` a line of 100 characters breaks at columns 40 and 80. */
    @Test
    fun fr38_word_wrap_column() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(page("x".repeat(100) + "\nnext", 900f, wordWrapValue(WordWrapMode.Column, 40)))
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val model = host().table.node(EDITOR)!!.code!!
                assertEquals(40, model.wrapColumns, "$path")
                assertContentEquals(intArrayOf(40, 80), model.wrapBreaksOf(0), "$path")
                val (startX, startY) = place(connection, host, 0, 0)
                val (_, lastOnFirstY) = place(connection, host, 0, 39)
                val (fortyX, fortyY) = place(connection, host, 0, 40)
                val (eightyX, eightyY) = place(connection, host, 0, 80)
                assertEquals(startY, lastOnFirstY, "$path: column 39 is not on the first row")
                val row = fortyY - startY
                assertTrue(row > 10, "$path: column 40 is $row pixels below the start")
                assertEquals(2 * row, eightyY - startY, "$path: column 80 is not on the third row")
                assertTrue(abs(fortyX - startX) <= 1 && abs(eightyX - startX) <= 1, "$path: the rows do not start at the left")
            }
        }
    }

    /**
     * The rule's parts: where a row breaks (after a space that fits, inside a word too long
     * for a row, never inside a pair, a tab to its stop and a wide letter as two columns), the
     * rows kept in step through edits, the rule read from its integer, and the columns a mode
     * comes to.
     */
    @Test
    fun fr38_word_wrap_rules_in_the_model() {
        assertContentEquals(intArrayOf(6, 11), wrapBreaks("alpha beta gamma", 8, 4))
        assertContentEquals(intArrayOf(4, 8), wrapBreaks("abcdefghij", 4, 4))
        assertContentEquals(intArrayOf(2), wrapBreaks("한글한글", 4, 4), "a wide letter takes two columns")
        assertContentEquals(intArrayOf(4), wrapBreaks("😀😀😀", 4, 4), "a pair is never split")
        assertContentEquals(intArrayOf(2), wrapBreaks("a\tb", 4, 4), "a tab goes to its stop")
        assertEquals(0, wrapBreaks("short", 40, 4).size)

        val model = CodeEditorModel("x".repeat(25) + "\nshort\n" + "y".repeat(9))
        model.setWrap(10, 4)
        assertEquals(listOf(0, 2, 2, 2), model.wrapPrefix()!!.toList())
        assertEquals(5, RowMap.of(model).totalRows)
        model.edit(CodePosition(1, 5), CodePosition(1, 5), "z".repeat(10), EditOrigin.Reader)
        model.edit(CodePosition(0, 0), CodePosition(0, 0), "new\n", EditOrigin.Reader)
        assertEquals(listOf(0, 0, 2, 3, 3), model.wrapPrefix()!!.toList(), "the rows did not follow the edits")
        model.setWrap(0, 4)
        assertNull(model.wrapPrefix())

        assertEquals(WordWrapRule(WordWrapMode.Column, 40), wordWrapRule(wordWrapValue(WordWrapMode.Column, 40)))
        assertEquals(WordWrapRule(WordWrapMode.Viewport, 0), wordWrapRule(wordWrapValue(WordWrapMode.Viewport)))
        assertNull(wordWrapRule(0))
        assertNull(wordWrapRule(wordWrapValue(WordWrapMode.Column, 0)), "a column count of none is not a rule")
        assertNull(wordWrapRule(9))
        assertEquals(30, wrapColumnsFor(WordWrapRule(WordWrapMode.Viewport, 0), 300, 10))
        assertEquals(20, wrapColumnsFor(WordWrapRule(WordWrapMode.Bounded, 20), 300, 10))
        assertEquals(30, wrapColumnsFor(WordWrapRule(WordWrapMode.Bounded, 80), 300, 10))
        assertEquals(80, wrapColumnsFor(WordWrapRule(WordWrapMode.Column, 80), 300, 10))
        assertEquals(0, wrapColumnsFor(null, 300, 10))

        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.WordWrap, PropertyValue.Integer(9)))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.WordWrap))
        assertFalse(NodeTable.supportsProperty(WidgetKind.Text, PropertyKind.WordWrap))
    }
}
