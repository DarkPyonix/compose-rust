package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.width
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ROOT = 1
private const val BUTTON = 2
private const val INK = 0xFF000000.toInt()

/**
 * A short label in a small round button is drawn whole, in every design system.
 *
 * The samples' steppers and play badges are text buttons 36dp and 44dp square with a
 * full corner and a one-character label. Material 3 pads a button 24dp on each side, more
 * than the whole button, so the label was measured at no width at all and drew as a lone
 * ellipsis dot. Material is what Android draws with, which is why only Android showed it.
 * The padding is there to keep a label off the button's ends; it is not meant to take the
 * label's own room, the same rule the vertical padding already follows.
 */
@OptIn(ExperimentalTestApi::class)
class RoundButtonLabelTest {

    private fun tree(system: DesignSystem, label: String, side: Float?) = buildList {
        add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)))
        add(Mutation.Create(ROOT, WidgetKind.Box))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.Size(300f, 200f)))
        add(Mutation.Create(BUTTON, WidgetKind.Button))
        var slot = 0
        if (side != null) add(Mutation.SetModifier(BUTTON, slot++, ProtocolModifier.Size(side, side)))
        add(Mutation.SetModifier(BUTTON, slot++, ProtocolModifier.Background(Paint.Literal(INK))))
        add(Mutation.SetModifier(BUTTON, slot, ProtocolModifier.ShapeRole(ShapeRole.Full)))
        add(Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.SetProp(BUTTON, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)))
        add(Mutation.Insert(ROOT, BUTTON, 0))
    }

    private class Measured(
        val labelWidth: Float,
        val inside: Boolean,
        val ellipsized: Boolean,
        val overflows: Boolean,
    )

    /** The label as laid out in a button of [side] dp square, or of its own size when null. */
    private fun measure(system: DesignSystem, label: String, side: Float?): Measured {
        var result: Measured? = null
        runComposeUiTest {
            setContent {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(tree(system, label, side))))
            }
            waitForIdle()
            val box = onNodeWithTag(nodeTestTag(BUTTON)).getUnclippedBoundsInRoot()
            val textNode = onNodeWithText(label, useUnmergedTree = true)
            val text = textNode.getUnclippedBoundsInRoot()
            val layouts = mutableListOf<TextLayoutResult>()
            textNode.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            val layout = layouts.single()
            result = Measured(
                labelWidth = text.width.value,
                inside = text.left >= box.left && text.right <= box.right &&
                    text.top >= box.top && text.bottom <= box.bottom,
                ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) },
                // Not hasVisualOverflow: a one-line label that may ellipsize is laid out
                // at the full width offered, so its paragraph is wider than its box
                // whenever the button leaves a spare pixel, and that reads as overflow.
                // A label is cut when its box is narrower than the line it needs, or
                // shorter than its lines, or it lost a line.
                overflows = layout.size.width < kotlin.math.ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth) ||
                    layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines,
            )
        }
        return result!!
    }

    @Test
    fun fr13_short_label_in_a_small_round_button_is_not_ellipsized() {
        // minimal's stepper and social's play and back badges.
        val labels = listOf("−", "+", "▶", "←", "OK")
        DesignSystem.entries.forEach { system ->
            labels.forEach { label ->
                val whole = measure(system, label, null)
                assertFalse(whole.ellipsized, "$system '$label' fits a button of its own size")
                listOf(36f, 44f).forEach { side ->
                    val small = measure(system, label, side)
                    assertFalse(small.ellipsized, "$system '$label' in a ${side}dp round button is not ellipsized")
                    assertFalse(small.overflows, "$system '$label' in a ${side}dp round button is not cut")
                    assertTrue(small.inside, "$system '$label' lies inside the ${side}dp round button")
                    assertEquals(
                        whole.labelWidth,
                        small.labelWidth,
                        0.5f,
                        "$system '$label' keeps its whole width in a ${side}dp round button",
                    )
                }
            }
        }
    }
}
