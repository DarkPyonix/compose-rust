@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.ui.node.LayoutProfile
import java.lang.System
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.readBytes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps

/**
 * How long a resize frame takes to draw on the CPU at twice the scale, each way
 * [RasterVariant] offers, asked for with DXC_DRAW_BENCH=1.
 *
 * A live resize at 200% asks for frames of about 960 x 1400 pixels at density 2, and the
 * whole step has one refresh (16 ms) to fit in. This takes the window's own scene through
 * the sizes such a drag passes, 961 to 1157 pixels wide and 1400 high at density 2, whatever
 * the runner's display scale is, and times every frame from the new size to the last pixel,
 * layout included, as the resize step does. Then it draws the scene once more at the size it
 * stopped at, which is the cost when every layer's recording is still valid. Last it draws one
 * frame each way and the same frame on the GPU, and compares the pixels with the thresholds
 * the CPU/GPU comparison uses.
 */
internal object DrawBench {
    private val enabled = System.getenv("DXC_DRAW_BENCH") == "1"
    private val started = kotlin.time.TimeSource.Monotonic.markNow()
    private var done = false
    private var layoutMs = 0.0
    private var pendingAfterLayout = false

    private const val HEIGHT = 1400
    private const val FIRST_WIDTH = 961
    private const val STEP = 4
    private const val SIZES = 50
    private const val WARMUP = 5
    private const val CACHED = 10
    private const val COMPARE_WIDTH = 1061

    /** True once, two seconds in. */
    fun due(): Boolean = enabled && !done && started.elapsedNow().inWholeMilliseconds >= 2_000

    fun run(scene: ComposeScene, context: DirectContext, nanos: () -> Long) {
        done = true
        val savedSize = scene.size
        val savedDensity = scene.density
        val maxWidth = FIRST_WIDTH + STEP * (SIZES - 1)
        val rowBytes = maxWidth * 4
        val pixels = nativeHeap.allocArray<ByteVar>(rowBytes.toLong() * HEIGHT)
        System.err.println(
            "compose-rust: draw-bench threads=${RasterDraw.threads} " +
                "sizes=${FIRST_WIDTH}..${maxWidth}x$HEIGHT density=2",
        )
        try {
            scene.density = Density(2f)
            if (LayoutProfile.enabled) profile(scene, pixels, rowBytes, nanos)
            for (variant in RasterVariant.entries) {
                fun frame(width: Int) {
                    scene.size = IntSize(width, HEIGHT)
                    // Measure and place first, on their own, so the frame's time splits into
                    // layout and the rest (recomposition, recording the layers, playing them).
                    val laid = kotlin.time.TimeSource.Monotonic.markNow()
                    scene.focusManager.getFocusRect(afterLayout = true)
                    layoutMs = laid.elapsedNow().inWholeMicroseconds / 1000.0
                    pendingAfterLayout = scene.hasInvalidations()
                    RasterDraw.draw(variant, pixels, rowBytes, width, HEIGHT) { canvas ->
                        scene.render(canvas.asComposeCanvas(), nanos())
                    }
                }
                for (index in 0 until WARMUP) frame(FIRST_WIDTH + STEP * index)
                val total = ArrayList<Double>()
                val record = ArrayList<Double>()
                val raster = ArrayList<Double>()
                val layout = ArrayList<Double>()
                var pending = 0
                for (index in 0 until SIZES) {
                    val mark = kotlin.time.TimeSource.Monotonic.markNow()
                    frame(FIRST_WIDTH + STEP * index)
                    total.add(mark.elapsedNow().inWholeMicroseconds / 1000.0)
                    record.add(RasterDraw.recordMs)
                    raster.add(RasterDraw.rasterMs)
                    layout.add(layoutMs)
                    if (pendingAfterLayout) pending++
                }
                val cached = ArrayList<Double>()
                for (index in 0 until CACHED) {
                    val mark = kotlin.time.TimeSource.Monotonic.markNow()
                    frame(maxWidth)
                    cached.add(mark.elapsedNow().inWholeMicroseconds / 1000.0)
                }
                frame(COMPARE_WIDTH)
                val compared = compare(scene, context, nanos(), pixels, rowBytes)
                System.err.println(
                    "compose-rust: draw-bench variant=${variant.label} " +
                        "median=${f(pct(total, 50))} p90=${f(pct(total, 90))} max=${f(total.max())} " +
                        "layout_median=${f(pct(layout, 50))} pending_after_layout=$pending/$SIZES " +
                        "record_median=${f(pct(record, 50))} raster_median=${f(pct(raster, 50))} " +
                        "cached_median=${f(pct(cached, 50))} $compared",
                )
            }
            // Only the height changing, at one width: what a size change costs when no text
            // has to be laid out again across a new width.
            val tall = ArrayList<Double>()
            for (index in 0 until SIZES) {
                val height = HEIGHT - 4 * (SIZES - index)
                scene.size = IntSize(FIRST_WIDTH, height)
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                RasterDraw.draw(RasterVariant.DIRECT, pixels, rowBytes, FIRST_WIDTH, height) { canvas ->
                    scene.render(canvas.asComposeCanvas(), nanos())
                }
                tall.add(mark.elapsedNow().inWholeMicroseconds / 1000.0)
            }
            System.err.println(
                "compose-rust: draw-bench height-only width=$FIRST_WIDTH median=${f(pct(tall, 50))} " +
                    "p90=${f(pct(tall, 90))} max=${f(tall.max())}",
            )
        } finally {
            nativeHeap.free(pixels.rawValue)
            scene.density = savedDensity
            scene.size = savedSize
        }
        System.err.println("compose-rust: draw-bench done")
    }

