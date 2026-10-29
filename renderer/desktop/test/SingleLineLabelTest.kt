package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
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
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PARENT = 1
private const val CONTROL = 2

/**
 * A Button or Chip label is one line. Squeezed into a parent narrower than the label, the
 * control used to be measured at the narrowest a Hangul label can be (one syllable) and
 * '보내' wrapped to '보' over '내' (compose-rust#328). The label is now one line high
 * however little room the parent offers (cut with an ellipsis, never wrapped).
 */
@OptIn(ExperimentalTestApi::class)
class SingleLineLabelTest {

    private fun tree(system: DesignSystem, kind: WidgetKind, label: String, parentWidth: Float) = listOf(
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)),
        Mutation.Create(PARENT, WidgetKind.Box),
        Mutation.SetModifier(PARENT, 0, ProtocolModifier.Width(parentWidth)),
        Mutation.Create(CONTROL, kind),
        Mutation.SetProp(CONTROL, PropertyKind.Text, PropertyValue.Text(label)),
        Mutation.Insert(PARENT, CONTROL, 0),
    )

    private fun labelBounds(system: DesignSystem, kind: WidgetKind, label: String, parentWidth: Float): DpRect {
        var bounds: DpRect? = null
        runComposeUiTest {
            setContent {
                ComposeRustContent(
                    rememberComposeRustHost(FakeHostConnection(tree(system, kind, label, parentWidth))),
                )
            }
            waitForIdle()
            bounds = onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        }
        return bounds!!
    }

    @Test
    fun fr13_button_and_chip_labels_do_not_wrap_when_the_parent_is_narrow() {
        listOf("보내" to WidgetKind.Button, "명령마다 확인" to WidgetKind.Chip).forEach { (label, kind) ->
            DesignSystem.entries.forEach { system ->
                val roomy = labelBounds(system, kind, label, 600f)
                val squeezed = labelBounds(system, kind, label, 12f)
                val single = roomy.bottom - roomy.top
                assertEquals(single.value, (squeezed.bottom - squeezed.top).value, 0.5f, "$system $kind '$label' stays on one line")
            }
        }
    }
}
