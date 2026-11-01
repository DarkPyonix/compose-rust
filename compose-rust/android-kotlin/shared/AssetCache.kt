package dev.darkpyonix.composerust.ui.node

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.IconRole
import java.lang.InterruptedException

/**
 * What one registered asset became once the Renderer read it.
 *
 * Nothing here points back at the batch buffer. The buffer is only valid for the call that
 * carried it, and an asset has to outlive the frame that draws it, so registration is where
 * the bytes stop being borrowed: a raster is decoded into a bitmap, a vector is parsed into
 * a document, and a symbol keeps nothing but the meaning it named.
 */
sealed interface Asset {
    /** A decoded PNG or JPEG. */
    class Raster(val bitmap: ImageBitmap) : Asset

    /** A parsed vector document, drawn at whatever size the modifier chain gives it. */
    class Vector(val document: VectorDocument) : Asset

    /**
     * A face the application shipped, or a stack of them, which a type role or a font list
     * can be resolved to.
     *
     * The family and not the bytes: reading a font file is done once at registration, and
     * every piece of text in that role after it is a lookup.
     *
     * [face] is what one font file said about itself, and is null for a stack. [chain] is
     * the stack's families in the order they are tried, and is null for a single face.
     */
    class Font(
        val family: FontFamily,
        val face: Face? = null,
        val chain: FontChain? = null,
    ) : Asset {
        /**
         * One font file: the Compose font that draws it, where the platform can group
         * faces into one family by weight, the weight and slant the file declares, and
         * which letters it has a glyph for.
         */
        class Face(
            val font: androidx.compose.ui.text.font.Font?,
            val weight: Int,
            val italic: Boolean,
            val hasGlyph: (Int) -> Boolean,
        )
    }

    /**
     * A gradient or a picture laid out as a fill, ready for anywhere a colour can go.
     *
     * Read once at registration. A frame painted with it is a lookup and a draw.
     */
    class Brush(val brush: ComposeBrush) : Asset

    /**
     * One meaning out of the closed set, with no artwork attached.
     *
     * The artwork belongs to the design system: this is what lets the same registration
     * come out as an SF Symbols shape under Cupertino and a Material Symbols shape under
     * Material 3, which a picture or a system icon name could never do.
     */
    data class Symbol(val role: IconRole) : Asset
}

/**
 * The assets the Host registered, by id.
 *
 * The Host owns the lifetime: `RegisterAsset` puts one in, `ReleaseAsset` takes it out, and
 * an id that names neither is a reported protocol error rather than a guess or a crash.
 *
 * Reading is a map lookup and allocates nothing, so a frame that draws the same picture
 * again costs a lookup and the draw.
 */
class AssetCache {
    private val entries = mutableStateMapOf<Int, Asset>()

    fun asset(assetId: Int): Asset? = entries[assetId]

    /** Registered ids, for tests that check what the cache is holding. */
    internal val size: Int get() = entries.size

    /**
     * Reads the bytes and keeps the result. Returns the error to report, or null.
     *
     * `kindTag` is the raw wire tag: a kind this Renderer cannot read has to be reported
     * without stopping the rest of the batch.
     */
    internal fun register(assetId: Int, kindTag: Int, bytes: ByteArray): TableError? {
        val kind = AssetKind.entries.getOrNull(kindTag - 1)
            ?: return TableError(
                TableError.UNSUPPORTED_ASSET,
                "asset $assetId has kind tag $kindTag, which this renderer cannot read",
            )
        val asset = try {
            decode(kind, bytes)
        } catch (error: Throwable) {
            // A corrupt or truncated file is the Host's mistake and gets reported as one.
            // Letting the decoder's exception out would take the composition down with it.
            if (error is InterruptedException) throw error
            return TableError(
                TableError.UNREADABLE_ASSET,
                "asset $assetId could not be read as $kind: ${error.message ?: "unreadable"}",
            )
        }
        if (asset == null) {
            val reason = if (kind == AssetKind.VectorIcon) {
                "does not name one of the icons this renderer draws"
            } else {
                "could not be read as $kind by this platform's graphics"
            }
            return TableError(TableError.UNREADABLE_ASSET, "asset $assetId $reason")
        }
        entries[assetId] = asset
        return null
    }

