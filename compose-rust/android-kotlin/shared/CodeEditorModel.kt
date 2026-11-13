package dev.darkpyonix.composerust.foundation.code

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import dev.darkpyonix.composerust.protocol.CodeEditRecord
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.EditGroupStatus
import dev.darkpyonix.composerust.protocol.DecorationRecord
import dev.darkpyonix.composerust.protocol.FoldRecord
import dev.darkpyonix.composerust.protocol.GutterDecorationKind
import dev.darkpyonix.composerust.protocol.GutterDecorationRecord
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.RevealAlign
import dev.darkpyonix.composerust.protocol.Severity
import dev.darkpyonix.composerust.protocol.SyntaxSpanRecord
import dev.darkpyonix.composerust.protocol.ViewZoneRecord
import kotlin.time.TimeSource
import kotlin.math.roundToInt

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

/** Whether this is one of the two tints drawn behind the text. */
val LiveDecoration.isBackground: Boolean
    get() = kind == DecorationKind.LineBackground || kind == DecorationKind.RangeBackground

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

/**
 * What a change-marker bar says about its line. Declared in the order the bars win a line:
 * a removal outranks a modification, which outranks an addition.
 */
enum class ChangeMark { Removed, Modified, Added }

/** The change a mark stands for, or null when it is an ordinary mark beside the line number. */
fun LiveGutterMark.changeMark(): ChangeMark? {
    if (kind != GutterDecorationKind.Bar) return null
    return when (color) {
        ColorRole.DiffAdded -> ChangeMark.Added
        ColorRole.DiffModified -> ChangeMark.Modified
        ColorRole.DiffRemoved -> ChangeMark.Removed
        else -> null
    }
}

/**
 * Room after a line that is not text, the way a diff leaves blank rows to line its two sides
 * up: [heightLines] rows high, with [text] drawn dimmed in it. It follows its line through
 * edits, and the caret, the selection and a copy pass over it.
 */
class LiveViewZone(var afterLine: Int, val heightLines: Float, val text: String)

/** A range the Host asked to have brought into view. A new request is a new object. */
class RevealRequest(val start: CodePosition, val end: CodePosition, val align: RevealAlign)

/**
 * A scroll offset the Host asked for, in the surface's own pixels, until the surface has
 * gone there. The surface keeps it inside what the document allows.
 */
class ScrollRequest(val offsetPx: Int)

/**
 * A rectangle on screen in the dp of the window's content: the editor's scroll and the
 * window's zoom are already in it.
 */
data class CodeRectDp(val x: Float, val y: Float, val width: Float, val height: Float)

