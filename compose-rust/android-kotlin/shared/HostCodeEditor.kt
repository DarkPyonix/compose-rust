package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.foundation.code.CodeEditorCallbacks
import dev.darkpyonix.composerust.foundation.code.CodeEditorLook
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodeEditorSurface
import dev.darkpyonix.composerust.foundation.code.EditorOutput
import dev.darkpyonix.composerust.foundation.code.EditorScroll
import dev.darkpyonix.composerust.protocol.CodeEditorColorsRecord
import dev.darkpyonix.composerust.protocol.CodeEditorRecords
import dev.darkpyonix.composerust.protocol.FontRefRecords
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.HoverPhase
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.FontChain
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.textStyle

/**
 * A code editor bound to its node.
 *
 * The document, the selection, the undo history and the decorations live in the node's
 * [CodeEditorModel], which the batches the Host sends feed and the surface edits. This only
 * resolves how the editor looks from the design system and carries what the model and the
 * surface have to say to the Host: each committed change, an edit refused, a lens or a gutter
 * mark pressed or a suggestion accepted, a pointer at rest, the save shortcut, and what the
 * Host listens to about the editor itself: the selection, the lines on screen, focus, folds
 * and whether an input method is composing.
 */
@Composable
internal fun HostCodeEditor(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    table: NodeTable,
    assets: AssetCache? = null,
) {
    val model = node.code ?: return
    val tabWidth = (node.property(PropertyKind.TabWidth) as? PropertyValue.Integer)?.value
        ?.toInt()
        ?.takeIf { it > 0 }
    // The application's own font list, where it gave one: the editor is set in its first
    // family and a letter that family lacks comes from the next one that has it.
    val fontBlob = node.bytes(PropertyKind.Font)
    val fontChain = remember(fontBlob, assets?.size) {
        if (fontBlob == null || assets == null) {
            null
        } else {
            FontRefRecords.decodeBlob(fontBlob)?.let { resolveFontChain(it, assets, mutableListOf()) }
        }
    }
    // Every property the look is made of is read here, so that changing one of them makes
    // a new look and leaving them alone keeps the one there is.
    val look = remember(
        theme,
        tabWidth,
        fontChain,
        node.property(PropertyKind.TypeRole),
        node.property(PropertyKind.FontSize),
        node.property(PropertyKind.LineHeight),
        node.property(PropertyKind.LineNumbers),
        node.property(PropertyKind.GutterMinWidthDp),
        node.property(PropertyKind.GutterPaddingDp),
        node.property(PropertyKind.TextInsetDp),
        node.bytes(PropertyKind.CodeEditorColors),
    ) { codeEditorLook(node, theme, tabWidth, fontChain) }
    val currentDispatcher by rememberUpdatedState(dispatcher)
    val keyPreview = remember(node) { codeEditorKeyPreview(node, { currentDispatcher }) }
    val callbacks = remember(node, model) {
        CodeEditorCallbacks(
            onOutput = { node.sendCodeOutput(model.drainOutput(includeReports = true), currentDispatcher) },
            onSave = {
                val handler = node.handler(PropertyKind.OnSave)
                if (handler != null) {
                    currentDispatcher.dispatch(
                        HostEvent.CodeSaveRequested(node.id, handler, model.document.version),
                    )
                }
            },
            onHover = { decoration, position, rest ->
                val handler = node.handler(PropertyKind.OnHover)
                if (handler != null) {
                    currentDispatcher.dispatch(
                        HostEvent.CodeHovered(
                            node.id,
                            handler,
                            decoration,
                            position.line,
                            position.column,
                            if (rest) HoverPhase.Rest else HoverPhase.Leave,
                        ),
                    )
                }
            },
            onKeyPreview = keyPreview,
            // The Host's pointer events over the editor go through the same router as every
            // other node's, so a press the editor does not consume goes on to the nearest
            // subscribed node around it.
            codePointer = { target ->
                if (node.subscribesToCodePointer()) {
                    codePointerEvents(node, table.pointers, currentDispatcher, target)
                } else {
                    Modifier
                }
            },
        )
    }
    // A listener attached after the editor was drawn has the row height and the offset
    // waiting for it in the model. Nothing else may redraw the editor soon, so they are
    // sent now.
    val hearsMetrics = node.handler(PropertyKind.OnCodeMetrics) != null
    val hearsScroll = node.handler(PropertyKind.OnCodeScrolled) != null
    val hearsCaret = node.handler(PropertyKind.OnCaretRect) != null
    LaunchedEffect(model, hearsMetrics, hearsScroll, hearsCaret) {
        if (hearsMetrics || hearsScroll || hearsCaret) callbacks.onOutput()
    }
    val focusRequest = node.focusRequestRevision
    CodeEditorSurface(model, look, callbacks, modifier, focusRequest = focusRequest) { scrolling ->
        // The editor scrolls down its document by an offset it keeps itself and along its
        // lines by an ordinary scroll state. Both take the Host's scroll requests and
        // both are in the report.
        HostScroll(
            node,
            table,
            remember(scrolling) {
                OffsetScrollSurface(
                    x = scrolling.horizontal?.axis(),
                    y = scrolling.vertical.axis(scrolling.viewportHeight),
                    mainVertical = true,
                )
            },
        )
    }
}

