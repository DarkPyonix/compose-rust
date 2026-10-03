package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.MeasureRecords
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measure requests written the way the Host writes them, for tests that stand in for it.
 *
 * The record layout is the generated one, so the offsets are the Host's. The Rust side has
 * its own encoder and its own tests; this one exists so the Renderer can be asked without a
 * Host, and it writes nothing the Host would not.
 */
internal class MeasureRequests {
    private sealed interface Request

    private class Text(
        val text: String,
        val role: Int,
        val fontSize: Float,
        val fontWeight: Int,
        val italic: Boolean,
        val wrap: Boolean,
        val letterSpacing: Float,
        val lineHeight: Float,
        val maxLines: Int,
        val tabSize: Int,
        val wordBreak: Int,
        val overflowWrap: Int,
        val absoluteSize: Boolean,
        val constraint: Int,
        val width: Float,
        val spans: ByteArray,
        val spanFonts: Map<Int, List<FontRef>>,
        val fonts: List<FontRef>,
    ) : Request

    private class Node(val nodeId: Int, val minWidth: Float, val maxWidth: Float, val minHeight: Float, val maxHeight: Float) : Request

    private val requests = mutableListOf<Request>()

    val count: Int get() = requests.size

    /** [role] is the wire tag: 0 for text with no role, 1 to 9 for the rungs. */
    fun text(
        text: String,
        role: Int = 5,
        constraint: Int = MeasureRecords.CONSTRAINT_AT_MOST,
        width: Float = 0f,
        fontSize: Float = 0f,
        fontWeight: Int = 0,
        italic: Boolean = false,
        wrap: Boolean = true,
        letterSpacing: Float = Float.NaN,
        lineHeight: Float = Float.NaN,
        maxLines: Int = 0,
        tabSize: Int = 8,
        wordBreak: Int = 0,
        overflowWrap: Int = 0,
        absoluteSize: Boolean = false,
        spans: ByteArray = ByteArray(0),
        spanFonts: Map<Int, List<FontRef>> = emptyMap(),
        fonts: List<FontRef> = emptyList(),
    ): MeasureRequests {
        requests += Text(
            text, role, fontSize, fontWeight, italic, wrap, letterSpacing, lineHeight, maxLines,
            tabSize, wordBreak, overflowWrap, absoluteSize, constraint, width, spans, spanFonts, fonts,
        )
        return this
    }

    fun node(
        nodeId: Int,
        minWidth: Float = 0f,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        minHeight: Float = 0f,
        maxHeight: Float = Float.POSITIVE_INFINITY,
    ): MeasureRequests {
        requests += Node(nodeId, minWidth, maxWidth, minHeight, maxHeight)
        return this
    }

