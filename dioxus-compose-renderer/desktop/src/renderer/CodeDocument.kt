package dioxus.compose.foundation.code

/**
 * A place in a code editor's document: a line and a column, both from zero.
 *
 * The column counts UTF-16 code units, which is what a Kotlin `String` indexes by and what a
 * language server counts in, so a position here is a position there with nothing converted.
 */
data class CodePosition(val line: Int, val column: Int) : Comparable<CodePosition> {
    override fun compareTo(other: CodePosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)

    override fun toString(): String = "$line:$column"

    companion object {
        val Zero = CodePosition(0, 0)
    }
}

/**
 * One change that was applied to a document.
 *
 * `start` and `end` are in the document as it stood before the change, `newEnd` is where the
 * inserted text ends afterwards, and `removed` is the text the change took out, which is what
 * undoing it puts back. `version` is the version the change produced.
 */
class AppliedChange(
    val version: Int,
    val start: CodePosition,
    val end: CodePosition,
    val text: String,
    val newEnd: CodePosition,
    val removed: String,
) {
    override fun toString(): String = "v$version [$start, $end) -> \"$text\" (now ends $newEnd)"
}

/**
 * Which way a position goes when text is inserted exactly where it is.
 *
 * `After` follows the insertion, the way the start of a mark does when text is typed right in
 * front of it. `Before` stays put, the way the end of a mark does when text is typed right
 * after it, so a mark never grows to take in what was typed beside it.
 */
enum class Bias { Before, After }

/**
 * The text of a code editor, held as lines, with a version that counts committed changes.
 *
 * A line ends at `\n`, `\r\n` or a lone `\r`, the language server's rule, and each line keeps
 * the break it was written with so that the text read back is the text that came in. Version
 * 0 is the text the document was opened with, and every change adds one.
 *
 * The changes since an earlier version are kept, up to [HISTORY_LIMIT] of them, so that a
 * range written against that version can be moved to where the same text is now. That is
 * what lets the Host send decorations computed while the reader kept typing, and edits
 * computed against a version the reader has since left behind.
 *
 * Nothing here knows about Compose or the boundary, so it can be measured and tested on its
 * own.
 */
class CodeDocument(text: String = "") {
    private val lines = ArrayList<String>()
    private val breaks = ArrayList<String>()
    private val history = ArrayDeque<AppliedChange>()

    /** The version the document is at. */
    var version: Int = 0
        private set

    /**
     * The oldest version a range can still be moved forward from. Older than this, the
     * changes in between have been forgotten.
     */
    var oldestVersion: Int = 0
        private set

    init {
        split(text, lines, breaks)
    }

    val lineCount: Int get() = lines.size

    fun line(index: Int): String = lines[index]

    /** The break that ends a line, empty for the last one. */
    fun lineBreak(index: Int): String = breaks[index]

    /** Where the document ends. */
    val end: CodePosition get() = CodePosition(lines.size - 1, lines[lines.size - 1].length)

    /** The whole text, breaks as they were written. */
    fun text(): String {
        val builder = StringBuilder()
        for (index in lines.indices) builder.append(lines[index]).append(breaks[index])
        return builder.toString()
    }

    /** The text between two positions. */
    fun textBetween(start: CodePosition, end: CodePosition): String {
        if (start.line == end.line) return lines[start.line].substring(start.column, end.column)
        val builder = StringBuilder()
        builder.append(lines[start.line], start.column, lines[start.line].length)
        builder.append(breaks[start.line])
        for (index in start.line + 1 until end.line) builder.append(lines[index]).append(breaks[index])
        builder.append(lines[end.line], 0, end.column)
        return builder.toString()
    }

    /**
     * Whether [text] is exactly this document, read without building the document as one
     * string, so that a Host sending back the text the reader already has costs a walk and
     * not a copy.
     */
    fun contentEquals(text: CharSequence): Boolean {
        var offset = 0
        for (index in lines.indices) {
            val line = lines[index]
            if (!text.regionMatchesAt(offset, line)) return false
            offset += line.length
            val lineBreak = breaks[index]
            if (!text.regionMatchesAt(offset, lineBreak)) return false
            offset += lineBreak.length
        }
        return offset == text.length
    }

    /** Opens other text as version 0. Every earlier change is forgotten. */
    fun reset(text: String) {
        lines.clear()
        breaks.clear()
        split(text, lines, breaks)
        history.clear()
        version = 0
        oldestVersion = 0
    }

    /**
     * Whether a position names a place in the document: a line that exists, a column inside
     * it or at its end, and not between the two halves of a character written as a pair.
     */
    fun isValid(position: CodePosition): Boolean {
        if (position.line < 0 || position.line >= lines.size) return false
        val line = lines[position.line]
        if (position.column < 0 || position.column > line.length) return false
        if (position.column in 1 until line.length &&
            line[position.column - 1].isHighSurrogate() &&
            line[position.column].isLowSurrogate()
        ) {
            return false
        }
        return true
    }

