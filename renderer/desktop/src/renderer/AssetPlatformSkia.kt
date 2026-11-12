package dev.darkpyonix.composerust.ui.node

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
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
 * The installed family called [name], with every face of it the machine has, or null where
 * this machine has none by that name.
 *
 * The font manager is asked for the family by name, and each face it answers is asked what
 * it actually is: a manager asked for a name it does not have can answer with a substitute,
 * and a font list has to move on to its next candidate rather than take that substitute for
 * the one it named.
 *
 * Every face of the family becomes one font of it, at the weight and slant the face itself
 * declares. Text in bold or italic is then set in the family's own bold or italic, the way a
 * browser sets it, and where the family has no bold, Compose sees that it has none and draws
 * one, rather than taking the regular face labelled bold at its word.
 *
 * The faces are listed rather than asked for one weight at a time. On macOS, asking for a
 * weight that one of the system's own families does not have (SF Mono has nothing under
 * 300) answers nothing, and from then on that family answers nothing for any weight, so
 * the face Compose later loads by name would be Helvetica.
 *
 * Kept per name: the machine's fonts do not change while the application runs, and the
 * same family object each time is what lets Compose reuse a layout it has already made.
 */
internal fun systemFontFamily(name: String): FontFamily? =
    installedFamilies.getOrPut(name) { InstalledFamily(buildSystemFontFamily(name)) }.family

private class InstalledFamily(val family: FontFamily?)

private val installedFamilies = HashMap<String, InstalledFamily>()

@OptIn(ExperimentalTextApi::class)
private fun buildSystemFontFamily(name: String): FontFamily? {
    val set = FontMgr.default.matchFamily(name)
    val faces = LinkedHashSet<Pair<Int, Boolean>>()
    for (index in 0 until set.count()) {
        val face = set.getTypeface(index) ?: continue
        if (!face.familyName.equals(name, ignoreCase = true)) continue
        val declared = face.fontStyle
        faces += declared.weight.coerceIn(1, 1000) to (declared.slant != FontSlant.UPRIGHT)
    }
    if (faces.isEmpty()) return null
    return FontFamily(
        faces.map { (weight, italic) ->
            SystemFont(name, FontWeight(weight), if (italic) FontStyle.Italic else FontStyle.Normal)
        },
    )
}

/** Draws the document into the whole of the current drawing area. */
internal fun DrawScope.drawVectorDocument(document: VectorDocument) {
    document.setContainerSize(size.width, size.height)
    drawIntoCanvas { canvas -> document.render(canvas.nativeCanvas) }
}
