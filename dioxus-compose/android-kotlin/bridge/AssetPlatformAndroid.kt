package dioxus.compose.ui.node

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
internal fun decodeFontAsset(bytes: ByteArray): FontFamily? {
    val file = File.createTempFile("dxc-font", ".ttf")
    return try {
        file.writeBytes(bytes)
        FontFamily(android.graphics.Typeface.createFromFile(file))
    } catch (error: RuntimeException) {
        // An unreadable font is the application's mistake and is reported as one.
        null
    } finally {
        file.delete()
    }
}

internal fun DrawScope.drawVectorDocument(document: VectorDocument) {
    // Unreachable: nothing produces a VectorDocument on this platform.
}
