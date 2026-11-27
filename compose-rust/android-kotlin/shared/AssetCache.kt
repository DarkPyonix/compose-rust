package dev.darkpyonix.composerust.ui.node

import androidx.compose.runtime.mutableStateListOf
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
         * The families this font's letters are looked for in: the stack, or for one font
         * file, that file alone with the letters it has. Null where the letters are not
         * known.
         *
         * A single face needs no chain where the machine finds a letter the face lacks
         * among its own fonts. Where the renderer carries the faces a letter falls back to
         * ([platformFallbackFamilies]), the chain is what says which letters go to them.
         * Made once, because the places that lay text out keep work per chain.
         */
        val letters: FontChain? by lazy(LazyThreadSafetyMode.NONE) {
            chain ?: face?.let { FontChain(listOf(FontChain.Member(family, it.hasGlyph))) }
        }

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
            val fonts: List<androidx.compose.ui.text.font.Font> = listOf(font),
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

    /** Ids whose bytes were asked of the Host since they were last let go of. */
    private val requested = HashSet<Int>()

    /** Ids asked for and not yet sent to the Host, oldest first. */
    private val wanted = ArrayList<Int>()

    /**
     * Ids a text waits for with no face of its font to draw in: the first family of the font
     * it is set in has not arrived (FR-48.4 rule 7). State, because the window's drawing reads
     * it: while any is here the window keeps the picture it drew last.
     */
    private val holding = mutableStateListOf<Int>()

    /** Whether a text is waiting for a font it has no face of, so the window holds its picture. */
    val holdingFrame: Boolean get() = holding.isNotEmpty()

    /** Stops waiting for every font asked for so far: they took too long, and the letters are drawn as missing glyphs. */
    internal fun endHold() {
        holding.clear()
    }

    /** Stacks with a family whose bytes are still on the Host, by id, made again as they arrive. */
    private val lazyStacks = HashMap<Int, LazyStack>()

    private class LazyStack(val bytes: ByteArray, val faces: IntArray)

    /**
     * How a request for bytes asks for the frame that sends it. The composition installs the
     * frame source it serves here. A request made before there was one is sent as soon as
     * there is.
     */
    var wake: () -> Unit = {}
        set(value) {
            field = value
            if (wanted.isNotEmpty()) value()
        }

    /**
     * Asks the Host for the bytes of [assetId], which a font list or a stack named as a font
     * the Host has not sent: once per id until it is let go of. The request goes out at the
     * top of the next frame ([drainWanted]), never from inside the composition or the measure
     * call that found the letter, because the Host answers with a batch of its own.
     */
    internal fun want(assetId: Int, hold: Boolean = false) {
        if (hold && !entries.containsKey(assetId) && assetId !in holding && assetId !in heldOnce) {
            holding += assetId
            heldOnce += assetId
        }
        if (entries.containsKey(assetId) || !requested.add(assetId)) return
        wanted += assetId
        wake()
    }

    /** Hands every id asked for since the last call to [send], oldest first. */
    internal fun drainWanted(send: (Int) -> Unit) {
        while (wanted.isNotEmpty()) send(wanted.removeAt(0))
    }

    /** Ids the window has already waited for: a font that took too long is not waited for again. */
    private val heldOnce = HashSet<Int>()

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
        decodeWarning = null
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
        holding.remove(assetId)
        if (kind == AssetKind.Font) {
            if (isFontStack(bytes)) {
                val faces = fontStackLazyFaces(bytes)
                if (faces.isEmpty()) lazyStacks.remove(assetId) else lazyStacks[assetId] = LazyStack(bytes, faces)
            } else {
                remakeStacksNaming(assetId)
            }
        }
        // Kept and drawn, but with a field the renderer could not honour, which the Host
        // is told about.
        val warning = decodeWarning ?: return null
        decodeWarning = null
        return TableError(TableError.UNREADABLE_ASSET, "asset $assetId: $warning")
    }

    // What the decoder could not honour in an asset it still read, for the registration
    // that is under way. A brush whose ellipse cannot be drawn is drawn as a circle.
    private var decodeWarning: String? = null

    /**
     * Forgets every registration, for a Renderer that is throwing its tree away and asking
     * the Host to send the whole of it again. The ids in the new batch are the Host's to
     * assign from nothing, so keeping the old entries would leave pictures nobody names.
     */
    internal fun clear() {
        entries.clear()
        requested.clear()
        wanted.clear()
        lazyStacks.clear()
        holding.clear()
        heldOnce.clear()
    }

    internal fun release(assetId: Int): TableError? {
        if (entries.remove(assetId) == null) {
            return TableError(
                TableError.UNKNOWN_ASSET,
                "ReleaseAsset for asset $assetId, which is not registered",
            )
        }
        // A face let go of is asked for again the next time a letter needs it.
        requested.remove(assetId)
        heldOnce.remove(assetId)
        lazyStacks.remove(assetId)
        remakeStacksNaming(assetId)
        return null
    }

    /**
     * Reads again every stack that names [faceId] as a face the Host had not sent, now that
     * it has arrived or been let go of. The stack is a new font with a new chain, so
     * everything set in it is measured and drawn again with the family as it now is.
     */
    private fun remakeStacksNaming(faceId: Int) {
        for ((stackId, stack) in lazyStacks.entries.toList()) {
            if (faceId !in stack.faces) continue
            val remade = try {
                decodeFontStack(stack.bytes, entries::get, ::want)
            } catch (refused: IllegalArgumentException) {
                // The id became something that is not a face. The stack stays as it was.
                continue
            }
            entries[stackId] = remade
        }
    }

    private fun decode(kind: AssetKind, bytes: ByteArray): Asset? = when (kind) {
        AssetKind.Png, AssetKind.Jpeg -> decodeRasterAsset(bytes)?.let(Asset::Raster)

        AssetKind.Svg -> decodeVectorAsset(bytes)?.let(Asset::Vector)

        // A stack names faces registered before it, so like an image brush it is read
        // against what this cache already holds. Anything else is a font file.
        AssetKind.Font -> if (isFontStack(bytes)) {
            decodeFontStack(bytes, entries::get, ::want)
        } else {
            decodeFontAsset(bytes)
        }

        // An image brush names a picture registered in its own right, so the lookup is
        // into what this cache is already holding. A brush registered before the picture
        // it names is unreadable and says so, rather than drawing nothing quietly.
        AssetKind.Brush -> decodeBrushAsset(bytes, { why -> decodeWarning = why }, entries::get)
            ?.let(Asset::Brush)

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
 * family has are looked for in [platformFallbackFamilies], and after those are left to the
 * platform's own fallback, which is what happened to every letter before the chain existed.
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

    /** Whether the first family is one whose bytes have not arrived. */
    private val firstPending = members[0].pending != null

    /** Whether any family is. */
    private val anyPending = members.any { it.pending != null }

    /**
     * One family of the chain, and which letters it has, or null where that is unknown.
     *
     * [pending] is set for a family whose bytes are still on the Host: a `LazyAsset` of a
     * font list, or a lazy family of a stack, before they arrive. It takes every letter that
     * reaches it, as a family whose letters are unknown does, so no letter falls past it to
     * a later family or the machine's own fonts. Those letters are drawn as missing glyphs
     * ([MissingGlyphFaces]) and the bytes are asked for.
     */
    class Member(
        val family: FontFamily,
        hasGlyph: ((Int) -> Boolean)?,
        val pending: PendingFace? = null,
    ) {
        private val ask = hasGlyph
        private val known = HashMap<Int, Boolean>()

        /** The answers for ASCII, which is most of what is asked: 0 not asked, 1 has, 2 lacks. */
        private val ascii = ByteArray(128)

        fun has(codePoint: Int): Boolean {
            val ask = ask ?: return true
            if (codePoint in ascii.indices) {
                if (ascii[codePoint] == 0.toByte()) ascii[codePoint] = if (ask(codePoint)) 1 else 2
                return ascii[codePoint] == 1.toByte()
            }
            return known.getOrPut(codePoint) { ask(codePoint) }
        }
    }

    /** The family text in this chain is set in. */
    val primary: FontFamily get() = members.first().family

    /**
     * The stretches of [text] whose letters come from a family after the first, or from
     * one of [platformFallbackFamilies], each with the span style that sets them there.
     * Empty where the first family has every letter, which is the usual case and costs a
     * lookup per letter.
     *
     * A letter that changes how the one before it is drawn (a combining mark, a joiner, a
     * variation selector) stays with that letter, so a syllable is never split between two
     * faces.
     */
    fun spans(text: String): List<AnnotatedString.Range<SpanStyle>> {
        val fallback = platformFallbackFamilies
        if (text.isEmpty() || (members.size + fallback.size < 2 && !anyPending)) return emptyList()
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var runStart = 0
        var runMember = UNSET
        var index = 0
        while (index < text.length) {
            val codePoint = codePointAt(text, index)
            val width = if (codePoint > 0xFFFF) 2 else 1
            val member = if (index > 0 && attaches(codePoint)) runMember else memberFor(codePoint, fallback)
            if (member != runMember) {
                if (spanned(runMember)) out += range(runMember, runStart, index, fallback, text)
                runStart = index
                runMember = member
            }
            index += width
        }
        if (spanned(runMember)) out += range(runMember, runStart, text.length, fallback, text)
        return out
    }

    /**
     * Whether letters drawn by [member] need a span: every family after the first, and the
     * first as well while its bytes have not arrived, because its letters are drawn as
     * missing glyphs and not in the family the text is set in.
     */
    private fun spanned(member: Int): Boolean = member > 0 || (member == 0 && firstPending)

    /**
     * Which family draws [codePoint]: an index into [members], or past their end, into
     * [fallback]. Zero, the first family, where nothing has the letter. [UNSET] for a
     * control character, which no family is asked about and no span covers.
     */
    private fun memberFor(codePoint: Int, fallback: List<Member>): Int {
        if (codePoint < 0x20) return UNSET
        if (members[0].has(codePoint)) return 0
        for (at in 1 until members.size) {
            if (members[at].has(codePoint)) return at
        }
        for (at in fallback.indices) {
            if (fallback[at].has(codePoint)) return members.size + at
        }
        return 0
    }

    private fun range(
        member: Int,
        start: Int,
        end: Int,
        fallback: List<Member>,
        text: String,
    ): AnnotatedString.Range<SpanStyle> {
        val drawn = if (member < members.size) members[member] else fallback[member - members.size]
        val pending = drawn.pending
        if (pending != null) {
            // A letter reached a family whose bytes are not here. It is not handed on to a
            // later family or to the machine: it is drawn as a missing glyph until they arrive.
            // A letter of the first family has no face at all to be drawn in: the window
            // waits a moment for it rather than show boxes (FR-48.4 rule 7).
            pending.request(hold = member == 0)
            val family = MissingGlyphFaces.family(codePointsIn(text, start, end)) ?: drawn.family
            return AnnotatedString.Range(SpanStyle(fontFamily = family), start, end)
        }
        return AnnotatedString.Range(SpanStyle(fontFamily = drawn.family), start, end)
    }

    private companion object {
        /** No family: what a control character and the start of a text are. */
        const val UNSET = -1
    }
}

