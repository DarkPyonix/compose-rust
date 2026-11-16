package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.CodeSelection
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.RowMap
import dev.darkpyonix.composerust.foundation.code.pressGutter
import dev.darkpyonix.composerust.protocol.CodeEditorRecords
import dev.darkpyonix.composerust.protocol.CodeEditRecord
import dev.darkpyonix.composerust.protocol.EditGroupStatus
import dev.darkpyonix.composerust.protocol.GutterDecorationKind
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.ProtocolException
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.RevealAlign
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDITOR = 3
private const val ON_CHANGE = 41L
private const val ON_SELECTION = 40L
private const val ON_VISIBLE = 42L
private const val ON_FOCUS = 43L
private const val ON_FOLD = 44L
private const val ON_COMPOSITION = 45L
private const val ON_DECORATION = 46L
private const val ON_REJECTED = 47L
private const val ON_GROUP_RESULT = 48L

private fun p(line: Int, column: Int) = CodePosition(line, column)

/** An editor node with every listener the additions report to, drained of what creating it queued. */
private fun editorTable(text: String): NodeTable {
    val table = NodeTable()
    table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    for ((kind, handler) in listOf(
        PropertyKind.OnValueChange to ON_CHANGE,
        PropertyKind.OnSelectionChange to ON_SELECTION,
        PropertyKind.OnVisibleRangeChange to ON_VISIBLE,
        PropertyKind.OnEditorFocusChange to ON_FOCUS,
        PropertyKind.OnFoldChange to ON_FOLD,
        PropertyKind.OnCompositionChange to ON_COMPOSITION,
        PropertyKind.OnDecorationClick to ON_DECORATION,
        PropertyKind.OnEditRejected to ON_REJECTED,
        PropertyKind.OnEditGroupResult to ON_GROUP_RESULT,
    )) {
        table.apply(Mutation.SetProp(EDITOR, kind, PropertyValue.Integer(handler)))
    }
    table.drainEvents()
    table.drainErrors()
    return table
}

private fun NodeTable.model(): CodeEditorModel = node(EDITOR)!!.code!!

/** `lines` lines, `l0` to `l<lines - 1>`, with the last one unterminated. */
private fun lines(count: Int): String = (0 until count).joinToString("\n") { "l$it" }

/** Fold records as the Rust schema lays them out: version, first line, last line, collapsed. */
private fun foldBlob(vararg folds: IntArray): ByteArray {
    val buffer = ByteBuffer.allocate(folds.size * 16).order(ByteOrder.LITTLE_ENDIAN)
    for (fold in folds) fold.forEach(buffer::putInt)
    return buffer.array()
}

/** One gutter record: version, line, kind, colour role (0 for none), id, glyph, reserved. */
private fun gutterRecord(
    buffer: ByteBuffer,
    version: Int,
    line: Int,
    kind: Int,
    color: Int,
    id: Long,
    glyph: Int,
) {
    buffer.putInt(version)
    buffer.putInt(line)
    buffer.putShort(kind.toShort())
    buffer.putShort(color.toShort())
    buffer.putLong(id)
    buffer.putInt(glyph)
    buffer.putInt(0)
}

private fun gutterBlob(count: Int, fill: (ByteBuffer) -> Unit): ByteArray {
    val buffer = ByteBuffer.allocate(count * 28).order(ByteOrder.LITTLE_ENDIAN)
    fill(buffer)
    return buffer.array()
}

private fun editGroupBlob(edits: List<CodeEditRecord>): ByteArray {
    val textBytes = edits.map { it.text.toByteArray(Charsets.UTF_8) }
    val totalText = textBytes.sumOf { it.size }
    val buffer = ByteBuffer.allocate(4 + edits.size * 24 + totalText).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(edits.size)
    var textOffset = 0
    for ((index, edit) in edits.withIndex()) {
        val utf8 = textBytes[index]
        buffer.putInt(edit.startLine)
        buffer.putInt(edit.startColumn)
        buffer.putInt(edit.endLine)
        buffer.putInt(edit.endColumn)
        buffer.putInt(textOffset)
        buffer.putInt(utf8.size)
        textOffset += utf8.size
    }
    for (utf8 in textBytes) {
        buffer.put(utf8)
    }
    return buffer.array()
}

/**
 * The additions to the code editor's reports and commands: the selection, the visible lines,
 * focus, folds, the marks beside the line numbers and the composition signal. Everything here
 * goes through the node table, which is what the Host's batches go through.
 */
