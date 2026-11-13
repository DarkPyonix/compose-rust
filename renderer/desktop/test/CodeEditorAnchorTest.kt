package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Protocol
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val BOX = 3
private const val ON_CARET = 71L

/** Sixty lines, `let value_0 = 0;` and on, so the view has somewhere to scroll to. */
private val LINES = (0 until 60).map { "let value_$it = $it;" }
private val TEXT = LINES.joinToString("\n")

/** Where a document position is in the field's text, which holds the whole window from line 0. */
private fun fieldOffset(line: Int, column: Int): Int = LINES.take(line).sumOf { it.length + 1 } + column

/** An editor alone on the page, 300 by 200 dp, with the records the test adds after it. */
private fun page(vararg extra: Mutation): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
    add(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    add(Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(300f, 200f)))
    add(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(TEXT)))
    addAll(extra)
}

private fun close(expected: Float, actual: Float, within: Float, what: String) =
    assertTrue(abs(expected - actual) <= within, "$what: expected $expected, was $actual")

/**
 * Criterion 19 of the code editor: the editor says where it draws its caret, and answers
 * the Host's questions about where a position is, both in the window's dp with the scroll
 * and the zoom already in them. Each runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorAnchorTest {
    @AfterTest
    fun windowedPath() {
        defaultCodeEditorPath = CodeEditorPath.Windowed
    }

    private fun ComposeUiTest.show(connection: FakeHostConnection): () -> ComposeRustHost {
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        return { host }
    }

    /**
     * Where the field draws the character at a position, in the window's dp: the left and
     * top of its box in the field's own layout, moved by where the field is.
     */
    private fun ComposeUiTest.drawnAt(line: Int, column: Int): Offset {
        val layout = drawnLayout(EDITOR)
        val box = layout.getBoundingBox(fieldOffset(line, column))
        val field = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).fetchSemanticsNode().positionInRoot
        return Offset((field.x + box.left) / 2f, (field.y + box.top) / 2f)
    }

    /**
     * Criterion 19.1. A caret the Host puts on the third line at the fifth column is
     * reported where that character is drawn, within a dp, and a scroll of 20dp reports the
     * new place once, 20dp higher.
     */
    @Test
    fun fr38_editor_reports_the_caret_rect() {
        installDrawnCodeEditor()
        var fieldCaret: HostEvent.CaretRect? = null
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(
                    page(
                        Mutation.SetProp(EDITOR, PropertyKind.OnCaretRect, PropertyValue.Integer(ON_CARET)),
                        Mutation.SetSelection(EDITOR, 0, 2, 4, 2, 4),
                    ),
                )
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                fun reported() = connection.nodeEvents.filterIsInstance<HostEvent.CaretRect>()
                val caret = reported().last()
                assertEquals(EDITOR, caret.nodeId)
                assertEquals(ON_CARET, caret.handlerId)
                assertEquals(2 to 4, caret.line to caret.column, "$path")
                assertTrue(caret.height > 10f && caret.width > 0f, "$path: the caret is $caret")
                if (path == CodeEditorPath.Windowed) {
                    val drawn = drawnAt(2, 4)
                    close(drawn.x, caret.x, 1f, "$path x")
                    close(drawn.y, caret.y, 1f, "$path y")
                    val layout = drawnLayout(EDITOR)
                    close((layout.getLineBottom(2) - layout.getLineTop(2)) / 2f, caret.height, 1f, "$path height")
                    fieldCaret = caret
                } else {
                    // The same face, the same gutter and the same rows: the drawn path puts the
                    // character where the field does.
                    val field = fieldCaret!!
                    close(field.x, caret.x, 1f, "$path x")
                    close(field.y, caret.y, 1f, "$path y")
                    close(field.height, caret.height, 1f, "$path height")
                }

                // The view scrolls 40 pixels, 20dp: one report, 20dp higher, nothing else moved.
                val before = reported().size
                connection.scheduleFrame(listOf(Mutation.SetScrollOffset(EDITOR, 40.0)))
                host().renderFrame(0L)
                waitForIdle()
                assertEquals(before + 1, reported().size, "$path: a scroll reports the caret once")
                val scrolled = reported().last()
                assertEquals(2 to 4, scrolled.line to scrolled.column)
                close(caret.y - 20f, scrolled.y, 0.5f, "$path scrolled y")
                close(caret.x, scrolled.x, 0.5f, "$path scrolled x")

                // A frame in which nothing moved says nothing.
                connection.scheduleFrame(listOf(Mutation.SetProp(EDITOR, PropertyKind.TabWidth, PropertyValue.Integer(4))))
                host().renderFrame(0L)
                waitForIdle()
                assertEquals(before + 1, reported().size, "$path: a still caret is not reported again")

                // The event is the header, two words and four floats.
                val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(40, Protocol.encodeEvent(scrolled, buffer))
            }
        }
    }

    /**
     * Criterion 19.2. A position on screen is answered with where a caret there is drawn,
     * one that is scrolled away or not in the document with nothing, and each query once. A
     * query sent with the editor waits for it to be drawn.
     */
    @Test
    fun fr38_editor_answers_position_rects() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(page(Mutation.QueryPositionRect(EDITOR, 4, 0, 0)))
                val host = show(connection)
                fun answers(request: Int) =
                    connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().filter { it.request == request }

                // Asked before the editor had a size, answered once it was drawn.
                val first = answers(4).single()
                assertEquals(EDITOR, first.nodeId)
                assertEquals(0L, first.handlerId)
                assertTrue(first.found, "$path: the start of the document is on screen")

                connection.scheduleFrame(
                    listOf(
                        Mutation.QueryPositionRect(EDITOR, 5, 1, 4),
                        Mutation.QueryPositionRect(EDITOR, 6, 55, 0),
                        Mutation.QueryPositionRect(EDITOR, 7, 1, 400),
                        Mutation.QueryPositionRect(EDITOR, 8, 999, 0),
                    ),
                )
                host().renderFrame(0L)
                waitForIdle()
                val visible = answers(5).single()
                assertTrue(visible.found, "$path: line 1 is on screen")
                if (path == CodeEditorPath.Windowed) {
                    val drawn = drawnAt(1, 4)
                    close(drawn.x, visible.x, 1f, "$path x")
                    close(drawn.y, visible.y, 1f, "$path y")
                }
                assertTrue(visible.y > first.y, "$path: line 1 is below line 0")
                // Fifty-five lines down is past the bottom of a view 200dp tall.
                assertFalse(answers(6).single().found, "$path: line 55 is not on screen")
                assertEquals(0f, answers(6).single().x)
                // Places that are not in the document are on no screen.
                assertFalse(answers(7).single().found)
                assertFalse(answers(8).single().found)

                // Once the view has scrolled down to it, line 55 is on screen.
                connection.scheduleFrame(listOf(Mutation.SetScrollOffset(EDITOR, 1.0e9)))
                host().renderFrame(0L)
                waitForIdle()
                connection.scheduleFrame(listOf(Mutation.QueryPositionRect(EDITOR, 9, 55, 0)))
                host().renderFrame(0L)
                waitForIdle()
                assertTrue(answers(9).single().found, "$path: line 55 after the scroll")
                // And no query was answered twice.
                assertEquals(listOf(4, 5, 6, 7, 8, 9), connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().map { it.request })
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
            }
        }
    }

    /**
     * The query through the interpreter: a node that is not an editor, or none at all, is a
     * protocol error and still an answer, and an editor removed before it was drawn answers
     * what waited. The record is 20 bytes on the wire.
     */
    @Test
    fun fr38_editor_answers_position_rects_through_the_interpreter() {
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("a\nb")))
        table.apply(Mutation.Create(BOX, WidgetKind.Box))
        table.drainEvents()
        table.drainErrors()

        table.apply(Mutation.QueryPositionRect(BOX, 1, 0, 0))
        table.apply(Mutation.QueryPositionRect(99, 2, 0, 0))
        assertEquals(
            listOf(TableError.UNSUPPORTED_PROPERTY, TableError.UNKNOWN_NODE),
            table.drainErrors().map { it.code },
        )
        assertEquals(
            listOf<HostEvent>(
                HostEvent.PositionRect(BOX, 0L, 1, false, 0f, 0f, 0f, 0f),
                HostEvent.PositionRect(99, 0L, 2, false, 0f, 0f, 0f, 0f),
            ),
            table.drainEvents(),
        )

        // Never drawn, so the query waits; the editor goes, so it is answered with nothing.
        table.apply(Mutation.QueryPositionRect(EDITOR, 3, 1, 0))
        assertEquals(emptyList(), table.drainEvents())
        table.apply(Mutation.Remove(EDITOR))
        assertEquals(listOf<HostEvent>(HostEvent.PositionRect(EDITOR, 0L, 3, false, 0f, 0f, 0f, 0f)), table.drainEvents())
        assertEquals(emptyList(), table.drainErrors())

        // The bytes the Rust encoder writes: tag 39, 20 bytes, the node, the request, the line
        // and the column.
        val batch = ByteBuffer.allocate(12 + 20).order(ByteOrder.LITTLE_ENDIAN)
        batch.putShort(0).putShort(12).putInt(12 + 20).putInt(1)
        batch.putShort(39).putShort(20).putInt(EDITOR).putInt(17).putInt(1).putInt(8)
        batch.flip()
        val decoded = mutableListOf<Mutation>()
        Protocol.decode(batch, decoded::add)
        assertEquals(listOf<Mutation>(Mutation.QueryPositionRect(EDITOR, 17, 1, 8)), decoded)
        // The answer is the header, two words and four floats.
        val out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, Protocol.encodeEvent(HostEvent.PositionRect(EDITOR, 0L, 17, true, 1f, 2f, 3f, 4f), out))
    }

    /** The handler the caret is reported to is an editor's own property. */
    @Test
    fun fr38_on_caret_rect_is_a_property_of_an_editor() {
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.OnCaretRect))
        assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.OnCaretRect))
        assertFalse(NodeTable.supportsProperty(WidgetKind.Text, PropertyKind.OnCaretRect))
    }
}