/**
 * A family of a chain whose bytes are still on the Host: the ids to ask for, and how.
 *
 * Asking is cheap to repeat: the cache asks the Host once per id ([AssetCache.want]), so
 * every text that reaches the family in a frame asks, and one request goes out.
 */
class PendingFace(
    val assetIds: IntArray,
    private val want: (Int, Boolean) -> Unit,
) {
    /** Asks for the bytes; [hold] when a text has no other face to draw these letters in. */
    fun request(hold: Boolean = false) {
        for (id in assetIds) want(id, hold)
    }
}

/** The distinct code points of [text] from [start] to [end], in order. */
private fun codePointsIn(text: String, start: Int, end: Int): IntArray {
    val seen = ArrayList<Int>(end - start)
    var index = start
    while (index < end) {
        val codePoint = codePointAt(text, index)
        seen += codePoint
        index += if (codePoint > 0xFFFF) 2 else 1
    }
    return seen.distinct().sorted().toIntArray()
}

/**
 * Faces that draw each letter they are made for as a missing glyph: an outlined box, a
 * blank for white space, and nothing at all for a mark or an invisible format character.
 *
 * A letter whose family is still on the Host has to be drawn somehow for the frame before
 * its bytes arrive, and it must not be drawn in a face of the machine's own: that is the
 * screen in the wrong typeface the application shipped its fonts to avoid. A family that
 * lacks the letter is no answer, because the platform then fills the letter in from the
 * machine's fonts. So the box comes from a face that has the letter, and only that one.
 *
 * Each face is a few hundred bytes, built here for the letters one span needs and kept
 * for the next span that needs the same ones. Measuring and drawing a text ask for the same
 * letters and get the same face, so they agree on the width the boxes take.
 */
