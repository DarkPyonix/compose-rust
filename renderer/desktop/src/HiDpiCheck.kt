@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Named outright, as WindowStartReport.kt does: the JVM imports java.lang on its own and
// Kotlin/Native does not, where it is the shim each native renderer carries.
import java.lang.System
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps

// Whether a window draws in device pixels and redraws when the display scale changes, checked
// by the renderer itself against its own window, the way Fr53WindowsCheck.kt checks several
// windows. One copy for every Kotlin/Native desktop renderer and the JVM tests: the scene, the
// pixel measurements and the order of the checks are here, and each platform's file supplies
// the window, its capture from the screen and what the system says the scale is.
//
// `.github/scripts/hidpi-windows-check.sh` and `hidpi-linux-check.sh` start the check at each
// display scale, change the scale while it runs, and read the lines it prints.

/** Setting this runs the check instead of the application: `COMPOSE_RUST_FR82_CHECK=1 hidpi --check`. */
internal const val FR82_CHECK_ENV = "COMPOSE_RUST_FR82_CHECK"

/**
 * How many times the check waits for the display scale to change while it runs, and checks
 * again at the new one. Unset is none: the check runs once at the scale it started at.
 */
internal const val FR82_SCALE_CHANGES_ENV = "COMPOSE_RUST_FR82_SCALE_CHANGES"

/** The title of the check's window, which the Linux check finds it by. */
internal const val HIDPI_TITLE = "compose-rust fr82 check"

/**
 * The marker scene, in dp. Everything sits at whole dp, so at a whole scale every edge of a
 * bar falls on a device pixel boundary, and nothing else is near a bar.
 */
internal object HiDpiScene {
    const val WIDTH_DP = 320
    const val HEIGHT_DP = 240

    /** The vertical bar: 1 dp wide at x = 10 dp, from y = 30 to 230 dp. */
    const val VERTICAL_X = 10
    const val VERTICAL_TOP = 30
    const val VERTICAL_LENGTH = 200

    /** The horizontal bar: 1 dp high at y = 10 dp, from x = 30 to 310 dp. */
    const val HORIZONTAL_Y = 10
    const val HORIZONTAL_LEFT = 30
    const val HORIZONTAL_LENGTH = 280

    /** The circle: 40 dp across, its box at (40, 40) dp. */
    const val CIRCLE_LEFT = 40
    const val CIRCLE_TOP = 40
    const val CIRCLE_SIZE = 40

    /** The rounded clip: 80 by 60 dp at (110, 40) dp, corners of 12 dp. */
    const val CLIP_LEFT = 110
    const val CLIP_TOP = 40
    const val CLIP_WIDTH = 80
    const val CLIP_HEIGHT = 60
    const val CLIP_RADIUS = 12

    /** The text "Hg" at 16 sp, its box at (40, 120) dp. */
    const val TEXT_LEFT = 40
    const val TEXT_TOP = 120
    const val TEXT = "Hg"
    const val TEXT_SP = 16
}

/** White, with black 1 dp bars, a circle, a rounded clip and "Hg": see [HiDpiScene]. */
@Composable
internal fun HiDpiMarkerScene() {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(
            Modifier.offset(HiDpiScene.VERTICAL_X.dp, HiDpiScene.VERTICAL_TOP.dp)
                .size(1.dp, HiDpiScene.VERTICAL_LENGTH.dp)
                .background(Color.Black),
        )
        Box(
            Modifier.offset(HiDpiScene.HORIZONTAL_LEFT.dp, HiDpiScene.HORIZONTAL_Y.dp)
                .size(HiDpiScene.HORIZONTAL_LENGTH.dp, 1.dp)
                .background(Color.Black),
        )
        Box(
            Modifier.offset(HiDpiScene.CIRCLE_LEFT.dp, HiDpiScene.CIRCLE_TOP.dp)
                .size(HiDpiScene.CIRCLE_SIZE.dp)
                .background(Color.Black, CircleShape),
        )
        Box(
            Modifier.offset(HiDpiScene.CLIP_LEFT.dp, HiDpiScene.CLIP_TOP.dp)
                .size(HiDpiScene.CLIP_WIDTH.dp, HiDpiScene.CLIP_HEIGHT.dp)
                .clip(RoundedCornerShape(HiDpiScene.CLIP_RADIUS.dp))
                .background(Color.Black),
        )
        BasicText(
            HiDpiScene.TEXT,
            Modifier.offset(HiDpiScene.TEXT_LEFT.dp, HiDpiScene.TEXT_TOP.dp),
            style = TextStyle(color = Color.Black, fontSize = HiDpiScene.TEXT_SP.sp),
        )
    }
}

