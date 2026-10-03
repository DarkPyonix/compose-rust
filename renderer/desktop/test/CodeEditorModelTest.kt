package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.code.CodeDocument
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.CodeSelection
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.EditorOutput
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.DecorationRecord
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Severity
import dev.darkpyonix.composerust.protocol.SyntaxSpanRecord
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun p(line: Int, column: Int) = CodePosition(line, column)

/** Where a language server would put a position in a string: `\n`, `\r\n` and `\r` end lines. */
private fun offsetOf(text: String, position: CodePosition): Int {
    var line = 0
    var index = 0
    while (line < position.line) {
        val character = text[index]
        if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            index += 2
            line += 1
        } else if (character == '\n' || character == '\r') {
            index += 1
            line += 1
        } else {
            index += 1
        }
    }
    return index + position.column
}

/** Applies a change the way a Host keeping its own copy does. */
private fun String.applying(change: HostEvent.CodeChanged): String {
    val start = offsetOf(this, p(change.startLine, change.startColumn))
    val end = offsetOf(this, p(change.endLine, change.endColumn))
    return substring(0, start) + change.text + substring(end)
}

private fun EditorOutput.asEvent(): HostEvent.CodeChanged {
    val change = (this as EditorOutput.Changed).change
    return HostEvent.CodeChanged(
        1,
        1L,
        change.version,
        change.start.line,
        change.start.column,
        change.end.line,
        change.end.column,
        change.text,
    )
}

private fun decoration(
    kind: DecorationKind,
    version: Int,
    startLine: Int,
    startColumn: Int,
    endLine: Int,
    endColumn: Int,
    id: Long = 0,
    text: String = "",
    severity: Severity? = if (kind == DecorationKind.Underline) Severity.Error else null,
) = DecorationRecord(version, kind, severity, null, startLine, startColumn, endLine, endColumn, id, text)

/**
 * The document a code editor holds, with nothing drawn: versions, the changes the Host is
 * told about, edits from the Host written against old versions, and decorations that follow
 * the reader's typing. Everything here is the Renderer's half of the agreement, checked
 * without a window.
 */
class CodeEditorModelTest {

    /**
     * Criterion 2. After typing, pasting, undoing, redoing and deleting across lines, with
     * emoji and every kind of line break in the way, replaying the reported changes in order
     * onto version 0 gives exactly the editor's buffer.
     */
    @Test
    fun fr38_replaying_every_change_onto_version_zero_gives_the_buffer() {
        val opened = "fn main() {\r\n    let face = \"😀🙂\";\r    println!(\"{face}\");\n}\n"
        val model = CodeEditorModel(opened)
        val random = Random(38)
        val pieces = listOf("a", "한", "😀", "\n", "    ", "x\r\ny", "}\n{", "\t", "", "🙂🙂")
        val events = mutableListOf<HostEvent.CodeChanged>()
        repeat(400) { step ->
            val document = model.document
            fun anywhere(): CodePosition {
                val line = random.nextInt(document.lineCount)
                var column = random.nextInt(document.line(line).length + 1)
                if (!document.isValid(p(line, column))) column -= 1
                return p(line, column)
            }
            when (random.nextInt(10)) {
                in 0..4 -> {
                    val at = anywhere()
                    model.edit(at, at, pieces[random.nextInt(pieces.size)], EditOrigin.Typing)
                }
                5, 6 -> {
                    val one = anywhere()
                    val two = anywhere()
                    model.edit(minOf(one, two), maxOf(one, two), pieces[random.nextInt(pieces.size)])
                }
                7 -> model.undo()
                8 -> model.redo()
                else -> {
                    // A paste of several lines over a selection.
                    val one = anywhere()
                    val two = anywhere()
                    model.edit(minOf(one, two), maxOf(one, two), "pasted 😀\r\nblock $step\n")
                }
            }
            model.drainOutput().filterIsInstance<EditorOutput.Changed>().forEach { events += it.asEvent() }
        }
        var replayed = opened
        var version = 0
        for (event in events) {
            assertEquals(version + 1, event.version, "versions go up by one per change")
            version = event.version
            replayed = replayed.applying(event)
        }
        assertEquals(model.document.text(), replayed)
        assertEquals(model.document.version, version)
    }

    /** Each committed change adds one to the version, and a new document starts again at 0. */
    @Test
    fun fr38_every_change_adds_one_and_a_new_document_starts_at_version_zero() {
        val model = CodeEditorModel("one\ntwo")
        model.edit(p(0, 3), p(0, 3), "!")
        model.edit(p(1, 0), p(1, 3), "2")
        assertEquals(2, model.document.version)
        assertEquals(
            listOf(1, 2),
            model.drainOutput().map { (it as EditorOutput.Changed).change.version },
        )
        assertTrue(model.setHostText("another file"))
        assertEquals(0, model.document.version)
        assertEquals("another file", model.document.text())
        assertFalse(model.canUndo)
    }

