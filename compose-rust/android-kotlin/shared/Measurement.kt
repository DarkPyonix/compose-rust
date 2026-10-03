package dev.darkpyonix.composerust.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.layout.SubcomposeSlotReusePolicy
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.currentThreadToken
import dev.darkpyonix.composerust.foundation.ResolvedText
import dev.darkpyonix.composerust.foundation.TextInput
import dev.darkpyonix.composerust.foundation.TextRun
import dev.darkpyonix.composerust.foundation.resolveText
import dev.darkpyonix.composerust.protocol.FontRefRecords
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.OverflowWrap
import dev.darkpyonix.composerust.protocol.SpanFontRecords
import dev.darkpyonix.composerust.protocol.SpanRecords
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WordBreak
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.TableError
import java.lang.InterruptedException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Renderer's half of a measure call: how big a run of text, or a node the Host already
 * sent, will be.
 *
 * The Host asks in the middle of laying its own page out, from inside a call this side made
 * into it, so the question arrives on this thread with this side standing still on the
 * stack below. It is answered there and then, with the density, the fonts and the design
 * system the composition is drawing with, and no queue or thread is involved.
 *
 * Every platform's entry point comes here: the C entry of the desktop library, the
 * Kotlin/Native export, the JNI upcall and the wasm export. Each hands over the Host's two
 * buffers as views of the Host's own memory, and nothing is copied.
 */
object RendererMeasure {
    private var context: MeasureContext? = null
    private var uiThread: Any? = null

    /** What the composition last drew with. Called from the composition, on its thread. */
    internal fun install(context: MeasureContext) {
        this.context = context
        uiThread = currentThreadToken()
    }

    /** Forgets the composition of [table], if it is the one installed. */
    internal fun uninstall(table: NodeTable) {
        if (context?.table === table) context = null
    }

    /**
     * Reads [count] request records from [requests] and writes [count] result records into
     * [results]. Answers zero, or a negative call status for a call that measured nothing:
     * a buffer too short for its records, a thread that is not the composition's, or no
     * composition yet to measure with.
     *
     * A request that is wrong on its own says so in its own result, and the rest are
     * measured. Nothing here throws into the caller, which is native code.
     */
    fun measure(requests: ByteBuffer, count: Int, results: ByteBuffer): Int {
        val thread = uiThread ?: return MeasureRecords.CALL_UNAVAILABLE
        if (currentThreadToken() != thread) return MeasureRecords.CALL_OFF_UI_THREAD
        val context = context ?: return MeasureRecords.CALL_UNAVAILABLE
        if (count < 0 || count > Int.MAX_VALUE / MeasureRecords.RECORD_LENGTH) {
            return MeasureRecords.CALL_UNREADABLE
        }
        val limit = requests.limit()
        if (count.toLong() * MeasureRecords.RECORD_LENGTH > limit.toLong()) {
            return MeasureRecords.CALL_UNREADABLE
        }
        if (count.toLong() * MeasureRecords.RESULT_LENGTH > results.limit().toLong()) {
            return MeasureRecords.CALL_UNREADABLE
        }
        val requestOrder = requests.order()
        val resultOrder = results.order()
        requests.order(ByteOrder.LITTLE_ENDIAN)
        results.order(ByteOrder.LITTLE_ENDIAN)
        try {
            for (index in 0 until count) {
                val measured = try {
                    context.measure(requests, limit, index * MeasureRecords.RECORD_LENGTH)
                } catch (error: Throwable) {
                    if (error is InterruptedException) throw error
                    context.table.report(
                        TableError.UNSUPPORTED_PROPERTY,
                        "measure request $index could not be measured: ${error.message}",
                    )
                    Measured.MALFORMED
                }
                measured.writeTo(results, index * MeasureRecords.RESULT_LENGTH)
            }
        } catch (error: Throwable) {
            if (error is InterruptedException) throw error
            return MeasureRecords.CALL_UNREADABLE
        } finally {
            requests.order(requestOrder)
            results.order(resultOrder)
        }
        return MeasureRecords.CALL_OK
    }
}

/** One answer, in dp, before it is written into the Host's result record. */
internal class Measured(
    val width: Float,
    val height: Float,
    val firstBaseline: Float,
    val lastBaseline: Float,
    val lastLineWidth: Float,
    val lineCount: Int,
    val flags: Int,
    val status: Int,
) {
    fun writeTo(results: ByteBuffer, at: Int) {
        results.position(at)
        results.putFloat(width)
        results.putFloat(height)
        results.putFloat(firstBaseline)
        results.putFloat(lastBaseline)
        results.putFloat(lastLineWidth)
        results.putInt(lineCount)
        results.putInt(flags)
        results.putInt(status)
    }

    companion object {
        private fun failed(status: Int) =
            Measured(0f, 0f, Float.NaN, Float.NaN, Float.NaN, 0, 0, status)

        val MALFORMED = failed(MeasureRecords.STATUS_MALFORMED)
        val UNKNOWN_NODE = failed(MeasureRecords.STATUS_UNKNOWN_NODE)
    }
}