/** A picture in device pixels, `0xAARRGGBB` per pixel, rows from the top. */
internal class HiDpiImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "${pixels.size} pixels for $width x $height" }
    }

    fun red(x: Int, y: Int): Int = (pixels[y * width + x] shr 16) and 0xFF

    fun green(x: Int, y: Int): Int = (pixels[y * width + x] shr 8) and 0xFF

    fun blue(x: Int, y: Int): Int = pixels[y * width + x] and 0xFF

    /** The mean of the three channels: 0 is black, 255 is white. */
    fun level(x: Int, y: Int): Int = (red(x, y) + green(x, y) + blue(x, y)) / 3

    /** Black as the criterion counts it: every channel 8 or less. */
    fun isBlack(x: Int, y: Int): Boolean = red(x, y) <= 8 && green(x, y) <= 8 && blue(x, y) <= 8

    /** White as the criterion counts it: every channel 247 or more. */
    fun isWhite(x: Int, y: Int): Boolean = red(x, y) >= 247 && green(x, y) >= 247 && blue(x, y) >= 247

    val size: IntSize get() = IntSize(width, height)

    companion object {
        /** From bytes in blue, green, red, alpha order, [rowBytes] to a row: a Windows DIB, an X image, Skia's BGRA. */
        fun fromBgra(bytes: ByteArray, width: Int, height: Int, rowBytes: Int = width * 4): HiDpiImage {
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val at = y * rowBytes + x * 4
                    val blue = bytes[at].toInt() and 0xFF
                    val green = bytes[at + 1].toInt() and 0xFF
                    val red = bytes[at + 2].toInt() and 0xFF
                    pixels[y * width + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            return HiDpiImage(width, height, pixels)
        }
    }
}

private fun bgra(width: Int, height: Int): ImageInfo =
    ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)

/** What [surface] holds, [width] by [height] from its corner, or null where it cannot be read. */
internal fun readSurface(surface: Surface, width: Int, height: Int): HiDpiImage? {
    val info = bgra(width, height)
    val bitmap = Bitmap()
    try {
        bitmap.allocPixels(info)
        if (!surface.readPixels(bitmap, 0, 0)) return null
        val bytes = bitmap.readPixels(info, width * 4, 0, 0) ?: return null
        return HiDpiImage.fromBgra(bytes, width, height)
    } finally {
        bitmap.close()
    }
}

/** What [canvas] has drawn so far, [width] by [height] from its corner, or null where it cannot be read. */
internal fun readCanvas(canvas: Canvas, width: Int, height: Int): HiDpiImage? {
    val info = bgra(width, height)
    val bitmap = Bitmap()
    try {
        bitmap.allocPixels(info)
        if (!canvas.readPixels(bitmap, 0, 0)) return null
        val bytes = bitmap.readPixels(info, width * 4, 0, 0) ?: return null
        return HiDpiImage.fromBgra(bytes, width, height)
    } finally {
        bitmap.close()
    }
}

/**
 * The marker scene as Compose draws it off the screen at [density], [width] by [height] pixels,
 * with Skia's raster backend: what `ImageComposeScene` would give. The pixel geometry is the
 * window's, so text is drawn with the same antialiasing it gets there.
 */
internal fun renderHiDpiScene(
    density: Density,
    width: Int,
    height: Int,
    geometry: PixelGeometry = PixelGeometry.RGB_H,
): HiDpiImage {
    val surface = Surface.makeRaster(bgra(width, height), width * 4, SurfaceProps(geometry))
    val scene = CanvasLayersComposeScene(
        density = density,
        size = IntSize(width, height),
        coroutineContext = Dispatchers.Unconfined,
    )
    try {
        scene.setContent { HiDpiMarkerScene() }
        scene.render(surface.canvas.asComposeCanvas(), 0L)
        return checkNotNull(readSurface(surface, width, height)) { "the offscreen scene could not be read back" }
    } finally {
        scene.close()
        surface.close()
    }
}

