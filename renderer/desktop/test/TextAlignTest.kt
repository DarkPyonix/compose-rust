package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TextAlign
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Physical text alignment. `Start` and `End` follow the writing direction, so in a right to
 * left paragraph `Start` is the right edge. `Left` and `Right` are the edges they name in
 * either direction, as CSS `text-align: left|right` are.
 */
@OptIn(ExperimentalTestApi::class)
class TextAlignTest {

    /** Where the first line of a right to left paragraph aligned [align] sits, in px. */
    private fun lineIn(align: TextAlign): Triple<Float, Float, Float> {
        var answer: Triple<Float, Float, Float>? = null
        runComposeUiTest {
            val mutations = listOf(
                Mutation.Create(COLUMN, WidgetKind.Column),
                Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(300f)),
                Mutation.Create(TEXT, WidgetKind.Text),
                Mutation.SetModifier(TEXT, 0, ProtocolModifier.FillMaxWidth),
                Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text("שלום עולם")),
                Mutation.SetProp(TEXT, PropertyKind.TextAlign, PropertyValue.Integer(align.ordinal + 1L)),
                Mutation.Insert(COLUMN, TEXT, 0),
            )
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations)))
                }
            }
            waitForIdle()
            val layout = drawnLayout(TEXT)
            answer = Triple(layout.getLineLeft(0), layout.getLineRight(0), layout.size.width.toFloat())
        }
        return answer!!
    }

    @Test
    fun fr13_physical_text_align_is_not_flipped_in_rtl() {
        val (leftLeft, leftRight, width) = lineIn(TextAlign.Left)
        assertEquals(300f, width, "the paragraph is as wide as its column")
        assertTrue(leftLeft < 1f && leftRight < width - 1f, "Left sits at the left edge: $leftLeft..$leftRight of $width")

        val (rightLeft, rightRight, _) = lineIn(TextAlign.Right)
        assertTrue(rightRight > width - 1f && rightLeft > 1f, "Right sits at the right edge: $rightLeft..$rightRight of $width")

        val (startLeft, startRight, _) = lineIn(TextAlign.Start)
        assertTrue(startRight > width - 1f && startLeft > 1f, "Start is the right edge in right to left text: $startLeft..$startRight")

        val (endLeft, endRight, _) = lineIn(TextAlign.End)
        assertTrue(endLeft < 1f && endRight < width - 1f, "End is the left edge in right to left text: $endLeft..$endRight")
    }

    /** A tag past the last alignment is reported and dropped, never guessed at. */
    @Test
    fun fr13_unknown_text_align_is_a_protocol_error() {
        val table = NodeTable()
        table.apply(Mutation.Create(TEXT, WidgetKind.Text))
        table.apply(Mutation.SetProp(TEXT, PropertyKind.TextAlign, PropertyValue.Integer(TextAlign.Right.ordinal + 1L)))
        assertEquals(emptyList(), table.drainErrors())

        table.apply(Mutation.SetProp(TEXT, PropertyKind.TextAlign, PropertyValue.Integer(7)))
        assertEquals(listOf(TableError.UNSUPPORTED_PROPERTY), table.drainErrors().map { it.code })
        assertNull(table.node(TEXT)!!.props[PropertyKind.TextAlign], "the unknown alignment is not kept")
    }

    private companion object {
        const val COLUMN = 1
        const val TEXT = 2
    }
}