class CodeEditorEventsTest {
    /**
     * Criterion 9. Several moves before the frame ends are one report, of where the selection
     * ended up, and an edit's report follows its change, at the version the change produced.
     */
    @Test
    fun fr38_selection_change_reaches_the_host() {
        val table = editorTable("a😀b\nsecond\nthird")
        table.apply(Mutation.SetSelection(EDITOR, 0, 0, 1, 0, 3))
        table.apply(Mutation.SetSelection(EDITOR, 0, 1, 2, 1, 4))
        assertEquals(
            listOf(HostEvent.SelectionChanged(EDITOR, ON_SELECTION, 0, 1, 2, 1, 4)),
            table.drainEvents(),
        )
        // Nothing moved, so nothing is said.
        assertEquals(emptyList(), table.drainEvents())

        // A move that ends where it began is not a move.
        val model = table.model()
        model.selection = CodeSelection(p(0, 0), p(0, 1))
        model.selection = CodeSelection(p(1, 2), p(1, 4))
        assertEquals(emptyList(), table.drainEvents())

        // Typing in front of the selection moves it, and the Host reads the change first.
        model.edit(p(1, 0), p(1, 0), "ab", EditOrigin.Typing)
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 1, 0, 1, 0, "ab"),
                HostEvent.SelectionChanged(EDITOR, ON_SELECTION, 1, 1, 4, 1, 6),
            ),
            table.drainEvents(),
        )
        // Pasting several ranges in one frame reports one selection, at the last version.
        model.edit(p(0, 0), p(0, 0), "x\n", EditOrigin.Reader)
        model.edit(p(0, 0), p(0, 0), "y\n", EditOrigin.Reader)
        val events = table.drainEvents()
        assertEquals(listOf(2, 3), events.filterIsInstance<HostEvent.CodeChanged>().map { it.version })
        assertEquals(1, events.filterIsInstance<HostEvent.SelectionChanged>().size)
        assertTrue(events.last() is HostEvent.SelectionChanged)
    }

    /** Criterion 9: a column is never reported between the two halves of a surrogate pair. */
    @Test
    fun fr38_selection_change_never_points_inside_a_surrogate_pair() {
        val table = editorTable("a😀b")
        table.model().selection = CodeSelection(p(0, 2), p(0, 3))
        assertEquals(
            listOf(HostEvent.SelectionChanged(EDITOR, ON_SELECTION, 0, 0, 1, 0, 3)),
            table.drainEvents(),
        )
    }

    /**
     * Criterion 10. SetSelection puts the selection where it says, moved past what the reader
     * typed since the version it was written against, and a place outside the document drops
     * only that command.
     */
    @Test
    fun fr38_set_selection_and_reveal_range_apply() {
        val table = editorTable("first\nsecond\nthird")
        val model = table.model()
        model.edit(p(0, 0), p(0, 0), "zzz\n", EditOrigin.Reader)
        table.drainEvents()

        // Written at version 0, when "second" was the line after the first.
        table.apply(Mutation.SetSelection(EDITOR, 0, 1, 0, 1, 3))
        assertEquals(CodeSelection(p(2, 0), p(2, 3)), model.selection)
        assertEquals(1, model.hostSelection)

        // Outside the document, before it, and in the middle of a pair: dropped and reported.
        val kept = model.selection
        table.apply(Mutation.SetSelection(EDITOR, 1, 9, 0, 9, 1))
        table.apply(Mutation.SetSelection(EDITOR, 1, 0, 40, 0, 1))
        table.apply(Mutation.SetSelection(EDITOR, 5, 0, 0, 0, 1))
        assertEquals(kept, model.selection)
        assertEquals(1, model.hostSelection)
        assertEquals(List(3) { TableError.INVALID_CODE_EDIT }, table.drainErrors().map { it.code })

        // A reversed selection is a selection: the active end may come first.
        table.apply(Mutation.SetSelection(EDITOR, 1, 2, 5, 1, 1))
        assertEquals(CodeSelection(p(2, 5), p(1, 1)), model.selection)
        assertTrue(table.drainErrors().isEmpty())

        // RevealRange asks the surface to scroll, to the range moved along the same way.
        table.apply(Mutation.RevealRange(EDITOR, 0, 2, 0, 2, 5, RevealAlign.Center))
        val request = assertNotNull(model.reveal)
        assertEquals(p(3, 0), request.start)
        assertEquals(p(3, 5), request.end)
        assertEquals(RevealAlign.Center, request.align)
        model.revealDone(request)
        assertNull(model.reveal)

        // A range that starts after it ends, or lies outside the document, is dropped.
        table.apply(Mutation.RevealRange(EDITOR, 1, 2, 0, 1, 0, RevealAlign.Top))
        table.apply(Mutation.RevealRange(EDITOR, 1, 90, 0, 91, 0, RevealAlign.Nearest))
        assertNull(model.reveal)
        assertEquals(List(2) { TableError.INVALID_CODE_EDIT }, table.drainErrors().map { it.code })

        // A command for a node that is not an editor is a protocol error too.
        table.apply(Mutation.Create(9, WidgetKind.Box))
        table.apply(Mutation.SetFolded(9, 0, true))
        assertEquals(listOf(TableError.UNSUPPORTED_PROPERTY), table.drainErrors().map { it.code })
    }

    /** Criterion 10: revealing a line inside a folded stretch unfolds it, and the Host hears of it. */
    @Test
    fun fr38_reveal_range_unfolds_what_hides_it() {
        val table = editorTable(lines(10))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Folds, PropertyValue.Bytes(foldBlob(intArrayOf(0, 1, 4, 1)))))
        table.drainEvents()
        assertTrue(table.model().isHidden(3))
        table.apply(Mutation.RevealRange(EDITOR, 0, 3, 0, 3, 1, RevealAlign.Nearest))
        assertFalse(table.model().isHidden(3))
        assertEquals(listOf(HostEvent.FoldChanged(EDITOR, ON_FOLD, 1, false)), table.drainEvents())
    }

    /**
     * Criterion 11. The lines on screen are reported when they change and not otherwise, once
     * per frame at most, and a scroll through a hundred thousand lines costs a report a frame.
     */
    @Test
    fun fr38_visible_range_is_reported_once_per_change() {
        val table = editorTable(lines(100_000))
        val model = table.model()
        model.reportVisibleRange(0, 40)
        model.reportVisibleRange(0, 40)
        assertEquals(
            listOf(HostEvent.VisibleRangeChanged(EDITOR, ON_VISIBLE, 0, 0, 40)),
            table.drainEvents(),
        )
        // The same range again says nothing.
        model.reportVisibleRange(0, 40)
        assertEquals(emptyList(), table.drainEvents())
        // Two reports in one frame are the later one.
        model.reportVisibleRange(5, 45)
        model.reportVisibleRange(6, 46)
        assertEquals(
            listOf(HostEvent.VisibleRangeChanged(EDITOR, ON_VISIBLE, 0, 6, 46)),
            table.drainEvents(),
        )
        // A scroll down the whole file: a frame per step, one report for each, none for a still frame.
        var reports = 0
        var first = 6
        repeat(1_000) { frame ->
            first += 97
            model.reportVisibleRange(first, first + 40)
            model.reportVisibleRange(first, first + 40)
            val frameEvents = table.drainEvents()
            assertEquals(1, frameEvents.size, "frame $frame")
            reports += frameEvents.size
            assertEquals(emptyList(), table.drainEvents())
        }
        assertEquals(1_000, reports)
    }

    /** Criterion 12. Gaining and losing focus are one event each, and the same state is not said twice. */
    @Test
    fun fr38_editor_focus_changes_reach_the_host() {
        val table = editorTable("abc")
        val model = table.model()
        model.focusChanged(false)
        assertEquals(emptyList(), table.drainEvents())
        model.focusChanged(true)
        model.focusChanged(true)
        assertEquals(listOf(HostEvent.EditorFocusChanged(EDITOR, ON_FOCUS, true)), table.drainEvents())
        model.focusChanged(false)
        assertEquals(listOf(HostEvent.EditorFocusChanged(EDITOR, ON_FOCUS, false)), table.drainEvents())
    }

    /**
     * Criterion 13. A stretch the Host sent folded takes its lines out of the rows, the reader
     * folding and unfolding is reported, the Host folding is not, and a fold hides lines
     * without changing a single coordinate in the document.
     */
    @Test
    fun fr38_folded_ranges_hide_lines_and_report_toggles() {
        val table = editorTable(lines(10))
        val model = table.model()
        table.apply(
            Mutation.SetProp(
                EDITOR,
                PropertyKind.Folds,
                PropertyValue.Bytes(foldBlob(intArrayOf(0, 1, 4, 1), intArrayOf(0, 6, 8, 0))),
            ),
        )
        assertEquals(emptyList(), table.drainEvents())
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(listOf(2, 4), model.hiddenLines().toList())
        assertTrue(model.isHidden(2) && model.isHidden(4))
        assertFalse(model.isHidden(1) || model.isHidden(5))

        // Folded lines have no row: the lines after them move up.
        val rows = RowMap.of(model)
        assertEquals(7, rows.totalRows)
        assertEquals(2, rows.textRow(5))
        assertEquals(5, rows.lineAtRow(2))
        assertEquals(1, rows.lineAtRow(1))

        // The reader unfolds and folds; each is said once, with the line the stretch starts on.
        assertTrue(model.toggleFold(1))
        assertTrue(model.toggleFold(6))
        assertFalse(model.toggleFold(9))
        assertEquals(
            listOf(
                HostEvent.FoldChanged(EDITOR, ON_FOLD, 1, false),
                HostEvent.FoldChanged(EDITOR, ON_FOLD, 6, true),
            ),
            table.drainEvents(),
        )
        assertEquals(listOf(7, 8), model.hiddenLines().toList())

        // The Host folds and unfolds. It asked, so it is not told.
        table.apply(Mutation.SetFolded(EDITOR, 1, true))
        table.apply(Mutation.SetFolded(EDITOR, 6, false))
        assertEquals(emptyList(), table.drainEvents())
        assertEquals(listOf(2, 4), model.hiddenLines().toList())
        table.apply(Mutation.SetFolded(EDITOR, 5, true))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })

        // A selection inside what is folded goes to the end of the line that stays.
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Folds, PropertyValue.Bytes(foldBlob(intArrayOf(0, 1, 4, 0)))))
        model.selection = CodeSelection(p(3, 1), p(3, 1))
        table.apply(Mutation.SetFolded(EDITOR, 1, true))
        assertEquals(CodeSelection(p(1, 2), p(1, 2)), model.selection)
    }

    /** Criterion 13: edits, selections and decorations keep their document coordinates under a fold. */
    @Test
    fun fr38_folded_lines_keep_their_document_coordinates() {
        val table = editorTable(lines(10))
        val model = table.model()
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Folds, PropertyValue.Bytes(foldBlob(intArrayOf(0, 2, 6, 1)))))
        // The Host edits a line inside the fold, by its document coordinates.
        table.apply(Mutation.EditCode(EDITOR, 1, 0, 4, 2, 4, 2, "!"))
        assertEquals("l4!", model.document.line(4))
        // The fold is still the same stretch, and a line typed above it moves it down.
        assertEquals(listOf(3, 6), model.hiddenLines().toList())
        model.edit(p(0, 0), p(0, 0), "new\n", EditOrigin.Reader)
        assertEquals(listOf(4, 7), model.hiddenLines().toList())
        assertEquals(3, model.folds.single().startLine)
        assertEquals(7, model.folds.single().endLine)
        // Deleting the whole stretch takes the fold with it.
        model.edit(p(3, 0), p(8, 0), "", EditOrigin.Reader)
        assertTrue(model.folds.isEmpty())
        assertEquals(0, model.hiddenLines().size)
    }

    /** A fold that hides nothing, or a record that is not 0 or 1, is reported and the rest are kept. */
    @Test
    fun fr38_an_unreadable_fold_is_a_protocol_error_and_the_rest_are_kept() {
        val table = editorTable(lines(10))
        val blob = foldBlob(
            intArrayOf(0, 3, 3, 0),
            intArrayOf(0, 1, 2, 2),
            intArrayOf(0, 5, 20, 0),
            intArrayOf(0, 6, 8, 1),
        )
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Folds, PropertyValue.Bytes(blob)))
        assertEquals(listOf(6), table.model().folds.map { it.startLine })
        assertEquals(List(3) { TableError.INVALID_CODE_EDIT }, table.drainErrors().map { it.code })
    }

    /**
     * Criterion 14. A mark beside a line number follows its line through edits and goes with it
     * when the line is deleted, and pressing it reports the id the application gave it, once.
     */
    @Test
    fun fr38_gutter_decorations_follow_edits_and_activate() {
        val table = editorTable(lines(10))
        val model = table.model()
        val blob = gutterBlob(3) { buffer ->
            gutterRecord(buffer, 0, 4, 1, 0, 90L, 0)
            gutterRecord(buffer, 0, 6, 3, 0, 91L, 0x1F41B)
            gutterRecord(buffer, 0, 8, 2, 0, 0L, 0)
        }
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.GutterDecorations, PropertyValue.Bytes(blob)))
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(listOf(4, 6, 8), model.gutterMarks.map { it.line })
        assertEquals(
            listOf(GutterDecorationKind.Dot, GutterDecorationKind.Glyph, GutterDecorationKind.Bar),
            model.gutterMarks.map { it.kind },
        )

        // A line typed above moves every mark down by one.
        model.edit(p(0, 0), p(0, 0), "new\n", EditOrigin.Reader)
        assertEquals(listOf(5, 7, 9), model.gutterMarks.map { it.line })
        // Deleting the line a mark is on takes the mark; the others close up.
        model.edit(p(5, 0), p(6, 0), "", EditOrigin.Reader)
        assertEquals(listOf(6, 8), model.gutterMarks.map { it.line })
        table.drainEvents()

        // Pressing the mark column on a line with a mark reports its id; one with none, or
        // with an id of zero, reports nothing.
        val rows = RowMap.of(model)
        val rowPx = 20
        val press = { line: Int ->
            pressGutter(model, rows, Offset(5f, line * rowPx + 3f), 0, rowPx, 100, rowPx, 0)
            table.drainEvents()
        }
        assertEquals(listOf(HostEvent.DecorationActivated(EDITOR, ON_DECORATION, 91L)), press(6))
        assertEquals(emptyList(), press(8))
        assertEquals(emptyList(), press(2))
        // A press outside the mark column is not a press on a mark.
        pressGutter(model, rows, Offset(60f, 6 * rowPx + 3f), 0, rowPx, 100, rowPx, 0)
        assertEquals(emptyList(), table.drainEvents())
    }

    /** Criterion 13: pressing the fold marker folds the stretch, and says so. */
    @Test
    fun fr38_pressing_the_fold_marker_toggles_the_fold() {
        val table = editorTable(lines(10))
        val model = table.model()
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Folds, PropertyValue.Bytes(foldBlob(intArrayOf(0, 1, 4, 0)))))
        val rows = RowMap.of(model)
        val rowPx = 20
        pressGutter(model, rows, Offset(90f, 1 * rowPx + 3f), 0, rowPx, 100, 0, rowPx)
        assertEquals(listOf(HostEvent.FoldChanged(EDITOR, ON_FOLD, 1, true)), table.drainEvents())
        assertEquals(listOf(2, 4), model.hiddenLines().toList())
        // A line with no fold on it does nothing.
        pressGutter(model, rows, Offset(90f, 0 * rowPx + 3f), 0, rowPx, 100, 0, rowPx)
        assertEquals(emptyList(), table.drainEvents())
    }

    /** A gutter record of a kind this side does not know is reported and left out. */
    @Test
    fun fr38_an_unreadable_gutter_record_is_a_protocol_error_and_the_rest_are_kept() {
        val table = editorTable(lines(4))
        val blob = gutterBlob(3) { buffer ->
            gutterRecord(buffer, 0, 1, 1, 0, 5L, 0)
            gutterRecord(buffer, 0, 2, 9, 0, 6L, 0)
            gutterRecord(buffer, 0, 3, 3, 0, 7L, 0xD800)
        }
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.GutterDecorations, PropertyValue.Bytes(blob)))
        assertEquals(listOf(1), table.model().gutterMarks.map { it.line })
        assertEquals(List(2) { TableError.INVALID_CODE_EDIT }, table.drainErrors().map { it.code })
    }

    /**
     * Criterion 15. The composition signal is a start and an end and carries no letters: the
     * Host hears no change while it lasts, and the committed text arrives as an ordinary change.
     */
    @Test
    fun fr38_composition_signal_carries_no_text() {
        val table = editorTable("abc")
        val model = table.model()
        model.compositionChanged(true)
        model.compositionChanged(true)
        val started = table.drainEvents()
        assertEquals(listOf(HostEvent.CompositionChanged(EDITOR, ON_COMPOSITION, true)), started)
        // While composing nothing but the signal can have gone: no change event is out.
        assertTrue(started.none { it is HostEvent.CodeChanged })
        // The event has room for a flag and padding and nowhere for letters.
        val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, Protocol.encodeEvent(started.single(), buffer))

        // The syllable is committed and the composition ends: the change, then the end.
        model.edit(p(0, 3), p(0, 3), "한", EditOrigin.Typing)
        model.compositionChanged(false)
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 0, 3, 0, 3, "한"),
                HostEvent.CompositionChanged(EDITOR, ON_COMPOSITION, false),
            ),
            table.drainEvents(),
        )
    }

    /** The new properties belong to a code editor and to nothing else. */
    @Test
    fun fr38_an_editor_keeps_the_new_properties_and_other_widgets_do_not() {
        val properties = listOf(
            PropertyKind.Folds,
            PropertyKind.GutterDecorations,
            PropertyKind.OnSelectionChange,
            PropertyKind.OnVisibleRangeChange,
            PropertyKind.OnEditorFocusChange,
            PropertyKind.OnFoldChange,
            PropertyKind.OnCompositionChange,
            PropertyKind.OnEditGroupResult,
        )
        for (property in properties) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, property), "$property")
            assertFalse(NodeTable.supportsProperty(WidgetKind.Text, property), "$property")
        }
    }

    /** The record decoders read the offsets the Rust schema writes, and report what they cannot read. */
    @Test
    fun fr38_fold_and_gutter_decoders_report_the_records_they_cannot_read() {
        val errors = mutableListOf<String>()
        val folds = CodeEditorRecords.decodeFolds(foldBlob(intArrayOf(3, 1, 4, 1), intArrayOf(3, 2, 5, 7)), errors::add)
        assertEquals(listOf(Triple(1, 4, true)), folds.map { Triple(it.startLine, it.endLine, it.collapsed) })
        assertEquals(1, errors.size)
        errors.clear()
        val marks = CodeEditorRecords.decodeGutterDecorations(
            gutterBlob(2) { buffer ->
                gutterRecord(buffer, 1, 2, 1, 0, 3L, 0)
                gutterRecord(buffer, 1, 3, 1, 999, 4L, 0)
            },
            errors::add,
        )
        assertEquals(listOf(2), marks.map { it.line })
        assertEquals(1, errors.size)
    }

    /**
     * Criterion 17.1: Three non-overlapping edits sent in one group at the current
     * baseVersion all apply and produce one `CodeEditGroupResult(Applied)`.
     */
    @Test
    fun fr38_edit_group_applies_all_edits_and_replies_once() {
        val table = editorTable("alpha\nbeta\ngamma")
        val edits = listOf(
            CodeEditRecord(0, 0, 0, 5, "ALPHA"),
            CodeEditRecord(1, 0, 1, 4, "BETA"),
            CodeEditRecord(2, 0, 2, 5, "GAMMA"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 17, 0, editGroupBlob(edits)))
        assertEquals("ALPHA\nBETA\nGAMMA", table.model().document.text())
        assertEquals(3, table.model().document.version)
        assertTrue(table.drainErrors().isEmpty())
        val events = table.drainEvents()
        val results = events.filterIsInstance<HostEvent.CodeEditGroupResult>()
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 17, EditGroupStatus.Applied, 0, 3, 3)),
            results,
        )
    }

    /**
     * Criterion 17.2: Each edit in the group emits one `CodeChanged` from the back of the
     * document towards the front with versions incrementing by 1, replaying them on a copy
     * yields the Renderer buffer, and `CodeEditGroupResult` comes last.
     */
    @Test
    fun fr38_edit_group_events_are_ordered_and_replayable() {
        val initial = "fn main() {\n    let a = 1;\n    let b = 2;\n}"
        val table = editorTable(initial)
        // Sent in forward order; applied and reported from back to front.
        val edits = listOf(
            CodeEditRecord(0, 0, 0, 2, "pub fn"),
            CodeEditRecord(1, 8, 1, 9, "first"),
            CodeEditRecord(2, 8, 2, 9, "second"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 21, 0, editGroupBlob(edits)))
        val events = table.drainEvents()
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 2, 8, 2, 9, "second"),
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 2, 1, 8, 1, 9, "first"),
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 3, 0, 0, 0, 2, "pub fn"),
                HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 21, EditGroupStatus.Applied, 0, 3, 3),
            ),
            events,
        )
        val mirror = dev.darkpyonix.composerust.foundation.code.CodeDocument(initial)
        for (change in events.filterIsInstance<HostEvent.CodeChanged>()) {
            mirror.replace(p(change.startLine, change.startColumn), p(change.endLine, change.endColumn), change.text)
            assertEquals(change.version, mirror.version)
        }
        assertEquals(table.model().document.text(), mirror.text())
    }

    /**
     * Criterion 17.3: A group is undone in one step and redone in one step, and is never
     * merged with the reader's preceding typing step.
     */
    @Test
    fun fr38_edit_group_is_one_undo_step() {
        val table = editorTable("one\ntwo\nthree")
        val model = table.model()
        // Reader types a character just before the group arrives.
        model.edit(p(0, 3), p(0, 3), "!", EditOrigin.Typing)
        table.drainEvents()
        assertEquals("one!\ntwo\nthree", model.document.text())

        val edits = listOf(
            CodeEditRecord(1, 0, 1, 3, "TWO"),
            CodeEditRecord(2, 0, 2, 5, "THREE"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 30, 1, editGroupBlob(edits)))
        table.drainEvents()
        assertEquals("one!\nTWO\nTHREE", model.document.text())

        // One undo reverts the whole group, leaving the reader's typed "!" in place.
        assertTrue(model.undo())
        assertEquals("one!\ntwo\nthree", model.document.text())

        // One redo brings the whole group back.
        assertTrue(model.redo())
        assertEquals("one!\nTWO\nTHREE", model.document.text())

        // Undoing twice reverts the group and then the reader's typing.
        assertTrue(model.undo())
        assertTrue(model.undo())
        assertEquals("one\ntwo\nthree", model.document.text())
    }

    /**
     * Criterion 17.4: Overlapping edits in the same group (including two insertions at the
     * same position) leave the document untouched, emit `Invalid` once naming the later edit
     * index, and emit no `ProtocolError`.
     */
    @Test
    fun fr38_edit_group_with_overlapping_edits_changes_nothing() {
        val table = editorTable("abcdef\nghijkl")
        val overlappingRanges = listOf(
            CodeEditRecord(0, 0, 0, 1, "A"),
            CodeEditRecord(0, 2, 0, 5, "CDE"),
            CodeEditRecord(0, 4, 0, 6, "EF"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 40, 0, editGroupBlob(overlappingRanges)))
        assertEquals("abcdef\nghijkl", table.model().document.text())
        assertEquals(0, table.model().document.version)
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 40, EditGroupStatus.Invalid, 0, 0, 2)),
            table.drainEvents(),
        )

        // Two insertions at the exact same position in one group also overlap because their
        // order is undefined.
        val samePointInsertions = listOf(
            CodeEditRecord(1, 2, 1, 2, "X"),
            CodeEditRecord(1, 2, 1, 2, "Y"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 41, 0, editGroupBlob(samePointInsertions)))
        assertEquals("abcdef\nghijkl", table.model().document.text())
        assertEquals(0, table.model().document.version)
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 41, EditGroupStatus.Invalid, 0, 0, 1)),
            table.drainEvents(),
        )
    }

    /**
     * Criterion 17.5: If any edit in the group has an out-of-bounds or reversed range, none
     * of the edits apply, `Invalid` names that edit's index, and no `ProtocolError` is sent.
     */
    @Test
    fun fr38_edit_group_with_a_bad_range_changes_nothing() {
        val table = editorTable("first\nsecond")
        val outOfBounds = listOf(
            CodeEditRecord(0, 0, 0, 5, "FIRST"),
            CodeEditRecord(9, 0, 9, 1, "nope"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 50, 0, editGroupBlob(outOfBounds)))
        assertEquals("first\nsecond", table.model().document.text())
        assertEquals(0, table.model().document.version)
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 50, EditGroupStatus.Invalid, 0, 0, 1)),
            table.drainEvents(),
        )

        val reversed = listOf(
            CodeEditRecord(0, 4, 0, 2, "bad"),
            CodeEditRecord(1, 0, 1, 6, "SECOND"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 51, 0, editGroupBlob(reversed)))
        assertEquals("first\nsecond", table.model().document.text())
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 51, EditGroupStatus.Invalid, 0, 0, 0)),
            table.drainEvents(),
        )
    }

    /**
     * Criterion 17.6: A group written against an older `baseVersion` rebases all of its
     * ranges and applies when none overlap the reader's typing, or rejects the whole group
     * with `Rejected` when even one edit overlaps.
     */
    @Test
    fun fr38_edit_group_with_a_stale_base_moves_or_rejects_all() {
        val table = editorTable("line0\nline1\nline2")
        val model = table.model()
        // Reader inserts a new line at the very top (version 0 -> 1).
        model.edit(p(0, 0), p(0, 0), "header\n", EditOrigin.Reader)
        table.drainEvents()
        assertEquals(1, model.document.version)

        // Group written against version 0 touches old lines 1 and 2 (now lines 2 and 3).
        val nonConflicting = listOf(
            CodeEditRecord(1, 0, 1, 5, "LINE1"),
            CodeEditRecord(2, 0, 2, 5, "LINE2"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 60, 0, editGroupBlob(nonConflicting)))
        assertEquals("header\nline0\nLINE1\nLINE2", model.document.text())
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 2, 3, 0, 3, 5, "LINE2"),
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 3, 2, 0, 2, 5, "LINE1"),
                HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 60, EditGroupStatus.Applied, 0, 3, 2),
            ),
            table.drainEvents(),
        )

        // Now reader edits inside line 2 ("LINE1" -> "LINE_ONE", version 3 -> 4).
        model.edit(p(2, 4), p(2, 5), "_ONE", EditOrigin.Reader)
        table.drainEvents()
        assertEquals(4, model.document.version)

        // Group written against version 3 edits line 1 (no conflict) and line 2 (conflicts!).
        val conflicting = listOf(
            CodeEditRecord(1, 0, 1, 5, "LINE0"),
            CodeEditRecord(2, 0, 2, 5, "CONFLICT"),
        )
        table.apply(Mutation.EditCodeGroup(EDITOR, 61, 3, editGroupBlob(conflicting)))
        // Atomicity: line 1 must still be "line0", not "LINE0".
        assertEquals("header\nline0\nLINE_ONE\nLINE2", model.document.text())
        assertEquals(4, model.document.version)
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 61, EditGroupStatus.Rejected, 3, 4, 1)),
            table.drainEvents(),
        )
    }

    /**
     * Criterion 17.7: A `baseVersion` older than the document's history window is refused
     * with `TooOld` (distinct from `Rejected`), leaving the document unchanged.
     */
    @Test
    fun fr38_edit_group_older_than_the_history_is_too_old() {
        val table = editorTable("a")
        val model = table.model()
        repeat( dev.darkpyonix.composerust.foundation.code.CodeDocument.HISTORY_LIMIT + 5 ) {
            model.edit(p(0, 0), p(0, 1), "b", EditOrigin.Reader)
        }
        table.drainEvents()
        val currentVersion = model.document.version
        assertTrue(model.document.oldestVersion > 0)

        val edits = listOf(CodeEditRecord(0, 0, 0, 1, "z"))
        table.apply(Mutation.EditCodeGroup(EDITOR, 70, 0, editGroupBlob(edits)))
        assertEquals("b", model.document.text())
        assertEquals(currentVersion, model.document.version)
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(
                HostEvent.CodeEditGroupResult(
                    EDITOR,
                    ON_GROUP_RESULT,
                    70,
                    EditGroupStatus.TooOld,
                    0,
                    currentVersion,
                    0,
                ),
            ),
            table.drainEvents(),
        )
    }

    /**
     * Criterion 17.8: `EditCode` keeps its existing single-edit behaviour: individual
     * rejection via `CodeEditRejected` and one undo step per applied edit.
     */
    @Test
    fun fr38_edit_code_sequence_keeps_its_behaviour() {
        val table = editorTable("aaa\nbbb")
        val model = table.model()
        table.apply(Mutation.EditCode(EDITOR, 80, 0, 0, 0, 0, 3, "AAA"))
        table.apply(Mutation.EditCode(EDITOR, 81, 1, 1, 0, 1, 3, "BBB"))
        assertEquals("AAA\nBBB", model.document.text())
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 0, 0, 0, 3, "AAA"),
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 2, 1, 0, 1, 3, "BBB"),
            ),
            table.drainEvents(),
        )
        // Each EditCode is its own undo step.
        assertTrue(model.undo())
        assertEquals("AAA\nbbb", model.document.text())
        assertTrue(model.undo())
        assertEquals("aaa\nbbb", model.document.text())
        table.drainEvents()

        // Reader edits line 0; a stale EditCode on line 0 emits CodeEditRejected.
        val beforeConflict = model.document.version
        model.edit(p(0, 0), p(0, 3), "reader", EditOrigin.Reader)
        table.drainEvents()
        table.apply(Mutation.EditCode(EDITOR, 82, beforeConflict, 0, 0, 0, 3, "host"))
        assertEquals(
            listOf(
                HostEvent.CodeEditRejected(
                    EDITOR,
                    ON_REJECTED,
                    82,
                    beforeConflict,
                    model.document.version,
                    0,
                    0,
                    0,
                    3,
                ),
            ),
            table.drainEvents(),
        )
    }

    /**
     * Criterion 17.11: An `EditCodeGroup` with zero edits that reaches the Renderer leaves
     * the document, version, and undo stack untouched, emits zero `CodeChanged` events, and
     * replies `Applied` with both `baseVersion` and `currentVersion` set to the document's
     * current version.
     */
    @Test
    fun fr38_an_empty_group_changes_nothing_and_replies_applied() {
        val table = editorTable("hello")
        val model = table.model()
        model.edit(p(0, 5), p(0, 5), "!", EditOrigin.Reader)
        table.drainEvents()
        assertEquals(1, model.document.version)

        // Send an empty group with a bogus baseVersion (99); it must not inspect baseVersion
        // and must reply Applied with baseVersion = currentVersion = 1.
        table.apply(Mutation.EditCodeGroup(EDITOR, 110, 99, editGroupBlob(emptyList())))
        assertEquals("hello!", model.document.text())
        assertEquals(1, model.document.version)
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 110, EditGroupStatus.Applied, 1, 1, 0)),
            table.drainEvents(),
        )
        // Undo still undoes the reader's "!" in one step; no empty step was pushed.
        assertTrue(model.undo())
        assertEquals("hello", model.document.text())
        assertFalse(model.canUndo)
    }

    /**
     * Criterion 17.12: A malformed `EditCodeGroup` record or blob leaves the document
     * untouched, emits one `ProtocolError`, and when the header (`nodeId` and `requestId`)
     * is readable, follows it with `Invalid` for that `requestId`; semantic errors (criteria
     * 4 and 5) emit no `ProtocolError`.
     */
    @Test
    fun fr38_a_broken_group_record_is_a_protocol_error_and_a_semantic_error_is_not() {
        val table = editorTable("intact")
        // 1. Truncated blob inside a valid 24-byte record: count says 2, but no records follow.
        val truncatedBlob = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(2).array()
        table.apply(Mutation.EditCodeGroup(EDITOR, 120, 0, truncatedBlob))
        assertEquals("intact", table.model().document.text())
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 120, EditGroupStatus.Invalid, 0, 0, 0)),
            table.drainEvents(),
        )

        // 2. Wire record with wrong length (16 instead of 24), but readable nodeId and requestId.
        val badLengthBatch = ByteBuffer.allocate(12 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0)
            putShort(12)
            putInt(12 + 16)
            putInt(1)
            putShort(28)
            putShort(16)
            putInt(EDITOR)
            putInt(121)
            putInt(0)
            position(0)
        }
        val decodedMutations = mutableListOf<Mutation>()
        val decodeFailure = runCatching { Protocol.decode(badLengthBatch, decodedMutations::add) }.exceptionOrNull()
        assertTrue(decodeFailure is ProtocolException)
        assertEquals(1, decodedMutations.size)
        table.apply(decodedMutations.single())
        assertEquals("intact", table.model().document.text())
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(
            listOf(HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP_RESULT, 121, EditGroupStatus.Invalid, 0, 0, 0)),
            table.drainEvents(),
        )

        // 3. Wire record with length < 12 (header unreadable): throws ProtocolException and
        // emits no mutation.
        val unreadableHeaderBatch = ByteBuffer.allocate(12 + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0)
            putShort(12)
            putInt(12 + 8)
            putInt(1)
            putShort(28)
            putShort(8)
            putInt(EDITOR)
            position(0)
        }
        val noneDecoded = mutableListOf<Mutation>()
        val headerFailure = runCatching { Protocol.decode(unreadableHeaderBatch, noneDecoded::add) }.exceptionOrNull()
        assertTrue(headerFailure is ProtocolException)
        assertTrue(noneDecoded.isEmpty())
    }
}
