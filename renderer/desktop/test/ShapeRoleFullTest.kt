package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.CornerRadius
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
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.resolvedShape
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val GREEN = 0xFF00FF00.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val ROOT = 1

/** Where the short pill and the tall pill sit on the page, in dp. */
private const val LEFT = 20f
private const val SHORT_TOP = 20f
private const val TALL_TOP = 100f
private const val WIDTH = 200f
private const val SHORT = 40f
private const val TALL = 80f

/**
 * `ShapeRole.Full` is a pill at whatever height the node has.
 *
 * An application that wants round ends on a chip, a button or a search field would
 * otherwise have to know the node's height and send half of it as a radius, and send it
 * again when the height changes. The role says the intent instead, and the corner is
 * worked out from the size the node was measured at: 20 dp on a node 40 dp tall, 40 dp
 * on the same node 80 dp tall.
 */
@OptIn(ExperimentalTestApi::class)
class ShapeRoleFullTest {

    // Every test runs at a density of one, so a dp is a pixel.
    private val density = Density(1f)

    @Test
    fun fr13_shape_role_full_outline_is_half_the_height_in_every_design_system() {
        for (system in DesignSystem.entries) {
            val shape = listOf<ProtocolModifier>(ProtocolModifier.ShapeRole(ShapeRole.Full))
                .resolvedShape(theme(system))
            for ((height, radius) in listOf(SHORT to 20f, TALL to 40f)) {
                val outline = shape.createOutline(Size(WIDTH, height), LayoutDirection.Ltr, density)
                assertIs<Outline.Rounded>(outline, "$system at a height of $height")
                val corners = outline.roundRect
                for (corner in listOf(
                    corners.topLeftCornerRadius,
                    corners.topRightCornerRadius,
                    corners.bottomRightCornerRadius,
                    corners.bottomLeftCornerRadius,
                )) {
                    assertEquals(CornerRadius(radius), corner, "$system at a height of $height")
                }
            }
        }
    }

    @Test
    fun fr13_shape_role_full_is_a_pill_at_any_height_material3() = pillAtBothHeights(DesignSystem.Material3)

    @Test
    fun fr13_shape_role_full_is_a_pill_at_any_height_cupertino() = pillAtBothHeights(DesignSystem.Cupertino)

    @Test
    fun fr13_shape_role_full_is_a_pill_at_any_height_fluent() = pillAtBothHeights(DesignSystem.Fluent)

    @Test
    fun fr13_shape_role_full_is_a_pill_at_any_height_liquid_glass() = pillAtBothHeights(DesignSystem.LiquidGlass)

    @Test
    fun fr13_shape_role_full_clip_and_border_follow_the_pill_material3() = clipAndBorderFollow(DesignSystem.Material3)

    @Test
    fun fr13_shape_role_full_clip_and_border_follow_the_pill_cupertino() = clipAndBorderFollow(DesignSystem.Cupertino)