/**
 * What a measure call is answered with: the composition's own density, fonts, layout
 * direction and design system, and its node table. Made afresh by every composition of the
 * content, so it is never older than what is on screen.
 */
internal class MeasureContext(
    val table: NodeTable,
    val theme: ResolvedTheme,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val fontFamilyResolver: FontFamily.Resolver,
    val station: MeasuringStation,
) {
    /**
     * The same measurer `BasicText` lays out with, in effect: `TextMeasurer` takes the
     * constraints, the wrapping and the overflow the way the text node does. No cache, so
     * an answer never depends on what was asked before it.
     */
    private val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 0)

    fun measure(requests: ByteBuffer, limit: Int, at: Int): Measured =
        when (requests.getShort(at + MeasureRecords.KIND_AT).toInt() and 0xffff) {
            MeasureRecords.KIND_TEXT ->
                zoomed(requests, at + MeasureRecords.TEXT_ZOOM_AT)
                    ?.let { measureText(requests, limit, at, it) } ?: Measured.MALFORMED
            MeasureRecords.KIND_NODE ->
                zoomed(requests, at + MeasureRecords.NODE_ZOOM_AT)
                    ?.let { measureNode(requests, at, it) } ?: Measured.MALFORMED
            else -> Measured.MALFORMED
        }

    /**
     * The density a request is measured at: the composition's, times the zoom of the page
     * region it belongs to, with no font scale. The operating system's text size reaches
     * that region through its zoom rather than through a font scale, so it is not applied
     * twice. A zoom of zero is one; a negative or non-finite one is a malformed request.
     */
    private fun zoomed(requests: ByteBuffer, at: Int): Density? {
        val zoom = Float.fromBits(requests.getInt(at))
        if (zoom.isNaN() || zoom.isInfinite() || zoom < 0f) return null
        return Density(density.density * (if (zoom == 0f) 1f else zoom), 1f)
    }

    private fun measureText(requests: ByteBuffer, limit: Int, at: Int, density: Density): Measured {
        val input = textInput(requests, limit, at) ?: return Measured.MALFORMED
        val constraint = requests.getInt(at + MeasureRecords.TEXT_CONSTRAINT_AT)
        val width = Float.fromBits(requests.getInt(at + MeasureRecords.TEXT_WIDTH_AT))
        val resolved = resolveText(input, theme, table.assets, density, fontFamilyResolver)
        resolved.problems.forEach { table.report(TableError.UNKNOWN_ASSET, it) }
        return when (constraint) {
            MeasureRecords.CONSTRAINT_MIN_CONTENT -> intrinsic(resolved, minimum = true, density)
            MeasureRecords.CONSTRAINT_MAX_CONTENT -> intrinsic(resolved, minimum = false, density)
            MeasureRecords.CONSTRAINT_AT_MOST -> {
                if (width.isNaN() || width < 0f) return Measured.MALFORMED
                val maxWidth = if (width.isInfinite()) {
                    Constraints.Infinity
                } else {
                    (width * density.density).roundToInt()
                }
                described(layout(resolved, maxWidth, density), width = null, density)
            }
            else -> Measured.MALFORMED
        }
    }

    /**
     * The narrowest or the widest the text can be, which is what Compose's paragraph
     * intrinsics answer, laid out at that width for everything else the answer carries.
     *
     * Text that does not wrap is as narrow as it is wide: there is nowhere for it to break.
     */
    private fun intrinsic(resolved: ResolvedText, minimum: Boolean, density: Density): Measured {
        val pixels = if (minimum && resolved.softWrap) {
            MultiParagraphIntrinsics(
                resolved.minContentText ?: resolved.text,
                resolved.style,
                if (resolved.minContentText != null) resolved.minContentPlaceholders else resolved.placeholders,
                density,
                fontFamilyResolver,
            ).minIntrinsicWidth
        } else {
            MultiParagraphIntrinsics(
                resolved.text,
                resolved.style,
                resolved.placeholders,
                density,
                fontFamilyResolver,
            ).maxIntrinsicWidth
        }
        return described(layout(resolved, ceil(pixels).toInt(), density), width = pixels / density.density, density)
    }

    private fun layout(resolved: ResolvedText, maxWidth: Int, density: Density): TextLayoutResult =
        measurer.measure(
            text = resolved.text,
            style = resolved.style,
            overflow = resolved.overflow,
            softWrap = resolved.softWrap,
            maxLines = resolved.maxLines,
            placeholders = resolved.placeholders,
            constraints = Constraints.fitPrioritizingWidth(0, maxWidth, 0, Constraints.Infinity),
            layoutDirection = layoutDirection,
            density = density,
            fontFamilyResolver = fontFamilyResolver,
        )

    /** A laid out text as a result record. [width] replaces the laid out width where given. */
    private fun described(result: TextLayoutResult, width: Float?, density: Density): Measured {
        val scale = density.density
        val lines = result.lineCount
        val last = lines - 1
        val lastLineWidth = if (lines > 0) {
            (result.getLineRight(last) - result.getLineLeft(last)) / scale
        } else {
            0f
        }
        return Measured(
            width = width ?: (result.size.width / scale),
            height = result.size.height / scale,
            firstBaseline = result.firstBaseline / scale,
            lastBaseline = result.lastBaseline / scale,
            lastLineWidth = lastLineWidth,
            lineCount = lines,
            flags = if (result.multiParagraph.didExceedMaxLines) MeasureRecords.FLAG_TRUNCATED else 0,
            status = MeasureRecords.STATUS_OK,
        )
    }

    private fun measureNode(requests: ByteBuffer, at: Int, density: Density): Measured {
        val nodeId = requests.getInt(at + MeasureRecords.NODE_ID_AT)
        // A node the table does not have: never sent, removed, or sent in the batch the
        // Host is computing now, which is only applied once this call has returned.
        if (table.node(nodeId) == null) return Measured.UNKNOWN_NODE
        fun dimension(offset: Int): Float = Float.fromBits(requests.getInt(at + offset))
        val constraints = constraintsOf(
            dimension(MeasureRecords.NODE_MIN_WIDTH_AT),
            dimension(MeasureRecords.NODE_MAX_WIDTH_AT),
            dimension(MeasureRecords.NODE_MIN_HEIGHT_AT),
            dimension(MeasureRecords.NODE_MAX_HEIGHT_AT),
            density,
        ) ?: return Measured.MALFORMED
        val size = station.measure(nodeId, constraints, density) ?: return Measured.UNKNOWN_NODE
        return Measured(
            width = size.width / density.density,
            height = size.height / density.density,
            firstBaseline = Float.NaN,
            lastBaseline = Float.NaN,
            lastLineWidth = Float.NaN,
            lineCount = 0,
            flags = 0,
            status = MeasureRecords.STATUS_OK,
        )
    }

    /**
     * Constraints in pixels from bounds in dp, rounded the way a size modifier rounds them,
     * or null where they make no sense: negative, not a number, or a minimum past its
     * maximum. An infinite maximum is no limit.
     */
    private fun constraintsOf(
        minWidth: Float,
        maxWidth: Float,
        minHeight: Float,
        maxHeight: Float,
        density: Density,
    ): Constraints? {
        fun pixels(value: Float): Int? = when {
            value.isNaN() || value < 0f -> null
            value.isInfinite() -> Constraints.Infinity
            else -> (value * density.density).roundToInt()
        }
        val minW = pixels(minWidth)?.takeIf { it != Constraints.Infinity } ?: return null
        val maxW = pixels(maxWidth) ?: return null
        val minH = pixels(minHeight)?.takeIf { it != Constraints.Infinity } ?: return null
        val maxH = pixels(maxHeight) ?: return null
        if (minW > maxW || minH > maxH) return null
        return Constraints.fitPrioritizingWidth(minW, maxW, minH, maxH)
    }

    /** A text request as the same input a `Text` node becomes, or null where it is malformed. */
    private fun textInput(requests: ByteBuffer, limit: Int, at: Int): TextInput? {
        fun int(offset: Int) = requests.getInt(at + offset)
        fun byte(offset: Int) = requests.get(at + offset).toInt() and 0xff
        fun real(offset: Int) = Float.fromBits(int(offset))

        val textAt = int(MeasureRecords.TEXT_TEXT_OFFSET_AT)
        val textLength = int(MeasureRecords.TEXT_TEXT_LENGTH_AT)
        if (textAt < 0 || textLength < 0 || textAt.toLong() + textLength > limit.toLong()) return null
        val utf8 = ByteArray(textLength)
        val mark = requests.position()
        requests.position(textAt)
        requests.get(utf8, 0, textLength)
        requests.position(mark)
        val text = utf8.decodeToString()

        val runs = SpanRecords.decodeAt(
            requests,
            limit,
            int(MeasureRecords.TEXT_SPANS_OFFSET_AT),
            int(MeasureRecords.TEXT_SPANS_COUNT_AT),
        )?.map { record ->
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
        } ?: return null
        val runFonts = SpanFontRecords.decode(
            requests,
            limit,
            int(MeasureRecords.TEXT_SPAN_FONTS_OFFSET_AT),
            int(MeasureRecords.TEXT_SPAN_FONTS_COUNT_AT),
        ) ?: return null
        val fonts = FontRefRecords.decode(
            requests,
            limit,
            int(MeasureRecords.TEXT_FONT_OFFSET_AT),
            int(MeasureRecords.TEXT_FONT_COUNT_AT),
        ) ?: return null

        val roleTag = requests.getShort(at + MeasureRecords.TEXT_TYPE_ROLE_AT).toInt() and 0xffff
        val role = if (roleTag == TYPE_ROLE_NONE) null else TypeRole.entries.getOrNull(roleTag - 1) ?: return null
        val fontSize = real(MeasureRecords.TEXT_FONT_SIZE_AT)
        val fontWeight = requests.getShort(at + MeasureRecords.TEXT_FONT_WEIGHT_AT).toInt() and 0xffff
        val letterSpacing = real(MeasureRecords.TEXT_LETTER_SPACING_AT)
        val lineHeight = real(MeasureRecords.TEXT_LINE_HEIGHT_AT)
        val maxLines = int(MeasureRecords.TEXT_MAX_LINES_AT)
        val tabSize = byte(MeasureRecords.TEXT_TAB_SIZE_AT)
        val wordBreak = byte(MeasureRecords.TEXT_WORD_BREAK_AT)
        val overflowWrap = byte(MeasureRecords.TEXT_OVERFLOW_WRAP_AT)
        return TextInput(
            text = text,
            runs = runs,
            runFonts = runFonts,
            noRole = roleTag == TYPE_ROLE_NONE,
            role = role,
            fontSize = fontSize.takeIf { it > 0f },
            fontWeight = fontWeight.takeIf { it != 0 },
            italic = byte(MeasureRecords.TEXT_ITALIC_AT) != 0,
            letterSpacing = letterSpacing.takeUnless { it.isNaN() },
            lineHeight = lineHeight.takeUnless { it.isNaN() },
            maxLines = if (maxLines <= 0) Int.MAX_VALUE else maxLines,
            softWrap = byte(MeasureRecords.TEXT_WRAP_AT) != 0,
            tabSize = if (tabSize == 0) TextInput.DEFAULT_TAB_SIZE else tabSize,
            wordBreak = if (wordBreak == 0) null else WordBreak.entries.getOrNull(wordBreak - 1) ?: return null,
            overflowWrap = if (overflowWrap == 0) null else OverflowWrap.entries.getOrNull(overflowWrap - 1) ?: return null,
            absoluteSize = byte(MeasureRecords.TEXT_ABSOLUTE_SIZE_AT) != 0,
            fonts = fonts,
        )
    }
}

