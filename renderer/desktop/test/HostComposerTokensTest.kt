package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
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
 * FR-75: TextField typography and control over composer and icon button tokens.
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
}
