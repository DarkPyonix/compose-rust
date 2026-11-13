package dev.darkpyonix.composerust.foundation.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.foundation.CodePointerTarget
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.GutterDecorationKind
import dev.darkpyonix.composerust.protocol.MinimapSide
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.RevealAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a diagnostic is marked under the text it is about. */
enum class UnderlineShape { Wavy, Straight, Dotted }

/**
 * The ways an editor can draw its text and take input. Which one is used is decided by
 * measuring all three against a document of a hundred thousand lines; the harness that does
 * it is `experiments/code-editor-frames`. Every one of them takes input through Compose's own
 * platform text input, so an input method composes exactly as it does in a text field.
 */
enum class CodeEditorPath {
    /**
     * One `BasicTextField` over a `TextFieldState` that holds only the lines around what is
     * on screen, refilled as the view scrolls. Layout costs what the window costs, whatever
     * the length of the document.
     */
    Windowed,

    /**
     * One `BasicTextField` holding the whole document. Simplest, and the baseline the other
     * two are measured against: its layout grows with the document.
     */
    Whole,

    /**
     * Text drawn line by line, only the lines on screen, with input taken through the
     * platform's text input session directly. Supplied by the platforms that have such a
     * session through [drawnCodeEditorSurface]; elsewhere it falls back to [Windowed].
     */
    Drawn,
}

/**
 * The path an editor draws with. [CodeEditorPath.Windowed] until the hundred thousand line
 * measurement says otherwise; changing it is the whole of switching paths.
 */
var defaultCodeEditorPath: CodeEditorPath = CodeEditorPath.Windowed

/**
 * The drawn path, where the platform provides one. Its input needs the platform's own text
 * input session, which is not the same type on any two platforms, so it is installed by the
 * platform rather than written here.
 */
var drawnCodeEditorSurface: (@Composable (CodeEditorModel, CodeEditorLook, CodeEditorCallbacks, Modifier, @Composable (EditorScrolling) -> Unit) -> Unit)? = null

/**
 * Everything a surface needs to draw an editor, already resolved from the design system.
 *
 * Plain values and two lookups rather than the design system itself, so a surface can be
 * driven by the measurement harness without a theme.
 */
class CodeEditorLook(
    /** The monospace rung of the type ladder, in the text's ink. */
    val textStyle: TextStyle,
    val container: Color,
    val gutter: Color,
    val lineNumber: Color,
    val currentLineNumber: Color,
    val currentLine: Color,
    val currentLineBorder: Color,
    val gutterDivider: Color,
    val gutterDividerWidth: Dp,
    val gutterPadding: Dp,
    val textInset: Dp,
    val selection: Color,
    val cursor: Color,
    val underlineWidth: Dp,
    /** The colour and shape of one underline. */
    val underline: (LiveDecoration) -> Pair<Color, UnderlineShape>,
    /** The colour a run's paint resolves to. */
    val paint: (Paint) -> Color,
    val ghostTextAlpha: Float,
    val lens: Color,
    val tabWidth: Int,
    val hoverDelayMillis: Long,
    /** Whether the platform's command key is Command, as on Apple's systems, or Control. */
    val commandIsMeta: Boolean,
    /** The colour of a mark beside a line number, given the role the Host named, if it named one. */
    val gutterMark: (ColorRole?) -> Color = { lens },
    /** The ink of the marker that folds or unfolds a stretch. */
    val foldMarker: Color = lineNumber,
    /** The width of a change-marker bar, which does not scale with the row. */
    val changeBarWidth: Dp = 4.dp,
    /** The space between a change-marker bar and what comes after it. */
    val changeBarGap: Dp = 12.dp,
    /** The word a screen reader hears for a changed line. */
    val changeMarkWord: (ChangeMark) -> String = { localizedChangeMarkWord(it) },
    /** Whether the line numbers are drawn. Without them the number column takes no room. */
    val lineNumbers: Boolean = true,
    /** The least width of the line number column, its padding included. */
    val gutterMinWidth: Dp = 0.dp,
    /**
     * The runs of a piece of shown text that are set in a later family of the application's
     * own font list, because the first family has no glyph for them. Null where the text is
     * set in the design system's face.
     */
    val fontFallback: ((String) -> List<AnnotatedString.Range<SpanStyle>>)? = null,
    /**
     * The tint a line background in a role is drawn with, across the editor's width and
     * past the end of the line. Unspecified draws nothing.
     */
    val lineBackground: (ColorRole) -> Color = { Color.Unspecified },
    /** The stronger tint a range background in a role is drawn with over its characters. */
    val rangeBackground: (ColorRole) -> Color = { Color.Unspecified },
    /** The side the minimap is drawn on, or null where the editor draws none. */
    val minimapSide: MinimapSide? = null,
    /** How wide the minimap is. */
    val minimapWidth: Dp = 72.dp,
    /** The mark over the part of the document that is on screen, in the minimap. */
    val minimapSlider: Color = lineNumber.copy(alpha = lineNumber.alpha * 0.22f),
)

/** The room the minimap takes on the left of the text and on its right, in pixels. */
internal fun Density.minimapInsetsPx(look: CodeEditorLook): Pair<Int, Int> {
    val width = look.minimapWidth.roundToPx()
    return when (look.minimapSide) {
        MinimapSide.Left -> width to 0
        MinimapSide.Right -> 0 to width
        null -> 0 to 0
    }
}

/**
 * The width of the line number column: the digits of the last line with the padding on both
 * sides, never narrower than the look's minimum, and the divider after it. Nothing at all
 * where the numbers are not drawn.
 */
internal fun Density.numberColumnPx(look: CodeEditorLook, digits: Int, digitWidth: Int): Int {
    if (!look.lineNumbers) return 0
    val natural = digits * digitWidth + (look.gutterPadding * 2).roundToPx()
    return max(natural, look.gutterMinWidth.roundToPx()) + look.gutterDividerWidth.roundToPx()
}

/** The divider's width where there is a number column for it to close, and none otherwise. */
internal fun Density.gutterDividerPx(look: CodeEditorLook): Float =
    if (look.lineNumbers) look.gutterDividerWidth.toPx() else 0f

/**
 * Goes to the offset the Host asked for, once the view has a height to clamp it by. The
 * limit is set first because this can run before the pass that sets it.
 */
internal fun scrollToRequest(
    model: CodeEditorModel,
    vertical: EditorScroll,
    request: ScrollRequest,
    totalPx: Int,
    viewportHeight: Int,
) {
    vertical.max = totalPx - viewportHeight
    vertical.scrollTo(request.offsetPx)
    model.scrollDone(request)
}

/** The word for a changed line in [language], in the same set of languages as the sidebar's. */
internal fun localizedChangeMarkWord(change: ChangeMark, language: String = androidx.compose.ui.text.intl.Locale.current.language): String =
    when (language) {
        "ko" -> when (change) {
            ChangeMark.Added -> "추가됨"
            ChangeMark.Modified -> "수정됨"
            ChangeMark.Removed -> "삭제됨"
        }
        else -> when (change) {
            ChangeMark.Added -> "added"
            ChangeMark.Modified -> "modified"
            ChangeMark.Removed -> "removed"
        }
    }

/** The room the gutter keeps for change bars and the gap behind them: none when no line has one. */
internal fun Density.changeColumnPx(model: CodeEditorModel, look: CodeEditorLook): Int =
    if (model.hasChangeBars) (look.changeBarWidth + look.changeBarGap).roundToPx() else 0

/**
 * What a screen reader hears about the changed lines, or null when there are none. The
 * editor is one node, so the lines are listed under the word for their change.
 */
internal fun changeDescription(model: CodeEditorModel, look: CodeEditorLook): String? {
    val lines = model.changeLines()
    if (lines.isEmpty()) return null
    return ChangeMark.entries.filter { it in lines }.joinToString("; ") { change ->
        val numbers = lines.getValue(change)
        val shown = numbers.take(CHANGE_LINES_SPOKEN).joinToString(", ") { (it + 1).toString() }
        val rest = if (numbers.size > CHANGE_LINES_SPOKEN) ", +${numbers.size - CHANGE_LINES_SPOKEN}" else ""
        "${look.changeMarkWord(change)}: $shown$rest"
    }
}

private const val CHANGE_LINES_SPOKEN = 50

/** Gives the editor's node the description of its changed lines, when it has any. */
@Composable
internal fun Modifier.changeSemantics(model: CodeEditorModel, look: CodeEditorLook): Modifier {
    val revision = model.overlayRevision
    val text = remember(model, revision, look) { changeDescription(model, look) } ?: return this
    return semantics { stateDescription = text }
}

/** What a surface tells the editor that holds it. All of them are called on the UI thread. */
class CodeEditorCallbacks(
    /** The model has something queued for the Host. */
    val onOutput: () -> Unit,
    /** The reader pressed the save shortcut. */
    val onSave: () -> Unit,
    /**
     * A pointer came to rest, or left. `decoration` is the id of the hover anchor under it,
     * or zero.
     */
    val onHover: (decoration: Long, position: CodePosition, rest: Boolean) -> Unit,
    /**
     * A key event, offered before the editor does anything with it. True means the Host
     * took the key and the editor leaves it alone. [composing] says an input method is
     * composing, which the Host is never offered a key during. Every event comes through
     * here, releases too, so a held key's repeats can be told from its first press.
     */
    val onKeyPreview: (event: KeyEvent, composing: Boolean) -> Boolean = { _, _ -> false },
    /**
     * What the editor's view carries for the Host's pointer events over it, given what is
     * under a point of the view. Nothing where the Host does not listen.
     */
    val codePointer: @Composable (CodePointerTarget) -> Modifier = { Modifier },
)

/** Test tag of the field a field surface types into. */
const val CODE_EDITOR_FIELD_TAG = "code-editor-field"

/** Draws an editor along [path]. */
@Composable
fun CodeEditorSurface(
    model: CodeEditorModel,
    look: CodeEditorLook,
    callbacks: CodeEditorCallbacks,
    modifier: Modifier = Modifier,
    path: CodeEditorPath = defaultCodeEditorPath,
    focusRequest: Long = 0L,
    scrolling: @Composable (EditorScrolling) -> Unit = {},
) {
    when (path) {
        CodeEditorPath.Windowed -> FieldSurface(model, look, callbacks, modifier, whole = false, focusRequest = focusRequest, scrolling = scrolling)
        CodeEditorPath.Whole -> FieldSurface(model, look, callbacks, modifier, whole = true, focusRequest = focusRequest, scrolling = scrolling)
        CodeEditorPath.Drawn -> {
            val drawn = drawnCodeEditorSurface
            if (drawn != null) {
                drawn(model, look, callbacks, modifier, scrolling)
            } else {
                FieldSurface(model, look, callbacks, modifier, whole = false, focusRequest = focusRequest, scrolling = scrolling)
            }
        }
    }
}

/**
 * How an editor's view scrolls, handed to whoever wires it to the Host: the offset down the
 * document, which the editor keeps itself, the offset along the lines where the path
 * scrolls that way, and the height of the view in pixels.
 */
class EditorScrolling internal constructor(
    internal val vertical: EditorScroll,
    internal val horizontal: ScrollState?,
    internal val viewportHeight: () -> Int,
)

/**
 * Where each line's rows are, counting the rows a lens above a line and a suggestion of
 * several lines add, the rows a long line wraps onto, and the room a view zone takes after
 * its line. Every row is the same height, because every line is set in the same face; a view
 * zone is the one thing that need not be a whole number of rows, so it is counted in pixels
 * on top of the rows.
 */
