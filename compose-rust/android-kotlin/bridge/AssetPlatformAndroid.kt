package dev.darkpyonix.composerust.ui.node

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Reading pictures on Android, which draws through its own graphics stack rather than
 * through Skia directly.
 *
 * That stack has no SVG reader, so a vector is read by the renderer's own [SvgDocument],
 * which follows the rules Skia's SVG document draws by on the desktop and iOS. The same file
 * then draws the same picture on every platform, rather than an empty box here.
 */
typealias VectorDocument = SvgDocument

internal fun decodeRasterAsset(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

internal fun decodeVectorAsset(bytes: ByteArray): VectorDocument? = SvgDocument.parse(bytes)

/**
 * Reads a font file into a family, through a file because that is the only door Android
 * offers.
 *
 * `Typeface.createFromFile` is the platform's loader below API 26 and the byte buffer
 * overload is not available to every device this runs on, so the bytes go to the cache
 * directory once, at registration. The file is deleted as soon as the face is built: the
 * typeface holds the parsed font, not the path.
 */
internal fun decodeFontAsset(bytes: ByteArray): Asset.Font? {
    val file = File.createTempFile("dxc-font", ".ttf")
    return try {
        file.writeBytes(bytes)
        val typeface = android.graphics.Typeface.createFromFile(file)
        // The weight a face declares is only readable from API 28. Below it, bold or not is
        // all there is to go on.
        val weight = if (android.os.Build.VERSION.SDK_INT >= 28) {
            typeface.weight
        } else if (typeface.isBold) {
            700
        } else {
            400
        }
        val paint = android.graphics.Paint().apply { this.typeface = typeface }
        Asset.Font(
            family = FontFamily(typeface),
            // No Compose font here: Android builds a family from a typeface, not from
            // faces, so a font stack sets each of its families in its regular face.
            face = Asset.Font.Face(null, weight, typeface.isItalic) { codePoint ->
                paint.hasGlyph(String(Character.toChars(codePoint)))
            },
        )
    } catch (error: RuntimeException) {
        // An unreadable font is the application's mistake and is reported as one.
        null
    } finally {
        file.delete()
    }
}

/**
 * The installed family called [name], or null where this device has none by that name.
 *
 * Android answers an unknown family with its default face rather than with nothing, so a
 * name that comes back as the default, and is not a name for the default, is taken to be
 * missing and the font list moves on to its next candidate.
 */
internal fun systemFontFamily(name: String): FontFamily? {
    val typeface = android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        ?: return null
    val isDefault = typeface == android.graphics.Typeface.DEFAULT ||
        typeface == android.graphics.Typeface.SANS_SERIF
    val namesDefault = name.equals("sans-serif", ignoreCase = true) ||
        name.equals("default", ignoreCase = true)
    if (isDefault && !namesDefault) return null
    return FontFamily(typeface)
}

/**
 * No face here is tracked by its `trak` table. The table is Apple's, Android's own faces
 * (Roboto, Noto) carry none, and Android's text stack does not read it, so text set here
 * already matches the platform's own text.
 */
@Suppress("UNUSED_PARAMETER")
internal fun fontTracking(family: FontFamily?, weight: Int, italic: Boolean): FontTracking? = null

/** Draws the document into the whole of the current drawing area. */
internal fun DrawScope.drawVectorDocument(document: VectorDocument) = drawSvgDocument(document)

/**
 * A radial gradient [radiusX] across and [radiusY] tall around [center]: the circle of
 * [radiusX], squashed vertically about its centre by the shader's local matrix.
 */
internal fun ellipticalRadialShader(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    colors: List<Color>,
    colorStops: List<Float>,
    tileMode: TileMode,
): Shader {
    val shader = RadialGradientShader(center, radiusX, colors, colorStops, tileMode)
    shader.setLocalMatrix(
        android.graphics.Matrix().apply { setScale(1f, radiusY / radiusX, center.x, center.y) },
    )
    return shader
}

/** The size the document says it is in its own units, or null where it says none. */
internal fun vectorIntrinsicSize(document: VectorDocument): Size? = document.intrinsicSize