/** The editor's own offset down its document, as an axis a scroll request can move. */
private fun EditorScroll.axis(viewport: () -> Int): ScrollAxis = ScrollAxis(
    state = scrollable,
    value = { offset },
    max = { max },
    viewport = viewport,
    measured = { viewport() > 0 },
    jump = { target -> scrollTo(target) },
)

/**
 * What an editor offers the Host before it handles a key: the `KeyDown` record a key handler
 * hears, sent to the node's `OnKeyPreview` handler, whose answer says whether the Host took
 * the key. Nothing is sent while an input method composes, for a release, or where nothing
 * listens. Every event passes through [held], so a repeat is marked as one.
 */
internal fun codeEditorKeyPreview(
    node: Node,
    dispatcher: () -> EventDispatcher,
    held: HeldKeys = HeldKeys(),
): (KeyEvent, Boolean) -> Boolean = { event, composing ->
    val handler = node.handler(PropertyKind.OnKeyPreview)
    if (handler == null) {
        held.observe(event.type, event.key)
        false
    } else {
        dispatchKeyDown(node.id, handler, event, composing, dispatcher(), held)
    }
}

/** The colours the application gave this editor, or null where it gave none that can be read. */
internal fun Node.codeEditorColors(): CodeEditorColorsRecord? =
    bytes(PropertyKind.CodeEditorColors)?.let { bytes ->
        // What cannot be read was reported when the property arrived; here it is only left out.
        CodeEditorRecords.decodeCodeEditorColors(bytes) {}.firstOrNull()
    }

/**
 * A length in dp the application set on the node, or null where it set none. Zero is a
 * length like any other here: a text inset of nothing is a thing to ask for. A length
 * below zero or one that is not a number is left to the design system.
 */
private fun Node.lengthOrNull(kind: PropertyKind): Dp? = when (val value = property(kind)) {
    is PropertyValue.Float -> value.value
    is PropertyValue.Integer -> value.value.toFloat()
    else -> null
}?.takeIf { it >= 0f && it.isFinite() }?.dp

/**
 * What an editor looks like here, resolved to plain values: the design system's editor,
 * with the type, the gutter and the colours the application set on the node over it.
 */
internal fun codeEditorLook(
    node: Node,
    theme: ResolvedTheme,
    tabWidth: Int?,
    fontChain: FontChain? = null,
): CodeEditorLook {
    val style = theme.rules.codeEditor(theme)
    val colors = node.codeEditorColors()
    // The role, the size and the row height are the node's where it has them, and the
    // monospace rung of the type ladder where it does not.
    val type = node.textStyle(theme, TypeRole.Mono)
    return CodeEditorLook(
        textStyle = type.copy(color = style.text, fontFamily = fontChain?.primary ?: type.fontFamily),
        container = colors?.container?.let { theme.color(it) } ?: style.container,
        gutter = colors?.gutter?.let { theme.color(it) } ?: style.gutter,
        lineNumber = colors?.lineNumber?.let { theme.color(it) } ?: style.lineNumber,
        currentLineNumber = colors?.currentLineNumber?.let { theme.color(it) } ?: style.currentLineNumber,
        currentLine = colors?.currentLine?.let { theme.color(it) } ?: style.currentLine,
        currentLineBorder = style.currentLineBorder,
        gutterDivider = style.gutterDivider,
        gutterDividerWidth = style.gutterDividerWidth,
        gutterPadding = node.lengthOrNull(PropertyKind.GutterPaddingDp) ?: style.gutterPadding,
        textInset = node.lengthOrNull(PropertyKind.TextInsetDp) ?: style.textInset,
        selection = style.selection,
        cursor = style.cursor,
        underlineWidth = style.underlineWidth,
        underline = { decoration ->
            style.underlineColor(decoration.severity, decoration.color, theme) to
                style.underlineShape(decoration.severity)
        },
        paint = { paint -> theme.color(paint) },
        ghostTextAlpha = style.ghostTextAlpha,
        lens = style.lens,
        tabWidth = tabWidth ?: style.tabWidth,
        hoverDelayMillis = style.hoverDelayMillis,
        commandIsMeta = theme.platform == HostPlatform.MacOs || theme.platform == HostPlatform.Ios,
        // A mark takes the role the Host named, and otherwise the lens's quiet ink.
        gutterMark = { role -> if (role == null) style.lens else theme.color(role) },
        foldMarker = style.lineNumber,
        // The same for every design system: the width and gap of a change-marker bar.
        changeBarWidth = 4.dp,
        changeBarGap = 12.dp,
        lineNumbers = node.flag(PropertyKind.LineNumbers, default = true),
        gutterMinWidth = node.lengthOrNull(PropertyKind.GutterMinWidthDp) ?: 0.dp,
        fontFallback = fontChain?.let { chain -> { text: String -> chain.spans(text) } },
    )
}

