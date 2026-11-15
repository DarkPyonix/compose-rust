package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.IconRole
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TextField typography and control over composer and icon button tokens.
 */
@OptIn(ExperimentalTestApi::class)
class HostComposerTokensTest {
    private val root = 1
    private val control = 2

    @Test
    fun fr75_text_field_honors_explicit_height_and_content_padding() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)),
            Mutation.Create(root, WidgetKind.Box),
            Mutation.Create(control, WidgetKind.TextField),
            Mutation.SetProp(control, PropertyKind.Text, PropertyValue.Text("hello")),
            Mutation.SetModifier(control, 0, ProtocolModifier.Height(36f)),
            Mutation.SetModifier(control, 1, ProtocolModifier.PaddingEach(12f, 6f, 12f, 6f)),
            Mutation.Insert(root, control, 0),
        )

        setContent {
            ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
        }
        waitForIdle()

        val bounds = onNodeWithTag(nodeTestTag(control)).getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue(
            abs(height.value - 36f) <= 1.5f,
            "field with explicit height 36dp measured $height instead of adhering to height",
        )
    }

    @Test
    fun fr75_icon_button_honors_explicit_size() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)),
            Mutation.Create(root, WidgetKind.Box),
            Mutation.Create(control, WidgetKind.Button),
            Mutation.SetProp(control, PropertyKind.Text, PropertyValue.Text("")),
            Mutation.SetProp(control, PropertyKind.Icon, PropertyValue.Integer(IconRole.Add.ordinal + 1L)),
            Mutation.SetModifier(control, 0, ProtocolModifier.Size(32f, 32f)),
            Mutation.SetModifier(control, 1, ProtocolModifier.CornerEach(8f, 8f, 8f, 8f)),
            Mutation.Insert(root, control, 0),
        )

        setContent {
            ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
        }
        waitForIdle()

        val bounds = onNodeWithTag(nodeTestTag(control)).getBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assertTrue(
            abs(width.value - 32f) <= 1.5f,
            "wordless button with explicit size 32dp measured width $width",
        )
        assertTrue(
            abs(height.value - 32f) <= 1.5f,
            "wordless button with explicit size 32dp measured height $height",
        )
    }

    /**
     * Where a filled Material button and its label "Commit" are laid out, the button first.
     * [padding] is the room the button asked for round its label, or null for a button that
     * asked for none.
     */
    private fun buttonAndLabel(padding: ProtocolModifier.PaddingEach?): Pair<DpRect, DpRect> {
        val batch = buildList {
            add(Mutation.SetTheme(material))
            add(Mutation.Create(root, WidgetKind.Box))
            add(Mutation.Create(control, WidgetKind.Button))
            add(Mutation.SetProp(control, PropertyKind.Text, PropertyValue.Text("Commit")))
            add(Mutation.SetProp(control, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Filled.ordinal + 1L)))
            if (padding != null) add(Mutation.SetModifier(control, 0, padding))
            add(Mutation.Insert(root, control, 0))
        }
        lateinit var bounds: Pair<DpRect, DpRect>
        runComposeUiTest {
            setContent {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
            waitForIdle()
            bounds = onNodeWithTag(nodeTestTag(control)).getBoundsInRoot() to
                onNodeWithText("Commit", useUnmergedTree = true).getBoundsInRoot()
        }
        return bounds
    }

    private val material = Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)

    @Test
    fun fr75_button_content_padding_sets_the_room_round_its_label() {
        val (button, label) = buttonAndLabel(ProtocolModifier.PaddingEach(12f, 30f, 12f, 30f))
        val start = (label.left - button.left).value
        val end = (button.right - label.right).value
        val above = (label.top - button.top).value
        val below = (button.bottom - label.bottom).value
        assertTrue(abs(start - 12f) <= 0.5f, "the label starts $start dp from the button's edge, not the 12 it asked for")
        assertTrue(abs(end - 12f) <= 0.5f, "the label ends $end dp from the button's edge, not the 12 it asked for")
        assertTrue(abs(above - 30f) <= 0.5f, "the label sits $above dp below the button's top, not the 30 it asked for")
        assertTrue(abs(below - 30f) <= 0.5f, "the label sits $below dp above the button's bottom, not the 30 it asked for")

        // The padding is the room inside the button, so none of it is added round it: the
        // button starts where one that asked for nothing starts.
        val (plain, _) = buttonAndLabel(null)
        assertTrue(
            abs((button.left - plain.left).value) <= 0.5f && abs((button.top - plain.top).value) <= 0.5f,
            "the button moved from (${plain.left}, ${plain.top}) to (${button.left}, ${button.top})",
        )
    }

    @Test
    fun fr75_button_without_content_padding_keeps_the_design_system_padding() {
        val theme = resolveTheme(material, HostPlatform.MacOs, systemDark = false)
        val style = theme.rules.button(ButtonVariant.Filled, theme)
        val (button, label) = buttonAndLabel(null)
        val start = (label.left - button.left).value
        val end = (button.right - label.right).value
        val wanted = style.horizontalPadding.value
        assertTrue(abs(start - wanted) <= 0.5f, "the label starts $start dp from the edge; the design system says $wanted")
        assertTrue(abs(end - wanted) <= 0.5f, "the label ends $end dp from the edge; the design system says $wanted")
        val height = (button.bottom - button.top).value
        assertTrue(
            height >= style.minHeight.value - 0.5f,
            "the button is $height dp tall, below the design system's ${style.minHeight}",
        )
    }
}
