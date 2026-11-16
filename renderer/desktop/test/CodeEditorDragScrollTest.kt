package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_SCROLLED = 72L
private const val ROW = 20f
private const val VIEW_HEIGHT = 210f
private const val SHOWN = 10
private val DOCUMENT = (0 until 4 * SHOWN).joinToString("\n") { "line $it" }

/**
 * Dragging a selection with the mouse past the editor's bottom edge, and holding the pointer
 * there, scrolls the document down and carries the selection past the lines shown before.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorDragScrollTest {
    @AfterTest
    fun windowedPath() {
        defaultCodeEditorPath = CodeEditorPath.Windowed
    }

    private fun dragsPastTheBottom(path: CodeEditorPath) {
        defaultCodeEditorPath = path
        runComposeUiTest {
            val connection = FakeHostConnection(
                listOf(
                    Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
                    Mutation.Create(EDITOR, WidgetKind.CodeEditor),
                    Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(320f, VIEW_HEIGHT)),
                    Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(DOCUMENT)),
                    Mutation.SetProp(EDITOR, PropertyKind.FontSize, PropertyValue.Float(13f)),
                    Mutation.SetProp(EDITOR, PropertyKind.LineHeight, PropertyValue.Float(ROW)),
                    Mutation.SetProp(EDITOR, PropertyKind.OnCodeScrolled, PropertyValue.Integer(ON_SCROLLED)),
                ),
            )
            lateinit var host: ComposeRustHost
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    host = rememberComposeRustHost(connection)
                    ComposeRustContent(host)
                }
            }
            waitForIdle()
            assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
            val model = host.table.node(EDITOR)!!.code!!
            val editor = onNodeWithTag(nodeTestTag(EDITOR))
            val height = editor.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

            // Press on line 2, a little right of the gutter, then go below the bottom edge
            // and stay there.
            editor.performMouseInput {
                moveTo(Offset(width - 40f, 2 * ROW + ROW / 2))
                press()
            }
            waitForIdle()
            // The view scrolls once a frame while the pointer is held past the edge, so the
            // clock is moved by hand: an idle wait would never see it idle.
            mainClock.autoAdvance = false
            for (step in 0 until 40) {
                editor.performMouseInput { moveTo(Offset(width - 40f, height + 30f + (step % 2))) }
                mainClock.advanceTimeBy(50)
                waitForIdle()
            }
            editor.performMouseInput { release() }
            mainClock.autoAdvance = true
            waitForIdle()

            val scrolls = connection.nodeEvents.filterIsInstance<HostEvent.CodeScrolled>()
            assertTrue(
                scrolls.any { it.value > 0 },
                "$path: holding the pointer below the editor did not scroll it (selection ${model.selection})",
            )
            val selection = model.selection
            assertEquals(2, selection.anchor.line, "$path: the drag did not start on line 2")
            assertTrue(
                selection.caret.line > SHOWN,
                "$path: the selection ends on line ${selection.caret.line}, not past the $SHOWN lines shown before the drag",
            )
        }
    }

    @Test
    fun fr38_drag_selection_past_the_bottom_scrolls_the_editor() {
        dragsPastTheBottom(CodeEditorPath.Windowed)
    }

    @Test
    fun fr38_drag_selection_past_the_bottom_scrolls_the_drawn_editor() {
        installDrawnCodeEditor()
        dragsPastTheBottom(CodeEditorPath.Drawn)
    }
}
