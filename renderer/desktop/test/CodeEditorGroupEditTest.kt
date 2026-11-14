package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.protocol.CodeEditRecord
import dev.darkpyonix.composerust.protocol.CodeEditorRecords
import dev.darkpyonix.composerust.protocol.EditGroupStatus
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EDITOR = 3
private const val ON_CHANGE = 51L
private const val ON_REJECTED = 52L
private const val ON_GROUP = 53L

private fun p(line: Int, column: Int) = CodePosition(line, column)

/** One edit of a group: a range of the document at the group's base version, and its text. */
private data class Edit(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val text: String)

/**
 * A group's blob as the Rust side lays it out: the count, one 24-byte record per edit (the
 * range, then the text's offset and length), then the texts. Offsets count from where the
 * texts start.
 */
private fun groupBlob(vararg edits: Edit): ByteArray {
    val texts = edits.map { it.text.toByteArray(StandardCharsets.UTF_8) }
    val buffer = ByteBuffer.allocate(4 + edits.size * 24 + texts.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(edits.size)
    var offset = 0
    for ((index, edit) in edits.withIndex()) {
        buffer.putInt(edit.startLine).putInt(edit.startColumn).putInt(edit.endLine).putInt(edit.endColumn)
        buffer.putInt(offset).putInt(texts[index].size)
        offset += texts[index].size
    }
    texts.forEach { buffer.put(it) }
    return buffer.array()
}

/**
 * One batch holding one `EditCodeGroup` record of [recordLength] bytes, written by hand so
 * that a broken one can be written too, and the blob behind it in the arena.
 */
private fun groupBatch(requestId: Int, baseVersion: Int, blob: ByteArray, recordLength: Int = 24): ByteArray {
    val recordsLength = 12 + recordLength
    val buffer = ByteBuffer.allocate(recordsLength + blob.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putShort(0).putShort(12).putInt(recordsLength).putInt(1)
    buffer.putShort(28).putShort(recordLength.toShort())
    val words = intArrayOf(EDITOR, requestId, baseVersion, recordsLength, blob.size)
    for (index in 0 until (recordLength - 4) / 4) buffer.putInt(words.getOrElse(index) { 0 })
    buffer.put(blob)
    return buffer.array()
}

/** An editor that reports its changes, its refusals and its group results, drained of what creating it queued. */
private fun editorTable(text: String): NodeTable {
    val table = NodeTable()
    table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnValueChange, PropertyValue.Integer(ON_CHANGE)))
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnEditRejected, PropertyValue.Integer(ON_REJECTED)))
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnEditGroupResult, PropertyValue.Integer(ON_GROUP)))
    table.drainEvents()
    table.drainErrors()
    return table
}

private fun NodeTable.model(): CodeEditorModel = node(EDITOR)!!.code!!

private fun NodeTable.group(requestId: Int, baseVersion: Int, vararg edits: Edit) =
    apply(Mutation.EditCodeGroup(EDITOR, requestId, baseVersion, groupBlob(*edits)))

private fun result(requestId: Int, status: EditGroupStatus, baseVersion: Int, currentVersion: Int, index: Int) =
    HostEvent.CodeEditGroupResult(EDITOR, ON_GROUP, requestId, status, baseVersion, currentVersion, index)

/** Where a language server would put a position in a string: `\n`, `\r\n` and `\r` end lines. */
private fun offsetOf(text: String, position: CodePosition): Int {
    var line = 0
    var index = 0
    while (line < position.line) {
        val character = text[index]
        if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            index += 2
        } else {
            index += 1
        }
        if (character == '\n' || character == '\r') line += 1
    }
    return index + position.column
}

/** Applies a change the way a Host keeping its own copy does. */
private fun String.applying(change: HostEvent.CodeChanged): String {
    val start = offsetOf(this, p(change.startLine, change.startColumn))
    val end = offsetOf(this, p(change.endLine, change.endColumn))
    return substring(0, start) + change.text + substring(end)
}

/**
 * A grouped edit, `EditCodeGroup`: several edits the Host asks for as one, which go in
 * whole or not at all and are answered exactly once by `CodeEditGroupResult`.
 */
