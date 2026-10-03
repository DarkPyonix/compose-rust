package dioxus.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.foundation.code.CodeEditorCallbacks
import dioxus.compose.foundation.code.CodeEditorLook
import dioxus.compose.foundation.code.CodeEditorModel
import dioxus.compose.foundation.code.CodeEditorSurface
import dioxus.compose.foundation.code.EditorOutput
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.HoverPhase
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.TypeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.textStyle

/**
 * A code editor bound to its node.
 *
 * The document, the selection, the undo history and the decorations live in the node's
 * [CodeEditorModel], which the batches the Host sends feed and the surface edits. This only
 * resolves how the editor looks from the design system and carries what the model and the
 * surface have to say to the Host: each committed change, an edit refused, a lens pressed or
 * a suggestion accepted, a pointer at rest, and the save shortcut.
 */
@Composable
internal fun HostCodeEditor(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val model = node.code ?: return
    val tabWidth = (node.property(PropertyKind.TabWidth) as? PropertyValue.Integer)?.value
        ?.toInt()
        ?.takeIf { it > 0 }
    val look = remember(theme, tabWidth) { codeEditorLook(node, theme, tabWidth) }
    val currentDispatcher by rememberUpdatedState(dispatcher)
    val callbacks = remember(node, model) {
        CodeEditorCallbacks(
            onOutput = { node.sendCodeOutput(model.drainOutput(), currentDispatcher) },
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
        )
    }
    CodeEditorSurface(model, look, callbacks, modifier)
}

/** What the design system says an editor looks like here, resolved to plain values. */
internal fun codeEditorLook(node: Node, theme: ResolvedTheme, tabWidth: Int?): CodeEditorLook {
    val style = theme.rules.codeEditor(theme)
    return CodeEditorLook(
        textStyle = node.textStyle(theme, TypeRole.Mono).copy(color = style.text),
        container = style.container,
        gutter = style.gutter,
        lineNumber = style.lineNumber,
        currentLineNumber = style.currentLineNumber,
        currentLine = style.currentLine,
        currentLineBorder = style.currentLineBorder,
        gutterDivider = style.gutterDivider,
        gutterDividerWidth = style.gutterDividerWidth,
        gutterPadding = style.gutterPadding,
        textInset = style.textInset,
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
