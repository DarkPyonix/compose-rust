package dev.darkpyonix.composerust.ui.platform

// Code points of a string, which the accessibility server counts in and the Kotlin/Native
// window's input method counts in as well. One definition for both: the input method's file
// in the Kotlin/Native renderer declares the same two functions today and drops them when it
// takes these from here.

/** Appends one code point, as the pair of surrogates it is when it is past the first plane. */
internal fun StringBuilder.appendPoint(point: Int) {
    if (point < 0x10000) {
        append(point.toChar())
    } else {
        val offset = point - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}

/** The code points of a string, with a pair of surrogates read as the one character it is. */
internal fun codePointsOf(text: String): List<Int> {
    val points = ArrayList<Int>(text.length)
    var index = 0
    while (index < text.length) {
        val unit = text[index]
        if (unit.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            points += 0x10000 + ((unit.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
            index += 2
        } else {
            points += unit.code
            index += 1
        }
    }
    return points
}