    /**
     * Text equal to the buffer is nothing new. An application that writes every change back
     * into the text it sends must not lose the reader's place or their history by doing so.
     */
    @Test
    fun fr38_text_equal_to_the_buffer_changes_nothing() {
        val model = CodeEditorModel("let a = 1;\n")
        model.edit(p(0, 5), p(0, 5), " ", EditOrigin.Typing)
        model.selection = CodeSelection.at(p(0, 6))
        model.drainOutput()
        assertFalse(model.setHostText("let a  = 1;\n"))
        assertEquals(1, model.document.version)
        assertEquals(p(0, 6), model.selection.caret)
        assertTrue(model.canUndo)
        assertEquals(emptyList(), model.drainOutput())
    }

    /** The line breaks a document was written with are the ones it is read back with. */
    @Test
    fun fr38_line_breaks_are_kept_as_written() {
        val text = "a\r\nb\rc\n\r\nd"
        val document = CodeDocument(text)
        assertEquals(6, document.lineCount)
        assertEquals(text, document.text())
        assertTrue(document.contentEquals(text))
        assertFalse(document.contentEquals("a\nb\rc\n\r\nd"))
        // A column between the two halves of an emoji is not a place.
        val emoji = CodeDocument("😀")
        assertFalse(emoji.isValid(p(0, 1)))
        assertTrue(emoji.isValid(p(0, 2)))
    }

    /**
     * Criterion 3, the first half. The Host formats against version 0 while the reader types
     * two lines above: the edit is moved down past what they typed and lands on the text it
     * was written for, and comes back as an ordinary change.
     */
    @Test
    fun fr38_a_stale_host_edit_is_moved_past_what_the_reader_typed() {
        val model = CodeEditorModel("fn a() {}\nfn b() {}\nlet  x = 1;\n")
        model.edit(p(0, 0), p(0, 0), "// first\n// second\n")
        model.edit(p(4, 0), p(4, 0), "  ", EditOrigin.Typing)
        model.drainOutput()
        // Against version 0: collapse the double space on what was line 2.
        model.hostEdit(7, 0, 2, 3, 2, 5, " ")
        assertEquals("// first\n// second\nfn a() {}\nfn b() {}\n  let x = 1;\n", model.document.text())
        val output = model.drainOutput()
        assertEquals(1, output.size)
        val change = (output.single() as EditorOutput.Changed).change
        assertEquals(3, change.version)
        assertEquals(p(4, 5), change.start)
        assertEquals(p(4, 7), change.end)
    }

    /**
     * Criterion 3, the second half. Where the reader changed the same place since, the Host's
     * edit is refused and the Host is told, with the request id and the range as it sent it.
     */
    @Test
    fun fr38_a_stale_host_edit_over_what_the_reader_changed_is_rejected() {
        val model = CodeEditorModel("let value = compute();\n")
        model.edit(p(0, 12), p(0, 19), "recompute", EditOrigin.Reader)
        model.drainOutput()
        model.hostEdit(42, 0, 0, 4, 0, 21, "x = 0")
        assertEquals("let value = recompute();\n", model.document.text())
        val rejected = model.drainOutput().single() as EditorOutput.Rejected
        assertEquals(42, rejected.requestId)
        assertEquals(0, rejected.baseVersion)
        assertEquals(1, rejected.currentVersion)
        assertEquals(p(0, 4), rejected.start)
        assertEquals(p(0, 21), rejected.end)
        // Touching the reader's change at an edge is not overlapping it.
        model.hostEdit(43, 0, 0, 0, 0, 3, "var")
        assertEquals("var value = recompute();\n", model.document.text())
    }

    /** A Host edit against a version the document never had is a protocol error, not a guess. */
    @Test
    fun fr38_a_host_edit_against_a_future_version_or_outside_the_document_is_an_error() {
        val model = CodeEditorModel("short")
        model.hostEdit(1, 3, 0, 0, 0, 1, "x")
        model.hostEdit(2, 0, 4, 0, 4, 0, "x")
        model.hostEdit(3, 0, 0, 3, 0, 1, "x")
        val output = model.drainOutput()
        assertEquals(3, output.size)
        assertTrue(output.all { it is EditorOutput.Error })
        assertEquals("short", model.document.text())
    }

