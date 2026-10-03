package dev.darkpyonix.composerust.foundation.code

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.DecorationKind
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.math.max
import kotlin.math.min

/**
 * Puts the drawn path behind [CodeEditorPath.Drawn]. Desktop only: its input goes through
 * the desktop's own text input session, which is a different type on every platform.
 * Installing it does not select it; [defaultCodeEditorPath] does.
 */
fun installDrawnCodeEditor() {
    drawnCodeEditorSurface = { model, look, callbacks, modifier -> DrawnSurface(model, look, callbacks, modifier) }
}

/** One line as it is drawn: the laid-out text and where each column of the source lands in it. */
private class LineView(
    val layout: TextLayoutResult,
    /** Where a caret before source column `c` goes. */
    val caretAt: IntArray,
    /** Where source character `c` itself starts, after anything shown in front of it. */
    val charAt: IntArray,
) {
    fun caretX(column: Int): Float =
        layout.getHorizontalPosition(caretAt[column.coerceIn(0, caretAt.size - 1)], true)

    fun charX(column: Int): Float =
        layout.getHorizontalPosition(charAt[column.coerceIn(0, charAt.size - 1)], true)

    /** The source column nearest to a point. */
    fun column(x: Float): Int {
        val shown = layout.getOffsetForPosition(Offset(x, layout.size.height / 2f))
        var low = 0
        var high = charAt.size - 1
        while (low < high) {
            val middle = (low + high) ushr 1
            if (charAt[middle] < shown) low = middle + 1 else high = middle
        }
        return low
    }
}

/**
 * The state of a drawn editor: the composition in progress, which lives only here, the
 * lines it has laid out, and where it is on screen for the input method's candidate window.
 */
