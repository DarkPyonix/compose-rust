package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.DecorationRecord
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val BOX = 3

/** The wire tags of the kinds and the role the test sends. */
private const val LINE_BACKGROUND = 5
private const val RANGE_BACKGROUND = 6
private val DIFF_ADDED = ColorRole.DiffAdded.ordinal + 1

/**
 * Decoration records as the Rust schema lays them out: version, kind, severity, colour
 * role, reserved, the range, the id, and an empty text pointing at the end of the records.
 */
private fun backgrounds(vararg records: IntArray): ByteArray {
    val buffer = ByteBuffer.allocate(records.size * 44).order(ByteOrder.LITTLE_ENDIAN)
    for (record in records) {
        val (kind, color) = record[0] to record[1]
        buffer.putInt(0).putShort(kind.toShort()).putShort(0).putShort(color.toShort()).putShort(0)
        for (index in 2 until 6) buffer.putInt(record[index])
        buffer.putLong(0L).putInt(records.size * 44).putInt(0)
    }
    return buffer.array()
}

private fun lineBackground(first: Int, last: Int, color: Int = DIFF_ADDED) =
    intArrayOf(LINE_BACKGROUND, color, first, 0, last, 0)

private fun rangeBackground(line: Int, from: Int, to: Int, color: Int = DIFF_ADDED) =
    intArrayOf(RANGE_BACKGROUND, color, line, from, line, to)

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** An editor alone on the page, 320 by 160 dp, with the records the test adds after it. */
private fun page(text: String, vararg extra: Mutation): List<Mutation> = buildList {
    add(theme())
    add(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    add(Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(320f, 160f)))
    add(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    addAll(extra)
}

/**
 * Criterion 22 of the code editor: a line background tints whole lines, past their end,
 * and follows them through edits, and a range background tints only its characters, more
 * strongly. Each runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorBackgroundTest {
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

    /** What the theme draws a role as, read from a box painted with it. */
    private fun roleColor(role: ColorRole): Color {
        var seen = Color.Unspecified
        runComposeUiTest {
            val connection = FakeHostConnection(
                listOf(
                    theme(),
                    Mutation.Create(BOX, WidgetKind.Box),
                    Mutation.SetModifier(BOX, 0, ProtocolModifier.Size(20f, 20f)),
                    Mutation.SetModifier(BOX, 1, ProtocolModifier.Background(Paint.Role(role))),
                ),
            )
            show(connection)
            seen = onNodeWithTag(nodeTestTag(BOX)).captureToImage().toPixelMap()[10, 10]
        }
        return seen
    }

    /** The picture of the editor, and where a caret at a position is in it, in its pixels. */
    private fun ComposeUiTest.picture(): PixelMap = onNodeWithTag(nodeTestTag(EDITOR)).captureToImage().toPixelMap()

    private fun ComposeUiTest.placeOf(connection: FakeHostConnection, host: () -> ComposeRustHost, request: Int, line: Int, column: Int): Pair<Int, Int> {
        connection.scheduleFrame(listOf(Mutation.QueryPositionRect(EDITOR, request, line, column)))
        host().renderFrame(0L)
        waitForIdle()
        val rect = connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().single { it.request == request }
        assertTrue(rect.found, "line $line, column $column is on screen")
        val origin = onNodeWithTag(nodeTestTag(EDITOR)).fetchSemanticsNode().positionInRoot
        return (rect.x - origin.x).roundToInt() to (rect.y - origin.y).roundToInt()
    }

    /**
     * Criterion 22.1. A `DiffAdded` line background on the second line paints it from the
     * text's left edge to the editor's right edge, far past its last character, and no
     * other line. A line typed in front of it moves it down with its line.
     */
    @Test
    fun fr38_line_background_spans_the_width_and_follows_edits() {
        installDrawnCodeEditor()
        val tint = roleColor(ColorRole.DiffAddedContainer)
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(
                    page(
                        "a = 1;\nb = 2;\nc = 3;\nd = 4;",
                        Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(backgrounds(lineBackground(1, 1)))),
                    ),
                )
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val (textLeft, secondTop) = placeOf(connection, host, 1, 1, 0)
                val (_, thirdTop) = placeOf(connection, host, 2, 2, 0)
                val row = thirdTop - secondTop
                assertTrue(row > 10, "$path: a row is $row pixels")
                val before = picture()
                val right = before.width - 3
                // Past the end of the line, at the right edge, and just inside the text area.
                for (x in listOf(right, textLeft + 120, textLeft + 1)) {
                    assertEquals(tint, before[x, secondTop + 2], "$path: the second line at $x")
                }
                assertNotEquals(tint, before[right, thirdTop + row / 2], "$path: the third line is tinted")
                assertNotEquals(tint, before[right, secondTop - row / 2], "$path: the first line is tinted")

                // A line typed in front of everything: the tint goes down with its line.
                connection.scheduleFrame(listOf(Mutation.EditCode(EDITOR, 1, 0, 0, 0, 0, 0, "z = 0;\n")))
                host().renderFrame(0L)
                waitForIdle()
                val after = picture()
                assertEquals(tint, after[right, thirdTop + row / 2], "$path: the tint did not follow its line down")
                assertNotEquals(tint, after[right, secondTop + 2], "$path: the tint stayed on the line it left")
            }
        }
    }

    /**
     * Criterion 22.2. A range background over `value` paints those five characters in the
     * stronger tint and nothing either side of them, on a line no other tint is on.
     */
    @Test
    fun fr38_range_background_paints_its_characters() {
        installDrawnCodeEditor()
        val strong = roleColor(ColorRole.DiffAddedEmphasis)
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(
                    page(
                        "// header\nlet value = 42;\n",
                        Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(backgrounds(rangeBackground(1, 4, 9)))),
                    ),
                )
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val (from, top) = placeOf(connection, host, 1, 1, 4)
                val (to, _) = placeOf(connection, host, 2, 1, 9)
                val (lineStart, _) = placeOf(connection, host, 3, 1, 0)
                val pixels = picture()
                // The top of the row, above the letters, is the tint and nothing else.
                val y = top + 1
                for (x in listOf(from + 2, (from + to) / 2, to - 2)) {
                    assertEquals(strong, pixels[x, y], "$path: inside the range at $x")
                }
                for (x in listOf(lineStart + 2, from - 3, to + 3, pixels.width - 3)) {
                    assertNotEquals(strong, pixels[x, y], "$path: outside the range at $x")
                }
            }
        }
    }

    /**
     * The two kinds through the model: lines follow edits as lines, a range as text, a
     * background with no role is refused, and every edit that takes all of a range or every
     * line of a background takes it away.
     */
    @Test
    fun fr38_line_and_range_backgrounds_follow_edits_in_the_model() {
        val model = CodeEditorModel("a\nb\nc\nd")
        model.setDecorations(
            listOf(
                DecorationRecord(0, DecorationKind.LineBackground, null, ColorRole.DiffAdded, 1, 0, 2, 0, 0L, ""),
                DecorationRecord(0, DecorationKind.RangeBackground, null, ColorRole.DiffRemoved, 3, 0, 3, 1, 0L, ""),
                DecorationRecord(0, DecorationKind.LineBackground, null, null, 0, 0, 0, 0, 0L, ""),
            ),
        )
        assertEquals(1, model.drainOutput().size, "a background with no colour is reported")
        assertEquals(listOf(1, 2), (0..3).filter { model.lineBackgroundAt(it) != null })
        assertEquals(ColorRole.DiffAdded, model.lineBackgroundAt(1)?.color)

        // Typing inside the first tinted line keeps both lines tinted; a line before them
        // moves them both.
        model.edit(CodePosition(1, 1), CodePosition(1, 1), "x", EditOrigin.Typing)
        assertEquals(listOf(1, 2), (0..3).filter { model.lineBackgroundAt(it) != null })
        model.edit(CodePosition(0, 0), CodePosition(0, 0), "new\n", EditOrigin.Reader)
        assertEquals(listOf(2, 3), (0..4).filter { model.lineBackgroundAt(it) != null })
        val range = model.decorations.single { it.kind == DecorationKind.RangeBackground }
        assertEquals(CodePosition(4, 0) to CodePosition(4, 1), range.start to range.end)

        // Deleting both tinted lines takes the background with them.
        model.edit(CodePosition(2, 0), CodePosition(4, 0), "", EditOrigin.Reader)
        assertNull((0..2).firstOrNull { model.lineBackgroundAt(it) != null })

        // Through the interpreter, a list naming a role nobody knows is one error for that record.
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("a\nb")))
        table.drainErrors()
        table.apply(
            Mutation.SetProp(
                EDITOR,
                PropertyKind.Decorations,
                PropertyValue.Bytes(backgrounds(lineBackground(0, 1), lineBackground(0, 0, color = 60_000))),
            ),
        )
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        assertEquals(listOf(0, 1), (0..1).filter { table.node(EDITOR)!!.code!!.lineBackgroundAt(it) != null })
    }
}
