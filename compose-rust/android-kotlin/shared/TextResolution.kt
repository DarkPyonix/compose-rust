package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow as ComposeOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.FontRefRecords
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.OverflowWrap
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SpanFontRecords
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.TextAlign
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WordBreak
import dev.darkpyonix.composerust.ui.floatProp
import dev.darkpyonix.composerust.ui.intProp
import dev.darkpyonix.composerust.ui.maxLines
import dev.darkpyonix.composerust.ui.node.Asset
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.systemFontFamily
import dev.darkpyonix.composerust.ui.overflow
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.role
import dev.darkpyonix.composerust.ui.typeRole

/**
 * Everything that decides how a piece of text is set, wherever it came from.
 *
 * A `Text` node being drawn and a measure request being answered both become one of
 * these, and [resolveText] turns it into what Compose lays out. Measuring and drawing go
 * through that one function on purpose: a measured size is only worth having if it is the
 * size the same text turns out to be on screen, and two functions that each meant to do
 * the same thing would sooner or later disagree about a font, a scale or a break.
 */
internal class TextInput(
    val text: String,
    /** The runs, or empty for none. */
    val runs: List<TextRun>,
    /** The fonts runs name for themselves, by run index. */
    val runFonts: Map<Int, List<FontRef>>,
    /**
     * True for text with no rung of the ladder at all, which takes its size, weight,
     * line height, letter spacing and font from its own values and nothing from the
     * design system.
     */
    val noRole: Boolean,
    /** The rung, where the text named one. */
    val role: TypeRole?,
    /** The rung where the text named none and is not [noRole]. */
    val defaultRole: TypeRole = TypeRole.Body,
    val fontSize: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean = false,
    val letterSpacing: Float? = null,
    val lineHeight: Float? = null,
    val maxLines: Int = Int.MAX_VALUE,
    val softWrap: Boolean = true,
    val tabSize: Int = DEFAULT_TAB_SIZE,
    val wordBreak: WordBreak? = null,
    val overflowWrap: OverflowWrap? = null,
    /** Sizes are CSS pixels, which the system's font scale does not enlarge. */
    val absoluteSize: Boolean = false,
    /** The fonts to try, for [noRole] text. Ignored where there is a role. */
    val fonts: List<FontRef> = emptyList(),
    /** Drawing only: neither changes a size. */
    val color: Paint? = null,
    val textAlign: TextAlign? = null,
    val overflow: ComposeOverflow = ComposeOverflow.Clip,
) {
    companion object {
        const val DEFAULT_TAB_SIZE = 8
        /** What text with no role is set at when it says nothing, as CSS's `medium`. */
        const val NO_ROLE_FONT_SIZE = 14f
        const val NO_ROLE_FONT_WEIGHT = 400
    }
}

/** What Compose is handed to lay the text out, drawn or measured. */
internal class ResolvedText(
    val text: AnnotatedString,
    val style: TextStyle,
    val softWrap: Boolean,
    val maxLines: Int,
    val overflow: ComposeOverflow,
    /** Where each tab sits and how far it advances. Empty where there are no tabs. */
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    /** The same placeholders, in the form `BasicText` takes them. */
    val inlineContent: Map<String, InlineTextContent>,
    /**
     * The text broken anywhere, for working out the narrowest it can be when a word too
     * long for its line may be broken inside. Null where that is the text itself.
     */
    val minContentText: AnnotatedString?,
    val minContentPlaceholders: List<AnnotatedString.Range<Placeholder>>,
    /** What was wrong with the runs, which were left out, or null where nothing was. */
    val runsProblem: String?,
    /** What was wrong with the fonts, one sentence each, for the caller to report. */
    val problems: List<String>,
)

/**
 * Turns a [TextInput] into what Compose lays out.
 *
 * [link] makes the annotation a run that is a link carries, for text that is drawn and
 * can be pressed. Measuring passes null: a link changes nothing about a size.
 */
