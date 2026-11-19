package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ROOT = 1
private const val BOX = 2
private const val RED = 0xFFD01010.toInt()
private const val BLUE = 0xFF1010D0.toInt()

/** A Border modifier with width <= 0 draws nothing instead of a 1 px hairline. */
@OptIn(ExperimentalTestApi::class)
class BorderZeroWidthTest {
    private fun capture(width: Float) = run {
        val batch = listOf(
            Mutation.Create(ROOT, WidgetKind.Box),
            Mutation.Create(BOX, WidgetKind.Box),
            Mutation.SetModifier(BOX, 0, ProtocolModifier.Size(80f, 40f)),
            Mutation.SetModifier(BOX, 1, ProtocolModifier.Background(Paint.Literal(RED))),
            Mutation.SetModifier(BOX, 2, ProtocolModifier.Border(width, Paint.Literal(BLUE))),
            Mutation.Insert(ROOT, BOX, 0),
        )
        var image: androidx.compose.ui.graphics.ImageBitmap? = null
        runComposeUiTest {
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
            waitForIdle()
            image = onNodeWithTag(nodeTestTag(ROOT)).captureToImage()
        }
        image!!.toPixelMap()
    }

    @Test
    fun border_width_zero_draws_nothing() {
        val pixels = capture(0f)
        assertEquals(RED, pixels[pixels.width / 2, 0].toArgb())
        assertEquals(RED, pixels[0, pixels.height / 2].toArgb())
        assertEquals(RED, pixels[pixels.width - 1, pixels.height / 2].toArgb())
        assertEquals(RED, pixels[pixels.width / 2, pixels.height - 1].toArgb())
    }

    @Test
    fun border_positive_width_draws_border() {
        val pixels = capture(2f)
        assertEquals(BLUE, pixels[pixels.width / 2, 0].toArgb())
        assertEquals(RED, pixels[pixels.width / 2, 6].toArgb())
    }
}
