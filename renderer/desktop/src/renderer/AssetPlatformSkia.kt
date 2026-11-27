package dev.darkpyonix.composerust.ui.node

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.skiaShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit

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
    SVGDOM(Data.makeFromBytes(rewriteSkiaEllipseGradients(bytes)))

private val ELLIPSE_TAG_REGEX = Regex("<ellipse\\b([^>]*?)(/?)>")
private val ELLIPSE_ATTR_REGEX = Regex("\\b(cx|cy|rx|ry)\\s*=\\s*\"([^\"]+)\"")

/**
 * Skia's SVG DOM paints an `<ellipse>` filled with an `objectBoundingBox` gradient solid black,
 * whereas an equivalent two-arc `<path>` renders the gradient as expected. Rewrite `<ellipse>`
 * elements when the document references a `url(#...)` paint server so desktop, iOS, and web
 * match Android's SVG reader.
 */
internal fun rewriteSkiaEllipseGradients(bytes: ByteArray): ByteArray {
    val text = bytes.decodeToString()
    if (!text.contains("<ellipse") || !text.contains("url(#")) return bytes
    var rewritten = false
    val updated = ELLIPSE_TAG_REGEX.replace(text) { match ->
        val attrs = match.groupValues[1]
        val selfClose = match.groupValues[2]
        var cx = 0.0
        var cy = 0.0
        var rx: Double? = null
        var ry: Double? = null
        for (attr in ELLIPSE_ATTR_REGEX.findAll(attrs)) {
            val value = attr.groupValues[2].trim().toDoubleOrNull() ?: continue
            when (attr.groupValues[1]) {
                "cx" -> cx = value
                "cy" -> cy = value
                "rx" -> rx = value
                "ry" -> ry = value
            }
        }
        val a = rx
        val b = ry
        if (a == null || b == null || a <= 0.0 || b <= 0.0) {
            match.value
        } else {
            rewritten = true
            val rest = ELLIPSE_ATTR_REGEX.replace(attrs, "")
            val x0 = cx - a
            val x1 = cx + a
            val d = "M $x0 $cy A $a $b 0 1 0 $x1 $cy A $a $b 0 1 0 $x0 $cy Z"
            "<path d=\"$d\"$rest$selfClose>"
        }
    }
    if (!rewritten) return bytes
    return updated.replace("</ellipse>", "</path>").encodeToByteArray()
}

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
 *
 * The glyph table is read out of the `cmap` table into a compact range array and the
 * validation typeface is closed right away, so only the `LoadedFont` bytes remain in
 * memory until Compose resolves the face into its own typeface cache.
 */
