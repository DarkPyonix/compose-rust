package dioxus.compose.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.foundation.TextRun
import dioxus.compose.foundation.decodeRuns
import dioxus.compose.foundation.runsProblem
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SpanRecords
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
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
    ): ByteArray = ByteBuffer.allocate(SpanRecords.SPAN_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(start)
        .putInt(length)
        .putShort(0)
        .putShort(flags.toShort())
        .putLong(color)
        .putLong(background)
        .putLong(0)
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
            setContent { DioxusContent(rememberDioxusHost(connection)) }
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
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(1, errors.size, "${connection.events}")
        assertEquals(2, errors.single().nodeId)
        assertTrue(errors.single().message.contains("text runs"))
        onNodeWithTag(nodeTestTag(2)).assertIsDisplayed()
    }
}