internal object MissingGlyphFaces {
    /** The face's units, and how wide its box is in them: three fifths of an em. */
    const val UNITS_PER_EM = 1000
    const val ADVANCE = 600

    /** How wide its blank for white space is: a quarter of an em. */
    const val SPACE_ADVANCE = 250

    /** The faces kept, by the letters they were made for. */
    private const val KEPT = 64
    private val made = LinkedHashMap<String, FontFamily>()
    private var base: FontFamily? = null

    /**
     * The family of a font list whose first candidate has not arrived: a face of this kind
     * made for the space alone, so the text has a family with no letters of the machine's in
     * it. Every letter of that text is drawn from [family] by a span.
     */
    val empty: FontFamily
        get() = base ?: (fresh() ?: FontFamily.Default).also { base = it }

    /**
     * A family of its own, for a stack whose first family has not arrived: a theme finds a
     * stack by the identity of its first family, so two stacks must not share one.
     */
    fun fresh(): FontFamily? = decode(intArrayOf(SPACE))

    /** A face for [codePoints], sorted and distinct, or null where the platform could not read one. */
    fun family(codePoints: IntArray): FontFamily? {
        val key = codePoints.joinToString(",")
        made[key]?.let { return it }
        val family = decode(codePoints) ?: return null
        if (made.size >= KEPT) made.clear()
        made[key] = family
        return family
    }

