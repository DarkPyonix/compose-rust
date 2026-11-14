package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.DecorationRecord
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val BLAME = "You, 2 days ago"

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** After-line decoration records as the Rust schema lays them out: line, text and role. */
private fun afterLines(vararg decorations: Pair<Int, String>): ByteArray {
    val texts = decorations.map { it.second.encodeToByteArray() }
    val recordsLength = decorations.size * 44
    val buffer = ByteBuffer.allocate(recordsLength + texts.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    var textOffset = recordsLength
    decorations.forEachIndexed { index, (line, _) ->
        buffer.putInt(0)
        buffer.putShort(7)
        buffer.putShort(0)
        buffer.putShort((ColorRole.OnSurfaceVariant.ordinal + 1).toShort())
        buffer.putShort(0)
        buffer.putInt(line).putInt(0).putInt(line).putInt(0)
        buffer.putLong(0L)
        buffer.putInt(textOffset).putInt(texts[index].size)
        textOffset += texts[index].size
    }
    texts.forEach(buffer::put)
    return buffer.array()
}

/** Keeps what a copy produced instead of handing it to the machine's clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class AfterLineClipboard : Clipboard, ClipboardManager {
    var copied: String? = null

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        copied = when (val native = clipEntry?.nativeClipEntry) {
            is String -> native
            is AnnotatedString -> native.text
            is Transferable -> native.getTransferData(DataFlavor.stringFlavor) as? String
            else -> return
        }
    }

    override fun setText(annotatedString: AnnotatedString) {
        copied = annotatedString.text
    }

    override fun getText(): AnnotatedString? = null

    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("after-line-test")
}

private val onMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * Criterion 28 of the code editor: muted text after the end of a line that is not text. It
 * is drawn after the line's last character, the caret and End stop in front of it, a copy
 * of the whole line leaves it out, a press on it puts the caret at the end of the line, and
 * it moves with its line. Runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorAfterLineTest {
    @AfterTest
    fun windowedPath() {
        defaultCodeEditorPath = CodeEditorPath.Windowed
    }

    private var request = 0

    /** Where a caret at [position] is drawn, in root pixels. */
    private fun ComposeUiTest.caretAt(connection: FakeHostConnection, host: () -> ComposeRustHost, position: CodePosition): HostEvent.PositionRect {
        val id = ++request
        connection.scheduleFrame(listOf(Mutation.QueryPositionRect(EDITOR, id, position.line, position.column)))
        host().renderFrame(0L)
        waitForIdle()
        val rect = connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().single { it.request == id }
        assertTrue(rect.found, "$position is on screen")
        return rect
    }

    /** How many pixels along the row of [rect], from just after it to 150 pixels on, are not the row's own colour. */
    private fun ComposeUiTest.inkAfter(rect: HostEvent.PositionRect): Int {
        val pixels = onRoot().captureToImage().toPixelMap()
        val middle = (rect.y + rect.height / 2f).roundToInt()
        val ground = pixels[pixels.width - 30, middle].toArgb()
        var ink = 0
        for (y in (rect.y + 2f).roundToInt() until (rect.y + rect.height - 2f).roundToInt()) {
            for (x in (rect.x + 4f).roundToInt() until minOf(pixels.width - 40, (rect.x + 150f).roundToInt())) {
                val argb = pixels[x, y].toArgb()
                val difference = abs((argb shr 16 and 0xFF) - (ground shr 16 and 0xFF)) +
                    abs((argb shr 8 and 0xFF) - (ground shr 8 and 0xFF)) +
                    abs((argb and 0xFF) - (ground and 0xFF))
                if (difference > 60) ink += 1
            }
        }
        return ink
    }

    /**
     * Criterion 28.1. The decoration's letters are drawn after the end of its line; End
     * stops in front of them, where the caret stood without them; copying the whole line
     * leaves them out; a press on them puts the caret at the end of the line; and a line typed
     * in front of everything takes them down with their line.
     */
    @Test
    fun fr38_after_line_decoration_is_not_text() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val clipboard = AfterLineClipboard()
                val connection = FakeHostConnection(
                    listOf(
                        theme(),
                        Mutation.Create(EDITOR, WidgetKind.CodeEditor),
                        Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(400f, 200f)),
                        Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("alpha\nbeta")),
                    ),
                )
                lateinit var host: ComposeRustHost
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalClipboard provides clipboard,
                        LocalClipboardManager provides clipboard,
                    ) {
                        host = rememberComposeRustHost(connection)
                        ComposeRustContent(host)
                    }
                }
                waitForIdle()
                val current = { host }
                val model: CodeEditorModel = host.table.node(EDITOR)!!.code!!
                val target: SemanticsNodeInteraction = if (path == CodeEditorPath.Drawn) {
                    onNodeWithTag(nodeTestTag(EDITOR)).also { it.performMouseInput { click(Offset(width - 4f, 4f)) } }
                } else {
                    onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
                }
                waitForIdle()

                val endBefore = caretAt(connection, current, CodePosition(0, 5))
                assertTrue(inkAfter(endBefore) < 5, "$path: something is drawn after the line before the decoration")

                connection.scheduleFrame(
                    listOf(Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(afterLines(0 to BLAME)))),
                )
                current().renderFrame(0L)
                waitForIdle()
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val decoration = model.decorations.single()
                assertEquals(DecorationKind.AfterLine, decoration.kind, "$path")
                assertEquals(BLAME, decoration.text, "$path")

                // Drawn after the end of the line, and the end of the line has not moved.
                val endAfter = caretAt(connection, current, CodePosition(0, 5))
                assertEquals(endBefore.x, endAfter.x, "$path: the end of the line moved")
                assertTrue(inkAfter(endAfter) > 20, "$path: nothing is drawn after the line")

                // End stops in front of it.
                connection.scheduleFrame(listOf(Mutation.SetSelection(EDITOR, 0, 0, 0, 0, 0)))
                current().renderFrame(0L)
                waitForIdle()
                target.performKeyInput { pressKey(Key.MoveEnd) }
                waitForIdle()
                assertEquals(CodePosition(0, 5), model.selection.caret, "$path: End did not stop at the end of the line")
                target.performKeyInput { pressKey(Key.DirectionRight) }
                waitForIdle()
                assertEquals(CodePosition(1, 0), model.selection.caret, "$path: Right from the end went into the decoration")

                // Copying the whole line leaves it out.
                connection.scheduleFrame(listOf(Mutation.SetSelection(EDITOR, 0, 0, 0, 1, 0)))
                current().renderFrame(0L)
                waitForIdle()
                target.performKeyInput { withKeyDown(if (onMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) } }
                waitForIdle()
                assertEquals("alpha\n", clipboard.copied, "$path: the copy is not the line's own text")

                // A press on it is a press past the end of the line.
                onRoot().performMouseInput { click(Offset(endAfter.x + 40f, endAfter.y + endAfter.height / 2f)) }
                waitForIdle()
                assertEquals(CodePosition(0, 5), model.selection.caret, "$path: a press on the decoration")

                // It moves with its line.
                connection.scheduleFrame(listOf(Mutation.EditCode(EDITOR, 1, 0, 0, 0, 0, 0, "new\n")))
                current().renderFrame(0L)
                waitForIdle()
                assertEquals(1, model.decorations.single().start.line, "$path: the decoration did not follow its line")
                assertTrue(inkAfter(caretAt(connection, current, CodePosition(1, 5))) > 20, "$path: nothing is drawn after the line it moved to")
            }
        }
    }

    /**
     * The model's part: an after-line decoration is on a line rather than a stretch of it. It
     * follows its line as lines come and go before it, stays as the line is typed on, and
     * goes when its line is deleted.
     */
    @Test
    fun fr38_after_line_decoration_follows_its_line_in_the_model() {
        val model = CodeEditorModel("a\nb\nc")
        model.setDecorations(listOf(DecorationRecord(0, DecorationKind.AfterLine, null, ColorRole.OnSurfaceVariant, 1, 0, 1, 0, 0L, BLAME)))
        assertEquals(emptyList(), model.drainOutput())
        assertEquals(listOf(1), model.afterLineDecorations(1).map { it.start.line })
        model.edit(CodePosition(1, 1), CodePosition(1, 1), "xyz", EditOrigin.Typing)
        assertEquals(1, model.decorations.single().start.line)
        model.edit(CodePosition(0, 0), CodePosition(0, 0), "top\n", EditOrigin.Reader)
        assertEquals(2, model.decorations.single().start.line)
        assertEquals(emptyList(), model.afterLineDecorations(1))
        assertEquals(BLAME, model.afterLineDecorations(2).single().text)
        model.edit(CodePosition(2, 0), CodePosition(3, 0), "", EditOrigin.Reader)
        assertEquals(emptyList(), model.decorations, "the decoration outlived its line")
    }
}