    /**
     * Forgets every registration, for a Renderer that is throwing its tree away and asking
     * the Host to send the whole of it again. The ids in the new batch are the Host's to
     * assign from nothing, so keeping the old entries would leave pictures nobody names.
     */
    internal fun clear() = entries.clear()

    internal fun release(assetId: Int): TableError? {
        if (entries.remove(assetId) == null) {
            return TableError(
                TableError.UNKNOWN_ASSET,
                "ReleaseAsset for asset $assetId, which is not registered",
            )
        }
        return null
    }

    private fun decode(kind: AssetKind, bytes: ByteArray): Asset? = when (kind) {
        AssetKind.Png, AssetKind.Jpeg -> decodeRasterAsset(bytes)?.let(Asset::Raster)

        AssetKind.Svg -> decodeVectorAsset(bytes)?.let(Asset::Vector)

        // A stack names faces registered before it, so like an image brush it is read
        // against what this cache already holds. Anything else is a font file.
        AssetKind.Font -> if (isFontStack(bytes)) {
            decodeFontStack(bytes, entries::get)
        } else {
            decodeFontAsset(bytes)
        }

        // An image brush names a picture registered in its own right, so the lookup is
        // into what this cache is already holding. A brush registered before the picture
        // it names is unreadable and says so, rather than drawing nothing quietly.
        AssetKind.Brush -> decodeBrushAsset(bytes, entries::get)?.let(Asset::Brush)

        // A vector icon registers a meaning, so its bytes are the role tag and nothing
        // else. There is no artwork on the wire and no icon name to look up at run time.
        AssetKind.VectorIcon -> iconRole(bytes)?.let(Asset::Symbol)
    }

    private fun iconRole(bytes: ByteArray): IconRole? {
        if (bytes.size < 2) return null
        val tag = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        return IconRole.entries.getOrNull(tag - 1)
    }
}

/**
 * Families tried a letter at a time, the way CSS reads a `font-family` list.
 *
 * Text is set in the first family, and each letter that family has no glyph for is set in
 * the next family that has one. A family whose letters are not known here (an installed or
 * a generic family) takes every letter that reaches it, and ends the chain. Letters that no
 * family has are left to the platform's own fallback, which is what happened to every
 * letter before the chain existed.
 *
 * Compose sets a run of text in one family and asks the platform for a letter it lacks,
 * and the platform answers from the machine's fonts before anything the application
 * registered. So the chain is applied as spans: [spans] says which stretches of a text go
 * to a later family, and every place that lays text out in a chained family adds them.
 */
class FontChain(val members: List<Member>) {
    init {
        require(members.isNotEmpty()) { "a font chain has at least one family" }
    }

    /** One family of the chain, and which letters it has, or null where that is unknown. */
    class Member(val family: FontFamily, hasGlyph: ((Int) -> Boolean)?) {
        private val ask = hasGlyph
        private val known = HashMap<Int, Boolean>()

        fun has(codePoint: Int): Boolean {
            val ask = ask ?: return true
            return known.getOrPut(codePoint) { ask(codePoint) }
        }
    }

    /** The family text in this chain is set in. */
    val primary: FontFamily get() = members.first().family

    /**
     * The stretches of [text] whose letters come from a family after the first, each with
     * the span style that sets them there. Empty where the first family has every letter,
     * which is the usual case and costs a lookup per letter.
     *
     * A letter that changes how the one before it is drawn (a combining mark, a joiner, a
     * variation selector) stays with that letter, so a syllable is never split between two
     * faces.
     */
    fun spans(text: String): List<AnnotatedString.Range<SpanStyle>> {
        if (members.size < 2 || text.isEmpty()) return emptyList()
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var runStart = 0
        var runMember = 0
        var index = 0
        while (index < text.length) {
            val codePoint = codePointAt(text, index)
            val width = if (codePoint > 0xFFFF) 2 else 1
            val member = if (index > 0 && attaches(codePoint)) runMember else memberFor(codePoint)
            if (member != runMember) {
                if (runMember != 0) out += range(runMember, runStart, index)
                runStart = index
                runMember = member
            }
            index += width
        }
        if (runMember != 0) out += range(runMember, runStart, text.length)
        return out
    }

    private fun memberFor(codePoint: Int): Int {
        if (codePoint < 0x20 || members[0].has(codePoint)) return 0
        for (at in 1 until members.size) {
            if (members[at].has(codePoint)) return at
        }
        return 0
    }