class CodeEditorGroupEditTest {
    /** Three edits that do not overlap, sent against the current version, all go in, and one result says so. */
    @Test
    fun fr38_edit_group_applies_all_edits_and_replies_once() {
        val table = editorTable("let alpha = beta + gamma;\n")
        table.group(
            41,
            0,
            Edit(0, 4, 0, 9, "first"),
            Edit(0, 12, 0, 16, "second"),
            Edit(0, 19, 0, 24, "third"),
        )
        assertEquals("let first = second + third;\n", table.model().document.text())
        val events = table.drainEvents()
        assertEquals(3, events.count { it is HostEvent.CodeChanged })
        assertEquals(listOf(result(41, EditGroupStatus.Applied, 0, 3, 3)), events.filterIsInstance<HostEvent.CodeEditGroupResult>())
        assertTrue(table.drainErrors().isEmpty())
    }

    /**
     * One change for each edit, from the end of the document backwards, each against the
     * document as it stood just before it and each one version on, so a Host replaying them
     * in order reaches the editor's text; the result comes after them. The edits are sent
     * out of order, span lines, meet at their edges, hold surrogate pairs and line breaks,
     * and two insertions share a place.
     */
    @Test
    fun fr38_edit_group_events_are_ordered_and_replayable() {
        val original = "fn main() {\r\n    let 한 = \"😀\";\n    call(a, b);\n}\n"
        val table = editorTable(original)
        table.group(
            7,
            0,
            Edit(2, 9, 2, 10, "x"),
            Edit(0, 0, 0, 2, "pub fn"),
            Edit(1, 13, 1, 15, "🎉🎉"),
            Edit(2, 4, 2, 8, "invoke"),
            Edit(0, 9, 1, 4, " {\n  "),
            Edit(3, 1, 3, 1, "// one"),
            Edit(3, 1, 3, 1, " // two"),
            Edit(2, 8, 2, 9, "["),
        )
        val events = table.drainEvents()
        val changes = events.filterIsInstance<HostEvent.CodeChanged>()
        assertEquals(8, changes.size)
        assertEquals((1..8).toList(), changes.map { it.version })
        for ((before, after) in changes.zipWithNext()) {
            assertTrue(
                p(after.startLine, after.startColumn) <= p(before.startLine, before.startColumn),
                "$after comes after $before, so it has to start no later",
            )
        }
        var replayed = original
        for (change in changes) replayed = replayed.applying(change)
        assertEquals(table.model().document.text(), replayed)
        assertEquals(
            "pub fn main() {\n  let 한 = \"🎉🎉\";\n    invoke[x, b);\n}// one // two\n",
            replayed,
        )
        assertEquals(result(7, EditGroupStatus.Applied, 0, 8, 8), events.last())
        assertEquals(1, events.count { it is HostEvent.CodeEditGroupResult })
    }

    /** The whole group undoes in one step and comes back in one, and never joins the typing before it. */
    @Test
    fun fr38_edit_group_is_one_undo_step() {
        val table = editorTable("one two three\n")
        val model = table.model()
        model.edit(p(0, 13), p(0, 13), "!", EditOrigin.Typing)
        model.edit(p(0, 14), p(0, 14), "?", EditOrigin.Typing)
        model.drainOutput()
        val typed = model.document.text()
        assertEquals("one two three!?\n", typed)
        val depth = model.undoDepth
        table.group(9, 2, Edit(0, 0, 0, 3, "1"), Edit(0, 4, 0, 7, "2"), Edit(0, 8, 0, 13, "3"))
        assertEquals("1 2 3!?\n", model.document.text())
        assertEquals(depth + 1, model.undoDepth)

        assertTrue(model.undo())
        assertEquals(typed, model.document.text())
        assertTrue(model.redo())
        assertEquals("1 2 3!?\n", model.document.text())
        assertTrue(model.undo())
        // The step before is still the typing, whole.
        assertTrue(model.undo())
        assertEquals("one two three\n", model.document.text())

        // And typing right after a group starts a step of its own.
        val after = editorTable("ab\n")
        after.group(1, 0, Edit(0, 0, 0, 1, "A"), Edit(0, 1, 0, 2, "B"))
        after.model().edit(p(0, 2), p(0, 2), "c", EditOrigin.Typing)
        assertTrue(after.model().undo())
        assertEquals("AB\n", after.model().document.text())
        assertTrue(after.model().undo())
        assertEquals("ab\n", after.model().document.text())
    }

