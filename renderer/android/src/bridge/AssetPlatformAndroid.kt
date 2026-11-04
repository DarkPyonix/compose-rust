package dev.darkpyonix.composerust.ui.node

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Reading pictures on Android, which draws through its own graphics stack rather than
 * through Skia directly.
 *
 * There is no vector document here. Android's graphics stack has no SVG parser, so an SVG
 * registration is reported as unreadable rather than drawn wrong or silently skipped, and
 * nothing ever constructs this type. Icons are unaffected: a vector icon registers a
 * meaning, and the design system draws the shape.
 */
class VectorDocument private constructor()

internal fun decodeRasterAsset(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

internal fun decodeVectorAsset(bytes: ByteArray): VectorDocument? = null

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

internal fun DrawScope.drawVectorDocument(document: VectorDocument) {
    // Unreachable: nothing produces a VectorDocument on this platform.
}