    private fun range(member: Int, start: Int, end: Int) =
        AnnotatedString.Range(SpanStyle(fontFamily = members[member].family), start, end)
}

/** The code point at [index], joining a surrogate pair. */
private fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }
    return high.code
}

/** A letter drawn as part of the one before it rather than on its own. */
private fun attaches(codePoint: Int): Boolean =
    codePoint in 0x0300..0x036F || // combining diacritical marks
        codePoint in 0x1AB0..0x1AFF ||
        codePoint in 0x1DC0..0x1DFF ||
        codePoint in 0x20D0..0x20FF ||
        codePoint in 0xFE20..0xFE2F ||
        codePoint in 0xFE00..0xFE0F || // variation selectors
        codePoint in 0xE0100..0xE01EF ||
        codePoint == 0x200C || codePoint == 0x200D || // joiners
        codePoint == 0x2060 || // word joiner, which keep-all puts between letters
        codePoint == 0x200B || // zero width space, which break-all puts between letters
        codePoint in 0x1160..0x11FF || // conjoining jamo after the first
        codePoint in 0xD7B0..0xD7FF ||
        codePoint in 0x1F3FB..0x1F3FF // skin tone modifiers

/** The magic a font stack's bytes start with, which no font file starts with. */
private val FONT_STACK_MAGIC = byteArrayOf('C'.code.toByte(), 'R'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte())
private const val FONT_STACK_VERSION = 1
private const val MAX_FONT_STACK_FAMILIES = 8
private const val MAX_FONT_STACK_FACES = 18

internal fun isFontStack(bytes: ByteArray): Boolean =
    bytes.size >= 4 && (0 until 4).all { bytes[it] == FONT_STACK_MAGIC[it] }

/**
 * Reads a font stack: the families of faces registered before it, in the order they are
 * tried.
 *
 * A stack that names an id which is not a registered face is refused whole, with the id in
 * the report, rather than drawn with a family missing: a screen that quietly lost its
 * Korean face is the mistake this is here to make visible. The roles that named the stack
 * then stay on the machine's own face.
 */
internal fun decodeFontStack(bytes: ByteArray, lookup: (Int) -> Asset?): Asset.Font {
    fun word(at: Int): Int {
        require(at + 4 <= bytes.size) { "the font stack ends early" }
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
    require(word(4) == FONT_STACK_VERSION) { "font stack version ${word(4)} is not one this renderer reads" }
    val count = word(8)
    require(count in 1..MAX_FONT_STACK_FAMILIES) { "a font stack holds 1 to $MAX_FONT_STACK_FAMILIES families, not $count" }
    var at = 12
    val members = ArrayList<FontChain.Member>(count)
    repeat(count) {
        val faces = word(at)
        require(faces in 1..MAX_FONT_STACK_FACES) { "a font stack family holds 1 to $MAX_FONT_STACK_FACES faces, not $faces" }
        at += 4
        val fonts = ArrayList<Asset.Font>(faces)
        repeat(faces) {
            val id = word(at)
            at += 4
            val font = lookup(id) as? Asset.Font
            require(font?.face != null) { "the font stack names asset $id, which is not a registered font file" }
            fonts += font!!
        }
        members += stackMember(fonts)
    }
    require(at == bytes.size) { "the font stack has ${bytes.size - at} bytes after its last family" }
    // Where the platform groups faces, the chain's first family is made here and is a family
    // no other text is set in: that is what lets the theme tell text in this stack from
    // text in its first face.
    return Asset.Font(family = members.first().family, chain = FontChain(members))
}

/**
 * One family of a stack: its faces as one family the platform picks a weight from, where
 * the platform can, and the letters any of them has.
 */
private fun stackMember(fonts: List<Asset.Font>): FontChain.Member {
    val faces = fonts.map { it.face!! }
    val composeFonts = faces.mapNotNull { it.font }
    val family = if (composeFonts.size == faces.size) {
        FontFamily(composeFonts)
    } else {
        // A platform that cannot group faces by weight sets the family in its regular
        // upright face, the one closest to weight 400.
        fonts.minBy { font ->
            val face = font.face!!
            kotlin.math.abs(face.weight - 400) + if (face.italic) 1000 else 0
        }.family
    }
    return FontChain.Member(family) { codePoint -> faces.any { it.hasGlyph(codePoint) } }
}
