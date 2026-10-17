package dev.darkpyonix.composerust.ui.platform

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle

/**
 * Whether Korean text is shaped, broken into words and wrapped correctly by the renderer the
 * application was linked with, asked for by setting `COMPOSE_RUST_TEXT_SELF_CHECK`.
 *
 * An application that is one executable carries no ICU data file beside it, so finding words
 * and places to wrap has to work from what is linked into the executable. When it does not,
 * nothing fails loudly: Korean comes out as boxes, a double click selects one syllable, and a
 * line wraps in the middle of a Latin word. This lays out text through the same Skia paragraph
 * engine Compose draws with and says which of those it saw, so a build can prove the
 * executable it shipped gets them right on a machine with no ICU data anywhere.
 *
 * Three things are checked:
 *
 * 1. every character has a glyph in some font the system offers (no unresolved glyphs, which
 *    is what draws as a box);
 * 2. the word around a syllable of a Korean word is that whole word;
 * 3. wrapped to a narrow width, lines break only between words or between Hangul syllables,
 *    and a Latin word that fits on a line is never split.
 *
 * Returns null when the check was not asked for, true when it passed and false when it
 * failed, and says which on standard error either way.
 */
internal fun runTextSelfCheckIfAsked(): Boolean? {
    if (java.lang.System.getenv(TEXT_SELF_CHECK_ENV) == null) return null
    val failures = mutableListOf<String>()
    val fonts = FontCollection().setDefaultFontManager(FontMgr.default)

    fun layout(text: String, width: Float): Paragraph {
        val style = ParagraphStyle()
        style.textStyle = TextStyle().setFontSize(16f)
        return ParagraphBuilder(style, fonts).addText(text).build().layout(width)
    }

    // 1. Glyph coverage, on one line so nothing else is being measured.
    val wide = layout(SAMPLE, 100_000f)
    if (wide.unresolvedGlyphsCount != 0) {
        failures += "${wide.unresolvedGlyphsCount} character(s) have no glyph in any font; they draw as boxes"
    }

    // 2. The word around a syllable in the middle of a Korean word.
    val wordStart = SAMPLE.indexOf(KOREAN_WORD)
    val middle = wordStart + KOREAN_WORD.length / 2
    val word = wide.getWordBoundary(middle)
    val found = SAMPLE.substring(
        word.start.coerceIn(0, SAMPLE.length),
        word.end.coerceIn(0, SAMPLE.length),
    )
    if (word.start != wordStart || word.end != wordStart + KOREAN_WORD.length) {
        failures += "the word around offset $middle is [${word.start}, ${word.end}) \"$found\", not \"$KOREAN_WORD\""
    }

    // 3. Wrapping. Narrow enough that the sample takes several lines, wide enough for the
    //    longest Latin word, which therefore must never be split.
    val latinWidth = layout(LATIN_WORD, 100_000f).maxIntrinsicWidth
    val narrow = layout(SAMPLE, latinWidth * 1.25f)
    val lines = narrow.lineMetrics
    if (lines.size < 3) {
        failures += "wrapped to ${latinWidth * 1.25f} the sample took ${lines.size} line(s), expected several"
    }
    for (line in lines.dropLast(1)) {
        val at = line.endIndex
        if (at <= 0 || at >= SAMPLE.length) {
            failures += "a line ends at $at, outside the text"
            continue
        }
        val before = SAMPLE[at - 1]
        val after = SAMPLE[at]
        val allowed = before.isWhitespace() || after.isWhitespace() ||
            isPunctuation(before) || (isHangul(before) && isHangul(after))
        if (!allowed) {
            failures += "a line breaks between '$before' and '$after' at $at, which is inside a word"
        }
    }
    val latinStart = SAMPLE.indexOf(LATIN_WORD)
    val latinEnd = latinStart + LATIN_WORD.length
    if (lines.none { it.startIndex <= latinStart && it.endIndex >= latinEnd }) {
        failures += "\"$LATIN_WORD\" fits on a line and was split across lines"
    }

    val breaks = lines.dropLast(1).map { it.endIndex }
    return if (failures.isEmpty()) {
        java.lang.System.err.println(
            "compose-rust text self-check: ok (no unresolved glyphs, word \"$found\", " +
                "${lines.size} lines breaking at $breaks)",
        )
        true
    } else {
        java.lang.System.err.println("compose-rust text self-check: FAILED")
        failures.forEach { java.lang.System.err.println("  $it") }
        false
    }
}

private fun isHangul(c: Char): Boolean = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

private fun isPunctuation(c: Char): Boolean = c in ".,!?;:"

/** The variable that asks for the check. */
internal const val TEXT_SELF_CHECK_ENV = "COMPOSE_RUST_TEXT_SELF_CHECK"

/** A Korean word in the middle of the sample, not first or last on any line it could take. */
private const val KOREAN_WORD = "반갑습니다"

/** A Latin word long enough that a broken line breaker would split it. */
private const val LATIN_WORD = "internationalization"

private const val SAMPLE =
    "안녕하세요 반갑습니다. 한국어 문장의 줄바꿈과 단어 경계를 internationalization 단어와 함께 확인합니다."
