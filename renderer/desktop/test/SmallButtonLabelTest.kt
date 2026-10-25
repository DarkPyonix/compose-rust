package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.height
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONTROL = 1
private const val LABEL = "검색"

/**
 * A control given a height smaller than its padding plus its label is still drawn with the
 * whole label. The first run of this found the Material 3 button cutting the label's lower
 * part at 32dp, which read '검색' as '거새'.
 */
@OptIn(ExperimentalTestApi::class)
class SmallButtonLabelTest {

    private fun tree(system: DesignSystem, kind: WidgetKind, height: Float) = listOf(
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)),
        Mutation.Create(CONTROL, kind),
        Mutation.SetModifier(CONTROL, 0, ProtocolModifier.Height(height)),
        Mutation.SetProp(CONTROL, PropertyKind.Text, PropertyValue.Text(LABEL)),
    )

    /** The label's height and the control's, and whether the label lies inside it. */
    private fun measure(system: DesignSystem, kind: WidgetKind, height: Float): Triple<Float, Float, Boolean> {
        var result: Triple<Float, Float, Boolean>? = null
        runComposeUiTest {
            setContent {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(tree(system, kind, height))))
            }
            waitForIdle()
            val box = onNodeWithTag(nodeTestTag(CONTROL)).getUnclippedBoundsInRoot()
            val text = onNodeWithText(LABEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val inside = text.top >= box.top && text.bottom <= box.bottom &&
                text.left >= box.left && text.right <= box.right
            result = Triple(text.height.value, box.height.value, inside)
        }
        return result!!
    }

    @Test
    fun fr13_small_button_keeps_its_label_whole() {
        listOf(WidgetKind.Button, WidgetKind.Chip).forEach { kind ->
            DesignSystem.entries.forEach { system ->
                val (whole, _, _) = measure(system, kind, 120f)
                val (label, box, inside) = measure(system, kind, 32f)
                assertEquals(32f, box, 0.5f, "$system $kind keeps the height it was given")
                assertTrue(inside, "$system $kind: the label lies inside the control")
                assertEquals(whole, label, 0.5f, "$system $kind: the label keeps its full line height")
            }
        }
    }
}
