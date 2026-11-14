package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import dev.darkpyonix.composerust.foundation.TextRun
import dev.darkpyonix.composerust.foundation.decodeRuns
import dev.darkpyonix.composerust.foundation.runsProblem
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SpanRecords
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs inside a string, and what happens when a Host miscounts them.
 *
 * A run reaching past the end of the string, or starting inside the one before it, is a
 * Host being wrong about its own text. The reader still wants to read the paragraph, so
 * it is reported and the string is drawn plain: never an abort.
 */
@OptIn(ExperimentalTestApi::class)
class TextRunsTest {

    private fun run(start: Int, length: Int) = TextRun(
        start = start,
        length = length,
        role = null,
        color = null,
        bold = false,
        italic = false,
        underline = false,
        strikethrough = false,
        handlerId = 0,
    )

    @Test
    fun fr26_runs_that_fit_the_string_are_accepted() {
        assertNull(runsProblem(listOf(run(0, 5), run(6, 4)), byteLength = 10))
    }

    @Test
    fun fr26_a_run_past_the_end_is_reported() {
        val problem = runsProblem(listOf(run(6, 9)), byteLength = 10)
        assertNotNull(problem, "a run covering bytes 6 to 15 of a ten byte string is wrong")
    }

    @Test
    fun fr26_overlapping_runs_are_reported() {
        val problem = runsProblem(listOf(run(0, 6), run(4, 3)), byteLength = 20)
        assertNotNull(problem, "the second run starts inside the first")
    }

    @Test
    fun fr26_an_empty_run_is_reported() {
        assertNotNull(runsProblem(listOf(run(3, 0)), byteLength = 10))
    }

    @Test
    fun fr26_a_blob_that_is_not_whole_records_is_refused() {
        assertNull(decodeRuns(ByteArray(17)), "seventeen bytes is not a whole number of runs")
        assertNull(decodeRuns(ByteArray(28)), "the old 28 byte record is not a whole run any more")
        assertEquals(0, decodeRuns(ByteArray(0))?.size, "no bytes is no runs, not an error")
    }

    /** One 36 byte record, written the way the Host writes it. */
    private fun record(
        start: Int,
        length: Int,
        color: Long = 0,
        background: Long = 0,
        flags: Int = 0,
        handler: Long = 0,
    ): ByteArray = ByteBuffer.allocate(SpanRecords.SPAN_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(start)
        .putInt(length)
        .putShort(0)
        .putShort(flags.toShort())
        .putLong(color)
        .putLong(background)
        .putLong(handler)
        .array()

    private fun role(role: ColorRole): Long = (1L shl 32) or (role.ordinal + 1L)

    private fun literal(argb: Int): Long = (2L shl 32) or (argb.toLong() and 0xffffffffL)

    @Test
    fun fr26_a_run_carries_its_colour_and_its_background_apart() {
        val runs = decodeRuns(
            record(0, 4, color = role(ColorRole.SyntaxKeyword), background = role(ColorRole.DiffAddedEmphasis)) +
                record(5, 2),
        )
        assertNotNull(runs)
        assertEquals(36, SpanRecords.SPAN_LENGTH)
        assertEquals(Paint.Role(ColorRole.SyntaxKeyword), runs[0].color)
        assertEquals(Paint.Role(ColorRole.DiffAddedEmphasis), runs[0].background)
        assertNull(runs[1].color)
        assertNull(runs[1].background, "a background of zero is no background")
    }

    private fun drawn(runs: ByteArray, text: String): java.awt.image.BufferedImage {
        lateinit var image: java.awt.image.BufferedImage
        runComposeUiTest {
            val connection = FakeHostConnection(
                listOf(
                    Mutation.Create(1, WidgetKind.Column),
                    Mutation.Create(2, WidgetKind.Text),
                    Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text(text)),
                    Mutation.SetProp(2, PropertyKind.FontSize, PropertyValue.Float(40f)),
                    Mutation.SetProp(2, PropertyKind.Spans, PropertyValue.Bytes(runs)),
                    Mutation.Insert(1, 2, 0),
                ),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            waitForIdle()
            image = onNodeWithTag(nodeTestTag(2)).captureToImage().toAwtImage()
        }
        return image
    }

    private fun count(image: java.awt.image.BufferedImage, argb: Int, from: Int, to: Int): Int {
        var found = 0
        for (x in from until to) {
            for (y in 0 until image.height) {
                if (image.getRGB(x, y) == argb) found++
            }
        }
        return found
    }

    /**
     * The changed word is painted behind and the rest of the line is not. The run is the
     * first word, so everything right of the middle is outside it.
     */
    @Test
    fun fr26_a_background_paints_behind_its_own_run_and_nowhere_else() {
        val red = 0xFFFF0000.toInt()
        val image = drawn(record(0, 4, background = literal(red)), "WWWW iiiiiiiiiiiiiiiii")
        assertTrue(count(image, red, 0, image.width / 4) > 0, "nothing was painted behind the run")
        assertEquals(0, count(image, red, image.width / 2, image.width), "the background ran past its run")
    }

    /** A run with a text colour and a background draws both. */
    @Test
    fun fr26_a_run_with_a_colour_and_a_background_draws_both() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val image = drawn(
            record(0, 4, color = literal(blue), background = literal(red), flags = 1),
            "WWWW",
        )
        assertTrue(count(image, red, 0, image.width) > 0, "the background was not drawn")
        assertTrue(count(image, blue, 0, image.width) > 0, "the text colour was not drawn")
    }

