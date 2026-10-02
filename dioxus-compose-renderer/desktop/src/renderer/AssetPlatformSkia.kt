package dioxus.compose.ui.node

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import org.jetbrains.skia.svg.SVGDOM

/**
 * Reading pictures where Skia is the graphics stack, which is every target Compose
 * Multiplatform draws itself: the desktop native image and iOS.
 *
 * The shared interpreter knows an asset is a raster or a vector and nothing more. Which
 * decoder turns the bytes into one is the platform's business, and Android's is a
 * different file because Android draws through its own graphics stack instead.
 */
typealias VectorDocument = SVGDOM

internal fun decodeRasterAsset(bytes: ByteArray): ImageBitmap? =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal fun decodeVectorAsset(bytes: ByteArray): VectorDocument? =
    SVGDOM(Data.makeFromBytes(bytes))

/**
 * Reads a font file into a family one type role can be written in.
 *
 * Null where the bytes are not a font Skia can read, which is reported and leaves the
 * role on the system face. A screen in the wrong typeface is a great deal better than no
 * screen, and the report is what tells the application which it got.
 */
internal fun decodeFontAsset(bytes: ByteArray): FontFamily? =
    FontMgr.default.makeFromData(Data.makeFromBytes(bytes))?.let { FontFamily(Typeface(it)) }

/** Draws the document into the whole of the current drawing area. */
internal fun DrawScope.drawVectorDocument(document: VectorDocument) {
    document.setContainerSize(size.width, size.height)
    drawIntoCanvas { canvas -> document.render(canvas.nativeCanvas) }
}