    /**
     * Criterion 4. An underline sent for version n moves down when a line is inserted above
     * it and goes away when its text is deleted, with nothing sent to the Host but the
     * reader's own changes.
     */
    @Test
    fun fr38_an_underline_follows_the_text_and_goes_with_it() {
        val model = CodeEditorModel("let a = 1;\nlet b = oops;\n")
        model.setDecorations(listOf(decoration(DecorationKind.Underline, 0, 1, 8, 1, 12)))
        model.edit(p(0, 0), p(0, 0), "// a comment\n")
        val underline = model.decorations.single()
        assertEquals(p(2, 8), underline.start)
        assertEquals(p(2, 12), underline.end)
        // Typing right after it does not stretch it.
        model.edit(p(2, 12), p(2, 12), "s", EditOrigin.Typing)
        assertEquals(p(2, 12), model.decorations.single().end)
        model.edit(p(2, 4), p(2, 13), "")
        assertTrue(model.decorations.isEmpty())
        assertTrue(model.drainOutput().all { it is EditorOutput.Changed })
    }

    /**
     * Decorations and colour runs written against a version the reader has since left land
     * on the text they were computed for.
     */
    @Test
    fun fr38_lists_written_against_an_older_version_land_on_their_text() {
        val model = CodeEditorModel("fn main() {}\n")
        model.edit(p(0, 0), p(0, 0), "\n\n")
        model.setDecorations(listOf(decoration(DecorationKind.HoverAnchor, 0, 0, 3, 0, 7, id = 5)))
        model.setSyntaxSpans(listOf(SyntaxSpanRecord(0, 0, 0, 0, 2, Paint.Role(ColorRole.Primary))))
        assertEquals(p(2, 3), model.decorations.single().start)
        assertEquals(p(2, 0), model.spans.single().start)
        assertEquals(p(2, 2), model.spans.single().end)
        assertEquals(5L, model.hoverAnchorAt(p(2, 5))?.id)
        assertNull(model.hoverAnchorAt(p(2, 8)))
    }

    /**
     * A range that starts after it ends, or that is outside the document, or a version the
     * document never had: that item is dropped and reported, and the rest of the list is kept.
     */
    @Test
    fun fr38_a_bad_record_is_dropped_and_reported_and_the_rest_are_kept() {
        val model = CodeEditorModel("one\ntwo\n")
        model.setDecorations(
            listOf(
                decoration(DecorationKind.Underline, 0, 1, 2, 1, 0),
                decoration(DecorationKind.Underline, 0, 9, 0, 9, 1),
                decoration(DecorationKind.Underline, 4, 0, 0, 0, 1),
                decoration(DecorationKind.Underline, 0, 0, 0, 0, 3),
            ),
        )
        assertEquals(1, model.decorations.size)
        assertEquals(3, model.drainOutput().count { it is EditorOutput.Error })
    }

    /**
     * Criterion 5, the suggestion. Accepting it puts its text in as an ordinary edit, which is
     * reported first, and then reports the decoration's id, in that order.
     */
    @Test
    fun fr38_accepting_a_suggestion_reports_the_change_and_then_the_activation() {
        val model = CodeEditorModel("let x = \n")
        model.setDecorations(listOf(decoration(DecorationKind.GhostText, 0, 0, 8, 0, 8, id = 77, text = "42;")))
        model.selection = CodeSelection.at(p(0, 8))
        assertTrue(model.acceptGhostText())
        assertEquals("let x = 42;\n", model.document.text())
        val output = model.drainOutput()
        assertEquals(2, output.size)
        assertEquals("42;", (output[0] as EditorOutput.Changed).change.text)
        assertEquals(77L, (output[1] as EditorOutput.Activated).id)
        assertNull(model.ghostText)
        assertEquals(p(0, 11), model.selection.caret)
    }

    /** Any other edit takes a suggestion away without accepting it. */
    @Test
    fun fr38_typing_something_else_takes_a_suggestion_away() {
        val model = CodeEditorModel("x\n")
        model.setDecorations(listOf(decoration(DecorationKind.GhostText, 0, 0, 1, 0, 1, id = 1, text = "yz")))
        model.edit(p(0, 1), p(0, 1), "q", EditOrigin.Typing)
        assertNull(model.ghostText)
        assertFalse(model.drainOutput().any { it is EditorOutput.Activated })
    }

    /** A lens reports its id when pressed, and a lens with no id reports nothing. */
    @Test
    fun fr38_pressing_a_lens_reports_its_id() {
        val model = CodeEditorModel("fn main() {}\n")
        model.setDecorations(
            listOf(
                decoration(DecorationKind.CodeLens, 0, 0, 0, 0, 0, id = 9, text = "Run"),
                decoration(DecorationKind.CodeLens, 0, 0, 0, 0, 0, id = 0, text = "2 references"),
            ),
        )
        val (line, lenses) = model.lensesByLine().single()
        assertEquals(0, line)
        lenses.forEach(model::activate)
        assertEquals(listOf(9L), model.drainOutput().map { (it as EditorOutput.Activated).id })
    }