    /** The records, then the payload they point at, every offset counted from the start. */
    fun encode(): ByteBuffer {
        val payload = Payload(requests.size * MeasureRecords.RECORD_LENGTH)
        val records = ByteBuffer.allocate(requests.size * MeasureRecords.RECORD_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)
        requests.forEachIndexed { index, request ->
            val at = index * MeasureRecords.RECORD_LENGTH
            when (request) {
                is Node -> {
                    records.putShort(at + MeasureRecords.KIND_AT, MeasureRecords.KIND_NODE.toShort())
                    records.putInt(at + MeasureRecords.NODE_ID_AT, request.nodeId)
                    records.putFloat(at + MeasureRecords.NODE_MIN_WIDTH_AT, request.minWidth)
                    records.putFloat(at + MeasureRecords.NODE_MAX_WIDTH_AT, request.maxWidth)
                    records.putFloat(at + MeasureRecords.NODE_MIN_HEIGHT_AT, request.minHeight)
                    records.putFloat(at + MeasureRecords.NODE_MAX_HEIGHT_AT, request.maxHeight)
                }
                is Text -> {
                    records.putShort(at + MeasureRecords.KIND_AT, MeasureRecords.KIND_TEXT.toShort())
                    records.putShort(at + MeasureRecords.TEXT_TYPE_ROLE_AT, request.role.toShort())
                    val utf8 = request.text.encodeToByteArray()
                    records.putInt(at + MeasureRecords.TEXT_TEXT_OFFSET_AT, payload.add(utf8))
                    records.putInt(at + MeasureRecords.TEXT_TEXT_LENGTH_AT, utf8.size)
                    records.putInt(at + MeasureRecords.TEXT_SPANS_OFFSET_AT, payload.add(request.spans))
                    records.putInt(at + MeasureRecords.TEXT_SPANS_COUNT_AT, request.spans.size / 36)
                    val entries = payload.reserve(request.spanFonts.size * 12)
                    var entry = entries
                    for ((span, refs) in request.spanFonts) {
                        val list = payload.fonts(refs)
                        payload.putInt(entry, span)
                        payload.putInt(entry + 4, list)
                        payload.putInt(entry + 8, refs.size)
                        entry += 12
                    }
                    records.putInt(at + MeasureRecords.TEXT_SPAN_FONTS_OFFSET_AT, entries)
                    records.putInt(at + MeasureRecords.TEXT_SPAN_FONTS_COUNT_AT, request.spanFonts.size)
                    records.putInt(at + MeasureRecords.TEXT_FONT_OFFSET_AT, payload.fonts(request.fonts))
                    records.putInt(at + MeasureRecords.TEXT_FONT_COUNT_AT, request.fonts.size)
                    records.putFloat(at + MeasureRecords.TEXT_FONT_SIZE_AT, request.fontSize)
                    records.putShort(at + MeasureRecords.TEXT_FONT_WEIGHT_AT, request.fontWeight.toShort())
                    records.put(at + MeasureRecords.TEXT_ITALIC_AT, (if (request.italic) 1 else 0).toByte())
                    records.put(at + MeasureRecords.TEXT_WRAP_AT, (if (request.wrap) 1 else 0).toByte())
                    records.putFloat(at + MeasureRecords.TEXT_LETTER_SPACING_AT, request.letterSpacing)
                    records.putFloat(at + MeasureRecords.TEXT_LINE_HEIGHT_AT, request.lineHeight)
                    records.putInt(at + MeasureRecords.TEXT_MAX_LINES_AT, request.maxLines)
                    records.put(at + MeasureRecords.TEXT_TAB_SIZE_AT, request.tabSize.toByte())
                    records.put(at + MeasureRecords.TEXT_WORD_BREAK_AT, request.wordBreak.toByte())
                    records.put(at + MeasureRecords.TEXT_OVERFLOW_WRAP_AT, request.overflowWrap.toByte())
                    records.put(at + MeasureRecords.TEXT_ABSOLUTE_SIZE_AT, (if (request.absoluteSize) 1 else 0).toByte())
                    records.putInt(at + MeasureRecords.TEXT_CONSTRAINT_AT, request.constraint)
                    records.putFloat(at + MeasureRecords.TEXT_WIDTH_AT, request.width)
                }
            }
        }
        val bytes = records.array() + payload.bytes()
        return ByteBuffer.wrap(bytes)
    }

    /** The payload area, growing as it is written, with positions counted from [base]. */
    private class Payload(private val base: Int) {
        private var bytes = ByteArray(256)
        private var length = 0

        fun bytes(): ByteArray = bytes.copyOf(length)

        fun reserve(size: Int): Int {
            ensure(size)
            val at = base + length
            length += size
            return at
        }

        fun add(data: ByteArray): Int {
            val at = reserve(data.size)
            data.copyInto(bytes, at - base)
            return at
        }

        fun putInt(at: Int, value: Int) {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at - base, value)
        }

