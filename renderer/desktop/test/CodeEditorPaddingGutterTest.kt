package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.foundation.contentPaddingDp
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
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
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROOT = 1
private const val EDITOR = 2

/** The wire tags of the roles the current-line test names. */
private const val ON_SURFACE_TAG = 6
private const val SURFACE_VARIANT_TAG = 7

/** A gutter mark that is a change bar, in the added colour. */
private const val BAR = 2
private const val DIFF_ADDED_TAG = 39

private fun float(value: Float) = PropertyValue.Float(value)

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** The content padding as the Rust side writes it: top, then bottom, each an f32 of dp. */
private fun padding(top: Float, bottom: Float): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(top).putFloat(bottom).array()

/** A colour record of six role tags: container, gutter, line number, current number, current line, current gutter. */
private fun colourRecord(currentLineNumber: Int = 0, currentLine: Int = 0, currentLineGutter: Int = 0): ByteArray =
    ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0)
        .putShort(0)
        .putShort(0)
        .putShort(currentLineNumber.toShort())
        .putShort(currentLine.toShort())
        .putShort(currentLineGutter.toShort())
        .array()

/** One fold record: version, first line, last line, folded. */
private fun fold(start: Int, end: Int): ByteArray =
    ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(start).putInt(end).putInt(0).array()

/** One change bar on [line]: version, line, kind, colour, id, glyph, reserved. */
private fun changeBar(line: Int): ByteArray =
    ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0).putInt(line).putShort(BAR.toShort()).putShort(DIFF_ADDED_TAG.toShort())
        .putLong(0L).putInt(0).putInt(0)
        .array()

/** Thirty short lines, enough that the editor scrolls at 120dp. */
private val DOCUMENT = (0 until 30).joinToString("\n") { "XXXX" }

/**
 * The rows of a picture that hold ink between two columns, as the first and the last of
 * them. A pixel is ink when it is not the colour of [quietColumn] on its own row, so a band
 * that runs the width of a row is not mistaken for a glyph.
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

/** The rightmost column of [rows] whose pixel differs from [quietColumn]'s on the same row. */
private fun lastInkColumn(pixels: PixelMap, rows: IntRange, quietColumn: Int): Int {
    var last = -1
    for (y in rows) {
        val quiet = pixels[quietColumn, y]
        for (x in 0 until quietColumn) if (pixels[x, y] != quiet && x > last) last = x
    }
    return last
}

private fun Color.distance(other: Color): Float =
    maxOf(abs(red - other.red), abs(green - other.green), abs(blue - other.blue))