/** Where the caret is drawn: the place it stands at and its rectangle. */
data class CaretReport(val position: CodePosition, val rect: CodeRectDp)

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

    /**
     * Every selection, the primary first, where there is more than one or there were until
     * now. Moves between two reports are one report.
     */
    class SelectionsMoved(val version: Int, val selections: List<CodeSelection>) : EditorOutput

    /** The lines on screen changed. Folded lines are not counted. */
    class VisibleRange(val version: Int, val first: Int, val last: Int) : EditorOutput

    /** The editor gained or lost the keyboard focus. */
    class Focus(val focused: Boolean) : EditorOutput

    /** The height of one row as it is drawn, in pixels: the first time, and when it changes. */
    class LineHeight(val px: Int) : EditorOutput

    /** How far the view is scrolled, in pixels. Steps between two reports are one report. */
    class Scrolled(val offsetPx: Int) : EditorOutput

    /** Where the caret is drawn. Moves between two reports are one report. */
    class Caret(val report: CaretReport) : EditorOutput

    /** The answer to the Host's position query [request]: a rectangle, or null where none is on screen. */
    class PositionAnswer(val request: Int, val rect: CodeRectDp?) : EditorOutput

    /** The reader folded or unfolded the stretch that starts on [startLine]. */
    class FoldToggled(val startLine: Int, val collapsed: Boolean) : EditorOutput

    /**
     * An input method started or ended a composition. What is being composed is not here: it
     * stays in the surface that is drawing it and never leaves this side.
     */
    class Composing(val composing: Boolean) : EditorOutput

    /** Something the Host sent that could not be honoured. Only that item was dropped. */
    class Error(val message: String) : EditorOutput

    /**
     * What became of one grouped Host edit, queued exactly once for each, after the changes
     * it made. [index] is how many edits went in where it was applied, and otherwise the
     * position in the group, as sent, of the edit that kept it out.
     */
    class GroupResult(
        val requestId: Int,
        val status: EditGroupStatus,
        val baseVersion: Int,
        val currentVersion: Int,
        val index: Int,
    ) : EditorOutput
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

    /**
     * The selections past the primary one, in the order they were added. Each is moved by
     * every change the way the primary is. Empty while there is one selection, which is
     * almost always.
     */
    var secondary: List<CodeSelection> = emptyList()
        set(value) {
            if (field.isEmpty() && value.isEmpty()) return
            field = value
            selectionDirty = true
            secondaryRevision += 1
        }

    /** Changes whenever the selections past the primary one do, for what draws them. */
    var secondaryRevision: Int by mutableIntStateOf(0)
        private set

    /** Every selection, the primary first. */
    val selections: List<CodeSelection> get() = if (secondary.isEmpty()) listOf(selection) else listOf(selection) + secondary

    /** The selection changed since it was last compared with what the Host was told. */
    private var selectionDirty = false
    private var reportedSelection = CodeSelection.at(CodePosition.Zero)

    /** Every selection as the Host was last told, where it was told about more than one. */
    private var reportedSelections: List<CodeSelection> = listOf(CodeSelection.at(CodePosition.Zero))

    /** The lines on screen, as the surface last said, and as the Host was last told. */
    private var pendingVisible: Pair<Int, Int>? = null
    private var reportedVisible: Pair<Int, Int>? = null

    /**
     * The row height and the scroll offset, as the surface last said and as the Host was
     * last told. No row height has been told before the first draw. An editor starts at
     * the top, so an offset of zero is what the Host already takes it to be.
     */
    private var pendingLineHeight = 0
    private var reportedLineHeight = 0
    private var pendingScroll = 0
    private var reportedScroll = 0

    /**
     * Whether the Host listens for the row height and for the offset. What nobody listens
     * for is not counted as told, so a listener attached after the editor was drawn still
     * hears the row height, and one attached after a scroll hears where the view is.
     */
    var hearsLineHeight = false
    var hearsScroll = false

    /**
     * Where the caret is drawn, as the surface last said and as the Host was last told, and
     * whether the Host listens. Like the row height, what nobody listens for is not counted
     * as told.
     */
    private var pendingCaret: CaretReport? = null
    private var reportedCaret: CaretReport? = null
    var hearsCaretRect = false

    /**
     * How the surface that draws the editor finds a position on screen, or null before it
     * has been drawn. It answers null for a position that is not on screen.
     */
    var locator: ((CodePosition) -> CodeRectDp?)? = null

    /** The Host's position queries that arrived before the editor was drawn, in order. */
    private val waitingQueries = ArrayList<Pair<Int, CodePosition>>()

    /** Whether a query is waiting for the surface to be drawn. */
    val hasWaitingQueries: Boolean get() = waitingQueries.isNotEmpty()

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

    /** The offset the Host last asked the view to be scrolled to, until the surface has done it. */
    var scrollRequest: ScrollRequest? by mutableStateOf(null)
        private set

    private val foldList = ArrayList<LiveFold>()
    private val gutterList = ArrayList<LiveGutterMark>()

    /** Changes whenever the gutter marks are replaced, moved or dropped, for the flags below. */
    private var gutterStamp = 0
    private var flagsStamp = -1
    private var changeBarsPresent = false
    private var ordinaryMarksPresent = false

    /** Changes whenever a fold is added, moved, removed or flipped, for the cache below. */
    private var foldStamp = 0
    private var hiddenStamp = -1
    private var hiddenCache = IntArray(0)

    private val decorationList = ArrayList<LiveDecoration>()
    private val spanList = ArrayList<LiveSpan>()
    private val zoneList = ArrayList<LiveViewZone>()

    /** How many lines the longest colour run covers, so a window can find runs that start above it. */
    private var longestSpanLines = 0

    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()
    private val output = ArrayList<EditorOutput>()

    val decorations: List<LiveDecoration> get() = decorationList
    val folds: List<LiveFold> get() = foldList
    val gutterMarks: List<LiveGutterMark> get() = gutterList
    val spans: List<LiveSpan> get() = spanList

    /** The view zones, in the order the Host sent them. */
    val viewZones: List<LiveViewZone> get() = zoneList

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** How many steps an undo can go back. */
    val undoDepth: Int get() = undoStack.size

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
        if (wrapColumns > 0) rewrapAll()
        decorationList.clear()
        backgroundsPresent = false
        spanList.clear()
        longestSpanLines = 0
        undoStack.clear()
        redoStack.clear()
        foldList.clear()
        gutterList.clear()
        zoneList.clear()
        gutterStamp += 1
        foldStamp += 1
        snippet = null
        secondary = emptyList()
        selection = CodeSelection.at(CodePosition.Zero)
        contentRevision += 1
        overlayRevision += 1
        return true
    }

    /** Replaces every decoration with the Host's new list. */
    fun setDecorations(records: List<DecorationRecord>) {
        decorationList.clear()
        for (record in records) {
            if (record.kind == DecorationKind.LineBackground) {
                // Whole lines, the first to the last, kept as the start of each. They are
                // followed to where they are now as lines, the way a gutter mark's line is.
                val color = record.color
                if (color == null) {
                    output += EditorOutput.Error(
                        "a line background on lines ${record.startLine} to ${record.endLine} names no colour",
                    )
                    continue
                }
                val (first, last) = followLines(record.version, record.startLine, record.endLine, "a line background")
                    ?: continue
                decorationList += LiveDecoration(
                    record.kind,
                    null,
                    color,
                    CodePosition(first, 0),
                    CodePosition(last, 0),
                    record.id,
                    "",
                )
                continue
            }
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
                // Taken above.
                DecorationKind.LineBackground -> continue
                DecorationKind.RangeBackground -> {
                    val color = record.color
                    if (color == null) {
                        output += EditorOutput.Error("a range background from $start to $end names no colour")
                        continue
                    }
                    LiveDecoration(record.kind, null, color, start, end, record.id, "")
                }
            }
        }
        backgroundsPresent = decorationList.any { it.isBackground }
        overlayRevision += 1
    }

    /** Whether any decoration is a line or a range background, so a frame without one skips them. */
    var backgroundsPresent = false
        private set

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
     * A group of edits the Host asked for as one, every range written against
     * [baseVersion]: all of them go in, or none does.
     *
     * Everything is checked before the document is touched. The group is [EditGroupStatus.Invalid]
     * where it makes no sense: a version the document has not reached, a range that starts
     * after it ends or is not in the document, or two edits that overlap (ranges that only
     * meet do not, and two insertions at one place go in in the order they were sent).
     * Where the reader has changed the document since, every range is moved past what they
     * typed, the way [hostEdit] moves one, and the whole group is
     * [EditGroupStatus.Rejected] if any of them meets it, or [EditGroupStatus.TooOld] where
     * the version is older than the document remembers.
     *
     * A group that goes in is applied from the end of the document backwards, so each
     * change is reported against the document as it stood just before it and the version
     * steps by one for each edit. It is one undo step of its own, never joined to the
     * typing before it. Exactly one [EditorOutput.GroupResult] follows the changes. An empty
     * group changes nothing and is answered as applied at the version the document is at.
     */
    fun hostEditGroup(requestId: Int, baseVersion: Int, edits: List<CodeEditRecord>) {
        fun answer(status: EditGroupStatus, index: Int) {
            output += EditorOutput.GroupResult(requestId, status, baseVersion, document.version, index)
        }
        if (edits.isEmpty()) {
            output += EditorOutput.GroupResult(
                requestId,
                EditGroupStatus.Applied,
                document.version,
                document.version,
                0,
            )
            return
        }
        // Compared as unsigned, which is what the wire carries: a version past 2^31 is
        // still one the document has not reached.
        if ((baseVersion.toLong() and 0xffff_ffffL) > document.version.toLong()) {
            return answer(EditGroupStatus.Invalid, 0)
        }

        class Planned(val index: Int, var start: CodePosition, var end: CodePosition, val text: String)

        val planned = ArrayList<Planned>(edits.size)
        for ((index, edit) in edits.withIndex()) {
            val start = CodePosition(edit.startLine, edit.startColumn)
            val end = CodePosition(edit.endLine, edit.endColumn)
            if (edit.startLine < 0 || edit.startColumn < 0 || edit.endLine < 0 || edit.endColumn < 0 ||
                start > end
            ) {
                return answer(EditGroupStatus.Invalid, index)
            }
            planned += Planned(index, start, end, edit.text)
        }
        overlapIn(planned.map { Triple(it.index, it.start, it.end) })?.let { return answer(EditGroupStatus.Invalid, it) }

        val changes = document.changesSince(baseVersion) ?: return answer(EditGroupStatus.TooOld, 0)
        for (edit in planned) {
            for (change in changes) {
                if (CodeDocument.overlaps(change, edit.start, edit.end)) {
                    return answer(EditGroupStatus.Rejected, edit.index)
                }
                if (edit.start == edit.end) {
                    edit.start = CodeDocument.map(edit.start, change, Bias.After)
                    edit.end = edit.start
                } else {
                    edit.start = CodeDocument.map(edit.start, change, Bias.After)
                    edit.end = CodeDocument.map(edit.end, change, Bias.Before)
                }
            }
        }
        for (edit in planned) {
            if (!document.isValid(edit.start) || !document.isValid(edit.end) || edit.start > edit.end) {
                return answer(EditGroupStatus.Invalid, edit.index)
            }
        }
        // Moving every range past the same changes keeps apart what was apart, so this
        // refuses nothing the check above let through. It checks the ranges as they will
        // be applied, which is what the document has to be protected from.
        overlapIn(planned.map { Triple(it.index, it.start, it.end) })?.let { return answer(EditGroupStatus.Invalid, it) }

        // Back to front: an edit further on is applied first, so the ones before it are
        // still where they were sent. Of two at the same place the one that reaches further
        // goes first, and of two insertions there the one sent later, so that they read in
        // the order they were sent.
        val ordered = planned.sortedWith(
            compareByDescending<Planned> { it.start }
                .thenByDescending { it.end }
                .thenByDescending { it.index },
        )
        val before = selection
        val applied = ArrayList<AppliedChange>(ordered.size)
        for (edit in ordered) applied += applyRaw(edit.start, edit.end, edit.text)
        redoStack.clear()
        undoStack += UndoEntry(applied, before, selection, mergeable = false, at = clock())
        output += EditorOutput.GroupResult(
            requestId,
            EditGroupStatus.Applied,
            baseVersion,
            document.version,
            applied.size,
        )
    }

    /**
     * A grouped Host edit whose record could not be read. The group changed nothing and is
     * still answered, as [EditGroupStatus.Invalid] at index 0. With [message] the editor
     * reports why; without it the decoder has already reported the record.
     */
    fun hostEditGroupUnreadable(requestId: Int, baseVersion: Int, message: String?) {
        if (message != null) output += EditorOutput.Error(message)
        output += EditorOutput.GroupResult(requestId, EditGroupStatus.Invalid, baseVersion, document.version, 0)
    }

    /**
     * The edit that overlaps another, given as (index, start, end), or null where none
     * does. Of an overlapping pair it is the one further on in the document. Ranges that
     * only meet do not overlap, and neither do two insertions at one place; an insertion
     * overlaps a range only from strictly inside it.
     */
    private fun overlapIn(ranges: List<Triple<Int, CodePosition, CodePosition>>): Int? {
        // In document order, an edit overlaps one before it exactly when it starts before
        // the farthest end of the stretches (not insertions) before it. An insertion before
        // it never overlaps it, and a stretch that starts where an insertion is sorts after
        // the insertion, so meeting at an edge is never counted.
        val sorted = ranges.sortedWith(
            compareBy<Triple<Int, CodePosition, CodePosition>> { it.second }.thenBy { it.third },
        )
        var reach: CodePosition? = null
        for ((index, start, end) in sorted) {
            val farthest = reach
            if (farthest != null && start < farthest) return index
            if (start != end && (farthest == null || end > farthest)) reach = end
        }
        return null
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

    /** The line background of [line], the last one sent where several cover it, or null. */
    fun lineBackgroundAt(line: Int): LiveDecoration? {
        if (!backgroundsPresent) return null
        for (index in decorationList.indices.reversed()) {
            val decoration = decorationList[index]
            if (decoration.kind == DecorationKind.LineBackground && decoration.start.line <= line && line <= decoration.end.line) {
                return decoration
            }
        }
        return null
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
        keepSnippetAround()
        if (selectionDirty) {
            selectionDirty = false
            val current = snapped(selection)
            if (current != reportedSelection) {
                reportedSelection = current
                output += EditorOutput.SelectionMoved(document.version, current)
            }
            // The whole list goes only while there is more than one selection, and once more
            // when it comes back to one, so an editor with one caret sends what it always did.
            val all = if (secondary.isEmpty()) listOf(current) else selections.map(::snapped)
            if (all != reportedSelections && (all.size > 1 || reportedSelections.size > 1)) {
                output += EditorOutput.SelectionsMoved(document.version, all)
            }
            reportedSelections = all
        }
        val visible = pendingVisible
        if (visible != null) {
            pendingVisible = null
            if (visible != reportedVisible) {
                reportedVisible = visible
                output += EditorOutput.VisibleRange(document.version, visible.first, visible.second)
            }
        }
        // The row height goes first: an offset means little to a Host that does not yet
        // know how tall a row is.
        if (hearsLineHeight && pendingLineHeight > 0 && pendingLineHeight != reportedLineHeight) {
            reportedLineHeight = pendingLineHeight
            output += EditorOutput.LineHeight(pendingLineHeight)
        }
        if (hearsScroll && pendingScroll != reportedScroll) {
            reportedScroll = pendingScroll
            output += EditorOutput.Scrolled(pendingScroll)
        }
        val caret = pendingCaret
        if (hearsCaretRect && caret != null && caret != reportedCaret) {
            reportedCaret = caret
            output += EditorOutput.Caret(caret)
        }
    }

    /**
     * The surface says where it draws the caret. Said whenever it draws, reported only when
     * it is not what the Host was last told, and at most once per drain.
     */
    fun reportCaretRect(report: CaretReport) {
        pendingCaret = report
    }

    /** Whether the caret has moved since the Host was told, and the Host listens. */
    val caretUntold: Boolean get() = hearsCaretRect && pendingCaret != null && pendingCaret != reportedCaret

    /**
     * The Host asks where a caret at a position would be drawn. Answered at once where the
     * editor has been drawn, and as soon as it is otherwise. A place that is not in the
     * document is not on screen.
     */
    fun hostQueryPosition(request: Int, line: Int, column: Int) {
        val position = CodePosition(line, column)
        if (line < 0 || column < 0 || !document.isValid(position)) {
            output += EditorOutput.PositionAnswer(request, null)
            return
        }
        val find = locator
        if (find == null) {
            waitingQueries += request to position
            return
        }
        output += EditorOutput.PositionAnswer(request, find(position))
    }

    /** Answers the queries that waited for the surface, now that it can find a position. */
    fun answerWaitingQueries() {
        val find = locator ?: return
        if (waitingQueries.isEmpty()) return
        for ((request, position) in waitingQueries.toList()) {
            output += EditorOutput.PositionAnswer(
                request,
                if (document.isValid(position)) find(position) else null,
            )
        }
        waitingQueries.clear()
    }

    /**
     * The requests of every query still waiting, which the editor will never answer now
     * because it is going away, and none waiting any more.
     */
    fun abandonQueries(): List<Int> {
        if (waitingQueries.isEmpty()) return emptyList()
        val requests = waitingQueries.map { it.first }
        waitingQueries.clear()
        return requests
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

    /**
     * The surface says how tall it draws a row and how far it is scrolled, both in pixels.
     * Said every frame, reported only when it is not what the Host was last told, and at
     * most once per drain, so a frame reports each of them at most once.
     */
    fun reportGeometry(lineHeightPx: Int, scrollPx: Int) {
        pendingLineHeight = lineHeightPx
        pendingScroll = scrollPx
    }

    /**
     * An offset the Host asked the view to be scrolled to, in the pixels the surface
     * reports its own offset in. Below zero is the top. A value that is not a number is
     * reported and dropped.
     */
    fun hostScrollTo(offsetPx: Double) {
        if (offsetPx.isNaN() || offsetPx.isInfinite()) {
            output += EditorOutput.Error("a scroll offset of $offsetPx is not a distance")
            return
        }
        val whole = offsetPx.coerceIn(0.0, Int.MAX_VALUE.toDouble())
        scrollRequest = ScrollRequest(whole.roundToInt())
    }

    /** The surface went where the last scroll request asked. */
    fun scrollDone(request: ScrollRequest) {
        if (scrollRequest === request) scrollRequest = null
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

    /**
     * Replaces every view zone with the Host's new list. A zone is read against the document
     * as it is now. One after a line the document does not have, or with a height that is not
     * a number above zero, is reported and left out.
     */
    fun setViewZones(records: List<ViewZoneRecord>) {
        zoneList.clear()
        for (record in records) {
            if (record.afterLine < 0 || record.afterLine >= document.lineCount) {
                output += EditorOutput.Error(
                    "a view zone follows line ${record.afterLine}, and the document has ${document.lineCount} lines",
                )
                continue
            }
            if (!(record.heightLines > 0f) || record.heightLines.isInfinite()) {
                output += EditorOutput.Error("a view zone after line ${record.afterLine} is ${record.heightLines} lines high")
                continue
            }
            zoneList += LiveViewZone(record.afterLine, record.heightLines, record.text)
        }
        overlayRevision += 1
    }

    // Long lines broken onto the rows below.

    /** The columns a long line is broken at, or 0 where none is. The surface that draws the editor sets it. */
    var wrapColumns = 0
        private set
    private var wrapTabWidth = 4

    /** How many rows past its first each line takes, while lines are broken. */
    private var wrapExtra: IntArray? = null

    /** Changes whenever [wrapExtra] does, for the running totals below. */
    private var wrapStamp = 0
    private var wrapPrefixStamp = -1
    private var wrapPrefixFolds = -1
    private var wrapPrefixCache: IntArray? = null

    /**
     * Breaks every line longer than [columns] onto the rows below it, a tab advancing to the
     * next of the stops [tabWidth] apart; 0 breaks none. Setting what is already set does
     * nothing, so a surface can say it on every frame.
     */
    fun setWrap(columns: Int, tabWidth: Int) {
        val wanted = maxOf(0, columns)
        if (wanted == wrapColumns && (wanted == 0 || tabWidth == wrapTabWidth)) return
        wrapColumns = wanted
        wrapTabWidth = tabWidth
        rewrapAll()
        overlayRevision += 1
    }

    private fun rewrapAll() {
        wrapExtra = if (wrapColumns == 0) {
            null
        } else {
            IntArray(document.lineCount) { wrapBreaks(document.line(it), wrapColumns, wrapTabWidth).size }
        }
        wrapStamp += 1
    }

    /** Where [line] breaks onto a new row, as the columns each row after the first starts at. */
    fun wrapBreaksOf(line: Int): IntArray =
        if (wrapColumns == 0 || line < 0 || line >= document.lineCount) NO_BREAKS
        else wrapBreaks(document.line(line), wrapColumns, wrapTabWidth)

    /**
     * The rows long lines wrap onto, as running totals for a row map, or null where nothing
     * wraps. Entry `i` counts the rows past their first of the lines before line `i`; a line
     * a fold hides counts none.
     */
    fun wrapPrefix(): IntArray? {
        val extra = wrapExtra ?: return null
        val cached = wrapPrefixCache
        if (cached != null && wrapPrefixStamp == wrapStamp && wrapPrefixFolds == foldStamp) return cached
        val prefix = IntArray(extra.size + 1)
        val folded = hiddenLines().isNotEmpty()
        for (line in extra.indices) {
            prefix[line + 1] = prefix[line] + if (folded && isHidden(line)) 0 else extra[line]
        }
        wrapPrefixCache = prefix
        wrapPrefixStamp = wrapStamp
        wrapPrefixFolds = foldStamp
        return prefix
    }

    /** Keeps the rows each line takes in step with a change: only the lines it touched are measured again. */
    private fun rewrap(change: AppliedChange) {
        val extra = wrapExtra ?: return
        val first = change.start.line
        val oldLast = change.end.line
        val newLast = change.newEnd.line
        val next = IntArray(document.lineCount)
        extra.copyInto(next, 0, 0, first)
        for (line in first..newLast) next[line] = wrapBreaks(document.line(line), wrapColumns, wrapTabWidth).size
        extra.copyInto(next, newLast + 1, oldLast + 1, extra.size)
        wrapExtra = next
        wrapStamp += 1
    }

    /** Replaces every mark beside the line numbers with the Host's new list. */
    fun setGutterDecorations(records: List<GutterDecorationRecord>) {
        gutterList.clear()
        gutterStamp += 1
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

    /** The marks of a line that sit in the mark column: every one that is not a change bar. */
    fun ordinaryMarksAt(line: Int): List<LiveGutterMark> =
        gutterList.filter { it.line == line && it.changeMark() == null }

    /**
     * The index in [gutterMarks] of the first mark at or after [from] that sits on [line] in
     * the mark column, or -1. Drawing walks the marks with this instead of building a list
     * per line, so a frame allocates nothing for them.
     */
    fun nextOrdinaryMarkIndex(line: Int, from: Int): Int {
        if (!ordinaryMarksPresent()) return -1
        for (i in from until gutterList.size) {
            val mark = gutterList[i]
            if (mark.line == line && mark.changeMark() == null) return i
        }
        return -1
    }

    private fun refreshMarkFlags() {
        if (flagsStamp == gutterStamp) return
        var bars = false
        var ordinary = false
        for (i in 0 until gutterList.size) {
            if (gutterList[i].changeMark() != null) bars = true else ordinary = true
        }
        changeBarsPresent = bars
        ordinaryMarksPresent = ordinary
        flagsStamp = gutterStamp
    }

    private fun ordinaryMarksPresent(): Boolean {
        refreshMarkFlags()
        return ordinaryMarksPresent
    }

    /** Whether any mark is a change bar, which is what makes the gutter reserve room for them. */
    val hasChangeBars: Boolean
        get() {
            refreshMarkFlags()
            return changeBarsPresent
        }

    /** Whether any mark belongs in the mark column. */
    val hasOrdinaryMarks: Boolean get() = ordinaryMarksPresent()

    /**
     * The one change bar a line shows. Several can sit on a line; the others stay in the
     * model and keep following edits, but only the strongest is drawn.
     */
    fun changeBarAt(line: Int): LiveGutterMark? {
        if (!hasChangeBars) return null
        var best: LiveGutterMark? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until gutterList.size) {
            val mark = gutterList[i]
            if (mark.line != line) continue
            val rank = mark.changeMark()?.ordinal ?: continue
            if (rank < bestRank) {
                best = mark
                bestRank = rank
            }
        }
        return best
    }

    /** The lines that show a change bar, by the change they show, in line order. */
    fun changeLines(): Map<ChangeMark, List<Int>> {
        val shown = HashMap<Int, ChangeMark>()
        for (mark in gutterList) {
            val change = mark.changeMark() ?: continue
            val old = shown[mark.line]
            if (old == null || change.ordinal < old.ordinal) shown[mark.line] = change
        }
        return shown.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.sorted() }
    }

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

    // Several selections at once, which a snippet's mirrors and the reader's extra carets are.

    /** The place [count] characters before [position], over line breaks, never inside a pair. */
    fun stepBack(position: CodePosition, count: Int): CodePosition {
        var at = position
        repeat(count) {
            at = when {
                at.column > 0 -> {
                    val line = document.line(at.line)
                    val step = if (at.column >= 2 && line[at.column - 1].isLowSurrogate() && line[at.column - 2].isHighSurrogate()) 2 else 1
                    CodePosition(at.line, at.column - step)
                }
                at.line > 0 -> CodePosition(at.line - 1, document.line(at.line - 1).length)
                else -> at
            }
        }
        return at
    }

    /** The place [count] characters after [position], over line breaks, never inside a pair. */
    fun stepForward(position: CodePosition, count: Int): CodePosition {
        var at = position
        repeat(count) {
            val line = document.line(at.line)
            at = when {
                at.column < line.length -> {
                    val step = if (line[at.column].isHighSurrogate() && at.column + 1 < line.length && line[at.column + 1].isLowSurrogate()) 2 else 1
                    CodePosition(at.line, at.column + step)
                }
                at.line < document.lineCount - 1 -> CodePosition(at.line + 1, 0)
                else -> at
            }
        }
        return at
    }

    /**
     * The same edit at every selection, as one undo step: a selection that is not collapsed
     * is replaced with [text], and at a caret the [before] characters in front of it and the
     * [after] characters behind it are. Each selection ends as a caret after what went in
     * there. Where two of them reach the same text they are one. Returns whether anything
     * changed.
     */
    fun editAtSelections(text: String, before: Int = 0, after: Int = 0, origin: EditOrigin = EditOrigin.Reader): Boolean {
        dismissGhostText()
        val all = selections
        // Each selection's range, with where it stands among the selections.
        val ranges = all.mapIndexed { index, selection ->
            if (selection.collapsed) {
                Triple(index, stepBack(selection.caret, before), stepForward(selection.caret, after))
            } else {
                Triple(index, selection.start, selection.end)
            }
        }.sortedWith(compareBy<Triple<Int, CodePosition, CodePosition>> { it.second }.thenBy { it.third })
        // Ranges that overlap are joined, the one that comes first in the list keeping its place.
        val joined = ArrayList<Triple<Int, CodePosition, CodePosition>>()
        for (range in ranges) {
            val last = joined.lastOrNull()
            if (last != null && range.second < last.third) {
                joined[joined.size - 1] = Triple(minOf(last.first, range.first), last.second, maxOf(last.third, range.third))
            } else if (last != null && range.second == last.second && range.third == last.third) {
                joined[joined.size - 1] = Triple(minOf(last.first, range.first), last.second, last.third)
            } else {
                joined += range
            }
        }
        if (text.isEmpty() && joined.all { it.second == it.third }) return false
        val selectionBefore = selection
        val secondaryBefore = secondary
        // From the end backwards, so the ranges in front are still where they were. The carets
        // already placed behind are moved by every change made in front of them.
        val carets = HashMap<Int, CodePosition>()
        val changes = ArrayList<AppliedChange>(joined.size)
        for ((index, start, end) in joined.asReversed()) {
            val change = applyRaw(start, end, text)
            changes += change
            for (key in carets.keys.toList()) carets[key] = CodeDocument.map(carets.getValue(key), change, Bias.After)
            carets[index] = change.newEnd
        }
        val placed = joined.map { CodeSelection.at(carets.getValue(it.first)) to it.first }.sortedBy { it.second }
        selection = placed.first().first
        secondary = placed.drop(1).map { it.first }
        redoStack.clear()
        undoStack += UndoEntry(
            changes,
            selectionBefore,
            selection,
            mergeable = false,
            at = clock(),
            secondaryBefore = secondaryBefore,
            secondaryAfter = secondary,
        )
        return true
    }

    /**
     * A reader's edit that a surface with one caret of its own made at the primary selection
     * as it stood before the edit, [primary], made again at every other selection. It is
     * repeated where it can be said the same way at a caret: text typed or pasted over the
     * selection, or characters taken from either side of a caret on its own line. Returns
     * false, and changes nothing, where it is not one of those; the caller then applies it
     * as an edit of one place.
     */
    fun editLikePrimary(primary: CodeSelection, start: CodePosition, end: CodePosition, text: String, origin: EditOrigin): Boolean {
        if (secondary.isEmpty()) return false
        if (!primary.collapsed) {
            if (start != primary.start || end != primary.end) return false
            val saved = selection
            selection = primary
            if (!editAtSelections(text, 0, 0, origin)) selection = saved
            return true
        }
        val caret = primary.caret
        if (start.line != caret.line || end.line != caret.line || start > caret || end < caret) return false
        val saved = selection
        selection = primary
        if (!editAtSelections(text, caret.column - start.column, end.column - caret.column, origin)) selection = saved
        return true
    }

    /** Back to the primary selection alone. Returns whether there were others. */
    fun dropSecondary(): Boolean {
        if (secondary.isEmpty()) return false
        secondary = emptyList()
        return true
    }

    // The reader's extra carets: added by a press, by the next match, or on the lines above
    // and below, and set by the Host.

    /**
     * Selections the Host asked for, the first of them the primary one, each a run of four
     * numbers (anchor line, anchor column, active line, active column) written against
     * [baseVersion]. Every end is moved past what the reader typed since. An end that is not
     * a place in the document, or no selections at all, drops the command and is reported.
     */
    fun hostSetSelections(baseVersion: Int, values: IntArray) {
        if (values.isEmpty() || values.size % 4 != 0) {
            output += EditorOutput.Error("a list of selections has ${values.size / 4} whole selections in it")
            return
        }
        val moved = ArrayList<CodeSelection>(values.size / 4)
        for (index in 0 until values.size / 4) {
            val anchor = CodePosition(values[index * 4], values[index * 4 + 1])
            val active = CodePosition(values[index * 4 + 2], values[index * 4 + 3])
            val (from, to) = moveAlong(baseVersion, "a selection", anchor, active, Bias.After, Bias.After) ?: return
            if (!document.isValid(from) || !document.isValid(to)) {
                output += EditorOutput.Error("the selection $anchor to $active is outside the document")
                return
            }
            if (moved.none { it.start == minOf(from, to) && it.end == maxOf(from, to) }) moved += CodeSelection(from, to)
        }
        dismissGhostText()
        selection = moved.first()
        secondary = moved.drop(1)
        hostSelection += 1
    }

    /** A caret added where the reader pressed with Alt held. One where a caret already is goes instead. */
    fun addCaret(position: CodePosition) {
        if (!document.isValid(position)) return
        val existing = secondary.indexOfFirst { it.collapsed && it.caret == position }
        when {
            existing >= 0 -> secondary = secondary.filterIndexed { index, _ -> index != existing }
            selection.collapsed && selection.caret == position -> {
                if (secondary.isEmpty()) return
                selection = secondary.first()
                secondary = secondary.drop(1)
            }
            else -> secondary = secondary + CodeSelection.at(position)
        }
    }

    /** The word around [position], or null where it is in none. */
    fun wordAt(position: CodePosition): Pair<CodePosition, CodePosition>? {
        val text = document.line(position.line)
        fun isWord(character: Char) = character.isLetterOrDigit() || character == '_'
        var start = position.column
        var end = position.column
        while (start > 0 && isWord(text[start - 1])) start -= 1
        while (end < text.length && isWord(text[end])) end += 1
        if (start == end) return null
        return CodePosition(position.line, start) to CodePosition(position.line, end)
    }

    /**
     * Cmd or Ctrl and D: with nothing selected, the word at the caret is selected; then each
     * press adds a selection on the next place the same text is, after the selection added
     * last and round the end of the document, passing over places already selected.
     * Returns whether anything changed.
     */
    fun addNextMatch(): Boolean {
        val last = secondary.lastOrNull() ?: selection
        if (last.collapsed) {
            if (secondary.isNotEmpty()) return false
            val (start, end) = wordAt(selection.caret) ?: return false
            selection = CodeSelection(start, end)
            hostSelection += 1
            return true
        }
        val wanted = document.textBetween(last.start, last.end)
        val whole = document.text()
        fun offsetOf(position: CodePosition): Int {
            var offset = 0
            for (line in 0 until position.line) offset += document.line(line).length + document.lineBreak(line).length
            return offset + position.column
        }
        fun positionOf(offset: Int): CodePosition {
            var rest = offset
            var line = 0
            while (line < document.lineCount - 1) {
                val length = document.line(line).length + document.lineBreak(line).length
                if (rest < length) break
                rest -= length
                line += 1
            }
            return CodePosition(line, minOf(rest, document.line(line).length))
        }
        val taken = selections.map { offsetOf(it.start) to offsetOf(it.end) }
        var from = offsetOf(last.end)
        repeat(2) {
            var at = whole.indexOf(wanted, from)
            while (at >= 0) {
                val end = at + wanted.length
                if (taken.none { (start, stop) -> at < stop && start < end }) {
                    secondary = secondary + CodeSelection(positionOf(at), positionOf(end))
                    return true
                }
                at = whole.indexOf(wanted, at + 1)
            }
            from = 0
        }
        return false
    }

    /**
     * Alt and Cmd or Ctrl with Up or Down: a caret on the line above the topmost selection,
     * or below the bottommost, at the same column where the line is that long. Returns
     * whether there was such a line.
     */
    fun addCaretVertically(down: Boolean): Boolean {
        val edge = if (down) selections.maxBy { it.caret } else selections.minBy { it.caret }
        val target = edge.caret.line + if (down) 1 else -1
        if (target < 0 || target >= document.lineCount) return false
        val line = nearestVisible(target, down)
        if (line == edge.caret.line || line < 0 || line >= document.lineCount) return false
        secondary = secondary + CodeSelection.at(CodePosition(line, minOf(edge.caret.column, document.line(line).length)))
        return true
    }

    /** Where a caret goes for a key that moves it, said without a picture of the text. */
    enum class CaretMove { Left, Right, Up, Down, Home, End }

    /**
     * [selection] moved by [move] the way the keys move a caret: a selection that is not
     * collapsed goes to its own edge for Left and Right, and [extend] keeps the anchor.
     */
    fun moved(selection: CodeSelection, move: CaretMove, extend: Boolean): CodeSelection {
        val caret = selection.caret
        val target = when (move) {
            CaretMove.Left -> if (!extend && !selection.collapsed) selection.start else stepBack(caret, 1)
            CaretMove.Right -> if (!extend && !selection.collapsed) selection.end else stepForward(caret, 1)
            CaretMove.Up, CaretMove.Down -> {
                val down = move == CaretMove.Down
                val line = nearestVisible((caret.line + if (down) 1 else -1).coerceIn(0, document.lineCount - 1), down)
                CodePosition(line, minOf(caret.column, document.line(line).length))
            }
            CaretMove.Home -> CodePosition(caret.line, 0)
            CaretMove.End -> CodePosition(caret.line, document.line(caret.line).length)
        }
        return CodeSelection(if (extend) selection.anchor else target, target)
    }

    /** Moves every selection past the primary one by [move]. The primary is its surface's to move. */
    fun moveSecondary(move: CaretMove, extend: Boolean) {
        if (secondary.isEmpty()) return
        secondary = secondary.map { moved(it, move, extend) }
    }

    // Snippet sessions.

    /** The snippet whose tab stops the reader is stepping through, or null. */
    private var snippet: SnippetSession? = null

    /** Changes when a snippet session starts, moves to another stop, or ends. */
    var snippetRevision: Int by mutableIntStateOf(0)
        private set

    /** Whether the reader is stepping through a snippet's tab stops. */
    val snippetActive: Boolean get() = snippet != null

    /** The choices of the stop the reader is on, while its list is open, or null. */
    val snippetChoices: List<String>?
        get() = snippet?.takeIf { it.choicesOpen }?.let { it.choices[it.order[it.current]] }

    /** The choice the list has marked. */
    var choiceIndex: Int by mutableIntStateOf(0)

    /**
     * A snippet the Host asked to have put in place of a range written against
     * [baseVersion]. The range is moved past what the reader typed since, and where they
     * typed in it the snippet is dropped and reported, as is a snippet that cannot be read.
     * What goes in is one undo step, reported as ordinary changes; each new line of it takes
     * the indentation of the line it goes into. Where it has tab stops, the reader is put on
     * the first.
     */
    fun hostInsertSnippet(baseVersion: Int, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, source: String) {
        val sentStart = CodePosition(startLine, startColumn)
        val sentEnd = CodePosition(endLine, endColumn)
        if (sentStart > sentEnd || startLine < 0 || startColumn < 0) {
            output += EditorOutput.Error("a snippet's range starts at $sentStart after it ends at $sentEnd")
            return
        }
        val parsed = parseSnippet(source) ?: run {
            output += EditorOutput.Error("a snippet cannot be read: \"$source\"")
            return
        }
        if (baseVersion > document.version) {
            output += EditorOutput.Error(
                "a snippet was written against version $baseVersion, and the document is at ${document.version}",
            )
            return
        }
        val changes = document.changesSince(baseVersion) ?: run {
            output += EditorOutput.Error(
                "a snippet was written against version $baseVersion, and the document remembers back to ${document.oldestVersion}",
            )
            return
        }
        var start = sentStart
        var end = sentEnd
        for (change in changes) {
            if (CodeDocument.overlaps(change, start, end)) {
                output += EditorOutput.Error("the reader changed the range $sentStart to $sentEnd a snippet was written for")
                return
            }
            if (start == end) {
                start = CodeDocument.map(start, change, Bias.After)
                end = start
            } else {
                start = CodeDocument.map(start, change, Bias.After)
                end = CodeDocument.map(end, change, Bias.Before)
            }
        }
        if (!document.isValid(start) || !document.isValid(end) || start > end) {
            output += EditorOutput.Error("a snippet's range $sentStart to $sentEnd is outside the document")
            return
        }
        endSnippet()
        dismissGhostText()
        val indent = document.line(start.line).takeWhile { it == ' ' || it == '\t' }
        val (text, offsets) = parsed.indented(indent)
        val selectionBefore = selection
        val secondaryBefore = secondary
        secondary = emptyList()
        val change = applyRaw(start, end, text)
        redoStack.clear()
        // Where an offset into the inserted text is in the document now.
        fun at(offset: Int): CodePosition {
            var line = start.line
            var column = start.column
            for (index in 0 until offset) {
                if (text[index] == '\n') {
                    line += 1
                    column = 0
                } else {
                    column += 1
                }
            }
            return CodePosition(line, column)
        }
        val places = HashMap<Int, MutableList<SnippetPlace>>()
        val choices = HashMap<Int, List<String>>()
        for ((index, stop) in parsed.stops.withIndex()) {
            val (from, to) = offsets[index]
            places.getOrPut(stop.number) { ArrayList() } += SnippetPlace(at(from), at(to))
            stop.choices?.let { if (stop.number !in choices) choices[stop.number] = it }
        }
        val numbers = places.keys.filter { it != 0 }.sorted()
        val final = places[0]?.first() ?: SnippetPlace(change.newEnd, change.newEnd)
        if (numbers.isEmpty()) {
            selection = CodeSelection.at(final.start)
        } else {
            val session = SnippetSession(numbers + 0, places.mapValues { it.value.toList() } + (0 to listOf(final)), choices, SnippetPlace(start, change.newEnd))
            snippet = session
            showStop(session)
        }
        undoStack += UndoEntry(
            listOf(change),
            selectionBefore,
            selection,
            mergeable = false,
            at = clock(),
            secondaryBefore = secondaryBefore,
            secondaryAfter = secondary,
        )
        hostSelection += 1
        snippetRevision += 1
    }

    /** Puts the selections on the stop the session is at, and opens its list where it has choices. */
    private fun showStop(session: SnippetSession) {
        val places = session.places.getValue(session.order[session.current])
        selection = CodeSelection(places[0].start, places[0].end)
        secondary = places.drop(1).map { CodeSelection(it.start, it.end) }
        session.choicesOpen = session.choices.containsKey(session.order[session.current])
        choiceIndex = 0
    }

    /**
     * Tab in a snippet: the next stop, or `$0` and the end of the session. A list of choices
     * that is open has its marked choice taken first. Returns false where there is no session.
     */
    fun nextSnippetStop(): Boolean {
        val session = snippet ?: return false
        if (session.choicesOpen) pickChoice(choiceIndex)
        session.current += 1
        if (session.current >= session.order.size - 1) {
            // The last stop is `$0`: the caret goes there and the session is over.
            val final = session.places.getValue(0).first()
            selection = CodeSelection.at(final.start)
            secondary = emptyList()
            snippet = null
        } else {
            showStop(session)
        }
        hostSelection += 1
        snippetRevision += 1
        return true
    }

    /** Shift+Tab in a snippet: the stop before, if there is one. Returns false where there is no session. */
    fun previousSnippetStop(): Boolean {
        val session = snippet ?: return false
        if (session.current > 0) session.current -= 1
        showStop(session)
        hostSelection += 1
        snippetRevision += 1
        return true
    }

    /** Ends the session, leaving the caret where it is. Returns whether there was one. */
    fun endSnippet(): Boolean {
        snippet ?: return false
        snippet = null
        secondary = emptyList()
        snippetRevision += 1
        return true
    }

    /** Closes the list of choices without taking one. Returns whether one was open. */
    fun closeChoices(): Boolean {
        val session = snippet ?: return false
        if (!session.choicesOpen) return false
        session.choicesOpen = false
        snippetRevision += 1
        return true
    }

    /** Moves the mark in the open list of choices by [step], round the ends. */
    fun moveChoice(step: Int) {
        val choices = snippetChoices ?: return
        choiceIndex = ((choiceIndex + step) % choices.size + choices.size) % choices.size
    }

    /** Puts choice [index] in every place of the stop the reader is on, and closes the list. */
    fun pickChoice(index: Int) {
        val session = snippet ?: return
        val choices = session.choices[session.order[session.current]] ?: return
        val choice = choices.getOrNull(index) ?: return
        session.choicesOpen = false
        val places = session.places.getValue(session.order[session.current])
        selection = CodeSelection(places[0].start, places[0].end)
        secondary = places.drop(1).map { CodeSelection(it.start, it.end) }
        editAtSelections(choice)
        val moved = session.places.getValue(session.order[session.current])
        selection = CodeSelection(moved[0].start, moved[0].end)
        secondary = moved.drop(1).map { CodeSelection(it.start, it.end) }
        hostSelection += 1
        snippetRevision += 1
    }

    /** Ends the session where the caret has left the snippet. */
    private fun keepSnippetAround() {
        val session = snippet ?: return
        val caret = selection.caret
        if (caret < session.bounds.start || caret > session.bounds.end) endSnippet()
    }

    /** Undoes the last step. Each change it makes is reported, in order. */
    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        endSnippet()
        redoStack += revert(entry)
        return true
    }

    /** Does again what was last undone. */
    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        endSnippet()
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
        secondary = entry.secondaryBefore
        return UndoEntry(
            inverses,
            selectionBefore = entry.selectionAfter,
            selectionAfter = entry.selectionBefore,
            mergeable = false,
            at = 0L,
            secondaryBefore = entry.secondaryAfter,
            secondaryAfter = entry.secondaryBefore,
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
        rewrap(change)
        moveOverlays(change)
        selection = CodeSelection(
            CodeDocument.map(selection.anchor, change, Bias.After),
            CodeDocument.map(selection.caret, change, Bias.After),
        )
        if (secondary.isNotEmpty()) {
            secondary = secondary.map {
                CodeSelection(CodeDocument.map(it.anchor, change, Bias.After), CodeDocument.map(it.caret, change, Bias.After))
            }
        }
        snippet?.follow(change)
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
                if (decoration.kind == DecorationKind.LineBackground) {
                    // Lines rather than a stretch of text: a line typed in front of them
                    // pushes them down, and they go only when every one of them is deleted.
                    val lines = CodeDocument.mapRange(
                        CodePosition(decoration.start.line, 0),
                        CodePosition(decoration.end.line + 1, 0),
                        change,
                    )
                    val last = lines?.let { if (it.second.column == 0) it.second.line - 1 else it.second.line }
                    if (lines == null || last == null || last < lines.first.line) {
                        iterator.remove()
                    } else {
                        decoration.start = CodePosition(lines.first.line, 0)
                        decoration.end = CodePosition(minOf(last, document.lineCount - 1), 0)
                    }
                    continue
                }
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
        if (zoneList.isNotEmpty()) {
            // A zone stays after its line: a line typed in front of it moves it down, typing on
            // its line leaves it, and where its line is deleted it goes to the line the
            // deletion closed up on.
            for (zone in zoneList) {
                zone.afterLine = CodeDocument.map(CodePosition(zone.afterLine, 0), change, Bias.After).line
                    .coerceIn(0, document.lineCount - 1)
            }
            moved = true
        }
        if (gutterList.isNotEmpty()) {
            gutterStamp += 1
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
        /** The selections past the primary one, before and after the step. */
        val secondaryBefore: List<CodeSelection> = emptyList(),
        val secondaryAfter: List<CodeSelection> = emptyList(),
    )

    companion object {
        /** Characters typed within this long of each other undo together. */
        const val TYPING_GROUP_NANOS: Long = 1_000_000_000L
    }
}

/** One place a snippet's tab stop is in the document, followed through edits. */
class SnippetPlace(var start: CodePosition, var end: CodePosition) {
    /**
     * Moves the place past a change. Text typed at either edge of it is taken in, so typing
     * into an empty stop grows the stop rather than leaving the text beside it.
     */
    fun follow(change: AppliedChange) {
        start = CodeDocument.map(start, change, Bias.Before)
        end = CodeDocument.map(end, change, Bias.After)
        if (end < start) end = start
    }
}

/**
 * A snippet the reader is stepping through: its stops in the order Tab visits them, `$0`
 * last, the places of each, the choices of those that have them, and the whole snippet,
 * which the caret leaving ends the session.
 */
internal class SnippetSession(
    val order: List<Int>,
    val places: Map<Int, List<SnippetPlace>>,
    val choices: Map<Int, List<String>>,
    val bounds: SnippetPlace,
) {
    var current = 0
    var choicesOpen = false

    fun follow(change: AppliedChange) {
        for (list in places.values) for (place in list) place.follow(change)
        bounds.follow(change)
    }
}

/** One tab stop of a parsed snippet: its number, and its choices where it has them. */
class ParsedStop(val number: Int, val choices: List<String>?)

/**
 * A snippet read: its text, and for each stop in the order they appear, where it is in
 * that text, as a start and an end offset.
 */
class ParsedSnippet(val text: String, val stops: List<ParsedStop>, val offsets: List<Pair<Int, Int>>) {
    /** The text with [indent] after every line break, and the stops' offsets in it. */
    fun indented(indent: String): Pair<String, List<Pair<Int, Int>>> {
        if (indent.isEmpty() || '\n' !in text) return text to offsets
        val moved = IntArray(text.length + 1)
        val builder = StringBuilder()
        for (index in text.indices) {
            moved[index] = builder.length
            builder.append(text[index])
            if (text[index] == '\n') builder.append(indent)
        }
        moved[text.length] = builder.length
        return builder.toString() to offsets.map { (from, to) -> moved[from] to moved[to] }
    }
}

/**
 * Reads a snippet in the subset of VS Code's syntax the editor takes: `$1`, `${1}`,
 * `${1:placeholder}` (a placeholder may hold stops of its own), `${1|one,two|}`, `$0`, and a
 * backslash before `$`, `}` or `\\`, and inside choices before `,` and `|`, for the
 * character itself. A `$` that starts none of these is itself. Every place of one number
 * shows the text of the first of them that has any, so `${1:name} = $1` reads `name = name`.
 * Null where the snippet is not one: a `${` that is never closed, a number too large, or
 * stops that make each other's text.
 */
fun parseSnippet(source: String): ParsedSnippet? {
    var index = 0
    fun number(): Int? {
        val from = index
        while (index < source.length && source[index].isDigit()) index += 1
        if (index == from || index - from > 6) return null
        return source.substring(from, index).toInt()
    }
    // Reads segments up to an unescaped `}` when [nested], or to the end.
    fun segments(nested: Boolean): List<SnippetSegment>? {
        val found = ArrayList<SnippetSegment>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                found += SnippetText(text.toString())
                text.clear()
            }
        }
        while (index < source.length) {
            val character = source[index]
            when {
                character == '\\' && index + 1 < source.length && source[index + 1] in "\$}\\" -> {
                    text.append(source[index + 1])
                    index += 2
                }
                character == '}' && nested -> {
                    flush()
                    return found
                }
                character == '$' && index + 1 < source.length && source[index + 1].isDigit() -> {
                    index += 1
                    val number = number() ?: return null
                    flush()
                    found += SnippetStop(number, null, null)
                }
                character == '$' && index + 2 < source.length && source[index + 1] == '{' && source[index + 2].isDigit() -> {
                    index += 2
                    val number = number() ?: return null
                    if (index >= source.length) return null
                    flush()
                    when (source[index]) {
                        '}' -> {
                            index += 1
                            found += SnippetStop(number, null, null)
                        }
                        ':' -> {
                            index += 1
                            val children = segments(nested = true) ?: return null
                            if (index >= source.length || source[index] != '}') return null
                            index += 1
                            found += SnippetStop(number, children, null)
                        }
                        '|' -> {
                            index += 1
                            val choices = ArrayList<String>()
                            val choice = StringBuilder()
                            var closed = false
                            while (index < source.length) {
                                val next = source[index]
                                if (next == '\\' && index + 1 < source.length && source[index + 1] in ",|\\$}") {
                                    choice.append(source[index + 1])
                                    index += 2
                                } else if (next == ',') {
                                    choices += choice.toString()
                                    choice.clear()
                                    index += 1
                                } else if (next == '|' && index + 1 < source.length && source[index + 1] == '}') {
                                    choices += choice.toString()
                                    index += 2
                                    closed = true
                                    break
                                } else {
                                    choice.append(next)
                                    index += 1
                                }
                            }
                            if (!closed) return null
                            found += SnippetStop(number, null, choices)
                        }
                        else -> return null
                    }
                }
                else -> {
                    text.append(character)
                    index += 1
                }
            }
        }
        if (nested) return null
        flush()
        return found
    }
    val tree = segments(nested = false) ?: return null

    // The text each number shows: that of its first place with a placeholder or choices.
    val defining = HashMap<Int, SnippetStop>()
    fun collect(list: List<SnippetSegment>) {
        for (segment in list) {
            if (segment is SnippetStop) {
                if ((segment.children != null || segment.choices != null) && segment.number !in defining) defining[segment.number] = segment
                segment.children?.let(::collect)
            }
        }
    }
    collect(tree)

    val text = StringBuilder()
    val stops = ArrayList<ParsedStop>()
    val offsets = ArrayList<Pair<Int, Int>>()
    var broken = false
    fun render(list: List<SnippetSegment>, depth: Int) {
        if (depth > 16) {
            broken = true
            return
        }
        for (segment in list) {
            when (segment) {
                is SnippetText -> text.append(segment.value)
                is SnippetStop -> {
                    val slot = stops.size
                    stops += ParsedStop(segment.number, segment.choices ?: defining[segment.number]?.choices)
                    offsets += 0 to 0
                    val from = text.length
                    when {
                        segment.choices != null -> text.append(segment.choices.firstOrNull() ?: "")
                        segment.children != null -> render(segment.children, depth + 1)
                        else -> {
                            val origin = defining[segment.number]
                            when {
                                origin?.choices != null -> text.append(origin.choices.firstOrNull() ?: "")
                                // A mirror shows the placeholder's text, without stops of its own.
                                origin?.children != null -> text.append(plain(origin.children))
                            }
                        }
                    }
                    offsets[slot] = from to text.length
                }
            }
        }
    }
    render(tree, 0)
    if (broken) return null
    return ParsedSnippet(text.toString(), stops, offsets)
}

