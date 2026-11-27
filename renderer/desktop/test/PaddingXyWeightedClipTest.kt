package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CARD = 1
private const val ROW = 2
private const val COL = 3
private const val CANVAS = 4
private const val SPACER = 5

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val BORDER_GRAY = 0xFFCCCCCC.toInt()

/**
 * Regression test for #982: a weighted Row (`WeightedStack`) must not implicitly clip its
 * children to bounds when `Modifier.clip` is not set. A 1 px stroke at `x = 0` inside a
 * bordered Box with `PaddingEach(20, 16, 20, 16)` -> `Row` -> `Column(Weight(1))` -> `Canvas`
 * must stay unclipped at `x = 19` and match `Padding(16) + Spacer(4)` pixel-for-pixel.
 */
@OptIn(ExperimentalTestApi::class)
class PaddingXyWeightedClipTest {
    private fun encodeRoundRectCommand(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        strokeWidth: Float,
        argb: Int,
    ): ByteArray {
        val paintLiteralBits = (2L shl 32) or (argb.toLong() and 0xffffffffL)
        val buf = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(3) // DrawCommand.RoundRect tag
        buf.putShort(36) // DrawCommands.COMMAND_LENGTH
        buf.putLong(paintLiteralBits)
        buf.putFloat(x)
        buf.putFloat(y)
        buf.putFloat(w)
        buf.putFloat(h)
        buf.putFloat(radius)
        buf.putFloat(strokeWidth)
        return buf.array()
    }

    private fun renderCard(usePaddingEach: Boolean): PixelMap {
        val commands = encodeRoundRectCommand(
            x = 0f,
            y = 4f,
            w = 20f,
            h = 50f,
            radius = 3f,
            strokeWidth = 1f,
            argb = BLACK,
        )
        val batch = buildList {
            add(Mutation.Create(CARD, WidgetKind.Box))
            add(Mutation.SetModifier(CARD, 0, ProtocolModifier.Size(240f, 120f)))
            add(Mutation.SetModifier(CARD, 14, ProtocolModifier.Background(Paint.Literal(WHITE))))
            add(Mutation.SetModifier(CARD, 19, ProtocolModifier.Border(1f, Paint.Literal(BORDER_GRAY))))
            if (usePaddingEach) {
                add(Mutation.SetModifier(CARD, 22, ProtocolModifier.PaddingEach(20f, 16f, 20f, 16f)))
            } else {
                add(Mutation.SetModifier(CARD, 22, ProtocolModifier.Padding(16f)))
            }

            add(Mutation.Create(ROW, WidgetKind.Row))
            add(Mutation.SetModifier(ROW, 1, ProtocolModifier.FillMaxWidth))

            add(Mutation.Create(COL, WidgetKind.Column))
            add(Mutation.SetModifier(COL, 0, ProtocolModifier.Weight(1f)))

            add(Mutation.Create(CANVAS, WidgetKind.Canvas))
            add(Mutation.SetModifier(CANVAS, 0, ProtocolModifier.Size(100f, 60f)))
            add(Mutation.SetProp(CANVAS, PropertyKind.Commands, PropertyValue.Bytes(commands)))
            add(Mutation.Insert(COL, CANVAS, 0))

            if (usePaddingEach) {
                add(Mutation.Insert(ROW, COL, 0))
            } else {
                add(Mutation.Create(SPACER, WidgetKind.Spacer))
                add(Mutation.SetModifier(SPACER, 0, ProtocolModifier.Width(4f)))
                add(Mutation.Insert(ROW, SPACER, 0))
                add(Mutation.Insert(ROW, COL, 1))
            }
            add(Mutation.Insert(CARD, ROW, 0))
        }

        var pixels: PixelMap? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
                }
            }
            waitForIdle()
            pixels = onRoot().captureToImage().toPixelMap()
        }
        return pixels!!
    }

    @Test
    fun fr70_padding_xy_weighted_row_does_not_clip_left_stroke_and_matches_spacer() {
        val withPaddingEach = renderCard(usePaddingEach = true)
        val withPaddingAndSpacer = renderCard(usePaddingEach = false)

        assertEquals(withPaddingAndSpacer.width, withPaddingEach.width)
        assertEquals(withPaddingAndSpacer.height, withPaddingEach.height)

        // Left half of the 1 px stroke centered at x = 0 inside the Canvas (card x = 20)
        // lands on pixel column x = 19. Implicit clipping on WeightedStack would erase it to white.
        val strokePixel = withPaddingEach[19, 16 + 20]
        assertTrue(
            strokePixel.red < 0.9f && strokePixel.green < 0.9f && strokePixel.blue < 0.9f,
            "Expected unclipped stroke pixel at (19, 36), got $strokePixel",
        )
        assertEquals(
            withPaddingAndSpacer[19, 16 + 20],
            strokePixel,
            "Stroke pixel at (19, 36) should match Padding(16) + Spacer(4)",
        )

        for (y in 0 until withPaddingEach.height) {
            for (x in 0 until withPaddingEach.width) {
                assertEquals(
                    withPaddingAndSpacer[x, y],
                    withPaddingEach[x, y],
                    "Pixel mismatch at ($x, $y)",
                )
            }
        }
    }
}
