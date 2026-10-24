package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.RenderNodeObserver
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val ROOT = 1
private const val BOX = 2
private const val CLICK = 77L

/** A CSS `matrix(a, b, c, d, e, f)`. */
private data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float = 0f, val f: Float = 0f) {
    operator fun times(other: Affine) = Affine(
        a * other.a + c * other.b,
        b * other.a + d * other.b,
        a * other.c + c * other.d,
        b * other.c + d * other.d,
        a * other.e + c * other.f + e,
        b * other.e + d * other.f + f,
    )

    fun apply(x: Float, y: Float) = Offset(a * x + c * y + e, b * x + d * y + f)

    fun modifier(originX: Float, originY: Float) = ProtocolModifier.Transform(a, b, c, d, e, f, originX, originY)

    companion object {
        fun rotate(degrees: Float): Affine {
            val radians = degrees * PI.toFloat() / 180f
            return Affine(cos(radians), sin(radians), -sin(radians), cos(radians))
        }

        fun scale(x: Float, y: Float = x) = Affine(x, 0f, 0f, y)

        fun skewX(degrees: Float) = Affine(1f, 0f, tan(degrees * PI.toFloat() / 180f), 1f)
    }
}

/**
 * CSS `transform` on a node: drawn through two layers, with the layout untouched and
 * pointer input following the drawn shape.
 *
 * Every test runs at a density of one, so a dp is a pixel.
 */
@OptIn(ExperimentalTestApi::class)
class TransformTest {

    @AfterTest
    fun clearObservers() {
        RenderNodeObserver.onCompose = null
        RenderNodeObserver.onMeasure = null
    }