    private fun decode(codePoints: IntArray): FontFamily? = try {
        decodeFontAsset(missingGlyphFont(codePoints))?.family
    } catch (failure: RuntimeException) {
        null
    }

    private const val SPACE = 0x20
}

/** Glyph ids of a missing glyph face: `.notdef`, the box, the blank, and nothing. */
private const val GLYPH_BOX = 1
private const val GLYPH_BLANK = 2
private const val GLYPH_NONE = 3
private const val MISSING_GLYPHS = 4

/** The most letters a missing glyph face maps through its format 4 table as well. */
private const val FORMAT_4_LIMIT = 8000

/**
 * A TrueType font that maps each of [codePoints] (sorted, distinct) to a box, to a blank for
 * white space, or to nothing with no advance for a mark or an invisible format character.
 *
 * The `cmap` table has a format 12 subtable for every code point and a format 4 one for those
 * in the Basic Multilingual Plane, the two every platform's font loader reads.
 */
internal fun missingGlyphFont(codePoints: IntArray): ByteArray {
    val glyphs = IntArray(codePoints.size) { missingGlyphFor(codePoints[it]) }
    val tables = listOf(
        "OS/2" to missingOs2(codePoints),
        "cmap" to missingCmap(codePoints, glyphs),
        "glyf" to missingGlyf(),
        "head" to missingHead(),
        "hhea" to missingHhea(),
        "hmtx" to missingHmtx(),
        "loca" to missingLoca(),
        "maxp" to missingMaxp(),
        "name" to missingName(),
        "post" to missingPost(),
    )
    val count = tables.size
    var power = 1
    var selector = 0
    while (power * 2 <= count) {
        power *= 2
        selector++
    }
    val directory = 12 + 16 * count
    val total = directory + tables.sumOf { padded(it.second.size) }
    return fontTable(total) {
        u32(0x00010000)
        u16(count)
        u16(power * 16)
        u16(selector)
        u16(count * 16 - power * 16)
        var offset = directory
        for ((name, data) in tables) {
            tag(name)
            u32(checksum(data))
            u32(offset)
            u32(data.size)
            offset += padded(data.size)
        }
        for ((_, data) in tables) {
            bytes(data)
            repeat(padded(data.size) - data.size) { u8(0) }
        }
    }
}

private fun missingGlyphFor(codePoint: Int): Int = when {
    attaches(codePoint) || invisible(codePoint) -> GLYPH_NONE
    codePoint <= 0xFFFF && Char(codePoint).isWhitespace() -> GLYPH_BLANK
    else -> GLYPH_BOX
}

/** A default ignorable code point: drawn as nothing by any face. */
private fun invisible(codePoint: Int): Boolean =
    codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x061C ||
        codePoint == 0x115F || codePoint == 0x3164 || codePoint == 0xFEFF || codePoint == 0xFFA0 ||
        codePoint in 0x17B4..0x17B5 ||
        codePoint in 0x180B..0x180F ||
        codePoint in 0x200B..0x200F ||
        codePoint in 0x202A..0x202E ||
        codePoint in 0x2060..0x206F ||
        codePoint in 0xFFF0..0xFFF8 ||
        codePoint in 0x1BCA0..0x1BCA3 ||
        codePoint in 0x1D173..0x1D17A ||
        codePoint in 0xE0000..0xE0FFF

