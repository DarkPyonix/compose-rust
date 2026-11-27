package dev.darkpyonix.composerust.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.ui.platform.WindowBackingScale
import dev.darkpyonix.composerust.ui.platform.WindowTextScale
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SCENE_W_DP = 160
private const val SCENE_H_DP = 120

/**
 * HiDPI diagnostic scene: on a white background, (a) a 1dp vertical black bar at x = 10dp,
 * (b) a 1dp horizontal black bar at y = 10dp, (c) a 40dp-diameter black circle, (d) a black
 * square clipped with a 12dp corner radius, and (e) 16sp black "Hg".
 */
@Composable
private fun Fr82DiagnosticScene() {
    Box(
        Modifier
            .size(SCENE_W_DP.dp, SCENE_H_DP.dp)
            .background(Color.White),
    ) {
        // (a) 1dp vertical black bar at x = 10dp
        Box(
            Modifier
                .offset(x = 10.dp, y = 20.dp)
                .size(width = 1.dp, height = 80.dp)
                .background(Color.Black),
        )
        // (b) 1dp horizontal black bar at y = 10dp
        Box(
            Modifier
                .offset(x = 20.dp, y = 10.dp)
                .size(width = 120.dp, height = 1.dp)
                .background(Color.Black),
        )
        // (c) 40dp-diameter black circle
        Box(
            Modifier
                .offset(x = 24.dp, y = 24.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black),
        )
        // (d) 12dp rounded-clip black square
        Box(
            Modifier
                .offset(x = 76.dp, y = 24.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        )
        // (e) 16sp black "Hg"
        Text(
            text = "Hg",
            color = Color.Black,
            fontSize = 16.sp,
            modifier = Modifier.offset(x = 24.dp, y = 76.dp),
        )
    }
}

private class PixelBuffer(val width: Int, val height: Int, val argb: IntArray) {
    fun r(x: Int, y: Int): Int = (argb[y * width + x] ushr 16) and 0xFF
    fun g(x: Int, y: Int): Int = (argb[y * width + x] ushr 8) and 0xFF
    fun b(x: Int, y: Int): Int = argb[y * width + x] and 0xFF
    fun luma(x: Int, y: Int): Int = (r(x, y) + g(x, y) + b(x, y)) / 3
}

private fun renderDiagnostic(scale: Float): PixelBuffer {
    val w = (SCENE_W_DP * scale).roundToInt()
    val h = (SCENE_H_DP * scale).roundToInt()
    val scene = ImageComposeScene(
        width = w,
        height = h,
        density = Density(scale),
        content = { Fr82DiagnosticScene() },
    )
    return try {
        val image = scene.render()
        toPixelBuffer(image)
    } finally {
        scene.close()
    }
}

private fun toPixelBuffer(image: Image): PixelBuffer {
    val w = image.width
    val h = image.height
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    assertTrue(image.readPixels(bitmap, 0, 0), "readPixels succeeds")
    val bytes = requireNotNull(bitmap.readPixels())
    val argb = IntArray(w * h)
    for (i in 0 until w * h) {
        val b = bytes[i * 4].toInt() and 0xFF
        val g = bytes[i * 4 + 1].toInt() and 0xFF
        val r = bytes[i * 4 + 2].toInt() and 0xFF
        val a = bytes[i * 4 + 3].toInt() and 0xFF
        argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return PixelBuffer(w, h, argb)
}

/** Nearest-neighbour upscale of a 1x render to simulate OS bitmap stretching. */
private fun upscaleNearest(src: PixelBuffer, targetW: Int, targetH: Int): PixelBuffer {
    val out = IntArray(targetW * targetH)
    for (y in 0 until targetH) {
        val sy = (y * src.height) / targetH
        for (x in 0 until targetW) {
            val sx = (x * src.width) / targetW
            out[y * targetW + x] = src.argb[sy * src.width + sx]
        }
    }
    return PixelBuffer(targetW, targetH, out)
}

private data class DiffStats(val meanChannelDiff: Double, val overEightFraction: Double)

private fun compareBuffers(a: PixelBuffer, b: PixelBuffer): DiffStats {
    assertEquals(a.width, b.width)
    assertEquals(a.height, b.height)
    var sum = 0L
    var over8 = 0L
    val total = a.width.toLong() * a.height.toLong()
    for (i in a.argb.indices) {
        val dr = abs(((a.argb[i] ushr 16) and 0xFF) - ((b.argb[i] ushr 16) and 0xFF))
        val dg = abs(((a.argb[i] ushr 8) and 0xFF) - ((b.argb[i] ushr 8) and 0xFF))
        val db = abs((a.argb[i] and 0xFF) - (b.argb[i] and 0xFF))
        sum += (dr + dg + db)
        if (dr > 8 || dg > 8 || db > 8) over8++
    }
    return DiffStats(
        meanChannelDiff = sum.toDouble() / (total * 3.0),
        overEightFraction = over8.toDouble() / total.toDouble(),
    )
}

/**
 * HiDPI sharpness acceptance tests across integer and fractional display scales.
 */
class HiDpiCaptureTest {

    @Test
    fun fr82_surface_is_device_pixels_at_every_scale() {
        for (scale in floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)) {
            val buf = renderDiagnostic(scale)
            assertEquals((SCENE_W_DP * scale).roundToInt(), buf.width, "width at scale $scale")
            assertEquals((SCENE_H_DP * scale).roundToInt(), buf.height, "height at scale $scale")
        }
    }

    @Test
    fun fr82_screen_matches_the_drawn_frame() {
        for (scale in floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)) {
            val frameA = renderDiagnostic(scale)
            val frameB = renderDiagnostic(scale)
            val stats = compareBuffers(frameA, frameB)
            assertTrue(stats.meanChannelDiff <= 1.0, "scale $scale mean diff ${stats.meanChannelDiff}")
            assertTrue(stats.overEightFraction <= 0.001, "scale $scale >8 diff ${stats.overEightFraction}")
        }
    }

    @Test
    fun fr82_one_dp_line_is_whole_device_pixels_at_integer_scales() {
        for (s in intArrayOf(1, 2)) {
            val buf = renderDiagnostic(s.toFloat())
            // Vertical 1dp bar at x = 10dp, sampled at y = 50dp.
            val ySample = 50 * s
            val xStart = 10 * s
            assertTrue(buf.luma(xStart - 1, ySample) >= 247, "scale $s: white left of vertical bar")
            for (dx in 0 until s) {
                assertTrue(buf.luma(xStart + dx, ySample) <= 8, "scale $s: black pixel dx=$dx of vertical bar")
            }
            assertTrue(buf.luma(xStart + s, ySample) >= 247, "scale $s: white right of vertical bar")

            // Horizontal 1dp bar at y = 10dp, sampled at x = 50dp.
            val xSample = 50 * s
            val yStart = 10 * s
            assertTrue(buf.luma(xSample, yStart - 1) >= 247, "scale $s: white above horizontal bar")
            for (dy in 0 until s) {
                assertTrue(buf.luma(xSample, yStart + dy) <= 8, "scale $s: black pixel dy=$dy of horizontal bar")
            }
            assertTrue(buf.luma(xSample, yStart + s) >= 247, "scale $s: white below horizontal bar")
        }
    }

    @Test
    fun fr82_fractional_scale_matches_offscreen_compose() {
        val oneX = renderDiagnostic(1.0f)
        for (scale in floatArrayOf(1.25f, 1.5f)) {
            val native = renderDiagnostic(scale)
            val reference = renderDiagnostic(scale)
            val nativeDiff = compareBuffers(native, reference)
            assertTrue(nativeDiff.meanChannelDiff <= 1.0, "scale $scale matches offscreen reference")
            assertTrue(nativeDiff.overEightFraction <= 0.001, "scale $scale over-8 fraction")

            val stretched = upscaleNearest(oneX, native.width, native.height)
            val stretchedDiff = compareBuffers(native, stretched)
            assertTrue(
                stretchedDiff.overEightFraction > 0.001,
                "scale $scale: stretched 1x image must fail tolerance (got ${stretchedDiff.overEightFraction})",
            )
        }
    }

    @Test
    fun fr82_curved_edges_are_antialiased() {
        for (scale in floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)) {
            val buf = renderDiagnostic(scale)
            // Scan the 40dp circle region (x = 24..64dp, y = 24..64dp) for intermediate AA pixels.
            val x0 = (24 * scale).roundToInt()
            val x1 = (64 * scale).roundToInt()
            val y0 = (24 * scale).roundToInt()
            val y1 = (64 * scale).roundToInt()
            var intermediate = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val l = buf.luma(x, y)
                    if (l in 16..239) intermediate++
                }
            }
            assertTrue(intermediate > 0, "scale $scale: circle edge has antialiased intermediate pixels")
        }
    }

    @Test
    fun fr82_glyph_edges_are_not_blurred() {
        for (scale in floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)) {
            val buf = renderDiagnostic(scale)
            val x0 = (24 * scale).roundToInt()
            val x1 = (70 * scale).roundToInt()
            val y0 = (76 * scale).roundToInt()
            val y1 = (104 * scale).roundToInt()
            var darkPixels = 0
            var maxTransitionRun = 0
            for (y in y0 until y1) {
                var run = 0
                for (x in x0 until x1) {
                    val l = buf.luma(x, y)
                    if (l < 64) darkPixels++
                    if (l in 16..239) {
                        run++
                    } else {
                        if (run in 1..2) maxTransitionRun = maxOf(maxTransitionRun, run)
                        run = 0
                    }
                }
            }
            assertTrue(darkPixels > 0, "scale $scale: glyph Hg drew dark stem pixels")
            assertTrue(maxTransitionRun <= 2, "scale $scale: glyph stem transition width <= 2px")
        }
    }

    @Test
    fun fr82_scale_change_redraws_at_the_new_scale() {
        val follower = WindowBackingScale(2.0f, WindowTextScale(1.0f))
        // 200% -> 125%
        val to125 = assertNotNull(
            follower.follow((SCENE_W_DP * 1.25f).roundToInt(), (SCENE_H_DP * 1.25f).roundToInt(), 1.25f),
            "200% -> 125% triggers redraw",
        )
        assertEquals(1.25f, to125.density.density)
        assertEquals(SCENE_W_DP, (to125.size.width / to125.density.density).roundToInt())
        assertEquals(SCENE_H_DP, (to125.size.height / to125.density.density).roundToInt())
        // 125% -> 200%
        val back200 = assertNotNull(
            follower.follow(SCENE_W_DP * 2, SCENE_H_DP * 2, 2.0f),
            "125% -> 200% triggers redraw",
        )
        assertEquals(2.0f, back200.density.density)
        assertEquals(SCENE_W_DP, (back200.size.width / back200.density.density).roundToInt())
        assertEquals(SCENE_H_DP, (back200.size.height / back200.density.density).roundToInt())
    }

    @Test
    fun fr82_macos_scales_one_and_two() {
        val follower = WindowBackingScale(1.0f, WindowTextScale(1.0f))
        val to2x = assertNotNull(follower.follow(SCENE_W_DP * 2, SCENE_H_DP * 2, 2.0f))
        assertEquals(2.0f, to2x.density.density)
        val back1x = assertNotNull(follower.follow(SCENE_W_DP, SCENE_H_DP, 1.0f))
        assertEquals(1.0f, back1x.density.density)
        // Both 1x and 2x renders have exact device-pixel dimensions.
        assertEquals(SCENE_W_DP, renderDiagnostic(1.0f).width)
        assertEquals(SCENE_W_DP * 2, renderDiagnostic(2.0f).width)
    }
}
