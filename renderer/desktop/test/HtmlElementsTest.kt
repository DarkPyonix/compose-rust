package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.RenderNodeObserver
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val GREEN = 0xFF00FF00.toInt()
private const val BLUE = 0xFF0000FF.toInt()
private const val BLACK = 0xFF000000.toInt()

private const val ROOT = 1

/**
 * The elements an HTML and CSS screen is drawn with, drawn.
 *
 * Every test runs at a density of one, so a dp is a pixel and a position the Host sent can
 * be read back from the picture without rounding. Each picture is of the root box, which
 * is white, so whatever a modifier draws outside its own node is in the picture too.
 */
@OptIn(ExperimentalTestApi::class)
class HtmlElementsTest {

    @AfterTest
    fun clearObserver() {
        RenderNodeObserver.onCompose = null
    }

    /** A page: a white root box of 300 by 200 and whatever is put in it. */
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

    /** One node of [widget], with [modifiers] in order, as child [index] of [parent]. */
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

    private fun ComposeUiTest.show(batch: List<Mutation>): ComposeRustHost = show(FakeHostConnection(batch))

    private fun ComposeUiTest.picture(): PixelMap =
        onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()

    private fun ComposeUiTest.bounds(id: Int): DpRect =
        onNodeWithTag(nodeTestTag(id)).getUnclippedBoundsInRoot().let {
            DpRect(it.left, it.top, it.right, it.bottom)
        }

    /** Within two steps of 255 on every channel, which is what antialiasing can move. */
    private fun assertColor(expected: Int, actual: Color, what: String) {
        val wanted = Color(expected)
        val close = abs(wanted.red - actual.red) <= 2f / 255f &&
            abs(wanted.green - actual.green) <= 2f / 255f &&
            abs(wanted.blue - actual.blue) <= 2f / 255f &&
            abs(wanted.alpha - actual.alpha) <= 2f / 255f
        assertTrue(close, "$what: expected $wanted, found $actual")
    }

