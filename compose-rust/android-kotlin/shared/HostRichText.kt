package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.LinkAnnotation
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.SpanRecords
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.TableError

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
 *
 * How it is set is decided by [resolveText], the same function a measure request is
 * answered with, so the size the Host was told is the size that is drawn.
 */
@Composable
internal fun HostRichText(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    assets: AssetCache,
) {
    val input = node.textInput()
    val density = textDensity(input, LocalDensity.current)
    val resolved = resolveText(
        input,
        theme,
        assets,
        density,
        LocalFontFamilyResolver.current,
    ) { run ->
        // A link is a press on a range, and a press is the event the boundary already
        // has. No new event tag for a thing that is already a click.
        LinkAnnotation.Clickable(
            tag = "compose-link-${run.start}",
            linkInteractionListener = {
                dispatcher.dispatch(HostEvent.Clicked(node.id, run.handlerId))
            },
        )
    }
    val problem = node.textInputProblem() ?: resolved.problems.firstOrNull()
    if (problem != null) ReportRuns(node.id, problem, dispatcher)
    // Text in CSS pixels is laid out in a density whose font scale is one, so the system's
    // text size does not reach it. Everything else is laid out where it stands.
    CompositionLocalProvider(LocalDensity provides density) {
        BasicText(
            text = resolved.text,
            modifier = modifier,
            style = resolved.style,
            overflow = resolved.overflow,
            softWrap = resolved.softWrap,
            maxLines = resolved.maxLines,
            inlineContent = resolved.inlineContent,
        )
    }
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
                message = "text on node $nodeId: $problem",
            ),
        )
    }
}
