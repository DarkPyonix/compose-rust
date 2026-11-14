package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.fail

private const val PAGE = 1
private const val SHORT_FIELD = 2
private const val LONG_FIELD = 3
private const val SHORT_OPTION = 10
private const val LONG_OPTION = 20
private const val SHORT = "Option 1"
private const val LONG = "An option whose label is far wider than the field it is chosen in"

/** The arrows a closed field may draw at its end. */
private val MARKERS = listOf("▾", "⌄")

/**
 * A closed dropdown lays its chosen option out in the room before its arrow, so the arrow
 * never draws over the end of the label, and a label too long for that room is cut short on
 * one line rather than running under the arrow or wrapping.
 */
@OptIn(ExperimentalTestApi::class)
class DropdownArrowSpaceTest {

    private enum class Variant { Default, Collapsible, Bare }

    private fun batch(system: DesignSystem, variant: Variant) = buildList {
        add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)))
        add(Mutation.Create(PAGE, WidgetKind.Column))
        add(Mutation.SetModifier(PAGE, 0, Modifier.FillMaxWidth))
        add(Mutation.SetModifier(PAGE, 1, Modifier.Padding(16f)))
        listOf(
            Triple(SHORT_FIELD, SHORT_OPTION, SHORT),
            Triple(LONG_FIELD, LONG_OPTION, LONG),
        ).forEachIndexed { index, (field, option, label) ->
            add(Mutation.Create(field, WidgetKind.Dropdown))
            add(Mutation.SetProp(field, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
            // The short one keeps its own width, which is where "Option 1" met its arrow;
            // the long one is held to a width its label cannot fit in.
            if (field == LONG_FIELD) add(Mutation.SetModifier(field, 0, Modifier.Width(180f)))
            when (variant) {
                Variant.Default -> Unit
                Variant.Collapsible -> add(Mutation.SetProp(field, PropertyKind.Collapsible, PropertyValue.Bool(true)))
                Variant.Bare -> add(Mutation.SetProp(field, PropertyKind.Bare, PropertyValue.Bool(true)))
            }
            add(Mutation.Create(option, WidgetKind.Text))
            add(Mutation.SetProp(option, PropertyKind.Text, PropertyValue.Text(label)))
            add(Mutation.Insert(field, option, 0))
            add(Mutation.Insert(PAGE, field, index))
        }
    }

    private class Measured(val shortLabel: DpRect, val longLabel: DpRect, val shortField: DpRect, val longField: DpRect, val markers: List<DpRect>)

    /** What the two fields measure, or null where the system draws a wheel and no field. */
    private fun measure(system: DesignSystem, variant: Variant): Measured? {
        var measured: Measured? = null
        runDesktopComposeUiTest(640, 480) {
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch(system, variant)))) }
            waitForIdle()
            val markers = MARKERS.flatMap { marker ->
                onAllNodesWithText(marker, useUnmergedTree = true).fetchSemanticsNodes().indices.map { index ->
                    onAllNodesWithText(marker, useUnmergedTree = true)[index].getUnclippedBoundsInRoot()
                }
            }
            if (markers.isEmpty()) return@runDesktopComposeUiTest
            measured = Measured(
                shortLabel = onNodeWithTag(nodeTestTag(SHORT_OPTION), useUnmergedTree = true).getUnclippedBoundsInRoot(),
                longLabel = onNodeWithTag(nodeTestTag(LONG_OPTION), useUnmergedTree = true).getUnclippedBoundsInRoot(),
                shortField = onNodeWithTag(nodeTestTag(SHORT_FIELD), useUnmergedTree = true).getUnclippedBoundsInRoot(),
                longField = onNodeWithTag(nodeTestTag(LONG_FIELD), useUnmergedTree = true).getUnclippedBoundsInRoot(),
                markers = markers,
            )
        }
        return measured
    }

    private fun DpRect.contains(other: DpRect) =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    @Test
    fun fr64_closed_field_label_stops_before_the_arrow() {
        val problems = mutableListOf<String>()
        DesignSystem.entries.forEach { system ->
            var fields = 0
            Variant.entries.forEach { variant ->
                val m = measure(system, variant) ?: return@forEach
                fields++
                listOf(
                    Triple("short", m.shortField, m.shortLabel),
                    Triple("long", m.longField, m.longLabel),
                ).forEach { (name, field, label) ->
                    val arrow = m.markers.firstOrNull { field.contains(it) }
                    if (arrow == null) {
                        problems += "$system $variant $name: no arrow inside the field at $field (arrows ${m.markers})"
                        return@forEach
                    }
                    if (label.right >= arrow.left) {
                        problems += "$system $variant $name: label ends at ${label.right}, arrow starts at ${arrow.left}"
                    }
                    if (label.left < field.left) {
                        problems += "$system $variant $name: label starts at ${label.left}, before the field at ${field.left}"
                    }
                }
                // Cut short on one line: the long label is no taller than the short one.
                if (m.longLabel.height > m.shortLabel.height + 0.5f.toDp()) {
                    problems += "$system $variant: the long label is ${m.longLabel.height} tall and the short one ${m.shortLabel.height}"
                }
            }
            if (fields == 0) problems += "$system: no variant drew a closed field"
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}

private fun Float.toDp() = androidx.compose.ui.unit.Dp(this)
