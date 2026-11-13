package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.ui.node.Asset
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.decodeBrushAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A gradient, registered the way a picture is and named by id after that.
 *
 * The bytes are this project's own layout rather than a file format, because no platform
 * stores a list of stops as a document and a format would be a parser to keep.
 */
class BrushAssetTest {

    private fun linear(vararg stops: Pair<Float, Int>): ByteArray =
        brushBytes(KIND_LINEAR, floatArrayOf(0.5f, 0f, 0.5f, 1f), *stops)

    /** A radial brush about the middle, [radius] across and [radiusY] tall. */
    private fun radial(radius: Float, radiusY: Float, vararg stops: Pair<Float, Int>): ByteArray =
        brushBytes(KIND_RADIAL, floatArrayOf(0.5f, 0.5f, radius, radiusY), *stops)

    /** Fills a square [side] pixels wide with [brush] and reads back what was drawn. */
    private fun painted(brush: Brush, side: Int = 100): PixelMap {
        val bitmap = ImageBitmap(side, side)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(side.toFloat(), side.toFloat()),
        ) { drawRect(brush) }
        return bitmap.toPixelMap()
    }

    private fun PixelMap.rows(): List<List<Color>> =
        (0 until height).map { y -> (0 until width).map { x -> this[x, y] } }

    private fun brushBytes(kind: Int, coordinates: FloatArray, vararg stops: Pair<Float, Int>): ByteArray {
        val bytes = ArrayList<Byte>()
        fun u16(value: Int) {
            bytes.add((value and 0xff).toByte())
            bytes.add(((value shr 8) and 0xff).toByte())
        }
        fun i32(value: Int) {
            u16(value and 0xffff)
            u16((value shr 16) and 0xffff)
        }
        u16(kind)
        u16(stops.size)
        u16(TILE_CLAMP)
        u16(0)
        for (coordinate in coordinates) i32(coordinate.toRawBits())
        for ((at, argb) in stops) {
            i32(at.toRawBits())
            i32(argb)
        }
        return bytes.toByteArray()
    }

    @Test
    fun fr23_a_gradient_is_read_into_a_brush() {
        val brush = decodeBrushAsset(linear(0f to BLUE, 1f to WHITE)) { null }
        assertNotNull(brush)
        assertTrue(brush is ShaderBrush, "a gradient came back as something else")
    }

    @Test
    fun fr23_a_gradient_is_measured_in_fractions_of_what_it_fills() {
        // The same brush over two different areas has to produce two different shaders.
        // A gradient described in pixels is a gradient that looks right at one size.
        val brush = decodeBrushAsset(linear(0f to BLUE, 1f to WHITE)) { null } as ShaderBrush
        val small = brush.createShader(Size(100f, 50f))
        val large = brush.createShader(Size(100f, 400f))
        assertTrue(small !== large)
    }

    @Test
    fun fr23_bytes_that_are_not_a_brush_are_not_read_as_one() {
        assertNull(decodeBrushAsset(ByteArray(3)) { null })
        // A stop count that disagrees with the length is a truncated registration.
        val truncated = linear(0f to BLUE, 1f to WHITE).copyOfRange(0, 28)
        assertNull(decodeBrushAsset(truncated) { null })
        // A gradient with no stops is not a fill.
        assertNull(decodeBrushAsset(linear()) { null })
    }

    @Test
    fun fr23_an_unregistered_brush_is_reported_rather_than_guessed() {
        val theme = resolveTheme(
            theme = null,
            platform = HostPlatform.Unknown,
            systemDark = false,
        )
        assertNull(theme.brush(Paint.Asset(7)))
        // A flat colour still answers, so a screen that never asked for a brush is
        // unaffected by any of this.
        assertEquals(
            SolidColor(theme.color(ColorRole.Primary)),
            theme.brush(Paint.Role(ColorRole.Primary)),
        )
    }

    @Test
    fun fr23_a_registered_brush_is_kept_and_found_by_id() {
        val cache = AssetCache()
        assertNull(cache.register(3, BRUSH_KIND, linear(0f to BLUE, 1f to WHITE)))
        assertTrue(cache.asset(3) is Asset.Brush)
    }

    /**
     * The fourth coordinate of a radial brush is its vertical radius. Half the radius tall,
     * the brush has run out to its last stop a quarter of the way down from the top edge of a
     * square, where a circle of the same radius is still between its stops; across the
     * middle the two agree. Zero is a circle, the very one Compose draws for the same centre,
     * radius and stops.
     */
    @Test
    fun fr23_elliptical_radial_gradient_uses_radius_y() {
        val ellipse = painted(decodeBrushAsset(radial(0.5f, 0.25f, 0f to RED, 1f to BLUE)) { null }!!)
        val circle = painted(decodeBrushAsset(radial(0.5f, 0f, 0f to RED, 1f to BLUE)) { null }!!)

        // 30 pixels above the centre: past the ellipse's 25 pixel vertical radius, and 0.6 of
        // the way out along the circle's 50.
        assertEquals(Color(BLUE), ellipse[50, 20], "the ellipse has reached its last stop above the centre")
        assertNotEquals(Color(BLUE), circle[50, 20], "a circle of the same radius is still blending there")
        // 30 pixels right of the centre, the horizontal radius is the same for both.
        assertEquals(circle[80, 50], ellipse[80, 50], "the horizontal radius is the brush's radius")

        val compose = painted(
            Brush.radialGradient(
                0f to Color(RED),
                1f to Color(BLUE),
                center = Offset(50f, 50f),
                radius = 50f,
            ),
        )
        assertEquals(compose.rows(), circle.rows(), "a vertical radius of zero is the circle it always was")
    }

    /** A vertical radius that is negative or not a number is reported, and drawn as a circle. */
    @Test
    fun fr23_a_negative_or_nan_radius_y_is_reported_and_drawn_as_a_circle() {
        val circle = painted(decodeBrushAsset(radial(0.5f, 0f, 0f to RED, 1f to BLUE)) { null }!!)
        for (radiusY in floatArrayOf(-0.25f, Float.NaN)) {
            val cache = AssetCache()
            val error = cache.register(4, BRUSH_KIND, radial(0.5f, radiusY, 0f to RED, 1f to BLUE))
            assertNotNull(error, "a vertical radius of $radiusY must be reported")
            val kept = cache.asset(4) as? Asset.Brush
            assertNotNull(kept, "the brush is still drawn, as a circle")
            assertEquals(circle.rows(), painted(kept.brush).rows(), "$radiusY draws as the circle")
        }
    }

    private companion object {
        const val KIND_LINEAR = 1
        const val KIND_RADIAL = 2
        const val RED = 0xffff0000.toInt()
        const val TILE_CLAMP = 1
        const val BRUSH_KIND = 6
        const val BLUE = 0xff4a90d9.toInt()
        const val WHITE = 0xffffffff.toInt()
    }
}