private class DrawnState(
    val model: CodeEditorModel,
    var look: CodeEditorLook,
    var callbacks: CodeEditorCallbacks,
) : DrawnInputTarget {
    /** Text an input method is still composing at the caret. Never sent to the Host. */
    var composing by mutableStateOf("")

    /** Changes when the caret or the selection moves, for what draws them. */
    var moves by mutableIntStateOf(0)

    var measurer: TextMeasurer? = null
    var style: TextStyle = TextStyle.Default
    var rowPx = 1
    var textLeft = 0f
    var coordinates: LayoutCoordinates? = null
    var rows: RowMap = RowMap.of(model)
    var scroll: EditorScroll? = null
    private val views = HashMap<Int, LineView>()
    private var viewsRevision = -1

    fun view(line: Int): LineView {
        // The caret changes a line's layout only through what is composed at it.
        val revision = model.contentRevision * 31 + model.overlayRevision * 7 +
            (if (composing.isEmpty()) 0 else composing.hashCode() + model.selection.caret.hashCode())
        if (revision != viewsRevision || views.size > VIEW_CACHE) {
            views.clear()
            viewsRevision = revision
        }
        return views.getOrPut(line) { layoutLine(line) }
    }

    private fun layoutLine(line: Int): LineView {
        val source = model.document.line(line)
        val builder = AnnotatedString.Builder()
        val caretAt = IntArray(source.length + 1)
        val charAt = IntArray(source.length + 1)
        val caret = model.selection.caret
        val ghost = model.ghostText?.takeIf { it.start.line == line }
        var displayColumn = 0
        for (column in 0..source.length) {
            caretAt[column] = builder.length
            if (caret.line == line && caret.column == column && composing.isNotEmpty()) {
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                builder.append(composing)
                builder.pop()
            }
            if (ghost != null && ghost.start.column == column && ghost.text.isNotEmpty()) {
                val ink = look.textStyle.color
                builder.pushStyle(SpanStyle(color = ink.copy(alpha = ink.alpha * look.ghostTextAlpha)))
                builder.append(ghost.text.substringBefore('\n'))
                builder.pop()
            }
            charAt[column] = builder.length
            if (column == source.length) break
            val character = source[column]
            if (character == '\t') {
                val width = look.tabWidth - displayColumn % look.tabWidth
                repeat(width) { builder.append(' ') }
                displayColumn += width
            } else {
                builder.append(character)
                displayColumn += 1
            }
        }
        for (span in model.spansInLines(line, line + 1)) {
            val from = if (span.start.line < line) 0 else span.start.column.coerceAtMost(source.length)
            val to = if (span.end.line > line) source.length else span.end.column.coerceAtMost(source.length)
            if (to > from) builder.addStyle(SpanStyle(color = look.paint(span.paint)), charAt[from], caretAt[to])
        }
        val layout = measurer!!.measure(builder.toAnnotatedString(), style, softWrap = false)
        return LineView(layout, caretAt, charAt)
    }

    fun moved() {
        moves += 1
    }

    // The input session's half.

    override fun text(): String {
        val caret = model.selection.caret
        val line = model.document.line(caret.line)
        return line.substring(0, caret.column) + composing + line.substring(caret.column)
    }

    override fun caret(): Int = model.selection.caret.column + composing.length

    override fun composition(): TextRange? {
        if (composing.isEmpty()) return null
        val column = model.selection.caret.column
        return TextRange(column, column + composing.length)
    }

    override fun commit(text: String) {
        composing = ""
        if (text.isEmpty()) return
        replaceSelection(text, EditOrigin.Typing)
    }

    override fun compose(text: String) {
        if (!model.selection.collapsed) replaceSelection("", EditOrigin.Reader)
        composing = text
    }

    override fun finishComposing() {
        val text = composing
        composing = ""
        if (text.isNotEmpty()) replaceSelection(text, EditOrigin.Typing)
    }

    override fun deleteAround(before: Int, after: Int) {
        val caret = model.selection.caret
        val line = model.document.line(caret.line)
        val start = CodePosition(caret.line, max(0, caret.column - before))
        val end = CodePosition(caret.line, min(line.length, caret.column + after))
        if (start == end) return
        model.edit(start, end, "")
        model.selection = CodeSelection.at(start)
        moved()
        callbacks.onOutput()
    }

    override fun layout(): TextLayoutResult? = view(model.selection.caret.line).layout

    override fun caretRectInRoot(): Rect {
        val caret = model.selection.caret
        val x = textLeft + view(caret.line).caretX(caret.column)
        val y = rows.textRow(caret.line) * rowPx.toFloat() - (scroll?.offset ?: 0)
        val origin = coordinates?.localToRoot(Offset(x, y)) ?: Offset(x, y)
        return Rect(origin, Size(1f, rowPx.toFloat()))
    }

    override fun fieldRectInRoot(): Rect {
        val coordinates = coordinates ?: return Rect.Zero
        return Rect(coordinates.localToRoot(Offset.Zero), coordinates.size.let { Size(it.width.toFloat(), it.height.toFloat()) })
    }

    fun replaceSelection(text: String, origin: EditOrigin) {
        val selection = model.selection
        val change = model.edit(selection.start, selection.end, text, origin) ?: return
        model.selection = CodeSelection.at(change.newEnd)
        moved()
        callbacks.onOutput()
    }

    fun positionAt(point: Offset): Pair<CodePosition, Boolean> {
        val row = ((point.y + (scroll?.offset ?: 0)) / rowPx).toInt().coerceAtLeast(0)
        val line = rows.lineAtRow(row).coerceIn(0, model.document.lineCount - 1)
        val onLens = rows.hasLens(line) && row == rows.rowTop(line)
        return CodePosition(line, view(line).column(point.x - textLeft)) to onLens
    }

    companion object {
        const val VIEW_CACHE = 400
    }
}

