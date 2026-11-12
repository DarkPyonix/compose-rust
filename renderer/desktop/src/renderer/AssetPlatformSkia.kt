package dev.darkpyonix.composerust.ui.node

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontWidth
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
 *
 * The face is handed to Compose as a font with an identity of its own, made from its
 * bytes, and the weight and slant the file declares. Compose registers a face under its
 * identity, so two weights of one typeface stay two faces: named by the family name the
 * file gives, the second weight registered would have drawn as the first.
 */
internal fun decodeFontAsset(bytes: ByteArray): Asset.Font? {
    val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return null
    val declared = typeface.fontStyle
    val weight = declared.weight.coerceIn(1, 1000)
    val italic = declared.slant != FontSlant.UPRIGHT
    val font = Font(
        identity = "compose-rust-font-${bytes.size}-${bytes.contentHashCode()}",
        getData = { bytes },
        weight = FontWeight(weight),
        style = if (italic) FontStyle.Italic else FontStyle.Normal,
    )
    return Asset.Font(
        family = FontFamily(font),
        face = Asset.Font.Face(font, weight, italic) { codePoint -> typeface.getUTF32Glyph(codePoint) != 0.toShort() },
    )
}

/**
 * The installed family called [name], or null where this machine has none by that name.
 *
 * Asked of the font manager by name, and the face it answers is asked what it actually is:
 * a manager asked for a name it does not have can answer with a substitute, and a font
 * list has to move on to its next candidate rather than take that substitute for the one
 * it named.
 */
internal fun systemFontFamily(name: String): FontFamily? {
    val face = FontMgr.default.matchFamilyStyle(name, org.jetbrains.skia.FontStyle.NORMAL) ?: return null
    if (!face.familyName.equals(name, ignoreCase = true)) return null
    return FontFamily(Typeface(face))
}

/**
 * The `trak` tracking of the installed face [family] sets text in at [weight] and slant,
 * or null where it has none, which is every face but Apple's own.
 *
 * Only a family of installed faces named by identity is read (the platform's interface
 * family, and the installed families a font list names): the face is the one Compose
 * itself loads for that identity, weight and slant, asked of the same font manager, so
 * the table read is the table of the face the letters are drawn in. A family made from
 * font bytes or from the toolkit's generic names is not tracked.
 *
 * Kept per identity, weight and slant: the machine's fonts do not change while the
 * application runs, and text is resolved every time it is measured or drawn.
 */
@OptIn(ExperimentalTextApi::class)
internal fun fontTracking(family: FontFamily?, weight: Int, italic: Boolean): FontTracking? {
    val fonts = (family as? FontListFontFamily)?.fonts ?: return null
    var identity: String? = null
    for (font in fonts) {
        if (font is SystemFont) {
            identity = font.identity
            break
        }
    }
    if (identity == null) return null
    val key = TrackingKey(identity, weight, italic)
    return trackings.getOrPut(key) { KnownTracking(readTracking(identity, weight, italic)) }.tracking
}

private data class TrackingKey(val identity: String, val weight: Int, val italic: Boolean)

private class KnownTracking(val tracking: FontTracking?)

private val trackings = HashMap<TrackingKey, KnownTracking>()

private fun readTracking(identity: String, weight: Int, italic: Boolean): FontTracking? {
    val style = org.jetbrains.skia.FontStyle(
        weight.coerceIn(1, 1000),
        FontWidth.NORMAL,
        if (italic) FontSlant.ITALIC else FontSlant.UPRIGHT,
    )
    val face = FontMgr.default.matchFamilyStyle(identity, style) ?: return null
    val bytes = face.getTableData("trak")?.bytes ?: return null
    val table = TrackTable.parse(bytes, face.unitsPerEm) ?: return null
    return FontTracking(table) { codePoint -> face.getUTF32Glyph(codePoint) != 0.toShort() }
}

/** Draws the document into the whole of the current drawing area. */
internal fun DrawScope.drawVectorDocument(document: VectorDocument) {
    document.setContainerSize(size.width, size.height)
    drawIntoCanvas { canvas -> document.render(canvas.nativeCanvas) }
}