internal class RowMap(
    val lineCount: Int,
    private val lensLines: IntArray,
    private val ghostLine: Int,
    private val ghostRows: Int,
    /** The lines a fold hides, as pairs of first and last line, in order and not overlapping. */
    private val hidden: IntArray = IntArray(0),
    /**
     * The rows each line takes past its first where long lines wrap, as running totals: entry
     * `i` counts the lines before line `i`, and a hidden line counts none. Null where nothing
     * wraps.
     */
    private val wrapPrefix: IntArray? = null,
    /** The line each view zone follows, one entry per zone, in order. */
    private val zoneLines: IntArray = IntArray(0),
    /** Each zone's height, in rows. */
    private val zoneHeights: FloatArray = FloatArray(0),
    /** The dimmed text each zone shows. */
    private val zoneTexts: Array<String> = emptyArray(),
    /** Whether a zone takes whole rows, for a path that can only show whole rows. */
    private val wholeZoneRows: Boolean = false,
) {
    private val hiddenRanges = hidden.size / 2

    /** How many hidden lines there are in the ranges before range `i`. */
    private val hiddenPrefix = IntArray(hiddenRanges + 1).also { prefix ->
        for (index in 0 until hiddenRanges) {
            prefix[index + 1] = prefix[index] + (hidden[index * 2 + 1] - hidden[index * 2] + 1)
        }
    }

    private val wrapTotal = wrapPrefix?.get(lineCount) ?: 0

    val totalRows: Int = lineCount - hiddenPrefix[hiddenRanges] + lensLines.size + ghostRows + wrapTotal

    private fun lensesBefore(line: Int): Int {
        var low = 0
        var high = lensLines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lensLines[middle] < line) low = middle + 1 else high = middle
        }
        return low
    }

    /** The range of hidden lines that holds [line], or -1. */
    private fun hiddenRangeOf(line: Int): Int {
        var low = 0
        var high = hiddenRanges
        while (low < high) {
            val middle = (low + high) ushr 1
            if (hidden[middle * 2 + 1] < line) low = middle + 1 else high = middle
        }
        return if (low < hiddenRanges && hidden[low * 2] <= line) low else -1
    }

    /** Whether a fold hides this line. */
    fun isHidden(line: Int): Boolean = hiddenRangeOf(line) >= 0

    /** How many lines before [line] a fold hides. */
    private fun hiddenBefore(line: Int): Int {
        var low = 0
        var high = hiddenRanges
        while (low < high) {
            val middle = (low + high) ushr 1
            if (hidden[middle * 2] < line) low = middle + 1 else high = middle
        }
        if (low == 0) return 0
        val index = low - 1
        return hiddenPrefix[index] + (min(hidden[index * 2 + 1], line - 1) - hidden[index * 2] + 1)
    }

    fun hasLens(line: Int): Boolean {
        val index = lensesBefore(line)
        return index < lensLines.size && lensLines[index] == line
    }

    /** How many rows [line]'s own text takes: one, and more where it wraps. */
    fun rowsOf(line: Int): Int {
        val prefix = wrapPrefix ?: return 1
        if (line < 0 || line >= lineCount) return 1
        return 1 + prefix[line + 1] - prefix[line]
    }

    /** The first row that belongs to [line], its lens if it has one. A hidden line has no row of its own. */
    fun rowTop(line: Int): Int {
        val wrapped = wrapPrefix?.get(line.coerceIn(0, lineCount)) ?: 0
        return line - hiddenBefore(line) + lensesBefore(line) + wrapped + if (ghostLine in 0 until line) ghostRows else 0
    }

    /** The row [line]'s own text starts on. */
    fun textRow(line: Int): Int = rowTop(line) + if (hasLens(line)) 1 else 0

    /** The line a row belongs to, never one a fold hides. */
    fun lineAtRow(row: Int): Int {
        var low = 0
        var high = lineCount - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (rowTop(middle) <= row) low = middle else high = middle - 1
        }
        // Hidden lines share the row of the line after them, so the search can end on one
        // only when the fold runs to the end of the document: the line that stays is above.
        val range = hiddenRangeOf(low)
        return if (range >= 0) max(0, hidden[range * 2] - 1) else low
    }

    // The view zones, which take pixels rather than rows.

    /** How many view zones there are, and the line each follows, its height and its text. */
    val zoneCount: Int get() = zoneLines.size
    fun zoneLine(index: Int): Int = zoneLines[index]
    fun zoneText(index: Int): String = zoneTexts[index]

    /** The pixels zone [index] takes where a row is [rowPx] tall: at least one. */
    fun zoneHeightPx(index: Int, rowPx: Int): Int {
        val height = zoneHeights[index]
        return if (wholeZoneRows) max(1, height.roundToInt()) * rowPx else max(1, (height * rowPx).roundToInt())
    }

    private var zonePrefixRow = -1
    private var zonePrefix = IntArray(1)

    /** The pixels of the zones before zone `i`, for a row [rowPx] tall. */
    private fun zonePrefix(rowPx: Int): IntArray {
        if (zonePrefixRow == rowPx) return zonePrefix
        val prefix = IntArray(zoneLines.size + 1)
        for (index in zoneLines.indices) prefix[index + 1] = prefix[index] + zoneHeightPx(index, rowPx)
        zonePrefix = prefix
        zonePrefixRow = rowPx
        return prefix
    }

    /** The pixels the zones after lines before [line] take. */
    fun zonesBeforePx(line: Int, rowPx: Int): Int {
        if (zoneLines.isEmpty()) return 0
        var low = 0
        var high = zoneLines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (zoneLines[middle] < line) low = middle + 1 else high = middle
        }
        return zonePrefix(rowPx)[low]
    }

    /** The whole height of the document, its zones included. */
    fun totalPx(rowPx: Int): Int = totalRows * rowPx + if (zoneLines.isEmpty()) 0 else zonePrefix(rowPx)[zoneLines.size]

    /** Where [line]'s first row starts, its lens if it has one, in pixels from the top of the document. */
    fun lineTopPx(line: Int, rowPx: Int): Int = rowTop(line) * rowPx + zonesBeforePx(line, rowPx)

    /** Where [line]'s own text starts. */
    fun textTopPx(line: Int, rowPx: Int): Int = textRow(line) * rowPx + zonesBeforePx(line, rowPx)

    /** Where zone [index] starts: after its line's rows and any suggestion rows below it, and the zones before it. */
    fun zoneTopPx(index: Int, rowPx: Int): Int {
        val line = zoneLines[index]
        val rows = textRow(line) + rowsOf(line) + if (ghostLine == line) ghostRows else 0
        return rows * rowPx + zonePrefix(rowPx)[index]
    }

    /**
     * The line a point [y] pixels down the document belongs to, never one a fold hides. A
     * point in a zone belongs to the line the zone follows.
     */
    fun lineAtPx(y: Int, rowPx: Int): Int {
        if (zoneLines.isEmpty()) return lineAtRow(if (y < 0) -1 else y / rowPx)
        var low = 0
        var high = lineCount - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (lineTopPx(middle, rowPx) <= y) low = middle else high = middle - 1
        }
        val range = hiddenRangeOf(low)
        return if (range >= 0) max(0, hidden[range * 2] - 1) else low
    }

    /**
     * Which of [line]'s own rows a point [y] pixels down the document is on, or -1 where it is
     * not on one: on its lens, on a suggestion's rows, or in a zone after it.
     */
    fun textRowAtPx(line: Int, y: Int, rowPx: Int): Int {
        val offset = y - textTopPx(line, rowPx)
        if (offset < 0) return -1
        val row = offset / rowPx
        return if (row < rowsOf(line)) row else -1
    }

    companion object {
        fun of(model: CodeEditorModel, wholeZoneRows: Boolean = false): RowMap {
            val hidden = model.hiddenLines()
            val lenses = model.lensesByLine().map { it.first }.filter { line -> !model.isHidden(line) }.toIntArray()
            val ghost = model.ghostText?.takeIf { !model.isHidden(it.start.line) }
            // A zone after a line a fold hides is folded away with it.
            val zones = model.viewZones.filter { !model.isHidden(it.afterLine) }.sortedBy { it.afterLine }
            return RowMap(
                model.document.lineCount,
                lenses,
                ghost?.start?.line ?: -1,
                ghost?.text?.count { it == '\n' } ?: 0,
                hidden,
                model.wrapPrefix(),
                IntArray(zones.size) { zones[it].afterLine },
                FloatArray(zones.size) { zones[it].heightLines },
                Array(zones.size) { zones[it].text },
                wholeZoneRows,
            )
        }
    }
}

/** One stretch the output transformation shows differently from the text underneath. */
internal class Insertion(
    /** Where it is in the field's own text. */
    val offset: Int,
    /** How much of that text it covers: one for a tab, none for what is only shown. */
    val covers: Int,
    val text: String,
    val style: SpanStyle?,
    /** The lenses a lens row shows, for pressing them. */
    val lenses: List<LiveDecoration>? = null,
    /**
     * Whether this is a view zone: rows shown after the end of a line, which a caret at the
     * end of that line stays in front of.
     */
    val zone: Boolean = false,
)

/**
 * The part of the document a field surface holds, and the bookkeeping that keeps the field,
 * the model and the Host in step.
 *
 * Everything here runs on the UI thread, from composition's side effects and from input
 * handlers, because those are the places a boundary call may be made from.
 */
internal class FieldWindow(val model: CodeEditorModel, private val whole: Boolean) {
    val state = TextFieldState()

    /** The lines the field holds: [start] up to, not including, [end]. */
    var start = 0
        private set
    var end = 0
        private set

    /** The field's text as last agreed with the model. */
    private var committed = ""
    private var committedRef: CharSequence? = null
    var lineStarts = IntArray(1)
        private set
    private var syncedRevision = -1

    /** The selection was outside the window when it was filled, so the field's is a stand-in. */
    var caretOutside = false
        private set
    private var lastSelection: TextRange? = null
    var presses by mutableIntStateOf(0)
    private var seenPresses = 0

    /** Changes when the window is refilled, for whatever draws from it. */
    var revision by mutableIntStateOf(0)
        private set

    /** What the output transformation last showed, in field offsets. */
    var insertions: List<Insertion> = emptyList()

    /** Asks the surface to bring the caret into view. */
    var scrollToCaret by mutableIntStateOf(0)

