package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.protocol.ColorScheme
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_CARET = 71L
private const val ON_SCROLLED = 72L

/** Rows are 20 pixels at a density of 1, and the view is ten and a half of them high. */
private const val ROW = 20f
private const val VIEW_HEIGHT = 210f
private const val SHOWN = 10

/** Three times the lines the view shows. */
private val DOCUMENT = (0 until 3 * SHOWN).joinToString("\n") { "line $it" }

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** The content padding as the Rust side writes it: top, then bottom, each an f32 of dp. */
private fun padding(top: Float, bottom: Float): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(top).putFloat(bottom).array()

/**
 * A caret moved by the keyboard stays in view: the view scrolls the least that shows the
 * whole caret line, past the bottom and past the top, and not at all while the caret moves
 * inside it. Runs on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorCaretFollowTest {
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

    private fun editor(vararg extra: Mutation): List<Mutation> = listOf(
        theme(),
        Mutation.Create(EDITOR, WidgetKind.CodeEditor),
        Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(320f, VIEW_HEIGHT)),
        Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(DOCUMENT)),
        Mutation.SetProp(EDITOR, PropertyKind.FontSize, PropertyValue.Float(13f)),
        Mutation.SetProp(EDITOR, PropertyKind.LineHeight, PropertyValue.Float(ROW)),
        Mutation.SetProp(EDITOR, PropertyKind.OnCaretRect, PropertyValue.Integer(ON_CARET)),
        Mutation.SetProp(EDITOR, PropertyKind.OnCodeScrolled, PropertyValue.Integer(ON_SCROLLED)),
    ) + extra

    /**
     * Presses Down from the first line to well past the last line the view shows, then Up
     * back to the first, and checks after every press that the caret is wholly inside the
     * editor. A press that leaves the caret inside the view does not scroll it.
     */
    private fun followsTheCaret(path: CodeEditorPath, vararg extra: Mutation) {
        defaultCodeEditorPath = path
        runComposeUiTest {
            val connection = FakeHostConnection(editor(*extra))
            val host = show(connection)
            assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
            val model = host().table.node(EDITOR)!!.code!!
            val box = onNodeWithTag(nodeTestTag(EDITOR)).getUnclippedBoundsInRoot()
            val boxTop = box.top.value
            val boxBottom = box.bottom.value

            val target: SemanticsNodeInteraction = if (path == CodeEditorPath.Drawn) {
                onNodeWithTag(nodeTestTag(EDITOR)).also { it.performMouseInput { click(Offset(width - 4f, 4f)) } }
            } else {
                onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
            }
            waitForIdle()
            connection.scheduleFrame(listOf(Mutation.SetSelection(EDITOR, 0, 0, 0, 0, 0)))
            host().renderFrame(0L)
            waitForIdle()
            assertEquals(CodePosition(0, 0), model.selection.caret, "$path")

            fun caret() = connection.nodeEvents.filterIsInstance<HostEvent.CaretRect>().last()
            fun scrolls() = connection.nodeEvents.filterIsInstance<HostEvent.CodeScrolled>()
            fun press(key: Key, line: Int) {
                target.performKeyInput { pressKey(key) }
                waitForIdle()
                assertEquals(line, model.selection.caret.line, "$path: the caret is not on line $line")
                val rect = caret()
                assertEquals(line, rect.line, "$path: the caret on line $line was not reported")
                assertTrue(
                    rect.y >= boxTop - 0.5f,
                    "$path: line $line starts at ${rect.y}, above the editor's top at $boxTop",
                )
                assertTrue(
                    rect.y + rect.height <= boxBottom + 0.5f,
                    "$path: line $line ends at ${rect.y + rect.height}, below the editor's bottom at $boxBottom",
                )
            }

            // Inside the view the caret moves and the view stays.
            for (line in 1 until SHOWN - 2) press(Key.DirectionDown, line)
            assertEquals(emptyList(), scrolls(), "$path: a move inside the view scrolled it")

            // Past the bottom, and on to twice the lines the view shows.
            for (line in SHOWN - 2..2 * SHOWN) press(Key.DirectionDown, line)
            assertTrue(scrolls().isNotEmpty(), "$path: the view never followed the caret down")

            // One line up is still inside the view: it does not scroll.
            val before = scrolls().size
            press(Key.DirectionUp, 2 * SHOWN - 1)
            assertEquals(before, scrolls().size, "$path: a move up inside the view scrolled it")

            // Up past the top, back to the first line.
            for (line in 2 * SHOWN - 2 downTo 0) press(Key.DirectionUp, line)
        }
    }

    @Test
    fun fr38_caret_move_keeps_the_whole_caret_line_in_view() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) followsTheCaret(path)
    }

    @Test
    fun fr38_caret_move_keeps_the_whole_caret_line_in_view_with_content_padding() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            followsTheCaret(
                path,
                Mutation.SetProp(EDITOR, PropertyKind.ContentPaddingDp, PropertyValue.Bytes(padding(16f, 24f))),
            )
        }
    }
}