/**
 * [source] stretched to [width] by [height] with bilinear filtering, which is what a window
 * server or a swapchain does to a frame drawn at the wrong size.
 */
internal fun stretched(source: HiDpiImage, width: Int, height: Int): HiDpiImage {
    val pixels = IntArray(width * height)
    val scaleX = source.width.toDouble() / width
    val scaleY = source.height.toDouble() / height
    for (y in 0 until height) {
        val fy = ((y + 0.5) * scaleY - 0.5).coerceIn(0.0, (source.height - 1).toDouble())
        val y0 = floor(fy).toInt()
        val y1 = min(y0 + 1, source.height - 1)
        val ty = fy - y0
        for (x in 0 until width) {
            val fx = ((x + 0.5) * scaleX - 0.5).coerceIn(0.0, (source.width - 1).toDouble())
            val x0 = floor(fx).toInt()
            val x1 = min(x0 + 1, source.width - 1)
            val tx = fx - x0
            val a = source.pixels[y0 * source.width + x0]
            val b = source.pixels[y0 * source.width + x1]
            val c = source.pixels[y1 * source.width + x0]
            val d = source.pixels[y1 * source.width + x1]
            var argb = 0xFF shl 24
            var shift = 16
            while (shift >= 0) {
                val top = channel(a, shift) * (1 - tx) + channel(b, shift) * tx
                val bottom = channel(c, shift) * (1 - tx) + channel(d, shift) * tx
                val value = (top * (1 - ty) + bottom * ty).roundToInt().coerceIn(0, 255)
                argb = argb or (value shl shift)
                shift -= 8
            }
            pixels[y * width + x] = argb
        }
    }
    return HiDpiImage(width, height, pixels)
}

private fun channel(argb: Int, shift: Int): Double = ((argb shr shift) and 0xFF).toDouble()

/**
 * How two pictures of the same size differ: the mean difference per channel, and how many
 * pixels differ by more than 8 levels in some channel.
 */
internal class PixelDifference(val mean: Double, val overEight: Int, val pixels: Int) {
    val fraction: Double get() = if (pixels == 0) 0.0 else overEight.toDouble() / pixels

    /** The same picture: a mean of at most 1 level, and at most 0.1% of the pixels over 8 levels. */
    val matches: Boolean get() = mean <= MAX_MEAN && overEight.toLong() * 1000L <= pixels.toLong()

    override fun toString(): String =
        "mean ${hundredths(mean)}, $overEight of $pixels pixels over 8 levels (${hundredths(fraction * 100)}%)"

    companion object {
        const val MAX_MEAN = 1.0
    }
}

/** How [a] and [b] differ, or null where they are not the same size. */
internal fun difference(a: HiDpiImage, b: HiDpiImage): PixelDifference? {
    if (a.width != b.width || a.height != b.height) return null
    var sum = 0L
    var over = 0
    for (index in a.pixels.indices) {
        val p = a.pixels[index]
        val q = b.pixels[index]
        val red = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
        val green = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        val blue = abs((p and 0xFF) - (q and 0xFF))
        sum += red + green + blue
        if (max(red, max(green, blue)) > 8) over++
    }
    val pixels = a.pixels.size
    return PixelDifference(if (pixels == 0) 0.0 else sum.toDouble() / (pixels * 3L), over, pixels)
}

/**
 * At a whole scale [n], whether both 1 dp bars are exactly [n] device pixels of black with white
 * beside them along their whole length. Null when they are; otherwise where they are not.
 *
 * Each row across the vertical bar, and each column across the horizontal one, is read over
 * the 28 dp next to the bar's edge of the scene, where nothing else is drawn: the pixels that
 * are not white must be exactly the bar's, and all of them black.
 */
