package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.height
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PAGE = 1
private const val HOLDER = 2
private const val FIELD = 3
private const val LINE = 4
private const val OPTION = 5
private const val RED = 0xFFD01010.toInt()
private const val BLUE = 0xFF1010D0.toInt()
private const val ABOVE = 6
private const val BELOW = 7

/**
 * A bare field draws none of the design system's frame: every pixel that is not a letter is
 * the colour of what it sits on, and it is as tall as one line of its text.
 */
@OptIn(ExperimentalTestApi::class)
class BareFieldTest {
    private class Drawn(val pixels: androidx.compose.ui.graphics.PixelMap, val fieldHeight: Float, val lineHeight: Float)

    /**
     * A red page holding, in a box the field's own size, the bare [widget], and beside it a
     * plain `Text` set in the same default style, whose height is one line.
     */
    private fun draw(widget: WidgetKind, vararg extra: Mutation): Drawn {
        val batch = buildList {
            add(Mutation.Create(PAGE, WidgetKind.Column))
            add(Mutation.SetModifier(PAGE, 0, ProtocolModifier.Background(Paint.Literal(RED))))
            add(Mutation.Create(HOLDER, WidgetKind.Box))
            add(Mutation.Create(FIELD, widget))
            add(Mutation.SetModifier(FIELD, 0, ProtocolModifier.Width(160f)))
            add(Mutation.SetProp(FIELD, PropertyKind.Bare, PropertyValue.Bool(true)))
            if (widget == WidgetKind.Dropdown) {
                add(Mutation.SetProp(FIELD, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
                add(Mutation.Create(OPTION, WidgetKind.Text))
                add(Mutation.SetProp(OPTION, PropertyKind.Text, PropertyValue.Text("")))
                add(Mutation.Insert(FIELD, OPTION, 0))
            }
            addAll(extra)
            add(Mutation.Insert(HOLDER, FIELD, 0))
            add(Mutation.Insert(PAGE, HOLDER, 0))
            add(Mutation.Create(LINE, WidgetKind.Text))
            add(Mutation.SetProp(LINE, PropertyKind.Text, PropertyValue.Text("Ag")))
            add(Mutation.Insert(PAGE, LINE, 1))
        }
        var image: ImageBitmap? = null
        var fieldHeight = 0f
        var lineHeight = 0f
        runComposeUiTest {
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
            waitForIdle()
            image = onNodeWithTag(nodeTestTag(HOLDER)).captureToImage()
            fieldHeight = onNodeWithTag(nodeTestTag(FIELD)).getUnclippedBoundsInRoot().height.value
            lineHeight = onNodeWithTag(nodeTestTag(LINE)).getUnclippedBoundsInRoot().height.value
        }
        return Drawn(image!!.toPixelMap(), fieldHeight, lineHeight)
    }

    @Test
    fun fr56_bare_field_draws_no_frame() {
        // No text and no placeholder, so nothing in the field is a letter.
        val drawn = draw(WidgetKind.TextField)
        val pixels = drawn.pixels
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                assertEquals(
                    RED,
                    pixels[x, y].toArgb(),
                    "pixel ($x, $y) of a bare field is the page's colour, not a fill, line or underline",
                )
            }
        }
        assertEquals(
            drawn.lineHeight,
            drawn.fieldHeight,
            0.5f,
            "a bare field is one line of text tall, with no minimum height or inner padding",
        )
    }

    @Test
    fun fr56_bare_dropdown_draws_no_frame() {
        // The option is empty, so the only letter is the marker at the end.
        val drawn = draw(WidgetKind.Dropdown)
        val pixels = drawn.pixels
        val markerZone = pixels.width * 3 / 4
        for (y in 0 until pixels.height) {
            for (x in 0 until markerZone) {
                assertEquals(
                    RED,
                    pixels[x, y].toArgb(),
                    "pixel ($x, $y) of a bare select is the page's colour, not a fill or line",
                )
            }
        }
        for (x in 0 until pixels.width) {
            assertEquals(RED, pixels[x, 0].toArgb(), "the top edge of a bare select has no line at $x")
            assertEquals(RED, pixels[x, pixels.height - 1].toArgb(), "the bottom edge has no line at $x")
        }
        assertEquals(
            drawn.lineHeight,
            drawn.fieldHeight,
            0.5f,
            "a bare select is one line of text tall, with no inner padding",
        )
    }