    fun toDoc(offset: Int): CodePosition {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (lineStarts[middle] <= offset) low = middle else high = middle - 1
        }
        return CodePosition(start + low, offset - lineStarts[low])
    }

    /** Where a document position is in the field, or null where the window does not hold it. */
    fun toField(position: CodePosition): Int? {
        if (position.line < start || position.line >= end) return null
        return lineStarts[position.line - start] + position.column
    }

    /** Fills the field with lines [first] up to [last], keeping the selection where it was. */
    fun load(first: Int, last: Int) {
        val document = model.document
        val from = first.coerceIn(0, document.lineCount - 1)
        val to = last.coerceIn(from + 1, document.lineCount)
        val builder = StringBuilder()
        val starts = IntArray(to - from)
        for (line in from until to) {
            starts[line - from] = builder.length
            builder.append(document.line(line))
            if (line < to - 1) builder.append('\n')
        }
        start = from
        end = to
        lineStarts = starts
        committed = builder.toString()
        val selection = model.selection
        val anchor = toField(selection.anchor)
        val caret = toField(selection.caret)
        caretOutside = anchor == null || caret == null
        val text = committed
        state.edit {
            replace(0, length, text)
            this.selection = if (anchor != null && caret != null) TextRange(anchor, caret) else TextRange.Zero
        }
        committedRef = state.text
        lastSelection = state.selection
        syncedRevision = model.contentRevision
        revision += 1
    }

    /**
     * The Host set the selection. The field keeps its own copy, so it is carried across by
     * filling the field again: the lines it holds if both ends are among them, and the
     * lines around the caret if not.
     */
    fun applyHostSelection() {
        if (state.composition != null) return
        syncUserEdit()
        val selection = model.selection
        if (toField(selection.anchor) != null && toField(selection.caret) != null) {
            load(start, end)
        } else {
            loadAroundCaret()
        }
    }

    /** Fills the field around the caret, wherever the view is. */
    fun loadAroundCaret() {
        val line = model.selection.caret.line
        val span = max(end - start, MIN_WINDOW)
        load(line - span / 2, line + span / 2)
    }

    /**
     * Commits what the reader changed in the field since it last agreed with the model, if
     * anything, and never while an input method is composing.
     */
    fun syncUserEdit(): Boolean {
        if (state.composition != null) return false
        val text = state.text
        if (text === committedRef) return false
        committedRef = text
        val old = committed
        if (text.length == old.length && text.contentEquals(old)) return false
        val limit = min(old.length, text.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == text[prefix]) prefix += 1
        if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix -= 1
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == text[text.length - 1 - suffix]) suffix += 1
        if (suffix > 0 && old[old.length - suffix].isLowSurrogate() && old.length - suffix - 1 >= prefix) suffix -= 1
        // Text typed beside the same text is found at the far end of the run, which is not
        // always where the caret was. Where the caret now stands right after the typed text
        // and the insertion can be read there as well, it is read there.
        val typed = text.length - suffix - prefix
        val caretNow = state.selection.takeIf { it.collapsed }?.end
        if (old.length - suffix == prefix && caretNow != null && caretNow - typed in 0 until prefix) {
            val at = caretNow - typed
            if (text.regionMatches(0, old, 0, at) && text.regionMatches(at + typed, old, at, old.length - at)) {
                suffix = old.length - at
                prefix = at
            }
        }
        val startPosition = toDoc(prefix)
        val endPosition = toDoc(old.length - suffix)
        val inserted = text.subSequence(prefix, text.length - suffix).toString()
        val origin = if (startPosition == endPosition && inserted.length in 1..2 && '\n' !in inserted) {
            EditOrigin.Typing
        } else {
            EditOrigin.Reader
        }
        committed = text.toString()
        lineStarts = lineStartsOf(committed)
        end = start + lineStarts.size
        if (model.secondary.isNotEmpty()) {
            // The field has one caret of its own. What the reader did at it is done at every
            // other selection too, and the field is filled again from the model, which now
            // holds all of it.
            if (model.editLikePrimary(model.selection, startPosition, endPosition, inserted, origin)) {
                load(start, end)
                return true
            }
            model.dropSecondary()
        }
        model.edit(startPosition, endPosition, inserted, origin)
        syncedRevision = model.contentRevision
        lastSelection = null
        syncSelection()
        revision += 1
        return true
    }

    /** Tells the model where the reader put the selection. */
    fun syncSelection() {
        val selection = state.selection
        if (presses != seenPresses) {
            seenPresses = presses
            caretOutside = false
            lastSelection = null
            // A press puts the caret in one place: the other selections go.
            model.dropSecondary()
        }
        if (selection == lastSelection || caretOutside) return
        lastSelection = selection
        val moved = CodeSelection(toDoc(selection.start), toDoc(selection.end))
        val ghost = model.ghostText
        if (ghost != null && model.selection != moved && moved.caret != ghost.start) model.dismissGhostText()
        model.selection = moved
    }

    /**
     * Brings the field, the model and the view into step: the reader's edit first, then a
     * change that came from elsewhere, then the selection, then which lines are held.
     */
    fun sync(visibleFirst: Int, visibleLast: Int) {
        if (state.composition != null) return
        syncUserEdit()
        val lineCount = model.document.lineCount
        if (model.contentRevision != syncedRevision) {
            load(start, if (whole) lineCount else max(end, start + 1))
        }
        syncSelection()
        if (whole) {
            if (start != 0 || end != lineCount) load(0, lineCount)
            return
        }
        val count = visibleLast - visibleFirst + 1
        val margin = max(count, MIN_MARGIN)
        val outside = visibleFirst >= end || visibleLast < start
        val nearTop = start > 0 && visibleFirst < start + margin / 3
        val nearBottom = end < lineCount && visibleLast > end - 1 - margin / 3
        if (outside || nearTop || nearBottom) load(visibleFirst - margin, visibleLast + 1 + margin)
    }

    /** Selects the whole document, which means holding all of it. */
    fun selectAll() {
        syncUserEdit()
        model.selection = CodeSelection(CodePosition.Zero, model.document.end)
        load(0, model.document.lineCount)
    }

    /** The field offset a shown offset stands for, and the insertion it falls in, if any. */
    fun toFieldOffset(shown: Int): Pair<Int, Insertion?> {
        var delta = 0
        for (insertion in insertions) {
            val shownStart = insertion.offset + delta
            if (shown < shownStart) break
            val shownEnd = shownStart + insertion.text.length
            if (shown < shownEnd) {
                return insertion.offset to insertion.takeIf { it.covers == 0 || it.lenses != null || it.zone }
            }
            // A stretch a fold hides shows as nothing, so the place where it was is one place:
            // the end of the line that stays.
            if (insertion.text.isEmpty() && insertion.covers > 0 && shown == shownStart) {
                return insertion.offset to null
            }
            delta += insertion.text.length - insertion.covers
        }
        return (shown - delta) to null
    }

    /** Where a field offset is shown. An end stays before what is shown at it, a start after. */
    fun toShown(offset: Int, asEnd: Boolean): Int {
        var delta = 0
        for (insertion in insertions) {
            if (insertion.offset > offset) break
            if (insertion.offset == offset && (asEnd || insertion.covers > 0 || insertion.zone)) break
            if (insertion.text.isEmpty() && insertion.covers > 0 && offset < insertion.offset + insertion.covers) {
                // Inside text a fold hides: nothing of it is shown, so it is where the fold is.
                return insertion.offset + delta
            }
            delta += insertion.text.length - insertion.covers
        }
        return offset + delta
    }

    companion object {
        /** Lines held beyond the screen on either side, at the least. */
        const val MIN_MARGIN = 40
        const val MIN_WINDOW = 120

        fun lineStartsOf(text: String): IntArray {
            var count = 1
            for (character in text) if (character == '\n') count += 1
            val starts = IntArray(count)
            var line = 1
            for (index in text.indices) if (text[index] == '\n') starts[line++] = index + 1
            return starts
        }
    }
}

/**
 * Keeps the reader's indentation when they start a new line. What is typed still arrives
 * through the platform's text input; this only adds the previous line's leading whitespace
 * after a line break the reader typed.
 */
internal object KeepIndentation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val original = originalText
        val current = asCharSequence()
        if (current.length != original.length + 1) return
        val limit = original.length
        var prefix = 0
        while (prefix < limit && original[prefix] == current[prefix]) prefix += 1
        if (current[prefix] != '\n') return
        // The rest has to be what was there, or this was not a single line break.
        for (index in prefix until limit) if (original[index] != current[index + 1]) return
        var lineStart = prefix
        while (lineStart > 0 && original[lineStart - 1] != '\n') lineStart -= 1
        var indentEnd = lineStart
        while (indentEnd < prefix && (original[indentEnd] == ' ' || original[indentEnd] == '\t')) indentEnd += 1
        if (indentEnd == lineStart) return
        val indent = original.subSequence(lineStart, indentEnd).toString()
        replace(prefix + 1, prefix + 1, indent)
        selection = TextRange(prefix + 1 + indent.length)
    }
}

/**
 * Shows what the Host drew over the window's text: colour runs, lenses above their lines, a
 * suggestion in place, and tabs at the width the design system gives. Nothing here changes
 * the text itself, so the caret steps over all of it.
 */
internal class EditorDisplay(
    private val window: FieldWindow,
    private val look: CodeEditorLook,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val model = window.model
        // Read so that new decorations and new runs show without the text changing.
        model.overlayRevision
        window.revision
        val start = window.start
        val text = asCharSequence().toString()
        // Line starts read from the text being shown rather than from the window, which may
        // be a step behind it for the frame between the reader's edit and its commit.
        val starts = FieldWindow.lineStartsOf(text)
        val end = start + starts.size
        fun fieldOffset(position: CodePosition): Int? {
            val index = position.line - start
            if (index < 0 || index >= starts.size) return null
            val lineEnd = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
            return min(starts[index] + position.column, lineEnd)
        }
        val insertions = ArrayList<Insertion>()
        val lensStyle = SpanStyle(color = look.lens)
        fun lineEnd(index: Int) = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
        // The lines a fold hides are cut out of what is shown. The text underneath keeps
        // them, so every position in the document is still where it was.
        val hidden = model.hiddenLines()
        var cut = 0
        while (cut < hidden.size) {
            val first = max(hidden[cut], start)
            val last = min(hidden[cut + 1], end - 1)
            if (first <= last) {
                if (first > start) {
                    val from = lineEnd(first - 1 - start)
                    insertions += Insertion(from, lineEnd(last - start) - from, "", null)
                } else {
                    val from = starts[first - start]
                    val to = if (last + 1 - start < starts.size) starts[last + 1 - start] else text.length
                    insertions += Insertion(from, to - from, "", null)
                }
            }
            cut += 2
        }
        for ((line, lenses) in model.lensesByLine()) {
            if (line < start || line >= end || model.isHidden(line)) continue
            val label = lenses.joinToString("  |  ") { it.text }
            insertions += Insertion(starts[line - start], 0, "$label\n", lensStyle, lenses)
        }
        val ghost = model.ghostText
        if (ghost != null && ghost.text.isNotEmpty() && !model.isHidden(ghost.start.line)) {
            val at = fieldOffset(ghost.start)
            if (at != null) {
                val ink = look.textStyle.color.takeOrElse(look.lens)
                insertions += Insertion(at, 0, ghost.text, SpanStyle(color = ink.copy(alpha = look.ghostTextAlpha)))
            }
        }
        // A view zone is rows after the end of its line, shown and never part of the text: as
        // many whole rows as it is high, its text dimmed on the first of them. The zones after
        // one line are one stretch, put in place of the line's own break, so that every row of
        // it starts inside what is only shown and the next line starts where it ends. After a
        // line a fold starts on, they go after the lines the fold hides.
        if (model.viewZones.isNotEmpty()) {
            val zoneStyle = SpanStyle(color = look.lens)
            val byLine = model.viewZones
                .filter { it.afterLine in start until end && !model.isHidden(it.afterLine) }
                .groupBy { it.afterLine }
            for ((line, zones) in byLine) {
                var last = line
                while (last + 1 < end && model.isHidden(last + 1)) last += 1
                val body = StringBuilder()
                for (zone in zones) {
                    val rows = max(1, zone.heightLines.roundToInt())
                    for (row in 0 until rows) {
                        // A row with nothing in it still holds a space, so that it starts on a
                        // character that is only shown.
                        body.append('\n').append(if (row == 0) zone.text.substringBefore('\n').ifEmpty { " " } else " ")
                    }
                }
                val at = lineEnd(last - start)
                if (last - start + 1 < starts.size) {
                    body.append('\n')
                    insertions += Insertion(at, 1, body.toString(), zoneStyle, zone = true)
                } else {
                    insertions += Insertion(at, 0, body.toString(), zoneStyle, zone = true)
                }
            }
        }
        // Tabs advance to the next stop, counted from the start of their own line.
        var column = 0
        var lineIndex = 0
        var hiddenLine = model.isHidden(start)
        for (index in text.indices) {
            when (text[index]) {
                '\n' -> {
                    column = 0
                    lineIndex += 1
                    hiddenLine = model.isHidden(start + lineIndex)
                }
                '\t' -> if (hiddenLine) {
                    // Cut out with the rest of its line; nothing is drawn in its place.
                } else {
                    val width = look.tabWidth - column % look.tabWidth
                    insertions += Insertion(index, 1, " ".repeat(width), null)
                    column += width
                }
                else -> column += 1
            }
        }
        // Lens rows and suggestions go in front of the text at the same offset, and a tab
        // after both. A zone goes after a suggestion at the end of its line, and in front of
        // the lines a fold cuts out after it.
        insertions.sortWith(compareBy<Insertion> { it.offset }.thenBy { it.covers }.thenBy { it.zone })
        window.insertions = insertions
        for (index in insertions.indices.reversed()) {
            val insertion = insertions[index]
            replace(insertion.offset, insertion.offset + insertion.covers, insertion.text)
        }
        var delta = 0
        for (insertion in insertions) {
            val style = insertion.style
            if (style != null) {
                val at = insertion.offset + delta
                addStyle(style, at, at + insertion.text.length)
            }
            delta += insertion.text.length - insertion.covers
        }
        for (span in model.spansInLines(start, end)) {
            val from = fieldOffset(maxOf(span.start, CodePosition(start, 0))) ?: continue
            val to = fieldOffset(minOf(span.end, CodePosition(end - 1, Int.MAX_VALUE / 2))) ?: continue
            if (to <= from) continue
            val shownFrom = window.toShown(from, asEnd = false)
            val shownTo = window.toShown(to, asEnd = true)
            // A run that lies wholly in a hidden stretch has nothing left to colour.
            if (shownTo <= shownFrom) continue
            addStyle(SpanStyle(color = look.paint(span.paint)), shownFrom, shownTo)
        }
        // Letters the first family of the application's font list lacks are set in the next
        // family that has them, over the text as it is shown.
        look.fontFallback?.let { fallback ->
            for (piece in fallback(asCharSequence().toString())) addStyle(piece.item, piece.start, piece.end)
        }
    }
}

