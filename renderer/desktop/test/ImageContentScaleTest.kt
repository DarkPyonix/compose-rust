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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ContentScale
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

private const val IMAGE = 1
private const val ASSET = 7
private const val BOX_WIDTH = 40f
private const val BOX_HEIGHT = 80f

private val FILL = Color(0xFF336699)

/**
 * A picture twice as wide as it is tall, all one colour, as a real PNG file. Laid into a box
 * twice as tall as it is wide, the fit is a band a quarter of the box's height across the
 * middle, so whether it was stretched is plain from the box's top row.
 */
private fun widePng(): ByteArray {
    val surface = Surface.makeRasterN32Premul(20, 10)
    surface.canvas.drawRect(Rect.makeWH(20f, 10f), Paint().apply { color = 0xFF336699.toInt() })
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}

/** The same picture as a document, with a viewBox that gives it the same proportions. */
private val WIDE_SVG = (
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 10\">" +
        "<rect width=\"20\" height=\"10\" fill=\"#336699\"/></svg>"
    ).toByteArray(Charsets.UTF_8)

@OptIn(ExperimentalTestApi::class)
private fun drawn(kind: AssetKind, bytes: ByteArray, scale: Long?): Pair<PixelMap, List<HostEvent>> {
    var pixels: PixelMap? = null
    var events: List<HostEvent> = emptyList()
    runComposeUiTest {
        val connection = FakeHostConnection(
            buildList {
                add(Mutation.RegisterAsset(ASSET, kind.ordinal + 1, bytes))
                add(Mutation.Create(IMAGE, WidgetKind.Image))
                add(Mutation.SetModifier(IMAGE, 0, ProtocolModifier.Size(BOX_WIDTH, BOX_HEIGHT)))
                add(Mutation.SetProp(IMAGE, PropertyKind.Asset, PropertyValue.Integer(ASSET.toLong())))
                if (scale != null) {
                    add(Mutation.SetProp(IMAGE, PropertyKind.ContentScale, PropertyValue.Integer(scale)))
                }
            },
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        pixels = onNodeWithTag(nodeTestTag(IMAGE)).captureToImage().toPixelMap()
        events = connection.events.toList()
    }
    return pixels!! to events
}

private fun tag(scale: ContentScale) = (scale.ordinal + 1).toLong()

private fun PixelMap.rows(): List<List<Color>> =
    (0 until height).map { y -> (0 until width).map { x -> this[x, y] } }

/** Every pixel of the box, sampled one inset from each edge so antialiasing cannot decide it. */
private fun PixelMap.coveredEverywhere(): Boolean =
    (1 until height - 1).all { y -> (1 until width - 1).all { x -> this[x, y] == FILL } }

@OptIn(ExperimentalTestApi::class)
class ImageContentScaleTest {
    /**
     * The box is twice as tall as it is wide and the picture twice as wide as it is tall, so
     * a picture that kept its proportions leaves most of the box empty. `FillBounds` leaves
     * none of it, for a raster and for a vector alike, and an image that was sent no scale
     * draws exactly as one that was sent `Fit`, which is how every image drew before.
     */
    @Test
    fun fr16_image_content_scale_fill_bounds_stretches() {
        for ((kind, bytes) in listOf(AssetKind.Png to widePng(), AssetKind.Svg to WIDE_SVG)) {
            val (filled, filledEvents) = drawn(kind, bytes, tag(ContentScale.FillBounds))
            assertTrue(
                filled.coveredEverywhere(),
                "$kind: a FillBounds picture leaves part of its box empty",
            )
            assertEquals(
                emptyList(),
                filledEvents.filterIsInstance<HostEvent.ProtocolError>(),
                "$kind: FillBounds is a scale the renderer knows",
            )

            val (unsent, _) = drawn(kind, bytes, null)
            val (fit, _) = drawn(kind, bytes, tag(ContentScale.Fit))
            assertEquals(
                fit.rows(),
                unsent.rows(),
                "$kind: an image that was sent no scale must draw as Fit",
            )
            assertNotEquals(
                FILL,
                unsent[unsent.width / 2, 1],
                "$kind: a picture of other proportions fits with room above it",
            )
            assertEquals(
                FILL,
                unsent[unsent.width / 2, unsent.height / 2],
                "$kind: a fitted picture sits in the middle of its box",
            )
        }
    }

    /** `Crop` keeps the proportions and covers the box, so it too leaves no part empty. */
    @Test
    fun fr16_image_content_scale_crop_covers_the_box() {
        for ((kind, bytes) in listOf(AssetKind.Png to widePng(), AssetKind.Svg to WIDE_SVG)) {
            val (cropped, _) = drawn(kind, bytes, tag(ContentScale.Crop))
            assertTrue(cropped.coveredEverywhere(), "$kind: a cropped picture leaves its box empty")
        }
    }

    /** A scale the renderer does not know is reported, and the picture is drawn as Fit. */
    @Test
    fun fr16_image_unknown_content_scale_is_a_protocol_error_and_draws_fit() {
        val bytes = widePng()
        val (unknown, events) = drawn(AssetKind.Png, bytes, 9L)
        assertTrue(
            events.filterIsInstance<HostEvent.ProtocolError>().isNotEmpty(),
            "a content scale outside Fit, Crop and FillBounds must be reported",
        )
        val (fit, _) = drawn(AssetKind.Png, bytes, tag(ContentScale.Fit))
        assertEquals(fit.rows(), unknown.rows(), "an unknown content scale must draw as Fit")
    }
}
