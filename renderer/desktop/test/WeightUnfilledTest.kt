package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.width
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val ROW = 1
private const val LABEL = 2
private const val BADGE = 3

private const val OVERFLOW_ELLIPSIS = 2L

/**
 * Verifies #981 (`Modifier::weight_unfilled(f32)` / wire tag 48, Compose `weight(fill = false)`):
 * a short child keeps its intrinsic width so a trailing sibling sits immediately beside it, while
 * a long child is constrained to the remaining space so the trailing sibling still fits.
 */
@OptIn(ExperimentalTestApi::class)
class WeightUnfilledTest {
    @Test
    fun fr13_weight_unfilled_short_child_keeps_intrinsic_width_and_sibling_sits_beside() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(ROW, WidgetKind.Row),
            Mutation.SetModifier(ROW, 0, ProtocolModifier.Width(300f)),
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Hi")),
            Mutation.SetProp(LABEL, PropertyKind.MaxLines, PropertyValue.Integer(1L)),
            Mutation.SetProp(LABEL, PropertyKind.Overflow, PropertyValue.Integer(OVERFLOW_ELLIPSIS)),
            Mutation.SetModifier(LABEL, 0, ProtocolModifier.WeightUnfilled(1f)),
            Mutation.Insert(ROW, LABEL, 0),
            Mutation.Create(BADGE, WidgetKind.Box),
            Mutation.SetModifier(BADGE, 0, ProtocolModifier.Size(40f, 20f)),
            Mutation.Insert(ROW, BADGE, 1),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
        }
        waitForIdle()

        val labelBounds = onNodeWithTag(nodeTestTag(LABEL)).getUnclippedBoundsInRoot()
        val badgeBounds = onNodeWithTag(nodeTestTag(BADGE)).getUnclippedBoundsInRoot()

        assertTrue(
            labelBounds.width.value > 0f && labelBounds.width.value < 100f,
            "Short unfilled-weight Text should keep intrinsic width (< 100 px), got ${labelBounds.width.value}",
        )
        assertTrue(
            abs(badgeBounds.left.value - labelBounds.right.value) < 1f,
            "40 px Box should sit immediately beside short Text (text right=${labelBounds.right.value}, box left=${badgeBounds.left.value})",
        )
        assertTrue(
            abs(badgeBounds.width.value - 40f) < 1f,
            "Sibling Box should keep its 40 px width, got ${badgeBounds.width.value}",
        )
    }

    @Test
    fun fr13_weight_unfilled_long_child_elides_to_remaining_space_and_sibling_fits() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(ROW, WidgetKind.Row),
            Mutation.SetModifier(ROW, 0, ProtocolModifier.Width(200f)),
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(
                LABEL,
                PropertyKind.Text,
                PropertyValue.Text("A very long title that must truncate cleanly before the sibling badge"),
            ),
            Mutation.SetProp(LABEL, PropertyKind.MaxLines, PropertyValue.Integer(1L)),
            Mutation.SetProp(LABEL, PropertyKind.Overflow, PropertyValue.Integer(OVERFLOW_ELLIPSIS)),
            Mutation.SetModifier(LABEL, 0, ProtocolModifier.WeightUnfilled(1f)),
            Mutation.Insert(ROW, LABEL, 0),
            Mutation.Create(BADGE, WidgetKind.Box),
            Mutation.SetModifier(BADGE, 0, ProtocolModifier.Size(60f, 20f)),
            Mutation.Insert(ROW, BADGE, 1),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
        }
        waitForIdle()

        val labelBounds = onNodeWithTag(nodeTestTag(LABEL)).getUnclippedBoundsInRoot()
        val badgeBounds = onNodeWithTag(nodeTestTag(BADGE)).getUnclippedBoundsInRoot()

        assertTrue(
            labelBounds.width.value <= 140.5f,
            "Long unfilled-weight Text should be constrained to <= 140 px, got ${labelBounds.width.value}",
        )
        assertTrue(
            abs(badgeBounds.width.value - 60f) < 1f,
            "Sibling Box should keep its 60 px width, got ${badgeBounds.width.value}",
        )
        assertTrue(
            badgeBounds.right.value <= 200.5f,
            "Sibling Box should fit cleanly inside 200 px Row, got right=${badgeBounds.right.value}",
        )
    }
}