@Composable
private fun DrawnSurface(
    model: CodeEditorModel,
    look: CodeEditorLook,
    callbacks: CodeEditorCallbacks,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val state = remember(model) { DrawnState(model, look, callbacks) }
    state.look = look
    state.callbacks = callbacks
    state.measurer = measurer
    val rowPx = remember(look.textStyle, density) { rowHeightPx(look.textStyle, density) }
    state.rowPx = rowPx
    state.style = look.textStyle
    val vertical = remember { EditorScroll() }
    state.scroll = vertical
    var viewportHeight by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val hover = remember { HoverTracker() }
    HoverReport(hover, look.hoverDelayMillis, callbacks)

    model.contentRevision
    model.overlayRevision
    val rows = RowMap.of(model)
    state.rows = rows
    val extent = rows.totalRows * rowPx - viewportHeight
    SideEffect { vertical.max = extent }
    val digitWidth = remember(look.textStyle) { measurer.measure("0", look.textStyle).size.width }
    val gutterWidth = with(density) {
        rows.lineCount.toString().length * digitWidth + (look.gutterPadding * 2).toPx() + look.gutterDividerWidth.toPx()
    }
    state.textLeft = gutterWidth + with(density) { look.textInset.toPx() }

    // What the reader's edits queued is sent from here as well, on the composition thread.
    SideEffect { callbacks.onOutput() }

    Box(
        modifier
            .boundedHeight(DEFAULT_EDITOR_HEIGHT)
            .background(look.container)
            .clipToBounds()
            .onSizeChanged { viewportHeight = it.height },
    ) {
        Box(
            Modifier
                // The viewport's size, never the document's: the lines on screen are drawn at
                // their rows less the offset, and the extent is only a number.
                .fillMaxSize()
                .scrollable(vertical.scrollable, Orientation.Vertical)
                .onGloballyPositioned { state.coordinates = it }
                .drawnTextInput(state)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { event -> drawnKey(event, state) }
                .pointerInput(state) {
                    awaitPointerEventScope {
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Press -> {
                                    focus.requestFocus()
                                    val (position, onLens) = state.positionAt(change.position)
                                    if (onLens) {
                                        val lens = model.lensesByLine().firstOrNull { it.first == position.line }?.second?.firstOrNull()
                                        if (lens != null) {
                                            model.activate(lens)
                                            callbacks.onOutput()
                                        }
                                    } else {
                                        state.composing = ""
                                        val anchor = if (event.keyboardModifiers.isShiftPressed) model.selection.anchor else position
                                        model.selection = CodeSelection(anchor, position)
                                        model.dismissGhostText()
                                        state.moved()
                                        dragging = true
                                    }
                                    change.consume()
                                }
                                PointerEventType.Move, PointerEventType.Enter -> {
                                    val (position, onLens) = state.positionAt(change.position)
                                    if (dragging && change.pressed) {
                                        model.selection = CodeSelection(model.selection.anchor, position)
                                        state.moved()
                                    } else {
                                        hover.moved(model, if (onLens) null else position)
                                    }
                                }
                                PointerEventType.Release -> dragging = false
                                PointerEventType.Exit -> hover.left()
                                else -> {}
                            }
                        }
                    }
                }
                .drawBehind {
                    state.moves
                    state.composing
                    val scroll = vertical.offset
                    translate(top = -scroll.toFloat()) {
                        drawLines(state, rows, scroll, viewportHeight, gutterWidth, focused)
                    }
                },
        )
    }
}

