package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorLook
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.UnderlineShape
import dev.darkpyonix.composerust.foundation.code.numberColumnPx
import dev.darkpyonix.composerust.protocol.CodeEditorRecords
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROOT = 1
private const val EDITOR = 2
private const val OTHER = 3
private const val ON_METRICS = 61L
private const val ON_SCROLLED = 62L
private const val ON_OTHER_SCROLLED = 63L

/** The wire tags of the two roles the colour test names. */
private const val PRIMARY_TAG = 1
private const val SECONDARY_TAG = 3

private fun float(value: Float) = PropertyValue.Float(value)

/**
 * One colour record as the Rust schema lays it out: a role tag in each of five halves, in
 * the order container, gutter, line number, current line number, current line, then two
 * reserved bytes. Zero leaves a part to the design system.
 */
private fun colourRecord(
    container: Int = 0,
    gutter: Int = 0,
    lineNumber: Int = 0,
    currentLineNumber: Int = 0,
    currentLine: Int = 0,
): ByteArray = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    .putShort(container.toShort())
    .putShort(gutter.toShort())
    .putShort(lineNumber.toShort())
    .putShort(currentLineNumber.toShort())
    .putShort(currentLine.toShort())
    .putShort(0)
    .array()

/** An editor alone on the page, `width` by `height` dp, with the properties the test names. */
private fun page(
    text: String,
    width: Float,
    height: Float,
    vararg properties: Pair<PropertyKind, PropertyValue>,
): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
    add(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    add(Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(width, height)))
    add(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    properties.forEach { (kind, value) -> add(Mutation.SetProp(EDITOR, kind, value)) }
}

/** A surface look with nothing of a design system in it, for the functions that only need numbers. */
private fun plainLook(lineNumbers: Boolean = true, gutterMinWidth: Float = 0f, gutterPadding: Float = 8f) =
    CodeEditorLook(
        textStyle = TextStyle(),
        container = Color.White,
        gutter = Color.White,
        lineNumber = Color.Black,
        currentLineNumber = Color.Black,
        currentLine = Color.Transparent,
        currentLineBorder = Color.Transparent,
        gutterDivider = Color.Transparent,
        gutterDividerWidth = 1.dp,
        gutterPadding = gutterPadding.dp,
        textInset = 8.dp,
        selection = Color.Gray,
        cursor = Color.Black,
        underlineWidth = 1.dp,
        underline = { Color.Red to UnderlineShape.Straight },
        paint = { Color.Black },
        ghostTextAlpha = 0.5f,
        lens = Color.Gray,
        tabWidth = 4,
        hoverDelayMillis = 300L,
        commandIsMeta = false,
        lineNumbers = lineNumbers,
        gutterMinWidth = gutterMinWidth.dp,
    )

/**
 * The rows of a picture that hold ink between two columns, as the first and the last of
 * them. A pixel is ink when it is not the colour of [quietColumn] on its own row, so a band
 * or a rule that runs the width of a row is not mistaken for a glyph.
 */
private fun inkRows(pixels: PixelMap, columns: IntRange, quietColumn: Int, rows: IntRange): IntRange? {
    var first = -1
    var last = -1
    for (y in rows) {
        val quiet = pixels[quietColumn, y]
        if (columns.any { x -> pixels[x, y] != quiet }) {
            if (first < 0) first = y
            last = y
        }
    }
    return if (first < 0) null else first..last
}