private fun Color.takeOrElse(other: Color): Color = if (this == Color.Unspecified) other else this

/** How tall an editor is where nothing above it says. */
internal val DEFAULT_EDITOR_HEIGHT = 320.dp

/**
 * Gives an editor a height where its parent offers an unbounded one, a column that scrolls
 * for instance. Without it the editor would be as tall as its document, which for a long
 * file is a page of hundreds of thousands of pixels and no editor at all.
 */
internal fun Modifier.boundedHeight(fallback: Dp): Modifier = layout { measurable, constraints ->
    val bounded = if (constraints.hasBoundedHeight) {
        constraints
    } else {
        constraints.copy(maxHeight = fallback.roundToPx().coerceAtLeast(constraints.minHeight))
    }
    val placeable = measurable.measure(bounded)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * How far an editor is scrolled, in pixels, kept by the editor rather than by a scroll
 * container.
 *
 * A scroll container measures its content at full height, and a hundred thousand lines at
 * twenty pixels is two million pixels, which no layout constraint can hold. So nothing is
 * ever measured at the document's height: the editor is the size of its viewport, the
 * lines on screen are placed at their row minus this offset, and the extent is a number
 * the offset is clamped to.
 */
internal class EditorScroll {
    var offset by mutableIntStateOf(0)
        private set

    /** The furthest the view can scroll: the document's height less the viewport's. */
    var max: Int
        get() = limit
        set(value) {
            limit = value.coerceAtLeast(0)
            if (offset > limit) offset = limit
        }

    /** Kept as state so that whoever reports the range hears it change. */
    private var limit by mutableIntStateOf(0)

    /** The part of a pixel a scroll gesture has moved and the offset has not yet taken. */
    private var remainder = 0f

    val scrollable: ScrollableState = ScrollableState { delta ->
        val before = offset
        val exact = before - delta + remainder
        val next = exact.roundToInt().coerceIn(0, max)
        remainder = if (next == exact.roundToInt()) exact - next else 0f
        offset = next
        (before - next).toFloat()
    }

    fun scrollTo(value: Int) {
        remainder = 0f
        offset = value.coerceIn(0, max)
    }
}

/** The row height in whole pixels, so every row starts on a pixel and rows never drift. */
internal fun rowHeightPx(style: TextStyle, density: Density): Int = with(density) {
    val lineHeight = if (style.lineHeight.isSp) style.lineHeight else (style.fontSize.value * 1.4f).sp
    max(1, lineHeight.toPx().roundToInt())
}

/** A rectangle in root pixels as dp of the window's content, at [density] pixels a dp. */
internal fun Rect.toDp(density: Float): CodeRectDp =
    CodeRectDp(left / density, top / density, width / density, height / density)

/**
 * Whether a caret rectangle in root pixels can be seen in an editor whose view is
 * [viewport], with its text starting [textLeft] pixels in: some of the row is inside the
 * view, and the caret is right of the gutter and not past the right edge.
 */
internal fun caretOnScreen(rect: Rect, viewport: LayoutCoordinates?, textLeft: Float, rightInset: Float = 0f): Boolean {
    val view = viewport?.takeIf { it.isAttached } ?: return false
    val origin = view.localToRoot(Offset.Zero)
    val top = origin.y
    val bottom = origin.y + view.size.height
    val left = origin.x + textLeft
    val right = origin.x + view.size.width - rightInset
    // Half a pixel either way, for a caret that stands exactly on the gutter's edge.
    return rect.bottom > top && rect.top < bottom && rect.left >= left - 0.5f && rect.left <= right + 0.5f
}

/**
 * Where a field surface draws a document position, for the caret report and the Host's
 * position queries.
 *
 * Its fields are refreshed on every composition and read after layout, from the draw pass
 * and from the layout callbacks, where the field's text layout and its place on screen are
 * the ones the reader sees.
 */
internal class FieldPlaces(private val window: FieldWindow) : CodePointerTarget {
    /** The field itself, where its text layout's coordinates start. */
    var field: LayoutCoordinates? = null

    /** The editor's view: everything the reader can see of it. */
    var viewport: LayoutCoordinates? = null
    var layout: (() -> TextLayoutResult?)? = null
    var gutterWidthPx = 0f
    var textLeftPx = 0f

    /** What a minimap on the right takes off the text's width. */
    var rightInsetPx = 0f
    var density = 1f

    /** The width the field draws its caret at. */
    var caretWidthPx = 2f

    /** The function the model finds positions with, the same one for as long as this lives. */
    val locate: (CodePosition) -> CodeRectDp? = { position ->
        caretInRoot(position)?.takeIf { caretOnScreen(it, viewport, textLeftPx, rightInsetPx) }?.toDp(density)
    }

    /**
     * Where a caret at [position] is drawn, in root pixels, or null where the field does
     * not hold the position: a line outside its window, or one a fold hides.
     */
    fun caretInRoot(position: CodePosition): Rect? {
        val model = window.model
        if (!model.document.isValid(position) || model.isHidden(position.line)) return null
        val offset = window.toField(position) ?: return null
        val result = layout?.invoke() ?: return null
        val coordinates = field?.takeIf { it.isAttached } ?: return null
        var shown = window.toShown(offset, asEnd = false)
        // A suggestion shows after the caret that stands where it starts.
        val ghost = model.ghostText
        if (ghost != null && ghost.start == position && ghost.text.isNotEmpty()) shown -= ghost.text.length
        val cursor = result.getCursorRect(shown.coerceIn(0, result.layoutInput.text.length))
        return Rect(coordinates.localToRoot(Offset(cursor.left, cursor.top)), Size(caretWidthPx, cursor.height))
    }

    /** Where the primary caret is drawn in the editor's own pixels, or null where it is not on screen. */
    fun caretInView(): Rect? {
        val view = viewport?.takeIf { it.isAttached } ?: return null
        val rect = caretInRoot(window.model.selection.caret)?.takeIf { caretOnScreen(it, viewport, textLeftPx, rightInsetPx) } ?: return null
        return rect.translate(-view.localToRoot(Offset.Zero))
    }

    override fun positionAt(x: Float, y: Float): CodePosition? {
        if (x < gutterWidthPx) return null
        val view = viewport?.takeIf { it.isAttached } ?: return null
        val coordinates = field?.takeIf { it.isAttached } ?: return null
        val result = layout?.invoke() ?: return null
        val point = coordinates.localPositionOf(view, Offset(x, y))
        if (point.x < 0f || point.y < 0f || point.y >= result.size.height) return null
        // Past the end of the line it is on, the pointer is over no character.
        val line = result.getLineForVerticalPosition(point.y)
        if (point.x > result.getLineRight(line)) return null
        val (offset, insertion) = window.toFieldOffset(result.getOffsetForPosition(point))
        // A lens row and a suggestion are shown, not the document's text.
        if (insertion != null) return null
        return window.toDoc(offset)
    }

    /**
     * Tells the model how to find positions and where the caret is, and answers the queries
     * that waited for the editor to be drawn. Answers whether the Host has something to hear.
     */
    fun report(): Boolean {
        // Before the field and the view have both been placed, nothing found here would be
        // where the reader sees it, and a waiting query would be answered with nothing.
        val ready = field?.isAttached == true && viewport?.isAttached == true && layout?.invoke() != null
        if (!ready) return false
        val model = window.model
        model.locator = locate
        if (!window.caretOutside) {
            val caret = model.selection.caret
            caretInRoot(caret)?.let { model.reportCaretRect(CaretReport(caret, it.toDp(density))) }
        }
        val answered = model.hasWaitingQueries
        if (answered) model.answerWaitingQueries()
        return answered || model.caretUntold
    }
}

/**
 * The two field paths. [whole] holds every line; otherwise the field holds the lines around
 * the screen and is refilled as the view scrolls.
 */
@Composable
private fun FieldSurface(
    model: CodeEditorModel,
    look: CodeEditorLook,
    callbacks: CodeEditorCallbacks,
    modifier: Modifier,
    whole: Boolean,
    focusRequest: Long = 0L,
    scrolling: @Composable (EditorScrolling) -> Unit = {},
) {
    val density = LocalDensity.current
    val rowPx = remember(look.textStyle, density) { rowHeightPx(look.textStyle, density) }
    val style = remember(look.textStyle, rowPx, density) {
        look.textStyle.copy(
            lineHeight = with(density) { rowPx.toSp() },
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    }
    val measurer = rememberTextMeasurer()
    val digitWidth = remember(style) { measurer.measure("0", style).size.width }
    val vertical = remember { EditorScroll() }
    val horizontal = rememberScrollState()
    val window = remember(model, whole) { FieldWindow(model, whole) }
    val display = remember(window, look) { EditorDisplay(window, look) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    scrolling(remember(vertical, horizontal) { EditorScrolling(vertical, horizontal) { viewportHeight } })
    // A holder rather than a state: the field hands over a new reader on every layout, and
    // keeping it in a state would recompose this surface once per layout for nothing. The
    // reader itself reads the field's layout state, so what draws from it still follows.
    val layoutHolder = remember { arrayOfNulls<() -> TextLayoutResult?>(1) }
    val focus = remember { FocusRequester() }
    val overlayFocusStack = dev.darkpyonix.composerust.foundation.LocalOverlayFocusStack.current
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0L) focus.requestFocus()
    }

    // Read here so the row map and the gutter follow every change of text and overlay.
    val contentRevision = model.contentRevision
    val overlayRevision = model.overlayRevision
    // A view zone shows as whole rows of the field's own text, so it is counted as whole rows.
    val rows = remember(contentRevision, overlayRevision) { RowMap.of(model, wholeZoneRows = true) }
    val lineCount = rows.lineCount
    val digits = lineCount.toString().length
    val hasMarks = model.hasOrdinaryMarks
    val hasFolds = model.folds.isNotEmpty()
    val markColumnPx = if (hasMarks) rowPx else 0
    val foldColumnPx = if (hasFolds) rowPx else 0
    val gutterWidthPx = density.numberColumnPx(look, digits, digitWidth) +
        markColumnPx + foldColumnPx + density.changeColumnPx(model, look)

    // Refilling the window recomposes this, so the field moves to where the new lines are.
    window.revision
    Sync(window, rows, vertical, viewportHeight, rowPx, callbacks)

    LaunchedEffect(window.scrollToCaret) {
        if (window.scrollToCaret == 0) return@LaunchedEffect
        val top = RowMap.of(model, wholeZoneRows = true).textTopPx(model.selection.caret.line, rowPx)
        if (top < vertical.offset || top + rowPx > vertical.offset + viewportHeight) {
            vertical.scrollTo(top - viewportHeight / 2)
        }
    }

    // The Host moved the selection: the field holds a copy of it that has to follow.
    val hostSelection = model.hostSelection
    LaunchedEffect(hostSelection) {
        if (hostSelection == 0) return@LaunchedEffect
        window.applyHostSelection()
        callbacks.onOutput()
    }

    // The Host asked for a range to be brought into view.
    val reveal = model.reveal
    LaunchedEffect(reveal) {
        if (reveal == null) return@LaunchedEffect
        // The effect can start before the pass that sets the limit has run, and a scroll
        // clamped to the old limit of 0 would go nowhere.
        vertical.max = rows.totalPx(rowPx) - viewportHeight
        scrollToReveal(RowMap.of(model, wholeZoneRows = true), vertical, reveal, rowPx, viewportHeight)
        model.revealDone(reveal)
        callbacks.onOutput()
    }

    // The Host asked for an offset. It waits for the first measure: before it the view has
    // no height, and an offset clamped by a height of nothing would be the wrong one.
    val scrollRequest = model.scrollRequest
    val measured = viewportHeight > 0
    LaunchedEffect(scrollRequest, measured) {
        if (scrollRequest == null || !measured) return@LaunchedEffect
        scrollToRequest(model, vertical, scrollRequest, rows.totalPx(rowPx), viewportHeight)
    }

    val hover = remember { HoverTracker() }
    HoverReport(hover, look.hoverDelayMillis, callbacks)

    val textInsetPx = with(density) { look.textInset.roundToPx() }
    val (minimapLeft, minimapRight) = density.minimapInsetsPx(look)

    // Where the caret is drawn and where a position is, read after layout. What they find
    // is sent from a coroutine rather than from inside the pass that found it.
    val places = remember(window) { FieldPlaces(window).also { it.layout = { layoutHolder[0]?.invoke() } } }
    places.gutterWidthPx = (minimapLeft + gutterWidthPx).toFloat()
    places.textLeftPx = (minimapLeft + gutterWidthPx + textInsetPx).toFloat()
    places.rightInsetPx = minimapRight.toFloat()
    places.density = density.density
    places.caretWidthPx = with(density) { 2.dp.toPx() }
    val scope = rememberCoroutineScope()
    val currentCallbacks by rememberUpdatedState(callbacks)
    val reportPlaces: () -> Unit = remember(places, scope) {
        { if (places.report()) scope.launch { currentCallbacks.onOutput() } }
    }
    DisposableEffect(model, places) {
        onDispose { if (model.locator === places.locate) model.locator = null }
    }

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(look.cursor, look.selection),
    ) {
        Box(
            modifier
                .boundedHeight(DEFAULT_EDITOR_HEIGHT)
                // Outside the field, so the Host hears a press before the field moves the
                // caret for it, and over the gutter as well as the text.
                .then(callbacks.codePointer(places))
                .changeSemantics(model, look)
                .background(look.container)
                .clipToBounds()
                .onSizeChanged { size ->
                    viewportHeight = size.height
                    viewportWidth = size.width
                }
                .onGloballyPositioned { coordinates ->
                    places.viewport = coordinates
                    reportPlaces()
                },
        ) {
            // The extent the offset may run to. A number, never a measured height.
            val extent = rows.totalPx(rowPx) - viewportHeight
            SideEffect { vertical.max = extent }
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = with(density) { minimapLeft.toDp() },
                        end = with(density) { minimapRight.toDp() },
                    )
                    .scrollable(vertical.scrollable, Orientation.Vertical)
                    .drawBehind {
                        translate(top = -vertical.offset.toFloat()) {
                            drawCurrentLine(window, rows, rowPx, look)
                            drawLineBackgrounds(model, rows, rowPx, vertical.offset, viewportHeight, 0f, look)
                        }
                        // Read so that typing, a move of the caret and a scroll along the
                        // lines all draw this again, and the caret is reported from here.
                        window.state.text
                        window.state.selection
                        horizontal.value
                        reportPlaces()
                    },
            ) {
                Box(
                    Modifier
                        .width(with(density) { gutterWidthPx.toDp() })
                        .fillMaxHeight()
                        .background(look.gutter)
                        .pointerInput(model, rows, markColumnPx, foldColumnPx) {
                            detectTapGestures { point ->
                                pressGutter(
                                    model,
                                    rows,
                                    point,
                                    vertical.offset,
                                    rowPx,
                                    gutterWidthPx,
                                    markColumnPx,
                                    foldColumnPx,
                                )
                                callbacks.onOutput()
                            }
                        }
                        .drawBehind {
                            val scroll = vertical.offset
                            translate(top = -scroll.toFloat()) {
                                drawGutter(
                                    window,
                                    rows,
                                    rowPx,
                                    scroll,
                                    viewportHeight,
                                    gutterWidthPx,
                                    markColumnPx,
                                    foldColumnPx,
                                    measurer,
                                    style,
                                    look,
                                )
                            }
                            val divider = gutterDividerPx(look)
                            if (divider > 0f && look.gutterDivider.alpha > 0f) {
                                drawRect(look.gutterDivider, Offset(size.width - divider, 0f), Size(divider, size.height))
                            }
                        },
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .horizontalScroll(horizontal),
                ) {
                    val minWidthPx = (viewportWidth - gutterWidthPx - textInsetPx - minimapLeft - minimapRight).coerceAtLeast(0)
                    run {
                        BasicTextField(
                            state = window.state,
                            modifier = Modifier
                                // Measured at the window's own height, never the
                                // document's, and placed at its rows less the offset. Only
                                // the viewport's height is reported upwards.
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        Constraints(minWidth = minWidthPx, maxHeight = Constraints.Infinity),
                                    )
                                    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
                                    layout(placeable.width + textInsetPx, height) {
                                        placeable.place(textInsetPx, rows.lineTopPx(window.start, rowPx) - vertical.offset)
                                    }
                                }
                                .onGloballyPositioned { coordinates ->
                                    places.field = coordinates
                                    reportPlaces()
                                }
                                .onFocusChanged { focusState ->
                                    overlayFocusStack.noteFocused(focus, focusState.hasFocus)
                                    model.focusChanged(focusState.hasFocus)
                                    callbacks.onOutput()
                                }
                                .focusRequester(focus)
                                .testTag(CODE_EDITOR_FIELD_TAG)
                                .onPreviewKeyEvent { event ->
                                    // The Host hears the key first, and a key it takes is
                                    // not the editor's.
                                    callbacks.onKeyPreview(event, window.state.composition != null) ||
                                        handleKey(event, window, look, callbacks) { layoutHolder[0]?.invoke() }
                                }
                                .pointerInput(window, look) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull() ?: continue
                                            val result = layoutHolder[0]?.invoke()
                                            when (event.type) {
                                                // A press the Host took is not the editor's.
                                                PointerEventType.Press -> if (!change.isConsumed) {
                                                    val lens = result?.let {
                                                        lensAt(window, it, change.position)
                                                    }
                                                    // Alt and a press adds a caret there and
                                                    // leaves the others where they are.
                                                    val added = if (event.keyboardModifiers.isAltPressed && lens == null) {
                                                        result?.let { positionAt(window, it, change.position) }
                                                    } else {
                                                        null
                                                    }
                                                    if (added != null) {
                                                        change.consume()
                                                        window.syncUserEdit()
                                                        window.model.addCaret(added)
                                                        callbacks.onOutput()
                                                    } else if (lens != null) {
                                                        change.consume()
                                                        window.model.activate(lens)
                                                        callbacks.onOutput()
                                                    } else {
                                                        window.presses += 1
                                                    }
                                                }
                                                // A pointer arriving is as much a place to
                                                // rest as one that moved.
                                                PointerEventType.Move, PointerEventType.Enter -> {
                                                    val position = result?.let {
                                                        positionAt(window, it, change.position)
                                                    }
                                                    hover.moved(window.model, position)
                                                }
                                                PointerEventType.Exit -> hover.left()
                                                else -> {}
                                            }
                                        }
                                    }
                                }
                                .drawBehind {
                                    window.state.text
                                    val result = layoutHolder[0]?.invoke() ?: return@drawBehind
                                    drawDecorations(window, result, look)
                                },
                            inputTransformation = KeepIndentation,
                            textStyle = style,
                            onTextLayout = { getResult -> layoutHolder[0] = getResult },
                            cursorBrush = SolidColor(look.cursor),
                            outputTransformation = display,
                        )
                    }
                }
            }
            if (look.minimapSide != null) {
                Minimap(
                    model,
                    rows,
                    look,
                    vertical,
                    viewportHeight,
                    rowPx,
                    Modifier
                        .align(if (look.minimapSide == MinimapSide.Left) Alignment.TopStart else Alignment.TopEnd)
                        .scrollable(vertical.scrollable, Orientation.Vertical),
                )
            }
            SnippetChoices(model, look, { places.caretInView() }) { callbacks.onOutput() }
        }
    }
}

