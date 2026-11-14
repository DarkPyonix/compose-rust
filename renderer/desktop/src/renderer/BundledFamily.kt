package dev.darkpyonix.composerust.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

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

/** Where the web bundle keeps [webDefaultFaces], relative to the page. */
const val WEB_FONT_DIRECTORY: String = "fonts"

/**
 * The family made of [faces], each with the bytes of its file.
 *
 * Every face is one font of the family at the weight and slant the table says, so Compose's
 * font matching, which follows CSS, chooses the face for a requested weight and style.
 */
fun bundledFamily(faces: List<Pair<BundledFace, ByteArray>>): FontFamily = FontFamily(
    faces.map { (face, bytes) ->
        Font(
            identity = "compose-rust-bundled-${face.file}",
            data = bytes,
            weight = FontWeight(face.weight),
            style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
        )
    },
)