/**
 * Criterion 29 of the code editor: content padding that scrolls with the document, gutter
 * columns that stay whether or not a file folds or changes, and the current line's band
 * carried across the gutter. Each runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorPaddingGutterTest {
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

    private fun editor(vararg properties: Pair<PropertyKind, PropertyValue>, text: String = DOCUMENT): List<Mutation> = buildList {
        add(theme())
        add(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        add(Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(300f, 120f)))
        add(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
        add(Mutation.SetProp(EDITOR, PropertyKind.FontSize, float(13f)))
        add(Mutation.SetProp(EDITOR, PropertyKind.LineHeight, float(20f)))
        add(Mutation.SetProp(EDITOR, PropertyKind.GutterMinWidthDp, float(56f)))
        add(Mutation.SetProp(EDITOR, PropertyKind.GutterPaddingDp, float(12f)))
        add(Mutation.SetProp(EDITOR, PropertyKind.TextInsetDp, float(8f)))
        properties.forEach { (kind, value) -> add(Mutation.SetProp(EDITOR, kind, value)) }
    }

    private fun FakeHostConnection.protocolErrors() = events.filterIsInstance<HostEvent.ProtocolError>()

    private fun ComposeUiTest.capture(): PixelMap = onNodeWithTag(nodeTestTag(EDITOR)).captureToImage().toPixelMap()

    private fun paths(): List<CodeEditorPath> {
        installDrawnCodeEditor()
        return listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)
    }

    /**
     * Criterion 29.1. With 8dp of top padding the first line is drawn 8dp lower than without
     * it, and scrolled 8dp it is back where an editor without padding draws it, at the top of
     * the view. The gutter still fills the view above the first line.
     */
    @Test
    fun fr38_editor_content_padding_scrolls_with_document() {
        for (path in paths()) {
            defaultCodeEditorPath = path
            fun firstLine(pixels: PixelMap): Int =
                assertNotNull(inkRows(pixels, 62..110, pixels.width - 4, 0 until pixels.height), "$path: no code was drawn").first

            var plain = 0
            runComposeUiTest {
                val connection = FakeHostConnection(editor())
                show(connection)
                assertEquals(emptyList(), connection.protocolErrors(), "$path")
                plain = firstLine(capture())
            }
            runComposeUiTest {
                val connection = FakeHostConnection(
                    editor(PropertyKind.ContentPaddingDp to PropertyValue.Bytes(padding(8f, 0f))),
                )
                val host = show(connection)
                assertEquals(emptyList(), connection.protocolErrors(), "$path")
                val padded = capture()
                assertTrue(abs(firstLine(padded) - (plain + 8)) <= 1, "$path: the first line starts at ${firstLine(padded)}, not 8 below $plain")
                // The gutter fills the view, the padding above the first line included.
                assertEquals(padded[2, padded.height - 2], padded[2, 2], "$path: the gutter does not reach the top of the view")

                connection.scheduleFrame(listOf(Mutation.SetScrollOffset(EDITOR, 8.0)))
                host().renderFrame(0L)
                waitForIdle()
                val scrolled = capture()
                assertTrue(abs(firstLine(scrolled) - plain) <= 1, "$path: scrolled 8dp the first line starts at ${firstLine(scrolled)}, not $plain")
            }
        }
    }

    /**
     * Criterion 29.2. With the gutter's columns fixed, a file that folds and has a change bar
     * and a file with neither start their text at the same x. Without it they do not, which
     * shows the property is what holds them.
     */
    @Test
    fun fr38_editor_fixed_gutter_columns_keep_text_start_constant() {
        for (path in paths()) {
            defaultCodeEditorPath = path
            fun textEnd(fixed: Boolean, adorned: Boolean): Int {
                var end = -1
                runComposeUiTest {
                    val properties = buildList {
                        if (fixed) add(PropertyKind.FixedGutterColumns to PropertyValue.Bool(true))
                        if (adorned) {
                            add(PropertyKind.Folds to PropertyValue.Bytes(fold(0, 2)))
                            add(PropertyKind.GutterDecorations to PropertyValue.Bytes(changeBar(0)))
                        }
                    }
                    val connection = FakeHostConnection(editor(*properties.toTypedArray(), text = "X\nX\nX\nX"))
                    show(connection)
                    assertEquals(emptyList(), connection.protocolErrors(), "$path")
                    val pixels = capture()
                    // The rightmost ink on the first row is the right edge of its one glyph.
                    end = lastInkColumn(pixels, 2 until 18, pixels.width - 4)
                }
                return end
            }

            val bare = textEnd(fixed = true, adorned = false)
            val adorned = textEnd(fixed = true, adorned = true)
            assertTrue(bare > 0, "$path: no text was drawn")
            assertEquals(bare, adorned, "$path: the text moved with the file's folds and changes")
            assertNotEquals(
                textEnd(fixed = false, adorned = false),
                textEnd(fixed = false, adorned = true),
                "$path: without fixed columns the text should move, so this test would prove nothing",
            )
        }
    }

    /**
     * Criterion 29.3. With the current line in SurfaceVariant across the gutter and its number
     * in OnSurface, a gutter pixel on the caret's row is SurfaceVariant and one on another row
     * is not, and the caret line's number is drawn in OnSurface over it.
     */
    @Test
    fun fr38_editor_current_line_gutter_and_number_colors() {
        fun roleColor(role: ColorRole): Color {
            var seen = Color.Unspecified
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        theme(),
                        Mutation.Create(ROOT, WidgetKind.Box),
                        Mutation.SetModifier(ROOT, 0, ProtocolModifier.Size(20f, 20f)),
                        Mutation.SetModifier(ROOT, 1, ProtocolModifier.Background(Paint.Role(role))),
                    ),
                )
                show(connection)
                seen = onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()[10, 10]
            }
            return seen
        }
        val surfaceVariant = roleColor(ColorRole.SurfaceVariant)
        val onSurface = roleColor(ColorRole.OnSurface)

        for (path in paths()) {
            defaultCodeEditorPath = path
            var plainGutter = Color.Unspecified
            runComposeUiTest {
                val connection = FakeHostConnection(editor())
                show(connection)
                plainGutter = capture()[2, 10]
            }
            assertNotEquals(surfaceVariant, plainGutter, "$path: the design system's gutter is already SurfaceVariant")

            runComposeUiTest {
                val connection = FakeHostConnection(
                    editor(
                        PropertyKind.CodeEditorColors to PropertyValue.Bytes(
                            colourRecord(
                                currentLineNumber = ON_SURFACE_TAG,
                                currentLine = SURFACE_VARIANT_TAG,
                                currentLineGutter = SURFACE_VARIANT_TAG,
                            ),
                        ),
                    ),
                )
                show(connection)
                assertEquals(emptyList(), connection.protocolErrors(), "$path")
                val pixels = capture()
                // The caret is on the first line, rows 0 to 19.
                assertEquals(surfaceVariant, pixels[2, 10], "$path: the caret row's gutter")
                assertEquals(plainGutter, pixels[2, 50], "$path: another row's gutter")
                // The number sits right-aligned in the 56dp column with 12dp of padding.
                var closest = Float.MAX_VALUE
                for (y in 0 until 20) for (x in 14..45) closest = minOf(closest, pixels[x, y].distance(onSurface))
                assertTrue(closest < 0.1f, "$path: the caret line's number is not drawn in OnSurface ($closest away)")
            }
        }

        // Unset, the gutter keeps its colour on the caret's row.
        runComposeUiTest {
            val connection = FakeHostConnection(
                editor(PropertyKind.CodeEditorColors to PropertyValue.Bytes(colourRecord(currentLine = SURFACE_VARIANT_TAG))),
            )
            show(connection)
            val pixels = capture()
            assertEquals(pixels[2, 50], pixels[2, 10], "the gutter took a colour nobody gave it")
        }
    }

    /** Padding that is not two lengths of zero or more is reported once and left out. */
    @Test
    fun fr38_editor_content_padding_that_cannot_be_read_is_reported() {
        assertEquals(8f to 0f, contentPaddingDp(padding(8f, 0f)))
        assertNull(contentPaddingDp(padding(-1f, 0f)))
        assertNull(contentPaddingDp(padding(0f, Float.NaN)))
        assertNull(contentPaddingDp(ByteArray(4)))

        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.drainErrors()
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.ContentPaddingDp, PropertyValue.Bytes(padding(-2f, 0f))))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.ContentPaddingDp, PropertyValue.Bytes(ByteArray(7))))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.ContentPaddingDp, PropertyValue.Bytes(padding(8f, 16f))))
        assertEquals(emptyList(), table.drainErrors())
    }

    /** Both properties belong to a code editor and to nothing else. */
    @Test
    fun fr38_editor_padding_and_fixed_gutter_are_properties_of_an_editor() {
        for (property in listOf(PropertyKind.ContentPaddingDp, PropertyKind.FixedGutterColumns)) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, property), "$property")
            assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, property), "$property")
            assertFalse(NodeTable.supportsProperty(WidgetKind.Text, property), "$property")
        }
    }
}
