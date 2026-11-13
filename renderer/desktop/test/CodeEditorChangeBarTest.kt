package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.code.ChangeMark
import dev.darkpyonix.composerust.foundation.code.CodeEditorLook
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.changeColumnPx
import dev.darkpyonix.composerust.foundation.code.changeMark
import dev.darkpyonix.composerust.foundation.code.drawGutterAdornments
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.NodeTable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val EDITOR = 5
private const val ROW = 20
private const val MARK_COLUMN = 20
private const val FOLD_LEFT = 150f
private const val ADDED = 39
private const val REMOVED = 40
private const val MODIFIED = 41
private const val DOT = 1
private const val BAR = 2

private val addedInk = Color(0f, 1f, 0f)
private val removedInk = Color(1f, 0f, 0f)
private val modifiedInk = Color(0f, 0f, 1f)
private val plainInk = Color(1f, 0f, 1f)

private fun look(): CodeEditorLook = CodeEditorLook(
    textStyle = TextStyle(),
    container = Color.White,
    gutter = Color.White,
    lineNumber = Color.Black,
    currentLineNumber = Color.Black,
    currentLine = Color.Transparent,
    currentLineBorder = Color.Transparent,
    gutterDivider = Color.Transparent,
    gutterDividerWidth = 1.dp,
    gutterPadding = 8.dp,
    textInset = 8.dp,
    selection = Color.Gray,
    cursor = Color.Black,
    underlineWidth = 1.dp,
    underline = { Color.Red to dev.darkpyonix.composerust.foundation.code.UnderlineShape.Straight },
    paint = { Color.Black },
    ghostTextAlpha = 0.5f,
    lens = Color.Gray,
    tabWidth = 4,
    hoverDelayMillis = 300L,
    commandIsMeta = false,
    gutterMark = { role ->
        when (role) {
            ColorRole.DiffAdded -> addedInk
            ColorRole.DiffRemoved -> removedInk
            ColorRole.DiffModified -> modifiedInk
            else -> plainInk
        }
    },
)

/** Gutter marks as (line, kind, colour code) records, applied through the interpreter. */
private fun modelWith(text: String, vararg marks: Triple<Int, Int, Int>): CodeEditorModel {
    val table = NodeTable()
    table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    val buffer = ByteBuffer.allocate(marks.size * 28).order(ByteOrder.LITTLE_ENDIAN)
    for ((line, kind, color) in marks) {
        buffer.putInt(0).putInt(line).putShort(kind.toShort()).putShort(color.toShort()).putLong(0L).putInt(0).putInt(0)
    }
    table.apply(Mutation.SetProp(EDITOR, PropertyKind.GutterDecorations, PropertyValue.Bytes(buffer.array())))
    return table.node(EDITOR)!!.code!!
}

private val lines = (0 until 6).joinToString("\n") { "l$it" }