internal fun oneDpLineProblem(image: HiDpiImage, n: Int): String? {
    fun px(dp: Int) = dp * n
    val across = px(HiDpiScene.HORIZONTAL_LEFT - 2)
    if (image.width < px(HiDpiScene.WIDTH_DP) || image.height < px(HiDpiScene.HEIGHT_DP)) {
        return "the picture is ${image.width}x${image.height}, smaller than the scene at ${n}x"
    }
    val vertical = px(HiDpiScene.VERTICAL_X) until px(HiDpiScene.VERTICAL_X + 1)
    for (y in px(HiDpiScene.VERTICAL_TOP + 10) until px(HiDpiScene.VERTICAL_TOP + HiDpiScene.VERTICAL_LENGTH - 10)) {
        val marked = (0 until across).filter { !image.isWhite(it, y) }
        if (marked != vertical.toList() || marked.any { !image.isBlack(it, y) }) {
            return "vertical bar, row $y: ${profile((0 until across).map { image.level(it, y) }, vertical)}"
        }
    }
    val horizontal = px(HiDpiScene.HORIZONTAL_Y) until px(HiDpiScene.HORIZONTAL_Y + 1)
    for (x in px(HiDpiScene.HORIZONTAL_LEFT + 10) until px(HiDpiScene.HORIZONTAL_LEFT + HiDpiScene.HORIZONTAL_LENGTH - 10)) {
        val marked = (0 until across).filter { !image.isWhite(x, it) }
        if (marked != horizontal.toList() || marked.any { !image.isBlack(x, it) }) {
            return "horizontal bar, column $x: ${profile((0 until across).map { image.level(x, it) }, horizontal)}"
        }
    }
    return null
}

/** The levels around where a bar should be, for a line that says what was there instead. */
private fun profile(levels: List<Int>, bar: IntRange): String {
    val from = max(0, bar.first - 3)
    val to = min(levels.size - 1, bar.last + 3)
    return "levels at ${from}..$to are ${levels.subList(from, to + 1).joinToString(" ")}, the bar should be ${bar.first}..${bar.last}"
}

/**
 * How wide the antialiased edges of the circle and of the rounded clip's corners are: the
 * pixels with a level between 16 and 239 near each curve, per device pixel of its length. An
 * antialiased edge has about one such pixel along each pixel of length; an aliased edge has
 * none, and a picture stretched by s has about s of them.
 */
internal class CurvedEdgeWidths(val circle: Double, val clip: Double) {
    val passes: Boolean get() = circle in MIN_WIDTH..MAX_WIDTH && clip in MIN_WIDTH..MAX_WIDTH

    override fun toString(): String =
        "edge width ${hundredths(circle)} px on the circle and ${hundredths(clip)} px on the clip's corners " +
            "(antialiased is ${hundredths(MIN_WIDTH)} to ${hundredths(MAX_WIDTH)})"

    companion object {
        const val MIN_WIDTH = 0.5
        const val MAX_WIDTH = 2.0
    }
}

internal fun curvedEdgeWidths(image: HiDpiImage, scale: Float): CurvedEdgeWidths {
    val s = scale.toDouble()
    val radius = HiDpiScene.CIRCLE_SIZE / 2.0 * s
    val circleX = (HiDpiScene.CIRCLE_LEFT + HiDpiScene.CIRCLE_SIZE / 2.0) * s
    val circleY = (HiDpiScene.CIRCLE_TOP + HiDpiScene.CIRCLE_SIZE / 2.0) * s
    val circle = intermediateNear(image, circleX, circleY, radius) { _, _ -> true } / (2 * PI * radius)

    val corner = HiDpiScene.CLIP_RADIUS * s
    val left = HiDpiScene.CLIP_LEFT * s
    val top = HiDpiScene.CLIP_TOP * s
    val right = (HiDpiScene.CLIP_LEFT + HiDpiScene.CLIP_WIDTH) * s
    val bottom = (HiDpiScene.CLIP_TOP + HiDpiScene.CLIP_HEIGHT) * s
    var clipCount = 0
    // Each corner's quarter of a circle, on the outer side of its centre.
    for ((cx, cy) in listOf(left + corner to top + corner, right - corner to top + corner, left + corner to bottom - corner, right - corner to bottom - corner)) {
        val outwardX = if (cx < (left + right) / 2) -1 else 1
        val outwardY = if (cy < (top + bottom) / 2) -1 else 1
        clipCount += intermediateNear(image, cx, cy, corner) { x, y -> (x - cx) * outwardX >= 0 && (y - cy) * outwardY >= 0 }
    }
    val clip = clipCount / (2 * PI * corner)
    return CurvedEdgeWidths(circle, clip)
}