/** Every visible row: the current line, the selection, the text, the marks and the gutter. */
private fun DrawScope.drawLines(
    state: DrawnState,
    rows: RowMap,
    scroll: Int,
    viewportHeight: Int,
    gutterWidth: Float,
    focused: Boolean,
) {
    val model = state.model
    val look = state.look
    val rowPx = state.rowPx.toFloat()
    val first = rows.lineAtRow((scroll / rowPx).toInt())
    val last = min(rows.lineCount - 1, rows.lineAtRow(((scroll + viewportHeight) / rowPx).toInt() + 1))
    val selection = model.selection
    drawRect(look.gutter, Offset(0f, scroll.toFloat()), Size(gutterWidth, viewportHeight.toFloat()))
    if (look.gutterDivider.alpha > 0f) {
        val width = look.gutterDividerWidth.toPx()
        drawRect(look.gutterDivider, Offset(gutterWidth - width, scroll.toFloat()), Size(width, viewportHeight.toFloat()))
    }
    val lenses = model.lensesByLine().toMap()
    for (line in first..last) {
        val view = state.view(line)
        val top = rows.textRow(line) * rowPx
        if (line == selection.caret.line && selection.collapsed && look.currentLine.alpha > 0f) {
            drawRect(look.currentLine, Offset(gutterWidth, top), Size(size.width - gutterWidth, rowPx))
        }
        lenses[line]?.let { row ->
            val label = state.measurer!!.measure(row.joinToString("  |  ") { it.text }, state.style.copy(color = look.lens))
            drawText(label, topLeft = Offset(state.textLeft, top - rowPx))
        }
        if (!selection.collapsed && line >= selection.start.line && line <= selection.end.line) {
            val from = if (line == selection.start.line) view.charX(selection.start.column) else 0f
            val to = if (line == selection.end.line) view.caretX(selection.end.column) else view.layout.size.width.toFloat() + rowPx / 3
            drawRect(look.selection, Offset(state.textLeft + from, top), Size(max(0f, to - from), rowPx))
        }
        drawText(view.layout, topLeft = Offset(state.textLeft, top + (rowPx - view.layout.size.height) / 2))
        for (decoration in model.decorations) {
            if (decoration.kind != DecorationKind.Underline) continue
            if (line < decoration.start.line || line > decoration.end.line) continue
            val from = if (line == decoration.start.line) view.charX(decoration.start.column) else 0f
            var to = if (line == decoration.end.line) view.caretX(decoration.end.column) else view.layout.size.width.toFloat()
            if (to - from < rowPx / 2) to = from + rowPx / 2
            val (color, shape) = look.underline(decoration)
            val stroke = look.underlineWidth.toPx()
            drawUnderline(shape, color, state.textLeft + from, state.textLeft + to, top + rowPx - stroke * 1.5f, stroke)
        }
        if (focused && line == selection.caret.line) {
            // After whatever is being composed, which is drawn in front of the caret column.
            val shown = view.caretAt[selection.caret.column.coerceIn(0, view.caretAt.size - 1)] + state.composing.length
            val x = state.textLeft + view.layout.getHorizontalPosition(shown, true)
            drawRect(look.cursor, Offset(x, top), Size(1.5.dp.toPx(), rowPx))
        }
        val number = state.measurer!!.measure(
            (line + 1).toString(),
            state.style.copy(color = if (line == selection.caret.line) look.currentLineNumber else look.lineNumber),
        )
        val padding = look.gutterPadding.toPx() + look.gutterDividerWidth.toPx()
        drawText(number, topLeft = Offset(gutterWidth - padding - number.size.width, top + (rowPx - number.size.height) / 2))
    }
}