/**
 * Brings the window into step after every change, on the composition thread. Its own
 * composable so that reading the field's text and the scroll position recomposes this and
 * nothing else.
 */
@Composable
private fun Sync(
    window: FieldWindow,
    rows: RowMap,
    vertical: EditorScroll,
    viewportHeight: Int,
    rowPx: Int,
    callbacks: CodeEditorCallbacks,
) {
    // Each read subscribes this scope, so typing, moving the caret, a press, a change from
    // elsewhere and scrolling all come through here.
    window.state.text
    window.state.selection
    window.state.composition
    window.presses
    window.model.contentRevision
    val scroll = vertical.offset
    val firstVisible = rows.lineAtPx(scroll, rowPx)
    val lastVisible = rows.lineAtPx(scroll + max(viewportHeight, rowPx), rowPx)
    val composing = window.state.composition != null
    SideEffect {
        // The start of a composition is said before anything else this pass does; its end
        // after the text it committed, so the Host reads the change and then the signal.
        if (composing) window.model.compositionChanged(true)
        if (window.end == 0) window.load(firstVisible - FieldWindow.MIN_MARGIN, lastVisible + FieldWindow.MIN_MARGIN)
        window.sync(firstVisible, lastVisible)
        if (!composing) window.model.compositionChanged(false)
        if (viewportHeight > 0) {
            reportVisible(window.model, rows, scroll, viewportHeight, rowPx)
            // Once the editor has a size it has been laid out, so the row height is the one
            // it draws with. The offset is read in this pass, so a frame says it once.
            window.model.reportGeometry(rowPx, scroll)
        }
        callbacks.onOutput()
    }
}

