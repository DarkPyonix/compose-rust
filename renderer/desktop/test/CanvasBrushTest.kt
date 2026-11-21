package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue

private const val RED = 0xFFFF0000.toInt()
private const val BLUE = 0xFF0000FF.toInt()
private const val GRADIENT_ASSET = 77
private const val BRUSH_KIND = 6

/** A Canvas draw command painted with a registered Brush draws the gradient, not a flat colour. */
@OptIn(ExperimentalTestApi::class)
class CanvasBrushTest {
    private fun horizontalGradient(): ByteArray {
        val buffer = ByteBuffer.allocate(24 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(1) // linear
        buffer.putShort(2) // two stops
        buffer.putShort(1) // clamp
        buffer.putShort(0)
        for (coordinate in floatArrayOf(0f, 0.5f, 1f, 0.5f)) buffer.putFloat(coordinate)
        buffer.putFloat(0f).putInt(RED)
        buffer.putFloat(1f).putInt(BLUE)
        return buffer.array()
    }

    private fun encodeRectCommand(x: Float, y: Float, w: Float, h: Float, strokeWidth: Float, paintBits: Long): ByteArray {
        val buf = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(2) // DrawCommand.Rect tag
        buf.putShort(36) // COMMAND_LENGTH
        buf.putLong(paintBits)
        buf.putFloat(x)
        buf.putFloat(y)
        buf.putFloat(w)
        buf.putFloat(h)
        buf.putFloat(strokeWidth)
        buf.putInt(0)
        return buf.array()
    }

    @Test
    fun canvas_draw_command_paints_registered_brush_gradient() = runComposeUiTest {
        // Paint.Asset(id) wire encoding: kind 3 in high 32 bits, asset id in low 32 bits.
        val paintAssetBits = (3L shl 32) or (GRADIENT_ASSET.toLong() and 0xffffffffL)
        val commands = encodeRectCommand(0f, 0f, 100f, 40f, 0f, paintAssetBits)
        val batch = listOf(
            Mutation.RegisterAsset(GRADIENT_ASSET, BRUSH_KIND, horizontalGradient()),
            Mutation.Create(1, WidgetKind.Canvas),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(100f, 40f)),
            Mutation.SetProp(1, PropertyKind.Commands, PropertyValue.Bytes(commands)),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        val left = pixels[5, pixels.height / 2]
        val right = pixels[pixels.width - 6, pixels.height / 2]
        assertTrue(left.red > 0.7f && left.blue < 0.3f, "left edge should be red, got $left")
        assertTrue(right.blue > 0.7f && right.red < 0.3f, "right edge should be blue, got $right")
    }
}
