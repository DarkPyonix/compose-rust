package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.DrawCommand
import dev.darkpyonix.composerust.protocol.DrawCommands
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
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

/**
 * A Canvas with a `Min`/`Max` reference coordinate space scales its drawing commands to the
 * container's measured width and height.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasViewBoxTest {

    private val page = Paint.Literal(0xFF102030.toInt())
    private val ink = Paint.Literal(0xFFE03020.toInt())

    @Test
    fun fr_63_fractional_canvas_scales_commands_to_each_container_width() = runComposeUiTest {
        val halfRect = DrawCommands.encode(
            listOf(
                DrawCommand.Rect(
                    paint = ink,
                    x = 0f,
                    y = 0f,
                    width = 0.5f,
                    height = 1f,
                    strokeWidth = 0f,
                ),
            ),
        )
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Column),
            // Narrow canvas: 80 dp wide -> 0.5 width fills left 40 dp.
            Mutation.Create(2, WidgetKind.Canvas),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(80f, 24f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.Background(page)),
            Mutation.SetProp(2, PropertyKind.Min, PropertyValue.Float(1f)),
            Mutation.SetProp(2, PropertyKind.Max, PropertyValue.Float(1f)),
            Mutation.SetProp(2, PropertyKind.Commands, PropertyValue.Bytes(halfRect)),
            // Wide canvas: 160 dp wide -> same 0.5 width fills left 80 dp.
            Mutation.Create(3, WidgetKind.Canvas),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(160f, 24f)),
            Mutation.SetModifier(3, 1, ProtocolModifier.Background(page)),
            Mutation.SetProp(3, PropertyKind.Min, PropertyValue.Float(1f)),
            Mutation.SetProp(3, PropertyKind.Max, PropertyValue.Float(1f)),
            Mutation.SetProp(3, PropertyKind.Commands, PropertyValue.Bytes(halfRect)),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 3, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val narrow = onNodeWithTag(nodeTestTag(2)).captureToImage().toPixelMap()
        assertEquals(Color(0xFFE03020), narrow[narrow.width / 4, narrow.height / 2], "left half of 80 dp canvas is filled")
        assertEquals(Color(0xFF102030), narrow[(narrow.width * 3) / 4, narrow.height / 2], "right half of 80 dp canvas is empty")

        val wide = onNodeWithTag(nodeTestTag(3)).captureToImage().toPixelMap()
        assertEquals(Color(0xFFE03020), wide[(wide.width * 3) / 8, wide.height / 2], "x = 60 dp inside 160 dp canvas is filled")
        assertEquals(Color(0xFF102030), wide[(wide.width * 3) / 4, wide.height / 2], "right half of 160 dp canvas is empty")
    }
}