    /** Undo and redo report every change they make, each with its own version. */
    @Test
    fun fr38_undo_and_redo_report_their_changes() {
        val model = CodeEditorModel("abc")
        model.edit(p(0, 3), p(0, 3), "def")
        model.edit(p(0, 0), p(0, 1), "A")
        model.drainOutput()
        assertTrue(model.undo())
        assertTrue(model.undo())
        assertEquals("abc", model.document.text())
        assertTrue(model.redo())
        assertEquals("abcdef", model.document.text())
        val versions = model.drainOutput().map { (it as EditorOutput.Changed).change.version }
        assertEquals(listOf(3, 4, 5), versions)
    }

    /** Characters typed in a run undo together, and a pause or a jump starts a new step. */
    @Test
    fun fr38_a_run_of_typing_undoes_as_one_step() {
        var now = 0L
        val model = CodeEditorModel("", clock = { now })
        for ((index, character) in "hello".withIndex()) {
            model.edit(p(0, index), p(0, index), character.toString(), EditOrigin.Typing)
            now += 100_000_000L
        }
        now += 2_000_000_000L
        model.edit(p(0, 5), p(0, 5), "!", EditOrigin.Typing)
        model.undo()
        assertEquals("hello", model.document.text())
        model.undo()
        assertEquals("", model.document.text())
    }

    /** The automatic indentation keeps the previous line's leading whitespace and no more. */
    @Test
    fun fr38_auto_indentation_keeps_the_previous_lines_indentation() {
        val state = androidx.compose.foundation.text.input.TextFieldState("    let a = 1;")
        state.edit {
            replace(length, length, "\n")
            with(dev.darkpyonix.composerust.foundation.code.KeepIndentation) { transformInput() }
        }
        assertEquals("    let a = 1;\n    ", state.text.toString())
        assertEquals(19, state.selection.start)
    }

    /**
     * An edit record goes through the node table to the editor's document, and what it did
     * is queued for the Host until the batch has been applied.
     */
    @Test
    fun fr38_an_edit_record_reaches_the_document_and_reports_through_the_table() {
        val table = NodeTable()
        table.apply(Mutation.Create(3, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(3, PropertyKind.OnValueChange, PropertyValue.Integer(11L)))
        table.apply(Mutation.SetProp(3, PropertyKind.OnEditRejected, PropertyValue.Integer(12L)))
        table.apply(Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("a\nb\n")))
        table.apply(Mutation.EditCode(3, 1, 0, 1, 0, 1, 1, "B"))
        table.apply(Mutation.EditCode(3, 2, 0, 1, 0, 1, 1, "C"))
        assertEquals("a\nB\n", table.node(3)?.code?.document?.text())
        assertEquals(
            listOf(
                HostEvent.CodeChanged(3, 11L, 1, 1, 0, 1, 1, "B"),
                HostEvent.CodeEditRejected(3, 12L, 2, 0, 1, 1, 0, 1, 1),
            ),
            table.drainEvents(),
        )
        assertTrue(table.drainErrors().isEmpty())
        // An edit record for a node that is not an editor is a protocol error.
        table.apply(Mutation.Create(4, WidgetKind.Text))
        table.apply(Mutation.EditCode(4, 1, 0, 0, 0, 0, 0, "x"))
        assertEquals(TableError.UNSUPPORTED_PROPERTY, table.drainErrors().single().code)
    }

    /** A decoration list with a record this side cannot read loses that record and says so. */
    @Test
    fun fr38_an_unreadable_decoration_record_is_a_protocol_error_and_the_rest_are_kept() {
        val records = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        fun record(kind: Int) {
            records.putInt(0)
            records.putShort(kind.toShort())
            records.putShort(1)
            records.putShort(0)
            records.putShort(0)
            records.putInt(0)
            records.putInt(0)
            records.putInt(0)
            records.putInt(2)
            records.putLong(0)
            records.putInt(88)
            records.putInt(0)
        }
        record(1)
        record(9)
        val table = NodeTable()
        table.apply(Mutation.Create(3, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("abc")))
        table.apply(Mutation.SetProp(3, PropertyKind.Decorations, PropertyValue.Bytes(records.array())))
        assertEquals(1, table.node(3)?.code?.decorations?.size)
        assertEquals(TableError.INVALID_CODE_EDIT, table.drainErrors().single().code)
    }

    /** What a code editor carries is kept on a code editor and nowhere else. */
    @Test
    fun fr38_an_editor_keeps_its_properties_and_other_widgets_do_not() {
        for (property in listOf(
            PropertyKind.Text,
            PropertyKind.Decorations,
            PropertyKind.SyntaxSpans,
            PropertyKind.TabWidth,
            PropertyKind.OnValueChange,
            PropertyKind.OnEditRejected,
            PropertyKind.OnHover,
            PropertyKind.OnSave,
            PropertyKind.OnDecorationClick,
        )) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, property), "$property")
        }
        assertFalse(NodeTable.supportsProperty(WidgetKind.Text, PropertyKind.Decorations))
        assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.TabWidth))
    }
}