    @Test
    fun fr56_bare_field_still_draws_a_node_background() {
        val drawn = draw(
            WidgetKind.TextField,
            Mutation.SetModifier(FIELD, 1, ProtocolModifier.Background(Paint.Literal(BLUE))),
        )
        val pixels = drawn.pixels
        assertEquals(BLUE, pixels[pixels.width / 2, pixels.height / 2].toArgb(), "the node's own fill is drawn")
        assertTrue(drawn.fieldHeight <= drawn.lineHeight + 0.5f, "a fill brings no minimum height with it")
    }

    /**
     * A bare field of several lines, 40dp tall and holding far more text than that, with
     * 60dp of red page above and below it. The caret is put at the end and a line is typed
     * there, so the text scrolls and its first lines lie above the field's top edge. Every
     * pixel of the page outside the field's own rectangle is still the page's colour.
     */
    @Test
    fun fr56_bare_multiline_field_clips_its_scrolled_text() = runComposeUiTest {
        val text = (1..12).joinToString("\n") { "MMMMMMMM $it" }
        val batch = buildList {
            add(Mutation.Create(PAGE, WidgetKind.Column))
            add(Mutation.SetModifier(PAGE, 0, ProtocolModifier.Background(Paint.Literal(RED))))
            add(Mutation.Create(ABOVE, WidgetKind.Spacer))
            add(Mutation.SetModifier(ABOVE, 0, ProtocolModifier.Size(160f, 60f)))
            add(Mutation.Create(FIELD, WidgetKind.TextField))
            add(Mutation.SetModifier(FIELD, 0, ProtocolModifier.Width(160f)))
            add(Mutation.SetModifier(FIELD, 1, ProtocolModifier.Height(40f)))
            add(Mutation.SetProp(FIELD, PropertyKind.Bare, PropertyValue.Bool(true)))
            add(Mutation.SetProp(FIELD, PropertyKind.Multiline, PropertyValue.Bool(true)))
            add(Mutation.SetProp(FIELD, PropertyKind.Text, PropertyValue.Text(text)))
            add(Mutation.Create(BELOW, WidgetKind.Spacer))
            add(Mutation.SetModifier(BELOW, 0, ProtocolModifier.Size(160f, 60f)))
            add(Mutation.Insert(PAGE, ABOVE, 0))
            add(Mutation.Insert(PAGE, FIELD, 1))
            add(Mutation.Insert(PAGE, BELOW, 2))
        }
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(FIELD)).requestFocus()
        waitForIdle()
        onNodeWithTag(nodeTestTag(FIELD)).performTextInputSelection(TextRange(text.length))
        waitForIdle()
        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("\nMMMMMMMM 13")
        waitForIdle()

        val scale = density.density
        val page = onNodeWithTag(nodeTestTag(PAGE)).getUnclippedBoundsInRoot()
        val field = onNodeWithTag(nodeTestTag(FIELD)).getUnclippedBoundsInRoot()
        assertEquals(40f, field.height.value, 0.5f, "the field keeps the height it was given")
        val pixels = onRoot().captureToImage().toPixelMap()
        // One pixel of slack round the field, for an edge that falls between two pixels.
        val fieldTop = (field.top.value * scale).toInt() - 1
        val fieldBottom = (field.bottom.value * scale).toInt() + 1
        val left = (page.left.value * scale).toInt()
        val right = minOf((page.right.value * scale).toInt(), pixels.width)
        val top = (page.top.value * scale).toInt()
        val bottom = minOf((page.bottom.value * scale).toInt(), pixels.height)
        assertTrue(fieldTop - top > 20 && bottom - fieldBottom > 20, "there is page above and below the field")
        for (y in top until bottom) {
            if (y in fieldTop..fieldBottom) continue
            for (x in left until right) {
                assertEquals(
                    RED,
                    pixels[x, y].toArgb(),
                    "pixel ($x, $y) lies outside the field (rows $fieldTop to $fieldBottom) and is the page's " +
                        "colour, not a line of text that scrolled out of the field",
                )
            }
        }
    }
}