/** A piece of a snippet as it was read: text, or a tab stop. */
private sealed class SnippetSegment

private class SnippetText(val value: String) : SnippetSegment()

private class SnippetStop(val number: Int, val children: List<SnippetSegment>?, val choices: List<String>?) : SnippetSegment()

/** The text of snippet segments, a stop inside them shown as its own text. */
private fun plain(list: List<SnippetSegment>, depth: Int = 0): String {
    if (depth > 16) return ""
    val builder = StringBuilder()
    for (segment in list) {
        when (segment) {
            is SnippetText -> builder.append(segment.value)
            is SnippetStop -> when {
                segment.choices != null -> builder.append(segment.choices.firstOrNull() ?: "")
                segment.children != null -> builder.append(plain(segment.children, depth + 1))
            }
        }
    }
    return builder.toString()
}

private val NO_BREAKS = IntArray(0)

/** Whether a character takes two columns of a monospaced face: East Asian wide letters and pictures. */
internal fun isWideCharacter(codePoint: Int): Boolean =
    codePoint in 0x1100..0x115F || codePoint in 0x2E80..0xA4CF || codePoint in 0xAC00..0xD7A3 ||
        codePoint in 0xF900..0xFAFF || codePoint in 0xFE30..0xFE4F || codePoint in 0xFF00..0xFF60 ||
        codePoint in 0xFFE0..0xFFE6 || codePoint in 0x1F300..0x1FAFF || codePoint in 0x20000..0x3FFFD

