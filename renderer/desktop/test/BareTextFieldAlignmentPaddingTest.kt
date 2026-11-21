package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

/**
 * A single-line bare TextField centres its text vertically in its own height, and per-side
 * PaddingEach stays inside the clickable, focusable field.
 */
@OptIn(ExperimentalTestApi::class)
class BareTextFieldAlignmentPaddingTest {
    @Test
    fun single_line_bare_textfield_centres_text_and_padding_is_clickable() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(200f, 48f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(Paint.Literal(WHITE))),
            Mutation.Create(2, WidgetKind.TextField),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(200f, 48f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.PaddingEach(24f, 0f, 12f, 0f)),
            Mutation.SetProp(2, PropertyKind.Bare, PropertyValue.Bool(true)),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("MMMM")),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        // 1. Clicking inside the 24dp start padding focuses the field.
        val field = onNodeWithTag(nodeTestTag(2))
        field.performTouchInput { click(Offset(8f, 24f)) }
        waitForIdle()
        field.assertIsFocused()

        // 2. Text ink is vertically centred in the 48px-tall box, not stuck at the top edge.
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        var minY = pixels.height
        var maxY = -1
        for (y in 0 until pixels.height) {
            for (x in 20 until pixels.width - 12) {
                if (pixels[x, y].toArgb() != WHITE) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        assertTrue(maxY >= minY, "expected drawn text pixels")
        val centerY = (minY + maxY) / 2f
        val boxMid = pixels.height / 2f
        assertTrue(
            kotlin.math.abs(centerY - boxMid) <= 6f,
            "expected text vertically centred near $boxMid, got minY=$minY maxY=$maxY (center=$centerY)",
        )
    }
}