/**
 * Criterion 18 of the code editor: the type and the row height an application gives it, a
 * gutter it can space or switch off, colours for its own parts, the row height it reports
 * and a scroll offset that can be read from one editor and given to another.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorLookTest {
    private fun ComposeUiTest.show(connection: FakeHostConnection, density: Float): () -> ComposeRustHost {
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        return { host }
    }

    private fun FakeHostConnection.protocolErrors() = events.filterIsInstance<HostEvent.ProtocolError>()

    /** The picture of an editor drawn at one pixel per dp with the given size and row height. */
    private fun numbered(fontSize: Float, lineHeight: Float): PixelMap {
        lateinit var pixels: PixelMap
        runComposeUiTest {
            val connection = FakeHostConnection(
                page(
                    "1",
                    300f,
                    80f,
                    PropertyKind.FontSize to float(fontSize),
                    PropertyKind.LineHeight to float(lineHeight),
                    PropertyKind.GutterMinWidthDp to float(56f),
                    PropertyKind.GutterPaddingDp to float(12f),
                    PropertyKind.TextInsetDp to float(8f),
                ),
            )
            show(connection, 1f)
            assertEquals(emptyList(), connection.protocolErrors())
            pixels = onNodeWithTag(nodeTestTag(EDITOR)).captureToImage().toPixelMap()
        }
        return pixels
    }

    /**
     * Criterion 18.1. With a size of 13 and a row height of 20 a row is 20dp tall, and the
     * line number is the size of the code: the document is the one character `1`, so the
     * number beside it is the same glyph, and the two cover the same rows of pixels.
     */
    @Test
    fun fr38_editor_type_and_line_height() {
        runComposeUiTest {
            val connection = FakeHostConnection(
                page(
                    "1\n2\n3",
                    300f,
                    120f,
                    PropertyKind.FontSize to float(13f),
                    PropertyKind.LineHeight to float(20f),
                ),
            )
            show(connection, 2f)
            assertEquals(emptyList(), connection.protocolErrors())
            val layout = drawnLayout(EDITOR)
            assertEquals(13.sp, layout.layoutInput.style.fontSize)
            // 20dp at two pixels per dp, on every row.
            for (line in 0 until 3) {
                assertEquals(40f, layout.getLineBottom(line) - layout.getLineTop(line), "row $line")
            }
        }

        // The number column is 56 wide with 12 of padding, so the number ends at 44 and
        // the first 12 columns are empty. The code starts 8 after the gutter and its rule.
        val small = numbered(13f, 20f)
        val number = assertNotNull(inkRows(small, 14..45, 2, 0 until 20), "no line number was drawn")
        val code = assertNotNull(inkRows(small, 62..110, small.width - 4, 0 until 20), "no code was drawn")
        assertTrue(abs(number.first - code.first) <= 1, "the number starts at ${number.first}, the code at ${code.first}")
        assertTrue(abs(number.last - code.last) <= 1, "the number ends at ${number.last}, the code at ${code.last}")
        // Nothing of the first row is drawn below it: the row is 20 tall and no taller.
        assertNull(inkRows(small, 14..45, 2, 20 until 80))

        // Twice the size is twice the number, so the number follows the size it was given
        // and is not a fixed one that happens to match.
        val large = numbered(26f, 40f)
        val largeNumber = assertNotNull(inkRows(large, 14..45, 2, 0 until 40))
        val smallHeight = number.last - number.first + 1
        val largeHeight = largeNumber.last - largeNumber.first + 1
        assertTrue(largeHeight >= smallHeight * 3 / 2, "a 26sp number is $largeHeight rows, a 13sp one $smallHeight")
    }

    /** The type properties an editor takes are the role, the size and the row height, and a font list. */
    @Test
    fun fr38_editor_type_and_line_height_are_properties_of_an_editor() {
        for (property in listOf(
            PropertyKind.TypeRole,
            PropertyKind.FontSize,
            PropertyKind.LineHeight,
            PropertyKind.Font,
            PropertyKind.LineNumbers,
            PropertyKind.GutterMinWidthDp,
            PropertyKind.GutterPaddingDp,
            PropertyKind.TextInsetDp,
            PropertyKind.CodeEditorColors,
            PropertyKind.OnCodeMetrics,
            PropertyKind.OnCodeScrolled,
        )) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, property), "$property")
        }
        for (property in listOf(
            PropertyKind.LineNumbers,
            PropertyKind.GutterMinWidthDp,
            PropertyKind.GutterPaddingDp,
            PropertyKind.TextInsetDp,
            PropertyKind.CodeEditorColors,
            PropertyKind.OnCodeMetrics,
            PropertyKind.OnCodeScrolled,
        )) {
            assertFalse(NodeTable.supportsProperty(WidgetKind.Text, property), "$property")
            assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, property), "$property")
        }
        // What an editor does not take stays refused.
        assertFalse(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.TextAlign))
        assertFalse(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.MaxLines))
    }

    /**
     * Criterion 18.2. Without line numbers there is no number column and the code starts
     * at the text inset. With them the column is at least as wide as the minimum.
     */
    @Test
    fun fr38_editor_without_line_numbers() {
        fun codeLeft(vararg properties: Pair<PropertyKind, PropertyValue>): Float {
            var left = 0f
            runComposeUiTest {
                val connection = FakeHostConnection(page("fn main() {}\n", 300f, 80f, *properties))
                show(connection, 1f)
                assertEquals(emptyList(), connection.protocolErrors())
                val editor = onNodeWithTag(nodeTestTag(EDITOR)).fetchSemanticsNode().positionInRoot.x
                val field = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .positionInRoot
                    .x
                left = field - editor
            }
            return left
        }

        val bare = codeLeft(
            PropertyKind.LineNumbers to PropertyValue.Bool(false),
            PropertyKind.TextInsetDp to float(7f),
        )
        assertEquals(7f, bare, "the code starts at the text inset when there is no number column")

        val beside = codeLeft(
            PropertyKind.GutterMinWidthDp to float(56f),
            PropertyKind.GutterPaddingDp to float(12f),
            PropertyKind.TextInsetDp to float(7f),
        )
        assertTrue(beside >= 56f + 7f, "the code starts at $beside, short of a 56dp number column and its inset")

        // The column itself, without a design system: nothing when the numbers are off,
        // the minimum while the digits fit inside it, and the digits once they do not.
        with(Density(1f)) {
            assertEquals(0, numberColumnPx(plainLook(lineNumbers = false, gutterMinWidth = 56f), 3, 8))
            assertEquals(56 + 1, numberColumnPx(plainLook(gutterMinWidth = 56f, gutterPadding = 12f), 2, 8))
            assertEquals(6 * 8 + 24 + 1, numberColumnPx(plainLook(gutterMinWidth = 56f, gutterPadding = 12f), 6, 8))
            assertEquals(2 * 8 + 16 + 1, numberColumnPx(plainLook(), 2, 8))
        }
    }

    /**
     * Criterion 18.3. The container and the gutter take the roles the record names, and a
     * part the record leaves at zero keeps the design system's colour.
     */
    @Test
    fun fr38_editor_colors_override_the_design_system() {
        /** The container's pixel and the gutter's, away from the text, the numbers and the corners. */
        fun parts(vararg properties: Pair<PropertyKind, PropertyValue>): Pair<Color, Color> {
            lateinit var seen: Pair<Color, Color>
            runComposeUiTest {
                val connection = FakeHostConnection(page("a", 300f, 80f, *properties))
                show(connection, 1f)
                assertEquals(emptyList(), connection.protocolErrors())
                val pixels = onNodeWithTag(nodeTestTag(EDITOR)).captureToImage().toPixelMap()
                seen = pixels[pixels.width - 10, pixels.height - 10] to pixels[2, pixels.height - 10]
            }
            return seen
        }

        /** What the theme draws a role as, read from a box painted with it. */
        fun roleColor(role: ColorRole): Color {
            var seen = Color.Unspecified
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
                        Mutation.Create(ROOT, WidgetKind.Box),
                        Mutation.SetModifier(ROOT, 0, ProtocolModifier.Size(20f, 20f)),
                        Mutation.SetModifier(ROOT, 1, ProtocolModifier.Background(Paint.Role(role))),
                    ),
                )
                show(connection, 1f)
                seen = onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()[10, 10]
            }
            return seen
        }

        val primary = roleColor(ColorRole.Primary)
        val secondary = roleColor(ColorRole.Secondary)
        assertNotEquals(primary, secondary)

        val (plainContainer, plainGutter) = parts()
        assertNotEquals(primary, plainContainer, "the design system's container is already the primary colour")
        assertNotEquals(secondary, plainGutter, "the design system's gutter is already the secondary colour")

        val (container, gutter) = parts(
            PropertyKind.CodeEditorColors to
                PropertyValue.Bytes(colourRecord(container = PRIMARY_TAG, gutter = SECONDARY_TAG)),
        )
        assertEquals(primary, container)
        assertEquals(secondary, gutter)

        // Only the container named: the gutter is the design system's, as if nothing was sent.
        val (onlyContainer, untouchedGutter) = parts(
            PropertyKind.CodeEditorColors to PropertyValue.Bytes(colourRecord(container = PRIMARY_TAG)),
        )
        assertEquals(primary, onlyContainer)
        assertEquals(plainGutter, untouchedGutter)

        // The decoder reads the five halves at the offsets the schema gives them.
        val errors = mutableListOf<String>()
        val record = CodeEditorRecords.decodeCodeEditorColors(
            colourRecord(PRIMARY_TAG, SECONDARY_TAG, 0, PRIMARY_TAG, SECONDARY_TAG),
            errors::add,
        ).single()
        assertEquals(emptyList(), errors)
        assertEquals(ColorRole.Primary, record.container)
        assertEquals(ColorRole.Secondary, record.gutter)
        assertNull(record.lineNumber)
        assertEquals(ColorRole.Primary, record.currentLineNumber)
        assertEquals(ColorRole.Secondary, record.currentLine)
    }

    /** A colour record that names a role nobody knows, or is cut short, is reported and draws as the design system. */
    @Test
    fun fr38_editor_colors_that_cannot_be_read_are_reported() {
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.drainErrors()
        table.apply(
            Mutation.SetProp(EDITOR, PropertyKind.CodeEditorColors, PropertyValue.Bytes(colourRecord(container = 60_000))),
        )
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.CodeEditorColors, PropertyValue.Bytes(ByteArray(7))))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        table.apply(
            Mutation.SetProp(EDITOR, PropertyKind.CodeEditorColors, PropertyValue.Bytes(colourRecord(gutter = PRIMARY_TAG))),
        )
        assertEquals(emptyList(), table.drainErrors())
    }

    /**
     * Criterion 18.4. The row height the editor reports is the height it draws a row with:
     * once when it is first drawn, and again when the height changes.
     */
    @Test
    fun fr38_editor_reports_line_height() = runComposeUiTest {
        val connection = FakeHostConnection(
            page(
                "one\ntwo\nthree",
                300f,
                120f,
                PropertyKind.OnCodeMetrics to PropertyValue.Integer(ON_METRICS),
            ),
        )
        val host = show(connection, 2f)
        fun reported() = connection.nodeEvents.filterIsInstance<HostEvent.CodeMetrics>()
        fun drawn() = drawnLayout(EDITOR).let { it.getLineBottom(1) - it.getLineTop(1) }

        // The design system's own row height, whatever it is: said once, and the one drawn.
        assertEquals(1, reported().size, "the row height is said once when the editor is first drawn")
        assertEquals(EDITOR, reported().single().nodeId)
        assertEquals(ON_METRICS, reported().single().handlerId)
        assertEquals(drawn().toDouble(), reported().single().value)

        // The application sets 30dp: one more report, of 60 pixels, which is what is drawn.
        connection.scheduleFrame(listOf(Mutation.SetProp(EDITOR, PropertyKind.LineHeight, float(30f))))
        host().renderFrame(0L)
        waitForIdle()
        assertEquals(2, reported().size, "a new row height is said once")
        assertEquals(60.0, reported().last().value)
        assertEquals(60f, drawn())

        // A frame in which nothing about the rows changed says nothing.
        connection.scheduleFrame(listOf(Mutation.SetProp(EDITOR, PropertyKind.TextInsetDp, float(9f))))
        host().renderFrame(0L)
        waitForIdle()
        assertEquals(2, reported().size)

        // The event is the header and one double.
        val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, Protocol.encodeEvent(reported().last(), buffer))
    }

    /** The surface says the row height and the offset every frame; the Host hears each change once. */
    @Test
    fun fr38_editor_reports_line_height_and_offset_once_per_change() {
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("a\nb\nc")))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnCodeMetrics, PropertyValue.Integer(ON_METRICS)))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnCodeScrolled, PropertyValue.Integer(ON_SCROLLED)))
        table.drainEvents()
        val model: CodeEditorModel = table.node(EDITOR)!!.code!!

        // The first frame: the row height, and no offset, because an editor starts at the top.
        model.reportGeometry(20, 0)
        assertEquals(listOf<HostEvent>(HostEvent.CodeMetrics(EDITOR, ON_METRICS, 20.0)), table.drainEvents())
        model.reportGeometry(20, 0)
        assertEquals(emptyList(), table.drainEvents())

        // Several steps inside one frame are where the view ended up.
        model.reportGeometry(20, 35)
        model.reportGeometry(20, 47)
        assertEquals(listOf<HostEvent>(HostEvent.CodeScrolled(EDITOR, ON_SCROLLED, 47.0)), table.drainEvents())

        // A scroll through many frames is one report a frame, and a still frame none.
        var offset = 47
        repeat(500) { frame ->
            offset += 13
            model.reportGeometry(20, offset)
            model.reportGeometry(20, offset)
            assertEquals(
                listOf<HostEvent>(HostEvent.CodeScrolled(EDITOR, ON_SCROLLED, offset.toDouble())),
                table.drainEvents(),
                "frame $frame",
            )
            assertEquals(emptyList(), table.drainEvents())
        }

        // A new row height goes before the offset that came with it.
        model.reportGeometry(24, offset + 1)
        assertEquals(
            listOf(
                HostEvent.CodeMetrics(EDITOR, ON_METRICS, 24.0),
                HostEvent.CodeScrolled(EDITOR, ON_SCROLLED, (offset + 1).toDouble()),
            ),
            table.drainEvents(),
        )


        // What nobody listens for is not counted as told: a listener that arrives after
        // the editor was drawn and scrolled hears where things stand, once.
        val late = NodeTable()
        late.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        val lateModel = late.node(EDITOR)!!.code!!
        lateModel.reportGeometry(18, 90)
        assertEquals(emptyList(), late.drainEvents())
        late.apply(Mutation.SetProp(EDITOR, PropertyKind.OnCodeMetrics, PropertyValue.Integer(ON_METRICS)))
        late.apply(Mutation.SetProp(EDITOR, PropertyKind.OnCodeScrolled, PropertyValue.Integer(ON_SCROLLED)))
        assertEquals(
            listOf(
                HostEvent.CodeMetrics(EDITOR, ON_METRICS, 18.0),
                HostEvent.CodeScrolled(EDITOR, ON_SCROLLED, 90.0),
            ),
            late.drainEvents(),
        )
        assertEquals(emptyList(), late.drainEvents())
        // And one that leaves hears nothing more.
        late.apply(Mutation.SetProp(EDITOR, PropertyKind.OnCodeScrolled, PropertyValue.None))
        lateModel.reportGeometry(18, 120)
        assertEquals(emptyList(), late.drainEvents())
    }

    /**
     * Criterion 18.5. Two editors over the same text. The reader scrolls the first, the
     * Host hears its offset and gives it to the second, and the second stands at the same
     * pixel: it reports the same offset and its lines are placed where the first one's are.
     */
    @Test
    fun fr38_editor_scroll_offset_round_trips() = runComposeUiTest {
        val text = (0 until 400).joinToString("\n") { "let line_$it = $it;" }
        val connection = FakeHostConnection(
            buildList {
                add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
                add(Mutation.Create(ROOT, WidgetKind.Column))
                for ((index, id) in listOf(EDITOR, OTHER).withIndex()) {
                    add(Mutation.Create(id, WidgetKind.CodeEditor))
                    add(Mutation.SetModifier(id, 0, ProtocolModifier.Size(300f, 120f)))
                    add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(text)))
                    add(Mutation.Insert(ROOT, id, index))
                }
                add(Mutation.SetProp(EDITOR, PropertyKind.OnCodeScrolled, PropertyValue.Integer(ON_SCROLLED)))
                add(Mutation.SetProp(OTHER, PropertyKind.OnCodeScrolled, PropertyValue.Integer(ON_OTHER_SCROLLED)))
            },
        )
        // The Host of a side-by-side view: what the first editor says, the second is told.
        connection.respondWith { event ->
            if (event is HostEvent.CodeScrolled && event.nodeId == EDITOR) {
                HostResponse(listOf(Mutation.SetScrollOffset(OTHER, event.value)))
            } else {
                HostResponse()
            }
        }
        show(connection, 1f)
        assertEquals(emptyList(), connection.protocolErrors())
        fun scrolled(node: Int) = connection.nodeEvents.filterIsInstance<HostEvent.CodeScrolled>().filter { it.nodeId == node }
        assertEquals(emptyList(), scrolled(EDITOR), "an editor at the top has nothing to say about its offset")

        onNodeWithTag(nodeTestTag(EDITOR)).performMouseInput {
            moveTo(center)
            scroll(6f)
        }
        waitForIdle()

        val first = scrolled(EDITOR)
        assertTrue(first.isNotEmpty(), "scrolling the first editor reports its offset")
        val offset = first.last().value
        assertTrue(offset > 0.0, "the first editor is still at the top")
        assertEquals(ON_SCROLLED, first.last().handlerId)

        // The second editor went where it was told and says so, in the same pixels.
        val second = scrolled(OTHER)
        assertTrue(second.isNotEmpty(), "the second editor did not move")
        assertEquals(offset, second.last().value)
        assertEquals(ON_OTHER_SCROLLED, second.last().handlerId)

        // And its lines are where the first one's are: each field sits the same distance
        // from the top of its own editor, and holds the same lines.
        val fields = onAllNodesWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, fields.size)
        val tops = listOf(EDITOR, OTHER).map { onNodeWithTag(nodeTestTag(it)).fetchSemanticsNode().positionInRoot.y }
        assertEquals(fields[0].positionInRoot.y - tops[0], fields[1].positionInRoot.y - tops[1])
        val pictures = listOf(EDITOR, OTHER).map { onNodeWithTag(nodeTestTag(it)).captureToImage().toPixelMap() }
        for (y in 0 until pictures[0].height step 3) {
            for (x in 0 until pictures[0].width step 3) {
                assertEquals(pictures[0][x, y], pictures[1][x, y], "the two editors differ at $x, $y")
            }
        }
    }

    /**
     * The scroll command through the interpreter: the offset waits for the surface in whole
     * pixels, an offset below zero is the top, and one that is not a number is a protocol
     * error that leaves the editor where it is. The record is 16 bytes on the wire.
     */
    @Test
    fun fr38_editor_scroll_offset_round_trips_through_the_interpreter() {
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.Create(ROOT, WidgetKind.Box))
        table.drainErrors()
        val model = table.node(EDITOR)!!.code!!
        assertNull(model.scrollRequest)

        table.apply(Mutation.SetScrollOffset(EDITOR, 240.0))
        val request = assertNotNull(model.scrollRequest)
        assertEquals(240, request.offsetPx)
        model.scrollDone(request)
        assertNull(model.scrollRequest)

        table.apply(Mutation.SetScrollOffset(EDITOR, -12.5))
        assertEquals(0, model.scrollRequest?.offsetPx)
        assertEquals(emptyList(), table.drainErrors())

        table.apply(Mutation.SetScrollOffset(EDITOR, Double.NaN))
        assertEquals(0, model.scrollRequest?.offsetPx, "a broken offset replaces nothing")
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })

        // Only an editor scrolls this way, and only one that exists.
        table.apply(Mutation.SetScrollOffset(ROOT, 10.0))
        assertEquals(listOf(TableError.UNSUPPORTED_PROPERTY), table.drainErrors().map { it.code })
        table.apply(Mutation.SetScrollOffset(99, 10.0))
        assertEquals(listOf(TableError.UNKNOWN_NODE), table.drainErrors().map { it.code })

        // The bytes the Rust encoder writes: tag 30, 16 bytes, the node, then the double.
        val batch = ByteBuffer.allocate(12 + 16).order(ByteOrder.LITTLE_ENDIAN)
        batch.putShort(0).putShort(12).putInt(12 + 16).putInt(1)
        batch.putShort(30).putShort(16).putInt(EDITOR).putDouble(1234.5)
        batch.flip()
        val decoded = mutableListOf<Mutation>()
        Protocol.decode(batch, decoded::add)
        assertEquals(listOf<Mutation>(Mutation.SetScrollOffset(EDITOR, 1234.5)), decoded)
    }
}