internal fun resolveText(
    input: TextInput,
    theme: ResolvedTheme,
    assets: AssetCache,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    link: ((TextRun) -> LinkAnnotation.Clickable)? = null,
    /**
     * Leaves out what only paints: the colour. Nothing about a size depends on it, and
     * resolving a colour through the design system is the dearest thing done here.
     */
    forMeasure: Boolean = false,
): ResolvedText {
    val problems = mutableListOf<String>()
    // Sizes are sp. Text in CSS pixels is not given smaller sp to undo the font scale:
    // it is set in a density whose font scale is one (see [textDensity]), which is the only
    // way that stays right where the font scale is not linear.
    fun sized(value: Float): TextUnit = value.sp

    val style = if (input.noRole) {
        TextStyle(
            fontSize = sized(input.fontSize ?: TextInput.NO_ROLE_FONT_SIZE),
            fontWeight = FontWeight(input.fontWeight ?: TextInput.NO_ROLE_FONT_WEIGHT),
            lineHeight = input.lineHeight?.let(::sized) ?: TextUnit.Unspecified,
            letterSpacing = input.letterSpacing?.let(::sized) ?: TextUnit.Unspecified,
            fontFamily = resolveFontFamily(input.fonts, assets, problems),
        )
    } else {
        val role = input.role ?: input.defaultRole
        val token = theme.type(role)
        TextStyle(
            fontSize = sized(input.fontSize ?: token.size),
            fontWeight = input.fontWeight?.let { FontWeight(it) } ?: FontWeight(token.weight),
            lineHeight = sized(input.lineHeight ?: token.lineHeight),
            letterSpacing = sized(input.letterSpacing ?: token.letterSpacing),
            // A font named for text that has a role is ignored: the theme decides what a
            // rung is written in, and one node cannot say otherwise.
            fontFamily = theme.family(role),
        )
    }.merge(
        TextStyle(
            color = when {
                forMeasure -> androidx.compose.ui.graphics.Color.Unspecified
                else -> input.color?.let(theme::color) ?: theme.color(ColorRole.OnSurface)
            },
            fontStyle = if (input.italic) FontStyle.Italic else null,
            textAlign = when (input.textAlign) {
                TextAlign.Start -> ComposeTextAlign.Start
                TextAlign.Center -> ComposeTextAlign.Center
                TextAlign.End -> ComposeTextAlign.End
                TextAlign.Justify -> ComposeTextAlign.Justify
                null -> ComposeTextAlign.Unspecified
            },
        ),
    )

    val badRuns = if (input.runs.isEmpty()) null else runsProblem(input.runs, input.text.encodeToByteArray().size)
    val runs = if (badRuns == null) input.runs else emptyList()
    val mode = when (input.wordBreak) {
        WordBreak.KeepAll -> BreakMode.KeepAll
        WordBreak.BreakAll -> BreakMode.BreakAll
        WordBreak.Normal, null -> BreakMode.Normal
    }
    val text = annotate(input, runs, mode, theme, assets, density, ::sized, link, problems)
    val (placeholders, inline) = tabStops(text, style, input.tabSize, density, fontFamilyResolver)

    // Breaking anywhere only matters to the narrowest width: laid out at any width, a word
    // too long for its line is broken inside it already. So the text broken anywhere is
    // made only for that question, and only where the answer differs.
    val anywhere = input.overflowWrap == OverflowWrap.Anywhere && mode != BreakMode.BreakAll
    val minContent = if (anywhere) {
        annotate(input, runs, BreakMode.BreakAll, theme, assets, density, ::sized, null, mutableListOf())
    } else {
        null
    }
    val minContentPlaceholders = minContent
        ?.let { tabStops(it, style, input.tabSize, density, fontFamilyResolver).first }
        ?: emptyList()

    return ResolvedText(
        text = text,
        style = style,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        overflow = input.overflow,
        placeholders = placeholders,
        inlineContent = inline,
        minContentText = minContent,
        minContentPlaceholders = minContentPlaceholders,
        runsProblem = badRuns,
        problems = problems,
    )
}

