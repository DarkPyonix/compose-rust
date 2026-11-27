package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WordBreak
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEXT = 1

/**
 * compose-rust #1094: `keep-all` keeps CJK words whole by putting U+2060 WORD JOINER between
 * their letters, and `break-all` lets them break with U+200B. Those characters steer the
 * layout only; the name a reader hears, and that Voice Control or a test looks a control up
 * by, is the string the Host sent.
 */
@OptIn(ExperimentalTestApi::class)
class KeepAllNameTest {

    private fun check(source: String, wordBreak: WordBreak, inserted: Char) = runComposeUiTest {
        val mutations = listOf(
            Mutation.Create(TEXT, WidgetKind.Text),
            Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text(source)),
            Mutation.SetProp(TEXT, PropertyKind.WordBreak, PropertyValue.Integer(wordBreak.ordinal + 1L)),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
        waitForIdle()
        val config = onNodeWithTag(nodeTestTag(TEXT)).fetchSemanticsNode().config
        val name = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        assertEquals(source, name, "the accessible name holds the characters that steer breaking")

        // The layout still has them: the words are kept whole (or broken) as before.
        val layouts = mutableListOf<TextLayoutResult>()
        assertNotNull(config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts)
        val laidOut = layouts.single().layoutInput.text.text
        assertTrue(inserted in laidOut, "the layout lost its $wordBreak characters: $laidOut")
    }

    @Test
    fun fr40_keep_all_text_is_named_by_the_string_the_host_sent() =
        check("명령마다 확인", WordBreak.KeepAll, '⁠')

    @Test
    fun fr40_break_all_text_is_named_by_the_string_the_host_sent() =
        check("abcdef ghij", WordBreak.BreakAll, '​')
}
