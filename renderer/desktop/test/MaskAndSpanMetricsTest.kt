package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TypeRole
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
private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val TRANSPARENT = 0x00000000
private const val BRUSH_KIND = 6
private const val MASK_ASSET = 88

@OptIn(ExperimentalTestApi::class)
class MaskAndSpanMetricsTest {
    private fun fadeGradient(): ByteArray {
        val buffer = ByteBuffer.allocate(24 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(1) // linear
        buffer.putShort(2) // two stops
        buffer.putShort(1) // clamp
        buffer.putShort(0)
        for (c in floatArrayOf(0f, 0.5f, 1f, 0.5f)) buffer.putFloat(c)
        buffer.putFloat(0f).putInt(OPAQUE_BLACK)
        buffer.putFloat(1f).putInt(TRANSPARENT)
        return buffer.array()
    }

    private fun spanBytes(start: Int, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(start)
        buf.putInt(length)
        return buf.array()
    }

    private fun spanMetricsBlob(span: Int, fontSize: Float, letterSpacing: Float): ByteArray {
        val buf = ByteBuffer.allocate(4 + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(1)
        buf.putInt(span)
        buf.putFloat(fontSize)
        buf.putFloat(letterSpacing)
        buf.putInt(0)
        return buf.array()
    }

    @Test
    fun fr49_gradient_mask_fades_node_and_children() = runComposeUiTest {
        val white = 0xFFFFFFFF.toInt()
        val batch = listOf(
            Mutation.RegisterAsset(MASK_ASSET, BRUSH_KIND, fadeGradient()),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(100f, 40f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(white))),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(100f, 40f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.Mask(MASK_ASSET, 0, 0f, 0f, 0f, 0f)),
            Mutation.SetModifier(2, 2, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        val left = pixels[4, pixels.height / 2]
        val right = pixels[pixels.width - 5, pixels.height / 2]
        assertTrue(left.red > 0.7f && left.green < 0.3f, "left edge should stay red, got $left")
        assertTrue(right.green > 0.7f && right.blue > 0.7f, "right edge should fade to white, got $right")
    }

    @Test
    fun nfr7_unregistered_mask_brush_is_a_protocol_error() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(80f, 40f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.SetModifier(1, 2, ProtocolModifier.Mask(9999, 0, 0f, 0f, 0f, 0f)),
        )
        val host = FakeHostConnection(batch)
        setContent { ComposeRustContent(rememberComposeRustHost(host)) }
        waitForIdle()

        assertTrue(host.events.any { it is HostEvent.ProtocolError }, "expected ProtocolError for unknown mask brush")
    }

    @Test
    fun fr40_span_font_size_draws_larger_and_ignored_when_text_has_a_role() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Column),
            // Plain no-role text at 14dp.
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(2, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Hello")),
            // No-role text with SpanMetrics bumping span 0 to 28dp.
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(3, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.SetProp(3, PropertyKind.Spans, PropertyValue.Bytes(spanBytes(0, 5))),
            Mutation.SetProp(3, PropertyKind.SpanMetrics, PropertyValue.Bytes(spanMetricsBlob(0, 28f, Float.NaN))),
            // Text WITH a role (Body) ignores SpanMetrics.
            Mutation.Create(4, WidgetKind.Text),
            Mutation.SetProp(4, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Body.ordinal.toLong() + 1L)),
            Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.SetProp(4, PropertyKind.Spans, PropertyValue.Bytes(spanBytes(0, 5))),
            Mutation.SetProp(4, PropertyKind.SpanMetrics, PropertyValue.Bytes(spanMetricsBlob(0, 28f, Float.NaN))),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 3, 1),
            Mutation.Insert(1, 4, 2),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val plainH = onNodeWithTag(nodeTestTag(2)).getBoundsInRoot().let { it.bottom - it.top }
        val bigH = onNodeWithTag(nodeTestTag(3)).getBoundsInRoot().let { it.bottom - it.top }
        val roleH = onNodeWithTag(nodeTestTag(4)).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(bigH > plainH, "SpanMetrics 28dp ($bigH) must be taller than 14dp ($plainH)")
        assertTrue(roleH < bigH, "Text with a role ($roleH) must ignore SpanMetrics 28dp ($bigH)")
    }
}