    /**
     * The same filled box at two heights. Every pixel clear of the antialiased edge is
     * judged against the capsule whose radius is half the height, so a corner cut at any
     * other radius fails: a 20 dp corner on the tall box leaves a straight flank where
     * the capsule is already curving away.
     */
    private fun pillAtBothHeights(system: DesignSystem) = runComposeUiTest {
        val pixels = picture(
            page(system) {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(LEFT, SHORT_TOP),
                    ProtocolModifier.RequiredSize(WIDTH, SHORT),
                    ProtocolModifier.ShapeRole(ShapeRole.Full),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                )
                node(
                    3, ROOT, 1,
                    ProtocolModifier.Offset(LEFT, TALL_TOP),
                    ProtocolModifier.RequiredSize(WIDTH, TALL),
                    ProtocolModifier.ShapeRole(ShapeRole.Full),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                )
            },
        )
        for ((top, height) in listOf(SHORT_TOP to SHORT, TALL_TOP to TALL)) {
            var inside = 0
            var outside = 0
            forEachPixelNear(top, height) { x, y, distance ->
                val actual = pixels[x, y]
                when {
                    distance < -1.5f -> {
                        assertTrue(isColor(RED, actual), "$system, height $height: ($x, $y) is inside the pill and is $actual")
                        inside++
                    }
                    distance > 1.5f -> {
                        assertTrue(isColor(WHITE, actual), "$system, height $height: ($x, $y) is outside the pill and is $actual")
                        outside++
                    }
                }
            }
            assertTrue(inside > 0 && outside > 0, "$system, height $height: nothing was compared")

            // The four pixels a radius of half the height decides and a smaller one does
            // not: the box's own corners, and the start edge a quarter of the way down,
            // where a pill has curved away and a smaller corner is still a straight side.
            val x = LEFT.toInt()
            val y = top.toInt()
            assertTrue(isColor(WHITE, pixels[x, y]), "$system, height $height: the top left corner is filled")
            assertTrue(
                isColor(WHITE, pixels[x + WIDTH.toInt() - 1, y + height.toInt() - 1]),
                "$system, height $height: the bottom right corner is filled",
            )
            assertTrue(
                isColor(WHITE, pixels[x, y + height.toInt() / 4]),
                "$system, height $height: the start edge is straight a quarter of the way down, so the end is not a half circle",
            )
            assertTrue(
                isColor(RED, pixels[x + 2, y + height.toInt() / 2]),
                "$system, height $height: the end does not reach the start edge at mid height",
            )
        }
    }

    /**
     * A pill that clips a larger green child and carries a 4 dp black border: nothing of
     * the child shows outside the capsule, and the border runs round its ends.
     */
    private fun clipAndBorderFollow(system: DesignSystem) = runComposeUiTest {
        val pixels = picture(
            page(system) {
                node(
                    2, ROOT, 0,
                    ProtocolModifier.Offset(LEFT, TALL_TOP),
                    ProtocolModifier.RequiredSize(WIDTH, TALL),
                    ProtocolModifier.ShapeRole(ShapeRole.Full),
                    ProtocolModifier.Clip(true),
                    ProtocolModifier.Background(Paint.Literal(RED)),
                    ProtocolModifier.Border(4f, Paint.Literal(BLACK)),
                )
                node(3, 2, 0, ProtocolModifier.RequiredSize(300f, 160f), ProtocolModifier.Background(Paint.Literal(GREEN)))
            },
        )
        var onTheBorder = 0
        var outside = 0
        forEachPixelNear(TALL_TOP, TALL) { x, y, distance ->
            val actual = pixels[x, y]
            when {
                distance > 1.5f -> {
                    assertTrue(isColor(WHITE, actual), "$system: ($x, $y) is outside the pill and is $actual")
                    outside++
                }
                distance < -1f && distance > -3f -> {
                    assertTrue(isColor(BLACK, actual), "$system: ($x, $y) is on the border and is $actual")
                    onTheBorder++
                }
            }
        }
        assertTrue(onTheBorder > 0 && outside > 0, "$system: nothing was compared")
    }

    /**
     * Calls [visit] for every pixel of the pill's box and a 10 px margin round it, with
     * the pixel's distance from the capsule's edge: negative inside, positive outside.
     * The capsule is the box with a half circle of half its height at each end.
     */
    private fun forEachPixelNear(top: Float, height: Float, visit: (x: Int, y: Int, distance: Float) -> Unit) {
        val radius = height / 2f
        val centreY = top + radius
        val margin = 10
        for (y in (top.toInt() - margin) until (top + height).toInt() + margin) {
            for (x in (LEFT.toInt() - margin) until (LEFT + WIDTH).toInt() + margin) {
                val px = x + 0.5f
                val py = y + 0.5f
                val nearestX = px.coerceIn(LEFT + radius, LEFT + WIDTH - radius)
                visit(x, y, hypot(px - nearestX, py - centreY) - radius)
            }
        }
    }

    private fun theme(system: DesignSystem) = resolveTheme(
        Theme(system, system, ColorScheme.Light, adaptive = false),
        HostPlatform.Unknown,
        systemDark = false,
        highContrast = { false },
    )

    private fun page(system: DesignSystem, build: MutableList<Mutation>.() -> Unit): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)))
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
}
