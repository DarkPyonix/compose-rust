package dev.darkpyonix.composerust.foundation.code

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.DecorationRecord
import dev.darkpyonix.composerust.protocol.FoldRecord
import dev.darkpyonix.composerust.protocol.GutterDecorationKind
import dev.darkpyonix.composerust.protocol.GutterDecorationRecord
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.RevealAlign
import dev.darkpyonix.composerust.protocol.Severity
import dev.darkpyonix.composerust.protocol.SyntaxSpanRecord
import kotlin.time.TimeSource

private val clockOrigin = TimeSource.Monotonic.markNow()

/** Nanoseconds on a monotonic clock, the same on every platform this is shared with. */
private fun monotonicNanos(): Long = clockOrigin.elapsedNow().inWholeNanoseconds

/** Where the caret is and where the selection started, in document positions. */
data class CodeSelection(val anchor: CodePosition, val caret: CodePosition) {
    val start: CodePosition get() = minOf(anchor, caret)
    val end: CodePosition get() = maxOf(anchor, caret)
    val collapsed: Boolean get() = anchor == caret

    companion object {
        fun at(position: CodePosition) = CodeSelection(position, position)
    }
}

/** One decoration as it stands in the current version of the document. */
class LiveDecoration(
    val kind: DecorationKind,
    val severity: Severity?,
    /** The role the Host asked for, or null where the design system chooses. */
    val color: ColorRole?,
    var start: CodePosition,
    var end: CodePosition,
    /** The application's name for it. Zero is never reported. */
    val id: Long,
    val text: String,
)

/**
 * One stretch of lines that can be folded, as it stands in the current version of the
 * document. Folding keeps [startLine] and hides the lines after it up to [endLine].
 */
class LiveFold(var startLine: Int, var endLine: Int, var collapsed: Boolean)

/** One mark beside a line number, on the line it has followed the document to. */
class LiveGutterMark(
    val kind: GutterDecorationKind,
    /** The role the Host asked for, or null where the design system chooses. */
    val color: ColorRole?,
    var line: Int,
    /** The application's name for it. Zero is never reported. */
    val id: Long,
    /** The Unicode scalar value a glyph shows. */
    val glyph: Int,
)

/** A range the Host asked to have brought into view. A new request is a new object. */
class RevealRequest(val start: CodePosition, val end: CodePosition, val align: RevealAlign)

/** One colour run of the syntax as it stands in the current version of the document. */
class LiveSpan(var start: CodePosition, var end: CodePosition, val paint: Paint)

/** Something the editor has to tell the Host, in the order it happened. */
sealed interface EditorOutput {
    /** A committed change, with the version it produced. */
    class Changed(val change: AppliedChange) : EditorOutput

    /** A Host edit that was not applied. The range is the one that was sent. */
    class Rejected(
        val requestId: Int,
        val baseVersion: Int,
        val currentVersion: Int,
        val start: CodePosition,
        val end: CodePosition,
    ) : EditorOutput

    /** A decoration was pressed or accepted. */
    class Activated(val id: Long) : EditorOutput

    /**
     * The selection moved, as it stands at [version]. Moves that happen between two reports
     * are one report, of where the selection ended up.
     */
    class SelectionMoved(val version: Int, val selection: CodeSelection) : EditorOutput

    /** The lines on screen changed. Folded lines are not counted. */
    class VisibleRange(val version: Int, val first: Int, val last: Int) : EditorOutput

    /** The editor gained or lost the keyboard focus. */
    class Focus(val focused: Boolean) : EditorOutput

    /** The reader folded or unfolded the stretch that starts on [startLine]. */
    class FoldToggled(val startLine: Int, val collapsed: Boolean) : EditorOutput

    /**
     * An input method started or ended a composition. What is being composed is not here: it
     * stays in the surface that is drawing it and never leaves this side.
     */
    class Composing(val composing: Boolean) : EditorOutput

    /** Something the Host sent that could not be honoured. Only that item was dropped. */
    class Error(val message: String) : EditorOutput
}

/** Where a change came from, which decides how it joins the undo history. */
enum class EditOrigin {
    /** One character typed, which joins the characters typed just before it. */
    Typing,