/** Tells the model which lines the view shows, folded lines left out. */
internal fun reportVisible(model: CodeEditorModel, rows: RowMap, scroll: Int, viewportHeight: Int, rowPx: Int) {
    val bottom = min(rows.totalPx(rowPx) - 1, scroll + viewportHeight - 1)
    model.reportVisibleRange(rows.lineAtPx(scroll, rowPx), rows.lineAtPx(max(0, bottom), rowPx))
}

/** Scrolls so that the range the Host named is on screen, the way it asked. */
internal fun scrollToReveal(
    rows: RowMap,
    vertical: EditorScroll,
    request: RevealRequest,
    rowPx: Int,
    viewportHeight: Int,
) {
    val top = rows.textTopPx(request.start.line, rowPx)
    val bottom = rows.textTopPx(request.end.line, rowPx) + rowPx
    when (request.align) {
        RevealAlign.Nearest -> when {
            top < vertical.offset || bottom - top > viewportHeight -> vertical.scrollTo(top)
            bottom > vertical.offset + viewportHeight -> vertical.scrollTo(bottom - viewportHeight)
        }
        RevealAlign.Center -> vertical.scrollTo((top + bottom) / 2 - viewportHeight / 2)
        RevealAlign.Top -> vertical.scrollTo(top)
    }
}

/**
 * A press in the gutter: on the fold marker it folds or unfolds the stretch that starts on
 * that line, and on a mark it reports the mark's id.
 */
internal fun pressGutter(
    model: CodeEditorModel,
    rows: RowMap,
    point: Offset,
    scroll: Int,
    rowPx: Int,
    gutterWidth: Int,
    markColumn: Int,
    foldColumn: Int,
) {
    val y = (point.y + scroll).toInt()
    if (point.y + scroll < 0f || y >= rows.totalPx(rowPx)) return
    val line = rows.lineAtPx(y, rowPx)
    // A lens sits in the row above its line, a wrapped line goes on below its first row and a
    // view zone after it, and the gutter has nothing to say on any of them.
    if (rows.textRowAtPx(line, y, rowPx) != 0) return
    if (foldColumn > 0 && point.x >= gutterWidth - foldColumn) {
        model.toggleFold(line)
    } else if (markColumn > 0 && point.x < markColumn) {
        model.ordinaryMarksAt(line).lastOrNull { it.id != 0L }?.let(model::activateGutter)
    }
}

/** The shortcuts an editor answers itself. Everything else goes to the field. */
private fun handleKey(
    event: KeyEvent,
    window: FieldWindow,
    look: CodeEditorLook,
    callbacks: CodeEditorCallbacks,
    layout: () -> TextLayoutResult?,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // Nothing is intercepted while an input method composes: its keys are its own.
    if (window.state.composition != null) return false
    val model = window.model
    val command = if (look.commandIsMeta) event.isMetaPressed else event.isCtrlPressed
    if (window.caretOutside && !command && isTyping(event)) {
        // The reader is about to type with the caret off screen: bring it back first, so
        // what they type lands where the caret is rather than at the top of the window.
        window.loadAroundCaret()
        window.scrollToCaret += 1
    }
    when {
        command && event.key == Key.S && !event.isShiftPressed -> {
            window.syncUserEdit()
            callbacks.onOutput()
            callbacks.onSave()
            return true
        }
        command && event.key == Key.Z && !event.isShiftPressed -> {
            window.syncUserEdit()
            if (model.undo()) window.scrollToCaret += 1
            callbacks.onOutput()
            return true
        }
        (command && event.key == Key.Z && event.isShiftPressed) ||
            (!look.commandIsMeta && event.isCtrlPressed && event.key == Key.Y) -> {
            window.syncUserEdit()
            if (model.redo()) window.scrollToCaret += 1
            callbacks.onOutput()
            return true
        }
        command && event.key == Key.A -> {
            window.selectAll()
            return true
        }
        // The next place the selected text is, as another selection.
        command && event.key == Key.D && !event.isShiftPressed && !event.isAltPressed -> {
            window.syncUserEdit()
            if (model.addNextMatch()) window.applyHostSelection()
            callbacks.onOutput()
            return true
        }
        // A caret on the line above or below the others.
        command && event.isAltPressed && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
            window.syncUserEdit()
            model.addCaretVertically(down = event.key == Key.DirectionDown)
            callbacks.onOutput()
            return true
        }
        // A snippet's list of choices takes the keys that move through it, pick from it and
        // close it; Tab and Shift+Tab step through the snippet's stops; Escape closes the
        // list, then ends the snippet, then drops the extra selections.
        model.snippetChoices != null && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
            model.moveChoice(if (event.key == Key.DirectionDown) 1 else -1)
            return true
        }
        model.snippetChoices != null && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
            window.syncUserEdit()
            model.pickChoice(model.choiceIndex)
            window.applyHostSelection()
            callbacks.onOutput()
            return true
        }
        event.key == Key.Tab && !command && model.snippetActive -> {
            window.syncUserEdit()
            if (event.isShiftPressed) model.previousSnippetStop() else model.nextSnippetStop()
            window.applyHostSelection()
            window.scrollToCaret += 1
            callbacks.onOutput()
            return true
        }
        event.key == Key.Escape && (model.closeChoices() || model.endSnippet()) -> {
            callbacks.onOutput()
            return true
        }
        event.key == Key.Escape && model.dropSecondary() -> {
            callbacks.onOutput()
            return true
        }
        event.key == Key.Escape -> {
            if (model.ghostText == null) return false
            model.dismissGhostText()
            return true
        }
        // The field moves its own caret; the others move the same way here, and the field
        // goes on to move its own.
        model.secondary.isNotEmpty() && !command && caretMove(event.key) != null -> {
            model.moveSecondary(caretMove(event.key)!!, event.isShiftPressed)
            if (!(model.viewZones.isNotEmpty() && (event.key == Key.DirectionUp || event.key == Key.DirectionDown))) return false
            val result = layout() ?: return false
            return moveOverRows(window, result, down = event.key == Key.DirectionDown, extend = event.isShiftPressed)
        }
        // The field moves between the rows it shows, and a view zone's rows are among them:
        // where there are zones the caret is moved here, past them.
        (event.key == Key.DirectionUp || event.key == Key.DirectionDown) && !command &&
            model.viewZones.isNotEmpty() -> {
            val result = layout() ?: return false
            return moveOverRows(window, result, down = event.key == Key.DirectionDown, extend = event.isShiftPressed)
        }
        event.key == Key.Tab && !event.isShiftPressed && !command -> {
            window.syncUserEdit()
            val ghost = model.ghostText
            if (ghost != null && model.selection.collapsed && model.selection.caret == ghost.start) {
                model.acceptGhostText()
                window.scrollToCaret += 1
                callbacks.onOutput()
                return true
            }
            insertIndentation(window, look.tabWidth)
            return true
        }
    }
    return false
}

/**
 * Moves the field's caret one row of the document's text up or down, keeping its distance
 * from the left, over the rows that are shown and are not text: a lens and a view zone.
 * Answers false where there is no such row on that side of the caret in the field, and the
 * field moves as it would on its own.
 */
private fun moveOverRows(window: FieldWindow, layout: TextLayoutResult, down: Boolean, extend: Boolean): Boolean {
    val current = window.state.selection
    val length = layout.layoutInput.text.length
    val shown = window.toShown(current.end, asEnd = false).coerceIn(0, length)
    val x = layout.getHorizontalPosition(shown, true)
    var row = layout.getLineForOffset(shown)
    val step = if (down) 1 else -1
    while (true) {
        row += step
        if (row < 0 || row >= layout.lineCount) return false
        val (_, insertion) = window.toFieldOffset(layout.getLineStart(row))
        if (insertion == null) break
    }
    val y = (layout.getLineTop(row) + layout.getLineBottom(row)) / 2f
    val (target, _) = window.toFieldOffset(layout.getOffsetForPosition(Offset(x, y)))
    window.state.edit {
        selection = if (extend) TextRange(current.start, target) else TextRange(target)
    }
    return true
}

/** How a key moves a caret, or null where it does not. */
internal fun caretMove(key: Key): CodeEditorModel.CaretMove? = when (key) {
    Key.DirectionLeft -> CodeEditorModel.CaretMove.Left
    Key.DirectionRight -> CodeEditorModel.CaretMove.Right
    Key.DirectionUp -> CodeEditorModel.CaretMove.Up
    Key.DirectionDown -> CodeEditorModel.CaretMove.Down
    Key.MoveHome -> CodeEditorModel.CaretMove.Home
    Key.MoveEnd -> CodeEditorModel.CaretMove.End
    else -> null
}

/** Whether a key press puts text in rather than moving or commanding. */
private fun isTyping(event: KeyEvent): Boolean = when (event.key) {
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd,
    Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight, Key.Escape,
    -> false
    else -> true
}

/**
 * Indents at the caret: a tab where the line is already indented with tabs, and spaces to
 * the next stop otherwise, so a file keeps the indentation it was written with.
 */
private fun insertIndentation(window: FieldWindow, tabWidth: Int) {
    val selection = window.state.selection
    val text = window.state.text
    var lineStart = selection.min
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart -= 1
    var indentEnd = lineStart
    while (indentEnd < text.length && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) indentEnd += 1
    val usesTabs = (lineStart until indentEnd).any { text[it] == '\t' }
    val column = selection.min - lineStart
    val indent = if (usesTabs) "\t" else " ".repeat(tabWidth - column % tabWidth)
    window.state.edit {
        replace(selection.min, selection.max, indent)
        this.selection = TextRange(selection.min + indent.length)
    }
}

/** The lens under a point in the field, if the point is on a lens row. */
private fun lensAt(window: FieldWindow, layout: TextLayoutResult, point: Offset): LiveDecoration? {
    val shown = layout.getOffsetForPosition(point)
    val (_, insertion) = window.toFieldOffset(shown)
    val lenses = insertion?.lenses ?: return null
    if (lenses.size == 1) return lenses.first()
    // Several lenses share a row, joined by a separator: find which one is under the point.
    var delta = 0
    for (other in window.insertions) {
        if (other === insertion) break
        delta += other.text.length - other.covers
    }
    val within = shown - (insertion.offset + delta)
    var at = 0
    for (lens in lenses) {
        val next = at + lens.text.length + LENS_SEPARATOR_LENGTH
        if (within < next) return lens
        at = next
    }
    return lenses.last()
}

private const val LENS_SEPARATOR_LENGTH = 5

/** The document position under a point in the field, or null over a lens or a suggestion. */
private fun positionAt(window: FieldWindow, layout: TextLayoutResult, point: Offset): CodePosition? {
    val shown = layout.getOffsetForPosition(point)
    val (offset, insertion) = window.toFieldOffset(shown)
    if (insertion != null) return null
    return window.toDoc(offset)
}

/** Where the pointer rests, decided on the UI thread after the design system's delay. */
internal class HoverTracker {
    /** The place the pointer moved to last, and when, as a state the reporter waits on. */
    var target by mutableStateOf<HoverTarget?>(null)