/** The keys the drawn editor answers. Typed characters arrive here the way they reach a text field on the desktop. */
private fun drawnKey(event: KeyEvent, state: DrawnState): Boolean {
    // While an input method composes, its keys are its own.
    if (state.composing.isNotEmpty()) return false
    val model = state.model
    val look = state.look
    val callbacks = state.callbacks
    val native = event.nativeKeyEvent as? java.awt.event.KeyEvent
    val command = if (look.commandIsMeta) event.isMetaPressed else event.isCtrlPressed
    if (native?.id == java.awt.event.KeyEvent.KEY_TYPED) {
        val character = native.keyChar
        if (command || Character.isISOControl(character) || character == java.awt.event.KeyEvent.CHAR_UNDEFINED) return false
        state.replaceSelection(character.toString(), EditOrigin.Typing)
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    val document = model.document
    val selection = model.selection
    val shift = event.isShiftPressed
    fun move(to: CodePosition) {
        model.selection = CodeSelection(if (shift) selection.anchor else to, to)
        model.dismissGhostText()
        state.moved()
    }
    val caret = selection.caret
    when {
        command && event.key == Key.S -> {
            callbacks.onSave()
        }
        command && event.key == Key.Z && !shift -> {
            model.undo()
            state.moved()
            callbacks.onOutput()
        }
        (command && event.key == Key.Z && shift) || (!look.commandIsMeta && event.isCtrlPressed && event.key == Key.Y) -> {
            model.redo()
            state.moved()
            callbacks.onOutput()
        }
        command && event.key == Key.A -> {
            model.selection = CodeSelection(CodePosition.Zero, document.end)
            state.moved()
        }
        command && (event.key == Key.C || event.key == Key.X) -> {
            if (selection.collapsed) return true
            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                StringSelection(document.textBetween(selection.start, selection.end)),
                null,
            )
            if (event.key == Key.X) state.replaceSelection("", EditOrigin.Reader)
        }
        command && event.key == Key.V -> {
            val pasted = runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
            }.getOrNull() ?: return true
            state.replaceSelection(pasted, EditOrigin.Reader)
        }
        event.key == Key.DirectionLeft -> move(
            when {
                !shift && !selection.collapsed -> selection.start
                caret.column > 0 -> {
                    val line = document.line(caret.line)
                    val step = if (caret.column >= 2 && line[caret.column - 1].isLowSurrogate()) 2 else 1
                    CodePosition(caret.line, caret.column - step)
                }
                caret.line > 0 -> CodePosition(caret.line - 1, document.line(caret.line - 1).length)
                else -> caret
            },
        )
        event.key == Key.DirectionRight -> move(
            when {
                !shift && !selection.collapsed -> selection.end
                caret.column < document.line(caret.line).length -> {
                    val line = document.line(caret.line)
                    val step = if (line[caret.column].isHighSurrogate() && caret.column + 1 < line.length) 2 else 1
                    CodePosition(caret.line, caret.column + step)
                }
                caret.line < document.lineCount - 1 -> CodePosition(caret.line + 1, 0)
                else -> caret
            },
        )
        event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
            event.key == Key.PageUp || event.key == Key.PageDown -> {
            val step = when (event.key) {
                Key.DirectionUp -> -1
                Key.DirectionDown -> 1
                Key.PageUp -> -PAGE_LINES
                else -> PAGE_LINES
            }
            val line = (caret.line + step).coerceIn(0, document.lineCount - 1)
            val x = state.view(caret.line).caretX(caret.column)
            var target = CodePosition(line, state.view(line).column(x))
            if (!document.isValid(target)) target = CodePosition(line, target.column - 1)
            move(target)
        }
        event.key == Key.MoveHome -> move(CodePosition(caret.line, 0))
        event.key == Key.MoveEnd -> move(CodePosition(caret.line, document.line(caret.line).length))
        event.key == Key.Backspace || event.key == Key.Delete -> {
            if (selection.collapsed) {
                val target = if (event.key == Key.Backspace) {
                    when {
                        caret.column > 0 -> {
                            val line = document.line(caret.line)
                            val step = if (caret.column >= 2 && line[caret.column - 1].isLowSurrogate()) 2 else 1
                            CodePosition(caret.line, caret.column - step)
                        }
                        caret.line > 0 -> CodePosition(caret.line - 1, document.line(caret.line - 1).length)
                        else -> return true
                    }
                } else {
                    when {
                        caret.column < document.line(caret.line).length -> {
                            val line = document.line(caret.line)
                            val step = if (line[caret.column].isHighSurrogate() && caret.column + 1 < line.length) 2 else 1
                            CodePosition(caret.line, caret.column + step)
                        }
                        caret.line < document.lineCount - 1 -> CodePosition(caret.line + 1, 0)
                        else -> return true
                    }
                }
                model.selection = CodeSelection(caret, target)
            }
            state.replaceSelection("", EditOrigin.Reader)
        }
        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
            val line = document.line(caret.line)
            val indent = line.takeWhile { it == ' ' || it == '\t' }.take(caret.column)
            state.replaceSelection("\n$indent", EditOrigin.Reader)
        }
        event.key == Key.Tab && !shift && !command -> {
            val ghost = model.ghostText
            if (ghost != null && selection.collapsed && caret == ghost.start) {
                model.acceptGhostText()
                state.moved()
                callbacks.onOutput()
            } else {
                val line = document.line(caret.line)
                val indent = if (line.takeWhile { it == ' ' || it == '\t' }.contains('\t')) {
                    "\t"
                } else {
                    " ".repeat(look.tabWidth - caret.column % look.tabWidth)
                }
                state.replaceSelection(indent, EditOrigin.Reader)
            }
        }
        event.key == Key.Escape -> {
            if (model.ghostText == null) return false
            model.dismissGhostText()
        }
        else -> return false
    }
    return true
}

private const val PAGE_LINES = 30