private fun padded(size: Int) = (size + 3) / 4 * 4

private fun checksum(data: ByteArray): Int {
    var sum = 0
    var at = 0
    while (at < data.size) {
        var word = 0
        for (offset in 0 until 4) {
            val byte = if (at + offset < data.size) data[at + offset].toInt() and 0xFF else 0
            word = (word shl 8) or byte
        }
        sum += word
        at += 4
    }
    return sum
}

/** A big-endian writer over a buffer of a size known in advance, as font tables are. */
private class FontTableWriter(size: Int) {
    val out = ByteArray(size)
    var at = 0

    fun u8(value: Int) {
        out[at++] = value.toByte()
    }

    fun u16(value: Int) {
        u8(value ushr 8)
        u8(value)
    }

    fun u32(value: Int) {
        u16(value ushr 16)
        u16(value)
    }

    fun tag(value: String) {
        for (char in value) u8(char.code)
    }

    fun bytes(value: ByteArray) {
        value.copyInto(out, at)
        at += value.size
    }
}

private fun fontTable(size: Int, write: FontTableWriter.() -> Unit): ByteArray {
    val writer = FontTableWriter(size)
    writer.write()
    check(writer.at == size) { "a font table was declared at $size bytes and written at ${writer.at}" }
    return writer.out
}

/**
 * The box, the one glyph with an outline: two contours, the outer clockwise and the inner
 * the other way, so the inside stays empty. 56 bytes.
 */
private fun missingGlyf() = fontTable(56) {
    u16(2)
    u16(50)
    u16(0)
    u16(550)
    u16(700)
    u16(3)
    u16(7)
    u16(0)
    repeat(8) { u8(1) }
    for (x in intArrayOf(50, 0, 500, 0, -450, 400, 0, -400)) u16(x)
    for (y in intArrayOf(0, 700, 0, -700, 50, 0, 600, 0)) u16(y)
}

/** Short offsets, in halves of a byte offset: only the box has bytes. */
private fun missingLoca() = fontTable((MISSING_GLYPHS + 1) * 2) {
    u16(0)
    u16(0)
    u16(28)
    u16(28)
    u16(28)
}

private fun missingHmtx() = fontTable(MISSING_GLYPHS * 4) {
    for ((advance, bearing) in listOf(
        MissingGlyphFaces.ADVANCE to 0,
        MissingGlyphFaces.ADVANCE to 50,
        MissingGlyphFaces.SPACE_ADVANCE to 0,
        0 to 0,
    )) {
        u16(advance)
        u16(bearing)
    }
}

private fun missingHead() = fontTable(54) {
    u32(0x00010000)
    u32(0x00010000)
    u32(0)
    u32(0x5F0F3CF5)
    u16(0x000B)
    u16(MissingGlyphFaces.UNITS_PER_EM)
    repeat(4) { u32(0) }
    u16(0)
    u16(0)
    u16(550)
    u16(700)
    u16(0)
    u16(8)
    u16(2)
    u16(0)
    u16(0)
}

private fun missingHhea() = fontTable(36) {
    u32(0x00010000)
    u16(800)
    u16(-200)
    u16(0)
    u16(MissingGlyphFaces.ADVANCE)
    u16(0)
    u16(0)
    u16(550)
    u16(1)
    u16(0)
    u16(0)
    repeat(4) { u16(0) }
    u16(0)
    u16(MISSING_GLYPHS)
}

private fun missingMaxp() = fontTable(32) {
    u32(0x00010000)
    u16(MISSING_GLYPHS)
    u16(8)
    u16(2)
    repeat(2) { u16(0) }
    u16(2)
    repeat(8) { u16(0) }
}

/**
 * A format 4 subtable for the letters in the Basic Multilingual Plane, one segment each,
 * where there are few enough to fit one, and a format 12 subtable for all of them.
 */