internal fun decodeFontAsset(bytes: ByteArray): Asset.Font? {
    val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return null
    val declared = typeface.fontStyle
    val weight = declared.weight.coerceIn(1, 1000)
    val italic = declared.slant != FontSlant.UPRIGHT
    val wghtBounds = typeface.variationAxes
        ?.firstOrNull { it.tag == "wght" && it.maxValue > it.minValue }
        ?.let { it.minValue.toInt().coerceIn(1, 1000) to it.maxValue.toInt().coerceIn(1, 1000) }
    val glyphs = parseCmapRanges(bytes) ?: probeGlyphRanges(typeface)
    typeface.close()
    val style = if (italic) FontStyle.Italic else FontStyle.Normal
    val baseIdentity = "compose-rust-font-${bytes.size}-${bytes.contentHashCode()}"
    if (wghtBounds != null) {
        val (minWeight, maxWeight) = wghtBounds
        val weights = buildSet {
            add(minWeight)
            add(maxWeight)
            add(weight.coerceIn(minWeight, maxWeight))
            for (standard in 100..900 step 100) {
                if (standard in minWeight..maxWeight) add(standard)
            }
        }.sorted()
        val fonts = weights.map { w ->
            Font(
                identity = "$baseIdentity-w$w",
                getData = { bytes },
                weight = FontWeight(w),
                style = style,
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        }
        val primary = fonts.minBy { abs(it.weight.weight - weight) }
        return Asset.Font(
            family = FontFamily(fonts),
            face = Asset.Font.Face(primary, weight, italic, glyphs::contains, fonts),
        )
    }
    val font = Font(
        identity = baseIdentity,
        getData = { bytes },
        weight = FontWeight(weight),
        style = style,
    )
    return Asset.Font(
        family = FontFamily(font),
        face = Asset.Font.Face(font, weight, italic, glyphs::contains),
    )
}

/**
 * Sorted, non-overlapping inclusive `[start, end]` pairs of Unicode code points the face
 * maps to a non-zero glyph id.
 *
 * A face with a few hundred ranges takes a couple of kilobytes instead of a second
 * multi-megabyte Skia typeface in native memory.
 */
internal class GlyphRanges(private val ranges: IntArray) {
    fun contains(codePoint: Int): Boolean {
        var low = 0
        var high = (ranges.size ushr 1) - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = mid shl 1
            val start = ranges[at]
            val end = ranges[at + 1]
            when {
                codePoint < start -> high = mid - 1
                codePoint > end -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}

internal class RangeBuilder {
    private var data = IntArray(64)
    var size = 0
        private set

    fun add(start: Int, end: Int) {
        if (start > end) return
        if (size >= 2 && data[size - 2] <= start && start <= data[size - 1] + 1) {
            if (end > data[size - 1]) data[size - 1] = end
            return
        }
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = start
        data[size++] = end
    }

    fun finish(): GlyphRanges {
        if (size <= 2) return GlyphRanges(data.copyOf(size))
        // Subtables are usually in code-point order; sort and merge if any were not.
        var sorted = true
        var at = 2
        while (at < size) {
            if (data[at] <= data[at - 1] + 1) {
                sorted = false
                break
            }
            at += 2
        }
        if (sorted) return GlyphRanges(data.copyOf(size))
        val pairs = ArrayList<IntRange>(size ushr 1)
        var index = 0
        while (index < size) {
            pairs += data[index]..data[index + 1]
            index += 2
        }
        pairs.sortBy { it.first }
        val merged = RangeBuilder()
        for (range in pairs) merged.add(range.first, range.last)
        return GlyphRanges(merged.data.copyOf(merged.size))
    }
}

/**
 * Reads the Unicode `cmap` subtable (format 12 first, then format 4) of a TrueType or
 * OpenType file, or the first face of a `ttcf` collection. Null where the file has neither
 * format, so the caller can fall back to asking the Skia typeface before closing it.
 */
internal fun parseCmapRanges(bytes: ByteArray): GlyphRanges? {
    fun u16(at: Int): Int? = if (at < 0 || at + 2 > bytes.size) {
        null
    } else {
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }
    fun s16(at: Int): Int? = u16(at)?.toShort()?.toInt()
    fun u32(at: Int): Int? {
        val high = u16(at) ?: return null
        val low = u16(at + 2) ?: return null
        return (high shl 16) or low
    }
    fun tag(at: Int, name: String): Boolean =
        at + 4 <= bytes.size && (0 until 4).all { bytes[at + it] == name[it].code.toByte() }

    val start = if (tag(0, "ttcf")) u32(12) ?: return null else 0
    val tables = u16(start + 4) ?: return null
    var cmap = -1
    for (index in 0 until tables) {
        val record = start + 12 + 16 * index
        if (tag(record, "cmap")) {
            cmap = u32(record + 8) ?: return null
            break
        }
    }
    if (cmap < 0) return null
    val numTables = u16(cmap + 2) ?: return null
    var format12 = -1
    var format4 = -1
    for (index in 0 until numTables) {
        val record = cmap + 4 + 8 * index
        val platform = u16(record) ?: return null
        val encoding = u16(record + 2) ?: return null
        val offset = u32(record + 4) ?: return null
        if (offset <= 0 || cmap.toLong() + offset >= bytes.size) continue
        val sub = cmap + offset
        val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
        if (!unicode) continue
        when (u16(sub)) {
            12 -> if (format12 < 0) format12 = sub
            4 -> if (format4 < 0) format4 = sub
        }
    }
    if (format12 >= 0) {
        val count = u32(format12 + 12) ?: return null
        if (count in 0..0x100000 && format12.toLong() + 16 + count.toLong() * 12 <= bytes.size) {
            val out = RangeBuilder()
            for (index in 0 until count) {
                val group = format12 + 16 + 12 * index
                val startCode = u32(group) ?: return null
                val endCode = u32(group + 4) ?: return null
                val startGlyph = u32(group + 8) ?: return null
                if (startCode < 0 || endCode < startCode) return null
                if (startGlyph != 0) {
                    out.add(startCode, endCode)
                } else if (endCode > startCode) {
                    out.add(startCode + 1, endCode)
                }
            }
            return out.finish()
        }
    }
    if (format4 >= 0) {
        val segCountX2 = u16(format4 + 6) ?: return null
        val segCount = segCountX2 ushr 1
        val endBase = format4 + 14
        val startBase = endBase + segCountX2 + 2
        val deltaBase = startBase + segCountX2
        val rangeBase = deltaBase + segCountX2
        if (segCount > 0 && rangeBase.toLong() + segCountX2 <= bytes.size) {
            val out = RangeBuilder()
            for (seg in 0 until segCount) {
                val endCode = u16(endBase + 2 * seg) ?: return null
                val startCode = u16(startBase + 2 * seg) ?: return null
                val delta = s16(deltaBase + 2 * seg) ?: return null
                val rangeOffset = u16(rangeBase + 2 * seg) ?: return null
                if (startCode == 0xFFFF && endCode == 0xFFFF) break
                if (endCode < startCode) return null
                if (rangeOffset == 0) {
                    var c = startCode
                    while (c <= endCode) {
                        if (((c + delta) and 0xFFFF) != 0) {
                            val zero = (-delta) and 0xFFFF
                            val runEnd = if (zero in (c + 1)..endCode) zero - 1 else endCode
                            out.add(c, runEnd)
                            c = runEnd + 1
                        } else {
                            c++
                        }
                    }
                } else {
                    val rangeAddr = rangeBase + 2 * seg
                    for (c in startCode..endCode) {
                        val glyphAddr = rangeAddr + rangeOffset + 2 * (c - startCode)
                        val glyph = u16(glyphAddr) ?: return null
                        if (glyph != 0 && ((glyph + delta) and 0xFFFF) != 0) {
                            out.add(c, c)
                        }
                    }
                }
            }
            return out.finish()
        }
    }
    return null
}

private fun probeGlyphRanges(typeface: org.jetbrains.skia.Typeface): GlyphRanges {
    val out = RangeBuilder()
    for (cp in 0..0xFFFF) {
        if (cp !in 0xD800..0xDFFF && typeface.getUTF32Glyph(cp) != 0.toShort()) {
            out.add(cp, cp)
        }
    }
    return out.finish()
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

/**
 * Draws the document scaled to the whole of the current drawing area, whatever size its
 * root declares, the way an icon slot draws a picture.
 *
 * Many icon sets write a fixed size on the root (`width='24' height='24'`) beside the
 * `viewBox`, and Skia draws such a root at that size, so a 24 unit icon stayed 24 pixels in
 * a slot of 48. The root is given the whole area for this one draw and its own size is put
 * back after, because the same document is also drawn as an `Image` at the size the file
 * gave it.
 */
internal fun DrawScope.drawVectorDocumentFitted(document: VectorDocument) {
    val root = document.root
    if (root == null || root.viewBox == null) {
        drawVectorDocument(document)
        return
    }
    val width = root.width
    val height = root.height
    root.width = WHOLE_AREA
    root.height = WHOLE_AREA
    try {
        drawVectorDocument(document)
    } finally {
        root.width = width
        root.height = height
    }
}

private val WHOLE_AREA = SVGLength(100f, SVGLengthUnit.PERCENTAGE)

/**
 * A radial gradient [radiusX] across and [radiusY] tall around [center].
 *
 * Compose's radial gradient is a circle, so this is the circle of [radiusX] with a local
 * matrix that squashes it vertically about its centre. The stops then fall on ellipses, as
 * CSS `radial-gradient(ellipse ...)` places them.
 */
internal fun ellipticalRadialShader(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    colors: List<Color>,
    colorStops: List<Float>,
    tileMode: TileMode,
): Shader {
    val circle = RadialGradientShader(center, radiusX, colors, colorStops, tileMode)
    val squash = radiusY / radiusX
    return circle.skiaShader.makeWithLocalMatrix(
        Matrix33(1f, 0f, 0f, 0f, squash, center.y * (1f - squash), 0f, 0f, 1f),
    ).asComposeShader()
}

/**
 * The size the document says it is, in its own units: its viewBox, or where it has none, a
 * width and a height given as plain numbers or pixels. Null where it says neither, which
 * leaves it a drawing with no proportions of its own.
 */
internal fun vectorIntrinsicSize(document: VectorDocument): Size? {
    val root = document.root ?: return null
    val box = root.viewBox
    if (box != null && box.width > 0f && box.height > 0f) return Size(box.width, box.height)
    val width = root.width
    val height = root.height
    val absolute = setOf(SVGLengthUnit.NUMBER, SVGLengthUnit.PX)
    if (width.unit !in absolute || height.unit !in absolute) return null
    if (width.value <= 0f || height.value <= 0f) return null
    return Size(width.value, height.value)
}

/**
 * Draws a blurred box shadow: [shape] in [brush], blurred by a Gaussian of standard deviation
 * [sigma], as one path drawn with a paint that blurs it. [width] and [height] are the
 * shadow's size, which only a renderer that blurs through a layer needs.
 *
 * No layer, on purpose. A shadow blurred as a layer is drawn offscreen at its full size on
 * every frame, and on Metal the textures that takes stay held while the shadow is on screen:
 * 12 to 13 MB across the screens of an application with a few dozen shadows, which no purge
 * of Skia's caches gave back. Skia blurs a path's coverage itself, analytically for
 * rectangles and rounded rectangles, with nothing offscreen the size of the shadow.
 */
@Suppress("UNUSED_PARAMETER")
internal fun CacheDrawScope.blurredShadow(
    shape: Path,
    brush: Brush,
    sigma: Float,
    width: Float,
    height: Float,
): DrawScope.() -> Unit {
    val paint = blurredShadowPaint(brush, size, sigma)
    return { drawContext.canvas.drawPath(shape, paint) }
}

/**
 * The paint a blurred shadow is drawn with: [brush] laid over [size], and a mask filter that
 * blurs whatever is drawn with it by a Gaussian of standard deviation [sigma].
 *
 * The deviation is in the canvas's own units, so a shadow under a scaled layer is blurred
 * with it, as the layer it replaces was.
 */
internal fun blurredShadowPaint(brush: Brush, size: Size, sigma: Float): Paint = Paint().also {
    brush.applyTo(size, it, 1f)
    it.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma, true)
}

/**
 * Where a line of [text] may end, as offsets after 0 in increasing order, the end of the
 * text last: the line breaker's own opportunities, which are the ones Skia's paragraph
 * breaks at. A word joiner forbids one and a zero width space makes one, as they do there.
 */
internal fun lineBreakOpportunities(text: String): IntArray {
    if (text.isEmpty()) return IntArray(0)
    val breaks = org.jetbrains.skia.BreakIterator.makeLineInstance()
    try {
        breaks.setText(text)
        val out = ArrayList<Int>()
        var at = breaks.next()
        while (at != org.jetbrains.skia.BreakIterator.DONE) {
            if (at > 0) out += at
            at = breaks.next()
        }
        return out.toIntArray()
    } finally {
        breaks.close()
    }
}