/**
 * Where a line breaks onto new rows [columns] columns wide: the column, in UTF-16 units, each
 * row after the first starts at. A row breaks after the last space or tab that fits in it,
 * and where a word is longer than the row, at the column itself; never between the halves
 * of a pair. A tab advances to the next of the stops [tabWidth] apart, counted from the start
 * of its row, and an East Asian wide letter takes two columns. Empty where the line fits.
 */
fun wrapBreaks(text: String, columns: Int, tabWidth: Int): IntArray {
    if (columns <= 0 || text.length <= columns / 2 && '\t' !in text) return NO_BREAKS
    var breaks: ArrayList<Int>? = null
    var rowStart = 0
    var column = 0
    var afterSpace = -1
    var index = 0
    val tab = maxOf(1, tabWidth)
    fun widthAt(at: Int, shown: Int): Int {
        val character = text[at]
        if (character == '\t') return tab - shown % tab
        val pair = character.isHighSurrogate() && at + 1 < text.length && text[at + 1].isLowSurrogate()
        val code = if (pair) 0x10000 + ((character.code - 0xD800) shl 10) + (text[at + 1].code - 0xDC00) else character.code
        return if (isWideCharacter(code)) 2 else 1
    }
    fun sizeAt(at: Int): Int =
        if (text[at].isHighSurrogate() && at + 1 < text.length && text[at + 1].isLowSurrogate()) 2 else 1
    while (index < text.length) {
        val width = widthAt(index, column)
        if (column + width > columns && index > rowStart) {
            val at = if (afterSpace > rowStart && afterSpace <= index) afterSpace else index
            (breaks ?: ArrayList<Int>().also { breaks = it }) += at
            rowStart = at
            afterSpace = -1
            column = 0
            var walk = at
            while (walk < index) {
                column += widthAt(walk, column)
                walk += sizeAt(walk)
            }
            continue
        }
        column += width
        val size = sizeAt(index)
        if (text[index] == ' ' || text[index] == '\t') afterSpace = index + size
        index += size
    }
    return breaks?.toIntArray() ?: NO_BREAKS
}
