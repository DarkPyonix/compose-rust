package dev.darkpyonix.composerust.test

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
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
import dev.darkpyonix.composerust.ui.pressInEasing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val PAGE = 1
private const val NODE = 2
private const val HANDLER = 77L

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val BLUE = 0xFF0000FF.toInt()
private const val GREEN = 0xFF00FF00.toInt()

/**
 * The hover style, the focus ring and the press style, measured in pixels.
 *
 * Every tree is a white page with 20 dp of padding round one pressable node, at a density
 * of 1, so a dp is a pixel and the node's top left corner is at (20, 20) on the page. The
 * modifier indices are the slots the Host's writer gives each modifier.
 */
@OptIn(ExperimentalTestApi::class)
class HoverFocusPressStyleTest {
    private fun theme() =
        Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

    private fun page(
        widget: WidgetKind = WidgetKind.Box,
        width: Float = 80f,
        height: Float = 80f,
        fill: Paint? = Paint.Literal(WHITE),
        modifiers: List<Pair<Int, ProtocolModifier>> = emptyList(),
    ) = buildList {
        add(theme())
        add(Mutation.Create(PAGE, WidgetKind.Box))
        add(Mutation.SetModifier(PAGE, 14, ProtocolModifier.Background(Paint.Literal(WHITE))))
        add(Mutation.SetModifier(PAGE, 22, ProtocolModifier.Padding(20f)))
        add(Mutation.Create(NODE, widget))
        add(Mutation.SetProp(NODE, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)))
        add(Mutation.SetModifier(NODE, 3, ProtocolModifier.Size(width, height)))
        if (fill != null) add(Mutation.SetModifier(NODE, 14, ProtocolModifier.Background(fill)))
        modifiers.forEach { (slot, value) -> add(Mutation.SetModifier(NODE, slot, value)) }
        add(Mutation.Insert(PAGE, NODE, 0))
    }

    private fun ComposeUiTest.show(batch: List<Mutation>) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
        }
        waitForIdle()
    }

    /**
     * A pixel of the page, in page coordinates. The page fills the root from its top left
     * corner; the root is captured, because a capture of the page's own node leaves out its
     * padding, and with it the ring drawn outside the node and the node's press scale.
     */
    private fun ComposeUiTest.pagePixel(x: Int, y: Int): Int =
        onRoot().captureToImage().toPixelMap()[x, y].toArgb()

    private fun ComposeUiTest.hover() {
        onNodeWithTag(nodeTestTag(NODE)).performMouseInput { moveTo(center) }
        waitForIdle()
    }

    private fun ComposeUiTest.leave() {
        onNodeWithTag(nodeTestTag(NODE)).performMouseInput { moveTo(Offset(-15f, -15f)) }
        waitForIdle()
    }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun luma(argb: Int) = 0.299 * red(argb) + 0.587 * green(argb) + 0.114 * blue(argb)

    private fun assertNear(expected: Int, actual: Int, tolerance: Int, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected, was $actual")
    }

    @Test
    fun fr55_hover_style_fills_the_hovered_surface() {
        runComposeUiTest {
            show(page(modifiers = listOf(39 to ProtocolModifier.HoverStyle(Paint.Literal(RED), 0.5f, null, 1f))))
            assertEquals(WHITE, pagePixel(60, 60), "nothing is laid over a node the mouse is not over")
            hover()
            val hovered = pagePixel(60, 60)
            // Red at half its alpha over white, not the default dark 8% layer.
            assertNear(255, red(hovered), 2, "red")
            assertNear(128, green(hovered), 3, "green")
            assertNear(128, blue(hovered), 3, "blue")
            leave()
            assertEquals(WHITE, pagePixel(60, 60), "the layer goes when the mouse leaves")
        }
    }

    @Test
    fun fr55_hover_style_swaps_the_border_colour() {
        runComposeUiTest {
            show(
                page(
                    modifiers = listOf(
                        19 to ProtocolModifier.Border(4f, Paint.Literal(BLUE)),
                        39 to ProtocolModifier.HoverStyle(null, 0f, Paint.Literal(GREEN), 1f),
                    ),
                ),
            )
            // (21, 60) is inside the node's left border.
            val resting = pagePixel(21, 60)
            assertTrue(blue(resting) > 200 && green(resting) < 60, "the border is blue at rest: ${resting.toUInt().toString(16)}")
            hover()
            val hovered = pagePixel(21, 60)
            assertTrue(green(hovered) > 200 && blue(hovered) < 60, "the border is green while hovered: ${hovered.toUInt().toString(16)}")
            leave()
            assertEquals(resting, pagePixel(21, 60), "the border colour comes back when the mouse leaves")
        }
    }

    @Test
    fun fr55_hover_style_content_alpha() {
        runComposeUiTest {
            show(page(fill = Paint.Literal(BLACK), modifiers = listOf(39 to ProtocolModifier.HoverStyle(null, 0f, null, 0.88f))))
            assertEquals(BLACK, pagePixel(60, 60), "the node is opaque at rest")
            hover()
            // Black, under the default 8% layer, at 88% over the white page: 12% of 255
            // is 30.6, and the layer adds about 2.
            val hovered = pagePixel(60, 60)
            assertNear(32, red(hovered), 5, "the node fades to 0.88 while hovered")
            leave()
            assertEquals(BLACK, pagePixel(60, 60), "the node is opaque again once the mouse leaves")
        }
    }

    @Test
    fun fr55_button_has_the_default_hover() {
        fun hoveredPixel(widget: WidgetKind): Pair<Int, Int> {
            var result = 0 to 0
            runComposeUiTest {
                show(page(widget = widget))
                val resting = pagePixel(60, 60)
                hover()
                result = resting to pagePixel(60, 60)
            }
            return result
        }
        val (boxResting, boxHovered) = hoveredPixel(WidgetKind.Box)
        val (buttonResting, buttonHovered) = hoveredPixel(WidgetKind.Button)
        assertEquals(WHITE, boxResting)
        assertEquals(WHITE, buttonResting, "the button's own white background")
        assertNotEquals(boxResting, boxHovered, "a container without a hover style has the default layer")
        assertNotEquals(buttonResting, buttonHovered, "a button now has a hover")
        assertEquals(boxHovered, buttonHovered, "the button's hover is the same OnSurface 8% layer")
    }

    @Test
    fun fr55_button_draws_the_keyboard_focus_ring() {
        // (60, 21) is just inside the top edge of an 80 by 40 button.
        runComposeUiTest {
            show(page(widget = WidgetKind.Button, height = 40f))
            val resting = pagePixel(60, 21)
            onNodeWithTag(nodeTestTag(NODE)).requestFocus()
            waitForIdle()
            assertNotEquals(resting, pagePixel(60, 21), "keyboard focus draws the ring")
        }
        runComposeUiTest {
            show(page(widget = WidgetKind.Button, height = 40f))
            val resting = pagePixel(60, 21)
            onNodeWithTag(nodeTestTag(NODE)).performMouseInput {
                moveTo(center)
                press()
                release()
                moveTo(Offset(-15f, -15f))
            }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(resting, pagePixel(60, 21), "focus a pointer gave draws no ring")
        }
    }

    @Test
    fun fr55_focus_ring_offset_and_colour() {
        runComposeUiTest {
            show(
                page(
                    height = 40f,
                    modifiers = listOf(40 to ProtocolModifier.FocusRing(Paint.Literal(RED), 2f, 2f)),
                ),
            )
            // The node's left edge is at x = 20. A 2 dp ring 2 dp out covers x = 16 and 17.
            val row = 40
            assertEquals(WHITE, pagePixel(17, row), "no ring before focus")
            onNodeWithTag(nodeTestTag(NODE)).requestFocus()
            waitForIdle()
            assertEquals(RED, pagePixel(17, row), "the ring is the given colour")
            assertEquals(RED, pagePixel(16, row), "the ring is the given width")
            assertEquals(WHITE, pagePixel(15, row), "the ring ends 4 dp out")
            assertEquals(WHITE, pagePixel(19, row), "the ring starts 2 dp out")
            assertEquals(WHITE, pagePixel(21, row), "nothing is drawn inside the node")
            // The ring takes no room: the node is where it was.
            assertEquals(20f, onNodeWithTag(nodeTestTag(NODE)).fetchSemanticsNode().boundsInRoot.left)
        }
    }

    @Test
    fun fr51_press_style_tint() {
        fun pressed(style: ProtocolModifier.PressStyle?): Pair<Int, Int> {
            var result = 0 to 0
            runComposeUiTest {
                show(
                    page(
                        fill = Paint.Role(ColorRole.Primary),
                        modifiers = buildList {
                            add(28 to ProtocolModifier.PressFeedback(1f, 0.5f))
                            if (style != null) add(41 to style)
                        },
                    ),
                )
                hover()
                val hovered = pagePixel(60, 60)
                onNodeWithTag(nodeTestTag(NODE)).performMouseInput { press() }
                waitForIdle()
                result = hovered to pagePixel(60, 60)
            }
            return result
        }
        val nan = Float.NaN
        val (hovered, tinted) = pressed(ProtocolModifier.PressStyle(Paint.Role(ColorRole.OnPrimary), nan, 0f, 0f, 0f))
        assertTrue(luma(tinted) > luma(hovered) + 40, "an OnPrimary tint lightens the pressed Primary surface")
        val (plainHovered, plain) = pressed(null)
        assertTrue(luma(plain) < luma(plainHovered), "without a press style the tint is the dark content colour")
    }

    @Test
    fun fr51_press_style_easing() {
        // The default curve, and the given one in its place.
        assertEquals(CubicBezierEasing(0f, 0f, 0.58f, 1f), pressInEasing(null))
        val nan = Float.NaN
        assertEquals(CubicBezierEasing(0f, 0f, 0.58f, 1f), pressInEasing(ProtocolModifier.PressStyle(null, nan, 0f, 0f, 0f)))
        val given = pressInEasing(ProtocolModifier.PressStyle(null, 0.2f, 0f, 0f, 1f))
        assertEquals(CubicBezierEasing(0.2f, 0f, 0f, 1f), given)

        // The node's width on every frame of a press, from the press to 400 ms later. A
        // scale of 0.5 makes the curves several pixels apart.
        fun widths(style: ProtocolModifier.PressStyle?): List<Int> {
            val seen = mutableListOf<Int>()
            runComposeUiTest {
                mainClock.autoAdvance = false
                show(
                    page(
                        fill = Paint.Literal(BLACK),
                        modifiers = buildList {
                            add(28 to ProtocolModifier.PressFeedback(0.5f, 0f))
                            if (style != null) add(41 to style)
                        },
                    ),
                )
                mainClock.advanceTimeBy(100)
                onNodeWithTag(nodeTestTag(NODE)).performMouseInput { moveTo(center); press() }
                repeat(25) {
                    mainClock.advanceTimeByFrame()
                    val pixels = onRoot().captureToImage().toPixelMap()
                    seen += (0 until pixels.width).count { x -> luma(pixels[x, 60].toArgb()) < 128 }
                }
            }
            return seen
        }
        val defaultWidths = widths(null)
        val givenWidths = widths(ProtocolModifier.PressStyle(null, 0.2f, 0f, 0f, 1f))
        assertTrue(defaultWidths.last() in 36..44, "the default press ends at half the width: $defaultWidths")
        assertTrue(givenWidths.last() in 36..44, "the given press ends at half the width: $givenWidths")
        assertTrue(
            givenWidths.sum() < defaultWidths.sum() - 20,
            "the given curve is further along through the press: $givenWidths against $defaultWidths",
        )
    }
}