private fun missingCmap(codePoints: IntArray, glyphs: IntArray): ByteArray {
    val bmp = codePoints.indices.filter { codePoints[it] < 0xFFFF }
    val format4 = bmp.size <= FORMAT_4_LIMIT
    val segments = bmp.size + 1
    val format4Length = if (format4) 16 + 8 * segments else 0
    val format12Length = 16 + 12 * codePoints.size
    val subtables = if (format4) 2 else 1
    val header = 4 + 8 * subtables
    return fontTable(header + format4Length + format12Length) {
        u16(0)
        u16(subtables)
        if (format4) {
            u16(3)
            u16(1)
            u32(header)
        }
        u16(3)
        u16(10)
        u32(header + format4Length)
        if (format4) {
            var power = 1
            var selector = 0
            while (power * 2 <= segments) {
                power *= 2
                selector++
            }
            u16(4)
            u16(format4Length)
            u16(0)
            u16(segments * 2)
            u16(power * 2)
            u16(selector)
            u16(segments * 2 - power * 2)
            for (at in bmp) u16(codePoints[at])
            u16(0xFFFF)
            u16(0)
            for (at in bmp) u16(codePoints[at])
            u16(0xFFFF)
            for (at in bmp) u16(glyphs[at] - codePoints[at])
            u16(1)
            repeat(segments) { u16(0) }
        }
        u16(12)
        u16(0)
        u32(format12Length)
        u32(0)
        u32(codePoints.size)
        for (at in codePoints.indices) {
            u32(codePoints[at])
            u32(codePoints[at])
            u32(glyphs[at])
        }
    }
}

/** The family name, in UTF-16 big-endian, written by hand: it is ASCII, and not every target has a charset for it. */
private fun missingName(): ByteArray {
    fun utf16(text: String) = ByteArray(text.length * 2) { at -> if (at % 2 == 0) 0 else text[at / 2].code.toByte() }
    val family = utf16("compose-rust missing glyph")
    val style = utf16("Regular")
    return fontTable(6 + 24 + family.size + style.size) {
        u16(0)
        u16(2)
        u16(30)
        for ((id, text, at) in listOf(Triple(1, family, 0), Triple(2, style, family.size))) {
            u16(3)
            u16(1)
            u16(0x409)
            u16(id)
            u16(text.size)
            u16(at)
        }
        bytes(family)
        bytes(style)
    }
}

private fun missingPost() = fontTable(32) {
    u32(0x00030000)
    u32(0)
    u16(-100)
    u16(50)
    u32(0)
    repeat(4) { u32(0) }
}

private fun missingOs2(codePoints: IntArray) = fontTable(96) {
    val first = codePoints.firstOrNull()?.coerceAtMost(0xFFFF) ?: 0x20
    val last = codePoints.lastOrNull()?.coerceAtMost(0xFFFF) ?: 0x20
    u16(4)
    u16(MissingGlyphFaces.ADVANCE)
    u16(400)
    u16(5)
    u16(0)
    for (value in intArrayOf(650, 600, 0, 75, 650, 600, 0, 350, 50, 300)) u16(value)
    u16(0)
    repeat(10) { u8(0) }
    repeat(4) { u32(0) }
    tag("NONE")
    u16(0x0040)
    u16(first)
    u16(last)
    u16(800)
    u16(-200)
    u16(0)
    u16(800)
    u16(200)
    u32(1)
    u32(0)
    u16(500)
    u16(700)
    u16(0)
    u16(0x20)
    u16(1)
}

/**
 * The families a letter is looked for in when no family of its chain has it, in order.
 *
 * Empty wherever the machine has fonts of its own, because there the platform's fallback
 * finds the letter. A browser gives a canvas none, so a letter the text's families lack
 * was drawn as an empty box: the web renderer puts the fallback faces it carries here
 * before the first frame, and every chain ends in them.
 *
 * Each member says which letters it has. One that could not say would take every letter
 * that reached it, and the ones after it would never be asked.
 */
var platformFallbackFamilies: List<FontChain.Member> = emptyList()

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

/**
 * The layout of a stack with a family whose bytes are still on the Host: version 1 with a
 * kind word before each family's face count, [FONT_STACK_FACES] or [FONT_STACK_LAZY_FACES].
 */
private const val FONT_STACK_LAZY_VERSION = 2
private const val FONT_STACK_FACES = 1
private const val FONT_STACK_LAZY_FACES = 4
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
 *
 * A lazy family's faces may not be here yet, and that is not a mistake: the Host keeps
 * them until they are needed. Until every one of them has arrived the family is pending
 * ([FontChain.Member.pending]): the letters that reach it are drawn as missing glyphs and
 * [want] asks for the faces that are missing. The cache reads the stack again as they
 * arrive.
 */
