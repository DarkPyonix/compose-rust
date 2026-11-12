package dev.darkpyonix.composerust.test

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.ContinuousCornerShape
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.CornerCurveShape
import dev.darkpyonix.composerust.ui.CornerEachShape
import dev.darkpyonix.composerust.ui.CurvedCorner
import dev.darkpyonix.composerust.ui.cornerCurve
import dev.darkpyonix.composerust.ui.cornerCurveCorners
import dev.darkpyonix.composerust.ui.cornerSmoothing
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.ui.withCornerCurve
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val GREEN = 0xFF00FF00.toInt()
private const val BLUE = 0xFF0000FF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val ROOT = 1

/**
 * Continuous-curvature corners: the geometry of one corner against Figma's corner
 * smoothing, how the curve sits on the radii the node already has, and that every part of
 * a node that draws its outline draws the same one.
 */
@OptIn(ExperimentalTestApi::class)
class CornerCurveTest {

    private val density = Density(1f)

    private fun square(side: Float, radius: Float) =
        RoundRect(0f, 0f, side, side, CornerRadius(radius))

    private fun assertNear(expected: Offset, actual: Offset, what: String, tolerance: Float = 0.01f) {
        assertTrue(
            abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance,
            "$what: expected $expected, found $actual",
        )
    }

    private fun CurvedCorner.points(): List<Offset> = listOf(start) + cubics

    @Test
    fun fr50_corner_curve_clamps_out_of_range() {
        assertEquals(0f, cornerSmoothing(-0.5f))
        assertEquals(0f, cornerSmoothing(Float.NEGATIVE_INFINITY))
        assertEquals(1f, cornerSmoothing(1.5f))
        assertEquals(1f, cornerSmoothing(Float.POSITIVE_INFINITY))
        assertEquals(0f, cornerSmoothing(Float.NaN))
        assertEquals(0.6f, cornerSmoothing(0.6f))
        // The value the node carries is read clamped, and the last one stands.
        assertEquals(1f, listOf(ProtocolModifier.CornerCurve(0.2f), ProtocolModifier.CornerCurve(7f)).cornerCurve())
        assertEquals(0f, listOf(ProtocolModifier.CornerCurve(Float.NaN)).cornerCurve())
        assertEquals(null, listOf<ProtocolModifier>(ProtocolModifier.Shape(4f, 4f, 4f, 4f)).cornerCurve())
        // An out-of-range value draws as the nearer end, not as nothing.
        val drawn = RoundedCornerShape(16.dp).withCornerCurve(listOf(ProtocolModifier.CornerCurve(9f)).cornerCurve())
        assertEquals(CornerCurveShape(RoundedCornerShape(16.dp), 1f), drawn)
    }

