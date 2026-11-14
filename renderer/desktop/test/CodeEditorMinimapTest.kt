package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_MINIMAP_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MinimapSide
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
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_VISIBLE = 61L

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

/** Four hundred lines of code, long enough that the minimap shrinks every row below a pixel. */
private val DOCUMENT = (0 until 400).joinToString("\n") { "let value$it = compute($it) + offset;" }

/**
 * Criterion 27 of the code editor: a minimap beside the text shows the whole document shrunk,
 * and a press on it scrolls the editor to the part of the document under the press. Runs on
 * the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorMinimapTest {
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

    /**
     * Criterion 27.1. With `Right`, the minimap is drawn against the editor's right edge,
     * with the document's text in it, and pressing it three quarters of the way down
     * scrolls the editor until the line three quarters of the way through the document is
     * on screen.
     */
    @Test
    fun fr38_minimap_click_scrolls() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        theme(),
                        Mutation.Create(EDITOR, WidgetKind.CodeEditor),
                        Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(320f, 160f)),
                        Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(DOCUMENT)),
                        Mutation.SetProp(EDITOR, PropertyKind.OnVisibleRangeChange, PropertyValue.Integer(ON_VISIBLE)),
                        Mutation.SetProp(
                            EDITOR,
                            PropertyKind.Minimap,
                            PropertyValue.Integer((MinimapSide.Right.ordinal + 1).toLong()),
                        ),
                    ),
                )
                show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                val editor = onNodeWithTag(nodeTestTag(EDITOR)).fetchSemanticsNode().boundsInRoot
                val minimap = onNodeWithTag(CODE_EDITOR_MINIMAP_TAG, useUnmergedTree = true)
                val bounds = minimap.fetchSemanticsNode().boundsInRoot
                assertEquals(editor.right, bounds.right, 0.5f, "$path: the minimap is not against the right edge")
                assertTrue(bounds.left > editor.center.x, "$path: the minimap is not on the right: $bounds in $editor")
                assertEquals(editor.height, bounds.height, 0.5f, "$path: the minimap is not as tall as the editor")

                // The document is drawn in it: the strip is not one colour.
                val pixels = minimap.captureToImage().toPixelMap()
                val colours = HashSet<Int>()
                for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width) colours += pixels[x, y].hashCode()
                assertTrue(colours.size > 2, "$path: the minimap draws nothing but its background")

                fun visible() = connection.nodeEvents.filterIsInstance<HostEvent.VisibleRangeChanged>().last()
                assertEquals(0, visible().firstLine, "$path: the editor does not start at the top")

                minimap.performTouchInput { click(Offset(width / 2f, height * 0.75f)) }
                waitForIdle()
                val range = visible()
                assertTrue(
                    range.firstLine <= 300 && 300 <= range.lastLine,
                    "$path: a press three quarters down the minimap shows lines ${range.firstLine} to ${range.lastLine}",
                )
                assertTrue(range.firstLine > 250, "$path: the view went only to line ${range.firstLine}")
            }
        }
    }

    /** The property belongs to a code editor and to nothing else. */
    @Test
    fun fr38_minimap_is_a_property_of_an_editor() {
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.Minimap))
        assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.Minimap))
    }
}
