package dev.darkpyonix.composerust.test

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A TrueType font with one glyph, a filled block one and a half em wide, at one private use
 * code point the way an icon font such as codicon puts its glyphs, and at `A` as well.
 *
 * Built here rather than checked in, so a test of icon fonts carries no font file and no
 * licence with it, and so what the glyph is, and how wide, is written down where the test
 * can read it. One and a half em is a width no other face gives either character, so text
 * that comes out exactly that wide was set in this font and in no fallback.
 */
internal object TinyIconFont {
    const val UNITS_PER_EM = 1000

    /** How wide the glyph is, in units: one and a half em. */
    const val ADVANCE = 1500

    /** The glyph's width as a share of the font size. */
    const val WIDTH_PER_SIZE = ADVANCE.toFloat() / UNITS_PER_EM

    /** Where codicon puts its first glyph, which is as good a place as any. */
    const val CODE_POINT = 0xEA60

    private const val LATIN_A = 0x41

    fun build(codePoint: Int = CODE_POINT): ByteArray {
        val tables = sortedMapOf(
            "OS/2" to os2(codePoint),
            "cmap" to cmap(codePoint),
            "glyf" to glyf(),
            "head" to head(),
            "hhea" to hhea(),
            "hmtx" to hmtx(),
            "loca" to loca(),
            "maxp" to maxp(),
            "name" to name(),
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
        short(ADVANCE)
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
        short(ADVANCE)
        short(0)
        short(0)
        short(ADVANCE)
        short(1)
        short(0)
        short(0)
        repeat(4) { short(0) }
        short(0)
        short(2)
    }

    private fun maxp() = table(32) {
        putInt(0x00010000)
        short(2)
        short(4)
        short(1)
        repeat(2) { short(0) }
        short(2)
        repeat(8) { short(0) }
    }

    /** The empty `.notdef` and the block, with no side bearing. */
    private fun hmtx() = table(8) {
        short(UNITS_PER_EM / 2)
        short(0)
        short(ADVANCE)
        short(0)
    }

    /** One contour, four points on the curve, from the origin to the advance by 800 units. */
    private fun glyf() = table(36) {
        short(1)
        short(0)
        short(0)
        short(ADVANCE)
        short(800)
        short(3)
        short(0)
        repeat(4) { put(1) }
        short(0)
        short(ADVANCE)
        short(0)
        short(-ADVANCE)
        short(0)
        short(0)
        short(800)
        short(0)
        short(0)
    }

    /** Short offsets, in halves: `.notdef` is empty and the square takes all 36 bytes. */
    private fun loca() = table(6) {
        short(0)
        short(0)
        short(18)
    }

    /** One Windows Unicode subtable: `A` and [codePoint] to the block, all else to nothing. */
    private fun cmap(codePoint: Int) = table(52) {
        short(0)
        short(1)
        short(3)
        short(1)
        putInt(12)
        // Format 4, three segments: `A`, the code point, and the closing 0xFFFF.
        short(4)
        short(40)
        short(0)
        short(6)
        short(4)
        short(1)
        short(2)
        short(LATIN_A)
        short(codePoint)
        short(0xFFFF)
        short(0)
        short(LATIN_A)
        short(codePoint)
        short(0xFFFF)
        short((1 - LATIN_A) and 0xFFFF)
        short((1 - codePoint) and 0xFFFF)
        short(1)
        short(0)
        short(0)
        short(0)
    }

    private fun name(): ByteArray {
        val family = "Tiny Icons".toByteArray(Charsets.UTF_16BE)
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

    private fun os2(codePoint: Int) = table(96) {
        short(4)
        short(ADVANCE)
        short(400)
        short(5)
        short(0)
        for (value in listOf(650, 600, 0, 75, 650, 600, 0, 350, 50, 300)) short(value)
        short(0)
        repeat(10) { put(0) }
        // Bit 0 of the Unicode ranges is Basic Latin, and bit 60 the Private Use Area.
        putInt(1)
        putInt(1 shl 28)
        putInt(0)
        putInt(0)
        put("NONE".encodeToByteArray())
        short(0x0040)
        short(LATIN_A)
        short(codePoint)
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
