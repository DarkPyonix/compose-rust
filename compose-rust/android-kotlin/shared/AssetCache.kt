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
         * One font file: the Compose font that draws it, the weight and slant the file
         * declares, and which letters it has a glyph for.
         *
         * The Compose font is what lets a stack group a typeface's files into one family
         * Compose picks a weight from. Every platform gives one, so bold text in a family
         * that ships a bold file is set in that file.
         */
        class Face(
            val font: androidx.compose.ui.text.font.Font,
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

/**
 * The normal track of a font's `trak` table: how far Apple's text system moves every
 * letter of the font apart, or together, at each size.
 *
 * Apple's own faces (SF, SF Mono, New York) carry this table, and CoreText applies it to
 * every advance it hands out, so every AppKit line and every browser on a Mac sets them
 * tracked. Skia's shaper takes its advances from the font's horizontal metrics and leaves
 * the table alone, so text set here in the same face came out wider than the same text
 * anywhere else on the machine: at 16px, SF is set 0.31px tighter per letter by the
 * table, which is 15px over a 48 letter line.
 *
 * The table gives a value in font units at a list of sizes. Between two sizes the value is
 * interpolated in a straight line, and outside the list the nearest size's value holds,
 * which is what CoreText does (measured at 4, 5, 7.5, 16.5, 150 and 200 points on macOS 26).
 */
internal class TrackTable private constructor(
    private val sizes: FloatArray,
    private val values: FloatArray,
    private val unitsPerEm: Int,
) {
    /**
     * What the table adds after every letter at [size] points, as a fraction of the size:
     * the letter spacing in em.
     */
    fun em(size: Float): Float {
        val last = sizes.size - 1
        val units = when {
            last == 0 || size <= sizes[0] -> values[0]
            size >= sizes[last] -> values[last]
            else -> {
                var upper = 1
                while (sizes[upper] < size) upper++
                val from = sizes[upper - 1]
                val to = sizes[upper]
                values[upper - 1] + (size - from) / (to - from) * (values[upper] - values[upper - 1])
            }
        }
        return units / unitsPerEm
    }

    companion object {
        private const val VERSION_1 = 0x00010000
        private const val HEADER_LENGTH = 12
        private const val TRACK_DATA_LENGTH = 8
        private const val TRACK_ENTRY_LENGTH = 8

        /**
         * Reads the normal track (track value 0) of a `trak` table, or null where the bytes
         * are not a table this reads, the table has no horizontal track data, there is no
         * normal track, or the normal track is zero at every size and so changes nothing.
         *
         * The layout is Apple's: a 12 byte header (version 1.0, format 0, the offset of the
         * horizontal track data, the offset of the vertical, a reserved word), then at that
         * offset the count of tracks, the count of sizes and the offset of the size table,
         * then one 8 byte entry per track (its value as 16.16 fixed point, a name index, and
         * the offset of its per-size values). The sizes are 16.16 fixed point and the values
         * signed 16 bit font units. Every offset counts from the start of the table.
         */
        fun parse(table: ByteArray, unitsPerEm: Int): TrackTable? {
            if (unitsPerEm <= 0 || table.size < HEADER_LENGTH) return null
            fun u16(at: Int): Int = ((table[at].toInt() and 0xFF) shl 8) or (table[at + 1].toInt() and 0xFF)
            fun i32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)
            fun fits(at: Int, length: Int): Boolean = at >= 0 && length >= 0 && at.toLong() + length <= table.size
            if (i32(0) != VERSION_1 || u16(4) != 0) return null
            val horizontal = u16(6)
            if (horizontal == 0 || !fits(horizontal, TRACK_DATA_LENGTH)) return null
            val tracks = u16(horizontal)
            val count = u16(horizontal + 2)
            val sizeTable = i32(horizontal + 4)
            if (count == 0 || !fits(horizontal + TRACK_DATA_LENGTH, tracks * TRACK_ENTRY_LENGTH)) return null
            if (!fits(sizeTable, count * 4)) return null
            var normal = -1
            for (track in 0 until tracks) {
                val entry = horizontal + TRACK_DATA_LENGTH + track * TRACK_ENTRY_LENGTH
                if (i32(entry) == 0) {
                    normal = u16(entry + 6)
                    break
                }
            }
            if (normal < 0 || !fits(normal, count * 2)) return null
            val sizes = FloatArray(count) { i32(sizeTable + it * 4) / 65536f }
            for (at in 1 until count) if (sizes[at] <= sizes[at - 1]) return null
            val values = FloatArray(count) { u16(normal + it * 2).toShort().toFloat() }
            if (values.all { it == 0f }) return null
            return TrackTable(sizes, values, unitsPerEm)
        }
    }
}