    /** What the Host was last told the pointer rests on, or null where it was told nothing. */
    var reported: HoverTarget? = null

    fun moved(model: CodeEditorModel, position: CodePosition?) {
        if (position == null) {
            left()
            return
        }
        val anchor = model.hoverAnchorAt(position)
        val next = HoverTarget(anchor?.id ?: 0L, position, anchor)
        val current = reported
        // Resting on the same anchor is still resting: nothing new to say.
        if (current != null && current.anchor != null && current.anchor === anchor) return
        if (target?.position == position) return
        target = next
    }

    fun left() {
        target = HoverTarget(0L, null, null)
    }
}

internal data class HoverTarget(val decoration: Long, val position: CodePosition?, val anchor: LiveDecoration?)

/** Waits out the rest delay and reports a rest or a departure, from the composition thread. */
@Composable
internal fun HoverReport(tracker: HoverTracker, delayMillis: Long, callbacks: CodeEditorCallbacks) {
    val target = tracker.target
    val currentCallbacks by rememberUpdatedState(callbacks)
    // Reported from the effect itself. A value parked in state for a side effect to pick up
    // is read by no composition, so writing it recomposes nothing and the report waits for
    // some later, unrelated recomposition. A new target cancels the wait for the old one.
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (target.position != null) delay(delayMillis)
        val previous = tracker.reported
        if (previous != null && previous.position != null &&
            (target.position == null || previous.anchor == null || previous.anchor !== target.anchor)
        ) {
            currentCallbacks.onHover(previous.decoration, previous.position, false)
            tracker.reported = null
        }
        if (target.position != null && tracker.reported == null) {
            currentCallbacks.onHover(target.decoration, target.position, true)
            tracker.reported = target
        }
    }
}

/**
 * The line backgrounds of the lines on screen, from [left] to the right edge whatever the
 * length of the line, over the current line's band and behind everything else.
 */
internal fun DrawScope.drawLineBackgrounds(
    model: CodeEditorModel,
    rows: RowMap,
    rowPx: Int,
    scroll: Int,
    viewportHeight: Int,
    left: Float,
    look: CodeEditorLook,
) {
    model.overlayRevision
    if (!model.backgroundsPresent) return
    val first = rows.lineAtPx(scroll, rowPx)
    val last = min(rows.lineCount - 1, rows.lineAtPx(scroll + viewportHeight + rowPx, rowPx))
    for (line in first..last) {
        if (rows.isHidden(line)) continue
        val background = model.lineBackgroundAt(line) ?: continue
        val color = look.lineBackground(background.color ?: continue)
        if (color == Color.Unspecified || color.alpha == 0f) continue
        val top = rows.textTopPx(line, rowPx).toFloat()
        drawRect(color, Offset(left, top), Size(size.width - left, rows.rowsOf(line) * rowPx.toFloat()))
    }
}

/** The line the caret is on, across the whole width, behind the text. */
private fun DrawScope.drawCurrentLine(window: FieldWindow, rows: RowMap, rowPx: Int, look: CodeEditorLook) {
    window.revision
    val selection = window.state.selection
    if (window.caretOutside || !selection.collapsed) return
    val line = window.toDoc(selection.end).line
    val top = rows.textTopPx(line, rowPx).toFloat()
    val height = rows.rowsOf(line) * rowPx.toFloat()
    if (look.currentLine.alpha > 0f) {
        drawRect(look.currentLine, Offset(0f, top), Size(size.width, height))
    }
    if (look.currentLineBorder.alpha > 0f) {
        val stroke = 1.dp.toPx()
        drawRect(
            look.currentLineBorder,
            Offset(stroke / 2, top + stroke / 2),
            Size(size.width - stroke, height - stroke),
            style = Stroke(stroke),
        )
    }
}

/**
 * The line numbers on screen, the marks and fold markers beside them, and the rule.
 *
 * From the left: the column of marks, when there are any, then the numbers, then the column
 * of fold markers, when anything can be folded, then the rule.
 */
private fun DrawScope.drawGutter(
    window: FieldWindow,
    rows: RowMap,
    rowPx: Int,
    scroll: Int,
    viewportHeight: Int,
    width: Int,
    markColumn: Int,
    foldColumn: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    look: CodeEditorLook,
) {
    window.revision
    window.model.overlayRevision
    val selection = window.state.selection
    val caretLine = if (window.caretOutside) -1 else window.toDoc(selection.end).line
    val first = rows.lineAtPx(scroll, rowPx)
    val last = min(rows.lineCount - 1, rows.lineAtPx(scroll + viewportHeight + rowPx, rowPx))
    val padding = look.gutterPadding.toPx()
    val divider = gutterDividerPx(look)
    val changeColumn = changeColumnPx(window.model, look)
    for (line in first..last) {
        if (rows.isHidden(line)) continue
        val top = rows.textTopPx(line, rowPx).toFloat()
        if (look.lineNumbers) {
            val label = (line + 1).toString()
            val color = if (line == caretLine) look.currentLineNumber else look.lineNumber
            // The same style as the text, so a number is the size of the code beside it.
            val measured = measurer.measure(label, style.copy(color = color))
            val x = width - divider - foldColumn - changeColumn - padding - measured.size.width
            drawText(measured, topLeft = Offset(x, top))
        }
        drawGutterAdornments(window.model, line, top, rowPx, markColumn, width - divider - foldColumn, foldColumn, changeColumn, measurer, style, look)
    }
}

/** The marks and the fold marker of one line, in the cells the gutter keeps for them. */
internal fun DrawScope.drawGutterAdornments(
    model: CodeEditorModel,
    line: Int,
    top: Float,
    rowPx: Int,
    markColumn: Int,
    foldLeft: Float,
    foldColumn: Int,
    changeColumn: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    look: CodeEditorLook,
) {
    val cell = rowPx.toFloat()
    // The change bar sits right of the numbers, the full row high, so that the bars of
    // neighbouring lines meet.
    val bar = model.changeBarAt(line)
    if (bar != null) {
        val left = foldLeft - changeColumn
        drawRect(look.gutterMark(bar.color), Offset(left, top), Size(look.changeBarWidth.toPx(), cell))
    }
    if (markColumn > 0) {
        var index = model.nextOrdinaryMarkIndex(line, 0)
        while (index >= 0) {
            val mark = model.gutterMarks[index]
            index = model.nextOrdinaryMarkIndex(line, index + 1)
            val color = look.gutterMark(mark.color)
            when (mark.kind) {
                GutterDecorationKind.Dot -> drawCircle(color, cell * 0.2f, Offset(markColumn / 2f, top + cell / 2f))
                GutterDecorationKind.Bar ->
                    drawRect(color, Offset(markColumn / 2f - cell * 0.1f, top), Size(cell * 0.2f, cell))
                GutterDecorationKind.Glyph -> {
                    val glyph = measurer.measure(codePointString(mark.glyph), style.copy(color = color))
                    drawText(glyph, topLeft = Offset((markColumn - glyph.size.width) / 2f, top + (cell - glyph.size.height) / 2f))
                }
            }
        }
    }
    val fold = if (foldColumn > 0) model.foldAt(line) else null
    if (fold != null) {
        val marker = measurer.measure(if (fold.collapsed) "\u25B8" else "\u25BE", style.copy(color = look.foldMarker))
        drawText(marker, topLeft = Offset(foldLeft + (foldColumn - marker.size.width) / 2f, top + (cell - marker.size.height) / 2f))
    }
}

/** A Unicode scalar value as a string, without the platform calls that only some platforms have. */
internal fun codePointString(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val offset = codePoint - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }

/** Underlines under their ranges, in the field's own coordinates. */
private fun DrawScope.drawDecorations(window: FieldWindow, layout: TextLayoutResult, look: CodeEditorLook) {
    val model = window.model
    model.overlayRevision
    window.revision
    val textLength = layout.layoutInput.text.length
    if (model.backgroundsPresent) drawRangeBackgrounds(window, layout, look)
    model.secondaryRevision
    if (model.secondary.isNotEmpty()) drawSecondarySelections(window, layout, look)
    for (decoration in model.decorations) {
        if (decoration.kind != DecorationKind.Underline) continue
        if (decoration.end.line < window.start || decoration.start.line >= window.end) continue
        val from = window.toField(maxOf(decoration.start, CodePosition(window.start, 0))) ?: continue
        val lastLine = window.end - 1
        val to = window.toField(minOf(decoration.end, CodePosition(lastLine, model.document.line(lastLine).length)))
            ?: continue
        val shownFrom = window.toShown(from, asEnd = false).coerceIn(0, textLength)
        val shownTo = window.toShown(to, asEnd = true).coerceIn(0, textLength)
        val (color, shape) = look.underline(decoration)
        val stroke = look.underlineWidth.toPx()
        val firstLine = layout.getLineForOffset(shownFrom)
        val lastShownLine = layout.getLineForOffset(shownTo)
        for (line in firstLine..lastShownLine) {
            val left = if (line == firstLine) layout.getHorizontalPosition(shownFrom, true) else layout.getLineLeft(line)
            var right = if (line == lastShownLine) layout.getHorizontalPosition(shownTo, true) else layout.getLineRight(line)
            // An empty range, at the end of a line for instance, still gets a mark a
            // character wide, or a problem there would be invisible.
            if (right - left < stroke * 4) right = left + (layout.getLineBottom(line) - layout.getLineTop(line)) / 2
            val y = layout.getLineBottom(line) - stroke * 1.5f
            drawUnderline(shape, color, left, right, y, stroke)
        }
    }
}

/**
 * The selections past the primary one, which the field does not know about: a selection's
 * tint over its characters, and a caret where it is collapsed, in the field's own
 * coordinates.
 */
private fun DrawScope.drawSecondarySelections(window: FieldWindow, layout: TextLayoutResult, look: CodeEditorLook) {
    val textLength = layout.layoutInput.text.length
    for (selection in window.model.secondary) {
        val from = window.toField(selection.start) ?: continue
        val to = window.toField(selection.end) ?: continue
        val shownFrom = window.toShown(from, asEnd = false).coerceIn(0, textLength)
        val shownTo = window.toShown(to, asEnd = true).coerceIn(0, textLength)
        if (shownFrom == shownTo) {
            val caret = layout.getCursorRect(shownFrom)
            drawRect(look.cursor, Offset(caret.left, caret.top), Size(2.dp.toPx(), caret.height))
            continue
        }
        val firstLine = layout.getLineForOffset(shownFrom)
        val lastLine = layout.getLineForOffset(shownTo)
        for (line in firstLine..lastLine) {
            val left = if (line == firstLine) layout.getHorizontalPosition(shownFrom, true) else layout.getLineLeft(line)
            val right = if (line == lastLine) layout.getHorizontalPosition(shownTo, true) else layout.getLineRight(line)
            if (right <= left) continue
            val top = layout.getLineTop(line)
            drawRect(look.selection, Offset(left, top), Size(right - left, layout.getLineBottom(line) - top))
        }
    }
}

