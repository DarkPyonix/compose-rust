package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.FormatOp
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.MAX_PENDING_RICH_BYTES
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RichRequest
import dev.darkpyonix.composerust.ui.node.TableError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a rich text field keeps of the Host's `FormatText` and `SetRichText` until its rich
 * text model takes them: every request in the order it came, and no more than
 * [MAX_PENDING_RICH_BYTES] of them, because nothing takes them yet and a queue nobody
 * empties would otherwise grow for as long as the node lives.
 */
class RichTextRequestsTest {

    /** A table with one rich text field, and the lines it logs. */
    private fun richField(): Pair<NodeTable, MutableList<String>> {
        val table = NodeTable()
        val logged = mutableListOf<String>()
        table.log = { line -> logged += line }
        table.apply(Mutation.Create(FIELD, WidgetKind.TextField))
        table.apply(Mutation.SetProp(FIELD, PropertyKind.RichText, PropertyValue.Bool(true)))
        return table to logged
    }

    private fun bold() = Mutation.FormatText(FIELD, FormatOp.Bold, 2, null)

    /** A document of [units] letters and no formatting, which weighs 40 bytes and two a letter. */
    private fun document(units: Int) =
        Mutation.SetRichText(FIELD, "a".repeat(units), ByteArray(0), ByteArray(0), -1, -1)

    @Test
    fun fr26_1_requests_wait_on_the_field_in_the_order_they_came() {
        val (table, logged) = richField()
        table.apply(bold())
        table.apply(Mutation.SetRichText(FIELD, "한글", ByteArray(24), ByteArray(16), 0, 2))
        table.apply(Mutation.FormatText(FIELD, FormatOp.Indent, 0, null))

        val node = table.node(FIELD)!!
        val waiting = node.pendingRich
        assertEquals(3, waiting.size)
        assertEquals(RichRequest.Format(FormatOp.Bold, 2, null), waiting[0])
        val load = waiting[1] as RichRequest.Load
        assertEquals("한글", load.text)
        assertEquals(24, load.characters.size)
        assertEquals(16, load.paragraphs.size)
        assertEquals(0 to 2, load.selectionStart to load.selectionEnd)
        assertEquals(RichRequest.Format(FormatOp.Indent, 0, null), waiting[2])
        // A command is its 20 byte record. A document is its 40 byte record, its text at two
        // bytes a UTF-16 unit and its two arrays.
        assertEquals(20L + (40L + 2 * 2 + 24 + 16) + 20L, node.pendingRichBytes)
        assertTrue(table.drainErrors().isEmpty())
        assertTrue(logged.isEmpty(), "nothing was dropped: $logged")
    }

    @Test
    fun fr26_1_a_request_for_a_field_that_is_not_rich_is_an_error_and_waits_nowhere() {
        val table = NodeTable()
        table.apply(Mutation.Create(FIELD, WidgetKind.TextField))
        table.apply(bold())
        table.apply(document(4))
        assertEquals(
            listOf(TableError.UNSUPPORTED_PROPERTY, TableError.UNSUPPORTED_PROPERTY),
            table.drainErrors().map { it.code },
        )
        assertTrue(table.node(FIELD)!!.pendingRich.isEmpty())
    }

    @Test
    fun fr26_1_requests_past_the_cap_are_dropped_and_said_once() {
        val (table, logged) = richField()
        // Two documents of 40 bytes and half the cap each: the second one does not fit
        // behind the first by its 80 bytes of record.
        val half = (MAX_PENDING_RICH_BYTES / 4).toInt()
        table.apply(document(half))
        val node = table.node(FIELD)!!
        assertEquals(40L + MAX_PENDING_RICH_BYTES / 2, node.pendingRichBytes)
        table.apply(document(half))
        assertEquals(1, node.pendingRich.size, "the second document was kept")
        assertEquals(40L + MAX_PENDING_RICH_BYTES / 2, node.pendingRichBytes)
        assertEquals(1, logged.size, "the drop is said: $logged")
        assertTrue("SetRichText" in logged[0] && "node $FIELD" in logged[0], logged[0])
        assertTrue(MAX_PENDING_RICH_BYTES.toString() in logged[0], logged[0])
        assertTrue("aaaa" !in logged[0], "the text of a document never reaches a log")

        // Small commands still fit until the cap is reached, and then they are dropped too,
        // without another line for each.
        val room = MAX_PENDING_RICH_BYTES - node.pendingRichBytes
        val fitting = (room / 20).toInt()
        repeat(fitting + 50) { table.apply(bold()) }
        assertEquals(1 + fitting, node.pendingRich.size)
        assertTrue(node.pendingRichBytes <= MAX_PENDING_RICH_BYTES)
        assertEquals(1 + 50, node.droppedRich)
        assertEquals(1, logged.size, "one line for the field, not one a request: $logged")
        // A dropped request is not a protocol error: the Host sent nothing the protocol forbids.
        assertTrue(table.drainErrors().isEmpty())
    }

    @Test
    fun fr26_1_a_document_larger_than_the_cap_is_taken_when_nothing_waits() {
        val (table, logged) = richField()
        val large = (MAX_PENDING_RICH_BYTES / 2).toInt() + 1
        table.apply(document(large))
        val node = table.node(FIELD)!!
        assertEquals(1, node.pendingRich.size)
        assertTrue(node.pendingRichBytes > MAX_PENDING_RICH_BYTES)
        assertTrue(logged.isEmpty(), "$logged")
        // Nothing fits behind it.
        table.apply(bold())
        assertEquals(1, node.pendingRich.size)
        assertEquals(1, logged.size)
        assertTrue("FormatText" in logged[0], logged[0])
    }

    @Test
    fun fr26_1_taking_the_requests_makes_room_and_the_next_drop_is_said_again() {
        val (table, logged) = richField()
        val large = (MAX_PENDING_RICH_BYTES / 2).toInt() + 1
        table.apply(document(large))
        table.apply(bold())
        val node = table.node(FIELD)!!
        assertEquals(1, logged.size)

        val taken = node.takePendingRich()
        assertEquals(1, taken.size)
        assertTrue(node.pendingRich.isEmpty())
        assertEquals(0L, node.pendingRichBytes)
        assertEquals(0, node.droppedRich)

        table.apply(bold())
        assertEquals(listOf<RichRequest>(RichRequest.Format(FormatOp.Bold, 2, null)), node.pendingRich)
        assertEquals(1, logged.size, "a request that fits says nothing")
        table.apply(document(large))
        assertEquals(1, node.pendingRich.size)
        assertEquals(2, logged.size, "a field that fills again says so again: $logged")
    }

    private companion object {
        const val FIELD = 7
    }
}
