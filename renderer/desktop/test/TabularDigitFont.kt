package dev.darkpyonix.composerust.test

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A TrueType font with the digits `0` and `1` only, whose digits are proportional by
 * default and tabular under the OpenType feature `tnum`.
 *
 * `0` is [ZERO_ADVANCE] units wide and `1` is [ONE_ADVANCE], so `0000` and `1111` differ.
 * A `GSUB` table maps both, under `tnum`, to glyphs [TABULAR_ADVANCE] wide, so with the
 * feature on the two strings are the same width. The feature is listed under the `DFLT`
 * and `latn` scripts, so it is found whichever one the shaper takes digits to be in.
 *
 * Built here rather than checked in, for the reason [TinyIconFont] gives: no font file and
 * no licence travel with the test, and the widths it relies on are written down where the
 * test can read them.
 */
internal object TabularDigitFont {
    const val UNITS_PER_EM = 1000
    const val ZERO_ADVANCE = 700
    const val ONE_ADVANCE = 300
    const val TABULAR_ADVANCE = 500

    private const val NOTDEF_ADVANCE = 500
    private const val GLYPHS = 5
    private const val DIGIT_ZERO = 0x30
    private const val DIGIT_ONE = 0x31

    /** Advances by glyph id: `.notdef`, `zero`, `one`, `zero.tnum`, `one.tnum`. */
    private val advances = intArrayOf(NOTDEF_ADVANCE, ZERO_ADVANCE, ONE_ADVANCE, TABULAR_ADVANCE, TABULAR_ADVANCE)
    private val widest = advances.max()