    /** A run whose background is zero draws nothing behind its letters. */
    @Test
    fun fr26_a_run_without_a_background_paints_nothing_behind_it() {
        val plain = drawn(record(0, 4), "WWWW")
        val red = 0xFFFF0000.toInt()
        assertEquals(0, count(plain, red, 0, plain.width))
    }

    /**
     * A run past the end of its string reaches the Host as one protocol error, and the
     * string is still drawn.
     */
    @Test
    fun fr26_a_bad_run_is_reported_to_the_host_and_the_text_still_draws() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(2, WidgetKind.Text),
                Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("short")),
                Mutation.SetProp(2, PropertyKind.Spans, PropertyValue.Bytes(record(2, 40))),
                Mutation.Insert(1, 2, 0),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(1, errors.size, "${connection.events}")
        assertEquals(2, errors.single().nodeId)
        assertTrue(errors.single().message.contains("text runs"))
        onNodeWithTag(nodeTestTag(2)).assertIsDisplayed()
    }

    /** Every text layout under a node, the way the node's own semantics hand them out. */
    private fun ComposeUiTest.layoutsIn(nodeId: Int): List<TextLayoutResult> {
        val found = mutableListOf<TextLayoutResult>()
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(found)
            node.children.forEach(::visit)
        }
        visit(onNodeWithTag(nodeTestTag(nodeId), useUnmergedTree = true).fetchSemanticsNode())
        return found
    }

    /**
     * One string with a bold word and a link in it. The bold word is set in a weight the
     * words around it are not, and a press reaches the Host only where the link is, under
     * the handler that run named.
     */
    @Test
    fun fr26_only_the_link_run_reports_a_click_and_bold_draws_bold() = runComposeUiTest {
        val text = "plain bold link"
        val linkHandler = 77L
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(2, WidgetKind.Text),
                Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text(text)),
                Mutation.SetProp(2, PropertyKind.FontSize, PropertyValue.Float(40f)),
                Mutation.SetProp(
                    2,
                    PropertyKind.Spans,
                    PropertyValue.Bytes(record(6, 4, flags = 1) + record(11, 4, handler = linkHandler)),
                ),
                Mutation.Insert(1, 2, 0),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()
        assertEquals(
            emptyList(),
            connection.events.filterIsInstance<HostEvent.ProtocolError>(),
            "the runs fit the string",
        )

        val layout = layoutsIn(2).single { it.layoutInput.text.text == text }
        val drawn = layout.layoutInput.text
        // Only the bold run names a weight, so the words around it keep the text's own.
        val weighted = drawn.spanStyles.filter { it.item.fontWeight != null }
        assertEquals(
            listOf(Triple(6, 10, FontWeight.Bold)),
            weighted.map { Triple(it.start, it.end, it.item.fontWeight) },
            "the weights the string is drawn in",
        )
        // Only the link run can be pressed.
        val links = drawn.getLinkAnnotations(0, drawn.length)
        assertEquals(listOf(11 to 15), links.map { it.start to it.end }, "the ranges that take a press")
        assertTrue(links.single().item is LinkAnnotation.Clickable)

        fun clicks() = connection.events.filterIsInstance<HostEvent.Clicked>()
        fun pressLetter(index: Int) {
            val at: Offset = layout.getBoundingBox(index).center
            onNodeWithTag(nodeTestTag(2)).performMouseInput { click(at) }
            waitForIdle()
        }

        pressLetter(2)
        assertEquals(emptyList(), clicks(), "a press on the plain words reports nothing")
        pressLetter(8)
        assertEquals(emptyList(), clicks(), "a press on the bold word reports nothing")
        pressLetter(13)
        assertEquals(listOf(HostEvent.Clicked(2, linkHandler)), clicks(), "a press on the link reports its handler once")
        pressLetter(8)
        assertEquals(1, clicks().size, "and the bold word still reports nothing after it")
    }
}
