@file:OptIn(
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import kotlin.native.Platform
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.RTreeFactory
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps

/**
 * The ways a resize frame can be drawn on the CPU, all at the full new size.
 *
 * - [DIRECT]: the scene paints straight into the window's pixels.
 * - [PICTURE]: the scene is recorded once into a picture, which is then played into them.
 * - [TILES]: recorded once, then played on every core at once, each into its own band of
 *   rows. The bands do not overlap, so no two threads write the same pixel.
 * - [OPAQUE], [TILES_OPAQUE]: as [DIRECT] and [TILES], with the pixels declared opaque, which
 *   lets Skia skip blending against what is under a fully covering draw.
 */
internal enum class RasterVariant(val label: String) {
    DIRECT("direct"),
    PICTURE("picture"),
    TILES("tiles"),
    OPAQUE("opaque"),
    TILES_OPAQUE("tiles-opaque"),
    ;

    val tiled get() = this == TILES || this == TILES_OPAQUE
    val recorded get() = this != DIRECT && this != OPAQUE
    val alphaType get() = if (this == OPAQUE || this == TILES_OPAQUE) ColorAlphaType.OPAQUE else ColorAlphaType.PREMUL

    companion object {
        /** DXC_RASTER_DRAW names one; tiles otherwise, the one measured fastest. */
        val chosen: RasterVariant by lazy {
            val asked = System.getenv("DXC_RASTER_DRAW")
            entries.firstOrNull { it.label == asked } ?: TILES
        }
    }
}

/** One band of rows for one thread: its pixels, and the picture to play into them. */
private class Band(
    val pixels: COpaquePointer,
    val rowBytes: Int,
    val width: Int,
    val top: Int,
    val height: Int,
    val alphaType: ColorAlphaType,
    val picture: Picture,
)

private fun playBand(band: Band) {
    val surface = Surface.makeRasterDirect(
        ImageInfo(band.width, band.height, ColorType.BGRA_8888, band.alphaType, ColorSpace.sRGB),
        band.pixels.rawValue,
        band.rowBytes,
        SurfaceProps(PixelGeometry.RGB_H),
    )
    surface.canvas.translate(0f, -band.top.toFloat())
    surface.canvas.drawPicture(band.picture)
    surface.close()
}

internal object RasterDraw {
    /** The threads a tiled frame uses, this one included. */
    val threads: Int = Platform.getAvailableProcessors().coerceIn(1, 8)

    private val workers: List<Worker> by lazy { List(threads - 1) { Worker.start(name = "raster-band-$it") } }

    /** How the last frame split: recording the scene (layout included) and playing it, in ms. */
    var recordMs = 0.0
        private set
    var rasterMs = 0.0
        private set

    /**
     * Draws one frame of [width] x [height] into [pixels] (BGRA, top-down, [rowBytes] apart)
     * the way [variant] says. [render] paints the scene into the canvas it is given.
     */
    fun draw(
        variant: RasterVariant,
        pixels: COpaquePointer,
        rowBytes: Int,
        width: Int,
        height: Int,
        render: (Canvas) -> Unit,
    ) {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        if (!variant.recorded) {
            val surface = Surface.makeRasterDirect(
                ImageInfo(width, height, ColorType.BGRA_8888, variant.alphaType, ColorSpace.sRGB),
                pixels.rawValue,
                rowBytes,
                SurfaceProps(PixelGeometry.RGB_H),
            )
            render(surface.canvas)
            surface.close()
            recordMs = 0.0
            rasterMs = millis(started)
            return
        }
        val recorder = PictureRecorder()
        val bounds = Rect.makeWH(width.toFloat(), height.toFloat())
        val bbh = if (variant.tiled) RTreeFactory() else null
        render(recorder.beginRecording(bounds, bbh))
        val picture = recorder.finishRecordingAsPicture()
        recordMs = millis(started)
        val played = kotlin.time.TimeSource.Monotonic.markNow()
        val count = if (variant.tiled) threads else 1
        val bands = ArrayList<Band>(count)
        val step = (height + count - 1) / count
        var top = 0
        while (top < height) {
            val rows = minOf(step, height - top)
            val at = (pixels.reinterpret<ByteVar>() + top.toLong() * rowBytes)!!
            bands.add(Band(at, rowBytes, width, top, rows, variant.alphaType, picture))
            top += rows
        }
        val pending = ArrayList<Future<Unit>>(bands.size)
        for (index in 1 until bands.size) {
            val band = bands[index]
            pending.add(workers[index - 1].execute(TransferMode.SAFE, { band }, ::playBand))
        }
        playBand(bands[0])
        for (future in pending) future.result
        rasterMs = millis(played)
        picture.close()
        recorder.close()
        bbh?.close()
    }

    private fun millis(mark: kotlin.time.TimeMark) = mark.elapsedNow().inWholeMicroseconds / 1000.0
}