internal fun decodeFontStack(
    bytes: ByteArray,
    lookup: (Int) -> Asset?,
    want: (Int, Boolean) -> Unit = { _, _ -> },
): Asset.Font {
    fun word(at: Int): Int {
        require(at + 4 <= bytes.size) { "the font stack ends early" }
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
    val version = word(4)
    require(version == FONT_STACK_VERSION || version == FONT_STACK_LAZY_VERSION) {
        "font stack version $version is not one this renderer reads"
    }
    val count = word(8)
    require(count in 1..MAX_FONT_STACK_FAMILIES) { "a font stack holds 1 to $MAX_FONT_STACK_FAMILIES families, not $count" }
    var at = 12
    val members = ArrayList<FontChain.Member>(count)
    repeat(count) {
        val lazy = if (version == FONT_STACK_LAZY_VERSION) {
            val kind = word(at)
            require(kind == FONT_STACK_FACES || kind == FONT_STACK_LAZY_FACES) {
                "a font stack family is of kind $kind, which is neither registered faces ($FONT_STACK_FACES) nor faces the Host keeps ($FONT_STACK_LAZY_FACES)"
            }
            at += 4
            kind == FONT_STACK_LAZY_FACES
        } else {
            false
        }
        val faces = word(at)
        require(faces in 1..MAX_FONT_STACK_FACES) { "a font stack family holds 1 to $MAX_FONT_STACK_FACES faces, not $faces" }
        at += 4
        val fonts = ArrayList<Asset.Font>(faces)
        val missing = ArrayList<Int>()
        repeat(faces) {
            val id = word(at)
            at += 4
            val found = lookup(id)
            if (found == null && lazy) {
                missing += id
            } else {
                val font = found as? Asset.Font
                require(font?.face != null) { "the font stack names asset $id, which is not a registered font file" }
                fonts += font!!
            }
        }
        members += if (missing.isEmpty()) {
            stackMember(fonts)
        } else {
            // The first family of a stack is how a theme finds it, so it is one no other
            // stack shares. A later family is never asked for by identity.
            val family = (if (members.isEmpty()) MissingGlyphFaces.fresh() else null) ?: MissingGlyphFaces.empty
            FontChain.Member(family, hasGlyph = null, pending = PendingFace(missing.toIntArray(), want))
        }
    }
    require(at == bytes.size) { "the font stack has ${bytes.size - at} bytes after its last family" }
    // The chain's first family is made here and is a family no other text is set in: that
    // is what lets the theme tell text in this stack from text in its first face.
    return Asset.Font(family = members.first().family, chain = FontChain(members))
}

/**
 * The faces a stack names in its lazy families, which the cache reads the stack again for
 * as they arrive or are let go of. Empty for a stack with none, and for bytes that are not
 * a stack this reads, which registering refuses on its own.
 */
internal fun fontStackLazyFaces(bytes: ByteArray): IntArray {
    fun word(at: Int): Int? = if (at < 0 || at + 4 > bytes.size) {
        null
    } else {
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
    val none = IntArray(0)
    if (!isFontStack(bytes) || word(4) != FONT_STACK_LAZY_VERSION) return none
    val count = word(8) ?: return none
    if (count !in 1..MAX_FONT_STACK_FAMILIES) return none
    val out = ArrayList<Int>()
    var at = 12
    repeat(count) {
        val kind = word(at) ?: return none
        val faces = word(at + 4) ?: return none
        if (faces !in 1..MAX_FONT_STACK_FACES) return none
        at += 8
        repeat(faces) {
            val id = word(at) ?: return none
            if (kind == FONT_STACK_LAZY_FACES) out += id
            at += 4
        }
    }
    return out.toIntArray()
}

/**
 * One family of a stack: its faces as one family Compose picks a weight from, and the
 * letters any of them has.
 */
private fun stackMember(fonts: List<Asset.Font>): FontChain.Member {
    val faces = fonts.map { it.face!! }
    val family = FontFamily(faces.flatMap { it.fonts })
    return FontChain.Member(family, hasGlyph = { codePoint -> faces.any { it.hasGlyph(codePoint) } })
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
