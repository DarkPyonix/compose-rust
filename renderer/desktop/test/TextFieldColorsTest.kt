package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.paintProp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TextFieldColorsTest {
    private val root = 1
    private val field = 2

    private val greenArgb = 0xFF00FF00.toInt()
    private val literalGreen = PropertyValue.Integer((2L shl 32) or (greenArgb.toLong() and 0xffffffffL))
    private val primaryRole = PropertyValue.Integer((1L shl 32) or (ColorRole.Primary.ordinal + 1).toLong())

    @Test
    fun fr56_text_field_honors_placeholder_and_caret_colors() {
        assertTrue(
            NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.PlaceholderColor),
            "TextField supports PlaceholderColor",
        )
        assertTrue(
            NodeTable.supportsProperty(WidgetKind.TextField, PropertyKind.CaretColor),
            "TextField supports CaretColor",
        )
        assertFalse(
            NodeTable.supportsProperty(WidgetKind.Button, PropertyKind.PlaceholderColor),
            "Button does not support PlaceholderColor",
        )
        assertFalse(
            NodeTable.supportsProperty(WidgetKind.Button, PropertyKind.CaretColor),
            "Button does not support CaretColor",
        )

        val mutations = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Cupertino, DesignSystem.Cupertino, ColorScheme.Light, false)),
            Mutation.Create(root, WidgetKind.Box),
            Mutation.Create(field, WidgetKind.TextField),
            Mutation.SetProp(field, PropertyKind.Placeholder, PropertyValue.Text("Search")),
            Mutation.SetProp(field, PropertyKind.PlaceholderColor, literalGreen),
            Mutation.SetProp(field, PropertyKind.CaretColor, primaryRole),
            Mutation.Insert(root, field, 0),
        )

        runComposeUiTest {
            val connection = FakeHostConnection(mutations)
            lateinit var hostState: ComposeRustHost
            setContent {
                val h = rememberComposeRustHost(connection)
                hostState = h
                ComposeRustContent(h)
            }
            waitForIdle()

            val node = hostState.table.node(field)
            assertNotNull(node, "field node exists")

            val placeholderPaint = node.paintProp(PropertyKind.PlaceholderColor)
            assertEquals(Paint.Literal(greenArgb), placeholderPaint, "placeholder paint matches literal")

            val caretPaint = node.paintProp(PropertyKind.CaretColor)
            assertEquals(Paint.Role(ColorRole.Primary), caretPaint, "caret paint matches role")

            val layouts = mutableListOf<TextLayoutResult>()
            collectLayouts(field, layouts)
            val placeholderLayout = layouts.find { it.layoutInput.text.text == "Search" }
            assertNotNull(placeholderLayout, "placeholder layout was collected")
            assertEquals(
                Color(greenArgb),
                placeholderLayout.layoutInput.style.color,
                "placeholder text layout color matches the specified literal color",
            )
        }
    }

    @Test
    fun fr56_default_field_keeps_design_system_colors() {
        val mutations = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Cupertino, DesignSystem.Cupertino, ColorScheme.Light, false)),
            Mutation.Create(root, WidgetKind.Box),
            Mutation.Create(field, WidgetKind.TextField),
            Mutation.SetProp(field, PropertyKind.Placeholder, PropertyValue.Text("Default")),
            Mutation.Insert(root, field, 0),
        )

        runComposeUiTest {
            val connection = FakeHostConnection(mutations)
            lateinit var hostState: ComposeRustHost
            setContent {
                val h = rememberComposeRustHost(connection)
                hostState = h
                ComposeRustContent(h)
            }
            waitForIdle()

            val node = hostState.table.node(field)
            assertNotNull(node, "field node exists")
            assertNull(node.paintProp(PropertyKind.PlaceholderColor), "no placeholder paint")
            assertNull(node.paintProp(PropertyKind.CaretColor), "no caret paint")

            val layouts = mutableListOf<TextLayoutResult>()
            collectLayouts(field, layouts)
            val placeholderLayout = layouts.find { it.layoutInput.text.text == "Default" }
            assertNotNull(placeholderLayout, "placeholder layout was collected")

            val resolved = resolveTheme(
                Theme(DesignSystem.Cupertino, DesignSystem.Cupertino, ColorScheme.Light, false),
                HostPlatform.Unknown,
                systemDark = false,
            )
            val expectedColor = resolved.color(ColorRole.OnSurfaceVariant).copy(alpha = 0.55f)
            assertEquals(
                expectedColor,
                placeholderLayout.layoutInput.style.color,
                "unconfigured placeholder color is OnSurfaceVariant with 55% alpha",
            )
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.collectLayouts(nodeId: Int, out: MutableList<TextLayoutResult>) {
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
            node.children.forEach(::walk)
        }
        walk(
            onNodeWithTag(nodeTestTag(nodeId), useUnmergedTree = true)
                .fetchSemanticsNode(),
        )
    }
}
