package dev.darkpyonix.composerust.ui.node

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * An SVG picture read into Compose paths, for a graphics stack that has no SVG reader of
 * its own.
 *
 * Android draws through its own graphics stack, and that stack cannot read SVG. The desktop
 * and iOS draw the same files through Skia's SVG document, so this reader follows Skia's
 * rules wherever the two could differ: the root's width and height are lengths in the
 * drawing area's own units, a percentage of it, or the whole of it when they are left out;
 * the `viewBox` is fitted into that by `preserveAspectRatio`; and nothing is clipped to the
 * viewport. An application ships one picture, and it draws the same everywhere.
 *
 * What it reads is the static drawing subset of SVG 1.1:
 * - shapes: `path`, `rect` (with `rx`/`ry`), `circle`, `ellipse`, `line`, `polyline`,
 *   `polygon`, grouped by `g`, `a`, `switch` and nested `svg`, and reused by `use` (of a
 *   shape, a group or a `symbol`, named by `xlink:href`);
 * - painting: `fill`, `stroke` and their opacities, `fill-rule`, `stroke-width`,
 *   `stroke-linecap`, `stroke-linejoin`, `stroke-miterlimit`, `stroke-dasharray`,
 *   `stroke-dashoffset`, `opacity`, `color` and `currentColor`, `display` and `visibility`,
 *   given as attributes or in a `style` attribute, and inherited the way CSS inherits them;
 * - colours: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`, the CSS colour
 *   names and `transparent`;
 * - paint servers: `linearGradient` and `radialGradient` in either unit system, with
 *   `gradientTransform`, `spreadMethod` and `href` inheritance, and `pattern` tiles;
 * - `transform` lists of `matrix`, `translate`, `scale`, `rotate`, `skewX` and `skewY`;
 * - `clipPath` in either unit system, with `clip-rule`.
 *
 * What it does not read is drawn as if it were absent, the way an unknown element is in a
 * browser: `text`, `image`, `mask`, `filter`, `marker`, `<style>` sheets, a radial
 * gradient's separate focal point (`fx`/`fy` draw as the centre), and animation.
 *
 * One place it deliberately differs from Skia: Skia gives an `ellipse` no bounding box, so a
 * gradient in bounding box units paints it solid black there. Here it paints the gradient.
 */
class SvgDocument private constructor(
    private val root: SvgElement,
    private val ids: Map<String, SvgElement>,
) {
    private val width = root.length("width") ?: SvgLength(100f, SvgLengthUnit.Percent)
    private val height = root.length("height") ?: SvgLength(100f, SvgLengthUnit.Percent)
    private val viewBox = root.numbers("viewBox")?.takeIf { it.size == 4 }
        ?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }
    private val aspect = SvgAspectRatio.parse(root.attributes["preserveAspectRatio"])

    // The picture built for the last viewport it was drawn in. Percentages in it resolve
    // against the viewport, which is the viewBox when there is one and the drawing area when
    // not, so a document with a viewBox is built once and every later frame reuses it.
    private var builtWidth = -1f
    private var builtHeight = -1f
    private var built: SvgPicture? = null

    // The viewBox fitted into the last drawing area, kept so a frame at the same size as the
    // one before allocates nothing.
    private var fittedWidth = -1f
    private var fittedHeight = -1f
    private var fitted: Matrix? = null

    /**
     * Draws into the whole of [scope]'s area. With [fit], the root's own width and height
     * are set aside and the viewport is the whole area, the way an icon slot sizes a picture.
     */
    internal fun draw(scope: DrawScope, fit: Boolean = false) {
        val area = scope.size
        if (area.width <= 0f || area.height <= 0f) return
        val viewportWidth = if (fit) area.width else width.resolve(area.width)
        val viewportHeight = if (fit) area.height else height.resolve(area.height)
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        val box = viewBox
        if (box == null) {
            picture(viewportWidth, viewportHeight).draw(scope)
            return
        }
        if (box.width <= 0f || box.height <= 0f) return
        val picture = picture(box.width, box.height)
        if (viewportWidth != fittedWidth || viewportHeight != fittedHeight) {
            val fit = aspect.fit(box, Rect(0f, 0f, viewportWidth, viewportHeight))
            fitted = if (fit.isIdentity) null else fit.toMatrix()
            fittedWidth = viewportWidth
            fittedHeight = viewportHeight
        }
        val matrix = fitted
        if (matrix == null) {
            picture.draw(scope)
        } else {
            scope.withTransform({ transform(matrix) }) { picture.draw(this) }
        }
    }

    private fun picture(viewportWidth: Float, viewportHeight: Float): SvgPicture {
        val cached = built
        if (cached != null && builtWidth == viewportWidth && builtHeight == viewportHeight) return cached
        val picture = SvgBuilder(ids, Size(viewportWidth, viewportHeight)).build(root)
        builtWidth = viewportWidth
        builtHeight = viewportHeight
        built = picture
        return picture
    }

    companion object {
        /**
         * Reads [bytes] as an SVG document, or null where they are not one: not UTF-8 text,
         * not well formed, or with a root that is not `svg`.
         */
        fun parse(bytes: ByteArray): SvgDocument? {
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (error: CharacterCodingException) {
                return null
            }
            val root = SvgXmlReader(text).read() ?: return null
            if (root.name != "svg") return null
            val ids = HashMap<String, SvgElement>()
            root.collectIds(ids)
            return SvgDocument(root, ids)
        }
    }
}

/** Draws [document] into the whole of the current drawing area. */
internal fun DrawScope.drawSvgDocument(document: SvgDocument) = document.draw(this)

// -------------------------------------------------------------------------------------------
// Reading the XML
// -------------------------------------------------------------------------------------------

internal class SvgElement(val name: String, val attributes: Map<String, String>) {
    val children = ArrayList<SvgElement>()

    /** The `style` attribute's declarations, which win over the attributes of the same name. */
    val style: Map<String, String> = attributes["style"]?.let(::parseSvgStyle) ?: emptyMap()

    fun property(name: String): String? = style[name] ?: attributes[name]

    /**
     * The element this one refers to. Only `xlink:href` is read: Skia's SVG document does not
     * read SVG 2's bare `href`, and a reference one platform follows and another drops would
     * draw a different picture on each.
     */
    fun href(): String? = attributes["xlink:href"]?.trim()
        ?.takeIf { it.startsWith("#") }?.substring(1)

    fun length(name: String): SvgLength? = attributes[name]?.let(SvgLength::parse)

    fun numbers(name: String): FloatArray? = attributes[name]?.let(::parseSvgNumbers)

    fun collectIds(into: MutableMap<String, SvgElement>) {
        attributes["id"]?.let { if (it !in into) into[it] = this }
        for (child in children) child.collectIds(into)
    }
}

private fun parseSvgStyle(text: String): Map<String, String> {
    val declarations = HashMap<String, String>()
    for (declaration in text.split(';')) {
        val colon = declaration.indexOf(':')
        if (colon <= 0) continue
        val key = declaration.substring(0, colon).trim()
        val value = declaration.substring(colon + 1).trim().removeSuffix("!important").trim()
        if (key.isNotEmpty() && value.isNotEmpty()) declarations[key] = value
    }
    return declarations
}

/**
 * A reader for the part of XML an SVG file uses: elements, attributes, comments, CDATA,
 * processing instructions and a document type declaration. Text is skipped, because nothing
 * this reader draws is text. Null where the document is not well formed.
 */
private class SvgXmlReader(private val text: String) {
    private var at = if (text.startsWith('﻿')) 1 else 0

    fun read(): SvgElement? {
        val stack = ArrayList<SvgElement>()
        var root: SvgElement? = null
        while (at < text.length) {
            val open = text.indexOf('<', at)
            if (open < 0) break
            at = open
            when {
                text.startsWith("<!--", at) -> at = skipPast("-->") ?: return null
                text.startsWith("<![CDATA[", at) -> at = skipPast("]]>") ?: return null
                text.startsWith("<?", at) -> at = skipPast("?>") ?: return null
                text.startsWith("<!", at) -> at = skipDeclaration() ?: return null
                text.startsWith("</", at) -> {
                    val close = text.indexOf('>', at)
                    if (close < 0) return null
                    val name = text.substring(at + 2, close).trim()
                    val top = stack.removeLastOrNull() ?: return null
                    if (localName(name) != top.name) return null
                    at = close + 1
                }
                else -> {
                    at++
                    val (element, selfClosing) = readTag() ?: return null
                    val parent = stack.lastOrNull()
                    if (parent != null) {
                        parent.children += element
                    } else if (root == null) {
                        root = element
                    } else {
                        return null
                    }
                    if (!selfClosing) stack += element
                }
            }
        }
        return if (stack.isEmpty()) root else null
    }

    private fun skipPast(end: String): Int? {
        val found = text.indexOf(end, at)
        return if (found < 0) null else found + end.length
    }

    // A document type declaration can carry an internal subset in brackets, whose own
    // declarations end in '>' too.
    private fun skipDeclaration(): Int? {
        var depth = 0
        var i = at
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return null
    }

    private fun readTag(): Pair<SvgElement, Boolean>? {
        val nameStart = at
        while (at < text.length && !text[at].isWhitespace() && text[at] != '>' && text[at] != '/') at++
        if (at == nameStart) return null
        val name = localName(text.substring(nameStart, at))
        val attributes = LinkedHashMap<String, String>()
        while (true) {
            while (at < text.length && text[at].isWhitespace()) at++
            if (at >= text.length) return null
            when (text[at]) {
                '>' -> {
                    at++
                    return SvgElement(name, attributes) to false
                }
                '/' -> {
                    if (at + 1 >= text.length || text[at + 1] != '>') return null
                    at += 2
                    return SvgElement(name, attributes) to true
                }
            }
            val keyStart = at
            while (at < text.length && text[at] != '=' && !text[at].isWhitespace() && text[at] != '>' &&
                text[at] != '/'
            ) {
                at++
            }
            val key = text.substring(keyStart, at)
            while (at < text.length && text[at].isWhitespace()) at++
            if (at >= text.length || text[at] != '=') return null
            at++
            while (at < text.length && text[at].isWhitespace()) at++
            if (at >= text.length) return null
            val quote = text[at]
            if (quote != '"' && quote != '\'') return null
            val end = text.indexOf(quote, at + 1)
            if (end < 0) return null
            attributes[key] = decodeEntities(text.substring(at + 1, end))
            at = end + 1
        }
    }

    // `svg:path` is a path. A name in another namespace keeps a local name nothing here
    // draws, which is what an unknown element should do.
    private fun localName(name: String): String {
        val colon = name.indexOf(':')
        return if (colon >= 0 && name.startsWith("svg:")) name.substring(colon + 1) else name
    }

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val semicolon = if (c == '&') value.indexOf(';', i) else -1
            if (semicolon < 0) {
                out.append(c)
                i++
                continue
            }
            val entity = value.substring(i + 1, semicolon)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)?.let(::codePointString)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePointString)
                else -> null
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semicolon + 1
            }
        }
        return out.toString()
    }

    private fun codePointString(codePoint: Int): String? = when {
        codePoint < 0 || codePoint > 0x10FFFF -> null
        codePoint < 0x10000 -> codePoint.toChar().toString()
        else -> {
            val offset = codePoint - 0x10000
            charArrayOf(
                (0xD800 + (offset shr 10)).toChar(),
                (0xDC00 + (offset and 0x3FF)).toChar(),
            ).concatToString()
        }
    }
}

// -------------------------------------------------------------------------------------------
// Values
// -------------------------------------------------------------------------------------------

internal enum class SvgLengthUnit { Number, Percent, Em, Ex, Cm, Mm, In, Pt, Pc }

internal class SvgLength(val value: Float, val unit: SvgLengthUnit) {
    /**
     * This length in user units, where [reference] is what a percentage is of. An absolute
     * unit is taken at 90 dots to the inch, Skia's resolution, so the desktop and this agree.
     */
    fun resolve(reference: Float, fontSize: Float = DEFAULT_FONT_SIZE): Float = when (unit) {
        SvgLengthUnit.Number -> value
        SvgLengthUnit.Percent -> value * reference / 100f
        SvgLengthUnit.Em -> value * fontSize
        SvgLengthUnit.Ex -> value * fontSize / 2f
        SvgLengthUnit.Cm -> value * DPI / 2.54f
        SvgLengthUnit.Mm -> value * DPI / 25.4f
        SvgLengthUnit.In -> value * DPI
        SvgLengthUnit.Pt -> value * DPI / 72f
        SvgLengthUnit.Pc -> value * DPI / 6f
    }

    companion object {
        private const val DPI = 90f
        private const val DEFAULT_FONT_SIZE = 12f

        fun parse(text: String): SvgLength? {
            val trimmed = text.trim()
            val svgNumberEnd = svgNumberEnd(trimmed, 0)
            if (svgNumberEnd == 0) return null
            val value = trimmed.substring(0, svgNumberEnd).toFloatOrNull() ?: return null
            val unit = when (trimmed.substring(svgNumberEnd).trim().lowercase()) {
                "", "px" -> SvgLengthUnit.Number
                "%" -> SvgLengthUnit.Percent
                "em" -> SvgLengthUnit.Em
                "ex" -> SvgLengthUnit.Ex
                "cm" -> SvgLengthUnit.Cm
                "mm" -> SvgLengthUnit.Mm
                "in" -> SvgLengthUnit.In
                "pt" -> SvgLengthUnit.Pt
                "pc" -> SvgLengthUnit.Pc
                else -> return null
            }
            return SvgLength(value, unit)
        }
    }
}

/** Where the number that starts at [start] ends: sign, digits, a fraction and an exponent. */
private fun svgNumberEnd(text: String, start: Int): Int {
    var i = start
    if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
    var digits = 0
    while (i < text.length && text[i].isDigit()) { i++; digits++ }
    if (i < text.length && text[i] == '.') {
        i++
        while (i < text.length && text[i].isDigit()) { i++; digits++ }
    }
    if (digits == 0) return start
    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
        var j = i + 1
        if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
        val exponentStart = j
        while (j < text.length && text[j].isDigit()) j++
        if (j > exponentStart) i = j
    }
    return i
}

/** A list of numbers separated by white space or commas, or null where one is not a number. */
private fun parseSvgNumbers(text: String): FloatArray? {
    val numbers = ArrayList<Float>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace() || c == ',') { i++; continue }
        val end = svgNumberEnd(text, i)
        if (end == i) return null
        numbers += text.substring(i, end).toFloatOrNull() ?: return null
        i = end
    }
    return numbers.toFloatArray()
}

/** A 2D affine transform: x' = a x + c y + e, y' = b x + d y + f. */
private class SvgAffine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    val isIdentity get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

    /** This after [other]: [other] applies to a point first. */
    operator fun times(other: SvgAffine) = SvgAffine(
        a * other.a + c * other.b,
        b * other.a + d * other.b,
        a * other.c + c * other.d,
        b * other.c + d * other.d,
        a * other.e + c * other.f + e,
        b * other.e + d * other.f + f,
    )

    fun inverse(): SvgAffine? {
        val determinant = a * d - b * c
        if (determinant == 0f || determinant.isNaN()) return null
        return SvgAffine(
            d / determinant,
            -b / determinant,
            -c / determinant,
            a / determinant,
            (c * f - d * e) / determinant,
            (b * e - a * f) / determinant,
        )
    }

    fun map(x: Float, y: Float) = Offset(a * x + c * y + e, b * x + d * y + f)

    fun map(rect: Rect): Rect {
        val p1 = map(rect.left, rect.top)
        val p2 = map(rect.right, rect.top)
        val p3 = map(rect.left, rect.bottom)
        val p4 = map(rect.right, rect.bottom)
        return Rect(
            min(min(p1.x, p2.x), min(p3.x, p4.x)),
            min(min(p1.y, p2.y), min(p3.y, p4.y)),
            max(max(p1.x, p2.x), max(p3.x, p4.x)),
            max(max(p1.y, p2.y), max(p3.y, p4.y)),
        )
    }

    fun toMatrix() = Matrix().also {
        it.values[Matrix.ScaleX] = a
        it.values[Matrix.SkewY] = b
        it.values[Matrix.SkewX] = c
        it.values[Matrix.ScaleY] = d
        it.values[Matrix.TranslateX] = e
        it.values[Matrix.TranslateY] = f
    }

    companion object {
        val Identity = SvgAffine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(x: Float, y: Float) = SvgAffine(1f, 0f, 0f, 1f, x, y)

        fun scale(x: Float, y: Float) = SvgAffine(x, 0f, 0f, y, 0f, 0f)

        /** The unit box mapped onto [box], which is what `objectBoundingBox` units mean. */
        fun boundingBox(box: Rect) = SvgAffine(box.width, 0f, 0f, box.height, box.left, box.top)

        /** A `transform` attribute, or null where it is not one. */
        fun parse(text: String?): SvgAffine? {
            if (text == null) return null
            var result = Identity
            var i = 0
            while (i < text.length) {
                while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++
                if (i >= text.length) break
                val open = text.indexOf('(', i)
                if (open < 0) return null
                val close = text.indexOf(')', open)
                if (close < 0) return null
                val name = text.substring(i, open).trim()
                val args = parseSvgNumbers(text.substring(open + 1, close)) ?: return null
                val step = when (name) {
                    "matrix" -> if (args.size == 6) {
                        SvgAffine(args[0], args[1], args[2], args[3], args[4], args[5])
                    } else {
                        return null
                    }
                    "translate" -> when (args.size) {
                        1 -> translate(args[0], 0f)
                        2 -> translate(args[0], args[1])
                        else -> return null
                    }
                    "scale" -> when (args.size) {
                        1 -> scale(args[0], args[0])
                        2 -> scale(args[0], args[1])
                        else -> return null
                    }
                    "rotate" -> when (args.size) {
                        1 -> rotate(args[0])
                        3 -> translate(args[1], args[2]) * rotate(args[0]) * translate(-args[1], -args[2])
                        else -> return null
                    }
                    "skewX" -> if (args.size == 1) SvgAffine(1f, 0f, tanDegrees(args[0]), 1f, 0f, 0f) else return null
                    "skewY" -> if (args.size == 1) SvgAffine(1f, tanDegrees(args[0]), 0f, 1f, 0f, 0f) else return null
                    else -> return null
                }
                result *= step
                i = close + 1
            }
            return result
        }

        private fun rotate(degrees: Float): SvgAffine {
            val radians = degrees * PI / 180.0
            val cosine = cos(radians).toFloat()
            val sine = kotlin.math.sin(radians).toFloat()
            return SvgAffine(cosine, sine, -sine, cosine, 0f, 0f)
        }

        private fun tanDegrees(degrees: Float) = tan(degrees * PI / 180.0).toFloat()
    }
}

/** `preserveAspectRatio`: how a viewBox is fitted into the viewport. */
private class SvgAspectRatio(private val alignX: Int, private val alignY: Int, private val slice: Boolean) {
    private val none get() = alignX < 0

    fun fit(viewBox: Rect, viewport: Rect): SvgAffine {
        var sx = viewport.width / viewBox.width
        var sy = viewport.height / viewBox.height
        if (!none) {
            val s = if (slice) max(sx, sy) else min(sx, sy)
            sx = s
            sy = s
        }
        var tx = viewport.left - viewBox.left * sx
        var ty = viewport.top - viewBox.top * sy
        if (!none) {
            tx += (viewport.width - viewBox.width * sx) * alignX / 2f
            ty += (viewport.height - viewBox.height * sy) * alignY / 2f
        }
        return SvgAffine(sx, 0f, 0f, sy, tx, ty)
    }

    companion object {
        private val Default = SvgAspectRatio(1, 1, false)

        fun parse(text: String?): SvgAspectRatio {
            if (text == null) return Default
            val words = text.trim().split(Regex("\\s+")).filter { it != "defer" }
            val align = words.getOrNull(0) ?: return Default
            val slice = words.getOrNull(1) == "slice"
            if (align == "none") return SvgAspectRatio(-1, -1, slice)
            if (align.length != 8 || align[0] != 'x' || align[4] != 'Y') return Default
            val x = alignment(align.substring(1, 4)) ?: return Default
            val y = alignment(align.substring(5, 8)) ?: return Default
            return SvgAspectRatio(x, y, slice)
        }

        private fun alignment(word: String) = when (word) {
            "Min" -> 0
            "Mid" -> 1
            "Max" -> 2
            else -> null
        }
    }
}

/** What `fill` or `stroke` names, before `currentColor` is known. */
private sealed interface SvgPaintValue {
    data object None : SvgPaintValue
    data object CurrentColor : SvgPaintValue
    class Solid(val color: Color) : SvgPaintValue
    class Server(val id: String, val fallback: SvgPaintValue?) : SvgPaintValue

    companion object {
        fun parse(text: String): SvgPaintValue? {
            val value = text.trim()
            if (value == "none") return None
            if (value == "currentColor") return CurrentColor
            if (value.startsWith("url(")) {
                val close = value.indexOf(')')
                if (close < 0) return null
                val reference = value.substring(4, close).trim().trim('"', '\'')
                if (!reference.startsWith("#")) return null
                val fallback = value.substring(close + 1).trim().takeIf { it.isNotEmpty() }?.let(::parse)
                return Server(reference.substring(1), fallback)
            }
            return parseSvgColor(value)?.let(::Solid)
        }
    }
}

private fun parseSvgColor(text: String): Color? {
    val value = text.trim()
    if (value.startsWith("#")) {
        val hex = value.substring(1)
        val digits = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            3 -> Color(
                expandSvgNibble(digits shr 8 and 0xF),
                expandSvgNibble(digits shr 4 and 0xF),
                expandSvgNibble(digits and 0xF),
            )
            4 -> Color(
                expandSvgNibble(digits shr 12 and 0xF),
                expandSvgNibble(digits shr 8 and 0xF),
                expandSvgNibble(digits shr 4 and 0xF),
                expandSvgNibble(digits and 0xF),
            )
            6 -> Color(0xFF000000 or digits)
            8 -> Color(((digits and 0xFF) shl 24) or (digits shr 8))
            else -> null
        }
    }
    val lower = value.lowercase()
    if (lower.startsWith("rgb(") || lower.startsWith("rgba(")) {
        val open = lower.indexOf('(')
        val close = lower.lastIndexOf(')')
        if (close < open) return null
        val parts = lower.substring(open + 1, close).split(',', ' ', '/').filter { it.isNotBlank() }
        if (parts.size != 3 && parts.size != 4) return null
        val channels = parts.take(3).map { part ->
            if (part.endsWith("%")) {
                (part.dropLast(1).toFloatOrNull() ?: return null) / 100f
            } else {
                (part.toFloatOrNull() ?: return null) / 255f
            }
        }
        val alpha = parts.getOrNull(3)?.let { part ->
            if (part.endsWith("%")) {
                (part.dropLast(1).toFloatOrNull() ?: return null) / 100f
            } else {
                part.toFloatOrNull() ?: return null
            }
        } ?: 1f
        return Color(
            channels[0].coerceIn(0f, 1f),
            channels[1].coerceIn(0f, 1f),
            channels[2].coerceIn(0f, 1f),
            alpha.coerceIn(0f, 1f),
        )
    }
    if (lower == "transparent") return Color.Transparent
    return svgNamedColours[lower]?.let { Color(0xFF000000 or it.toLong()) }
}

private fun expandSvgNibble(nibble: Long): Int = (nibble * 17).toInt()

private val svgNamedColours: Map<String, Int> by lazy {
    val table = HashMap<String, Int>()
    for (entry in SVG_NAMED_COLOURS.split(' ')) {
        val equals = entry.indexOf('=')
        table[entry.substring(0, equals)] = entry.substring(equals + 1).toInt(16)
    }
    table
}

// The CSS colour keywords, which SVG 1.1 takes as its own.
private const val SVG_NAMED_COLOURS =
    "aliceblue=f0f8ff antiquewhite=faebd7 aqua=00ffff aquamarine=7fffd4 azure=f0ffff beige=f5f5dc " +
        "bisque=ffe4c4 black=000000 blanchedalmond=ffebcd blue=0000ff blueviolet=8a2be2 brown=a52a2a " +
        "burlywood=deb887 cadetblue=5f9ea0 chartreuse=7fff00 chocolate=d2691e coral=ff7f50 " +
        "cornflowerblue=6495ed cornsilk=fff8dc crimson=dc143c cyan=00ffff darkblue=00008b " +
        "darkcyan=008b8b darkgoldenrod=b8860b darkgray=a9a9a9 darkgreen=006400 darkgrey=a9a9a9 " +
        "darkkhaki=bdb76b darkmagenta=8b008b darkolivegreen=556b2f darkorange=ff8c00 " +
        "darkorchid=9932cc darkred=8b0000 darksalmon=e9967a darkseagreen=8fbc8f " +
        "darkslateblue=483d8b darkslategray=2f4f4f darkslategrey=2f4f4f darkturquoise=00ced1 " +
        "darkviolet=9400d3 deeppink=ff1493 deepskyblue=00bfff dimgray=696969 dimgrey=696969 " +
        "dodgerblue=1e90ff firebrick=b22222 floralwhite=fffaf0 forestgreen=228b22 fuchsia=ff00ff " +
        "gainsboro=dcdcdc ghostwhite=f8f8ff gold=ffd700 goldenrod=daa520 gray=808080 green=008000 " +
        "greenyellow=adff2f grey=808080 honeydew=f0fff0 hotpink=ff69b4 indianred=cd5c5c indigo=4b0082 " +
        "ivory=fffff0 khaki=f0e68c lavender=e6e6fa lavenderblush=fff0f5 lawngreen=7cfc00 " +
        "lemonchiffon=fffacd lightblue=add8e6 lightcoral=f08080 lightcyan=e0ffff " +
        "lightgoldenrodyellow=fafad2 lightgray=d3d3d3 lightgreen=90ee90 lightgrey=d3d3d3 " +
        "lightpink=ffb6c1 lightsalmon=ffa07a lightseagreen=20b2aa lightskyblue=87cefa " +
        "lightslategray=778899 lightslategrey=778899 lightsteelblue=b0c4de lightyellow=ffffe0 " +
        "lime=00ff00 limegreen=32cd32 linen=faf0e6 magenta=ff00ff maroon=800000 " +
        "mediumaquamarine=66cdaa mediumblue=0000cd mediumorchid=ba55d3 mediumpurple=9370db " +
        "mediumseagreen=3cb371 mediumslateblue=7b68ee mediumspringgreen=00fa9a " +
        "mediumturquoise=48d1cc mediumvioletred=c71585 midnightblue=191970 mintcream=f5fffa " +
        "mistyrose=ffe4e1 moccasin=ffe4b5 navajowhite=ffdead navy=000080 oldlace=fdf5e6 olive=808000 " +
        "olivedrab=6b8e23 orange=ffa500 orangered=ff4500 orchid=da70d6 palegoldenrod=eee8aa " +
        "palegreen=98fb98 paleturquoise=afeeee palevioletred=db7093 papayawhip=ffefd5 " +
        "peachpuff=ffdab9 peru=cd853f pink=ffc0cb plum=dda0dd powderblue=b0e0e6 purple=800080 " +
        "rebeccapurple=663399 red=ff0000 rosybrown=bc8f8f royalblue=4169e1 saddlebrown=8b4513 " +
        "salmon=fa8072 sandybrown=f4a460 seagreen=2e8b57 seashell=fff5ee sienna=a0522d silver=c0c0c0 " +
        "skyblue=87ceeb slateblue=6a5acd slategray=708090 slategrey=708090 snow=fffafa " +
        "springgreen=00ff7f steelblue=4682b4 tan=d2b48c teal=008080 thistle=d8bfd8 tomato=ff6347 " +
        "turquoise=40e0d0 violet=ee82ee wheat=f5deb3 white=ffffff whitesmoke=f5f5f5 yellow=ffff00 " +
        "yellowgreen=9acd32"

// -------------------------------------------------------------------------------------------
// Styles
// -------------------------------------------------------------------------------------------

/** The inherited presentation properties, as an element sees them once its own are applied. */
private data class SvgStyle(
    val fill: SvgPaintValue = SvgPaintValue.Solid(Color.Black),
    val fillOpacity: Float = 1f,
    val fillRule: PathFillType = PathFillType.NonZero,
    val stroke: SvgPaintValue = SvgPaintValue.None,
    val strokeOpacity: Float = 1f,
    val strokeWidth: SvgLength = SvgLength(1f, SvgLengthUnit.Number),
    val strokeCap: StrokeCap = StrokeCap.Butt,
    val strokeJoin: StrokeJoin = StrokeJoin.Miter,
    val strokeMiter: Float = 4f,
    val strokeDash: List<SvgLength>? = null,
    val strokeDashOffset: SvgLength = SvgLength(0f, SvgLengthUnit.Number),
    val color: Color = Color.Black,
    val clipRule: PathFillType = PathFillType.NonZero,
    val visible: Boolean = true,
) {
    /** This style with [element]'s own declarations applied. Invalid values are ignored. */
    fun with(element: SvgElement): SvgStyle {
        var style = this
        element.property("color")?.let { value -> parseSvgColor(value)?.let { style = style.copy(color = it) } }
        element.property("fill")?.let { value -> SvgPaintValue.parse(value)?.let { style = style.copy(fill = it) } }
        element.property("stroke")?.let { value ->
            SvgPaintValue.parse(value)?.let { style = style.copy(stroke = it) }
        }
        element.property("fill-opacity")?.let { value ->
            opacity(value)?.let { style = style.copy(fillOpacity = it) }
        }
        element.property("stroke-opacity")?.let { value ->
            opacity(value)?.let { style = style.copy(strokeOpacity = it) }
        }
        element.property("fill-rule")?.let { value -> rule(value)?.let { style = style.copy(fillRule = it) } }
        element.property("clip-rule")?.let { value -> rule(value)?.let { style = style.copy(clipRule = it) } }
        element.property("stroke-width")?.let { value ->
            SvgLength.parse(value)?.takeIf { it.value >= 0f }?.let { style = style.copy(strokeWidth = it) }
        }
        element.property("stroke-linecap")?.let { value ->
            when (value.trim()) {
                "butt" -> StrokeCap.Butt
                "round" -> StrokeCap.Round
                "square" -> StrokeCap.Square
                else -> null
            }?.let { style = style.copy(strokeCap = it) }
        }
        element.property("stroke-linejoin")?.let { value ->
            when (value.trim()) {
                "miter", "miter-clip", "arcs" -> StrokeJoin.Miter
                "round" -> StrokeJoin.Round
                "bevel" -> StrokeJoin.Bevel
                else -> null
            }?.let { style = style.copy(strokeJoin = it) }
        }
        element.property("stroke-miterlimit")?.let { value ->
            value.trim().toFloatOrNull()?.takeIf { it >= 1f }?.let { style = style.copy(strokeMiter = it) }
        }
        element.property("stroke-dasharray")?.let { value ->
            if (value.trim() == "none") {
                style = style.copy(strokeDash = null)
            } else {
                val parts = value.split(',', ' ').filter { it.isNotBlank() }.map { SvgLength.parse(it) }
                if (parts.isNotEmpty() && parts.all { it != null && it.value >= 0f }) {
                    style = style.copy(strokeDash = parts.filterNotNull())
                }
            }
        }
        element.property("stroke-dashoffset")?.let { value ->
            SvgLength.parse(value)?.let { style = style.copy(strokeDashOffset = it) }
        }
        element.property("visibility")?.let { value ->
            when (value.trim()) {
                "visible" -> style = style.copy(visible = true)
                "hidden", "collapse" -> style = style.copy(visible = false)
            }
        }
        return style
    }

    private fun opacity(value: String): Float? {
        val trimmed = value.trim()
        val number = if (trimmed.endsWith("%")) {
            trimmed.dropLast(1).toFloatOrNull()?.div(100f)
        } else {
            trimmed.toFloatOrNull()
        }
        return number?.coerceIn(0f, 1f)
    }

    private fun rule(value: String) = when (value.trim()) {
        "nonzero" -> PathFillType.NonZero
        "evenodd" -> PathFillType.EvenOdd
        else -> null
    }
}

// -------------------------------------------------------------------------------------------
// The built picture
// -------------------------------------------------------------------------------------------

/** One drawn thing, with everything it needs worked out once, when the picture is built. */
private sealed class SvgNode(
    val transform: Matrix?,
    val clip: Path?,
    val layer: Paint?,
    val layerBounds: Rect,
) {
    abstract fun content(scope: DrawScope)

    fun draw(scope: DrawScope) {
        val matrix = transform
        if (matrix != null) {
            scope.withTransform({ transform(matrix) }) { clipped(this) }
        } else {
            clipped(scope)
        }
    }

    private fun clipped(scope: DrawScope) {
        val clip = clip
        if (clip != null) scope.clipPath(clip) { layered(this) } else layered(scope)
    }

    private fun layered(scope: DrawScope) {
        val layer = layer
        if (layer == null) {
            content(scope)
            return
        }
        val canvas = scope.drawContext.canvas
        canvas.saveLayer(layerBounds, layer)
        content(scope)
        canvas.restore()
    }
}

private class SvgGroup(
    val children: List<SvgNode>,
    transform: Matrix?,
    clip: Path?,
    layer: Paint?,
    layerBounds: Rect,
) : SvgNode(transform, clip, layer, layerBounds) {
    override fun content(scope: DrawScope) {
        for (child in children) child.draw(scope)
    }
}

private class SvgShape(
    val path: Path,
    val fill: SvgPainting?,
    val stroke: SvgPainting?,
    transform: Matrix?,
    clip: Path?,
    layer: Paint?,
    layerBounds: Rect,
) : SvgNode(transform, clip, layer, layerBounds) {
    override fun content(scope: DrawScope) {
        fill?.paint(scope, path)
        stroke?.paint(scope, path)
    }
}

/** How one shape is filled or stroked. */
private sealed class SvgPainting(val style: DrawStyle, val alpha: Float) {
    abstract fun paint(scope: DrawScope, path: Path)

    class Solid(val color: Color, style: DrawStyle, alpha: Float) :
        SvgPainting(style, alpha) {
        override fun paint(scope: DrawScope, path: Path) =
            scope.drawPath(path, color, alpha = alpha, style = style)
    }

    /** A gradient drawn straight onto the shape, where its own space is the shape's. */
    class Direct(val brush: Brush, style: DrawStyle, alpha: Float) :
        SvgPainting(style, alpha) {
        override fun paint(scope: DrawScope, path: Path) =
            scope.drawPath(path, brush, alpha = alpha, style = style)
    }

    /**
     * A paint server whose space is not the shape's: a gradient in bounding box units or
     * under a transform, or a pattern. The shape is drawn opaque into a layer and the
     * server's drawing is kept only where the shape is, so the result is the same whatever
     * the server's space does to angles.
     */
    class Masked(
        val bounds: Rect,
        val serverTransform: Matrix,
        val server: SvgServerDrawing,
        style: DrawStyle,
        alpha: Float,
    ) : SvgPainting(style, alpha) {
        private val isolate = Paint()
        private val keepInside = Paint().also {
            it.blendMode = BlendMode.SrcIn
            it.alpha = alpha
        }

        override fun paint(scope: DrawScope, path: Path) {
            val canvas = scope.drawContext.canvas
            canvas.saveLayer(bounds, isolate)
            scope.drawPath(path, Color.Black, style = style)
            canvas.saveLayer(bounds, keepInside)
            scope.withTransform({ transform(serverTransform) }) { server.draw(this) }
            canvas.restore()
            canvas.restore()
        }
    }
}

private sealed interface SvgServerDrawing {
    fun draw(scope: DrawScope)

    /** A gradient over [area], which is the shape's bounds in the gradient's own space. */
    class Gradient(val brush: Brush, val area: Rect) : SvgServerDrawing {
        override fun draw(scope: DrawScope) =
            scope.drawRect(brush, topLeft = area.topLeft, size = area.size)
    }

    /** A solid colour, which is what a gradient with one stop or no length draws. */
    class Flat(val color: Color, val area: Rect) : SvgServerDrawing {
        override fun draw(scope: DrawScope) =
            scope.drawRect(color, topLeft = area.topLeft, size = area.size)
    }

    /** One pattern tile, repeated over the columns and rows that cover the shape. */
    class Tiles(
        val tile: Rect,
        val columns: IntRange,
        val rows: IntRange,
        val content: Matrix?,
        val children: List<SvgNode>,
    ) : SvgServerDrawing {
        override fun draw(scope: DrawScope) {
            for (row in rows) {
                for (column in columns) {
                    val left = tile.left + column * tile.width
                    val top = tile.top + row * tile.height
                    scope.withTransform({ translate(left, top) }) {
                        clipRect(0f, 0f, tile.width, tile.height) {
                            val matrix = content
                            if (matrix != null) {
                                withTransform({ transform(matrix) }) { for (child in children) child.draw(this) }
                            } else {
                                for (child in children) child.draw(this)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class SvgPicture(val children: List<SvgNode>) {
    fun draw(scope: DrawScope) {
        for (child in children) child.draw(scope)
    }
}

// -------------------------------------------------------------------------------------------
// Building
// -------------------------------------------------------------------------------------------

private class SvgBuilt(val node: SvgNode, val bounds: Rect?)

private class SvgBuilder(private val ids: Map<String, SvgElement>, private val viewport: Size) {
    private val diagonal = sqrt((viewport.width * viewport.width + viewport.height * viewport.height) / 2f)
    private var depth = 0

    fun build(root: SvgElement): SvgPicture {
        val style = SvgStyle().with(root)
        return SvgPicture(root.children.mapNotNull { child(it, style)?.node })
    }

    private fun x(element: SvgElement, name: String, default: Float = 0f) =
        element.length(name)?.resolve(viewport.width) ?: default

    private fun y(element: SvgElement, name: String, default: Float = 0f) =
        element.length(name)?.resolve(viewport.height) ?: default

    private fun other(element: SvgElement, name: String, default: Float = 0f) =
        element.length(name)?.resolve(diagonal) ?: default

    private fun child(element: SvgElement, parentStyle: SvgStyle, extra: SvgAffine? = null): SvgBuilt? {
        if (element.property("display")?.trim() == "none") return null
        // A `use` that reaches itself, directly or through others, stops here rather than
        // building forever.
        if (depth > MAX_DEPTH) return null
        depth++
        try {
            val style = parentStyle.with(element)
            val own = SvgAffine.parse(element.attributes["transform"]) ?: SvgAffine.Identity
            val transform = if (extra != null) extra * own else own
            return when (element.name) {
                "g", "a", "switch" -> group(element, element.children, style, transform)
                "svg" -> nestedSvg(element, style, transform)
                "use" -> use(element, style, transform)
                "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" ->
                    shape(element, style, transform)
                else -> null
            }
        } finally {
            depth--
        }
    }

    private fun group(element: SvgElement, children: List<SvgElement>, style: SvgStyle, transform: SvgAffine): SvgBuilt? {
        val built = children.mapNotNull { child(it, style) }
        if (built.isEmpty()) return null
        val bounds = union(built.map { it.bounds })
        return finish(element, transform, bounds) { matrix, clip, layer, layerBounds ->
            SvgGroup(built.map { it.node }, matrix, clip, layer, layerBounds)
        }
    }

    private fun nestedSvg(element: SvgElement, style: SvgStyle, transform: SvgAffine): SvgBuilt? {
        val placed = transform * SvgAffine.translate(x(element, "x"), y(element, "y"))
        val box = element.numbers("viewBox")?.takeIf { it.size == 4 }
        if (box == null) return group(element, element.children, style, placed)
        val width = x(element, "width", viewport.width)
        val height = y(element, "height", viewport.height)
        if (box[2] <= 0f || box[3] <= 0f || width <= 0f || height <= 0f) return null
        val fitted = SvgAspectRatio.parse(element.attributes["preserveAspectRatio"]).fit(
            Rect(box[0], box[1], box[0] + box[2], box[1] + box[3]),
            Rect(0f, 0f, width, height),
        )
        return group(element, element.children, style, placed * fitted)
    }

    private fun use(element: SvgElement, style: SvgStyle, transform: SvgAffine): SvgBuilt? {
        val target = element.href()?.let(ids::get) ?: return null
        val placed = transform * SvgAffine.translate(x(element, "x"), y(element, "y"))
        if (target.name == "symbol") {
            val box = target.numbers("viewBox")?.takeIf { it.size == 4 }
            val symbolStyle = style.with(target)
            if (box == null) return group(element, target.children, symbolStyle, placed)
            val width = x(element, "width", viewport.width)
            val height = y(element, "height", viewport.height)
            if (box[2] <= 0f || box[3] <= 0f || width <= 0f || height <= 0f) return null
            val fitted = SvgAspectRatio.parse(target.attributes["preserveAspectRatio"]).fit(
                Rect(box[0], box[1], box[0] + box[2], box[1] + box[3]),
                Rect(0f, 0f, width, height),
            )
            return group(element, target.children, symbolStyle, placed * fitted)
        }
        val inner = child(target, style) ?: return null
        val bounds = inner.bounds
        return finish(element, placed, bounds) { matrix, clip, layer, layerBounds ->
            SvgGroup(listOf(inner.node), matrix, clip, layer, layerBounds)
        }
    }

    private fun shape(element: SvgElement, style: SvgStyle, transform: SvgAffine): SvgBuilt? {
        val path = geometry(element) ?: return null
        if (!style.visible) return null
        path.fillType = style.fillRule
        val bounds = path.getBounds()
        val strokeWidth = style.strokeWidth.resolve(diagonal)
        // A line has no inside, so only its stroke is drawn.
        val fillable = element.name != "line"
        val fillPaint = if (fillable) resolvePaint(style.fill, style) else null
        val strokePaint = if (strokeWidth > 0f) resolvePaint(style.stroke, style) else null
        val opacity = opacity(element)
        // An element that paints once can take its opacity into its paint. One that both
        // fills and strokes needs a layer, or the stroke would show the fill through it.
        val fold = opacity < 1f && (fillPaint == null || strokePaint == null)
        val alphaScale = if (fold) opacity else 1f
        val drawnBounds = if (strokePaint != null) bounds.inflate(strokeWidth * max(style.strokeMiter, 1f)) else bounds
        val fill = fillPaint?.let { painting(it, Fill, style.fillOpacity * alphaScale, bounds, drawnBounds) }
        val stroke = strokePaint?.let {
            painting(it, strokeStyle(style, strokeWidth), style.strokeOpacity * alphaScale, bounds, drawnBounds)
        }
        if (fill == null && stroke == null) return null
        return finish(element, transform, drawnBounds, foldedOpacity = fold) { matrix, clip, layer, layerBounds ->
            SvgShape(path, fill, stroke, matrix, clip, layer, layerBounds)
        }
    }

    /**
     * Wraps a built node in its transform, clip and opacity, and reports its bounds in its
     * parent's space.
     */
    private fun finish(
        element: SvgElement,
        transform: SvgAffine,
        bounds: Rect?,
        foldedOpacity: Boolean = false,
        make: (Matrix?, Path?, Paint?, Rect) -> SvgNode,
    ): SvgBuilt? {
        val clip = element.property("clip-path")?.let { clipPath(it, bounds) }
        if (clip === EMPTY_CLIP) return null
        val opacity = if (foldedOpacity) 1f else opacity(element)
        if (opacity <= 0f) return null
        val layer = if (opacity < 1f) Paint().also { it.alpha = opacity } else null
        val local = bounds ?: Rect.Zero
        val matrix = if (transform.isIdentity) null else transform.toMatrix()
        val node = make(matrix, clip, layer, local.inflate(1f))
        val parentBounds = bounds?.let { if (transform.isIdentity) it else transform.map(it) }
        return SvgBuilt(node, parentBounds)
    }

    private fun opacity(element: SvgElement): Float =
        element.property("opacity")?.trim()?.let {
            if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull()
        }?.coerceIn(0f, 1f) ?: 1f

    private fun clipPath(value: String, bounds: Rect?): Path? {
        val reference = SvgPaintValue.parse(value) as? SvgPaintValue.Server ?: return null
        val element = ids[reference.id]?.takeIf { it.name == "clipPath" } ?: return null
        val units = element.attributes["clipPathUnits"]?.trim()
        val unitTransform = if (units == "objectBoundingBox") {
            val box = bounds ?: return EMPTY_CLIP
            SvgAffine.boundingBox(box)
        } else {
            SvgAffine.Identity
        }
        val own = SvgAffine.parse(element.attributes["transform"]) ?: SvgAffine.Identity
        val outer = own * unitTransform
        val style = SvgStyle().with(element)
        var combined: Path? = null
        for (child in element.children) {
            val shapeElement = if (child.name == "use") child.href()?.let(ids::get) ?: continue else child
            if (child.property("display")?.trim() == "none") continue
            val childStyle = style.with(child).let { if (shapeElement !== child) it.with(shapeElement) else it }
            if (!childStyle.visible) continue
            val path = geometry(shapeElement) ?: continue
            path.fillType = childStyle.clipRule
            var childTransform = SvgAffine.parse(child.attributes["transform"]) ?: SvgAffine.Identity
            if (shapeElement !== child) {
                childTransform = childTransform * SvgAffine.translate(x(child, "x"), y(child, "y")) *
                    (SvgAffine.parse(shapeElement.attributes["transform"]) ?: SvgAffine.Identity)
            }
            val full = outer * childTransform
            if (!full.isIdentity) path.transform(full.toMatrix())
            val previous = combined
            combined = if (previous == null) {
                path
            } else {
                Path().also { it.op(previous, path, PathOperation.Union) }
            }
        }
        return combined ?: EMPTY_CLIP
    }

    private fun geometry(element: SvgElement): Path? = when (element.name) {
        "path" -> element.attributes["d"]?.let { data ->
            try {
                PathParser().parsePathString(data).toPath()
            } catch (error: IllegalArgumentException) {
                null
            } catch (error: IndexOutOfBoundsException) {
                null
            }
        }
        "rect" -> {
            val left = x(element, "x")
            val top = y(element, "y")
            val width = x(element, "width")
            val height = y(element, "height")
            if (width <= 0f || height <= 0f) {
                null
            } else {
                var rx = element.length("rx")?.resolve(viewport.width)
                var ry = element.length("ry")?.resolve(viewport.height)
                if (rx == null) rx = ry
                if (ry == null) ry = rx
                val cornerX = (rx ?: 0f).coerceIn(0f, width / 2f)
                val cornerY = (ry ?: 0f).coerceIn(0f, height / 2f)
                Path().also {
                    val box = Rect(left, top, left + width, top + height)
                    if (cornerX > 0f && cornerY > 0f) {
                        it.addRoundRect(RoundRect(box, CornerRadius(cornerX, cornerY)))
                    } else {
                        it.addRect(box)
                    }
                }
            }
        }
        "circle" -> {
            val r = other(element, "r")
            if (r <= 0f) {
                null
            } else {
                val cx = x(element, "cx")
                val cy = y(element, "cy")
                Path().also { it.addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
            }
        }
        "ellipse" -> {
            var rx = element.length("rx")?.resolve(viewport.width)
            var ry = element.length("ry")?.resolve(viewport.height)
            if (rx == null) rx = ry
            if (ry == null) ry = rx
            if (rx == null || ry == null || rx <= 0f || ry <= 0f) {
                null
            } else {
                val cx = x(element, "cx")
                val cy = y(element, "cy")
                Path().also { it.addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }
            }
        }
        "line" -> Path().also {
            it.moveTo(x(element, "x1"), y(element, "y1"))
            it.lineTo(x(element, "x2"), y(element, "y2"))
        }
        "polyline", "polygon" -> {
            val points = element.numbers("points")
            if (points == null || points.size < 4) {
                null
            } else {
                Path().also {
                    it.moveTo(points[0], points[1])
                    var i = 2
                    while (i + 1 < points.size) {
                        it.lineTo(points[i], points[i + 1])
                        i += 2
                    }
                    if (element.name == "polygon") it.close()
                }
            }
        }
        else -> null
    }

    private fun strokeStyle(style: SvgStyle, width: Float): Stroke {
        val dashes = style.strokeDash?.map { it.resolve(diagonal) }
        val effect = if (dashes != null && dashes.isNotEmpty() && dashes.any { it > 0f }) {
            val intervals = if (dashes.size % 2 == 1) dashes + dashes else dashes
            PathEffect.dashPathEffect(intervals.toFloatArray(), style.strokeDashOffset.resolve(diagonal))
        } else {
            null
        }
        return Stroke(width, style.strokeMiter, style.strokeCap, style.strokeJoin, effect)
    }

    /** What a `fill` or `stroke` value paints, with `currentColor` and fallbacks resolved. */
    private sealed interface Resolved {
        class Solid(val color: Color) : Resolved
        class Server(val element: SvgElement) : Resolved
    }

    private fun resolvePaint(value: SvgPaintValue, style: SvgStyle): Resolved? = when (value) {
        SvgPaintValue.None -> null
        SvgPaintValue.CurrentColor -> Resolved.Solid(style.color)
        is SvgPaintValue.Solid -> Resolved.Solid(value.color)
        is SvgPaintValue.Server -> {
            val element = ids[value.id]
            if (element != null && element.name in PAINT_SERVERS) {
                Resolved.Server(element)
            } else {
                value.fallback?.let { resolvePaint(it, style) }
            }
        }
    }

    private fun painting(
        paint: Resolved,
        drawStyle: DrawStyle,
        alpha: Float,
        bounds: Rect,
        drawnBounds: Rect,
    ): SvgPainting? = when (paint) {
        is Resolved.Solid -> SvgPainting.Solid(paint.color, drawStyle, alpha)
        is Resolved.Server -> when (paint.element.name) {
            "pattern" -> pattern(paint.element, drawStyle, alpha, bounds, drawnBounds)
            else -> gradient(paint.element, drawStyle, alpha, bounds, drawnBounds)
        }
    }

    // ---- gradients ----

    /** A gradient's attribute, from it or from the gradient its `href` names. */
    private fun gradientAttribute(element: SvgElement, name: String): String? {
        var current: SvgElement? = element
        var hops = 0
        while (current != null && hops < MAX_DEPTH) {
            current.attributes[name]?.let { return it }
            current = current.href()?.let(ids::get)
            hops++
        }
        return null
    }

    private fun stops(element: SvgElement): List<Pair<Float, Color>> {
        var current: SvgElement? = element
        var hops = 0
        while (current != null && hops < MAX_DEPTH) {
            val stops = current.children.filter { it.name == "stop" }
            if (stops.isNotEmpty()) {
                var last = 0f
                val gradientStyle = SvgStyle().with(current)
                return stops.map { stop ->
                    val text = stop.attributes["offset"]?.trim() ?: "0"
                    val raw = if (text.endsWith("%")) {
                        text.dropLast(1).toFloatOrNull()?.div(100f)
                    } else {
                        text.toFloatOrNull()
                    } ?: 0f
                    val offset = max(raw.coerceIn(0f, 1f), last)
                    last = offset
                    val stopStyle = gradientStyle.with(stop)
                    val colorText = stop.property("stop-color")?.trim()
                    val color = when {
                        colorText == null -> Color.Black
                        colorText == "currentColor" -> stopStyle.color
                        else -> parseSvgColor(colorText) ?: Color.Black
                    }
                    val opacity = stop.property("stop-opacity")?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
                    offset to color.copy(alpha = color.alpha * opacity)
                }
            }
            current = current.href()?.let(ids::get)
            hops++
        }
        return emptyList()
    }

    private fun gradient(
        element: SvgElement,
        drawStyle: DrawStyle,
        alpha: Float,
        bounds: Rect,
        drawnBounds: Rect,
    ): SvgPainting? {
        val stops = stops(element)
        if (stops.isEmpty()) return null
        val boundingBox = gradientAttribute(element, "gradientUnits")?.trim() != "userSpaceOnUse"
        if (boundingBox && (bounds.width <= 0f || bounds.height <= 0f)) return null
        val units = if (boundingBox) SvgAffine.boundingBox(bounds) else SvgAffine.Identity
        val own = SvgAffine.parse(gradientAttribute(element, "gradientTransform")) ?: SvgAffine.Identity
        val space = units * own
        val inverse = space.inverse() ?: return null
        val area = inverse.map(drawnBounds)
        val tileMode = when (gradientAttribute(element, "spreadMethod")?.trim()) {
            "reflect" -> TileMode.Mirror
            "repeat" -> TileMode.Repeated
            else -> TileMode.Clamp
        }
        // In bounding box units a bare number is a fraction of the box, and so is a percentage.
        fun coordinate(name: String, default: String, reference: Float): Float {
            val length = SvgLength.parse(gradientAttribute(element, name) ?: default) ?: SvgLength.parse(default)!!
            return if (boundingBox) {
                if (length.unit == SvgLengthUnit.Percent) length.value / 100f else length.value
            } else {
                length.resolve(reference)
            }
        }
        val pairs = stops.toTypedArray()
        val server: SvgServerDrawing = if (element.name == "linearGradient") {
            val start = Offset(coordinate("x1", "0%", viewport.width), coordinate("y1", "0%", viewport.height))
            val end = Offset(coordinate("x2", "100%", viewport.width), coordinate("y2", "0%", viewport.height))
            if (stops.size == 1 || start == end) {
                SvgServerDrawing.Flat(stops.last().second, area)
            } else {
                SvgServerDrawing.Gradient(Brush.linearGradient(*pairs, start = start, end = end, tileMode = tileMode), area)
            }
        } else {
            val center = Offset(coordinate("cx", "50%", viewport.width), coordinate("cy", "50%", viewport.height))
            val radius = coordinate("r", "50%", diagonal)
            if (stops.size == 1 || radius <= 0f) {
                SvgServerDrawing.Flat(stops.last().second, area)
            } else {
                SvgServerDrawing.Gradient(
                    Brush.radialGradient(*pairs, center = center, radius = radius, tileMode = tileMode),
                    area,
                )
            }
        }
        if (space.isIdentity) {
            return when (server) {
                is SvgServerDrawing.Flat -> SvgPainting.Solid(server.color, drawStyle, alpha)
                is SvgServerDrawing.Gradient -> SvgPainting.Direct(server.brush, drawStyle, alpha)
                is SvgServerDrawing.Tiles -> null
            }
        }
        return SvgPainting.Masked(drawnBounds.inflate(1f), space.toMatrix(), server, drawStyle, alpha)
    }

    // ---- patterns ----

    private fun pattern(
        element: SvgElement,
        drawStyle: DrawStyle,
        alpha: Float,
        bounds: Rect,
        drawnBounds: Rect,
    ): SvgPainting? {
        val boundingBox = element.attributes["patternUnits"]?.trim() != "userSpaceOnUse"
        val contentBox = element.attributes["patternContentUnits"]?.trim() == "objectBoundingBox"
        fun length(name: String, reference: Float): Float {
            val length = element.length(name) ?: return 0f
            return if (boundingBox) {
                val fraction = if (length.unit == SvgLengthUnit.Percent) length.value / 100f else length.value
                fraction * reference
            } else {
                length.resolve(reference)
            }
        }
        val referenceWidth = if (boundingBox) bounds.width else viewport.width
        val referenceHeight = if (boundingBox) bounds.height else viewport.height
        val width = length("width", referenceWidth)
        val height = length("height", referenceHeight)
        if (width <= 0f || height <= 0f) return null
        val left = length("x", referenceWidth) + if (boundingBox) bounds.left else 0f
        val top = length("y", referenceHeight) + if (boundingBox) bounds.top else 0f
        val tile = Rect(left, top, left + width, top + height)
        val space = SvgAffine.parse(element.attributes["patternTransform"]) ?: SvgAffine.Identity
        val inverse = space.inverse() ?: return null
        val area = inverse.map(drawnBounds)
        val columns = floor((area.left - tile.left) / width).toInt()..ceil((area.right - tile.left) / width).toInt()
        val rows = floor((area.top - tile.top) / height).toInt()..ceil((area.bottom - tile.top) / height).toInt()
        // A tile far smaller than the shape would be drawn many thousands of times a frame.
        val count = (columns.last - columns.first + 1).toLong() * (rows.last - rows.first + 1)
        if (count > MAX_TILES) return null
        val viewBox = element.numbers("viewBox")?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
        val content = when {
            viewBox != null -> SvgAspectRatio.parse(element.attributes["preserveAspectRatio"]).fit(
                Rect(viewBox[0], viewBox[1], viewBox[0] + viewBox[2], viewBox[1] + viewBox[3]),
                Rect(0f, 0f, width, height),
            )
            // The content's origin is the tile's corner, so only its scale depends on the box.
            contentBox -> SvgAffine.scale(bounds.width, bounds.height)
            else -> SvgAffine.Identity
        }
        val style = SvgStyle().with(element)
        val children = element.children.mapNotNull { child(it, style)?.node }
        if (children.isEmpty()) return null
        val server = SvgServerDrawing.Tiles(
            tile,
            columns,
            rows,
            if (content.isIdentity) null else content.toMatrix(),
            children,
        )
        return SvgPainting.Masked(drawnBounds.inflate(1f), space.toMatrix(), server, drawStyle, alpha)
    }

    private fun union(bounds: List<Rect?>): Rect? {
        var result: Rect? = null
        for (rect in bounds) {
            if (rect == null) continue
            result = result?.let {
                Rect(min(it.left, rect.left), min(it.top, rect.top), max(it.right, rect.right), max(it.bottom, rect.bottom))
            } ?: rect
        }
        return result
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_TILES = 4096L
        val PAINT_SERVERS = setOf("linearGradient", "radialGradient", "pattern")

        /** A clip that leaves nothing, so what it clips is not drawn at all. */
        val EMPTY_CLIP = Path()
    }
}

