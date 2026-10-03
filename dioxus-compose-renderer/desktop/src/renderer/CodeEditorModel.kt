package dioxus.compose.foundation.code

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.DecorationKind
import dioxus.compose.protocol.DecorationRecord
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.Severity
import dioxus.compose.protocol.SyntaxSpanRecord
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

    private val decorationList = ArrayList<LiveDecoration>()
    private val spanList = ArrayList<LiveSpan>()

    /** How many lines the longest colour run covers, so a window can find runs that start above it. */
    private var longestSpanLines = 0

    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()
    private val output = ArrayList<EditorOutput>()

    val decorations: List<LiveDecoration> get() = decorationList
    val spans: List<LiveSpan> get() = spanList

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The suggestion on show, if there is one. */
    val ghostText: LiveDecoration? get() = decorationList.firstOrNull { it.kind == DecorationKind.GhostText }

    /** Everything queued for the Host since the last call, in order, and an empty queue. */
    fun drainOutput(): List<EditorOutput> {
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
        .toSortedMap()
        .map { (line, lenses) -> line to lenses }

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
        return UndoEntry(inverses, entry.selectionAfter, mergeable = false, at = 0L)
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
