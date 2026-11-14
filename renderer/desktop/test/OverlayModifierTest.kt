package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An overlay is laid over the node in the outline its background fills, and takes no room.
 *
 * The modifier indices are the slots the Host's writer gives each modifier (the shape 12,
 * the background 14, the padding 18, the overlay 27), so the chain is the one a real node
 * builds: the overlay comes after the padding in the list and still covers the padding.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayModifierTest {
    private val pageArgb = 0xFF102030.toInt()
    private val fillArgb = 0xFFFFFFFF.toInt()
    private val overlayArgb = 0xFF20A040.toInt()
    private val page = Paint.Literal(pageArgb)
    private val fill = Paint.Literal(fillArgb)
    private val wash = Paint.Literal(overlayArgb)

    private fun theme() =
        Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))

    @Test
    fun fr60_overlay_follows_bounds_and_shape() {
        // A radius of 12 on every corner, given three ways: as a `Shape`, as the corners an
        // HTML box names, and as those corners curved.
        val shapes = listOf(
            "Shape" to listOf(ProtocolModifier.Shape(12f, 12f, 12f, 12f)),
            "CornerEach" to listOf(ProtocolModifier.CornerEach(12f, 12f, 12f, 12f)),
            "CornerEach with CornerCurve" to listOf(
                ProtocolModifier.CornerEach(12f, 12f, 12f, 12f),
                ProtocolModifier.CornerCurve(0.6f),
            ),
        )
        for ((name, shape) in shapes) {
            runComposeUiTest {
                val batch = buildList {
                    add(theme())
                    add(Mutation.Create(1, WidgetKind.Box))
                    add(Mutation.SetModifier(1, 0, ProtocolModifier.Size(100f, 100f)))
                    add(Mutation.SetModifier(1, 1, ProtocolModifier.Background(page)))
                    add(Mutation.Create(2, WidgetKind.Box))
                    add(Mutation.SetModifier(2, 3, ProtocolModifier.Size(80f, 80f)))
                    shape.forEachIndexed { index, value -> add(Mutation.SetModifier(2, if (index == 0) 12 else 26, value)) }
                    add(Mutation.SetModifier(2, 14, ProtocolModifier.Background(fill)))
                    add(Mutation.SetModifier(2, 18, ProtocolModifier.Padding(10f)))
                    add(Mutation.SetModifier(2, 27, ProtocolModifier.Overlay(wash)))
                    add(Mutation.Insert(1, 2, 0))
                }
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
                    }
                }
                waitForIdle()
                // The page, with the node in its top left corner. The node's own test tag sits
                // inside its padding, so the page is what shows the whole box.
                val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
                for ((x, y) in listOf(0 to 0, 79 to 0, 0 to 79, 79 to 79, 1 to 1, 84 to 40, 40 to 84)) {
                    assertEquals(Color(pageArgb), pixels[x, y], "$name: ($x, $y) is outside the rounded node")
                }
                // Inside the padding, at the edge, and in the middle: the whole box the
                // background fills is covered, not only the padded content.
                for ((x, y) in listOf(3 to 40, 40 to 3, 40 to 40, 76 to 40)) {
                    assertEquals(Color(overlayArgb), pixels[x, y], "$name: ($x, $y) is inside the node")
                }
            }
        }
    }

    @Test
    fun fr60_overlay_follows_bounds_and_shape_without_growing_a_content_sized_node() = runComposeUiTest {
        val batch = listOf(
            theme(),
            Mutation.Create(1, WidgetKind.Row),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(200f, 100f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(page)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 12, ProtocolModifier.Shape(12f, 12f, 12f, 12f)),
            Mutation.SetModifier(2, 27, ProtocolModifier.Overlay(wash)),
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(40f, 24f)),
            Mutation.SetModifier(3, 1, ProtocolModifier.Background(fill)),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(1, 2, 0),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(2)).assertWidthIsEqualTo(40.dp)
        onNodeWithTag(nodeTestTag(2)).assertHeightIsEqualTo(24.dp)
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        // Over the child, and nowhere past it.
        assertEquals(Color(overlayArgb), pixels[20, 12])
        assertEquals(Color(pageArgb), pixels[60, 12], "the overlay stops at the node's edge")
        assertEquals(Color(pageArgb), pixels[20, 50], "the overlay stops at the node's edge")
    }
}
