package dioxus.compose.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dioxus.compose.design.HostPlatform
import dioxus.compose.foundation.code.CODE_EDITOR_FIELD_TAG
import dioxus.compose.foundation.code.CodeEditorPath
import dioxus.compose.foundation.code.defaultCodeEditorPath
import dioxus.compose.protocol.CodeEditorRecords
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DecorationKind
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.HoverPhase
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.hostPlatformOverride
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.tooling.designShowcaseRecords
import dioxus.compose.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EDITOR = 1
private const val ON_CHANGE = 11L
private const val ON_REJECTED = 12L
private const val ON_HOVER = 13L
private const val ON_SAVE = 14L
private const val ON_DECORATION = 15L

/** One decoration record, laid out as the Rust schema lays it out, with its text behind it. */
private fun decorations(vararg records: DecorationSpec): ByteArray {
    val textBytes = records.map { it.text.toByteArray(Charsets.UTF_8) }
    val recordsLength = records.size * 44
    val buffer = ByteBuffer.allocate(recordsLength + textBytes.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    var textOffset = recordsLength
    records.forEachIndexed { index, record ->
        buffer.putInt(0)
        buffer.putShort((record.kind.ordinal + 1).toShort())
        buffer.putShort(if (record.kind == DecorationKind.Underline) 1 else 0)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putInt(record.startLine)
        buffer.putInt(record.startColumn)
        buffer.putInt(record.endLine)
        buffer.putInt(record.endColumn)
        buffer.putLong(record.id)
        buffer.putInt(textOffset)
        buffer.putInt(textBytes[index].size)
        textOffset += textBytes[index].size
    }
    textBytes.forEach(buffer::put)
    return buffer.array()
}

private class DecorationSpec(
    val kind: DecorationKind,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val id: Long = 0,
    val text: String = "",
)

private fun editor(system: DesignSystem, text: String, vararg extra: Mutation): List<Mutation> = listOf(
    Mutation.SetTheme(Theme(system, DesignSystem.Material3, ColorScheme.Light, false)),
    Mutation.Create(EDITOR, WidgetKind.CodeEditor),
    Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)),
    Mutation.SetProp(EDITOR, PropertyKind.OnValueChange, PropertyValue.Integer(ON_CHANGE)),
    Mutation.SetProp(EDITOR, PropertyKind.OnEditRejected, PropertyValue.Integer(ON_REJECTED)),
    Mutation.SetProp(EDITOR, PropertyKind.OnHover, PropertyValue.Integer(ON_HOVER)),
    Mutation.SetProp(EDITOR, PropertyKind.OnSave, PropertyValue.Integer(ON_SAVE)),
    Mutation.SetProp(EDITOR, PropertyKind.OnDecorationClick, PropertyValue.Integer(ON_DECORATION)),
    *extra,
)

