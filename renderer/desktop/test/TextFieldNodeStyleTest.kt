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
import kotlin.test.assertNotEquals

private const val FIELD = 1
private const val RED = 0xFFD01010.toInt()
private const val BLUE = 0xFF1010D0.toInt()

/** What a TextField node names for itself replaces the design system's frame. */
@OptIn(ExperimentalTestApi::class)
class TextFieldNodeStyleTest {
    private fun pixelsOf(vararg modifiers: Pair<Int, ProtocolModifier>) = buildList {
        add(Mutation.Create(FIELD, WidgetKind.TextField))
        add(Mutation.SetModifier(FIELD, 0, ProtocolModifier.Size(120f, 40f)))
        modifiers.forEach { (slot, value) -> add(Mutation.SetModifier(FIELD, slot, value)) }
    }

    private fun capture(batch: List<Mutation>) = run {
        var image: androidx.compose.ui.graphics.ImageBitmap? = null
        runComposeUiTest {
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
            waitForIdle()
            image = onNodeWithTag(nodeTestTag(FIELD)).captureToImage()
        }
        image!!.toPixelMap()
    }

    @Test
    fun fr_56_a_field_that_names_a_fill_is_filled_with_it() {
        val pixels = capture(pixelsOf(6 to ProtocolModifier.Background(Paint.Literal(RED))))
        assertEquals(RED, pixels[pixels.width - 8, pixels.height / 2].toArgbInt())
    }

    @Test
    fun fr_56_a_field_that_names_a_border_draws_that_border_and_not_the_systems() {
        val pixels = capture(
            pixelsOf(
                6 to ProtocolModifier.Background(Paint.Literal(RED)),
                7 to ProtocolModifier.Border(2f, Paint.Literal(BLUE)),
            ),
        )
        assertEquals(BLUE, pixels[pixels.width / 2, 0].toArgbInt())
        assertEquals(RED, pixels[pixels.width / 2, 6].toArgbInt())
    }

    @Test
    fun fr_56_a_field_that_names_nothing_keeps_the_systems_frame() {
        val plain = capture(pixelsOf())
        assertNotEquals(RED, plain[plain.width - 8, plain.height / 2].toArgbInt())
    }

    @Test
    fun fr_56_a_field_that_names_a_corner_is_cut_to_it() {
        val pixels = capture(
            pixelsOf(
                5 to ProtocolModifier.Shape(20f, 20f, 20f, 20f),
                6 to ProtocolModifier.Background(Paint.Literal(RED)),
            ),
        )
        assertNotEquals(RED, pixels[0, 0].toArgbInt(), "the corner pixel is outside a 20 dp corner")
        assertEquals(RED, pixels[pixels.width - 8, pixels.height / 2].toArgbInt())
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = this.toArgb()