/**
 * The density a text is set in: the one it is drawn or measured under, with the font scale
 * taken out for text in CSS pixels, which the system's text size does not enlarge.
 */
internal fun textDensity(input: TextInput, density: Density): Density =
    if (input.absoluteSize && density.fontScale != 1f) Density(density.density, 1f) else density

/**
 * The first candidate that resolves, as a CSS font list is read.
 *
 * A registered font asset that is not there is reported and passed over. An installed
 * family is asked for by name, and a name the machine does not have is passed over without
 * a report, because a list that names fonts it may not find is what a font list is for.
 * Nothing resolving is a sans serif.
 */
internal fun resolveFontFamily(
    refs: List<FontRef>,
    assets: AssetCache,
    problems: MutableList<String>,
): FontFamily {
    for (ref in refs) {
        when (ref) {
            is FontRef.Asset -> {
                val font = assets.asset(ref.assetId) as? Asset.Font
                if (font != null) return font.family
                problems += "font asset ${ref.assetId} is not a registered font; the next font in the list is used"
            }
            is FontRef.System -> systemFontFamily(ref.name)?.let { return it }
            is FontRef.Generic -> return genericFontFamily(ref.family)
        }
    }
    return FontFamily.SansSerif
}

/** Which installed family each generic name is, which is the platform's answer. */
internal fun genericFontFamily(family: GenericFamily): FontFamily = when (family) {
    GenericFamily.SystemUi -> platformUiFamily
    GenericFamily.SansSerif -> FontFamily.SansSerif
    GenericFamily.Serif -> FontFamily.Serif
    GenericFamily.Monospace -> FontFamily.Monospace
}

/** Where lines may break, beyond where the language itself allows. */
internal enum class BreakMode { Normal, KeepAll, BreakAll }

/** Keeps two letters on one line: CSS `keep-all` between letters of a CJK word. */
private const val WORD_JOINER = '⁠'

/** Lets a line break between two letters: CSS `break-all`. */
private const val ZERO_WIDTH_SPACE = '​'

/**
 * The text with invisible characters put where a break is forbidden or allowed, and where
 * each of the original characters ended up. Both characters are default ignorable, so
 * they take no width and are never drawn: they only change where a line can end.
 *
 * The platform's own line breaking is what decides everything else. It breaks Korean
 * between syllables, which is CSS `normal`, so `keep-all` is the one that has to be asked
 * for, and it differs from platform to platform in ways that this does not.
 */
internal class BrokenText(val text: String, private val map: IntArray?) {
    /** Where character [index] of the original string is in [text]. */
    fun position(index: Int): Int = map?.get(index) ?: index
}

internal fun breakText(text: String, mode: BreakMode): BrokenText {
    if (mode == BreakMode.Normal || text.length < 2) return BrokenText(text, null)
    val out = StringBuilder(text.length + text.length / 2)
    val map = IntArray(text.length + 1)
    for (index in text.indices) {
        map[index] = out.length
        val current = text[index]
        out.append(current)
        val next = text.getOrNull(index + 1) ?: continue
        val insert = when (mode) {
            BreakMode.KeepAll -> letter(current) && letter(next) && (cjk(current) || cjk(next))
            BreakMode.BreakAll -> letter(current) && letter(next) && !joinedJamo(current, next)
            BreakMode.Normal -> false
        }
        if (insert) out.append(if (mode == BreakMode.KeepAll) WORD_JOINER else ZERO_WIDTH_SPACE)
    }
    map[text.length] = out.length
    return BrokenText(out.toString(), map)
}

/** A letter or digit, whole: half of a surrogate pair is never split from the other. */
private fun letter(char: Char): Boolean = !char.isSurrogate() && char.isLetterOrDigit()

