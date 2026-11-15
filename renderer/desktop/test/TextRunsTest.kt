package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.darkpyonix.composerust.foundation.TextRun
import dev.darkpyonix.composerust.foundation.decodeRuns
import dev.darkpyonix.composerust.foundation.runsProblem
import dev.darkpyonix.composerust.foundation.textInput
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
        handlerId: Long = 0,
    ): ByteArray = ByteBuffer.allocate(SpanRecords.SPAN_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(start)
        .putInt(length)
        .putShort(0)
        .putShort(flags.toShort())
        .putLong(color)
        .putLong(background)
        .putLong(handlerId)
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

    private fun drawnLayout(nodeTag: String, rule: androidx.compose.ui.test.ComposeUiTest): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val ok = rule.onNodeWithTag(nodeTag)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        assertEquals(true, ok)
        return layouts.single()
    }

    /**
     * A bold run and a link run inside one `Text` draw differently, and pressing each run
     * reports a click only for the link.
     */
    @OptIn(ExperimentalTextApi::class)
    @Test
    fun fr26_bold_and_link_runs_draw_differently_and_only_the_link_reports_a_click() = runComposeUiTest {
        val black = 0xFF000000.toInt()
        // "plain WWWW link":
        //   0..5   -> "plain" (plain)
        //   6..10  -> "WWWW"  (bold)
        //   11..15 -> "link"  (underlined link with handler 7)
        val runs = record(6, 4, flags = 1) +
            record(11, 4, flags = 4, handlerId = 7L)
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(2, WidgetKind.Text),
                Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("plain WWWW link")),
                Mutation.SetProp(2, PropertyKind.Color, PropertyValue.Integer(literal(black))),
                Mutation.SetProp(2, PropertyKind.FontSize, PropertyValue.Float(40f)),
                Mutation.SetProp(2, PropertyKind.Spans, PropertyValue.Bytes(runs)),
                Mutation.Create(3, WidgetKind.Text),
                Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("WWWW")),
                Mutation.SetProp(3, PropertyKind.Color, PropertyValue.Integer(literal(black))),
                Mutation.SetProp(3, PropertyKind.FontSize, PropertyValue.Float(40f)),
                Mutation.Insert(1, 2, 0),
                Mutation.Insert(1, 3, 1),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        val layout = drawnLayout(nodeTestTag(2), this)
        val plainLayout = drawnLayout(nodeTestTag(3), this)
        val drawn: AnnotatedString = layout.layoutInput.text

        val weighted = drawn.spanStyles.filter { it.item.fontWeight != null }
        assertEquals(
            listOf(Triple(6, 10, FontWeight.Bold)),
            weighted.map { Triple(it.start, it.end, it.item.fontWeight) },
        )
        val decorated = drawn.spanStyles.filter { it.item.textDecoration != null }
        assertEquals(
            listOf(Triple(11, 15, TextDecoration.Underline)),
            decorated.map { Triple(it.start, it.end, it.item.textDecoration) },
        )
        val links = drawn.getLinkAnnotations(0, drawn.length)
        assertEquals(listOf(11 to 15), links.map { it.start to it.end })

        // Compare the bold "WWWW" against the unstyled "WWWW" below it so removing bold
        // from the renderer fails both the advance width and the ink pixel count.
        val boldWidth = layout.getHorizontalPosition(10, usePrimaryDirection = true) -
            layout.getHorizontalPosition(6, usePrimaryDirection = true)
        val plainWidth = plainLayout.getHorizontalPosition(4, usePrimaryDirection = true) -
            plainLayout.getHorizontalPosition(0, usePrimaryDirection = true)
        val node = onNodeWithTag(nodeTestTag(2))
        val image = node.captureToImage().toAwtImage()
        val plainImage = onNodeWithTag(nodeTestTag(3)).captureToImage().toAwtImage()
        val boldFrom = layout.getBoundingBox(6).left.toInt().coerceIn(0, image.width)
        val boldTo = kotlin.math.ceil(layout.getBoundingBox(9).right).toInt().coerceIn(boldFrom, image.width)
        val boldInk = count(image, black, boldFrom, boldTo)
        val plainInk = count(plainImage, black, 0, plainImage.width)
        assertTrue(
            boldInk > plainInk || boldWidth > plainWidth,
            "the bold run must draw heavier or wider than the plain run (boldInk=$boldInk plainInk=$plainInk boldWidth=$boldWidth plainWidth=$plainWidth)",
        )

        fun pressLetter(offset: Int) {
            val box = layout.getBoundingBox(offset)
            node.performTouchInput { click(box.center) }
            waitForIdle()
        }
        fun clicks(): List<HostEvent.Clicked> = connection.events.filterIsInstance<HostEvent.Clicked>()

        pressLetter(2)
        assertEquals(emptyList(), clicks(), "pressing the plain word reports nothing")

        pressLetter(7)
        assertEquals(emptyList(), clicks(), "pressing the bold word reports nothing")

        pressLetter(13)
        assertEquals(listOf(HostEvent.Clicked(2, 7L)), clicks())

        pressLetter(7)
        assertEquals(1, clicks().size, "pressing the bold word after the link still reports nothing")
    }

    /**
     * A `Text` with no spans carries no `Spans` property in the node table, draws an
     * unannotated string with no span styles or links, and takes `AppendText` on its plain
     * string.
     */
    @OptIn(ExperimentalTextApi::class)
    @Test
    fun fr26_a_text_with_no_spans_keeps_its_record_size_and_path() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(2, WidgetKind.Text),
                Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("hello")),
                Mutation.Insert(1, 2, 0),
            ),
        )
        connection.scheduleFrame(listOf(Mutation.AppendText(2, " world")))
        lateinit var host: dev.darkpyonix.composerust.runtime.ComposeRustHost
        setContent {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
        waitForIdle()

        val initialLayout = drawnLayout(nodeTestTag(2), this)
        assertEquals("hello", initialLayout.layoutInput.text.text)
        assertEquals(emptyList(), initialLayout.layoutInput.text.spanStyles)
        assertEquals(emptyList(), initialLayout.layoutInput.text.getLinkAnnotations(0, 5))

        runOnIdle { host.renderFrame(16_666_667L) }
        waitForIdle()

        val node = assertNotNull(host.table.node(2))
        assertNull(node.property(PropertyKind.Spans))
        val input = node.textInput()
        assertEquals("hello world", input.text)
        assertEquals(emptyList(), input.runs)

        val appendedLayout = drawnLayout(nodeTestTag(2), this)
        assertEquals("hello world", appendedLayout.layoutInput.text.text)
        assertEquals(emptyList(), appendedLayout.layoutInput.text.spanStyles)
        assertEquals(emptyList(), appendedLayout.layoutInput.text.getLinkAnnotations(0, 11))
        assertEquals(
            listOf(AnnotatedString("hello world")),
            onNodeWithTag(nodeTestTag(2)).fetchSemanticsNode().config[SemanticsProperties.Text],
        )
    }
}