    /** Anything else the reader did: a paste, a cut, a deletion, an accepted suggestion. */
    Reader,

    /** An edit the Host asked for. */
    Host,
}

/**
 * Everything a code editor holds that is not how it is drawn: the document, the decorations
 * and colour runs over it, the selection, the undo history, and what the Host has to be told.
 *
 * The Renderer owns all of it. The Host's text, lists and edits come in through the
 * `set`/`host` functions as the batch carrying them is applied; the reader's edits come in
 * through [edit] from whichever surface draws the editor. Each committed change moves the
 * decorations, the colour runs and the selection along with it, and queues one
 * [EditorOutput.Changed] for the Host, so the version the Host sees is one sequence whoever
 * made the change.
 *
 * Two counters say what changed, for the surface to read: [contentRevision] when the text did
 * and [overlayRevision] when only what is drawn over it did.
 */
class CodeEditorModel(
    text: String = "",
    /** Nanoseconds, for deciding whether two keystrokes are one undo step. */
    private val clock: () -> Long = ::monotonicNanos,
) {
    val document = CodeDocument(text)

    var contentRevision: Int by mutableIntStateOf(0)
        private set

    var overlayRevision: Int by mutableIntStateOf(0)
        private set

    /**
     * The selection, kept by the surface as the reader moves it and moved along by every
     * change, so a change the Host makes elsewhere leaves the caret on the text it was on.
     */
    var selection: CodeSelection = CodeSelection.at(CodePosition.Zero)
        set(value) {
            field = value
            selectionDirty = true
        }

    /** The selection changed since it was last compared with what the Host was told. */
    private var selectionDirty = false
    private var reportedSelection = CodeSelection.at(CodePosition.Zero)

    /** The lines on screen, as the surface last said, and as the Host was last told. */
    private var pendingVisible: Pair<Int, Int>? = null
    private var reportedVisible: Pair<Int, Int>? = null

    private var focused = false
    private var composing = false

    /**
     * Counts the selections the Host set, for a surface to carry into the field it types
     * into: the field holds its own copy of the selection, and this is how it learns that the
     * model's was changed from outside.
     */
    var hostSelection: Int by mutableIntStateOf(0)
        private set

    /** The range the Host last asked to have brought into view, until the surface has done it. */
    var reveal: RevealRequest? by mutableStateOf(null)
        private set

    private val foldList = ArrayList<LiveFold>()
    private val gutterList = ArrayList<LiveGutterMark>()

    /** Changes whenever a fold is added, moved, removed or flipped, for the cache below. */
    private var foldStamp = 0
    private var hiddenStamp = -1
    private var hiddenCache = IntArray(0)

    private val decorationList = ArrayList<LiveDecoration>()
    private val spanList = ArrayList<LiveSpan>()

    /** How many lines the longest colour run covers, so a window can find runs that start above it. */
    private var longestSpanLines = 0

    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()
    private val output = ArrayList<EditorOutput>()

    val decorations: List<LiveDecoration> get() = decorationList
    val folds: List<LiveFold> get() = foldList
    val gutterMarks: List<LiveGutterMark> get() = gutterList
    val spans: List<LiveSpan> get() = spanList

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The suggestion on show, if there is one. */
    val ghostText: LiveDecoration? get() = decorationList.firstOrNull { it.kind == DecorationKind.GhostText }

    /**
     * Everything queued for the Host since the last call, in order, and an empty queue.
     *
     * The selection and the visible lines are reported as where they ended up, so with
     * [includeReports] they are added at the end of the queue. The caller that ends a frame
     * asks for them; one that drains in the middle of a batch does not, and leaves them to
     * the drain that ends it.
     */
    fun drainOutput(includeReports: Boolean = false): List<EditorOutput> {
        if (includeReports) flushReports()
        if (output.isEmpty()) return emptyList()
        val drained = output.toList()
        output.clear()
        return drained
    }

    /**
     * The document the Host gave. Text equal to what the editor holds changes nothing, so an
     * application that writes every change back into the text it sends keeps the reader's
     * caret and history. Different text opens as a new document at version 0, and the
     * decorations, the colour runs and the history of the old one go with it.
     *
     * Returns whether the document was replaced.
     */
    fun setHostText(text: String): Boolean {
        if (document.contentEquals(text)) return false
        document.reset(text)
        decorationList.clear()
        spanList.clear()
        longestSpanLines = 0
        undoStack.clear()
        redoStack.clear()
        foldList.clear()
        gutterList.clear()
        foldStamp += 1
        selection = CodeSelection.at(CodePosition.Zero)
        contentRevision += 1
        overlayRevision += 1
        return true
    }

    /** Replaces every decoration with the Host's new list. */
    fun setDecorations(records: List<DecorationRecord>) {
        decorationList.clear()
        for (record in records) {
            val range = follow(record.version, record.startLine, record.startColumn, record.endLine, record.endColumn)
                ?: continue
            val (start, end) = range
            decorationList += when (record.kind) {
                // A lens belongs to a line rather than to a stretch of it.
                DecorationKind.CodeLens -> {
                    val at = CodePosition(start.line, 0)
                    LiveDecoration(record.kind, null, null, at, at, record.id, record.text)
                }
                DecorationKind.GhostText ->
                    LiveDecoration(record.kind, null, null, start, start, record.id, record.text)
                DecorationKind.Underline -> LiveDecoration(
                    record.kind,
                    record.severity ?: Severity.Information,
                    record.color,
                    start,
                    end,
                    record.id,
                    "",
                )
                DecorationKind.HoverAnchor ->
                    LiveDecoration(record.kind, null, null, start, end, record.id, "")
            }
        }
        overlayRevision += 1
    }

    /** Replaces every colour run with the Host's new list. */
    fun setSyntaxSpans(records: List<SyntaxSpanRecord>) {
        spanList.clear()
        longestSpanLines = 0
        for (record in records) {
            val (start, end) = follow(
                record.version,
                record.startLine,
                record.startColumn,
                record.endLine,
                record.endColumn,
            ) ?: continue
            if (start == end) continue
            spanList += LiveSpan(start, end, record.paint)
            longestSpanLines = maxOf(longestSpanLines, end.line - start.line)
        }
        spanList.sortWith(compareBy { it.start })
        overlayRevision += 1
    }

    /**
     * A range the Host wrote against [version], moved to where the same text is now, or null
     * where it cannot be: a version the document never had or no longer remembers, a start
     * after its end, or a place outside the document. Each of those is reported and only
     * that item is dropped.
     */
    private fun follow(
        version: Int,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
    ): Pair<CodePosition, CodePosition>? {
        var start = CodePosition(startLine, startColumn)
        var end = CodePosition(endLine, endColumn)
        if (start > end || startLine < 0 || startColumn < 0) {
            output += EditorOutput.Error("a range starts at $start after it ends at $end")
            return null
        }
        val changes = document.changesSince(version) ?: run {
            output += EditorOutput.Error(
                "a range was written against version $version, and the document is at " +
                    "${document.version} and remembers back to ${document.oldestVersion}",
            )
            return null
        }
        if (changes.isEmpty()) {
            if (!document.isValid(start) || !document.isValid(end)) {
                output += EditorOutput.Error("the range $start to $end is outside the document")
                return null
            }
            return start to end
        }
        for (change in changes) {
            val moved = CodeDocument.mapRange(start, end, change) ?: return null
            start = moved.first
            end = moved.second
        }
        if (!document.isValid(start) || !document.isValid(end)) {
            output += EditorOutput.Error("the range $start to $end is outside the document")
            return null
        }
        return start to end
    }

    /**
     * An edit the Host asked for, written against [baseVersion].
     *
     * Where the reader has changed the document since, the range is moved along with what
     * they typed. Where they typed in the same place, the edit is refused and the Host is
     * told, because the Host never writes over its reader. An edit that is applied is
     * reported back as an ordinary change.
     */
    fun hostEdit(
        requestId: Int,
        baseVersion: Int,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        text: String,
    ) {
        val sentStart = CodePosition(startLine, startColumn)
        val sentEnd = CodePosition(endLine, endColumn)
        if (sentStart > sentEnd || startLine < 0 || startColumn < 0) {
            output += EditorOutput.Error("an edit's range starts at $sentStart after it ends at $sentEnd")
            return
        }
        if (baseVersion > document.version) {
            output += EditorOutput.Error(
                "an edit was written against version $baseVersion, and the document is at ${document.version}",
            )
            return
        }
        fun reject() {
            output += EditorOutput.Rejected(requestId, baseVersion, document.version, sentStart, sentEnd)
        }
        // Older than the document remembers is a version nothing can be moved forward from.
        val changes = document.changesSince(baseVersion) ?: return reject()
        var start = sentStart
        var end = sentEnd
        for (change in changes) {
            if (CodeDocument.overlaps(change, start, end)) return reject()
            if (start == end) {
                start = CodeDocument.map(start, change, Bias.After)
                end = start
            } else {
                start = CodeDocument.map(start, change, Bias.After)
                end = CodeDocument.map(end, change, Bias.Before)
            }
        }
        if (!document.isValid(start) || !document.isValid(end) || start > end) {
            output += EditorOutput.Error("an edit's range $sentStart to $sentEnd is outside the document")
            return
        }
        apply(start, end, text, EditOrigin.Host)
    }

    /**
     * An edit the reader made, already committed: never a composition still in progress.
     *
     * Any suggestion on show goes away, because the reader typed something else.
     */
    fun edit(start: CodePosition, end: CodePosition, text: String, origin: EditOrigin = EditOrigin.Reader): AppliedChange? {
        if (!document.isValid(start) || !document.isValid(end) || start > end) return null
        if (start == end && text.isEmpty()) return null
        dismissGhostText()
        return apply(start, end, text, origin)
    }

    /**
     * Accepts the suggestion on show: its text goes in as an ordinary edit, reported first,
     * and then the decoration's id is reported as activated. Returns false where there is
     * nothing to accept.
     */
    fun acceptGhostText(): Boolean {
        val ghost = ghostText ?: return false
        decorationList.remove(ghost)
        overlayRevision += 1
        if (ghost.text.isNotEmpty()) {
            val change = apply(ghost.start, ghost.start, ghost.text, EditOrigin.Reader)
            selection = CodeSelection.at(change.newEnd)
        }
        if (ghost.id != 0L) output += EditorOutput.Activated(ghost.id)
        return true
    }

    /** Takes the suggestion off the screen without accepting it. */
    fun dismissGhostText() {
        if (decorationList.removeAll { it.kind == DecorationKind.GhostText }) overlayRevision += 1
    }

    /** A lens was pressed. */
    fun activate(decoration: LiveDecoration) {
        if (decoration.id != 0L) output += EditorOutput.Activated(decoration.id)
    }

    /** The hover anchor under a position, if there is one. */
    fun hoverAnchorAt(position: CodePosition): LiveDecoration? = decorationList.firstOrNull {
        it.kind == DecorationKind.HoverAnchor && it.start <= position &&
            (position < it.end || (it.start == it.end && position == it.start))
    }

    /** The lenses, grouped by the line they sit above, in line order. */
    fun lensesByLine(): List<Pair<Int, List<LiveDecoration>>> = decorationList
        .filter { it.kind == DecorationKind.CodeLens }
        .groupBy { it.start.line }
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    /** The colour runs that touch lines [first] up to, not including, [last]. */
    fun spansInLines(first: Int, last: Int): List<LiveSpan> {
        if (spanList.isEmpty()) return emptyList()
        // The first run that starts at or after the line a run would have to start on to
        // reach this window, found by halving.
        val earliest = first - longestSpanLines
        var low = 0
        var high = spanList.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (spanList[middle].start.line < earliest) low = middle + 1 else high = middle
        }
        val found = ArrayList<LiveSpan>()
        var index = low
        while (index < spanList.size) {
            val span = spanList[index]
            if (span.start.line >= last) break
            if (span.end.line >= first) found += span
            index += 1
        }
        return found
    }

    // The additions that report on the editor and let the Host steer it: the selection, the
    // lines on screen, focus, composition, folds and the marks beside the line numbers.

    /**
     * Queues what has changed since the last drain and is reported as its latest value
     * rather than as every step: where the selection ended up, and which lines are on
     * screen. A move that ends where it started, and a range that is the one the Host was
     * last told, say nothing.
     */
    private fun flushReports() {
        if (selectionDirty) {
            selectionDirty = false
            val current = snapped(selection)
            if (current != reportedSelection) {
                reportedSelection = current
                output += EditorOutput.SelectionMoved(document.version, current)
            }
        }
        val visible = pendingVisible
        if (visible != null) {
            pendingVisible = null
            if (visible != reportedVisible) {
                reportedVisible = visible
                output += EditorOutput.VisibleRange(document.version, visible.first, visible.second)
            }
        }
    }

    /** A position moved to the nearest place the document has, never between a pair's halves. */
    private fun snapped(position: CodePosition): CodePosition {
        val line = position.line.coerceIn(0, document.lineCount - 1)
        val text = document.line(line)
        var column = position.column.coerceIn(0, text.length)
        if (column in 1 until text.length && text[column - 1].isHighSurrogate() && text[column].isLowSurrogate()) {
            column -= 1
        }
        return CodePosition(line, column)
    }

    private fun snapped(selection: CodeSelection) = CodeSelection(snapped(selection.anchor), snapped(selection.caret))

    /**
     * The surface says which lines are on screen. Said every frame, reported only when it
     * is not the range the Host was last told, and at most once per drain.
     */
    fun reportVisibleRange(first: Int, last: Int) {
        pendingVisible = first to last
    }

    /** The surface says whether it holds the keyboard focus. */
    fun focusChanged(hasFocus: Boolean) {
        if (hasFocus == focused) return
        focused = hasFocus
        output += EditorOutput.Focus(hasFocus)
    }

    /** The surface says whether an input method is composing in it. */
    fun compositionChanged(isComposing: Boolean) {
        if (isComposing == composing) return
        composing = isComposing
        output += EditorOutput.Composing(isComposing)
    }

    /** Replaces every fold with the Host's new list. */
    fun setFolds(records: List<FoldRecord>) {
        foldList.clear()
        for (record in records) {
            if (record.startLine >= record.endLine) {
                output += EditorOutput.Error(
                    "a fold starts on line ${record.startLine} and ends on line ${record.endLine}, " +
                        "which hides nothing",
                )
                continue
            }
            val (first, last) = followLines(record.version, record.startLine, record.endLine, "a fold")
                ?: continue
            if (first >= last) continue
            if (foldList.any { it.startLine == first }) {
                output += EditorOutput.Error("two folds start on line $first")
                continue
            }
            foldList += LiveFold(first, last, record.collapsed)
        }
        foldsChanged()
    }

    /** Replaces every mark beside the line numbers with the Host's new list. */
    fun setGutterDecorations(records: List<GutterDecorationRecord>) {
        gutterList.clear()
        for (record in records) {
            if (record.kind == GutterDecorationKind.Glyph && !isScalarValue(record.glyph)) {
                output += EditorOutput.Error("a gutter glyph is ${record.glyph}, which is not a Unicode scalar value")
                continue
            }
            val (line, _) = followLines(record.version, record.line, record.line, "a gutter mark") ?: continue
            gutterList += LiveGutterMark(record.kind, record.color, line, record.id, record.glyph)
        }
        overlayRevision += 1
    }

    private fun isScalarValue(value: Int) = value in 0..0x10FFFF && value !in 0xD800..0xDFFF

    /**
     * Lines written against [version], moved to where the same lines are now, or null where
     * they cannot be: a version the document never had or no longer remembers, lines that
     * are not in it, or lines the reader has since deleted. The first two are reported.
     */
    private fun followLines(version: Int, first: Int, last: Int, what: String): Pair<Int, Int>? {
        if (first < 0 || last < first) {
            output += EditorOutput.Error("$what is on lines $first to $last")
            return null
        }
        val changes = document.changesSince(version) ?: run {
            output += EditorOutput.Error(
                "$what was written against version $version, and the document is at " +
                    "${document.version} and remembers back to ${document.oldestVersion}",
            )
            return null
        }
        if (changes.isEmpty()) {
            if (last >= document.lineCount) {
                output += EditorOutput.Error("$what is on lines $first to $last, outside the document")
                return null
            }
            return first to last
        }
        // The lines as a range from the start of the first to the start of the line after
        // the last, which is a range the document's own rules for moving ranges apply to.
        var start = CodePosition(first, 0)
        var end = CodePosition(last + 1, 0)
        for (change in changes) {
            val moved = CodeDocument.mapRange(start, end, change) ?: return null
            start = moved.first
            end = moved.second
        }
        val lastLine = if (end.column == 0) end.line - 1 else end.line
        if (start.line >= document.lineCount || lastLine < start.line) return null
        return start.line to minOf(lastLine, document.lineCount - 1)
    }

    private fun foldsChanged() {
        foldStamp += 1
        overlayRevision += 1
        keepSelectionVisible()
    }

    /**
     * The lines a fold hides, as pairs of first and last line in one array, in order and
     * with overlapping stretches joined. Empty where nothing is folded.
     */
    fun hiddenLines(): IntArray {
        if (hiddenStamp == foldStamp) return hiddenCache
        val ranges = foldList.filter { it.collapsed }.sortedBy { it.startLine }
        val merged = ArrayList<Int>()
        for (fold in ranges) {
            val first = fold.startLine + 1
            val last = fold.endLine
            if (merged.isNotEmpty() && first <= merged[merged.size - 1] + 1) {
                if (last > merged[merged.size - 1]) merged[merged.size - 1] = last
            } else {
                merged += first
                merged += last
            }
        }
        hiddenCache = merged.toIntArray()
        hiddenStamp = foldStamp
        return hiddenCache
    }

    /** Whether a fold hides this line. */
    fun isHidden(line: Int): Boolean {
        val hidden = hiddenLines()
        var low = 0
        var high = hidden.size / 2
        while (low < high) {
            val middle = (low + high) ushr 1
            if (hidden[middle * 2 + 1] < line) low = middle + 1 else high = middle
        }
        return low < hidden.size / 2 && hidden[low * 2] <= line
    }

    /**
     * [line], or where a fold hides it, the nearest line that stays: below the stretch when
     * [down], and the line the fold starts on otherwise.
     */
    fun nearestVisible(line: Int, down: Boolean): Int {
        if (!isHidden(line)) return line
        val hidden = hiddenLines()
        var index = 0
        while (index < hidden.size && hidden[index + 1] < line) index += 2
        val first = hidden[index]
        val last = hidden[index + 1]
        return if (down && last + 1 < document.lineCount) last + 1 else first - 1
    }

    /** The fold that starts on a line, if one does. */
    fun foldAt(line: Int): LiveFold? = foldList.firstOrNull { it.startLine == line }

    /** A selection end inside a stretch that is folded away has nowhere to be seen: it goes to the line that stays. */
    private fun keepSelectionVisible() {
        fun visible(position: CodePosition): CodePosition {
            if (!isHidden(position.line)) return position
            val stays = foldList.filter { it.collapsed && it.startLine < position.line && position.line <= it.endLine }
                .minOf { it.startLine }
            return CodePosition(stays, document.line(stays).length)
        }
        val moved = CodeSelection(visible(selection.anchor), visible(selection.caret))
        if (moved != selection) selection = moved
    }

    /** The reader folded or unfolded the stretch that starts on [startLine]. */
    fun toggleFold(startLine: Int): Boolean {
        val fold = foldAt(startLine) ?: return false
        fold.collapsed = !fold.collapsed
        foldsChanged()
        output += EditorOutput.FoldToggled(fold.startLine, fold.collapsed)
        return true
    }

    /** The Host folded or unfolded a stretch. It asked, so it is not told. */
    fun hostSetFolded(startLine: Int, collapsed: Boolean) {
        val fold = foldAt(startLine)
        if (fold == null) {
            output += EditorOutput.Error("no fold starts on line $startLine")
            return
        }
        if (fold.collapsed == collapsed) return
        fold.collapsed = collapsed
        foldsChanged()
    }

    /** A mark beside a line number was pressed. */
    fun activateGutter(mark: LiveGutterMark) {
        if (mark.id != 0L) output += EditorOutput.Activated(mark.id)
    }

    /** The marks beside a line, in the order the Host sent them. */
    fun gutterMarksAt(line: Int): List<LiveGutterMark> = gutterList.filter { it.line == line }

    /**
     * A selection the Host asked for, written against [baseVersion]. Both ends are moved
     * along with what the reader typed since. An end that is not a place in the document
     * drops the command and is reported.
     */
    fun hostSetSelection(baseVersion: Int, anchorLine: Int, anchorColumn: Int, activeLine: Int, activeColumn: Int) {
        val sentAnchor = CodePosition(anchorLine, anchorColumn)
        val sentActive = CodePosition(activeLine, activeColumn)
        val moved = moveAlong(baseVersion, "a selection", sentAnchor, sentActive, Bias.After, Bias.After) ?: return
        val (anchor, active) = moved
        if (!document.isValid(anchor) || !document.isValid(active)) {
            output += EditorOutput.Error("the selection $sentAnchor to $sentActive is outside the document")
            return
        }
        dismissGhostText()
        selection = CodeSelection(anchor, active)
        hostSelection += 1
    }

    /**
     * A range the Host asked to have brought into view, written against [baseVersion]. A
     * stretch folded over it is unfolded first, and the surface scrolls.
     */
    fun hostReveal(baseVersion: Int, start: CodePosition, end: CodePosition, align: RevealAlign) {
        if (start > end) {
            output += EditorOutput.Error("a range to reveal starts at $start after it ends at $end")
            return
        }
        val moved = moveAlong(baseVersion, "a range to reveal", start, end, Bias.After, Bias.Before) ?: return
        val (from, to) = moved
        if (!document.isValid(from) || !document.isValid(to)) {
            output += EditorOutput.Error("the range $start to $end is outside the document")
            return
        }
        var unfolded = false
        for (fold in foldList) {
            if (!fold.collapsed) continue
            if (from.line <= fold.endLine && to.line > fold.startLine) {
                fold.collapsed = false
                output += EditorOutput.FoldToggled(fold.startLine, false)
                unfolded = true
            }
        }
        if (unfolded) foldsChanged()
        reveal = RevealRequest(from, maxOf(from, to), align)
    }

    /** The surface did what the last reveal asked. */
    fun revealDone(request: RevealRequest) {
        if (reveal === request) reveal = null
    }

    /**
     * Two positions written against [baseVersion], moved to where the same text is now, or
     * null where they cannot be, which is reported: a place that is below zero, a version the
     * document has not reached, or one it no longer remembers.
     */
    private fun moveAlong(
        baseVersion: Int,
        what: String,
        first: CodePosition,
        second: CodePosition,
        firstBias: Bias,
        secondBias: Bias,
    ): Pair<CodePosition, CodePosition>? {
        if (first.line < 0 || first.column < 0 || second.line < 0 || second.column < 0) {
            output += EditorOutput.Error("$what is at $first to $second, which is not a place in a document")
            return null
        }
        if (baseVersion > document.version) {
            output += EditorOutput.Error(
                "$what was written against version $baseVersion, and the document is at ${document.version}",
            )
            return null
        }
        val changes = document.changesSince(baseVersion) ?: run {
            output += EditorOutput.Error(
                "$what was written against version $baseVersion, and the document is at " +
                    "${document.version} and remembers back to ${document.oldestVersion}",
            )
            return null
        }
        var a = first
        var b = second
        for (change in changes) {
            a = CodeDocument.map(a, change, firstBias)
            b = CodeDocument.map(b, change, secondBias)
        }
        return a to b
    }

    /** Undoes the last step. Each change it makes is reported, in order. */
    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        redoStack += revert(entry)
        return true
    }

    /** Does again what was last undone. */
    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        undoStack += revert(entry)
        return true
    }

    /** Applies the inverse of every change in [entry], last first, and returns that as an entry. */
    private fun revert(entry: UndoEntry): UndoEntry {
        dismissGhostText()
        val inverses = ArrayList<AppliedChange>()
        for (change in entry.changes.asReversed()) {
            inverses += applyRaw(change.start, change.newEnd, change.removed)
        }
        selection = entry.selectionBefore
        return UndoEntry(
            inverses,
            selectionBefore = entry.selectionAfter,
            selectionAfter = entry.selectionBefore,
            mergeable = false,
            at = 0L,
        )
    }

    private fun apply(start: CodePosition, end: CodePosition, text: String, origin: EditOrigin): AppliedChange {
        val before = selection
        val change = applyRaw(start, end, text)
        redoStack.clear()
        val now = clock()
        val last = undoStack.lastOrNull()
        val typed = origin == EditOrigin.Typing && start == end && text.length <= 2 && '\n' !in text
        if (typed && last != null && last.mergeable &&
            last.changes.last().newEnd == start &&
            now - last.at < TYPING_GROUP_NANOS
        ) {
            undoStack[undoStack.size - 1] = UndoEntry(last.changes + change, last.selectionBefore, selection, true, now)
        } else {
            undoStack += UndoEntry(listOf(change), before, selection, typed, now)
        }
        return change
    }

    /** Changes the document, moves everything over it, and queues the report. */
    private fun applyRaw(start: CodePosition, end: CodePosition, text: String): AppliedChange {
        val change = document.replace(start, end, text)
        moveOverlays(change)
        selection = CodeSelection(
            CodeDocument.map(selection.anchor, change, Bias.After),
            CodeDocument.map(selection.caret, change, Bias.After),
        )
        output += EditorOutput.Changed(change)
        contentRevision += 1
        return change
    }

    private fun moveOverlays(change: AppliedChange) {
        var moved = false
        if (decorationList.isNotEmpty()) {
            val iterator = decorationList.iterator()
            while (iterator.hasNext()) {
                val decoration = iterator.next()
                val range = CodeDocument.mapRange(decoration.start, decoration.end, change)
                if (range == null) {
                    iterator.remove()
                } else {
                    decoration.start = range.first
                    decoration.end = range.second
                }
            }
            moved = true
        }
        if (spanList.isNotEmpty()) {
            // Runs entirely before the change do not move, and they are found by halving, so
            // a keystroke near the end of a long file walks only the runs after it.
            var low = 0
            var high = spanList.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (spanList[middle].start.line < change.start.line - longestSpanLines) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            var index = low
            var removed = 0
            while (index < spanList.size) {
                val span = spanList[index]
                val range = if (span.end < change.start) span.start to span.end else
                    CodeDocument.mapRange(span.start, span.end, change)
                if (range == null) {
                    removed += 1
                } else {
                    span.start = range.first
                    span.end = range.second
                    // A run grows when lines are typed inside it, and the search above has
                    // to keep finding it from below.
                    longestSpanLines = maxOf(longestSpanLines, span.end.line - span.start.line)
                    if (removed > 0) spanList[index - removed] = span
                }
                index += 1
            }
            if (removed > 0) spanList.subList(spanList.size - removed, spanList.size).clear()
            moved = true
        }
        if (foldList.isNotEmpty()) {
            val iterator = foldList.iterator()
            while (iterator.hasNext()) {
                val fold = iterator.next()
                val range = CodeDocument.mapRange(
                    CodePosition(fold.startLine, 0),
                    CodePosition(fold.endLine + 1, 0),
                    change,
                )
                val last = range?.let { if (it.second.column == 0) it.second.line - 1 else it.second.line }
                if (range == null || last == null || last <= range.first.line) {
                    iterator.remove()
                } else {
                    fold.startLine = range.first.line
                    fold.endLine = minOf(last, document.lineCount - 1)
                }
            }
            foldStamp += 1
            moved = true
        }
        if (gutterList.isNotEmpty()) {
            val iterator = gutterList.iterator()
            while (iterator.hasNext()) {
                val mark = iterator.next()
                val range = CodeDocument.mapRange(
                    CodePosition(mark.line, 0),
                    CodePosition(mark.line + 1, 0),
                    change,
                )
                if (range == null) iterator.remove() else mark.line = range.first.line
            }
            moved = true
        }
        if (moved) overlayRevision += 1
    }

    private class UndoEntry(
        val changes: List<AppliedChange>,
        val selectionBefore: CodeSelection,
        val selectionAfter: CodeSelection,
        /** Whether the next typed character may join this step. */
        val mergeable: Boolean,
        /** When the step last grew. */
        val at: Long,
    )

    companion object {
        /** Characters typed within this long of each other undo together. */
        const val TYPING_GROUP_NANOS: Long = 1_000_000_000L
    }
}