/** Hangul, the CJK ideographs, and the two kana. */
private fun cjk(char: Char): Boolean {
    val code = char.code
    return code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F ||
        code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3040..0x30FF
}

/** Conjoining jamo that make one syllable together, which a break would tear apart. */
private fun joinedJamo(first: Char, second: Char): Boolean =
    first.code in 0x1100..0x11FF && second.code in 0x1100..0x11FF

/** The text and its runs, with the breaks [mode] asks for, as one annotated string. */
private fun annotate(
    input: TextInput,
    runs: List<TextRun>,
    mode: BreakMode,
    theme: ResolvedTheme,
    assets: AssetCache,
    density: Density,
    sized: (Float) -> TextUnit,
    link: ((TextRun) -> LinkAnnotation.Clickable)?,
    problems: MutableList<String>,
): AnnotatedString {
    val broken = breakText(input.text, mode)
    val hasTabs = broken.text.indexOf('\t') >= 0
    if (runs.isEmpty() && !hasTabs) return AnnotatedString(broken.text)
    val utf8 = if (runs.isEmpty()) null else input.text.encodeToByteArray()
    return buildAnnotatedString {
        // Tabs become placeholders, each standing in place of its own character, so the
        // string keeps its length and every run still covers what it covered.
        var tab = 0
        for (char in broken.text) {
            if (char == '\t') {
                appendInlineContent(tabId(tab++), "\t")
            } else {
                append(char)
            }
        }
        if (utf8 == null) return@buildAnnotatedString
        runs.forEachIndexed { index, run ->
            val from = broken.position(utf8.decodeToString(0, run.start).length)
            val to = broken.position(utf8.decodeToString(0, run.start + run.length).length)
            // A run of text with no role may name its own font. Where the text has a
            // role, or the run does, the font is the theme's.
            val family = input.runFonts[index]
                ?.takeIf { input.noRole && run.role == null }
                ?.let { resolveFontFamily(it, assets, problems) }
            val style = SpanStyle(
                color = run.color?.let(theme::color) ?: androidx.compose.ui.graphics.Color.Unspecified,
                // Behind this run's letters only, line by line where it wraps. The line's
                // own background is the node's, and this is the part of it that changed.
                background = runBackground(run, theme),
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontSize = run.role?.let { sized(theme.type(it).size) } ?: TextUnit.Unspecified,
                fontFamily = family,
                textDecoration = when {
                    run.underline && run.strikethrough ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    run.underline -> TextDecoration.Underline
                    run.strikethrough -> TextDecoration.LineThrough
                    else -> null
                },
            )
            addStyle(style, from, to)
            if (link != null && run.handlerId != 0L) addLink(link(run), from, to)
        }
    }
}

private fun tabId(index: Int): String = "compose-rust-tab-$index"

/**
 * One placeholder per tab, as wide as it takes to reach the next tab stop.
 *
 * The stops are [tabSize] spaces of the text's own font apart, as CSS `tab-size` puts
 * them, and a tab advances to the next one after whatever comes before it on its line. A
 * tab that would advance less than half a space goes on to the stop after, as CSS has it.
 * Only the side that measures the letters can work this out, which is why a Host cannot
 * expand tabs into spaces in a proportional font.
 *
 * "Its line" is the line the text starts it on, counted from the last newline. Text that
 * wraps before a tab puts the tab's stops where the unwrapped line would have them, which
 * is right for `pre` and as close as a layout that has not happened yet can get otherwise.
 */