    /**
     * Radius 16, smoothing 0.6, a 100 by 100 box. The reference points are Figma's corner
     * smoothing as figma-squircle computes it: `p = 25.6`, `a = 8.9608`, `b = 4.4804`,
     * `c = 3.4226`, `d = 1.7439`, an arc of 36 degrees whose chord is 6.9923 on each axis.
     */
    @Test
    fun fr50_corner_curve_path_matches_reference_points() {
        val topRight = cornerCurveCorners(square(100f, 16f), 0.6f)[0]
        val expected = listOf(
            Offset(74.4f, 0f),
            Offset(83.3608f, 0f),
            Offset(87.8413f, 0f),
            Offset(91.2638f, 1.7439f),
            Offset(94.2744f, 3.2779f),
            Offset(96.7221f, 5.7256f),
            Offset(98.2561f, 8.7362f),
            Offset(100f, 12.1587f),
            Offset(100f, 16.6392f),
            Offset(100f, 25.6f),
        )
        val actual = topRight.points()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (want, got) -> assertNear(want, got, "point $index") }
        // The arc's ends lie on the circle of radius 16 about (84, 16).
        for (point in listOf(actual[3], actual[6])) {
            assertTrue(abs(hypot(point.x - 84f, point.y - 16f) - 16f) < 0.01f, "$point is on the arc")
        }
    }

    @Test
    fun fr50_corner_curve_zero_equals_rounded_corner_shape() {
        val size = Size(100f, 60f)
        val rounded = RoundedCornerShape(16.dp)
        val plain = rounded.createOutline(size, LayoutDirection.Ltr, density)
        // Zero is the rounded shape itself, so it draws the same pixels.
        assertSame(rounded, rounded.withCornerCurve(0f))
        assertEquals(plain, rounded.withCornerCurve(0f).createOutline(size, LayoutDirection.Ltr, density))
        // Through the node's modifiers as well.
        val nodeShape = listOf(ProtocolModifier.Shape(16f, 16f, 16f, 16f), ProtocolModifier.CornerCurve(0f))
            .resolvedShape(theme(DesignSystem.Material3))
        assertEquals(plain, nodeShape.createOutline(size, LayoutDirection.Ltr, density))
        // A curve of zero replaces a design system's continuous corner with a plain arc.
        assertEquals(rounded, ContinuousCornerShape(16.dp).withCornerCurve(0f))
        // And the path itself, at zero, is a quarter circle: every point at distance r from
        // the corner's centre, the straight runs empty.
        val corner = cornerCurveCorners(square(100f, 16f), 0f)[0]
        assertNear(Offset(84f, 0f), corner.start, "the arc starts where a rounded corner does")
        assertNear(Offset(100f, 16f), corner.end, "and ends there")
        val (p0, p1, p2, p3) = corner.cubics.subList(2, 6)
        for (step in 0..10) {
            val t = step / 10f
            val u = 1f - t
            val x = u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x
            val y = u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y
            assertTrue(abs(hypot(x - 84f, y - 16f) - 16f) < 0.01f, "($x, $y) is on the circle")
        }
    }

    @Test
    fun fr50_corner_curve_limits_radius_to_half_the_shorter_side() {
        for (smoothing in listOf(0f, 0.6f, 1f)) {
            val rect = RoundRect(0f, 0f, 120f, 40f, CornerRadius(500f))
            val corners = cornerCurveCorners(rect, smoothing)
            val (topRight, bottomRight, bottomLeft, topLeft) = corners
            // No curve starts further than half the shorter side from its corner.
            assertTrue(120f - topRight.start.x <= 20.001f, "$smoothing: ${topRight.start}")
            assertTrue(topRight.end.y <= 20.001f, "$smoothing: ${topRight.end}")
            // So two corners never overlap on a side: each starts after the last one ended.
            assertTrue(topLeft.end.x <= topRight.start.x, "top side at $smoothing")
            assertTrue(topRight.end.y <= bottomRight.start.y, "right side at $smoothing")
            assertTrue(bottomRight.end.x >= bottomLeft.start.x, "bottom side at $smoothing")
            assertTrue(bottomLeft.end.y >= topLeft.start.y, "left side at $smoothing")
            // Every point of the outline is inside the box, which holds the curves too, since
            // a cubic lies inside the hull of its points.
            for (point in corners.flatMap { it.points() }) {
                assertTrue(point.x in -0.001f..120.001f && point.y in -0.001f..40.001f, "$point at $smoothing")
            }
            // The shrunk corner is the same curve at a smaller size, not a different one:
            // its start is still (1 + s) r from the corner with r = 20 / (1 + s).
            assertTrue(abs((120f - topRight.start.x) - 20f) < 0.001f, "the curve uses the whole budget")
        }
    }

    @Test
    fun fr50_corner_curve_is_symmetric_and_inside_its_bounds() {
        val corners = cornerCurveCorners(square(100f, 16f), 0.6f)
        val (topRight, bottomRight, bottomLeft, topLeft) = corners.map { it.points() }
        topRight.indices.forEach { index ->
            val point = topRight[index]
            // A quarter turn about the centre carries each corner onto the next one.
            assertNear(Offset(100f - point.y, point.x), bottomRight[index], "bottom right $index")
            assertNear(Offset(100f - point.x, 100f - point.y), bottomLeft[index], "bottom left $index")
            assertNear(Offset(point.y, 100f - point.x), topLeft[index], "top left $index")
            // And the corner is its own mirror across the diagonal.
            val mirrored = topRight[topRight.size - 1 - index]
            assertNear(Offset(100f - mirrored.y, 100f - mirrored.x), point, "diagonal mirror $index")
        }
        for (point in corners.flatMap { it.points() }) {
            assertTrue(point.x in 0f..100f && point.y in 0f..100f, "$point is inside the box")
        }
        // A corner without a radius stays square: its one point is the corner.
        val square = cornerCurveCorners(square(100f, 0f), 0.6f)
        assertEquals(listOf(Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f), Offset(0f, 0f)), square.map { it.start })
        assertTrue(square.all { it.cubics.isEmpty() })
        // A smooth corner is not the rounded one: it leaves the side earlier.
        assertTrue(topRight[0].x < 84f)
    }

    @Test
    fun fr50_corner_curve_combines_with_corner_each() {
        val size = Size(100f, 100f)
        val each = CornerEachShape(4f, 8f, 12f, 16f)
        val curved = listOf(ProtocolModifier.CornerEach(4f, 8f, 12f, 16f), ProtocolModifier.CornerCurve(0.5f))
            .resolvedShape(theme(DesignSystem.Material3))
        assertEquals(CornerCurveShape(each, 0.5f), curved)
        val outline = curved.createOutline(size, LayoutDirection.Ltr, density)
        assertIs<Outline.Generic>(outline)
        // Each corner keeps its own radius: its curve starts 1.5 r from that corner.
        val corners = cornerCurveCorners(each.roundRect(size, density), 0.5f)
        assertNear(Offset(100f - 1.5f * 8f, 0f), corners[0].start, "top right, radius 8")
        assertNear(Offset(100f, 100f - 1.5f * 12f), corners[1].start, "bottom right, radius 12")
        assertNear(Offset(1.5f * 16f, 100f), corners[2].start, "bottom left, radius 16")
        assertNear(Offset(0f, 1.5f * 4f), corners[3].start, "top left, radius 4")
        // The radius a `Shape` gave stays the same too, and only the curve changes.
        val shaped = listOf(ProtocolModifier.Shape(10f, 10f, 10f, 10f), ProtocolModifier.CornerCurve(0.6f))
            .resolvedShape(theme(DesignSystem.Material3))
        assertEquals(CornerCurveShape(RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp), 0.6f), shaped)
        // With no radius there is nothing to curve: a rectangle stays a rectangle.
        val rectangle = listOf<ProtocolModifier>(ProtocolModifier.CornerCurve(0.6f))
            .resolvedShape(theme(DesignSystem.Material3))
        assertIs<Outline.Rectangle>(rectangle.createOutline(size, LayoutDirection.Ltr, density))
    }

    @Test
    fun fr50_corner_curve_applies_in_every_design_system() {
        val size = Size(100f, 100f)
        for (system in DesignSystem.values()) {
            val resolved = theme(system)
            for (modifiers in listOf(
                listOf(ProtocolModifier.ShapeRole(ShapeRole.Medium), ProtocolModifier.CornerCurve(0.6f)),
                listOf(ProtocolModifier.Shape(16f, 16f, 16f, 16f), ProtocolModifier.CornerCurve(0.6f)),
            )) {
                val shape = modifiers.resolvedShape(resolved)
                assertIs<CornerCurveShape>(shape, "$system $modifiers")
                assertEquals(0.6f, shape.smoothing)
                // A role whose radius is zero in this system has no corner to curve.
                if (resolved.radius(ShapeRole.Medium).value > 0f || modifiers[0] is ProtocolModifier.Shape) {
                    assertIs<Outline.Generic>(shape.createOutline(size, LayoutDirection.Ltr, density), "$system")
                }
            }
            // Without a curve, a number is a plain arc in every system.
            val plain = listOf(ProtocolModifier.Shape(16f, 16f, 16f, 16f)).resolvedShape(resolved)
            assertIs<Outline.Rounded>(plain.createOutline(size, LayoutDirection.Ltr, density), "$system")
        }
    }

    private fun theme(system: DesignSystem) = resolveTheme(
        Theme(system, system, ColorScheme.Light, adaptive = false),
        HostPlatform.Unknown,
        systemDark = false,
        highContrast = { false },
    )

    // The picture. Every test runs at a density of one, so a dp is a pixel.

    private fun page(build: MutableList<Mutation>.() -> Unit): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
        add(Mutation.Create(ROOT, WidgetKind.AbsoluteBox))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.RequiredSize(400f, 220f)))
        add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.Background(Paint.Literal(WHITE))))
        build()
    }

    private fun MutableList<Mutation>.node(id: Int, parent: Int, index: Int, vararg modifiers: ProtocolModifier) {
        add(Mutation.Create(id, WidgetKind.Box))
        modifiers.forEachIndexed { position, modifier -> add(Mutation.SetModifier(id, position, modifier)) }
        add(Mutation.Insert(parent, id, index))
    }

    private fun ComposeUiTest.picture(batch: List<Mutation>): PixelMap {
        setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
        }
        waitForIdle()
        return onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()
    }

    private fun isColor(expected: Int, actual: Color): Boolean {
        val wanted = Color(expected)
        return abs(wanted.red - actual.red) <= 2f / 255f &&
            abs(wanted.green - actual.green) <= 2f / 255f &&
            abs(wanted.blue - actual.blue) <= 2f / 255f
    }

    /** The outline as a closed polygon, each cubic cut into 32 straight pieces. */
    private fun polygon(rect: RoundRect, smoothing: Float): List<Offset> = buildList {
        for (corner in cornerCurveCorners(rect, smoothing)) {
            add(corner.start)
            var from = corner.start
            for (index in corner.cubics.indices step 3) {
                val (one, two, end) = corner.cubics.subList(index, index + 3)
                for (step in 1..32) {
                    val t = step / 32f
                    val u = 1f - t
                    add(
                        Offset(
                            u * u * u * from.x + 3 * u * u * t * one.x + 3 * u * t * t * two.x + t * t * t * end.x,
                            u * u * u * from.y + 3 * u * u * t * one.y + 3 * u * t * t * two.y + t * t * t * end.y,
                        ),
                    )
                }
                from = end
            }
        }
    }

    /** How far [point] is inside [outline]: positive inside, negative outside. */
    private fun depth(outline: List<Offset>, point: Offset): Float {
        var inside = false
        var nearest = Float.MAX_VALUE
        for (index in outline.indices) {
            val a = outline[index]
            val b = outline[(index + 1) % outline.size]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0f, 1f)
            nearest = min(nearest, sqrt((point.x - a.x - t * dx).let { it * it } + (point.y - a.y - t * dy).let { it * it }))
        }
        return if (inside) nearest else -nearest
    }

    /**
     * Two nodes cut to radius 40 at smoothing 1. The first has a red background, a black
     * border four wide and a clip, and a green child larger than it; the second a red
     * background and a blue shadow moved by (12, 12).
     *
     * Every pixel of the first node's corner is checked against the curved outline: outside
     * it is the page, just inside it is the border, deeper in is the child. No red shows
     * anywhere, so the background, border and clip are the same outline. The shadow is
     * blue exactly where the moved outline is and the node's own outline is not.
     */
    @Test
    fun fr50_corner_curve_shape_is_shared_by_background_border_shadow_clip() = runComposeUiTest {
        val pixels = picture(
            page {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(20f, 20f),
                    ProtocolModifier.RequiredSize(160f, 160f),
                    ProtocolModifier.Shape(40f, 40f, 40f, 40f),
                    ProtocolModifier.CornerCurve(1f),
                    ProtocolModifier.Clip(true),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                    ProtocolModifier.Border(4f, Paint.Literal(BLACK)),
                )
                node(3, 2, 0, ProtocolModifier.RequiredSize(220f, 220f), ProtocolModifier.Background(Paint.Literal(GREEN)))
                node(
                    4, ROOT, 1,
                    ProtocolModifier.Offset(210f, 20f),
                    ProtocolModifier.RequiredSize(160f, 160f),
                    // Ahead of the shape: a `Shape` clips what follows it in the list to
                    // its outline, and a shadow is drawn outside that outline. The shadow
                    // still reads the node's shape and curve from wherever they are.
                    ProtocolModifier.Shadow(12f, 12f, 0f, 0f, Paint.Literal(BLUE)),
                    ProtocolModifier.Shape(40f, 40f, 40f, 40f),
                    ProtocolModifier.CornerCurve(1f),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                )
            },
        )
        val first = polygon(RoundRect(20f, 20f, 180f, 180f, CornerRadius(40f)), 1f)
        var checked = 0
        for (y in 20 until 100) for (x in 20 until 100) {
            val color = pixels[x, y]
            val depth = depth(first, Offset(x + 0.5f, y + 0.5f))
            assertTrue(!isColor(RED, color), "background shows at ($x, $y), $depth inside the outline")
            when {
                depth < -1f -> assertTrue(isColor(WHITE, color), "($x, $y) is outside the outline: $color")
                depth > 1f && depth < 3f -> assertTrue(isColor(BLACK, color), "($x, $y) is on the border: $color")
                depth > 5f -> assertTrue(isColor(GREEN, color), "($x, $y) is the clipped child: $color")
                else -> checked--
            }
            checked++
        }
        assertTrue(checked > 4000, "most of the corner was checked, $checked pixels")

        val own = polygon(RoundRect(210f, 20f, 370f, 180f, CornerRadius(40f)), 1f)
        val cast = polygon(RoundRect(222f, 32f, 382f, 192f, CornerRadius(40f)), 1f)
        for (y in 100 until 200) for (x in 300 until 390) {
            val color = pixels[x, y]
            val point = Offset(x + 0.5f, y + 0.5f)
            val inOwn = depth(own, point)
            val inCast = depth(cast, point)
            when {
                inOwn > 1f -> assertTrue(isColor(RED, color), "($x, $y) is the node: $color")
                inOwn < -1f && inCast > 1f -> assertTrue(isColor(BLUE, color), "($x, $y) is the shadow: $color")
                max(inOwn, inCast) < -1f -> assertTrue(isColor(WHITE, color), "($x, $y) is the page: $color")
            }
        }
    }
}