/**
 * The tracking of the face text is set in, and which letters that face has.
 *
 * A letter the face has no glyph for is drawn from a fallback face, which is not tracked by
 * this face's table: CoreText tracks each face by its own table, and the faces it falls back
 * to for Hangul, kana or ideographs have none. [untracked] finds those letters so they can
 * be set without this face's tracking.
 */
internal class FontTracking(val table: TrackTable, private val hasGlyph: (Int) -> Boolean) {
    /** 0 for unknown, 1 for has a glyph, 2 for has none, for the first 256 code points. */
    private val latin = ByteArray(256)
    private val known = HashMap<Int, Boolean>()

    fun has(codePoint: Int): Boolean {
        if (codePoint < 0x20) return true
        if (codePoint < latin.size) {
            if (latin[codePoint] == 0.toByte()) latin[codePoint] = if (hasGlyph(codePoint)) 1 else 2
            return latin[codePoint] == 1.toByte()
        }
        return known.getOrPut(codePoint) { hasGlyph(codePoint) }
    }

    /**
     * The stretches of [text] whose letters this face has no glyph for, as start and end
     * pairs, flattened. Empty where the face has every letter, which is the usual case.
     * A letter that is drawn as part of the one before it stays with that letter.
     */
    fun untracked(text: String): IntArray {
        var out: IntArray? = null
        var count = 0
        var start = -1
        var index = 0
        var previousMissing = false
        while (index < text.length) {
            val codePoint = codePointAt(text, index)
            val width = if (codePoint > 0xFFFF) 2 else 1
            val missing = if (index > 0 && attaches(codePoint)) previousMissing else !has(codePoint)
            if (missing && start < 0) start = index
            if (!missing && start >= 0) {
                out = append(out, count, start, index)
                count += 2
                start = -1
            }
            previousMissing = missing
            index += width
        }
        if (start >= 0) {
            out = append(out, count, start, text.length)
            count += 2
        }
        return if (out == null) NONE else out.copyOf(count)
    }

    private fun append(out: IntArray?, count: Int, start: Int, end: Int): IntArray {
        val grown = if (out == null) IntArray(8) else if (count + 2 > out.size) out.copyOf(out.size * 2) else out
        grown[count] = start
        grown[count + 1] = end
        return grown
    }

    private companion object {
        val NONE = IntArray(0)
    }
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
    // The chain's first family is made here and is a family no other text is set in: that
    // is what lets the theme tell text in this stack from text in its first face.
    return Asset.Font(family = members.first().family, chain = FontChain(members))
}

/**
 * One family of a stack: its faces as one family Compose picks a weight from, and the
 * letters any of them has.
 */
private fun stackMember(fonts: List<Asset.Font>): FontChain.Member {
    val faces = fonts.map { it.face!! }
    val family = FontFamily(faces.map { it.font })
    return FontChain.Member(family) { codePoint -> faces.any { it.hasGlyph(codePoint) } }
}

/** The weight, 1 to 1000, and the slant a font file declares for itself. */
data class DeclaredFaceStyle(val weight: Int, val italic: Boolean)

/**
 * The weight and slant a font file declares in its `OS/2` table, or null where the bytes
 * are not a font that has one.
 *
 * Read from the bytes rather than asked of the platform's loader, because not every loader
 * can say. Android below API 28 answers only bold or not, so a medium and a semibold face
 * read as regular and bold, and two faces of one family claim the same weight.
 *
 * A collection (`ttcf`) is read by its first face, the one a loader opens when it is
 * handed the file.
 */
internal fun declaredFaceStyle(bytes: ByteArray): DeclaredFaceStyle? {
    fun u16(at: Int): Int? = if (at < 0 || at + 2 > bytes.size) {
        null
    } else {
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }
    fun u32(at: Int): Int? {
        val high = u16(at) ?: return null
        val low = u16(at + 2) ?: return null
        return (high shl 16) or low
    }
    fun tag(at: Int, name: String): Boolean =
        at + 4 <= bytes.size && (0 until 4).all { bytes[at + it] == name[it].code.toByte() }

    val start = if (tag(0, "ttcf")) u32(12) ?: return null else 0
    val tables = u16(start + 4) ?: return null
    for (index in 0 until tables) {
        val record = start + 12 + 16 * index
        if (!tag(record, "OS/2")) continue
        val table = u32(record + 8) ?: return null
        val weight = u16(table + 4) ?: return null
        val selection = u16(table + 62) ?: return null
        // Bit 0 says italic and bit 9 oblique; a slanted face is set for italic text either way.
        val italic = selection and 0x0201 != 0
        return DeclaredFaceStyle(weight.coerceIn(1, 1000), italic)
    }
    return null
}