    /**
     * The drag again with every node's measuring timed by kind: per frame, layout as a whole
     * and each kind's own share of it, most first. What no node accounts for is Compose's own
     * work around them.
     */
    private fun profile(
        scene: ComposeScene,
        pixels: kotlinx.cinterop.CPointer<ByteVar>,
        rowBytes: Int,
        nanos: () -> Long,
    ) {
        for (index in 0 until WARMUP) {
            scene.size = IntSize(FIRST_WIDTH + STEP * index, HEIGHT)
            RasterDraw.draw(RasterVariant.DIRECT, pixels, rowBytes, FIRST_WIDTH + STEP * index, HEIGHT) { canvas ->
                scene.render(canvas.asComposeCanvas(), nanos())
            }
        }
        LayoutProfile.reset()
        var layoutTotal = 0.0
        for (index in 0 until SIZES) {
            val width = FIRST_WIDTH + STEP * index
            scene.size = IntSize(width, HEIGHT)
            val laid = kotlin.time.TimeSource.Monotonic.markNow()
            scene.focusManager.getFocusRect(afterLayout = true)
            layoutTotal += laid.elapsedNow().inWholeMicroseconds / 1000.0
            RasterDraw.draw(RasterVariant.DIRECT, pixels, rowBytes, width, HEIGHT) { canvas ->
                scene.render(canvas.asComposeCanvas(), nanos())
            }
        }
        val kinds = LayoutProfile.snapshot()
        val accounted = kinds.sumOf { it.second }
        System.err.println(
            "compose-rust: draw-bench layout-profile per_frame_layout=${f(layoutTotal / SIZES)} " +
                "in_nodes=${f(accounted / SIZES)} outside_nodes=${f((layoutTotal - accounted) / SIZES)}",
        )
        for ((kind, millis, count) in kinds.take(15)) {
            System.err.println(
                "compose-rust: draw-bench layout-profile kind=$kind own_ms_per_frame=${f(millis / SIZES)} " +
                    "measures_per_frame=${count / SIZES}",
            )
        }
    }

    /** The CPU frame just drawn against the same frame on the GPU, over every pixel. */
    private fun compare(
        scene: ComposeScene,
        context: DirectContext,
        nanos: Long,
        pixels: kotlinx.cinterop.CPointer<ByteVar>,
        rowBytes: Int,
    ): String {
        val width = COMPARE_WIDTH
        val info = ImageInfo(width, HEIGHT, ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        val gpu = Surface.makeRenderTarget(context, false, info, 0, SurfaceProps(PixelGeometry.RGB_H))
        scene.render(gpu.canvas.asComposeCanvas(), nanos)
        gpu.flushAndSubmit(true)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        val read = gpu.readPixels(bitmap, 0, 0)
        gpu.close()
        if (!read) return "compare=unreadable verdict=FAIL"
        val gpuBytes = bitmap.readPixels(info, width * 4, 0, 0) ?: return "compare=unreadable verdict=FAIL"
        var max = 0
        var sum = 0L
        var differing = 0L
        for (y in 0 until HEIGHT) {
            val row = (pixels + y.toLong() * rowBytes)!!.readBytes(width * 4)
            val base = y * width * 4
            for (x in 0 until width) {
                var worst = 0
                for (channel in 0 until 3) {
                    val at = x * 4 + channel
                    val d = kotlin.math.abs((row[at].toInt() and 0xFF) - (gpuBytes[base + at].toInt() and 0xFF))
                    sum += d
                    if (d > worst) worst = d
                }
                if (worst > max) max = worst
                if (worst > 8) differing++
            }
        }
        val count = width.toLong() * HEIGHT
        val mean = sum.toDouble() / (count * 3)
        val fraction = differing.toDouble() / count
        val passed = mean <= 1.0 && fraction <= 0.01
        return "compare_mean=${f(mean)} compare_max=$max differing_over_8=${f(fraction * 100)}% " +
            "verdict=${if (passed) "PASS" else "FAIL"}"
    }

    private fun pct(values: List<Double>, p: Int): Double {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p + 50) / 100
        return sorted[index]
    }

    private fun f(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toLong()
        return "${hundredths / 100}.${kotlin.math.abs(hundredths % 100).toString().padStart(2, '0')}"
    }
}