    fun build(family: String = "Tabular Digits"): ByteArray {
        val tables = sortedMapOf(
            "GSUB" to gsub(),
            "OS/2" to os2(),
            "cmap" to cmap(),
            "glyf" to glyf(),
            "head" to head(),
            "hhea" to hhea(),
            "hmtx" to hmtx(),
            "loca" to loca(),
            "maxp" to maxp(),
            "name" to name(family),
            "post" to post(),
        )
        val count = tables.size
        var power = 1
        var selector = 0
        while (power * 2 <= count) {
            power *= 2
            selector++
        }
        val directory = 12 + 16 * count
        val total = directory + tables.values.sumOf { padded(it.size) }
        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0x00010000)
        out.putShort(count.toShort())
        out.putShort((power * 16).toShort())
        out.putShort(selector.toShort())
        out.putShort((count * 16 - power * 16).toShort())
        var offset = directory
        for ((tag, data) in tables) {
            out.put(tag.encodeToByteArray())
            out.putInt(checksum(data))
            out.putInt(offset)
            out.putInt(data.size)
            offset += padded(data.size)
        }
        for (data in tables.values) {
            out.put(data)
            repeat(padded(data.size) - data.size) { out.put(0) }
        }
        return out.array()
    }

    private fun padded(size: Int) = (size + 3) / 4 * 4

    private fun checksum(data: ByteArray): Int {
        val buffer = ByteBuffer.wrap(data.copyOf(padded(data.size))).order(ByteOrder.BIG_ENDIAN)
        var sum = 0
        while (buffer.hasRemaining()) sum += buffer.getInt()
        return sum
    }

    private fun table(size: Int, write: ByteBuffer.() -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.write()
        check(!buffer.hasRemaining()) { "a table was declared at $size bytes and written shorter" }
        return buffer.array()
    }

    private fun ByteBuffer.short(value: Int) {
        putShort(value.toShort())
    }

    private fun ByteBuffer.tag(value: String) {
        put(value.encodeToByteArray())
    }

    /**
     * One script list with `DFLT` and `latn` sharing a default language system that turns
     * on feature 0, `tnum`, whose one lookup is a single substitution of glyphs 1 and 2 by
     * glyphs 3 and 4.
     */
    private fun gsub() = table(76) {
        // Header: version 1.0, then the script, feature and lookup lists.
        short(1)
        short(0)
        short(10)
        short(36)
        short(50)
        // Script list, at 10: two records sorted by tag, both to the script at 14.
        short(2)
        tag("DFLT")
        short(14)
        tag("latn")
        short(14)
        // Script, at 14 of the list: its default language system at 4, and no others.
        short(4)
        short(0)
        // Language system: no reordering table, no required feature, feature 0.
        short(0)
        short(0xFFFF)
        short(1)
        short(0)
        // Feature list, at 36: `tnum`, its table at 8.
        short(1)
        tag("tnum")
        short(8)
        // Feature: no parameters, lookup 0.
        short(0)
        short(1)
        short(0)
        // Lookup list, at 50: one lookup at 4.
        short(1)
        short(4)
        // Lookup: single substitution, no flags, one subtable at 8.
        short(1)
        short(0)
        short(1)
        short(8)
        // Single substitution format 1: coverage at 6, every covered glyph plus two.
        short(1)
        short(6)
        short(2)
        // Coverage format 1: glyphs 1 and 2.
        short(1)
        short(2)
        short(1)
        short(2)
    }

    private fun head() = table(54) {
        putInt(0x00010000)
        putInt(0x00010000)
        putInt(0)
        putInt(0x5F0F3CF5)
        short(0x000B)
        short(UNITS_PER_EM)
        putLong(0)
        putLong(0)
        short(0)
        short(0)
        short(widest)
        short(800)
        short(0)
        short(8)
        short(2)
        short(0)
        short(0)
    }

    private fun hhea() = table(36) {
        putInt(0x00010000)
        short(800)
        short(-200)
        short(0)
        short(widest)
        short(0)
        short(0)
        short(widest)
        short(1)
        short(0)
        short(0)
        repeat(4) { short(0) }
        short(0)
        short(GLYPHS)
    }

    private fun maxp() = table(32) {
        putInt(0x00010000)
        short(GLYPHS)
        short(4)
        short(1)
        repeat(2) { short(0) }
        short(2)
        repeat(8) { short(0) }
    }

    private fun hmtx() = table(GLYPHS * 4) {
        for (advance in advances) {
            short(advance)
            short(0)
        }
    }

    /** `.notdef` is empty; every digit glyph is a block as wide as its advance, 36 bytes. */
    private fun glyf() = table((GLYPHS - 1) * 36) {
        for (advance in advances.drop(1)) {
            short(1)
            short(0)
            short(0)
            short(advance)
            short(800)
            short(3)
            short(0)
            repeat(4) { put(1) }
            short(0)
            short(advance)
            short(0)
            short(-advance)
            short(0)
            short(0)
            short(800)
            short(0)
            short(0)
        }
    }

    /** Short offsets, in halves of a byte offset. */
    private fun loca() = table((GLYPHS + 1) * 2) {
        short(0)
        for (glyph in 0 until GLYPHS) short(glyph * 18)
    }

    /** One Windows Unicode subtable: `0` and `1` to glyphs 1 and 2. */
    private fun cmap() = table(44) {
        short(0)
        short(1)
        short(3)
        short(1)
        putInt(12)
        // Format 4, two segments: the two digits, and the closing 0xFFFF.
        short(4)
        short(32)
        short(0)
        short(4)
        short(4)
        short(1)
        short(0)
        short(DIGIT_ONE)
        short(0xFFFF)
        short(0)
        short(DIGIT_ZERO)
        short(0xFFFF)
        short((1 - DIGIT_ZERO) and 0xFFFF)
        short(1)
        short(0)
        short(0)
    }

    private fun name(familyName: String): ByteArray {
        val family = familyName.toByteArray(Charsets.UTF_16BE)
        val style = "Regular".toByteArray(Charsets.UTF_16BE)
        return table(6 + 24 + family.size + style.size) {
            short(0)
            short(2)
            short(30)
            for ((id, text, at) in listOf(Triple(1, family, 0), Triple(2, style, family.size))) {
                short(3)
                short(1)
                short(0x409)
                short(id)
                short(text.size)
                short(at)
            }
            put(family)
            put(style)
        }
    }

    private fun post() = table(32) {
        putInt(0x00030000)
        putInt(0)
        short(-100)
        short(50)
        putInt(0)
        repeat(4) { putInt(0) }
    }

    private fun os2() = table(96) {
        short(4)
        short(TABULAR_ADVANCE)
        short(400)
        short(5)
        short(0)
        for (value in listOf(650, 600, 0, 75, 650, 600, 0, 350, 50, 300)) short(value)
        short(0)
        repeat(10) { put(0) }
        // Bit 0 of the Unicode ranges is Basic Latin.
        putInt(1)
        putInt(0)
        putInt(0)
        putInt(0)
        put("NONE".encodeToByteArray())
        short(0x0040)
        short(DIGIT_ZERO)
        short(DIGIT_ONE)
        short(800)
        short(-200)
        short(0)
        short(800)
        short(200)
        putInt(1)
        putInt(0)
        short(500)
        short(700)
        short(0)
        short(32)
        short(1)
    }
}
