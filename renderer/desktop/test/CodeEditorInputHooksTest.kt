package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.foundation.codeEditorKeyPreview
import dev.darkpyonix.composerust.foundation.pointerKindBit
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Key as ProtocolKey
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PointerKind
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_PREVIEW = 81L
private const val ON_CODE_POINTER = 91L

/** An editor alone on the page, 300 by 160 dp, with the records the test adds after it. */
private fun page(text: String, vararg extra: Mutation): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
    add(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    add(Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(300f, 160f)))
    add(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    addAll(extra)
}

/**
 * Criteria 20 and 21 of the code editor: the Host hears a key before the editor does and
 * can take it, and hears the pointer over the editor with the place in the text under it.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorInputHooksTest {
    @BeforeTest
    fun windowsShortcuts() {
        hostPlatformOverride = HostPlatform.Windows
    }

    @AfterTest
    fun clearPlatform() {
        hostPlatformOverride = null
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
     * Criterion 20.1. The Host takes Down while a list is open: the caret stays where it
     * was. It lets Down go: the caret moves a line, as it does with nobody listening. Both
     * presses reach the Host first, as the `KeyDown` record a key handler hears.
     */
    @Test
    fun fr38_editor_key_preview_consumes_keys() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                var take = true
                val connection = FakeHostConnection(
                    page("first\nsecond\nthird", Mutation.SetProp(EDITOR, PropertyKind.OnKeyPreview, PropertyValue.Integer(ON_PREVIEW))),
                )
                connection.respondWith { event ->
                    if (event is HostEvent.KeyDown && event.handlerId == ON_PREVIEW && take) HostResponse(result = 1) else HostResponse()
                }
                val host = show(connection)
                fun caret() = host().table.node(EDITOR)!!.code!!.selection.caret
                val target = if (path == CodeEditorPath.Drawn) {
                    // The drawn editor takes the focus when it is pressed, here past the
                    // end of the first line.
                    val editor = onNodeWithTag(nodeTestTag(EDITOR))
                    editor.performMouseInput { click(Offset(width - 4f, 4f)) }
                    editor
                } else {
                    onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
                }
                waitForIdle()
                val start = caret()
                assertEquals(0, start.line, "$path")

                target.performKeyInput { pressKey(Key.DirectionDown) }
                waitForIdle()
                assertEquals(start, caret(), "$path: a key the Host took moved the caret")

                take = false
                target.performKeyInput { pressKey(Key.DirectionDown) }
                waitForIdle()
                assertEquals(1, caret().line, "$path: a key the Host let go did not move the caret")

                val previews = connection.nodeEvents.filterIsInstance<HostEvent.KeyDown>()
                assertEquals(2, previews.size, "$path: each press is offered once: $previews")
                for (preview in previews) {
                    assertEquals(EDITOR, preview.nodeId)
                    assertEquals(ON_PREVIEW, preview.handlerId)
                    assertEquals(ProtocolKey.ArrowDown, preview.key)
                    assertEquals(0x51, preview.code)
                    assertEquals("ArrowDown", preview.text)
                    assertFalse(preview.repeat)
                }
            }
        }
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.OnKeyPreview))
        assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.OnKeyPreview))
    }

    /**
     * Criterion 20.2. While an input method composes nothing is offered to the Host, so a
     * Down that picks a candidate stays the input method's. Releases are never offered, and
     * outside a composition the press is, with the Host's answer as the editor's.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun fr38_editor_key_preview_skips_composition() {
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnKeyPreview, PropertyValue.Integer(ON_PREVIEW)))
        val node = table.node(EDITOR)!!
        val sent = mutableListOf<HostEvent>()
        var answer = true
        val preview = codeEditorKeyPreview(node, { EventDispatcher { sent += it; answer } })
        val down = KeyEvent(Key.DirectionDown, KeyEventType.KeyDown)
        val up = KeyEvent(Key.DirectionDown, KeyEventType.KeyUp)

        assertFalse(preview(down, true), "a key during a composition was taken from the input method")
        assertFalse(preview(up, true))
        assertEquals(emptyList(), sent, "a key during a composition reached the Host")

        assertTrue(preview(down, false), "the Host's answer is the editor's")
        assertFalse(preview(up, false), "a release is not offered")
        answer = false
        assertFalse(preview(down, false))
        assertEquals(2, sent.size)
        assertTrue(sent.all { it is HostEvent.KeyDown && it.handlerId == ON_PREVIEW && it.nodeId == EDITOR })

        // Held down, the second press is a repeat.
        preview(down, false)
        assertTrue((sent.last() as HostEvent.KeyDown).repeat)

        // Nobody listening: nothing is sent and the key stays the editor's.
        table.apply(Mutation.SetProp(EDITOR, PropertyKind.OnKeyPreview, PropertyValue.None))
        assertFalse(preview(down, false))
        assertEquals(3, sent.size)
    }

    /**
     * Criterion 21.1. Cmd and a click on the `b` of `beta` reaches the Host once, as a
     * press of the primary button with Meta held and the line and column of that letter,
     * and a press the Host consumed leaves the caret where it was. Let go, the same click
     * moves the caret there, and a press over the gutter is over no character.
     */
    @Test
    fun fr38_editor_pointer_reports_text_position() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            hostPlatformOverride = HostPlatform.MacOs
            runComposeUiTest {
                var take = true
                val connection = FakeHostConnection(
                    page(
                        "let alpha = 1;\nlet beta = alpha + 1;\n",
                        Mutation.SetProp(EDITOR, PropertyKind.OnCodePointer, PropertyValue.Integer(ON_CODE_POINTER)),
                        Mutation.SetProp(
                            EDITOR,
                            PropertyKind.PointerKinds,
                            PropertyValue.Integer(pointerKindBit(PointerKind.Down).toLong()),
                        ),
                        // Where the `b` of `beta` is, which is where the test clicks.
                        Mutation.QueryPositionRect(EDITOR, 1, 1, 4),
                    ),
                )
                connection.respondWith { event ->
                    if (event is HostEvent.CodePointer && take) HostResponse(result = 1) else HostResponse()
                }
                val host = show(connection)
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                fun caret() = host().table.node(EDITOR)!!.code!!.selection
                fun pointers() = connection.nodeEvents.filterIsInstance<HostEvent.CodePointer>()
                val letter = connection.nodeEvents.filterIsInstance<HostEvent.PositionRect>().single()
                assertTrue(letter.found, "$path")
                val editor = onNodeWithTag(nodeTestTag(EDITOR))
                val origin = editor.fetchSemanticsNode().positionInRoot
                val point = Offset(letter.x + 2f - origin.x, letter.y + letter.height / 2f - origin.y)

                editor.performKeyInput { keyDown(Key.MetaLeft) }
                editor.performMouseInput {
                    moveTo(point)
                    press()
                    release()
                }
                editor.performKeyInput { keyUp(Key.MetaLeft) }
                waitForIdle()
                val down = pointers().single()
                assertEquals(EDITOR, down.nodeId)
                assertEquals(ON_CODE_POINTER, down.handlerId)
                assertEquals(PointerKind.Down, down.kind)
                assertEquals(1 to 4, down.line to down.column, "$path: the press is over the b of beta")
                assertEquals(8, down.modifiers and 8, "$path: Meta is held")
                assertEquals(0, down.modifiers and 2, "$path: Ctrl is not")
                assertEquals(1, down.button)
                assertEquals(1, down.clickCount)
                assertTrue(abs(point.x - down.x) <= 0.6f && abs(point.y - down.y) <= 0.6f, "$path: at $point, sent $down")
                assertTrue(caret().collapsed && caret().caret == CodePosition(0, 0), "$path: a consumed press moved the caret to ${caret()}")

                // The Host lets the same click go: the caret goes to the letter, as it always did.
                take = false
                mainClock.advanceTimeBy(2_000)
                editor.performMouseInput {
                    moveTo(point)
                    press()
                    release()
                }
                waitForIdle()
                assertEquals(2, pointers().size, "$path")
                assertEquals(0, pointers().last().modifiers, "$path")
                assertEquals(CodePosition(1, 4), caret().caret, "$path: a press the Host let go did not move the caret")

                // Over the gutter there is no character.
                mainClock.advanceTimeBy(2_000)
                editor.performMouseInput {
                    moveTo(Offset(2f, point.y))
                    press()
                    release()
                }
                waitForIdle()
                assertEquals(-1 to -1, pointers().last().let { it.line to it.column }, "$path")
            }
        }
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.OnCodePointer))
        assertTrue(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.PointerKinds))
        assertFalse(NodeTable.supportsProperty(WidgetKind.CodeEditor, PropertyKind.OnPointer))
        assertFalse(NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.OnCodePointer))
    }
}