/** How many pixels within 3 px of the circle at ([cx], [cy]) of [radius] have a level from 16 to 239. */
private fun intermediateNear(
    image: HiDpiImage,
    cx: Double,
    cy: Double,
    radius: Double,
    inside: (Double, Double) -> Boolean,
): Int {
    var count = 0
    val reach = radius + 3
    for (y in max(0, (cy - reach).toInt()) until min(image.height, (cy + reach).toInt() + 1)) {
        for (x in max(0, (cx - reach).toInt()) until min(image.width, (cx + reach).toInt() + 1)) {
            val px = x + 0.5
            val py = y + 0.5
            if (!inside(px, py)) continue
            val distance = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
            if (abs(distance - radius) > 3) continue
            if (image.level(x, y) in 16..239) count++
        }
    }
    return count
}

/**
 * How wide the left edge of the vertical strokes of "Hg" is, in device pixels: the most pixels
 * found between the last white one and the stroke's darkest, over the rows of the "H" above the
 * "g". Null where no text was found where the scene puts it.
 */
internal fun glyphEdgeWidth(image: HiDpiImage, scale: Float): Int? {
    val s = scale.toDouble()
    val left = (HiDpiScene.TEXT_LEFT - 4) * s
    val right = min(image.width.toDouble(), (HiDpiScene.TEXT_LEFT + 60) * s)
    val top = (HiDpiScene.TEXT_TOP - 4) * s
    val bottom = min(image.height.toDouble(), (HiDpiScene.TEXT_TOP + 40) * s)
    val xs = left.toInt() until right.toInt()
    var inkTop = -1
    var inkBottom = -1
    for (y in top.toInt() until bottom.toInt()) {
        if (xs.any { image.level(it, y) < 128 }) {
            if (inkTop < 0) inkTop = y
            inkBottom = y
        }
    }
    if (inkTop < 0) return null
    val inkHeight = inkBottom - inkTop + 1
    // The "H" alone: the top of the ink is its cap height, and the "g" starts at the x-height,
    // about a fifth of the ink's height lower.
    val first = inkTop + max(1, (inkHeight * 0.04).roundToInt())
    val last = max(first, inkTop + (inkHeight * 0.18).roundToInt())
    val reach = 4 * ceil(s).toInt() + 2
    var widest = 0
    for (y in first..last) {
        val start = xs.firstOrNull { image.level(it, y) < 240 } ?: continue
        val span = start until min(xs.last + 1, start + reach)
        val darkest = span.minOf { image.level(it, y) }
        val dark = span.first { image.level(it, y) <= darkest + 16 }
        widest = max(widest, dark - start)
    }
    return widest
}

/** The most device pixels a glyph edge may take to go from white to the stroke. */
internal const val MAX_GLYPH_EDGE = 2

/** One check's answer at one scale. */
internal class HiDpiVerdict(val name: String, val passed: Boolean, val detail: String)

/**
 * The window the check runs in, as each platform has it. Everything is read from the system
 * or from the frame the window last drew, not from what the check asked for.
 */
internal interface HiDpiWindow {
    /** One turn of the window's own loop: what it heard, and a frame when there is a reason to draw one. */
    fun step()

    /** Device pixels per dp as the system says now: Windows' DPI of the window over 96, X11's `Xft.dpi` over 96. */
    fun systemScale(): Float

    /** The client area in device pixels, as the system has it now. */
    fun clientSize(): IntSize

    /** How many frames the window has drawn. */
    val frames: Int

    /** The size in pixels of the surface the last frame was drawn into. */
    val surfaceSize: IntSize

    /** The pixels of the last frame, as drawn. */
    val lastFrame: HiDpiImage?

