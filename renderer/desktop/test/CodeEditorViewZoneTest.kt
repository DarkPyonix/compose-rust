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
import dev.darkpyonix.composerust.protocol.ViewZoneRecord
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
private const val ON_VISIBLE = 62L

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** View zone records as the Rust schema lays them out: the line, the height and an empty text. */
internal fun viewZones(vararg zones: Pair<Int, Float>): ByteArray {
    val buffer = ByteBuffer.allocate(zones.size * 16).order(ByteOrder.LITTLE_ENDIAN)
    for ((line, height) in zones) buffer.putInt(line).putFloat(height).putInt(zones.size * 16).putInt(0)
    return buffer.array()
}

/**
 * Criterion 24 of the code editor: a view zone is room after a line that is not text. It
 * pushes the lines after it down, the caret passes over it, and it follows its line through
 * edits. Runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorViewZoneTest {
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

    private fun ComposeUiTest.top(connection: FakeHostConnection, host: () -> ComposeRustHost, request: Int, line: Int): Int {
        connection.scheduleFrame(listOf(Mutation.QueryPositionRect(EDITOR, request, line, 0)))
        host().renderFrame(0L)
        waitForIdle()
        val rect = connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().single { it.request == request }
        assertTrue(rect.found, "line $line is on screen")
        return rect.y.roundToInt()
    }

    /**
     * Criterion 24.1. A zone two rows high after the third line pushes the fourth line down
     * by those two rows. Down from the third line goes to the fourth, over the zone, and Up
     * comes back. A line typed in front of everything moves the zone down with its line.
     */
    @Test
    fun fr38_view_zones_push_lines_and_are_skipped() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        theme(),
                        Mutation.Create(EDITOR, WidgetKind.CodeEditor),
                        Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(320f, 240f)),
                        Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("zero\none\ntwo\nthree\nfour")),
                        Mutation.SetProp(EDITOR, PropertyKind.OnVisibleRangeChange, PropertyValue.Integer(ON_VISIBLE)),
                        Mutation.SetProp(EDITOR, PropertyKind.ViewZones, PropertyValue.Bytes(viewZones(2 to 2f))),
                    ),
                )
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val model = host().table.node(EDITOR)!!.code!!
                val row = top(connection, host, 1, 1) - top(connection, host, 2, 0)
                assertTrue(row > 10, "$path: a row is $row pixels")
                assertEquals(row, top(connection, host, 3, 2) - top(connection, host, 4, 1), "$path: the zone moved the third line")
                assertEquals(3 * row, top(connection, host, 5, 3) - top(connection, host, 6, 2), "$path: the zone did not push the fourth line down two rows")

                val target: SemanticsNodeInteraction = if (path == CodeEditorPath.Drawn) {
                    onNodeWithTag(nodeTestTag(EDITOR)).also { it.performMouseInput { click(Offset(width - 4f, 4f)) } }
                } else {
                    onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
                }
                waitForIdle()
                connection.scheduleFrame(listOf(Mutation.SetSelection(EDITOR, 0, 2, 1, 2, 1)))
                host().renderFrame(0L)
                waitForIdle()
                assertEquals(CodePosition(2, 1), model.selection.caret, "$path")
                target.performKeyInput { pressKey(Key.DirectionDown) }
                waitForIdle()
                assertEquals(CodePosition(3, 1), model.selection.caret, "$path: Down did not go over the zone")
                target.performKeyInput { pressKey(Key.DirectionUp) }
                waitForIdle()
                assertEquals(CodePosition(2, 1), model.selection.caret, "$path: Up did not come back over the zone")

                // A line in front of everything: the zone stays after the line it followed.
                connection.scheduleFrame(listOf(Mutation.EditCode(EDITOR, 1, 0, 0, 0, 0, 0, "new\n")))
                host().renderFrame(0L)
                waitForIdle()
                assertEquals(3, model.viewZones.single().afterLine, "$path")
                assertEquals(row, top(connection, host, 7, 3) - top(connection, host, 8, 2), "$path: the zone stayed where it was")
                assertEquals(3 * row, top(connection, host, 9, 4) - top(connection, host, 10, 3), "$path: the zone did not follow its line")
                // The lines on screen are the document's own, the zone not counted among them.
                val visible = connection.nodeEvents.filterIsInstance<HostEvent.VisibleRangeChanged>().last()
                assertEquals(0 to 5, visible.firstLine to visible.lastLine, "$path")
            }
        }
    }

    /**
     * The model's part: a zone follows its line as lines come and go before it and stays put
     * as its own line is typed on; a zone after a line the document does not have, or one
     * that is not a height, is refused with a protocol error and the rest are kept.
     */
    @Test
    fun fr38_view_zones_follow_edits_in_the_model() {
        val model = CodeEditorModel("a\nb\nc\nd")
        model.setViewZones(listOf(ViewZoneRecord(1, 1.5f, "gap"), ViewZoneRecord(9, 1f, ""), ViewZoneRecord(2, 0f, "")))
        assertEquals(2, model.drainOutput().size, "the two zones that cannot be are reported")
        assertEquals(listOf(1), model.viewZones.map { it.afterLine })
        model.edit(CodePosition(1, 1), CodePosition(1, 1), "x", EditOrigin.Typing)
        assertEquals(1, model.viewZones.single().afterLine)
        model.edit(CodePosition(0, 0), CodePosition(0, 0), "top\n", EditOrigin.Reader)
        assertEquals(2, model.viewZones.single().afterLine)
        // Deleting the line the zone follows closes it up on the line before.
        model.edit(CodePosition(1, 1), CodePosition(2, 2), "", EditOrigin.Reader)
        assertEquals(1, model.viewZones.single().afterLine)

        // In pixels, half a row more after line 1 where a zone may be any height, and a whole
        // row where it may only be whole rows.
        val exact = RowMap.of(model)
        assertEquals(20, exact.textTopPx(1, 20))
        assertEquals(2 * 20 + 30, exact.textTopPx(2, 20))
        assertEquals(1, exact.lineAtPx(2 * 20 + 29, 20), "a point in the zone belongs to the line it follows")
        assertEquals(-1, exact.textRowAtPx(1, 2 * 20 + 5, 20), "a point in the zone is on no row of text")
        assertEquals(2 * 20 + 40, RowMap.of(model, wholeZoneRows = true).textTopPx(2, 20))

        // Through the interpreter, on an editor and nowhere else.
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("a\nb")))
        table.drainErrors()
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.ViewZones, PropertyValue.Bytes(viewZones(0 to 1f, 5 to 1f))))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        assertEquals(listOf(0), table.node(EDITOR)!!.code!!.viewZones.map { it.afterLine })
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.ViewZones))
        assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.ViewZones))
    }
}