private fun tabStops(
    text: AnnotatedString,
    style: TextStyle,
    tabSize: Int,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
): Pair<List<AnnotatedString.Range<Placeholder>>, Map<String, InlineTextContent>> {
    if (text.text.indexOf('\t') < 0) return emptyList<AnnotatedString.Range<Placeholder>>() to emptyMap()
    val space = MultiParagraphIntrinsics(AnnotatedString(" "), style, emptyList(), density, fontFamilyResolver)
        .maxIntrinsicWidth
    val stop = space * (if (tabSize > 0) tabSize else TextInput.DEFAULT_TAB_SIZE)
    val placeholders = mutableListOf<AnnotatedString.Range<Placeholder>>()
    val inline = mutableMapOf<String, InlineTextContent>()
    var tab = 0
    for (index in text.text.indices) {
        if (text.text[index] != '\t') continue
        val lineStart = text.text.lastIndexOf('\n', index - 1) + 1
        val before = placeholders
            .filter { it.start >= lineStart }
            .map { AnnotatedString.Range(it.item, it.start - lineStart, it.end - lineStart) }
        val prefix = MultiParagraphIntrinsics(
            text.subSequence(lineStart, index),
            style,
            before,
            density,
            fontFamilyResolver,
        ).maxIntrinsicWidth
        var advance = if (stop > 0f) stop - (prefix % stop) else space
        if (advance < space / 2f) advance += stop
        val placeholder = Placeholder(
            width = with(density) { advance.toSp() },
            height = 0.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        )
        placeholders += AnnotatedString.Range(placeholder, index, index + 1)
        inline[tabId(tab++)] = InlineTextContent(placeholder) {}
    }
    return placeholders to inline
}

/** What a `Text` node says about how it is set, read off its properties. */
internal fun Node.textInput(): TextInput {
    val blob = (property(PropertyKind.Spans) as? PropertyValue.Bytes)?.value
    val decoded = blob?.let(::decodeRuns)
    return TextInput(
        text = text(PropertyKind.Text),
        runs = decoded ?: emptyList(),
        runFonts = bytes(PropertyKind.SpanFonts)?.let(SpanFontRecords::decodeBlob) ?: emptyMap(),
        noRole = (property(PropertyKind.TypeRole) as? PropertyValue.Integer)?.value == TYPE_ROLE_NONE.toLong(),
        role = typeRole(),
        fontSize = floatProp(PropertyKind.FontSize),
        fontWeight = intProp(PropertyKind.FontWeight)?.toInt(),
        letterSpacing = floatProp(PropertyKind.LetterSpacing),
        lineHeight = floatProp(PropertyKind.LineHeight),
        maxLines = maxLines(),
        softWrap = (property(PropertyKind.SoftWrap) as? PropertyValue.Bool)?.value != false,
        tabSize = intProp(PropertyKind.TabSize)?.toInt() ?: TextInput.DEFAULT_TAB_SIZE,
        wordBreak = role(PropertyKind.WordBreak, WordBreak.entries.toTypedArray()),
        overflowWrap = role(PropertyKind.OverflowWrap, OverflowWrap.entries.toTypedArray()),
        absoluteSize = when (val value = property(PropertyKind.AbsoluteSize)) {
            is PropertyValue.Bool -> value.value
            is PropertyValue.Integer -> value.value != 0L
            else -> false
        },
        fonts = bytes(PropertyKind.Font)?.let(FontRefRecords::decodeBlob) ?: emptyList(),
        color = paintProp(PropertyKind.Color),
        textAlign = role(PropertyKind.TextAlign, TextAlign.entries.toTypedArray()),
        overflow = overflow(),
    )
}

/** What is malformed about a `Text` node's run list, or null where nothing is. */
internal fun Node.runsDecodeProblem(): String? {
    val spans = (property(PropertyKind.Spans) as? PropertyValue.Bytes)?.value
    if (spans != null && decodeRuns(spans) == null) {
        return "the run list is not a whole number of records"
    }
    bytes(PropertyKind.SpanFonts)?.let {
        if (SpanFontRecords.decodeBlob(it) == null) return "the run font table does not decode"
    }
    return null
}

/** What is malformed about a `Text` node's font list, or null where nothing is. */
internal fun Node.fontListProblem(): String? {
    bytes(PropertyKind.Font)?.let {
        if (FontRefRecords.decodeBlob(it) == null) return "the font list does not decode"
    }
    return null
}