    private fun page(build: MutableList<Mutation>.() -> Unit): List<Mutation> = buildList {
        add(
            Mutation.SetTheme(
                Theme(
                    designSystem = DesignSystem.Material3,
                    fallback = DesignSystem.Material3,
                    colorScheme = ColorScheme.Light,
                    adaptive = false,
                ),
            ),
        )
        add(Mutation.Create(ROOT, WidgetKind.AbsoluteBox))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.RequiredSize(300f, 200f)))
        add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.Background(Paint.Literal(WHITE))))
        build()
    }

    private fun MutableList<Mutation>.node(
        id: Int,
        parent: Int,
        index: Int,
        vararg modifiers: ProtocolModifier,
        widget: WidgetKind = WidgetKind.Box,
    ) {
        add(Mutation.Create(id, widget))
        modifiers.forEachIndexed { position, modifier -> add(Mutation.SetModifier(id, position, modifier)) }
        add(Mutation.Insert(parent, id, index))
    }

    private fun ComposeUiTest.show(connection: FakeHostConnection): ComposeRustHost {
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        return host
    }

    private fun ComposeUiTest.picture(): PixelMap =
        onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()

    /** Where a point local to node [id] lands in the root box, through every layer above it. */
    private fun ComposeUiTest.toRootBox(id: Int, x: Float, y: Float): Offset {
        val root = onNodeWithTag(nodeTestTag(ROOT)).fetchSemanticsNode().layoutInfo.coordinates
        val node = onNodeWithTag(nodeTestTag(id)).fetchSemanticsNode().layoutInfo.coordinates
        return node.localToRoot(Offset(x, y)) - root.localToRoot(Offset.Zero)
    }

    private fun ComposeUiTest.clickAt(x: Float, y: Float) {
        onNodeWithTag(nodeTestTag(ROOT)).performMouseInput {
            moveTo(Offset(x, y))
            press()
            release()
        }
        waitForIdle()
    }

    private fun assertColor(expected: Int, actual: Color, what: String) {
        val wanted = Color(expected)
        val close = abs(wanted.red - actual.red) <= 2f / 255f &&
            abs(wanted.green - actual.green) <= 2f / 255f &&
            abs(wanted.blue - actual.blue) <= 2f / 255f
        assertTrue(close, "$what: expected $wanted, found $actual")
    }

    /**
     * The four corners of a 100 by 40 box land where the matrix puts them, within a
     * hundredth of a dp, for a rotation, a scale, a skew, a reflection and the three
     * multiplied together, about the corner and about the centre.
     */
    @Test
    fun fr41_corners_land_where_the_matrix_puts_them() {
        val product = Affine.rotate(15f) * Affine.scale(1.5f) * Affine.skewX(10f)
        val matrices = mapOf(
            "rotate(15deg)" to Affine.rotate(15f),
            "scale(1.5)" to Affine.scale(1.5f),
            "skewX(10deg)" to Affine.skewX(10f),
            "scaleX(-1)" to Affine.scale(-1f, 1f),
            "rotate scale skewX" to product,
            "the same, moved" to product.copy(e = 7f, f = -3f),
        )
        for ((name, matrix) in matrices) {
            for ((originX, originY) in listOf(0f to 0f, 0.5f to 0.5f)) {
                runComposeUiTest {
                    show(
                        FakeHostConnection(
                            page {
                                node(
                                    BOX, ROOT, 0,
                                    ProtocolModifier.Offset(50f, 50f),
                                    ProtocolModifier.RequiredSize(100f, 40f),
                                    matrix.modifier(originX, originY),
                                )
                            },
                        ),
                    )
                    val originInBox = Offset(originX * 100f, originY * 40f)
                    for ((x, y) in listOf(0f to 0f, 100f to 0f, 100f to 40f, 0f to 40f)) {
                        val moved = matrix.apply(x - originInBox.x, y - originInBox.y)
                        val expected = Offset(50f, 50f) + originInBox + moved
                        val actual = toRootBox(BOX, x, y)
                        assertTrue(
                            abs(expected.x - actual.x) < 0.01f && abs(expected.y - actual.y) < 0.01f,
                            "$name about ($originX, $originY): corner ($x, $y) should be at $expected, is at $actual",
                        )
                    }
                }
            }
        }
    }

    /**
     * The layout does not see the transform: the node measures as it would without one,
     * its sibling stays where it was, and the size it reports is its untransformed size.
     */
    @Test
    fun fr41_a_transform_leaves_the_layout_alone() {
        fun measure(transform: ProtocolModifier?): Triple<Pair<Int, Int>, Offset, HostEvent.WindowSizeChanged?> {
            var result: Triple<Pair<Int, Int>, Offset, HostEvent.WindowSizeChanged?>? = null
            runComposeUiTest {
                val connection = FakeHostConnection(
                    page {
                        val modifiers = listOfNotNull(
                            ProtocolModifier.Offset(50f, 50f),
                            ProtocolModifier.RequiredSize(100f, 40f),
                            transform,
                            ProtocolModifier.ObserveSize(5),
                        )
                        node(BOX, ROOT, 0, *modifiers.toTypedArray())
                        node(3, ROOT, 1, ProtocolModifier.Offset(200f, 50f), ProtocolModifier.RequiredSize(30f, 30f))
                    },
                )
                show(connection)
                val info = onNodeWithTag(nodeTestTag(BOX)).fetchSemanticsNode().layoutInfo
                result = Triple(
                    info.width to info.height,
                    toRootBox(3, 0f, 0f),
                    connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().lastOrNull { it.nodeId == BOX },
                )
            }
            return result!!
        }
        val plain = measure(null)
        val turned = measure(Affine.rotate(30f).copy(e = 15f).modifier(0.5f, 0.5f))
        assertEquals(100 to 40, plain.first)
        assertEquals(plain.first, turned.first, "the measured size")
        assertEquals(plain.second, turned.second, "the sibling's place")
        assertEquals(100f, turned.third?.widthDp, "the reported width")
        assertEquals(40f, turned.third?.heightDp, "the reported height")
    }

    /**
     * A pointer is tested against the shape that is drawn. A 100 by 40 box turned 45
     * degrees about its centre is not hit near its original corner, is hit at its centre,
     * and is hit outside its original rectangle where the turned shape now is.
     */
    @Test
    fun fr41_a_turned_node_is_hit_where_it_is_drawn() = runComposeUiTest {
        val connection = FakeHostConnection(
            page {
                node(
                    BOX, ROOT, 0,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(100f, 40f),
                    Affine.rotate(45f).modifier(0.5f, 0.5f),
                    ProtocolModifier.Clickable(CLICK),
                )
            },
        )
        show(connection)
        fun clicks() = connection.events.filterIsInstance<HostEvent.Clicked>().count { it.handlerId == CLICK }

        clickAt(50f + 3f, 50f + 3f)
        assertEquals(0, clicks(), "near the original corner, which the turned box has left")
        clickAt(100f, 70f)
        assertEquals(1, clicks(), "the centre")
        // Thirty four along both axes from the centre: outside the original rectangle,
        // whose half height is twenty, and inside the turned one.
        clickAt(100f + 34f, 70f + 34f)
        assertEquals(2, clicks(), "outside the original rectangle, inside the turned shape")
    }

    /** What is inside a turned node turns with it: a child box and a piece of text. */
    @Test
    fun fr41_content_turns_with_its_node() = runComposeUiTest {
        show(
            FakeHostConnection(
                page {
                    node(
                        BOX, ROOT, 0,
                        ProtocolModifier.Offset(50f, 80f),
                        ProtocolModifier.RequiredSize(100f, 40f),
                        Affine.rotate(90f).modifier(0.5f, 0.5f),
                        widget = WidgetKind.AbsoluteBox,
                    )
                    node(3, BOX, 0, ProtocolModifier.RequiredSize(20f, 20f), ProtocolModifier.Background(Paint.Literal(RED)))
                    add(Mutation.Create(4, WidgetKind.Text))
                    add(Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("turned")))
                    add(Mutation.SetModifier(4, 0, ProtocolModifier.Offset(40f, 10f)))
                    add(Mutation.Insert(BOX, 4, 1))
                },
            ),
        )
        // The centre is (100, 100). The child's middle, (60, 90), is (-40, -10) from it,
        // and a quarter turn clockwise takes that to (10, -40).
        val pixels = picture()
        assertColor(RED, pixels[110, 60], "the child, turned")
        assertColor(WHITE, pixels[60, 90], "where the child was before the turn")
        // The text's corner, (90, 90), is (-10, -10) from the centre and turns to (10, -10).
        val corner = toRootBox(4, 0f, 0f)
        assertTrue(
            abs(corner.x - 110f) < 0.01f && abs(corner.y - 90f) < 0.01f,
            "the text's corner is at $corner",
        )
    }

    /**
     * A clip inside a transform turns with it, and a transform and an opacity draw the same
     * picture in either order.
     */
    @Test
    fun fr41_clip_turns_with_the_node_and_alpha_commutes_with_it() = runComposeUiTest {
        val turn = Affine.rotate(30f).modifier(0.5f, 0.5f)
        val half = ProtocolModifier.Alpha(0.5f)
        show(
            FakeHostConnection(
                page {
                    // A 60 square turned 45 degrees, clipping a child much larger than itself.
                    node(
                        BOX, ROOT, 0,
                        ProtocolModifier.Offset(40f, 40f),
                        ProtocolModifier.RequiredSize(60f, 60f),
                        Affine.rotate(45f).modifier(0.5f, 0.5f),
                        ProtocolModifier.Clip(true),
                        widget = WidgetKind.AbsoluteBox,
                    )
                    node(
                        3, BOX, 0,
                        ProtocolModifier.Offset(-20f, -20f),
                        ProtocolModifier.RequiredSize(100f, 100f),
                        ProtocolModifier.Background(Paint.Literal(RED)),
                    )
                    // The same group twice, one with the transform first and one with the
                    // opacity first, 140 apart.
                    for ((id, offsetX, order) in listOf(Triple(10, 140f, listOf(turn, half)), Triple(20, 0f, listOf(half, turn)))) {
                        node(
                            id, ROOT, id / 10,
                            ProtocolModifier.Offset(offsetX + 20f, 140f),
                            ProtocolModifier.RequiredSize(60f, 40f),
                            *order.toTypedArray(),
                            widget = WidgetKind.AbsoluteBox,
                        )
                        node(id + 1, id, 0, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Background(Paint.Literal(RED)))
                        node(
                            id + 2, id, 1,
                            ProtocolModifier.Offset(20f, 0f),
                            ProtocolModifier.RequiredSize(40f, 40f),
                            ProtocolModifier.Background(Paint.Literal(0xFF0000FF.toInt())),
                        )
                    }
                },
            ),
        )
        val pixels = picture()
        // The turned square is a diamond about (70, 70) reaching 42.4 along each axis.
        assertColor(WHITE, pixels[42, 42], "the original corner, outside the turned clip")
        assertColor(RED, pixels[70, 30], "above the original square, inside the turned clip")

        // The second group sits at x 20 and the first at x 160, both at y 140, and turned
        // they reach from y 124 to 196. The clipped diamond above ends at y 113.
        for (y in 118 until 200) {
            for (x in 0 until 140) {
                assertEquals(
                    pixels[x, y],
                    pixels[x + 140, y],
                    "pixel ($x, $y) differs between [Alpha, Transform] and [Transform, Alpha]",
                )
            }
        }
    }

    /**
     * A matrix with no inverse, such as `scale(0)`, draws nothing and takes no input.
     */
    @Test
    fun fr41_a_singular_matrix_draws_nothing_and_takes_no_click() = runComposeUiTest {
        val connection = FakeHostConnection(
            page {
                node(
                    BOX, ROOT, 0,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(100f, 40f),
                    Affine.scale(0f).modifier(0.5f, 0.5f),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                    ProtocolModifier.Clickable(CLICK),
                )
            },
        )
        show(connection)
        val pixels = picture()
        assertColor(WHITE, pixels[100, 70], "the middle of a box scaled to nothing")
        clickAt(100f, 70f)
        assertEquals(0, connection.events.filterIsInstance<HostEvent.Clicked>().size)
    }

    /**
     * A change to the transform alone reaches only that node and measures nothing again:
     * not the node, and not what is inside it.
     */
    @Test
    fun fr41_changing_the_transform_measures_nothing_again() = runComposeUiTest {
        val compositions = mutableMapOf<Int, Int>()
        val measures = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        RenderNodeObserver.onMeasure = { id -> measures[id] = (measures[id] ?: 0) + 1 }
        val connection = FakeHostConnection(
            page {
                node(
                    BOX, ROOT, 0,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(100f, 40f),
                    Affine.rotate(10f).modifier(0.5f, 0.5f),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(3, BOX, 0, ProtocolModifier.RequiredSize(20f, 20f), widget = WidgetKind.AbsoluteBox)
            },
        )
        connection.respondWith {
            HostResponse(listOf(Mutation.SetModifier(BOX, 2, Affine.rotate(70f).modifier(0.5f, 0.5f))))
        }
        val host = show(connection)
        val measuredBefore = measures.toMap()
        val composedBefore = compositions.toMap()

        host.dispatch(HostEvent.Clicked(ROOT, 0))
        waitForIdle()

        val corner = toRootBox(BOX, 0f, 0f)
        val expected = Offset(100f, 70f) + Affine.rotate(70f).apply(-50f, -20f)
        assertTrue(abs(corner.x - expected.x) < 0.01f && abs(corner.y - expected.y) < 0.01f, "the new transform is drawn")
        assertEquals(measuredBefore[BOX], measures[BOX], "the transformed node is not measured again")
        assertEquals(measuredBefore[3], measures[3], "its child is not measured again")
        assertEquals(composedBefore[3], compositions[3], "its child is not recomposed")
        assertEquals(composedBefore[ROOT], compositions[ROOT], "its parent is not recomposed")
    }
}