@OptIn(ExperimentalTestApi::class)
class CodeEditorChangeBarTest {
    /** Draws the gutter adornments of the first [rows] lines into a bitmap, one row per line. */
    private fun render(model: CodeEditorModel, rows: Int = 6): androidx.compose.ui.graphics.PixelMap {
        var measurer: TextMeasurer? = null
        val image = ImageBitmap(200, ROW * rows)
        runComposeUiTest {
            setContent { measurer = rememberTextMeasurer() }
            waitForIdle()
        }
        val look = look()
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(200f, ROW * rows.toFloat())) {
            for (line in 0 until rows) {
                drawGutterAdornments(
                    model, line, line * ROW.toFloat(), ROW,
                    if (model.hasOrdinaryMarks) MARK_COLUMN else 0,
                    FOLD_LEFT, 0, with(Density(1f)) { changeColumnPx(model, look) }, measurer!!, TextStyle(), look,
                )
            }
        }
        return image.toPixelMap()
    }

    /** The bar starts at the fold column's left edge less 4dp and 12dp, and is 4 pixels wide. */
    private val barLeft = (FOLD_LEFT - 16).toInt()

    /** Criterion 16.1: four dp wide, right of the numbers, the full row, and neighbours meet. */
    @Test
    fun fr38_change_bar_is_four_dp_wide_right_of_the_numbers() {
        val model = modelWith(lines, Triple(1, BAR, ADDED), Triple(2, BAR, ADDED))
        val pixels = render(model)
        for (y in ROW until 3 * ROW) {
            assertEquals(addedInk, pixels[barLeft, y], "left edge at $y")
            assertEquals(addedInk, pixels[barLeft + 3, y], "right edge at $y")
            assertNotEquals(addedInk, pixels[barLeft - 1, y])
            assertNotEquals(addedInk, pixels[barLeft + 4, y])
        }
        // Nothing above the first changed line or below the last.
        assertNotEquals(addedInk, pixels[barLeft, ROW - 1])
        assertNotEquals(addedInk, pixels[barLeft, 3 * ROW])
    }

    /** Criterion 16.3: one bar per line, the strongest of the changes on it. */
    @Test
    fun fr38_change_bars_priority_removed_modified_added() {
        val model = modelWith(
            lines,
            Triple(1, BAR, ADDED), Triple(1, BAR, MODIFIED), Triple(1, BAR, REMOVED),
            Triple(2, BAR, ADDED), Triple(2, BAR, MODIFIED),
        )
        assertEquals(ChangeMark.Removed, model.changeBarAt(1)!!.changeMark())
        assertEquals(ChangeMark.Modified, model.changeBarAt(2)!!.changeMark())
        // The ones that lost stay in the model.
        assertEquals(5, model.gutterMarks.size)
        val pixels = render(model)
        assertEquals(removedInk, pixels[barLeft + 1, ROW + 5])
        assertEquals(modifiedInk, pixels[barLeft + 1, 2 * ROW + 5])
    }

    /** Criterion 16.4: the bar goes where its line goes and leaves with it. */
    @Test
    fun fr38_change_bar_follows_edits() {
        val model = modelWith(lines, Triple(3, BAR, MODIFIED), Triple(4, BAR, ADDED))
        model.edit(CodePosition(0, 0), CodePosition(0, 0), "x\ny\n", EditOrigin.Reader)
        assertEquals(ChangeMark.Modified, model.changeBarAt(5)!!.changeMark())
        assertNull(model.changeBarAt(3))
        // Deleting the changed line takes its bar, and the other one closes up.
        model.edit(CodePosition(5, 0), CodePosition(6, 0), "", EditOrigin.Reader)
        assertEquals(ChangeMark.Added, model.changeBarAt(5)!!.changeMark())
        assertEquals(mapOf(ChangeMark.Added to listOf(5)), model.changeLines())
    }

    /** Criterion 16.2: a dot and a plain bar stay in the mark column beside the change bars. */
    @Test
    fun fr38_other_gutter_marks_are_unchanged_next_to_change_bars() {
        val model = modelWith(
            lines,
            Triple(0, DOT, 0), Triple(1, BAR, 0), Triple(1, BAR, ADDED), Triple(2, BAR, MODIFIED),
        )
        val pixels = render(model)
        // The dot is centred in the mark column; the plain bar is centred in it, 0.2 of a row wide.
        assertEquals(plainInk, pixels[MARK_COLUMN / 2, ROW / 2])
        assertEquals(plainInk, pixels[MARK_COLUMN / 2, ROW + 5])
        assertEquals(addedInk, pixels[barLeft + 1, ROW + 5])
        assertEquals(modifiedInk, pixels[barLeft + 1, 2 * ROW + 5])
        // A model without change bars reserves no room for them.
        val plain = modelWith(lines, Triple(0, DOT, 0))
        assertEquals(false, plain.hasChangeBars)
        assertEquals(true, model.hasChangeBars)
    }

    private val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /**
     * Bytes this thread allocates per call of the gutter drawing for one line, after a
     * warm-up, averaged over many calls.
     */
    private fun bytesPerLine(model: CodeEditorModel): Long {
        var measurer: TextMeasurer? = null
        runComposeUiTest {
            setContent { measurer = rememberTextMeasurer() }
            waitForIdle()
        }
        val look = look()
        val density = Density(1f)
        val style = TextStyle()
        val image = ImageBitmap(200, ROW * 6)
        var bytes = 0L
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(200f, ROW * 6f)) {
            val markColumn = if (model.hasOrdinaryMarks) MARK_COLUMN else 0
            fun pass() {
                val changeColumn = changeColumnPx(model, look)
                for (line in 0 until 6) {
                    drawGutterAdornments(model, line, line * ROW.toFloat(), ROW, markColumn, FOLD_LEFT, 0, changeColumn, measurer!!, style, look)
                }
            }
            repeat(2_000) { pass() }
            val before = threads.currentThreadAllocatedBytes
            repeat(10_000) { pass() }
            bytes = (threads.currentThreadAllocatedBytes - before) / (10_000L * 6)
        }
        return bytes
    }

    /**
     * Drawing the gutter of a line allocates nothing the editor keeps up. Mark lookups walk
     * the mark list by index, so the only bytes left are the temporaries of the drawing
     * calls themselves (offsets and sizes the JIT may not elide). The ceiling is 256 bytes
     * per line, which a per-line list or iterator would exceed several times over.
     */
    @Test
    fun fr38_gutter_draw_does_not_allocate_without_change_bars() {
        val model = modelWith(lines, Triple(1, DOT, 0), Triple(3, BAR, 0))
        assertEquals(false, model.hasChangeBars)
        val bytes = bytesPerLine(model)
        kotlin.test.assertTrue(bytes < 256, "allocated $bytes bytes per line")
    }

    @Test
    fun fr38_gutter_draw_does_not_allocate_with_change_bars() {
        val model = modelWith(lines, Triple(1, BAR, ADDED), Triple(2, BAR, MODIFIED), Triple(2, BAR, REMOVED))
        assertEquals(true, model.hasChangeBars)
        val bytes = bytesPerLine(model)
        kotlin.test.assertTrue(bytes < 256, "allocated $bytes bytes per line")
    }
}
