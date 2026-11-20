package dev.darkpyonix.composerust.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import dev.darkpyonix.composerust.ui.node.FontChain
import dev.darkpyonix.composerust.ui.node.decodeFontAsset
import org.jetbrains.skia.Data

/**
 * One face of a family the renderer carries in its own bundle: the file, and the weight and
 * slant it is.
 */
class BundledFace(val file: String, val weight: Int, val italic: Boolean)

/**
 * The faces the web bundle carries for its default family, Roboto.
 *
 * A browser gives a canvas no fonts of the machine's, so the only face a page had was the
 * one Skia's web build carries, Roboto Regular. Text in bold or italic was then set in that
 * one regular face, because Compose's web build does not synthesize a weight or a slant a
 * family does not have. A browser setting `system-ui` or `sans-serif` in bold uses a real
 * bold face, and so does every other target here, so the bundle carries the family's real
 * faces and Compose picks among them the way CSS does.
 *
 * Regular is carried as well, though Skia has one: a family is one set of files, and a bold
 * from one release beside a regular from another would not line up.
 *
 * Medium is there because the design systems' type ladders use 500 for labels and titles,
 * and CSS's rule sets 500 in the regular face when there is no medium. Weights between the
 * faces take the nearest the CSS way: 100 to 400 the regular, 600 and up the bold.
 *
 * The files are Roboto 2.138 (googlefonts/roboto-2), under the Apache License 2.0 that sits
 * beside them in `renderer/web/resources/fonts/`.
 */
val webDefaultFaces: List<BundledFace> = listOf(
    BundledFace("Roboto-Regular.ttf", 400, italic = false),
    BundledFace("Roboto-Italic.ttf", 400, italic = true),
    BundledFace("Roboto-Medium.ttf", 500, italic = false),
    BundledFace("Roboto-MediumItalic.ttf", 500, italic = true),
    BundledFace("Roboto-Bold.ttf", 700, italic = false),
    BundledFace("Roboto-BoldItalic.ttf", 700, italic = true),
)

/**
 * The faces the web bundle carries for the letters [webDefaultFaces] have no glyph for, in
 * the order they are asked.
 *
 * Every other target draws such a letter from one of the machine's fonts. A canvas in a
 * browser has none of them, so a backspace sign (U+232B) or a play triangle (U+25B6), which
 * Roboto does not have, was an empty box on the web and a glyph everywhere else.
 *
 * The one face is cut from Noto Sans Symbols 2 (notofonts/symbols), keeping the code points
 * of Arrows (U+2190 to U+21FF), Miscellaneous Technical (U+2300 to U+23FF) and Geometric
 * Shapes (U+25A0 to U+25FF) that the typeface has: all of Geometric Shapes, and the keys
 * and media signs of the other two. It is under the SIL Open Font License 1.1, which sits
 * beside it as `OFL.txt` and says how the file was cut.
 */
val webFallbackFaces: List<String> = listOf("NotoSansSymbols2-Subset.ttf")

/** Where the web bundle keeps [webDefaultFaces] and [webFallbackFaces], relative to the page. */
const val WEB_FONT_DIRECTORY: String = "fonts"

/**
 * The family made of [faces], each with the bytes of its file.
 *
 * Every face is one font of the family at the weight and slant the table says, so Compose's
 * font matching, which follows CSS, chooses the face for a requested weight and style.
 *
 * Each file is copied into Skia once and the font is made from that copy, so the typefaces
 * Compose makes for it share it and the byte array is not kept beside it.
 */
fun bundledFamily(faces: List<Pair<BundledFace, ByteArray>>): FontFamily = FontFamily(
    faces.map { (face, bytes) ->
        Font(
            identity = "compose-rust-bundled-${face.file}",
            data = Data.makeFromBytes(bytes),
            weight = FontWeight(face.weight),
            style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
        )
    },
)

/**
 * The chain text in [family] is set through, where [family] is [bundledFamily] of [faces]:
 * that family alone, with the letters it has. Null where the regular face cannot be read,
 * which leaves the family taking every letter, as it did before.
 *
 * Knowing the letters is what lets one the family lacks go on to the fallback faces. They
 * are asked of the upright regular face, because the faces of one release of a family
 * cover the same letters.
 */
fun bundledChain(family: FontFamily, faces: List<Pair<BundledFace, ByteArray>>): FontChain? {
    val regular = faces.firstOrNull { (face, _) -> face.weight == 400 && !face.italic } ?: faces.firstOrNull()
    val hasGlyph = regular?.let { (_, bytes) -> decodeFontAsset(bytes)?.face?.hasGlyph } ?: return null
    return FontChain(listOf(FontChain.Member(family, hasGlyph)))
}

/**
 * One of [webFallbackFaces], from the bytes of its file, as the family that draws the
 * letters it has. Null where the bytes are not a font.
 */
fun bundledFallback(bytes: ByteArray): FontChain.Member? {
    val font = decodeFontAsset(bytes) ?: return null
    return FontChain.Member(font.family, font.face?.hasGlyph ?: return null)
}