    /**
     * Each child is exactly where its `Offset` puts it, relative to the box it is in, and
     * exactly the size its `RequiredSize` says, whatever its siblings are. A child larger
     * than the box is neither squeezed nor moved.
     */
    @Test
    fun fr42_children_sit_exactly_at_their_offsets() = runComposeUiTest {
        show(
            page {
                node(2, ROOT, 0, ProtocolModifier.Offset(10f, 20f), ProtocolModifier.RequiredSize(50f, 30f))
                node(
                    3, ROOT, 1,
                    ProtocolModifier.Offset(100f, 40f),
                    ProtocolModifier.RequiredSize(120f, 100f),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(4, 3, 0, ProtocolModifier.Offset(5f, 7f), ProtocolModifier.RequiredSize(20f, 10f))
                // Wider than the root and partly left of it, as an overflowing CSS box is.
                node(5, ROOT, 2, ProtocolModifier.Offset(-30f, 150f), ProtocolModifier.RequiredSize(400f, 80f))
            },
        )

        val root = bounds(ROOT)
        fun assertAt(id: Int, x: Float, y: Float, width: Float, height: Float) {
            val box = bounds(id)
            assertEquals(x, (box.left - root.left).value, "node $id left")
            assertEquals(y, (box.top - root.top).value, "node $id top")
            assertEquals(width, (box.right - box.left).value, "node $id width")
            assertEquals(height, (box.bottom - box.top).value, "node $id height")
        }
        assertAt(ROOT, 0f, 0f, 300f, 200f)
        assertAt(2, 10f, 20f, 50f, 30f)
        assertAt(3, 100f, 40f, 120f, 100f)
        // Relative to its own box, not to the root.
        assertAt(4, 105f, 47f, 20f, 10f)
        assertAt(5, -30f, 150f, 400f, 80f)
    }

    /** Later children draw over earlier ones, which is how the Host says what is on top. */
    @Test
    fun fr42_later_children_draw_over_earlier_ones() = runComposeUiTest {
        show(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(10f, 10f),
                    ProtocolModifier.RequiredSize(50f, 50f),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                )
                node(
                    3, ROOT, 1,
                    ProtocolModifier.Offset(35f, 35f),
                    ProtocolModifier.RequiredSize(50f, 50f),
                    ProtocolModifier.Background(Paint.Literal(BLUE)),
                )
            },
        )
        val pixels = picture()
        assertColor(RED, pixels[20, 20], "where only the first child is")
        assertColor(BLUE, pixels[45, 45], "where the two overlap")
    }

    /**
     * Each side in its own width and paint, over the background, with the joins running
     * from the outer corners towards the inner ones as a browser draws them.
     */
    @Test
    fun fr42_border_each_draws_four_sides() = runComposeUiTest {
        show(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(10f, 10f),
                    ProtocolModifier.RequiredSize(100f, 60f),
                    ProtocolModifier.Background(Paint.Literal(WHITE)),
                    ProtocolModifier.BorderEach(
                        4f, 8f, 6f, 10f,
                        Paint.Literal(RED),
                        Paint.Literal(GREEN),
                        Paint.Literal(BLUE),
                        Paint.Literal(BLACK),
                    ),
                )
            },
        )
        val pixels = picture()
        // Positions are the root's, so the node's own (x, y) is (10 + x, 10 + y).
        assertColor(RED, pixels[60, 11], "the top side")
        assertColor(GREEN, pixels[10 + 96, 40], "the right side")
        assertColor(BLUE, pixels[60, 10 + 57], "the bottom side")
        assertColor(BLACK, pixels[14, 40], "the left side")
        assertColor(WHITE, pixels[60, 40], "the background inside the border")
        assertColor(WHITE, pixels[60, 10 + 5], "just inside the four pixel top side")
        // The top left join runs from (0, 0) to (10, 4): above it is the top side, below it
        // the left side.
        assertColor(RED, pixels[10 + 8, 10 + 1], "above the top left join")
        assertColor(BLACK, pixels[10 + 1, 10 + 3], "below the top left join")
        assertColor(WHITE, pixels[5, 5], "outside the node")
    }

    /**
     * Each corner is cut to its own radius, and the background follows it. A corner with no
     * radius stays square.
     */
    @Test
    fun fr42_corner_each_rounds_each_corner_on_its_own() = runComposeUiTest {
        show(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(10f, 10f),
                    ProtocolModifier.RequiredSize(100f, 60f),
                    ProtocolModifier.CornerEach(30f, 0f, 10f, 0f),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                )
            },
        )
        val pixels = picture()
        assertColor(WHITE, pixels[10 + 2, 10 + 2], "inside the top left radius")
        assertColor(RED, pixels[10 + 97, 10 + 2], "the square top right corner")
        assertColor(WHITE, pixels[10 + 99, 10 + 59], "inside the bottom right radius")
        assertColor(RED, pixels[10 + 1, 10 + 58], "the square bottom left corner")
        assertColor(RED, pixels[60, 40], "the middle")
    }

    /**
     * A CSS `box-shadow` with no blur: the node's outline moved by the offset, drawn only
     * outside the node. A node with no background does not show its own shadow through it.
     */
    @Test
    fun fr42_shadow_is_drawn_outside_the_node_only() = runComposeUiTest {
        show(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(40f, 40f),
                    ProtocolModifier.Shadow(10f, 10f, 0f, 0f, Paint.Literal(BLACK)),
                )
                node(
                    3, ROOT, 1,
                    ProtocolModifier.Offset(150f, 50f),
                    ProtocolModifier.RequiredSize(40f, 40f),
                    ProtocolModifier.Shadow(0f, 0f, 0f, 5f, Paint.Literal(BLACK)),
                )
            },
        )
        val pixels = picture()
        assertColor(BLACK, pixels[95, 95], "the shadow beyond the node's corner")
        assertColor(WHITE, pixels[70, 70], "under the node, where CSS cuts the shadow away")
        assertColor(WHITE, pixels[95, 55], "above where the moved shadow starts")
        // The spread grows the outline by five on every side.
        assertColor(BLACK, pixels[150 - 3, 70], "inside the spread, left of the node")
        assertColor(WHITE, pixels[150 - 7, 70], "outside the spread")
    }

    /** A blurred shadow fades across its edge, half dark where the sharp edge would be. */
    @Test
    fun fr42_shadow_blur_fades_across_the_edge() = runComposeUiTest {
        show(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(40f, 60f),
                    ProtocolModifier.Shadow(40f, 0f, 20f, 0f, Paint.Literal(BLACK)),
                )
            },
        )
        val pixels = picture()
        // The sharp shadow would end at x = 50 + 40 + 40 = 130.
        val atEdge = pixels[130, 80].red
        val inside = pixels[115, 80].red
        val outside = pixels[145, 80].red
        assertTrue(atEdge in 0.3f..0.7f, "the edge of a blurred shadow is half dark, found $atEdge")
        assertTrue(inside < atEdge && atEdge < outside, "the shadow fades outwards: $inside, $atEdge, $outside")
        assertTrue(outside > 0.85f, "well past the edge is nearly the page, found $outside")
    }

    /**
     * `overflow: hidden`: a rectangle where the node has no corners, its rounded shape
     * where it has them, and nothing cut at all when the clip is off.
     */
    @Test
    fun fr42_clip_follows_corner_each_or_the_rectangle() = runComposeUiTest {
        show(
            page {
                // Square clip.
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(20f, 20f),
                    ProtocolModifier.RequiredSize(60f, 60f),
                    ProtocolModifier.Clip(true),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(3, 2, 0, ProtocolModifier.RequiredSize(100f, 100f), ProtocolModifier.Background(Paint.Literal(RED)))
                // Rounded clip.
                node(
                    4, ROOT, 1,
                    ProtocolModifier.Offset(120f, 20f),
                    ProtocolModifier.RequiredSize(60f, 60f),
                    ProtocolModifier.CornerEach(30f, 30f, 30f, 30f),
                    ProtocolModifier.Clip(true),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(5, 4, 0, ProtocolModifier.RequiredSize(100f, 100f), ProtocolModifier.Background(Paint.Literal(RED)))
                // No clip.
                node(
                    6, ROOT, 2,
                    ProtocolModifier.Offset(20f, 100f),
                    ProtocolModifier.RequiredSize(60f, 60f),
                    ProtocolModifier.Clip(false),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(7, 6, 0, ProtocolModifier.RequiredSize(100f, 100f), ProtocolModifier.Background(Paint.Literal(RED)))
            },
        )
        val pixels = picture()
        assertColor(RED, pixels[20 + 2, 20 + 2], "the square clip keeps its corner")
        assertColor(WHITE, pixels[20 + 70, 20 + 30], "the square clip cuts what overflows")
        assertColor(WHITE, pixels[120 + 2, 20 + 2], "the rounded clip cuts its corner")
        assertColor(RED, pixels[120 + 30, 20 + 30], "the rounded clip keeps its middle")
        assertColor(WHITE, pixels[120 + 70, 20 + 30], "the rounded clip cuts what overflows")
        assertColor(RED, pixels[20 + 70, 100 + 30], "without a clip the overflow shows")
    }

    /**
     * Opacity applies to the group once. Two opaque red children overlapping inside a box
     * at half opacity are one even pink; faded one by one, the overlap would be darker.
     */
    @Test
    fun fr42_alpha_composites_the_subtree_once() = runComposeUiTest {
        show(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(10f, 10f),
                    ProtocolModifier.RequiredSize(100f, 50f),
                    ProtocolModifier.Alpha(0.5f),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(3, 2, 0, ProtocolModifier.RequiredSize(60f, 50f), ProtocolModifier.Background(Paint.Literal(RED)))
                node(
                    4, 2, 1,
                    ProtocolModifier.Offset(40f, 0f),
                    ProtocolModifier.RequiredSize(60f, 50f),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                )
            },
        )
        val pixels = picture()
        val single = pixels[10 + 20, 35]
        val overlap = pixels[10 + 50, 35]
        assertColor(single.toArgb(), overlap, "the overlap is the same colour as one child alone")
        assertTrue(abs(single.green - 0.5f) < 0.03f, "half opaque red over white is pink, found $single")
        assertTrue(single.red > 0.98f, "half opaque red over white keeps all its red, found $single")
    }

    /**
     * One modifier changed on one node redraws that node and nothing else: not its
     * siblings, and not the box they are in.
     */
    @Test
    fun fr42_changing_one_modifier_recomposes_only_that_node() = runComposeUiTest {
        val compositions = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        val connection = FakeHostConnection(
            page {
                node(2, ROOT, 0, ProtocolModifier.Offset(10f, 10f), ProtocolModifier.RequiredSize(20f, 20f))
                node(3, ROOT, 1, ProtocolModifier.Offset(50f, 10f), ProtocolModifier.RequiredSize(20f, 20f))
            },
        )
        connection.respondWith { HostResponse(listOf(Mutation.SetModifier(2, 0, ProtocolModifier.Offset(40f, 60f)))) }
        val host = show(connection)
        val before = compositions.toMap()

        host.dispatch(HostEvent.Clicked(ROOT, 0))
        waitForIdle()

        val root = bounds(ROOT)
        assertEquals(40f, (bounds(2).left - root.left).value, "the moved node is where it was sent")
        assertEquals(60f, (bounds(2).top - root.top).value, "the moved node is where it was sent")
        assertEquals((before[2] ?: 0) + 1, compositions[2], "the changed node recomposes once")
        assertEquals(before[3], compositions[3], "its sibling does not recompose")
        assertEquals(before[ROOT], compositions[ROOT], "the box they are in does not recompose")
    }
}