    /** Edits that overlap each other change nothing, answer `Invalid` once, and are no protocol error. */
    @Test
    fun fr38_edit_group_with_overlapping_edits_changes_nothing() {
        val table = editorTable("abcdefgh\n")
        val cases = listOf(
            // Two stretches; the later of the pair in the document is the one named.
            listOf(Edit(0, 0, 0, 4, "x"), Edit(0, 6, 0, 7, "y"), Edit(0, 2, 0, 5, "z")) to 2,
            // An insertion strictly inside a stretch.
            listOf(Edit(0, 3, 0, 3, "x"), Edit(0, 1, 0, 6, "y")) to 0,
            // The same stretch twice.
            listOf(Edit(0, 1, 0, 3, "x"), Edit(0, 1, 0, 3, "y")) to 1,
        )
        for ((edits, index) in cases) {
            table.group(5, 0, *edits.toTypedArray())
            assertEquals("abcdefgh\n", table.model().document.text())
            assertEquals(0, table.model().document.version)
            assertEquals(listOf<HostEvent>(result(5, EditGroupStatus.Invalid, 0, 0, index)), table.drainEvents())
            assertTrue(table.drainErrors().isEmpty())
        }
        // Meeting at an edge is not overlapping, nor are two insertions at one place.
        table.group(6, 0, Edit(0, 0, 0, 2, "x"), Edit(0, 2, 0, 4, "y"), Edit(0, 4, 0, 4, "1"), Edit(0, 4, 0, 4, "2"))
        assertEquals("xy12efgh\n", table.model().document.text())
    }

    /** One range that is not in the document, or that starts after it ends, keeps the others out too. */
    @Test
    fun fr38_edit_group_with_a_bad_range_changes_nothing() {
        val table = editorTable("one\ntwo\n")
        val cases = listOf(
            listOf(Edit(0, 0, 0, 1, "O"), Edit(9, 0, 9, 1, "x")) to 1,
            listOf(Edit(0, 0, 0, 1, "O"), Edit(1, 1, 1, 9, "x")) to 1,
            listOf(Edit(1, 2, 1, 1, "x"), Edit(0, 0, 0, 1, "O")) to 0,
            listOf(Edit(0, 0, 0, 1, "O"), Edit(-1, 0, 0, 0, "x")) to 1,
        )
        for ((edits, index) in cases) {
            table.group(3, 0, *edits.toTypedArray())
            assertEquals("one\ntwo\n", table.model().document.text())
            assertEquals(listOf<HostEvent>(result(3, EditGroupStatus.Invalid, 0, 0, index)), table.drainEvents())
            assertTrue(table.drainErrors().isEmpty())
        }
        // A version the document has not reached is the group's mistake as a whole.
        table.group(4, 3, Edit(0, 0, 0, 1, "O"))
        assertEquals(listOf<HostEvent>(result(4, EditGroupStatus.Invalid, 3, 0, 0)), table.drainEvents())
        assertTrue(table.drainErrors().isEmpty())
        assertEquals(0, table.model().undoDepth)
    }

    /**
     * Written against a version the reader has typed past, every range is moved past what
     * they typed; where any range meets what they typed, the whole group is refused.
     */
    @Test
    fun fr38_edit_group_with_a_stale_base_moves_or_rejects_all() {
        val table = editorTable("alpha beta gamma\n")
        val model = table.model()
        // The reader puts a line in front and a word in the middle: version 2.
        model.edit(p(0, 0), p(0, 0), "// note\n", EditOrigin.Reader)
        model.edit(p(1, 6), p(1, 6), "big ", EditOrigin.Reader)
        model.drainOutput()
        assertEquals("// note\nalpha big beta gamma\n", model.document.text())

        table.group(11, 0, Edit(0, 0, 0, 5, "ALPHA"), Edit(0, 6, 0, 10, "BETA"), Edit(0, 11, 0, 16, "GAMMA"))
        assertEquals("// note\nALPHA big BETA GAMMA\n", model.document.text())
        val applied = table.drainEvents()
        assertEquals(result(11, EditGroupStatus.Applied, 0, 5, 3), applied.last())

        // The reader changes "BETA" itself; a group written against version 5 that touches
        // it is refused whole, naming the first edit that met the reader's change.
        model.edit(p(1, 10), p(1, 11), "b", EditOrigin.Reader)
        model.drainOutput()
        val before = model.document.text()
        table.group(12, 5, Edit(1, 0, 1, 5, "a"), Edit(1, 15, 1, 20, "g"), Edit(1, 10, 1, 14, "b"))
        assertEquals(before, model.document.text())
        assertEquals(listOf<HostEvent>(result(12, EditGroupStatus.Rejected, 5, 6, 2)), table.drainEvents())
        assertTrue(table.drainErrors().isEmpty())
    }

