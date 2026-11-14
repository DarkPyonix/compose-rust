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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_CHOICES_TAG
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodeEditorPath
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.code.CodeSelection
import dev.darkpyonix.composerust.foundation.code.EditOrigin
import dev.darkpyonix.composerust.foundation.code.defaultCodeEditorPath
import dev.darkpyonix.composerust.foundation.code.installDrawnCodeEditor
import dev.darkpyonix.composerust.foundation.code.parseSnippet
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
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDITOR = 2
private const val ON_CHANGE = 71L

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

private fun p(line: Int, column: Int) = CodePosition(line, column)

/** An editor alone on the page with [text], reporting its changes. */
internal fun snippetPage(text: String, vararg extra: Mutation): List<Mutation> = buildList {
    add(theme())
    add(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
    add(Mutation.SetModifier(EDITOR, 0, ProtocolModifier.Size(360f, 200f)))
    add(Mutation.SetProp(EDITOR, PropertyKind.Text, PropertyValue.Text(text)))
    add(Mutation.SetProp(EDITOR, PropertyKind.OnValueChange, PropertyValue.Integer(ON_CHANGE)))
    addAll(extra)
}

/**
 * What a reader types reaches either path: the field takes text input, and the drawn editor
 * takes the typed character the desktop sends as a key event with no key of its own.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
internal fun SemanticsNodeInteraction.typeInto(path: CodeEditorPath, text: String) {
    if (path == CodeEditorPath.Drawn) {
        for (character in text) performKeyPress(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = character.code))
    } else {
        performTextInput(text)
    }
}

/**
 * Criterion 23 of the code editor: a snippet goes in as one edit and opens its tab stops,
 * Tab and Shift+Tab step between them, places with one number change together, and a stop
 * with choices opens a list. The keys run on the field path and on the drawn path.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorSnippetTest {
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

    /** The node the keys go to, focused: the field, or the drawn editor after a press. */
    private fun ComposeUiTest.focusEditor(path: CodeEditorPath): SemanticsNodeInteraction {
        val target = if (path == CodeEditorPath.Drawn) {
            onNodeWithTag(nodeTestTag(EDITOR)).also { it.performMouseInput { click(Offset(width - 4f, 4f)) } }
        } else {
            onNodeWithTag(CODE_EDITOR_FIELD_TAG, useUnmergedTree = true).also { it.requestFocus() }
        }
        waitForIdle()
        return target
    }

    private fun ComposeUiTest.send(connection: FakeHostConnection, host: () -> ComposeRustHost, vararg mutations: Mutation) {
        connection.scheduleFrame(mutations.toList())
        host().renderFrame(0L)
        waitForIdle()
    }

    /**
     * Criterion 23.1. After `fn ${1:name}(${2:args}) {$0}` goes in, the caret has `name`
     * selected; Tab selects `args`; Tab again puts the caret between the braces and ends the
     * session, so the next Tab is an ordinary one. The insertion reached the Host as an
     * ordinary change and undoes as one step.
     */
    @Test
    fun fr38_snippet_tab_stops() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(snippetPage(""))
                val host = show(connection)
                val model = host().table.node(EDITOR)!!.code!!
                val target = focusEditor(path)
                send(connection, host, Mutation.InsertSnippet(EDITOR, 0, 0, 0, 0, 0, "fn \${1:name}(\${2:args}) {\$0}"))
                assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>(), "$path")
                assertEquals("fn name(args) {}", model.document.text(), "$path")
                val change = connection.nodeEvents.filterIsInstance<HostEvent.CodeChanged>().single()
                assertEquals("fn name(args) {}", change.text, "$path: the insertion is one ordinary change")
                assertEquals(CodeSelection(p(0, 3), p(0, 7)), model.selection, "$path: name is not selected")
                assertTrue(model.snippetActive, "$path")

                target.performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                assertEquals(CodeSelection(p(0, 8), p(0, 12)), model.selection, "$path: Tab did not select args")
                target.performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                assertEquals(CodeSelection.at(p(0, 15)), model.selection, "$path: Tab did not reach \$0")
                assertFalse(model.snippetActive, "$path: the session did not end at \$0")
                assertEquals("fn name(args) {}", model.document.text(), "$path: stepping changed the text")

                // The session is over, so Tab indents again.
                target.performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                assertTrue(model.document.text().startsWith("fn name(args) { "), "$path: ${model.document.text()}")

                // The indentation, then the whole snippet, each one step back.
                assertTrue(model.undo())
                assertTrue(model.undo())
                assertEquals("", model.document.text(), "$path: the snippet did not undo as one step")
            }
        }
    }

    /**
     * Criterion 23.2. In `${1:a} + ${1:a}` both places are selected together; typing `b`
     * changes both to `b`, and typing `c` after it changes both again.
     */
    @Test
    fun fr38_snippet_mirrors_edit_together() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(snippetPage("let x = ;"))
                val host = show(connection)
                val model = host().table.node(EDITOR)!!.code!!
                val target = focusEditor(path)
                send(connection, host, Mutation.InsertSnippet(EDITOR, 0, 0, 8, 0, 8, "\${1:a} + \${1:a}"))
                assertEquals("let x = a + a;", model.document.text(), "$path")
                assertEquals(CodeSelection(p(0, 8), p(0, 9)), model.selection, "$path")
                assertEquals(listOf(CodeSelection(p(0, 12), p(0, 13))), model.secondary, "$path: the mirror is not selected too")

                target.typeInto(path, "b")
                waitForIdle()
                assertEquals("let x = b + b;", model.document.text(), "$path: the mirror did not follow")
                target.typeInto(path, "c")
                waitForIdle()
                assertEquals("let x = bc + bc;", model.document.text(), "$path: the mirror did not follow the second letter")
                assertEquals(p(0, 10), model.selection.caret, "$path")

                // Escape ends the session, and the mirror's caret goes with it.
                target.performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                assertFalse(model.snippetActive, "$path")
                assertEquals(emptyList(), model.secondary, "$path")
            }
        }
    }

    /** A stop with choices opens its list; Down marks the next choice and Enter puts it in every place. */
    @Test
    fun fr38_snippet_choice_opens_a_list() {
        installDrawnCodeEditor()
        for (path in listOf(CodeEditorPath.Windowed, CodeEditorPath.Drawn)) {
            defaultCodeEditorPath = path
            runComposeUiTest {
                val connection = FakeHostConnection(snippetPage(""))
                val host = show(connection)
                val model = host().table.node(EDITOR)!!.code!!
                val target = focusEditor(path)
                send(connection, host, Mutation.InsertSnippet(EDITOR, 0, 0, 0, 0, 0, "let \${1|var,val,const|} = \$1;"))
                assertEquals("let var = var;", model.document.text(), "$path")
                assertEquals(listOf("var", "val", "const"), model.snippetChoices, "$path")
                onNodeWithTag(CODE_EDITOR_CHOICES_TAG, useUnmergedTree = true).assertExists("$path: the list is not shown")
                target.performKeyInput { pressKey(Key.DirectionDown) }
                target.performKeyInput { pressKey(Key.Enter) }
                waitForIdle()
                assertEquals("let val = val;", model.document.text(), "$path")
                assertNull(model.snippetChoices, "$path: the list stayed open")
                onNodeWithTag(CODE_EDITOR_CHOICES_TAG, useUnmergedTree = true).assertDoesNotExist()
            }
        }
    }

    /**
     * The syntax: placeholders, mirrors of them, choices, the final stop, escapes, a dollar
     * sign that starts nothing, and what cannot be read. A new line takes the indentation
     * of the line the snippet goes into, and the session ends when the caret leaves.
     */
    @Test
    fun fr38_snippet_syntax_and_session_in_the_model() {
        val parsed = parseSnippet("a \${1:x} \$1 \${2|p,q\\,r|} \\\$5 \$x \${3:(\$4)}\$0")!!
        assertEquals("a x x p \$5 \$x ()", parsed.text)
        assertEquals(listOf(1, 1, 2, 3, 4, 0), parsed.stops.map { it.number })
        assertEquals(listOf("p", "q,r"), parsed.stops[2].choices)
        assertEquals(listOf(2 to 3, 4 to 5, 6 to 7, 14 to 16, 15 to 15, 16 to 16), parsed.offsets)
        assertNull(parseSnippet("\${1:open"))
        assertNull(parseSnippet("\${1|a,b"))

        val model = CodeEditorModel("    body")
        model.hostInsertSnippet(0, 0, 8, 0, 8, "\nif \${1:cond} {\n\t\$0\n}")
        assertEquals("    body\n    if cond {\n    \t\n    }", model.document.text())
        assertEquals(CodeSelection(p(1, 7), p(1, 11)), model.selection)
        // The caret leaving the snippet ends it at the end of the frame.
        model.selection = CodeSelection.at(p(0, 0))
        model.drainOutput(includeReports = true)
        assertFalse(model.snippetActive)

        // A snippet whose range the reader has typed over is dropped and reported.
        val typed = CodeEditorModel("abc")
        typed.edit(p(0, 1), p(0, 2), "X", EditOrigin.Typing)
        typed.drainOutput()
        typed.hostInsertSnippet(0, 0, 0, 0, 3, "\$1")
        assertEquals("aXc", typed.document.text())
        assertEquals(1, typed.drainOutput().size)

        // Through the interpreter, a snippet that cannot be read is a protocol error.
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(Mutation.InsertSnippet(EDITOR, 0, 0, 0, 0, 0, "\${1:never closed"))
        assertEquals(listOf(TableError.INVALID_CODE_EDIT), table.drainErrors().map { it.code })
        table.apply(Mutation.InsertSnippet(9, 0, 0, 0, 0, 0, "x"))
        assertEquals(listOf(TableError.UNKNOWN_NODE), table.drainErrors().map { it.code })
    }
}
