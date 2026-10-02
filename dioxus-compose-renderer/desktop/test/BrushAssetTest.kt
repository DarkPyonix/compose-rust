package dioxus.compose.test

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Paint
import dioxus.compose.ui.node.Asset
import dioxus.compose.ui.node.AssetCache
import dioxus.compose.ui.node.decodeBrushAsset
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun linear(vararg stops: Pair<Float, Int>): ByteArray {
        val bytes = ArrayList<Byte>()
        fun u16(value: Int) {
            bytes.add((value and 0xff).toByte())
            bytes.add(((value shr 8) and 0xff).toByte())
        }
        fun i32(value: Int) {
            u16(value and 0xffff)
            u16((value shr 16) and 0xffff)
        }
        u16(KIND_LINEAR)
        u16(stops.size)
        u16(TILE_CLAMP)
        u16(0)
        for (coordinate in floatArrayOf(0.5f, 0f, 0.5f, 1f)) i32(coordinate.toRawBits())
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

    private companion object {
        const val KIND_LINEAR = 1
        const val TILE_CLAMP = 1
        const val BRUSH_KIND = 6
        const val BLUE = 0xff4a90d9.toInt()
        const val WHITE = 0xffffffff.toInt()
    }
}