/**
 * A code editor drawn and driven through the interpreter: the events the reader's actions
 * send, the edits the Host asks for, and the editor under each design system.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorTest {
    @BeforeTest
    fun windowsShortcuts() {
        hostPlatformOverride = HostPlatform.Windows
    }

    @AfterTest
    fun clearPlatform() {
        hostPlatformOverride = null
        defaultCodeEditorPath = CodeEditorPath.Windowed
    }

    /** Criterion 8: every one of the seven systems draws the editor, decorations and all. */
    @Test
    fun fr38_every_design_system_draws_an_editor() {
        val lists = decorations(
            DecorationSpec(DecorationKind.Underline, 0, 3, 0, 7),
            DecorationSpec(DecorationKind.CodeLens, 0, 0, 0, 0, id = 1, text = "Run"),
            DecorationSpec(DecorationKind.GhostText, 1, 0, 1, 0, id = 2, text = "// next"),
        )
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                val connection = FakeHostConnection(
                    editor(
                        system,
                        "fn main() {\n\tlet a = 1;\n}\n",
                        Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(lists)),
                    ),
                )
                setContent { DioxusContent(rememberDioxusHost(connection)) }
                waitForIdle()
                onNodeWithTag(nodeTestTag(EDITOR)).assertIsDisplayed()
                onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).assertIsDisplayed()
                assertTrue(connection.events.none { it is HostEvent.ProtocolError }, "$system")
            }
        }
    }

    /** What the reader commits reaches the Host as one change carrying the new version. */
    @Test
    fun fr38_a_committed_edit_reaches_the_host_once_with_its_version() = runComposeUiTest {
        val connection = FakeHostConnection(editor(DesignSystem.Material3, "abc"))
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        val field = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true)
        field.requestFocus()
        field.performTextInput("😀")
        waitForIdle()
        val changes = connection.nodeEvents.filterIsInstance<HostEvent.CodeChanged>()
        assertEquals(1, changes.size)
        assertEquals(1, changes.single().version)
        assertEquals("😀", changes.single().text)
    }

    /** Criterion 6: the save shortcut sends the version the reader is looking at. */
    @Test
    fun fr38_the_save_shortcut_sends_the_current_version() = runComposeUiTest {
        val connection = FakeHostConnection(editor(DesignSystem.Fluent, "abc"))
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        val field = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true)
        field.requestFocus()
        field.performTextInput("x")
        waitForIdle()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.CodeSaveRequested(EDITOR, ON_SAVE, 1)),
            connection.nodeEvents.filterIsInstance<HostEvent.CodeSaveRequested>(),
        )
    }

    /**
     * Criterion 5, the suggestion: Tab accepts it, which sends the change first and the
     * decoration's id after it, and nothing else.
     */
    @Test
    fun fr38_tab_accepts_a_suggestion_change_first_then_activation() = runComposeUiTest {
        val lists = decorations(DecorationSpec(DecorationKind.GhostText, 0, 0, 0, 0, id = 77, text = "let "))
        val connection = FakeHostConnection(
            editor(
                DesignSystem.Material3,
                "x = 1;\n",
                Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(lists)),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        val field = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true)
        field.requestFocus()
        field.performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals(
            listOf(
                HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 0, 0, 0, 0, "let "),
                HostEvent.DecorationActivated(EDITOR, ON_DECORATION, 77L),
            ),
            connection.nodeEvents.filter { it is HostEvent.CodeChanged || it is HostEvent.DecorationActivated },
        )
    }

    /** Criterion 5, the lens: pressing the row above its line sends its id once. */
    @Test
    fun fr38_pressing_a_lens_sends_its_id() = runComposeUiTest {
        val lists = decorations(DecorationSpec(DecorationKind.CodeLens, 0, 0, 0, 0, id = 41, text = "Run test"))
        val connection = FakeHostConnection(
            editor(
                DesignSystem.Material3,
                "fn main() {}\n",
                Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(lists)),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()
        // The lens is the field's first row.
        onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).performMouseInput { click(Offset(8f, 4f)) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.DecorationActivated(EDITOR, ON_DECORATION, 41L)),
            connection.nodeEvents.filterIsInstance<HostEvent.DecorationActivated>(),
        )
    }

    /**
     * Criterion 5, the hover: a pointer at rest over an anchor sends its id once after the
     * design system's delay, and leaving sends one departure.
     */
    @Test
    fun fr38_a_resting_pointer_sends_one_rest_and_leaving_sends_one_leave() = runComposeUiTest {
        val lists = decorations(DecorationSpec(DecorationKind.HoverAnchor, 0, 0, 0, 12, id = 42))
        val connection = FakeHostConnection(
            editor(
                DesignSystem.Material3,
                "fn main() {}\n",
                Mutation.SetProp(EDITOR, PropertyKind.Decorations, PropertyValue.Bytes(lists)),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()
        val field = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true)
        field.performMouseInput { moveTo(Offset(12f, 6f)) }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        field.performMouseInput { moveTo(Offset(12f, 6f)) }
        mainClock.advanceTimeBy(2_000)
        field.performMouseInput { exit() }
        mainClock.advanceTimeBy(100)
        waitForIdle()
        val hovers = connection.nodeEvents.filterIsInstance<HostEvent.CodeHovered>()
        assertEquals(listOf(HoverPhase.Rest, HoverPhase.Leave), hovers.map { it.phase })
        assertEquals(listOf(42L, 42L), hovers.map { it.decoration })
    }

    /**
     * An edit the Host asks for lands in the field and comes back as a change, and the Host
     * writing that change back as the editor's text resets nothing.
     */
    @Test
    fun fr38_a_host_edit_shows_and_the_written_back_text_resets_nothing() = runComposeUiTest {
        val connection = FakeHostConnection(
            editor(DesignSystem.Material3, "let  a = 1;\n", Mutation.EditCode(EDITOR, 5, 0, 0, 3, 0, 5, " ")),
        )
        lateinit var host: DioxusHost
        connection.respondWith { event ->
            if (event is HostEvent.CodeChanged) {
                HostResponse(listOf(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text("let a = 1;\n"))))
            } else {
                HostResponse()
            }
        }
        setContent { host = rememberDioxusHost(connection); DioxusContent(host) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.CodeChanged(EDITOR, ON_CHANGE, 1, 0, 3, 0, 5, " ")),
            connection.nodeEvents.filterIsInstance<HostEvent.CodeChanged>(),
        )
        val document = host.table.node(EDITOR)!!.code!!.document
        assertEquals("let a = 1;\n", document.text())
        assertEquals(1, document.version)
    }

    /**
     * A hundred thousand lines open, and the field holds only the lines around the screen,
     * which is what keeps typing and scrolling from paying for the whole file.
     */
    @Test
    fun fr38_a_hundred_thousand_lines_open_and_only_the_window_is_laid_out() = runComposeUiTest {
        val text = buildString { repeat(100_000) { append("let line_").append(it).append(" = ").append(it).append(";\n") } }
        val connection = FakeHostConnection(editor(DesignSystem.Material3, text))
        setContent {
            DioxusContent(rememberDioxusHost(connection))
        }
        waitForIdle()
        val shown = onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
        assertTrue(shown.count { it == '\n' } < 2_000, "the field holds ${shown.count { it == '\n' }} lines")
        assertTrue(shown.startsWith("let line_0 = 0;"))
    }

    /** Criterion 8: the showcase carries an editor with all four decorations, and it decodes. */
    @Test
    fun fr38_the_design_showcase_has_an_editor_with_every_decoration() {
        val records = designShowcaseRecords(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))
        val editor = records.filterIsInstance<Mutation.Create>().single { it.widget == WidgetKind.CodeEditor }.nodeId
        val bytes = records.filterIsInstance<Mutation.SetProp>()
            .single { it.nodeId == editor && it.property == PropertyKind.Decorations }
            .value as PropertyValue.Bytes
        val errors = mutableListOf<String>()
        val kinds = CodeEditorRecords.decodeDecorations(bytes.value, errors::add).map { it.kind }.toSet()
        assertEquals(DecorationKind.entries.toSet(), kinds)
        assertEquals(emptyList<String>(), errors)
    }
}
