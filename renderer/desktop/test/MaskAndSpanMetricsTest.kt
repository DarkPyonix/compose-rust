package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MeasureRecords
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val BLUE = 0xFF0000FF.toInt()
private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val TRANSPARENT = 0x00000000
private const val PNG_KIND = 1
private const val BRUSH_KIND = 6
private const val MASK_ASSET = 88
private const val MASK_PNG_ASSET = 89
private const val MASK_IMG_BRUSH_ASSET = 90

private fun maskFlags(repeat: Int, sizeMode: Int): Int = (repeat and 0xff) or ((sizeMode and 0xff) shl 8)

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

    private fun solidPng(width: Int, height: Int, opaqueWidth: Int = width): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, if (x < opaqueWidth) WHITE else TRANSPARENT)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun imageBrush(imageAssetId: Int): ByteArray {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(3) // KIND_IMAGE
        buffer.putShort(0) // 0 stops
        buffer.putShort(1) // clamp
        buffer.putShort(0)
        buffer.putInt(imageAssetId)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)
        return buffer.array()
    }

    private fun spanBytes(vararg ranges: Pair<Int, Int>): ByteArray {
        val buf = ByteBuffer.allocate(36 * ranges.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((start, length) in ranges) {
            val base = buf.position()
            buf.putInt(base, start)
            buf.putInt(base + 4, length)
            buf.position(base + 36)
        }
        return buf.array()
    }

    private fun spanMetricsEntries(vararg entries: Triple<Int, Float, Float>): ByteArray {
        val buf = ByteBuffer.allocate(16 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((span, fontSize, letterSpacing) in entries) {
            buf.putInt(span)
            buf.putFloat(fontSize)
            buf.putFloat(letterSpacing)
            buf.putInt(0)
        }
        return buf.array()
    }

    private fun spanMetricsBlob(vararg entries: Triple<Int, Float, Float>): ByteArray {
        val body = spanMetricsEntries(*entries)
        val buf = ByteBuffer.allocate(4 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(entries.size)
        buf.put(body)
        return buf.array()
    }

    @Test
    fun fr49_gradient_mask_fades_node_and_children() = runComposeUiTest {
        val batch = listOf(
            Mutation.RegisterAsset(MASK_ASSET, BRUSH_KIND, fadeGradient()),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(100f, 40f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(WHITE))),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(100f, 40f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.Mask(MASK_ASSET, 0, 0f, 0f, 0f, 0f)),
            Mutation.SetModifier(2, 2, ProtocolModifier.Background(Paint.Literal(RED))),
            // Child box spanning the full width so we verify children are masked too.
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(100f, 20f)),
            Mutation.SetModifier(3, 1, ProtocolModifier.Background(Paint.Literal(BLUE))),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        // Top half shows the blue child; bottom half shows the red parent.
        val childLeft = pixels[4, 8]
        val childRight = pixels[pixels.width - 5, 8]
        val parentLeft = pixels[4, pixels.height - 8]
        val parentRight = pixels[pixels.width - 5, pixels.height - 8]
        assertTrue(childLeft.blue > 0.7f && childLeft.red < 0.3f, "child left should stay blue, got $childLeft")
        assertTrue(childRight.red > 0.7f && childRight.green > 0.7f, "child right should fade to white, got $childRight")
        assertTrue(parentLeft.red > 0.7f && parentLeft.green < 0.3f, "parent left should stay red, got $parentLeft")
        assertTrue(parentRight.green > 0.7f && parentRight.blue > 0.7f, "parent right should fade to white, got $parentRight")
    }

    @Test
    fun fr49_image_mask_respects_repeat() = runComposeUiTest {
        // 16x16 image: left 8px opaque, right 8px transparent.
        val png = solidPng(16, 16, opaqueWidth = 8)
        fun makeBatch(repeat: Int) = listOf(
            Mutation.RegisterAsset(MASK_PNG_ASSET, PNG_KIND, png),
            Mutation.RegisterAsset(MASK_IMG_BRUSH_ASSET, BRUSH_KIND, imageBrush(MASK_PNG_ASSET)),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(64f, 32f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(WHITE))),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(64f, 32f)),
            Mutation.SetModifier(
                2,
                1,
                ProtocolModifier.Mask(MASK_IMG_BRUSH_ASSET, maskFlags(repeat = repeat, sizeMode = 0), 0f, 0f, 0f, 0f),
            ),
            Mutation.SetModifier(2, 2, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.Insert(1, 2, 0),
        )

        // NoRepeat (3): only x in [0, 8), y in [0, 16) is opaque; x = 20 is completely transparent (white).
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(makeBatch(3)))) }
        waitForIdle()
        val noRepeat = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        val nrInside = noRepeat[4, 8]
        val nrGap = noRepeat[12, 8]
        val nrOutside = noRepeat[20, 8]
        assertTrue(nrInside.red > 0.8f && nrInside.green < 0.2f, "NoRepeat inside opaque half must be red, got $nrInside")
        assertTrue(nrGap.green > 0.8f && nrGap.blue > 0.8f, "NoRepeat transparent half must be white, got $nrGap")
        assertTrue(nrOutside.green > 0.8f && nrOutside.blue > 0.8f, "NoRepeat outside tile must be white, got $nrOutside")
    }

    @Test
    fun fr49_image_mask_tiles_when_repeat_is_enabled() = runComposeUiTest {
        val png = solidPng(16, 16, opaqueWidth = 8)
        val batch = listOf(
            Mutation.RegisterAsset(MASK_PNG_ASSET, PNG_KIND, png),
            Mutation.RegisterAsset(MASK_IMG_BRUSH_ASSET, BRUSH_KIND, imageBrush(MASK_PNG_ASSET)),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(64f, 32f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(WHITE))),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(64f, 32f)),
            Mutation.SetModifier(
                2,
                1,
                ProtocolModifier.Mask(MASK_IMG_BRUSH_ASSET, maskFlags(repeat = 0, sizeMode = 0), 0f, 0f, 0f, 0f),
            ),
            Mutation.SetModifier(2, 2, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val repeated = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        val tile1Opaque = repeated[4, 8]
        val tile2Opaque = repeated[20, 8]
        val tile2Transparent = repeated[28, 8]
        val row2Opaque = repeated[20, 24]
        assertTrue(tile1Opaque.red > 0.8f && tile1Opaque.green < 0.2f, "tile 1 opaque must be red, got $tile1Opaque")
        assertTrue(tile2Opaque.red > 0.8f && tile2Opaque.green < 0.2f, "tile 2 opaque must be red, got $tile2Opaque")
        assertTrue(tile2Transparent.green > 0.8f && tile2Transparent.blue > 0.8f, "tile 2 transparent half must be white, got $tile2Transparent")
        assertTrue(row2Opaque.red > 0.8f && row2Opaque.green < 0.2f, "row 2 tile must be red, got $row2Opaque")
    }

    @Test
    fun fr49_mask_size_and_position() = runComposeUiTest {
        // 10x10 solid opaque mask, Explicit 20x20 placed at (30, 10) with NoRepeat.
        val png = solidPng(10, 10)
        val batch = listOf(
            Mutation.RegisterAsset(MASK_PNG_ASSET, PNG_KIND, png),
            Mutation.RegisterAsset(MASK_IMG_BRUSH_ASSET, BRUSH_KIND, imageBrush(MASK_PNG_ASSET)),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(80f, 40f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(WHITE))),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(80f, 40f)),
            Mutation.SetModifier(
                2,
                1,
                ProtocolModifier.Mask(
                    MASK_IMG_BRUSH_ASSET,
                    maskFlags(repeat = 3, sizeMode = 3),
                    20f,
                    20f,
                    30f,
                    10f,
                ),
            ),
            Mutation.SetModifier(2, 2, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        val before = pixels[10, 20]
        val inside = pixels[40, 20]
        val after = pixels[65, 20]
        assertTrue(before.green > 0.8f && before.blue > 0.8f, "left of positioned mask must be white, got $before")
        assertTrue(inside.red > 0.8f && inside.green < 0.2f, "inside positioned 20x20 mask must be red, got $inside")
        assertTrue(after.green > 0.8f && after.blue > 0.8f, "right of positioned mask must be white, got $after")
    }

    @Test
    fun fr49_mask_composes_with_alpha_and_clip() = runComposeUiTest {
        val batch = listOf(
            Mutation.RegisterAsset(MASK_ASSET, BRUSH_KIND, fadeGradient()),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(WHITE))),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.Shape(40f, 40f, 40f, 40f)),
            Mutation.SetModifier(2, 2, ProtocolModifier.Clip(true)),
            Mutation.SetModifier(2, 3, ProtocolModifier.Alpha(0.5f)),
            Mutation.SetModifier(2, 4, ProtocolModifier.Mask(MASK_ASSET, 0, 0f, 0f, 0f, 0f)),
            Mutation.SetModifier(2, 5, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        // Top-left corner is outside the 40dp circle clip -> white.
        val corner = pixels[2, 2]
        // Left-middle is inside clip, masked ~opaque, alpha 0.5 -> blended pink (green ~0.5).
        val leftMid = pixels[8, 40]
        // Right-middle is faded out by gradient mask -> near white.
        val rightMid = pixels[74, 40]
        assertTrue(corner.green > 0.9f && corner.blue > 0.9f, "clipped corner must be white, got $corner")
        assertTrue(leftMid.red > 0.8f && leftMid.green in 0.3f..0.75f, "left interior must be half-alpha red, got $leftMid")
        assertTrue(rightMid.green > 0.85f && rightMid.blue > 0.85f, "right interior must fade to white, got $rightMid")
    }

    @Test
    fun nfr7_unregistered_mask_brush_is_a_protocol_error() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(80f, 40f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Mask(9999, 0, 0f, 0f, 0f, 0f)),
            Mutation.SetModifier(1, 2, ProtocolModifier.Background(Paint.Literal(RED))),
        )
        val host = FakeHostConnection(batch)
        setContent { ComposeRustContent(rememberComposeRustHost(host)) }
        waitForIdle()

        assertTrue(host.events.any { it is HostEvent.ProtocolError }, "expected ProtocolError for unknown mask brush")
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        val right = pixels[pixels.width - 5, pixels.height / 2]
        assertTrue(right.red > 0.8f && right.green < 0.2f, "unregistered mask must draw node unmasked, got $right")
    }

    @Test
    fun fr49_mask_does_not_affect_hit_testing() = runComposeUiTest {
        val batch = listOf(
            Mutation.RegisterAsset(MASK_ASSET, BRUSH_KIND, fadeGradient()),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(100f, 40f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Mask(MASK_ASSET, 0, 0f, 0f, 0f, 0f)),
            Mutation.SetModifier(1, 2, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.SetModifier(1, 3, ProtocolModifier.Clickable(77)),
        )
        val host = FakeHostConnection(batch)
        setContent { ComposeRustContent(rememberComposeRustHost(host)) }
        waitForIdle()

        // Click on the far-right alpha=0 region of the masked box.
        onNodeWithTag(nodeTestTag(1)).performTouchInput {
            click(Offset(width - 2f, height / 2f))
        }
        waitForIdle()
        assertTrue(
            host.events.any { it is HostEvent.Clicked && it.nodeId == 1 && it.handlerId == 77L },
            "alpha-0 area of a masked node must still receive clicks, got ${host.events}",
        )
    }

    @Test
    fun fr40_span_metrics_out_of_range_is_protocol_error() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Text),
            Mutation.SetProp(1, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(1, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(1, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.SetProp(1, PropertyKind.Spans, PropertyValue.Bytes(spanBytes(0 to 5))),
            // Span index 3 is out of range (only 1 span exists).
            Mutation.SetProp(1, PropertyKind.SpanMetrics, PropertyValue.Bytes(spanMetricsBlob(Triple(3, 28f, Float.NaN)))),
        )
        val host = FakeHostConnection(batch)
        startHost(host)
        assertTrue(
            host.events.any { it is HostEvent.ProtocolError },
            "out-of-range SpanMetrics must report ProtocolError",
        )

        // Measurement with out-of-range span index returns STATUS_MALFORMED.
        val (status, results) = measure(
            MeasureRequests().text(
                "Hello",
                role = 0,
                width = 200f,
                fontSize = 14f,
                spans = spanBytes(0 to 5),
                spanMetrics = spanMetricsEntries(Triple(3, 28f, Float.NaN)),
            ),
        )
        assertEquals(MeasureRecords.CALL_OK, status)
        assertEquals(MeasureRecords.STATUS_MALFORMED, results.single().status)
    }

    @Test
    fun fr40_span_font_size_draws_larger() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(2, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(3, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.SetProp(3, PropertyKind.Spans, PropertyValue.Bytes(spanBytes(0 to 5))),
            Mutation.SetProp(3, PropertyKind.SpanMetrics, PropertyValue.Bytes(spanMetricsBlob(Triple(0, 28f, Float.NaN)))),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 3, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val plainH = onNodeWithTag(nodeTestTag(2)).getBoundsInRoot().let { it.bottom - it.top }
        val bigH = onNodeWithTag(nodeTestTag(3)).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(bigH > plainH, "SpanMetrics 28dp ($bigH) must be taller than 14dp ($plainH)")
    }

    @Test
    fun fr40_span_letter_spacing_widens_the_run() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(2, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("ABCDEFGH")),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(3, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("ABCDEFGH")),
            Mutation.SetProp(3, PropertyKind.Spans, PropertyValue.Bytes(spanBytes(0 to 8))),
            Mutation.SetProp(3, PropertyKind.SpanMetrics, PropertyValue.Bytes(spanMetricsBlob(Triple(0, Float.NaN, 4f)))),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 3, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val plainW = onNodeWithTag(nodeTestTag(2)).getBoundsInRoot().let { it.right - it.left }
        val wideW = onNodeWithTag(nodeTestTag(3)).getBoundsInRoot().let { it.right - it.left }
        assertTrue(wideW > plainW, "SpanMetrics letter_spacing 4dp ($wideW) must widen run over plain ($plainW)")
    }

    @Test
    fun fr40_span_metrics_ignored_when_text_has_a_role() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Body.ordinal.toLong() + 1L)),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Body.ordinal.toLong() + 1L)),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Hello")),
            Mutation.SetProp(3, PropertyKind.Spans, PropertyValue.Bytes(spanBytes(0 to 5))),
            Mutation.SetProp(3, PropertyKind.SpanMetrics, PropertyValue.Bytes(spanMetricsBlob(Triple(0, 28f, 4f)))),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 3, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val plain = onNodeWithTag(nodeTestTag(2)).getBoundsInRoot()
        val withMetrics = onNodeWithTag(nodeTestTag(3)).getBoundsInRoot()
        assertEquals(plain.right - plain.left, withMetrics.right - withMetrics.left, "role text must ignore SpanMetrics width")
        assertEquals(plain.bottom - plain.top, withMetrics.bottom - withMetrics.top, "role text must ignore SpanMetrics height")
    }

    @Test
    fun pr2_measured_text_matches_the_drawn_text_with_span_metrics() = runComposeUiTest {
        val text = "본문 14와 큰 글자 그리고 자간이 들어간 한국어 문단"
        val utf8 = text.encodeToByteArray()
        val bigStart = "본문 14와 ".encodeToByteArray().size
        val bigLen = "큰 글자".encodeToByteArray().size
        val spacedStart = "본문 14와 큰 글자 ".encodeToByteArray().size
        val spacedLen = "그리고 자간이".encodeToByteArray().size
        val spans = spanBytes(bigStart to bigLen, spacedStart to spacedLen)
        val metricsEntries = spanMetricsEntries(
            Triple(0, 28f, Float.NaN),
            Triple(1, Float.NaN, 2f),
        )
        val metricsBlob = spanMetricsBlob(
            Triple(0, 28f, Float.NaN),
            Triple(1, Float.NaN, 2f),
        )
        val widthDp = 180f
        val zoom = 1.5f
        val batch = listOf(
            Mutation.Create(1, WidgetKind.AbsoluteBox),
            Mutation.SetProp(1, PropertyKind.IslandZoom, PropertyValue.Float(zoom)),
            Mutation.Create(2, WidgetKind.Column),
            Mutation.SetModifier(2, 0, ProtocolModifier.Width(widthDp)),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.TypeRole, PropertyValue.Integer(0L)),
            Mutation.SetProp(3, PropertyKind.FontSize, PropertyValue.Float(14f)),
            Mutation.SetProp(3, PropertyKind.AbsoluteSize, PropertyValue.Bool(true)),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text(text)),
            Mutation.SetProp(3, PropertyKind.Spans, PropertyValue.Bytes(spans)),
            Mutation.SetProp(3, PropertyKind.SpanMetrics, PropertyValue.Bytes(metricsBlob)),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(1, 2, 0),
        )
        startHost(FakeHostConnection(batch), density = Density(density = 1f, fontScale = 1.5f))
        val (status, results) = measure(
            MeasureRequests().text(
                text = text,
                role = 0,
                width = widthDp,
                fontSize = 14f,
                absoluteSize = true,
                spans = spans,
                spanMetrics = metricsEntries,
                zoom = zoom,
            ),
        )
        assertEquals(MeasureRecords.CALL_OK, status)
        assertTrue(utf8.isNotEmpty())
        assertSameAsDrawn(results.single(), drawnLayout(3), zoom, "span metrics + Korean + absolute_size + zoom 1.5")
    }

    @Test
    fun fr49_and_fr40_checked_in_vector_decodes() {
        val bytes = vectorFile("mask_and_span_metrics.bin").readBytes()
        val mutations = mutableListOf<Mutation>()
        dev.darkpyonix.composerust.protocol.Protocol.decode(
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            mutations::add,
        )
        assertEquals(2, mutations.size)
        val mask = mutations[0] as Mutation.SetModifier
        assertEquals(4, mask.nodeId)
        assertEquals(0, mask.index)
        assertEquals(
            ProtocolModifier.Mask(9, maskFlags(repeat = 3, sizeMode = 2), 64f, 32f, 4f, 2f),
            mask.modifier,
        )
        val metrics = mutations[1] as Mutation.SetProp
        assertEquals(24, metrics.nodeId)
        assertEquals(PropertyKind.SpanMetrics, metrics.property)
        val decoded = dev.darkpyonix.composerust.foundation.decodeSpanMetricsBlob(
            (metrics.value as PropertyValue.Bytes).value,
        ) { error -> throw AssertionError("unexpected decode error: $error") }
        assertEquals(2, decoded!!.size)
        assertEquals(28f, decoded[1]!!.fontSize)
        assertEquals(2f, decoded[4]!!.letterSpacing)
    }

    private fun vectorFile(name: String): java.io.File {
        var directory: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = java.io.File(directory, "compose-rust/tests/vectors/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("protocol vector $name not found above ${System.getProperty("user.dir")}")
    }
}
