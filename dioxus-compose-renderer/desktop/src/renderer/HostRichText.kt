package dioxus.compose.foundation

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SpanRecords
import dioxus.compose.protocol.TypeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.TableError
import dioxus.compose.ui.maxLines
import dioxus.compose.ui.overflow
import dioxus.compose.ui.textStyle

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
    /**
     * What is painted behind this run's letters, or null for nothing. A wrapped run is
     * painted on each line it reaches, and nowhere between its letters and the next run's.
     */
    val background: Paint? = null,
)

/**
 * Reads the blob the Host sent. A blob that is not a whole number of records is refused.
 *
 * The record layout is the generated one, so the byte offsets are the Host's and nobody
 * counts them by hand on this side.
 */
internal fun decodeRuns(bytes: ByteArray): List<TextRun>? =
    SpanRecords.decode(bytes)?.map { record ->
        TextRun(
            start = record.start,
            length = record.length,
            role = record.typeRole,
            color = record.color,
            bold = record.bold,
            italic = record.italic,
            underline = record.underline,
            strikethrough = record.strikethrough,
            handlerId = record.handlerId,
            background = record.background,
        )
    }

/**
 * The colour painted behind a run, or Unspecified for none.
 *
 * A brush has no flat colour to give a run of letters, so it stands for the colour it
 * starts from, the way it does wherever only a colour can be taken.
 */
internal fun runBackground(run: TextRun, theme: ResolvedTheme): androidx.compose.ui.graphics.Color =
    run.background?.let(theme::color) ?: androidx.compose.ui.graphics.Color.Unspecified

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
            ReportRuns(node.id, "the run list is not a whole number of records", dispatcher)
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
        ReportRuns(node.id, problem, dispatcher)
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
                // Behind this run's letters only, line by line where it wraps. The line's
                // own background is the node's, and this is the part of it that changed.
                background = runBackground(run, theme),
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
 * The report goes to the Host as a `ProtocolError`, like every other protocol error, so the
 * application that miscounted can find out; it never ends the process, because the reader
 * still wants to read the paragraph. Once per problem, after composition and on the thread
 * composition runs on, which is the thread the Host was started on.
 */
@Composable
private fun ReportRuns(nodeId: Int, problem: String, dispatcher: EventDispatcher) {
    val said = remember(nodeId) { arrayOfNulls<String>(1) }
    SideEffect {
        if (said[0] == problem) return@SideEffect
        said[0] = problem
        dispatcher.dispatch(
            HostEvent.ProtocolError(
                nodeId = nodeId,
                handlerId = 0,
                code = TableError.UNSUPPORTED_PROPERTY,
                message = "text runs on node $nodeId: $problem; the string is drawn without them",
            ),
        )
    }
}
