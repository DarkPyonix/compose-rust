package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.hostTextFieldValueHook
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverflowWrap
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WordBreak
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Long tokens and where lines break: `OverflowWrap.Anywhere` on a `Text`, and `WordBreak`
 * on a `TextField`'s typed text and placeholder.
 *
 * Every case is laid out at a density of one, so a dp is a pixel.
 */
@OptIn(ExperimentalTestApi::class)
class OverflowWrapTest {

    private fun tag(value: Enum<*>) = PropertyValue.Integer(value.ordinal + 1L)

    /** The text as it was given: without the invisible characters that only move breaks. */
    private fun plain(text: String) = text.filter { it != '⁠' && it != '​' }

    /** Each line of [layout], as the characters of the original string it holds. */
    private fun lines(layout: TextLayoutResult): List<String> {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount).map { plain(text.substring(layout.getLineStart(it), layout.getLineEnd(it))) }
    }

    /**
     * A 120dp row whose only child is a Text that takes the row's width by weight. A weighted
     * child is never laid out narrower than the narrowest width it says it can take, so this
     * is where a text that answers with the width of its longest token comes out too wide.
     */
    private fun weightedText(text: String, anywhere: Boolean) = buildList {
        add(Mutation.Create(ROW, WidgetKind.Row))
        add(Mutation.SetModifier(ROW, 0, ProtocolModifier.Width(COLUMN_WIDTH)))
        add(Mutation.Create(TEXT, WidgetKind.Text))
        add(Mutation.SetModifier(TEXT, 0, ProtocolModifier.Weight(1f)))
        add(Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text(text)))
        add(Mutation.SetProp(TEXT, PropertyKind.WordBreak, tag(WordBreak.KeepAll)))
        if (anywhere) add(Mutation.SetProp(TEXT, PropertyKind.OverflowWrap, tag(OverflowWrap.Anywhere)))
        add(Mutation.Insert(ROW, TEXT, 0))
    }

    private class Laid(val width: Int, val layout: TextLayoutResult, val errors: List<HostEvent.ProtocolError>)

    private fun laidOut(batch: List<Mutation>): Laid {
        var laid: Laid? = null
        runComposeUiTest {
            val connection = FakeHostConnection(batch)
            startHost(connection, Density(1f))
            waitForIdle()
            laid = Laid(
                width = onNodeWithTag(nodeTestTag(TEXT), useUnmergedTree = true).fetchSemanticsNode().size.width,
                layout = drawnLayout(TEXT),
                errors = connection.events.filterIsInstance<HostEvent.ProtocolError>(),
            )
        }
        return laid!!
    }

    @Test
    fun fr58_overflow_wrap_anywhere_breaks_long_tokens() {
        val sentence = "한글 문장은 띄어쓰기에서만 줄을 바꿉니다"
        val tokens = listOf(
            // A path with no break in it at all, 40 letters.
            "averyveryverylongidentifierwithnobreaks1",
            // 40 syllables run together, which keep-all would never break.
            "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타",
        )
        for (token in tokens) {
            assertEquals(40, token.length)
            val text = "$sentence $token"
            val laid = laidOut(weightedText(text, anywhere = true))
            assertEquals(emptyList(), laid.errors, "$token: no protocol error")
            val layout = laid.layout
            assertTrue(laid.width <= COLUMN_WIDTH.toInt(), "$token: the text stays inside the 120dp row, it is ${laid.width} wide")
            assertTrue(layout.size.width <= COLUMN_WIDTH.toInt(), "$token: laid out ${layout.size.width} wide")
            assertTrue(!layout.hasVisualOverflow, "$token: nothing is cut off")
            for (line in 0 until layout.lineCount) {
                assertTrue(
                    layout.getLineRight(line) <= layout.size.width + 0.5f,
                    "$token: line $line ends at ${layout.getLineRight(line)}, past the width ${layout.size.width}",
                )
            }
            val drawn = lines(layout)
            assertEquals(text, drawn.joinToString(""), "$token: every letter is drawn")
            // The token is broken inside, over more than one line.
            val tokenStart = text.indexOf(token)
            val ends = (0 until layout.lineCount).map { plainOffset(layout, layout.getLineEnd(it)) }
            val linesOfToken = ends.count { it > tokenStart }
            assertTrue(linesOfToken >= 2, "$token: the token runs over $linesOfToken lines: $drawn")
            // The Korean sentence still breaks only at its spaces.
            drawn.forEachIndexed { line, letters ->
                if (ends[line] <= tokenStart) {
                    assertTrue(letters.endsWith(" "), "$token: a line of the sentence ends inside a word: $drawn")
                }
            }
        }
    }

    @Test
    fun fr58_without_overflow_wrap_a_keep_all_token_keeps_the_text_wide() {
        // What `Anywhere` is for: asked how narrow it can be, a keep-all text says the width
        // of its longest token, and the row lays it out that wide.
        val laid = laidOut(weightedText("가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카", anywhere = false))
        assertTrue(laid.width > COLUMN_WIDTH.toInt(), "the keep-all token is laid out wider than the row: ${laid.width}")
    }

    /** Where offset [offset] of the drawn string is in the original string. */
    private fun plainOffset(layout: TextLayoutResult, offset: Int): Int =
        plain(layout.layoutInput.text.text.substring(0, offset)).length

    private fun field(vararg props: Pair<PropertyKind, PropertyValue>) = buildList {
        add(Mutation.Create(COLUMN, WidgetKind.Column))
        add(Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(COLUMN_WIDTH)))
        add(Mutation.Create(FIELD, WidgetKind.TextField))
        add(Mutation.SetProp(FIELD, PropertyKind.Multiline, PropertyValue.Bool(true)))
        for ((kind, value) in props) add(Mutation.SetProp(FIELD, kind, value))
        add(Mutation.Insert(COLUMN, FIELD, 0))
    }

    /** Every text layout under the field, with the string each one draws. */
    private fun ComposeUiTest.layoutsIn(nodeId: Int): List<TextLayoutResult> {
        val found = mutableListOf<TextLayoutResult>()
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                action(layouts)
                found += layouts
            }
            node.children.forEach(::visit)
        }
        visit(onNodeWithTag(nodeTestTag(nodeId), useUnmergedTree = true).fetchSemanticsNode())
        return found
    }

    /** The lines [text] is drawn in, in the field, with [wordBreak] or none. */
    private fun fieldLines(text: String, hint: Boolean, wordBreak: WordBreak?): Pair<List<String>, List<HostEvent.ProtocolError>> {
        var result: Pair<List<String>, List<HostEvent.ProtocolError>>? = null
        runComposeUiTest {
            val props = buildList {
                add((if (hint) PropertyKind.Placeholder else PropertyKind.Text) to PropertyValue.Text(text))
                if (wordBreak != null) add(PropertyKind.WordBreak to tag(wordBreak))
                add(PropertyKind.OverflowWrap to tag(OverflowWrap.Anywhere))
            }
            val connection = FakeHostConnection(field(*props.toTypedArray()))
            startHost(connection, Density(1f))
            waitForIdle()
            val layout = assertNotNull(
                layoutsIn(FIELD).firstOrNull { plain(it.layoutInput.text.text) == text },
                "no layout draws \"$text\"",
            )
            result = lines(layout) to connection.events.filterIsInstance<HostEvent.ProtocolError>()
        }
        return result!!
    }

    @Test
    fun fr58_text_field_word_break_applies_to_the_hint() {
        val hint = "검색어를 입력하고 엔터를 누르세요"
        val (keepAll, errors) = fieldLines(hint, hint = true, WordBreak.KeepAll)
        assertEquals(emptyList(), errors, "a field takes WordBreak and OverflowWrap")
        assertTrue(keepAll.size >= 2, "the hint wraps in a narrow field: $keepAll")
        for (line in keepAll.dropLast(1)) {
            assertTrue(line.endsWith(" "), "a line of the hint ends inside a word: $keepAll")
        }
        // Without keep-all the same hint breaks between syllables, so the rule is what moved
        // the breaks.
        val (normal, _) = fieldLines(hint, hint = true, null)
        assertTrue(normal.dropLast(1).any { !it.endsWith(" ") }, "the hint without keep-all breaks inside a word: $normal")
    }

    @Test
    fun fr58_text_field_word_break_applies_to_the_typed_text() {
        val typed = "받아쓰기를 연습하는 문장입니다 그리고 끝"
        val (lines, errors) = fieldLines(typed, hint = false, WordBreak.KeepAll)
        assertEquals(emptyList(), errors)
        assertTrue(lines.size >= 2, "the text wraps in a narrow field: $lines")
        for (line in lines.dropLast(1)) {
            assertTrue(line.endsWith(" "), "a line of the typed text ends inside a word: $lines")
        }
    }

    @Test
    fun fr58_a_keep_all_field_keeps_the_caret_and_the_composition_on_the_typed_text() = runComposeUiTest {
        var update: ((TextFieldValue) -> Unit)? = null
        hostTextFieldValueHook = { update = it }
        try {
            val connection = FakeHostConnection(
                field(
                    PropertyKind.WordBreak to tag(WordBreak.KeepAll),
                    PropertyKind.OnValueChange to PropertyValue.Integer(ON_CHANGE),
                ),
            )
            startHost(connection, Density(1f))
            waitForIdle()
            val apply = assertNotNull(update, "the field installed its value hook")
            // A syllable being composed after two committed ones, with the caret after it:
            // offsets into the typed text, which the drawn text has joiners between.
            runOnIdle { apply(TextFieldValue("한글입", selection = TextRange(3), composition = TextRange(2, 3))) }
            waitForIdle()
            val layout = layoutsIn(FIELD).first { plain(it.layoutInput.text.text) == "한글입" }
            assertTrue(layout.layoutInput.text.text.length > 3, "the drawn text carries the joiners")
            // The value the Host is told is what was typed: the joiners are only drawn.
            assertEquals(
                "한글입",
                connection.events.filterIsInstance<HostEvent.TextChanged>().lastOrNull()?.text,
                "the Host is told the typed text",
            )
            assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())
        } finally {
            hostTextFieldValueHook = null
        }
    }

    private companion object {
        const val COLUMN_WIDTH = 120f
        const val ROW = 1
        const val COLUMN = 1
        const val TEXT = 2
        const val FIELD = 2
        const val ON_CHANGE = 9L
    }
}