    /** The density the scene is drawn at. */
    val density: Density

    /** How many times the scene's density has changed since the window opened. */
    val densityChanges: Int

    /** The client area as it is on the screen now, or null where it could not be read. */
    fun capture(): HiDpiImage?

    /** The pixel geometry the window's surface draws text with. */
    val geometry: PixelGeometry

    /** Checks only this platform has, at [scale]. */
    fun platformChecks(scale: Float): List<HiDpiVerdict> = emptyList()
}

/** How many scale changes the check was asked to wait for. */
internal fun fr82ScaleChanges(): Int = System.getenv(FR82_SCALE_CHANGES_ENV)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

/**
 * Runs the checks in [window] at the scale it opened at, then waits for [changes] scale
 * changes and checks again at each new scale. Each result is a line on standard error,
 * `compose-rust: <name> scale=<s>: ok <detail>` or `... FAILED <detail>`, and the last line says
 * whether all of them passed. True when they did.
 */
internal fun runHiDpiChecks(window: HiDpiWindow, changes: Int): Boolean {
    var passed = true
    val first = window.systemScale()
    if (!window.runUntil(SETTLE_MILLIS) { fitted(window, first) }) {
        report(HiDpiVerdict("fr82_surface_is_device_pixels_at_every_scale", false, "no frame fitted the window within $SETTLE_MILLIS ms: ${describe(window)}"), first)
        System.err.println("compose-rust: fr82 checks: FAILED")
        return false
    }
    window.runFor(SETTLED_MILLIS)
    val opened = dpSize(window.clientSize(), first)
    passed = phase(window, first) && passed
    var scale = first
    repeat(changes) {
        val framesBefore = window.frames
        val densityChangesBefore = window.densityChanges
        System.err.println("compose-rust: fr82 waiting for a scale change from ${two(scale)}")
        if (!window.runUntil(CHANGE_WAIT_MILLIS) { window.systemScale() != scale }) {
            report(HiDpiVerdict("fr82_scale_change_redraws_at_the_new_scale", false, "the scale stayed ${two(scale)} for $CHANGE_WAIT_MILLIS ms"), scale)
            System.err.println("compose-rust: fr82 checks: FAILED")
            return false
        }
        passed = fr82_scale_change_redraws_at_the_new_scale(window, scale, opened, framesBefore, densityChangesBefore) && passed
        scale = window.systemScale()
        passed = phase(window, scale) && passed
    }
    System.err.println("compose-rust: fr82 checks: ${if (passed) "ok" else "FAILED"}")
    return passed
}

/** Every check that applies at [scale], on the frame on the screen now. */
private fun phase(window: HiDpiWindow, scale: Float): Boolean {
    val capture = window.capture()
    val client = window.clientSize()
    System.err.println(
        "compose-rust: fr82 phase scale=${two(scale)} ${describe(window)} " +
            "capture=${capture?.let { "${it.width}x${it.height}" } ?: "none"}",
    )
    val verdicts = mutableListOf(
        fr82_surface_is_device_pixels_at_every_scale(window, scale, client),
        fr82_screen_matches_the_drawn_frame(window.lastFrame, capture, client),
    )
    val whole = scale.roundToInt()
    if (abs(scale - whole) < SCALE_EPSILON && whole >= 1) {
        verdicts += fr82_one_dp_line_is_whole_device_pixels_at_integer_scales(capture, whole)
    } else {
        verdicts += fr82_fractional_scale_matches_offscreen_compose(window, capture, scale, client)
    }
    verdicts += fr82_curved_edges_are_antialiased(capture, scale)
    verdicts += fr82_glyph_edges_are_not_blurred(capture, scale)
    verdicts += window.platformChecks(scale)
    var passed = true
    for (verdict in verdicts) {
        report(verdict, scale)
        passed = passed && verdict.passed
    }
    return passed
}