    /** A version older than the editor remembers is `TooOld`, which is not the `Rejected` of a collision. */
    @Test
    fun fr38_edit_group_older_than_the_history_is_too_old() {
        val table = editorTable("x\n")
        val model = table.model()
        repeat(CodeDocumentLimit + 1) { model.edit(p(0, 0), p(0, 0), "a", EditOrigin.Reader) }
        model.drainOutput()
        val version = model.document.version
        val before = model.document.text()
        table.group(21, 0, Edit(0, 0, 0, 1, "b"))
        assertEquals(before, model.document.text())
        assertEquals(listOf<HostEvent>(result(21, EditGroupStatus.TooOld, 0, version, 0)), table.drainEvents())
        // The newest version still remembered moves forward as usual.
        table.group(22, version - 1, Edit(0, 0, 0, 0, "b"))
        assertEquals(EditGroupStatus.Applied, (table.drainEvents().last() as HostEvent.CodeEditGroupResult).status)
        assertTrue(table.drainErrors().isEmpty())
    }

    /**
     * `EditCode` sent one after another keeps what it did before: each is moved past every
     * change since its version, including the ones sent with it, only the one that meets an
     * earlier one is refused, the earlier ones stay, and each is one undo step.
     */
    @Test
    fun fr38_edit_code_sequence_keeps_its_behaviour() {
        val table = editorTable("abc def ghi\n")
        val model = table.model()
        table.apply(Mutation.EditCode(EDITOR, 1, 0, 0, 0, 0, 3, "ABCD"))
        table.apply(Mutation.EditCode(EDITOR, 2, 0, 0, 8, 0, 11, "GHI"))
        table.apply(Mutation.EditCode(EDITOR, 3, 0, 0, 2, 0, 5, "x"))
        assertEquals("ABCD def GHI\n", model.document.text())
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 0, 0, 0, 3, "ABCD"),
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 2, 0, 9, 0, 12, "GHI"),
                HostEvent.CodeEditRejected(EDITOR, ON_REJECTED, 3, 0, 2, 0, 2, 0, 5),
            ),
            table.drainEvents(),
        )
        assertEquals(2, model.undoDepth)
        assertTrue(model.undo())
        assertEquals("ABCD def ghi\n", model.document.text())
        assertTrue(table.drainErrors().isEmpty())
    }

    /**
     * The command decoded here holds what the Rust side wrote: the head, the count and every
     * text, a surrogate pair among them; and the result encodes to the 36 bytes it reads.
     */
    @Test
    fun fr38_edit_group_round_trips_on_the_wire() {
        val blob = groupBlob(Edit(1, 17, 1, 19, "🎉"), Edit(0, 0, 0, 2, "pub fn"), Edit(2, 0, 2, 0, ""))
        val decoded = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(groupBatch(8, 1, blob)), decoded::add)
        val group = decoded.single() as Mutation.EditCodeGroup
        assertEquals(Mutation.EditCodeGroup(EDITOR, 8, 1, blob), group)
        val errors = mutableListOf<String>()
        assertEquals(
            listOf(
                CodeEditRecord(1, 17, 1, 19, "🎉"),
                CodeEditRecord(0, 0, 0, 2, "pub fn"),
                CodeEditRecord(2, 0, 2, 0, ""),
            ),
            CodeEditorRecords.decodeEditGroup(group.edits, errors::add),
        )
        assertTrue(errors.isEmpty())

        val out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        val length = Protocol.encodeEvent(result(8, EditGroupStatus.TooOld, 1, 3, 2), out)
        assertEquals(36, length)
        assertEquals(51, out.getShort(0).toInt())
        assertEquals(36, out.getShort(2).toInt())
        assertEquals(listOf(8, 4, 1, 3, 2), (0 until 5).map { out.getInt(16 + it * 4) })
    }

    /**
     * A group with no edits changes nothing, makes no change event and no undo step, leaves
     * the version, and is answered as applied at the version the document is at, whatever
     * version it was sent with.
     */
    @Test
    fun fr38_an_empty_group_changes_nothing_and_replies_applied() {
        val table = editorTable("keep\n")
        val model = table.model()
        model.edit(p(0, 4), p(0, 4), "s", EditOrigin.Reader)
        model.drainOutput()
        val depth = model.undoDepth
        table.group(17, 99)
        assertEquals("keeps\n", model.document.text())
        assertEquals(1, model.document.version)
        assertEquals(depth, model.undoDepth)
        assertEquals(listOf<HostEvent>(result(17, EditGroupStatus.Applied, 1, 1, 0)), table.drainEvents())
        assertTrue(table.drainErrors().isEmpty())
    }

    /**
     * A record of the wrong length is one protocol error and, where its head can be read,
     * one `Invalid` after it for the same request; edits that cannot be read are the same.
     * A group that only makes no sense is answered `Invalid` with no protocol error.
     */
    @Test
    fun fr38_a_broken_group_record_is_a_protocol_error_and_a_semantic_error_is_not() {
        val connection = RawBatchConnection(
            listOf(
                Mutation.Create(EDITOR, WidgetKind.CodeEditor),
                Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("abc\n")),
                Mutation.SetProp(EDITOR, PropertyKind.OnValueChange, PropertyValue.Integer(ON_CHANGE)),
                Mutation.SetProp(EDITOR, PropertyKind.OnEditGroupResult, PropertyValue.Integer(ON_GROUP)),
            ),
        )
        val host = ComposeRustHost(connection)
        host.start()
        fun send(batch: ByteArray): List<HostEvent> {
            connection.recorded.clear()
            connection.next = batch
            host.renderFrame(0L)
            return connection.recorded.filter {
                it is HostEvent.ProtocolError || it is HostEvent.CodeEditGroupResult || it is HostEvent.CodeChanged
            }
        }
        fun text() = host.table.node(EDITOR)!!.code!!.document.text()
        val good = groupBlob(Edit(0, 0, 0, 1, "A"))

        // Twenty bytes: the head is there, the edits' reference is not.
        val short = send(groupBatch(31, 0, good, recordLength = 20))
        assertEquals(2, short.size, "$short")
        assertTrue(short[0] is HostEvent.ProtocolError)
        assertEquals(result(31, EditGroupStatus.Invalid, 0, 0, 0), short[1])
        assertEquals("abc\n", text())

        // Twenty-eight bytes, one word too many.
        val long = send(groupBatch(32, 0, good, recordLength = 28))
        assertTrue(long[0] is HostEvent.ProtocolError)
        assertEquals(listOf<HostEvent>(result(32, EditGroupStatus.Invalid, 0, 0, 0)), long.drop(1))

        // Eight bytes: not even the request can be read, so there is nobody to answer.
        val headless = send(groupBatch(33, 0, good, recordLength = 8))
        assertEquals(1, headless.size, "$headless")
        assertTrue(headless.single() is HostEvent.ProtocolError)

        // A well formed record whose edits cannot be read: the count says two.
        val unreadable = good.copyOf().also { it[0] = 2 }
        val broken = send(groupBatch(34, 0, unreadable))
        assertTrue(broken[0] is HostEvent.ProtocolError)
        assertEquals(listOf<HostEvent>(result(34, EditGroupStatus.Invalid, 0, 0, 0)), broken.drop(1))
        assertEquals("abc\n", text())

        // Overlapping edits and a range past the end are the group's mistake, not the wire's.
        val overlapping = send(groupBatch(35, 0, groupBlob(Edit(0, 0, 0, 2, "x"), Edit(0, 1, 0, 3, "y"))))
        assertEquals(listOf<HostEvent>(result(35, EditGroupStatus.Invalid, 0, 0, 1)), overlapping)
        val outside = send(groupBatch(36, 0, groupBlob(Edit(0, 0, 0, 1, "x"), Edit(5, 0, 5, 0, "y"))))
        assertEquals(listOf<HostEvent>(result(36, EditGroupStatus.Invalid, 0, 0, 1)), outside)
        assertEquals("abc\n", text())

        // And a good one still goes in after all of that.
        val applied = send(groupBatch(37, 0, good))
        assertEquals(result(37, EditGroupStatus.Applied, 0, 1, 1), applied.last())
        assertEquals("Abc\n", text())
    }
}

/** How many changes a document remembers, past which a version is too old to move forward from. */
private val CodeDocumentLimit = dev.darkpyonix.composerust.foundation.code.CodeDocument.HISTORY_LIMIT

/**
 * A Host whose first batch is a list of records and whose frames are raw bytes, decoded by
 * the generated decoder the way the boundary decodes them, so a broken record can be sent.
 */
private class RawBatchConnection(private val initial: List<Mutation>) : HostConnection {
    val recorded = mutableListOf<HostEvent>()
    var next: ByteArray? = null

    override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        recorded += event
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        val batch = next ?: return
        next = null
        Protocol.decode(ByteBuffer.wrap(batch), onMutation)
    }

    override fun shutdown() {}
}
