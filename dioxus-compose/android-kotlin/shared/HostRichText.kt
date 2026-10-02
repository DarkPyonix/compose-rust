package dioxus.compose.foundation

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.TypeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.runtime.onProtocolError
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.TableError
import dioxus.compose.ui.maxLines
import dioxus.compose.ui.overflow
import dioxus.compose.ui.textStyle

/** Bytes of one span record, which the Host writes and this reads back. */
private const val SPAN_LEN = 28

private const val FLAG_BOLD = 1
private const val FLAG_ITALIC = 2
private const val FLAG_UNDERLINE = 4
private const val FLAG_STRIKETHROUGH = 8

/**
 * One run of different treatment inside a string.
 *
 * Offsets are in bytes of the UTF-8 the Host measured, because that is what the string is
 * measured in on that side. They are turned into character offsets here, where the string
 * is a sequence of characters, and a run that does not land on a character boundary is a
 * malformed run rather than a run that draws oddly.
 */
internal data class TextRun(
    val start: Int,
    val length: Int,
    val role: TypeRole?,
    val color: Paint?,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val handlerId: Long,
)

/**
 * A paint from the bits the Host wrote, or null where it said nothing.
 *
 * The same two kinds the rest of the protocol uses. Decoded here rather than reached for
 * in the generated reader, which decodes from a buffer at an offset and has no offset to
 * give for a value that came out of a blob.
 */
private fun paintOf(bits: Long): Paint? = when ((bits ushr 32).toInt()) {
    1 -> ColorRole.entries.getOrNull(bits.toInt() - 1)?.let(Paint::Role)
    2 -> Paint.Literal(bits.toInt())
    else -> null
}

/** Reads the blob the Host sent. A blob that is not a whole number of records is refused. */
internal fun decodeRuns(bytes: ByteArray): List<TextRun>? {
    if (bytes.size % SPAN_LEN != 0) return null
    fun word(at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
    fun half(at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    return (0 until bytes.size / SPAN_LEN).map { index ->
        val at = index * SPAN_LEN
        val flags = half(at + 10)
        val paintBits = word(at + 12) or (word(at + 16) shl 32)
        val handler = word(at + 20) or (word(at + 24) shl 32)
        TextRun(
            start = word(at).toInt(),
            length = word(at + 4).toInt(),
            role = TypeRole.entries.getOrNull(half(at + 8) - 1),
            color = paintOf(paintBits),
            bold = flags and FLAG_BOLD != 0,
            italic = flags and FLAG_ITALIC != 0,
            underline = flags and FLAG_UNDERLINE != 0,
            strikethrough = flags and FLAG_STRIKETHROUGH != 0,
            handlerId = handler,
        )
    }
}

/**
 * What is wrong with a set of runs, or null when nothing is.
 *
 * Checked rather than trusted, and reported rather than thrown: a run reaching past the
 * end of the string is a Host that miscounted, and that must not take the window down
 * with it.
 */
internal fun runsProblem(runs: List<TextRun>, byteLength: Int): String? {
    var previousEnd = 0
    for (run in runs) {
        if (run.length <= 0) {
            return "a run of ${run.length} bytes at ${run.start} covers nothing"
        }
        if (run.start < previousEnd) {
            return "a run at ${run.start} starts inside the one before it, which ended at $previousEnd"
        }
        if (run.start + run.length > byteLength) {
            return "a run covering bytes ${run.start} to ${run.start + run.length} reaches past a string of $byteLength"
        }
        previousEnd = run.start + run.length
    }
    return null
}

/**
 * A string whose runs are drawn differently, and whose link runs report a press.
 *
 * One widget rather than three, because a paragraph with a bold phrase in it is one piece
 * of text: split into pieces it would wrap at the seams, and the phrase would never share
 * a line with the words around it.
 */
@Composable
internal fun HostRichText(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val raw = node.text(PropertyKind.Text)
    val blob = (node.property(PropertyKind.Spans) as? PropertyValue.Bytes)?.value
    val runs = blob?.let(::decodeRuns)

    if (blob == null || runs == null || runs.isEmpty()) {
        if (blob != null && runs == null) {
            reportRuns("the run list is not a whole number of records")
        }
        BasicText(
            text = raw,
            modifier = modifier,
            style = node.textStyle(theme),
            maxLines = node.maxLines(),
            overflow = node.overflow(),
        )
        return
    }

    val utf8 = raw.encodeToByteArray()
    val problem = runsProblem(runs, utf8.size)
    if (problem != null) {
        reportRuns(problem)
        BasicText(
            text = raw,
            modifier = modifier,
            style = node.textStyle(theme),
            maxLines = node.maxLines(),
            overflow = node.overflow(),
        )
        return
    }

    val annotated = buildAnnotatedString {
        append(raw)
        for (run in runs) {
            val from = utf8.decodeToString(0, run.start).length
            val to = utf8.decodeToString(0, run.start + run.length).length
            val style = SpanStyle(
                color = run.color?.let(theme::color) ?: androidx.compose.ui.graphics.Color.Unspecified,
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontSize = run.role?.let { theme.type(it).size.sp }
                    ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                textDecoration = when {
                    run.underline && run.strikethrough ->
                        TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                        )
                    run.underline -> TextDecoration.Underline
                    run.strikethrough -> TextDecoration.LineThrough
                    else -> null
                },
            )
            addStyle(style, from, to)
            if (run.handlerId != 0L) {
                // A link is a press on a range, and a press is the event the boundary
                // already has. No new event tag for a thing that is already a click.
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "dioxus-link-${run.start}",
                        linkInteractionListener = {
                            dispatcher.dispatch(HostEvent.Clicked(node.id, run.handlerId))
                        },
                    ),
                    from,
                    to,
                )
            }
        }
    }

    BasicText(
        text = annotated,
        modifier = modifier,
        style = node.textStyle(theme),
        maxLines = node.maxLines(),
        overflow = node.overflow(),
    )
}

/**
 * Says a set of runs is wrong, and goes on drawing the string without them.
 *
 * The requirement is explicit that this must not end the process: a miscounted run is a
 * Host's arithmetic being wrong about its own string, and the reader still wants to read
 * the paragraph.
 */
private fun reportRuns(problem: String) {
    onProtocolError(TableError(TableError.UNSUPPORTED_PROPERTY, "text runs: $problem"))
}