/** The range backgrounds over the characters they cover, in the field's own coordinates. */
private fun DrawScope.drawRangeBackgrounds(window: FieldWindow, layout: TextLayoutResult, look: CodeEditorLook) {
    val model = window.model
    val textLength = layout.layoutInput.text.length
    for (decoration in model.decorations) {
        if (decoration.kind != DecorationKind.RangeBackground || decoration.start == decoration.end) continue
        if (decoration.end.line < window.start || decoration.start.line >= window.end) continue
        val color = look.rangeBackground(decoration.color ?: continue)
        if (color == Color.Unspecified || color.alpha == 0f) continue
        val from = window.toField(maxOf(decoration.start, CodePosition(window.start, 0))) ?: continue
        val lastLine = window.end - 1
        val to = window.toField(minOf(decoration.end, CodePosition(lastLine, model.document.line(lastLine).length)))
            ?: continue
        val shownFrom = window.toShown(from, asEnd = false).coerceIn(0, textLength)
        val shownTo = window.toShown(to, asEnd = true).coerceIn(0, textLength)
        if (shownTo <= shownFrom) continue
        val firstLine = layout.getLineForOffset(shownFrom)
        val lastShownLine = layout.getLineForOffset(shownTo)
        for (line in firstLine..lastShownLine) {
            val left = if (line == firstLine) layout.getHorizontalPosition(shownFrom, true) else layout.getLineLeft(line)
            val right = if (line == lastShownLine) layout.getHorizontalPosition(shownTo, true) else layout.getLineRight(line)
            if (right <= left) continue
            val top = layout.getLineTop(line)
            drawRect(color, Offset(left, top), Size(right - left, layout.getLineBottom(line) - top))
        }
    }
}

internal fun DrawScope.drawUnderline(shape: UnderlineShape, color: Color, left: Float, right: Float, y: Float, stroke: Float) {
    when (shape) {
        UnderlineShape.Straight -> drawLine(color, Offset(left, y), Offset(right, y), stroke)
        UnderlineShape.Dotted -> drawLine(
            color,
            Offset(left, y),
            Offset(right, y),
            stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke, stroke * 2)),
        )
        UnderlineShape.Wavy -> {
            val wave = stroke * 2.5f
            val path = Path()
            path.moveTo(left, y)
            var x = left
            var up = true
            while (x < right) {
                val next = min(x + wave, right)
                path.quadraticTo((x + next) / 2, if (up) y - wave / 2 else y + wave / 2, next, y)
                x = next
                up = !up
            }
            drawPath(path, color, style = Stroke(stroke))
        }
    }
}

/** Test tag of an editor's minimap. */
const val CODE_EDITOR_MINIMAP_TAG = "code-editor-minimap"

/** How many columns of a line the minimap shows: past them a line is cut off. */
private const val MINIMAP_COLUMNS = 120

/**
 * The whole document shrunk into a strip beside the text, with the part on screen marked.
 *
 * Every row of the document gets the same share of the strip's height, never more than a
 * couple of dp, so a short document is drawn at the top and a long one fills it. The strip is
 * drawn one pixel band at a time rather than one line at a time, so a document of a hundred
 * thousand lines costs what the strip's height costs. A press, or a drag, scrolls the editor
 * so that the row under the pointer is in the middle of the view.
 *
 * The decorations that say where something is show here too: a line background across the
 * strip, a range background over its characters, and an underline as a mark at the strip's
 * outer edge in its colour.
 */
@Composable
internal fun Minimap(
    model: CodeEditorModel,
    rows: RowMap,
    look: CodeEditorLook,
    vertical: EditorScroll,
    viewportHeight: Int,
    rowPx: Int,
    modifier: Modifier = Modifier,
) {
    val currentRows by rememberUpdatedState(rows)
    val currentViewport by rememberUpdatedState(viewportHeight)
    val currentRowPx by rememberUpdatedState(rowPx)
    val density = LocalDensity.current
    val maxRowPx = with(density) { MINIMAP_ROW_MAX.toPx() }
    Box(
        modifier
            .width(look.minimapWidth)
            .fillMaxHeight()
            .testTag(CODE_EDITOR_MINIMAP_TAG)
            .pointerInput(vertical) {
                awaitPointerEventScope {
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Press -> dragging = true
                            PointerEventType.Release -> {
                                dragging = false
                                continue
                            }
                            PointerEventType.Move -> if (!dragging || !change.pressed) continue
                            else -> continue
                        }
                        change.consume()
                        val total = currentRows.totalPx(currentRowPx)
                        val scale = minimapScale(total, currentRowPx, size.height.toFloat(), maxRowPx)
                        val at = (change.position.y / scale).toInt().coerceIn(0, max(0, total - 1))
                        vertical.max = total - currentViewport
                        vertical.scrollTo(at - currentViewport / 2)
                    }
                }
            }
            .drawBehind {
                model.contentRevision
                model.overlayRevision
                drawMinimap(model, rows, look, vertical.offset, viewportHeight, rowPx, maxRowPx)
            },
    )
}

/** The tallest a document row is drawn in the minimap. */
private val MINIMAP_ROW_MAX = 2.dp

/**
 * How many of a minimap's pixels one pixel of a document [totalPx] tall takes, in a minimap
 * [height] pixels tall, where a row is [rowPx] in the editor and at most [maxRowPx] in the
 * minimap.
 */
internal fun minimapScale(totalPx: Int, rowPx: Int, height: Float, maxRowPx: Float): Float {
    val most = maxRowPx / max(1, rowPx)
    return if (totalPx <= 0) most else min(most, height / totalPx)
}

private fun DrawScope.drawMinimap(
    model: CodeEditorModel,
    rows: RowMap,
    look: CodeEditorLook,
    scroll: Int,
    viewportHeight: Int,
    rowPx: Int,
    maxRowPx: Float,
) {
    val total = rows.totalPx(rowPx)
    if (total <= 0 || rowPx <= 0) return
    val scale = minimapScale(total, rowPx, size.height, maxRowPx)
    val charWidth = max(1f, size.width / MINIMAP_COLUMNS)
    val ink = look.textStyle.color.let { if (it == Color.Unspecified) look.lineNumber else it }
    val textInk = ink.copy(alpha = ink.alpha * 0.45f)
    val band = max(1f, rowPx * scale)
    val barHeight = max(1f, band * 0.75f)
    val contentHeight = total * scale
    // One document row per pixel band: where several rows share a band, the first one is drawn.
    var y = 0f
    var lastLine = -1
    while (y < contentHeight && y < size.height) {
        val at = (y / scale).toInt().coerceIn(0, total - 1)
        val line = rows.lineAtPx(at, rowPx)
        if (line != lastLine && rows.textTopPx(line, rowPx) <= at) {
            lastLine = line
            val top = rows.textTopPx(line, rowPx) * scale
            model.lineBackgroundAt(line)?.color?.let(look.lineBackground)
                ?.takeIf { it != Color.Unspecified && it.alpha > 0f }
                ?.let { drawRect(it.copy(alpha = min(1f, it.alpha * 2f)), Offset(0f, top), Size(size.width, band)) }
            val text = model.document.line(line)
            var column = 0
            var index = 0
            var runStart = -1
            while (index <= text.length && column <= MINIMAP_COLUMNS) {
                val blank = index == text.length || text[index] == ' ' || text[index] == '\t'
                if (blank && runStart >= 0) {
                    drawRect(textInk, Offset(runStart * charWidth, top), Size((column - runStart) * charWidth, barHeight))
                    runStart = -1
                } else if (!blank && runStart < 0) {
                    runStart = column
                }
                if (index < text.length) {
                    column += if (text[index] == '\t') look.tabWidth - column % look.tabWidth else 1
                }
                index += 1
            }
            for (span in model.spansInLines(line, line + 1)) {
                val from = if (span.start.line < line) 0 else displayColumn(text, span.start.column, look.tabWidth)
                val to = if (span.end.line > line) displayColumn(text, text.length, look.tabWidth)
                else displayColumn(text, span.end.column, look.tabWidth)
                val start = min(from, MINIMAP_COLUMNS)
                val end = min(to, MINIMAP_COLUMNS)
                if (end <= start) continue
                val color = look.paint(span.paint)
                drawRect(color.copy(alpha = color.alpha * 0.6f), Offset(start * charWidth, top), Size((end - start) * charWidth, barHeight))
            }
        }
        y += band
    }
    // Range backgrounds, such as what a search found, and the diagnostics, over the text.
    for (decoration in model.decorations) {
        when (decoration.kind) {
            DecorationKind.RangeBackground -> {
                val role = decoration.color ?: continue
                val color = look.rangeBackground(role).takeIf { it != Color.Unspecified && it.alpha > 0f } ?: continue
                for (line in decoration.start.line..decoration.end.line) {
                    if (line >= model.document.lineCount || rows.isHidden(line)) continue
                    val text = model.document.line(line)
                    val from = if (line == decoration.start.line) displayColumn(text, decoration.start.column, look.tabWidth) else 0
                    val to = if (line == decoration.end.line) displayColumn(text, decoration.end.column, look.tabWidth)
                    else displayColumn(text, text.length, look.tabWidth)
                    val top = rows.textTopPx(line, rowPx) * scale
                    val width = max(charWidth, (min(to, MINIMAP_COLUMNS) - min(from, MINIMAP_COLUMNS)) * charWidth)
                    drawRect(color.copy(alpha = min(1f, color.alpha * 2.5f)), Offset(min(from, MINIMAP_COLUMNS) * charWidth, top), Size(width, band))
                }
            }
            DecorationKind.Underline -> {
                if (decoration.start.line >= model.document.lineCount || rows.isHidden(decoration.start.line)) continue
                val (color, _) = look.underline(decoration)
                val top = rows.textTopPx(decoration.start.line, rowPx) * scale
                val mark = max(2f, charWidth * 3f)
                val x = if (look.minimapSide == MinimapSide.Left) 0f else size.width - mark
                drawRect(color, Offset(x, top), Size(mark, max(2f, band)))
            }
            else -> {}
        }
    }
    // The part of the document that is on screen.
    if (rowPx > 0 && viewportHeight > 0 && look.minimapSlider.alpha > 0f) {
        val top = scroll * scale
        val height = max(2f, viewportHeight * scale)
        drawRect(look.minimapSlider, Offset(0f, top), Size(size.width, height))
    }
}

/** How many columns of the screen the first [column] UTF-16 units of [text] take, with tabs advanced to their stops. */
internal fun displayColumn(text: String, column: Int, tabWidth: Int): Int {
    var shown = 0
    val end = min(column, text.length)
    for (index in 0 until end) shown += if (text[index] == '\t') tabWidth - shown % tabWidth else 1
    return shown
}

/** Test tag of the list a snippet's stop with choices opens. */
const val CODE_EDITOR_CHOICES_TAG = "code-editor-snippet-choices"

/**
 * The list a snippet's stop with choices opens, under the caret: each choice in the editor's
 * type, the marked one in the selection's tint. A press on one puts it in the stop.
 * [caret] is where the caret is in the editor's own pixels, or null where it is not on screen.
 */
@Composable
internal fun SnippetChoices(
    model: CodeEditorModel,
    look: CodeEditorLook,
    caret: () -> Rect?,
    onPicked: () -> Unit,
) {
    model.snippetRevision
    val choices = model.snippetChoices ?: return
    val at = caret() ?: return
    val marked = model.choiceIndex
    Column(
        Modifier
            .offset { IntOffset(at.left.roundToInt(), at.bottom.roundToInt()) }
            .testTag(CODE_EDITOR_CHOICES_TAG)
            .background(look.gutter)
            .border(1.dp, look.lineNumber.copy(alpha = look.lineNumber.alpha * 0.4f)),
    ) {
        for ((index, choice) in choices.withIndex()) {
            BasicText(
                choice,
                style = look.textStyle,
                modifier = Modifier
                    .background(if (index == marked) look.selection else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .pointerInput(model, index) {
                        detectTapGestures {
                            model.pickChoice(index)
                            onPicked()
                        }
                    },
            )
        }
    }
}