/**
 * Where a node the Host already sent is measured: a second, unplaced composition of the
 * same node under the same composition locals, measured with the Host's constraints by
 * Compose's own layout and thrown away.
 *
 * The size is the one Compose layout gives the node, because it is Compose layout that
 * gives it: `SubcomposeLayoutState.precompose` composes the node into a slot of a layout
 * that sits in the tree beside the content, and `premeasure` measures that slot as a
 * parent would. Both are stable API. The copy is given an event dispatcher that drops
 * everything, so nothing a widget reports while it is being measured reaches the Host,
 * which is in the middle of a call of its own.
 */
internal class MeasuringStation(
    /** Draws a node, the way the content does. */
    private val content: @Composable (Int) -> Unit,
) {
    internal val state = SubcomposeLayoutState(SubcomposeSlotReusePolicy(0))

    private var calls = 0

    /**
     * The node's size under [constraints], in pixels, laid out at [density], or null where
     * it cannot be measured.
     */
    fun measure(nodeId: Int, constraints: Constraints, density: Density): IntSize? {
        val handle = state.precompose(Slot(nodeId, calls++)) {
            CompositionLocalProvider(LocalDensity provides density) { content(nodeId) }
        }
        try {
            if (handle.placeablesCount == 0) return IntSize.Zero
            var width = 0
            var height = 0
            for (index in 0 until handle.placeablesCount) {
                handle.premeasure(index, constraints)
                val size = handle.getSize(index)
                width = maxOf(width, size.width)
                height = maxOf(height, size.height)
            }
            return IntSize(width, height)
        } finally {
            handle.dispose()
        }
    }

    /** A slot of its own for every measurement, so a disposed one is never reused. */
    private data class Slot(val nodeId: Int, val call: Int)
}

/** The layout a [MeasuringStation] composes into. It takes no room and draws nothing. */
@Composable
internal fun MeasuringStationLayout(station: MeasuringStation) {
    SubcomposeLayout(station.state, Modifier) { _ -> layout(0, 0) {} }
}

/** An event dispatcher for the measured copy: it consumes nothing and tells nobody. */
internal val NoEvents = EventDispatcher { false }

/** The node [nodeId] drawn for measuring, in [table], with no events going anywhere. */
@Composable
internal fun MeasuredNode(nodeId: Int, table: NodeTable) {
    RenderNode(nodeId, table, NoEvents)
}