/** The surface is the client area in device pixels, and the scene's density is the scale. */
@Suppress("FunctionName")
private fun fr82_surface_is_device_pixels_at_every_scale(window: HiDpiWindow, scale: Float, client: IntSize): HiDpiVerdict {
    val surface = window.surfaceSize
    val density = window.density.density
    val passed = surface == client && abs(density - scale) < SCALE_EPSILON
    return HiDpiVerdict(
        "fr82_surface_is_device_pixels_at_every_scale",
        passed,
        "surface ${surface.width}x${surface.height}, client ${client.width}x${client.height}, " +
            "scene density ${two(density)}, system scale ${two(scale)}",
    )
}

/** What the screen shows of the client area is the frame the window drew, pixel for pixel. */
@Suppress("FunctionName")
private fun fr82_screen_matches_the_drawn_frame(frame: HiDpiImage?, capture: HiDpiImage?, client: IntSize): HiDpiVerdict {
    val name = "fr82_screen_matches_the_drawn_frame"
    if (capture == null) return HiDpiVerdict(name, false, "the screen could not be read")
    if (frame == null) return HiDpiVerdict(name, false, "the drawn frame could not be read back")
    if (capture.size != client) {
        return HiDpiVerdict(name, false, "the capture is ${capture.width}x${capture.height}, the client ${client.width}x${client.height}")
    }
    val diff = difference(capture, frame)
        ?: return HiDpiVerdict(name, false, "the capture is ${capture.width}x${capture.height}, the frame ${frame.width}x${frame.height}")
    return HiDpiVerdict(name, diff.matches, "screen against the drawn frame: $diff")
}

/** At s = 1 and 2, each 1 dp bar is exactly s black device pixels with white beside it. */
@Suppress("FunctionName")
private fun fr82_one_dp_line_is_whole_device_pixels_at_integer_scales(capture: HiDpiImage?, n: Int): HiDpiVerdict {
    val name = "fr82_one_dp_line_is_whole_device_pixels_at_integer_scales"
    if (capture == null) return HiDpiVerdict(name, false, "the screen could not be read")
    val problem = oneDpLineProblem(capture, n)
    return HiDpiVerdict(
        name,
        problem == null,
        problem ?: "both bars are $n black pixels wide with white beside them along their length",
    )
}

/**
 * At a fractional scale the screen is what Compose draws off the screen at that density, and
 * the same comparison against the scene drawn at 1x and stretched fails, which is what shows
 * the comparison can see a stretch.
 */
@Suppress("FunctionName")
private fun fr82_fractional_scale_matches_offscreen_compose(
    window: HiDpiWindow,
    capture: HiDpiImage?,
    scale: Float,
    client: IntSize,
): HiDpiVerdict {
    val name = "fr82_fractional_scale_matches_offscreen_compose"
    if (capture == null) return HiDpiVerdict(name, false, "the screen could not be read")
    val density = window.density
    val reference = renderHiDpiScene(density, client.width, client.height, window.geometry)
    val dp = dpSize(client, scale)
    val oneX = renderHiDpiScene(Density(1f, density.fontScale), dp.width, dp.height, window.geometry)
    val stretch = stretched(oneX, client.width, client.height)
    val against = difference(capture, reference)
        ?: return HiDpiVerdict(name, false, "the capture is ${capture.width}x${capture.height}, the client ${client.width}x${client.height}")
    val againstStretch = difference(capture, stretch)!!
    // Not judged, said: whether a difference from the reference is the screen's or the drawing's.
    val drawn = window.lastFrame?.let { difference(it, reference) }
    return HiDpiVerdict(
        name,
        against.matches && !againstStretch.matches,
        "screen against Compose at ${two(density.density)}: $against; " +
            "against 1x stretched (must differ): $againstStretch" +
            (drawn?.let { "; drawn frame against Compose: $it" } ?: ""),
    )
}

/** The circle and the clip's corners have antialiased edges one to two pixels wide. */
@Suppress("FunctionName")
private fun fr82_curved_edges_are_antialiased(capture: HiDpiImage?, scale: Float): HiDpiVerdict {
    val name = "fr82_curved_edges_are_antialiased"
    if (capture == null) return HiDpiVerdict(name, false, "the screen could not be read")
    val widths = curvedEdgeWidths(capture, scale)
    return HiDpiVerdict(name, widths.passes, widths.toString())
}

