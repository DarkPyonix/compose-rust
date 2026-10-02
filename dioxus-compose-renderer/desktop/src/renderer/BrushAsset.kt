package dioxus.compose.ui.node

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode

// The brush layout, which is this project's own and not a file format. A kind, a stop
// count, a tile mode, two bytes of padding, four coordinates, and then one eight byte
// record per stop. Nothing is skipped for a brush that does not use a coordinate, so the
// stops start at the same place whatever the kind is and a short buffer cannot walk a
// reader off the end.

private const val HEADER_LENGTH = 24
private const val STOP_LENGTH = 8

private const val KIND_LINEAR = 1
private const val KIND_RADIAL = 2
private const val KIND_IMAGE = 3

/**
 * A brush read from a registration, or null where the bytes are not one.
 *
 * [imageOf] answers what a picture id was registered as, because an image brush names one
 * that was registered separately: the picture is a picture in its own right and may also
 * be drawn as one.
 */
internal fun decodeBrushAsset(bytes: ByteArray, imageOf: (Int) -> Asset?): Brush? {
    if (bytes.size < HEADER_LENGTH) return null
    val kind = bytes.u16(0)
    val stopCount = bytes.u16(2)
    if (bytes.size != HEADER_LENGTH + stopCount * STOP_LENGTH) return null
    val tile = tileMode(bytes.u16(4)) ?: return null
    val coordinates = FloatArray(4) { Float.fromBits(bytes.i32(8 + 4 * it)) }

    if (kind == KIND_IMAGE) {
        // The picture's id rides in the first coordinate's bits, which is where the Host
        // put it rather than giving the image case a second layout to read.
        val picture = imageOf(coordinates[0].toRawBits()) as? Asset.Raster ?: return null
        return ShaderBrush(ImageShader(picture.bitmap, tile, tile))
    }

    val stops = Array(stopCount) { index ->
        val at = Float.fromBits(bytes.i32(HEADER_LENGTH + index * STOP_LENGTH))
        val argb = bytes.i32(HEADER_LENGTH + index * STOP_LENGTH + 4)
        at to Color(argb)
    }
    // A gradient with nothing in it is not a fill. Reported rather than drawn as black.
    if (stops.isEmpty()) return null

    return when (kind) {
        KIND_LINEAR -> FractionalGradient(stops, coordinates, tile, radial = false)
        KIND_RADIAL -> FractionalGradient(stops, coordinates, tile, radial = true)
        else -> null
    }
}

// Read by hand rather than through a byte buffer: this file is compiled for the browser
// and for Kotlin/Native as well, and neither has one.
private fun ByteArray.u16(at: Int): Int =
    (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

private fun ByteArray.i32(at: Int): Int =
    (this[at].toInt() and 0xff) or
        ((this[at + 1].toInt() and 0xff) shl 8) or
        ((this[at + 2].toInt() and 0xff) shl 16) or
        ((this[at + 3].toInt() and 0xff) shl 24)

private fun tileMode(tag: Int): TileMode? = when (tag) {
    1 -> TileMode.Clamp
    2 -> TileMode.Repeated
    3 -> TileMode.Mirror
    else -> null
}

/**
 * A gradient described in fractions of whatever it ends up filling.
 *
 * Fractions and not pixels, because nothing on the Host's side knows how large anything
 * ended up, and a gradient given in pixels is a gradient that looks right at one size. A
 * ShaderBrush is handed the measured area, which is the one place the two meet.
 */
private class FractionalGradient(
    private val stops: Array<Pair<Float, Color>>,
    private val coordinates: FloatArray,
    private val tile: TileMode,
    private val radial: Boolean,
) : ShaderBrush() {

    override fun createShader(size: Size): Shader = if (radial) {
        androidx.compose.ui.graphics.RadialGradientShader(
            center = Offset(coordinates[0] * size.width, coordinates[1] * size.height),
            // Of the larger side, so a circle stays a circle in a wide surface.
            radius = coordinates[2] * maxOf(size.width, size.height),
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
            tileMode = tile,
        )
    } else {
        androidx.compose.ui.graphics.LinearGradientShader(
            from = Offset(coordinates[0] * size.width, coordinates[1] * size.height),
            to = Offset(coordinates[2] * size.width, coordinates[3] * size.height),
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
            tileMode = tile,
        )
    }
}
