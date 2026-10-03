package dioxus.compose.foundation.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import dioxus.compose.protocol.DecorationKind
import dioxus.compose.protocol.Paint
import kotlinx.coroutines.delay
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
var drawnCodeEditorSurface: (@Composable (CodeEditorModel, CodeEditorLook, CodeEditorCallbacks, Modifier) -> Unit)? = null

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
)

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
) {
    when (path) {
        CodeEditorPath.Windowed -> FieldSurface(model, look, callbacks, modifier, whole = false)
        CodeEditorPath.Whole -> FieldSurface(model, look, callbacks, modifier, whole = true)
        CodeEditorPath.Drawn -> {
            val drawn = drawnCodeEditorSurface
            if (drawn != null) {
                drawn(model, look, callbacks, modifier)
            } else {
                FieldSurface(model, look, callbacks, modifier, whole = false)
            }
        }
    }
}

/**
 * Where each line's rows are, counting the rows a lens above a line and a suggestion of
 * several lines add. Every row is the same height, because code does not wrap and every line
 * is set in the same face.
 */
internal class RowMap(
    val lineCount: Int,
    private val lensLines: IntArray,
    private val ghostLine: Int,
    private val ghostRows: Int,
) {
    val totalRows: Int = lineCount + lensLines.size + ghostRows

    private fun lensesBefore(line: Int): Int {
        var low = 0
        var high = lensLines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lensLines[middle] < line) low = middle + 1 else high = middle
        }
        return low
    }

    fun hasLens(line: Int): Boolean {
        val index = lensesBefore(line)
        return index < lensLines.size && lensLines[index] == line
    }

    /** The first row that belongs to [line], its lens if it has one. */
    fun rowTop(line: Int): Int =
        line + lensesBefore(line) + if (ghostLine in 0 until line) ghostRows else 0

    /** The row [line]'s own text starts on. */
    fun textRow(line: Int): Int = rowTop(line) + if (hasLens(line)) 1 else 0

    /** The line a row belongs to. */
    fun lineAtRow(row: Int): Int {
        var low = 0
        var high = lineCount - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (rowTop(middle) <= row) low = middle else high = middle - 1
        }
        return low
    }

    companion object {
        fun of(model: CodeEditorModel): RowMap {
            val lenses = model.lensesByLine().map { it.first }.toIntArray()
            val ghost = model.ghostText
            return RowMap(
                model.document.lineCount,
                lenses,
                ghost?.start?.line ?: -1,
                ghost?.text?.count { it == '\n' } ?: 0,
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
                return insertion.offset to insertion.takeIf { it.covers == 0 || it.lenses != null }
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
            if (insertion.offset == offset && (asEnd || insertion.covers > 0)) break
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
        for ((line, lenses) in model.lensesByLine()) {
            if (line < start || line >= end) continue
            val label = lenses.joinToString("  |  ") { it.text }
            insertions += Insertion(starts[line - start], 0, "$label\n", lensStyle, lenses)
        }
        val ghost = model.ghostText
        if (ghost != null && ghost.text.isNotEmpty()) {
            val at = fieldOffset(ghost.start)
            if (at != null) {
                val ink = look.textStyle.color.takeOrElse(look.lens)
                insertions += Insertion(at, 0, ghost.text, SpanStyle(color = ink.copy(alpha = look.ghostTextAlpha)))
            }
        }
        // Tabs advance to the next stop, counted from the start of their own line.
        var column = 0
        for (index in text.indices) {
            when (text[index]) {
                '\n' -> column = 0
                '\t' -> {
                    val width = look.tabWidth - column % look.tabWidth
                    insertions += Insertion(index, 1, " ".repeat(width), null)
                    column += width
                }
                else -> column += 1
            }
        }
        // Lens rows and suggestions go in front of the text at the same offset, and a tab
        // after both.
        insertions.sortWith(compareBy<Insertion> { it.offset }.thenBy { it.covers })
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
            addStyle(
                SpanStyle(color = look.paint(span.paint)),
                window.toShown(from, asEnd = false),
                window.toShown(to, asEnd = true),
            )
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

/** The row height in whole pixels, so every row starts on a pixel and rows never drift. */
internal fun rowHeightPx(style: TextStyle, density: Density): Int = with(density) {
    val lineHeight = if (style.lineHeight.isSp) style.lineHeight else (style.fontSize.value * 1.4f).sp
    max(1, lineHeight.toPx().roundToInt())
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
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val window = remember(model, whole) { FieldWindow(model, whole) }
    val display = remember(window, look) { EditorDisplay(window, look) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    // A holder rather than a state: the field hands over a new reader on every layout, and
    // keeping it in a state would recompose this surface once per layout for nothing. The
    // reader itself reads the field's layout state, so what draws from it still follows.
    val layoutHolder = remember { arrayOfNulls<() -> TextLayoutResult?>(1) }
    val focus = remember { FocusRequester() }

    // Read here so the row map and the gutter follow every change of text and overlay.
    val contentRevision = model.contentRevision
    val overlayRevision = model.overlayRevision
    val rows = remember(contentRevision, overlayRevision) { RowMap.of(model) }
    val lineCount = rows.lineCount
    val digits = lineCount.toString().length
    val gutterWidthPx = with(density) {
        digits * digitWidth + (look.gutterPadding * 2).roundToPx() + look.gutterDividerWidth.roundToPx()
    }

    // Refilling the window recomposes this, so the field moves to where the new lines are.
    window.revision
    Sync(window, rows, vertical, viewportHeight, rowPx, callbacks)

    LaunchedEffect(window.scrollToCaret) {
        if (window.scrollToCaret == 0) return@LaunchedEffect
        val caretRow = RowMap.of(model).textRow(model.selection.caret.line)
        val top = caretRow * rowPx
        if (top < vertical.value || top + rowPx > vertical.value + viewportHeight) {
            vertical.scrollTo((top - viewportHeight / 2).coerceAtLeast(0))
        }
    }

    val hover = remember { HoverTracker() }
    HoverReport(hover, look.hoverDelayMillis, callbacks)

    val textInsetPx = with(density) { look.textInset.roundToPx() }

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(look.cursor, look.selection),
    ) {
        Box(
            modifier
                .boundedHeight(DEFAULT_EDITOR_HEIGHT)
                .background(look.container)
                .clipToBounds()
                .onSizeChanged { size ->
                    viewportHeight = size.height
                    viewportWidth = size.width
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(vertical)
                    .height(with(density) { (rows.totalRows * rowPx).toDp() })
                    .drawBehind {
                        drawCurrentLine(window, rows, rowPx, look)
                    },
            ) {
                Row(Modifier.fillMaxHeight()) {
                    Box(
                        Modifier
                            .width(with(density) { gutterWidthPx.toDp() })
                            .fillMaxHeight()
                            .background(look.gutter)
                            .drawBehind {
                                drawGutter(
                                    window,
                                    rows,
                                    rowPx,
                                    vertical.value,
                                    viewportHeight,
                                    gutterWidthPx,
                                    measurer,
                                    style,
                                    look,
                                )
                            },
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .horizontalScroll(horizontal),
                    ) {
                        val minWidth = with(density) {
                            (viewportWidth - gutterWidthPx - textInsetPx).coerceAtLeast(0).toDp()
                        }
                        BasicTextField(
                            state = window.state,
                            modifier = Modifier
                                .offset { IntOffset(textInsetPx, rows.rowTop(window.start) * rowPx) }
                                .widthIn(min = minWidth)
                                .focusRequester(focus)
                                .testTag(CODE_EDITOR_FIELD_TAG)
                                .onPreviewKeyEvent { event ->
                                    handleKey(event, window, look, callbacks)
                                }
                                .pointerInput(window, look) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull() ?: continue
                                            val result = layoutHolder[0]?.invoke()
                                            when (event.type) {
                                                PointerEventType.Press -> {
                                                    val lens = result?.let {
                                                        lensAt(window, it, change.position)
                                                    }
                                                    if (lens != null) {
                                                        change.consume()
                                                        window.model.activate(lens)
                                                        callbacks.onOutput()
                                                    } else {
                                                        window.presses += 1
                                                    }
                                                }
                                                PointerEventType.Move -> {
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
    vertical: ScrollState,
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
    val scroll = vertical.value
    val firstVisible = rows.lineAtRow(scroll / rowPx)
    val lastVisible = rows.lineAtRow((scroll + max(viewportHeight, rowPx)) / rowPx)
    SideEffect {
        if (window.end == 0) window.load(firstVisible - FieldWindow.MIN_MARGIN, lastVisible + FieldWindow.MIN_MARGIN)
        window.sync(firstVisible, lastVisible)
        callbacks.onOutput()
    }
}

/** The shortcuts an editor answers itself. Everything else goes to the field. */
private fun handleKey(
    event: KeyEvent,
    window: FieldWindow,
    look: CodeEditorLook,
    callbacks: CodeEditorCallbacks,
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
        event.key == Key.Escape -> {
            if (model.ghostText == null) return false
            model.dismissGhostText()
            return true
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
    var settled by remember { mutableStateOf<HoverTarget?>(null) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (target.position != null) delay(delayMillis)
        settled = target
    }
    SideEffect {
        val rest = settled ?: return@SideEffect
        settled = null
        val previous = tracker.reported
        if (previous != null && previous.position != null &&
            (rest.position == null || previous.anchor == null || previous.anchor !== rest.anchor)
        ) {
            callbacks.onHover(previous.decoration, previous.position, false)
            tracker.reported = null
        }
        if (rest.position != null && tracker.reported == null) {
            callbacks.onHover(rest.decoration, rest.position, true)
            tracker.reported = rest
        }
    }
}

/** The line the caret is on, across the whole width, behind the text. */
private fun DrawScope.drawCurrentLine(window: FieldWindow, rows: RowMap, rowPx: Int, look: CodeEditorLook) {
    window.revision
    val selection = window.state.selection
    if (window.caretOutside || !selection.collapsed) return
    val line = window.toDoc(selection.end).line
    val top = rows.textRow(line) * rowPx.toFloat()
    if (look.currentLine.alpha > 0f) {
        drawRect(look.currentLine, Offset(0f, top), Size(size.width, rowPx.toFloat()))
    }
    if (look.currentLineBorder.alpha > 0f) {
        val stroke = 1.dp.toPx()
        drawRect(
            look.currentLineBorder,
            Offset(stroke / 2, top + stroke / 2),
            Size(size.width - stroke, rowPx - stroke),
            style = Stroke(stroke),
        )
    }
}

/** The line numbers on screen, and the rule beside them. */
private fun DrawScope.drawGutter(
    window: FieldWindow,
    rows: RowMap,
    rowPx: Int,
    scroll: Int,
    viewportHeight: Int,
    width: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    look: CodeEditorLook,
) {
    window.revision
    val selection = window.state.selection
    val caretLine = if (window.caretOutside) -1 else window.toDoc(selection.end).line
    val first = rows.lineAtRow(scroll / rowPx)
    val last = min(rows.lineCount - 1, rows.lineAtRow((scroll + viewportHeight) / rowPx + 1))
    val padding = look.gutterPadding.toPx()
    val divider = look.gutterDividerWidth.toPx()
    for (line in first..last) {
        val label = (line + 1).toString()
        val color = if (line == caretLine) look.currentLineNumber else look.lineNumber
        val measured = measurer.measure(label, style.copy(color = color))
        val x = width - divider - padding - measured.size.width
        drawText(measured, topLeft = Offset(x, rows.textRow(line) * rowPx.toFloat()))
    }
    if (divider > 0f && look.gutterDivider.alpha > 0f) {
        drawRect(look.gutterDivider, Offset(width - divider, 0f), Size(divider, size.height))
    }
}

/** Underlines under their ranges, in the field's own coordinates. */
private fun DrawScope.drawDecorations(window: FieldWindow, layout: TextLayoutResult, look: CodeEditorLook) {
    val model = window.model
    model.overlayRevision
    window.revision
    val textLength = layout.layoutInput.text.length
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