/** The vertical strokes of "Hg" go from white to the stroke in at most two pixels. */
@Suppress("FunctionName")
private fun fr82_glyph_edges_are_not_blurred(capture: HiDpiImage?, scale: Float): HiDpiVerdict {
    val name = "fr82_glyph_edges_are_not_blurred"
    if (capture == null) return HiDpiVerdict(name, false, "the screen could not be read")
    val width = glyphEdgeWidth(capture, scale)
        ?: return HiDpiVerdict(name, false, "no text where the scene draws \"${HiDpiScene.TEXT}\"")
    return HiDpiVerdict(name, width <= MAX_GLYPH_EDGE, "glyph edge $width px (at most $MAX_GLYPH_EDGE)")
}

/**
 * After the scale changed with no input, a frame at the new scale is drawn within two
 * seconds, at the window's old size in dp, and the scene's density changed once.
 */
@Suppress("FunctionName")
private fun fr82_scale_change_redraws_at_the_new_scale(
    window: HiDpiWindow,
    from: Float,
    opened: IntSize,
    framesBefore: Int,
    densityChangesBefore: Int,
): Boolean {
    val now = window.systemScale()
    val heard = TimeSource.Monotonic.markNow()
    val redrawn = window.runUntil(REDRAW_MILLIS) { window.frames > framesBefore && fitted(window, now) }
    val took = heard.elapsedNow().inWholeMilliseconds
    window.runFor(SETTLED_MILLIS)
    val dp = dpSize(window.clientSize(), now)
    val sameSize = abs(dp.width - opened.width) <= 1 && abs(dp.height - opened.height) <= 1
    val densityChanges = window.densityChanges - densityChangesBefore
    val verdict = HiDpiVerdict(
        "fr82_scale_change_redraws_at_the_new_scale",
        redrawn && sameSize && densityChanges == 1,
        "from ${two(from)} to ${two(now)}: " +
            (if (redrawn) "redrawn at the new scale $took ms after the change was seen" else "no frame fitted the new scale within $REDRAW_MILLIS ms") +
            "; ${describe(window)}; the window is ${dp.width}x${dp.height} dp (it opened at ${opened.width}x${opened.height}); " +
            "the scene's density changed $densityChanges times (once is right)",
    )
    report(verdict, now)
    return verdict.passed
}

private fun fitted(window: HiDpiWindow, scale: Float): Boolean =
    window.frames > 0 && window.surfaceSize == window.clientSize() && abs(window.density.density - scale) < SCALE_EPSILON

private fun describe(window: HiDpiWindow): String {
    val client = window.clientSize()
    return "client=${client.width}x${client.height} surface=${window.surfaceSize.width}x${window.surfaceSize.height} " +
        "density=${two(window.density.density)} frames=${window.frames}"
}

private fun dpSize(pixels: IntSize, scale: Float): IntSize =
    IntSize((pixels.width / scale).roundToInt(), (pixels.height / scale).roundToInt())

private fun report(verdict: HiDpiVerdict, scale: Float) {
    System.err.println(
        "compose-rust: ${verdict.name} scale=${two(scale)}: ${if (verdict.passed) "ok" else "FAILED"} ${verdict.detail}",
    )
}

/** Runs the window's loop until [condition] holds. False when [timeoutMillis] passed first. */
private fun HiDpiWindow.runUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
        if (start.elapsedNow().inWholeMilliseconds > timeoutMillis) return false
        step()
    }
    return true
}

private fun HiDpiWindow.runFor(millis: Long) {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow().inWholeMilliseconds < millis) step()
}

/** A scale with two decimals, the way the scripts look for it: 1.00, 1.25, 2.00. */
internal fun two(value: Float): String = hundredths(value.toDouble())

private fun hundredths(value: Double): String {
    val scaled = (value * 100).roundToInt()
    val sign = if (scaled < 0) "-" else ""
    val magnitude = abs(scaled)
    return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
}

private const val SCALE_EPSILON = 0.001f
private const val SETTLE_MILLIS = 10_000L
private const val SETTLED_MILLIS = 500L
private const val REDRAW_MILLIS = 2_000L
private const val CHANGE_WAIT_MILLIS = 120_000L
