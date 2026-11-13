package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.BackgroundRepeat
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val BOX = 1
private const val GRADIENT = 5
private const val BRUSH_KIND = 6
private const val RED = 0xFFFF0000.toInt()
private const val BLUE = 0xFF0000FF.toInt()

/** A gradient from red at the left edge to blue at the right, in this project's brush layout. */
private fun horizontalGradient(): ByteArray {
    val buffer = ByteBuffer.allocate(24 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putShort(1) // linear
    buffer.putShort(2) // two stops
    buffer.putShort(1) // clamp
    buffer.putShort(0)
    for (coordinate in floatArrayOf(0f, 0.5f, 1f, 0.5f)) buffer.putFloat(coordinate)
    buffer.putFloat(0f).putInt(RED)
    buffer.putFloat(1f).putInt(BLUE)
    return buffer.array()
}

@OptIn(ExperimentalTestApi::class)
private fun drawn(vararg modifiers: ProtocolModifier): Pair<PixelMap, List<HostEvent>> {
    var pixels: PixelMap? = null
    var events: List<HostEvent> = emptyList()
    runComposeUiTest {
        val connection = FakeHostConnection(
            buildList {
                add(Mutation.RegisterAsset(GRADIENT, BRUSH_KIND, horizontalGradient()))
                add(Mutation.Create(BOX, WidgetKind.Box))
                add(Mutation.SetModifier(BOX, 0, ProtocolModifier.Size(100f, 50f)))
                modifiers.forEachIndexed { index, modifier -> add(Mutation.SetModifier(BOX, index + 1, modifier)) }
            },
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        pixels = onNodeWithTag(nodeTestTag(BOX)).captureToImage().toPixelMap()
        events = connection.events.toList()
    }
    return pixels!! to events
}

private fun layer(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    paint: Paint,
    repeat: BackgroundRepeat,
    tileWidth: Float = width,
    tileHeight: Float = height,
) = ProtocolModifier.BackgroundBrush(x, y, width, height, paint, repeat, tileWidth, tileHeight)

private fun Color.isNear(other: Color) =
    abs(red - other.red) < 0.08f && abs(green - other.green) < 0.08f && abs(blue - other.blue) < 0.08f &&
        abs(alpha - other.alpha) < 0.08f

/**
 * One node draws a brush in a rectangle of itself and repeats it the way it was told,
 * where the HTML path used to build a clipped box and a box for every tile.
 */
@OptIn(ExperimentalTestApi::class)
class BackgroundBrushTest {
    private val solid = Paint.Literal(RED)

    /** Without a repeat the brush is in the rectangle and nowhere else. */
    @Test
    fun fr42_background_brush_without_repeat_fills_only_its_rectangle() {
        val (pixels, events) = drawn(layer(10f, 10f, 20f, 10f, solid, BackgroundRepeat.NoRepeat))
        assertEquals(emptyList(), events.filterIsInstance<HostEvent.ProtocolError>())
        assertEquals(Color(RED), pixels[15, 15], "inside the rectangle")
        assertNotEquals(Color(RED), pixels[35, 15], "right of the rectangle")
        assertNotEquals(Color(RED), pixels[15, 25], "below the rectangle")
        assertNotEquals(Color(RED), pixels[5, 5], "above and left of it")
    }

    /**
     * Repeated along the width only, every 40 dp, both ways from the rectangle: the copy
     * before it, at -30, reaches nothing, and the copies at 50 and 90 are drawn, the last
     * cut at the node's edge. No copy goes above or below the row.
     */
    @Test
    fun fr42_background_brush_repeat_x_steps_by_the_tile_width() {
        val (pixels, _) = drawn(layer(10f, 10f, 20f, 10f, solid, BackgroundRepeat.RepeatX, 40f, 10f))
        for (x in listOf(15, 55, 95)) assertEquals(Color(RED), pixels[x, 15], "a copy at x $x")
        for (x in listOf(5, 35, 75)) assertNotEquals(Color(RED), pixels[x, 15], "a gap at x $x")
        assertNotEquals(Color(RED), pixels[15, 35], "a row repeat runs along the width only")
    }

    /** Repeated both ways, a rectangle placed off the node's corner still covers it to its edges. */
    @Test
    fun fr42_background_brush_repeat_covers_the_node_both_ways() {
        val (pixels, _) = drawn(layer(-5f, -5f, 10f, 10f, solid, BackgroundRepeat.Repeat))
        for (y in listOf(1, 24, 48)) {
            for (x in listOf(1, 50, 98)) assertEquals(Color(RED), pixels[x, y], "($x, $y) is covered")
        }
    }

    /**
     * A gradient is measured against one tile, not the node: across each 25 dp copy it runs
     * from red to blue, and starts again at the next.
     */
    @Test
    fun fr42_background_brush_gradient_is_measured_against_the_tile() {
        val (pixels, events) = drawn(
            layer(0f, 0f, 25f, 50f, Paint.Asset(GRADIENT), BackgroundRepeat.RepeatX),
        )
        assertEquals(emptyList(), events.filterIsInstance<HostEvent.ProtocolError>())
        for (start in listOf(0, 25, 50, 75)) {
            assertTrue(pixels[start, 25].isNear(Color(RED)), "the copy at $start starts red: ${pixels[start, 25]}")
            assertTrue(
                pixels[start + 24, 25].isNear(Color(BLUE)),
                "the copy at $start ends blue: ${pixels[start + 24, 25]}",
            )
        }
    }

    /** Over a background colour, and cut to the node's corners as CSS cuts a background. */
    @Test
    fun fr42_background_brush_lies_over_the_background_and_follows_the_corners() {
        val (pixels, _) = drawn(
            ProtocolModifier.CornerEach(20f, 20f, 20f, 20f),
            ProtocolModifier.Background(Paint.Literal(BLUE)),
            layer(0f, 0f, 100f, 50f, solid, BackgroundRepeat.NoRepeat),
        )
        assertEquals(Color(RED), pixels[50, 25], "the layer covers the colour")
        assertNotEquals(Color(RED), pixels[1, 1], "the layer is cut to the rounded corner")
    }

    /** A step that is not a size is refused as a protocol error and nothing is drawn. */
    @Test
    fun fr42_background_brush_with_a_step_that_is_not_a_size_is_refused() {
        for ((width, height) in listOf(0f to 10f, 40f to -1f)) {
            val (pixels, events) = drawn(layer(10f, 10f, 20f, 10f, solid, BackgroundRepeat.Repeat, width, height))
            assertTrue(
                events.filterIsInstance<HostEvent.ProtocolError>().any { it.code == TableError.INVALID_MODIFIER },
                "a step of $width by $height must be reported",
            )
            assertNotEquals(Color(RED), pixels[15, 15], "a refused layer draws nothing")
        }
    }

    /** The record the Rust Host writes decodes to the same values on this side. */
    @Test
    fun fr42_background_brush_round_trips() {
        val bytes = vector()
        assertEquals(64, bytes.size)
        val mutations = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), mutations::add)
        assertEquals<List<Mutation>>(
            listOf(
                Mutation.SetModifier(
                    4,
                    2,
                    layer(12.5f, -4f, 48f, 24f, Paint.Asset(9), BackgroundRepeat.RepeatX, 56f, 30f),
                ),
            ),
            mutations,
        )
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(52, buffer.getShort(14).toInt(), "a 52 byte record")
        assertEquals(28, buffer.getShort(22).toInt(), "modifier tag 28")
    }

    private fun vector(): ByteArray {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "compose-rust/tests/vectors/background_brush.bin")
            if (candidate.isFile) return candidate.readBytes()
            directory = directory.parentFile
        }
        error("protocol vector background_brush.bin not found above ${System.getProperty("user.dir")}")
    }
}