/**
 * Turns what an editor's model queued into events for the Host, in the order it was queued.
 *
 * An event goes only where the application listens: a change with no `on_change` handler
 * has nobody to tell. A record the Host sent that could not be honoured is reported through
 * [onError] instead, so it becomes a protocol error.
 */
internal fun Node.codeEvents(outputs: List<EditorOutput>, onError: (String) -> Unit): List<HostEvent> {
    if (outputs.isEmpty()) return emptyList()
    val events = ArrayList<HostEvent>(outputs.size)
    for (output in outputs) {
        when (output) {
            is EditorOutput.Changed -> {
                val handler = handler(PropertyKind.OnValueChange) ?: continue
                val change = output.change
                events += HostEvent.CodeChanged(
                    id,
                    handler,
                    change.version,
                    change.start.line,
                    change.start.column,
                    change.end.line,
                    change.end.column,
                    change.text,
                )
            }
            is EditorOutput.Rejected -> {
                val handler = handler(PropertyKind.OnEditRejected) ?: continue
                events += HostEvent.CodeEditRejected(
                    id,
                    handler,
                    output.requestId,
                    output.baseVersion,
                    output.currentVersion,
                    output.start.line,
                    output.start.column,
                    output.end.line,
                    output.end.column,
                )
            }
            is EditorOutput.Activated -> {
                val handler = handler(PropertyKind.OnDecorationClick) ?: continue
                events += HostEvent.DecorationActivated(id, handler, output.id)
            }
            is EditorOutput.SelectionMoved -> {
                val handler = handler(PropertyKind.OnSelectionChange) ?: continue
                val selection = output.selection
                events += HostEvent.SelectionChanged(
                    id,
                    handler,
                    output.version,
                    selection.anchor.line,
                    selection.anchor.column,
                    selection.caret.line,
                    selection.caret.column,
                )
            }
            is EditorOutput.VisibleRange -> {
                val handler = handler(PropertyKind.OnVisibleRangeChange) ?: continue
                events += HostEvent.VisibleRangeChanged(id, handler, output.version, output.first, output.last)
            }
            is EditorOutput.Focus -> {
                val handler = handler(PropertyKind.OnEditorFocusChange) ?: continue
                events += HostEvent.EditorFocusChanged(id, handler, output.focused)
            }
            is EditorOutput.FoldToggled -> {
                val handler = handler(PropertyKind.OnFoldChange) ?: continue
                events += HostEvent.FoldChanged(id, handler, output.startLine, output.collapsed)
            }
            // Pixels cross as a whole number in a double, the unit the scroll command takes.
            is EditorOutput.LineHeight -> {
                val handler = handler(PropertyKind.OnCodeMetrics) ?: continue
                events += HostEvent.CodeMetrics(id, handler, output.px.toDouble())
            }
            is EditorOutput.Scrolled -> {
                val handler = handler(PropertyKind.OnCodeScrolled) ?: continue
                events += HostEvent.CodeScrolled(id, handler, output.offsetPx.toDouble())
            }
            // In the window's dp, the scroll and the zoom already in them.
            is EditorOutput.Caret -> {
                val handler = handler(PropertyKind.OnCaretRect) ?: continue
                val (position, rect) = output.report
                events += HostEvent.CaretRect(
                    id,
                    handler,
                    position.line,
                    position.column,
                    rect.x,
                    rect.y,
                    rect.width,
                    rect.height,
                )
            }
            // An answer goes to the query and names no handler: the Host asked, so it is
            // waiting for it whether or not anything on the editor listens.
            is EditorOutput.PositionAnswer -> {
                val rect = output.rect
                events += HostEvent.PositionRect(
                    id,
                    0L,
                    output.request,
                    rect != null,
                    rect?.x ?: 0f,
                    rect?.y ?: 0f,
                    rect?.width ?: 0f,
                    rect?.height ?: 0f,
                )
            }
            is EditorOutput.Composing -> {
                val handler = handler(PropertyKind.OnCompositionChange) ?: continue
                events += HostEvent.CompositionChanged(id, handler, output.composing)
            }
            is EditorOutput.Error -> onError(output.message)
        }
    }
    return events
}

/** Sends what the surface made the model queue, straight away, on the UI thread. */
private fun Node.sendCodeOutput(outputs: List<EditorOutput>, dispatcher: EventDispatcher) {
    val events = codeEvents(outputs) { message ->
        dispatcher.dispatch(
            HostEvent.ProtocolError(
                nodeId = 0,
                handlerId = 0,
                code = CODE_EDITOR_ERROR,
                message = "code editor $id: $message",
            ),
        )
    }
    for (event in events) dispatcher.dispatch(event)
}

/** The protocol error code a code editor reports a record it could not honour with. */
internal const val CODE_EDITOR_ERROR = 9