    /**
     * Replaces the text from [start] up to [end] with [text], and says what changed.
     *
     * The lines either side are split again together with the new text, so a break that
     * arrives in pieces, a `\r` typed in front of a `\n`, ends up as the one break a
     * language server would read there.
     */
    fun replace(start: CodePosition, end: CodePosition, text: String): AppliedChange {
        require(isValid(start) && isValid(end) && start <= end) {
            "the range $start to $end is not in this document"
        }
        val removed = textBetween(start, end)
        val first = maxOf(0, start.line - 1)
        val last = end.line
        val region = StringBuilder()
        for (index in first until start.line) region.append(lines[index]).append(breaks[index])
        region.append(lines[start.line], 0, start.column)
        region.append(text)
        val insertedEnd = region.length
        region.append(lines[last], end.column, lines[last].length).append(breaks[last])
        val newLines = ArrayList<String>()
        val newBreaks = ArrayList<String>()
        split(region, newLines, newBreaks)
        if (breaks[last].isNotEmpty()) {
            // The region ended with a break, so splitting it left an empty line after it
            // that belongs to the line the region stopped in front of.
            newLines.removeAt(newLines.size - 1)
            newBreaks.removeAt(newBreaks.size - 1)
        }
        // Where the inserted text ends, found in the region as it was split.
        var offset = 0
        var newEnd = CodePosition(first + newLines.size - 1, newLines.last().length)
        for (index in newLines.indices) {
            val lineEnd = offset + newLines[index].length
            if (insertedEnd <= lineEnd) {
                newEnd = CodePosition(first + index, insertedEnd - offset)
                break
            }
            val breakEnd = lineEnd + newBreaks[index].length
            if (insertedEnd < breakEnd) {
                // Inside a two-character break, which is not a place: the end of the line.
                newEnd = CodePosition(first + index, newLines[index].length)
                break
            }
            offset = breakEnd
        }
        replaceRange(lines, first, last, newLines)
        replaceRange(breaks, first, last, newBreaks)
        version += 1
        val change = AppliedChange(version, start, end, text, newEnd, removed)
        history.addLast(change)
        while (history.size > HISTORY_LIMIT) {
            oldestVersion = history.removeFirst().version
        }
        return change
    }

    /**
     * The changes that took the document from [since] to where it is now, in order, or null
     * where that version is in the future or older than the document remembers.
     */
    fun changesSince(since: Int): List<AppliedChange>? {
        if (since > version || since < oldestVersion) return null
        if (since == version) return emptyList()
        val skip = history.size - (version - since)
        // A copy, because applying the edit these were asked for adds to the history.
        return history.subList(skip, history.size).toList()
    }

    companion object {
        /** How many changes are remembered for moving old ranges forward. */
        const val HISTORY_LIMIT = 10_000

        /**
         * Where [position] is after [change].
         *
         * A position inside the text the change took out has nowhere to be; it goes to the
         * start of the replacement with [Bias.Before] and to its end with [Bias.After].
         */
        fun map(position: CodePosition, change: AppliedChange, bias: Bias): CodePosition {
            val start = change.start
            val end = change.end
            if (position < start) return position
            if (position == start) {
                return if (start == end && bias == Bias.After) change.newEnd else position
            }
            if (position < end) return if (bias == Bias.After) change.newEnd else start
            return if (position.line == end.line) {
                CodePosition(change.newEnd.line, change.newEnd.column + position.column - end.column)
            } else {
                CodePosition(position.line + change.newEnd.line - end.line, position.column)
            }
        }

        /**
         * Where a range is after [change], or null where the change took out all of its text.
         *
         * The start follows text inserted right in front of it and the end does not follow
         * text inserted right after it, so a range never takes in what was typed beside it.
         * An empty range, a place rather than a stretch, goes where an insertion at it goes,
         * and disappears only when the change took out text on both sides of it.
         */
        fun mapRange(start: CodePosition, end: CodePosition, change: AppliedChange): Pair<CodePosition, CodePosition>? {
            val deleting = change.start < change.end
            if (start == end) {
                if (deleting && change.start < start && start < change.end) return null
                val moved = map(start, change, Bias.After)
                return moved to moved
            }
            if (deleting && change.start <= start && end <= change.end) return null
            val newStart = map(start, change, Bias.After)
            var newEnd = map(end, change, Bias.Before)
            if (newEnd < newStart) newEnd = newStart
            if (newStart == newEnd) return null
            return newStart to newEnd
        }

        /**
         * Whether a change the reader made touches a range the Host wrote against an older
         * version: whether applying the Host's edit there would write over what the reader
         * typed.
         *
         * Ranges that only meet at an edge do not overlap. An insertion overlaps a range only
         * from strictly inside it.
         */
        fun overlaps(change: AppliedChange, start: CodePosition, end: CodePosition): Boolean {
            val changeStart = change.start
            val changeEnd = change.end
            if (changeStart == changeEnd) return start < changeStart && changeStart < end
            if (start == end) return changeStart < start && start < changeEnd
            return changeStart < end && start < changeEnd
        }

        private fun split(text: CharSequence, into: MutableList<String>, breaksInto: MutableList<String>) {
            var start = 0
            var index = 0
            while (index < text.length) {
                val character = text[index]
                if (character == '\n' || character == '\r') {
                    into.add(text.subSequence(start, index).toString())
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        breaksInto.add("\r\n")
                        index += 2
                    } else {
                        breaksInto.add(if (character == '\n') "\n" else "\r")
                        index += 1
                    }
                    start = index
                } else {
                    index += 1
                }
            }
            into.add(text.subSequence(start, text.length).toString())
            breaksInto.add("")
        }

        private fun <T> replaceRange(list: ArrayList<T>, first: Int, last: Int, with: List<T>) {
            val common = minOf(last - first + 1, with.size)
            for (index in 0 until common) list[first + index] = with[index]
            if (with.size > common) {
                list.addAll(first + common, with.subList(common, with.size))
            } else if (last - first + 1 > common) {
                list.subList(first + common, last + 1).clear()
            }
        }

        private fun CharSequence.regionMatchesAt(offset: Int, part: String): Boolean {
            if (offset + part.length > length) return false
            for (index in part.indices) if (this[offset + index] != part[index]) return false
            return true
        }
    }
}
