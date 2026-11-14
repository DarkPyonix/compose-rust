package dev.darkpyonix.composerust.ui.node

import android.content.Context
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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
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
 * `Typeface.createFromFile` reads every Android this renderer runs on, while the byte
 * buffer loaders arrive only in API 29, so the bytes go to the cache directory once, at
 * registration. The file is deleted as soon as the face is built: the typeface holds the
 * parsed font, not the path.
 *
 * The face goes to Compose as a font of its own, at the weight and slant the file
 * declares, and not as a ready typeface. A family made from a typeface is that typeface
 * at every weight, which is how bold and semibold text came out regular here: a stack's
 * semibold file was never asked for. As fonts, a stack's files group into one family and
 * Compose sets each weight in the nearest face, drawing a bold where the family has none.
 */
internal fun decodeFontAsset(bytes: ByteArray): Asset.Font? {
    val file = File.createTempFile("dxc-font", ".ttf")
    return try {
        file.writeBytes(bytes)
        val typeface = android.graphics.Typeface.createFromFile(file)
        // The file says what it is. Where it has no OS/2 table, the typeface is asked, and
        // below API 28 bold or not is all it can answer.
        val declared = declaredFaceStyle(bytes) ?: DeclaredFaceStyle(
            weight = if (android.os.Build.VERSION.SDK_INT >= 28) typeface.weight else if (typeface.isBold) 700 else 400,
            italic = typeface.isItalic,
        )
        val font = LoadedFaceFont(
            typeface,
            FontWeight(declared.weight),
            if (declared.italic) FontStyle.Italic else FontStyle.Normal,
        )
        val paint = android.graphics.Paint().apply { this.typeface = typeface }
        Asset.Font(
            family = FontFamily(font),
            face = Asset.Font.Face(font, declared.weight, declared.italic) { codePoint ->
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
 * A face already read at registration, handed to Compose as a font.
 *
 * Loading it is giving back the typeface, so it is blocking and costs nothing. Two faces
 * are two fonts by identity, so two weights of one typeface never stand in for each
 * other.
 */
@OptIn(ExperimentalTextApi::class)
private class LoadedFaceFont(
    val typeface: android.graphics.Typeface,
    override val weight: FontWeight,
    override val style: FontStyle,
) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): android.graphics.Typeface =
            (font as LoadedFaceFont).typeface

        override suspend fun awaitLoad(context: Context, font: AndroidFont): android.graphics.Typeface =
            (font as LoadedFaceFont).typeface
    }

    override fun toString(): String = "LoadedFaceFont(weight=$weight, style=$style)"
}

/**
 * The installed family called [name], at every weight and slant, or null where this device
 * has none by that name.
 *
 * Android answers an unknown family with its default face rather than with nothing, so a
 * name that comes back as the default, and is not a name for the default, is taken to be
 * missing and the font list moves on to its next candidate.
 *
 * The family is a font for each standard weight in each slant, each naming the installed
 * family, rather than the one typeface the name answers. That typeface is the regular face,
 * and a family made from it sets bold text regular. Compose asks the device for the weight
 * it needs when text first uses it.
 *
 * Kept per name: the device's fonts do not change while the application runs, and the same
 * family object each time is what lets Compose reuse a layout it has already made.
 */
internal fun systemFontFamily(name: String): FontFamily? =
    installedFamilies.getOrPut(name) { InstalledFamily(buildSystemFontFamily(name)) }.family

private class InstalledFamily(val family: FontFamily?)

private val installedFamilies = HashMap<String, InstalledFamily>()

@OptIn(ExperimentalTextApi::class)
private fun buildSystemFontFamily(name: String): FontFamily? {
    val typeface = android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        ?: return null
    val isDefault = typeface == android.graphics.Typeface.DEFAULT ||
        typeface == android.graphics.Typeface.SANS_SERIF
    val namesDefault = name.equals("sans-serif", ignoreCase = true) ||
        name.equals("default", ignoreCase = true)
    if (isDefault && !namesDefault) return null
    val device = DeviceFontFamilyName(name)
    return FontFamily(
        (100..900 step 100).flatMap { weight ->
            listOf(FontStyle.Normal, FontStyle.Italic).map { style -> Font(device, FontWeight(weight), style) }
        },
    )
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
 * Draws the document scaled to the whole of the current drawing area, whatever size its
 * root declares, the way an icon slot draws a picture.
 */
internal fun DrawScope.drawVectorDocumentFitted(document: VectorDocument) = document.draw(this, fit = true)

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
