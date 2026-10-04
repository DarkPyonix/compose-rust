package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps

/**
 * Whether a frame drawn on the CPU looks like the same frame drawn on the GPU, asked for
 * with DXC_PIXEL_COMPARE=1.
 *
 * The window draws resize frames with Skia's raster backend and every other frame with
 * Direct3D, so the picture switches between the two at the start and end of every resize.
 * This draws one test scene through both, set up the way the window sets them up (sRGB,
 * premultiplied, horizontal RGB subpixel order), and compares the pixels region by region:
 * text with subpixel antialiasing, a gradient, thin and fractional lines, curved edges, and
 * an image that lives in a GPU texture and is read back once for the CPU, as GPU-only
 * content is when a CPU resize begins.
 *
 * The verdict: a region passes when its mean difference is at most 1 level per channel and
 * at most 1% of its pixels differ by more than 8 levels. One level is below what an 8-bit
 * display shows; a few pixels on glyph edges differing by more is what two rasterisers of
 * the same coverage produce, and a switch that happens only as a drag starts and stops does
 * not show them. A colour-space or gamma mismatch moves whole regions, which fails the mean.
 */
internal object PixelCompare {
    private const val WIDTH = 480
    private const val HEIGHT = 320

    private class Region(val name: String, val rect: Rect)

    private val regions = listOf(
        Region("text", Rect.makeXYWH(0f, 0f, 480f, 80f)),
        Region("gradient", Rect.makeXYWH(0f, 80f, 480f, 60f)),
        Region("lines", Rect.makeXYWH(0f, 140f, 240f, 100f)),
        Region("edges", Rect.makeXYWH(240f, 140f, 240f, 100f)),
        Region("gpu-image", Rect.makeXYWH(0f, 240f, 480f, 80f)),
    )

    /** Runs the comparison and prints one line per region and a verdict. True when it passes. */
    fun run(context: DirectContext): Boolean {
        val props = SurfaceProps(PixelGeometry.RGB_H)
        val gpuInfo = ImageInfo(WIDTH, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        val cpuInfo = ImageInfo(WIDTH, HEIGHT, ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)

        // GPU-only content: an image whose pixels exist only in a texture.
        val source = Surface.makeRenderTarget(context, false, gpuInfo, 0, props)
        drawImageSource(source.canvas)
        source.flushAndSubmit(true)
        val textureImage = source.makeImageSnapshot()
        // The CPU path's copy of it, read back once.
        val readBack = Bitmap()
        readBack.allocPixels(gpuInfo)
        textureImage.readPixels(context, readBack, 0, 0)
        val rasterImage = Image.makeFromBitmap(readBack)

        val gpu = Surface.makeRenderTarget(context, false, gpuInfo, 0, props)
        drawScene(gpu.canvas, textureImage)
        gpu.flushAndSubmit(true)
        val cpu = Surface.makeRaster(cpuInfo, WIDTH * 4, props)
        drawScene(cpu.canvas, rasterImage)

        val gpuPixels = read(gpu, gpuInfo)
        val cpuPixels = read(cpu, gpuInfo)
        var passed = gpuPixels != null && cpuPixels != null
        if (gpuPixels != null && cpuPixels != null) {
            for (region in regions) {
                passed = report(region, gpuPixels, cpuPixels) && passed
            }
        } else {
            System.err.println("compose-rust: pixel-compare could not read a surface back")
        }
        System.err.println("compose-rust: pixel-compare verdict ${if (passed) "PASS" else "FAIL"}")
        source.close()
        gpu.close()
        cpu.close()
        return passed
    }

    private fun read(surface: Surface, info: ImageInfo): ByteArray? {
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        if (!surface.readPixels(bitmap, 0, 0)) return null
        return bitmap.readPixels(info, WIDTH * 4, 0, 0)
    }

    private fun report(region: Region, gpu: ByteArray, cpu: ByteArray): Boolean {
        var max = 0
        var sum = 0L
        var samples = 0L
        var differing = 0
        var pixels = 0
        val left = region.rect.left.toInt()
        val top = region.rect.top.toInt()
        for (y in top until region.rect.bottom.toInt()) {
            for (x in left until region.rect.right.toInt()) {
                val at = (y * WIDTH + x) * 4
                var worst = 0
                for (channel in 0 until 4) {
                    val difference = kotlin.math.abs((gpu[at + channel].toInt() and 0xFF) - (cpu[at + channel].toInt() and 0xFF))
                    sum += difference
                    samples++
                    if (difference > worst) worst = difference
                }
                if (worst > max) max = worst
                if (worst > 8) differing++
                pixels++
            }
        }
        val mean = sum.toDouble() / samples
        val fraction = differing.toDouble() / pixels
        val passed = mean <= 1.0 && fraction <= 0.01
        System.err.println(
            "compose-rust: pixel-compare region=${region.name} max=$max " +
                "mean=${hundredths(mean)} differing_over_8=$differing/$pixels " +
                "(${hundredths(fraction * 100)}%) ${if (passed) "pass" else "FAIL"}",
        )
        return passed
    }

    private fun drawImageSource(canvas: Canvas) {
        canvas.clear(Color.WHITE)
        val paint = Paint().apply { isAntiAlias = true }
        for (index in 0 until 12) {
            paint.color = Color.makeRGB(20 * index, 255 - 20 * index, 128)
            canvas.drawCircle(20f + index * 38f, 280f, 16f, paint)
        }
    }

    private fun drawScene(canvas: Canvas, image: Image) {
        canvas.clear(Color.WHITE)
        val typeface = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.NORMAL)
            ?: FontMgr.default.legacyMakeTypeface("", FontStyle.NORMAL)
        val text = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        for ((row, size) in listOf(12f, 16f, 24f).withIndex()) {
            val font = Font(typeface, size).apply { edging = FontEdging.SUBPIXEL_ANTI_ALIAS; isSubpixel = true }
            canvas.drawString("Hamburgefonstiv 0123 한글", 8.5f, 20f + row * 24f, font, text)
        }
        val gradient = Paint().apply {
            shader = Shader.makeLinearGradient(Point(0f, 0f), Point(480f, 0f), intArrayOf(Color.RED, Color.BLUE))
        }
        canvas.drawRect(Rect.makeXYWH(0f, 80f, 480f, 60f), gradient)
        val line = Paint().apply { color = Color.BLACK; isAntiAlias = true; mode = PaintMode.STROKE }
        for (index in 0 until 10) {
            line.strokeWidth = if (index % 2 == 0) 1f else 0.5f
            val x = 10f + index * 22.3f
            canvas.drawLine(x, 150f, x + 15f, 230f, line)
        }
        val edge = Paint().apply { color = Color.makeRGB(30, 120, 220); isAntiAlias = true }
        canvas.drawCircle(300f, 190f, 40.3f, edge)
        canvas.drawRRect(org.jetbrains.skia.RRect.makeXYWH(350.5f, 155.25f, 110f, 70f, 12f), edge)
        val band = Rect.makeXYWH(0f, 240f, 480f, 80f)
        canvas.drawImageRect(image, band, band)
    }

    private fun hundredths(value: Double): String {
        val scaled = kotlin.math.round(value * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}