        /** Font records followed by their names; answers where the records start. */
        fun fonts(refs: List<FontRef>): Int {
            val records = reserve(refs.size * 12)
            refs.forEachIndexed { index, ref ->
                val record = records + index * 12
                when (ref) {
                    is FontRef.Asset -> {
                        putInt(record, 1)
                        putInt(record + 4, ref.assetId)
                    }
                    is FontRef.System -> {
                        val name = ref.name.encodeToByteArray()
                        putInt(record, 2)
                        putInt(record + 4, add(name))
                        putInt(record + 8, name.size)
                    }
                    is FontRef.Generic -> {
                        putInt(record, 3)
                        putInt(record + 4, ref.family.ordinal + 1)
                    }
                }
            }
            return records
        }

        private fun ensure(more: Int) {
            if (length + more <= bytes.size) return
            bytes = bytes.copyOf(maxOf(bytes.size * 2, length + more))
        }
    }
}

/** One answer, read back out of a result buffer. */
internal data class MeasuredResult(
    val width: Float,
    val height: Float,
    val firstBaseline: Float,
    val lastBaseline: Float,
    val lastLineWidth: Float,
    val lineCount: Int,
    val flags: Int,
    val status: Int,
) {
    val truncated: Boolean get() = flags and MeasureRecords.FLAG_TRUNCATED != 0
}

/** Room for [count] results. */
internal fun resultBuffer(count: Int): ByteBuffer =
    ByteBuffer.allocate(count * MeasureRecords.RESULT_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

internal fun ByteBuffer.results(count: Int): List<MeasuredResult> {
    val buffer = duplicate().order(ByteOrder.LITTLE_ENDIAN)
    return List(count) { index ->
        val at = index * MeasureRecords.RESULT_LENGTH
        MeasuredResult(
            width = buffer.getFloat(at + MeasureRecords.RESULT_WIDTH_AT),
            height = buffer.getFloat(at + MeasureRecords.RESULT_HEIGHT_AT),
            firstBaseline = buffer.getFloat(at + MeasureRecords.RESULT_FIRST_BASELINE_AT),
            lastBaseline = buffer.getFloat(at + MeasureRecords.RESULT_LAST_BASELINE_AT),
            lastLineWidth = buffer.getFloat(at + MeasureRecords.RESULT_LAST_LINE_WIDTH_AT),
            lineCount = buffer.getInt(at + MeasureRecords.RESULT_LINE_COUNT_AT),
            flags = buffer.getInt(at + MeasureRecords.RESULT_FLAGS_AT),
            status = buffer.getInt(at + MeasureRecords.RESULT_STATUS_AT),
        )
    }
}

/** The blob a `Font` property carries: a count, the records, then the names. */
internal fun fontBlob(refs: List<FontRef>): ByteArray {
    val names = mutableListOf<ByteArray>()
    val recordsEnd = 4 + refs.size * 12
    val out = ByteBuffer.allocate(recordsEnd + refs.sumOf { (it as? FontRef.System)?.name?.encodeToByteArray()?.size ?: 0 })
        .order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(refs.size)
    var nameAt = recordsEnd
    for (ref in refs) {
        when (ref) {
            is FontRef.Asset -> out.putInt(1).putInt(ref.assetId).putInt(0)
            is FontRef.System -> {
                val name = ref.name.encodeToByteArray()
                out.putInt(2).putInt(nameAt).putInt(name.size)
                names += name
                nameAt += name.size
            }
            is FontRef.Generic -> out.putInt(3).putInt(ref.family.ordinal + 1).putInt(0)
        }
    }
    names.forEach { out.put(it) }
    return out.array()
}

/** One 36 byte run record, written the way the Host writes it. */
internal fun spanRecord(start: Int, length: Int, role: Int = 0, flags: Int = 0): ByteArray =
    ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(start)
        .putInt(length)
        .putShort(role.toShort())
        .putShort(flags.toShort())
        .putLong(0)
        .putLong(0)
        .putLong(0)
        .array()
