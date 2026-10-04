package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.design.FieldStyle
import dev.darkpyonix.composerust.design.forHeight
import dev.darkpyonix.composerust.design.FieldUnderline
import dev.darkpyonix.composerust.design.LocalDesignTheme
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.textStyle
import dev.darkpyonix.composerust.ui.node.HostText
import dev.darkpyonix.composerust.design.LocalGlassDepth

/**
 * The uncontrolled TextField.
 *
 * The editing value and the IME composition live in `remember` and never round-trip through
 * the Host. The Host learns about edits through coalesced `TextChanged` and through the
 * commit events `TextSubmitted` and `FocusLost`; it changes the value only with `SetText`,
 * which is held back while a composition is in progress.
 *
 * A Host `on_key_down` handler is offered the key first and its result decides consumption;
 * `TextSubmitted` remains for a field that only declares `on_submit`.
 *
 * Key events are never dispatched while composing: Enter during a Korean composition means
 * "commit the composition", not "submit".
 */
@Composable
internal fun HostTextField(node: Node, modifier: Modifier, dispatcher: EventDispatcher) {
    val nodeId = node.id
    var value by remember(nodeId) {
        mutableStateOf(TextFieldValue(node.text(PropertyKind.Text)))
    }
    var appliedHostRevision by remember(nodeId) { mutableStateOf(0L) }
    var focused by remember(nodeId) { mutableStateOf(false) }

    val multiline = node.flag(PropertyKind.Multiline, default = false)
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val changeHandler = node.handler(PropertyKind.OnValueChange)
    val submitHandler = node.handler(PropertyKind.OnSubmit)
    val keyDownHandler = node.handler(PropertyKind.OnKeyDown)
    val focusLostHandler = node.handler(PropertyKind.OnFocusLost)
    val placeholder = node.text(PropertyKind.Placeholder)
    val hostText = node.hostText
    val composing = value.composition != null
    // The field takes its type role and its colours from the design system, the same way a
    // Text does.
    val theme = LocalDesignTheme.current
    val textStyle = node.textStyle(theme)

    // A Host SetText is applied only once the composition has finished: replacing the text
    // mid-composition would destroy the syllable being assembled.
    LaunchedEffect(hostText, composing) {
        if (hostText != null && hostText.revision != appliedHostRevision && !composing) {
            value = TextFieldValue(hostText.text, selection = hostText.selection(hostText.text))
            appliedHostRevision = hostText.revision
        }
    }

    // Change notification, at most one a frame.
    //
    // An edit is told to the Host on the next frame, carrying the latest text, so keys that
    // arrive within one frame cost it one event and a key is never held back by a timer: the
    // line that echoes what was typed follows the key by a frame. The last text is always
    // sent, because the frame that follows an edit sends whatever the field holds then, and
    // a later edit starts another. The Host is told what the field now shows; it never sends
    // the value back, so the composition cannot be reset by the round trip.
    if (changeHandler != null) {
        LaunchedEffect(nodeId, changeHandler) {
            var lastSent = value.text
            var latest = lastSent
            var sending: Job? = null
            coroutineScope {
                snapshotFlow { value.text }.collect { text ->
                    latest = text
                    if (text == lastSent || sending?.isActive == true) return@collect
                    sending = launch {
                        do {
                            withFrameNanos { }
                            lastSent = latest
                            dispatcher.dispatch(HostEvent.TextChanged(nodeId, changeHandler, lastSent))
                        } while (latest != lastSent)
                    }
                }
            }
        }
    }

    val inputModifier = modifier
        .onFocusChanged { state ->
            if (focused && !state.isFocused && focusLostHandler != null) {
                dispatcher.dispatch(HostEvent.FocusLost(nodeId, focusLostHandler))
            }
            focused = state.isFocused
        }
        // Preview is required because this intercepts Enter before the editor inserts a
        // newline; the Host's synchronous result decides whether it is consumed.
        //
        // `value.composition` is read here rather than from the `composing` snapshot above so
        // the state is the one that exists at the moment the key arrives: that read is the
        // guard that keeps Enter away from Rust during a Korean composition.
        .onPreviewKeyEvent { event ->
            val composingNow = value.composition != null
            val consumedByHandler = keyDownHandler != null &&
                dispatchKeyDown(nodeId, keyDownHandler, event, composingNow, dispatcher)
            if (consumedByHandler) {
                true
            } else if (!isDispatchableKeyDown(event, composingNow)) {
                false
            } else if (!shouldSubmitOnEnter(
                    composing = composingNow,
                    multiline = multiline,
                    shiftPressed = event.isShiftPressed,
                    hasSubmitHandler = submitHandler != null,
                )
            ) {
                false
            } else {
                dispatcher.dispatch(HostEvent.TextSubmitted(nodeId, submitHandler!!, value.text))
            }
        }

    // The frame is the design system's, not the Host's: a fill, a line, room inside, and
    // whatever each of them changes when the caret arrives. Focus is read here rather than
    // reported, so none of that transition crosses the boundary.
    val frame = theme.rules.field(theme).forHeight(multiline, theme).let { style ->
        // On glass the field may take the glass as its fill, where the system says so.
        val onGlass = style.containerOnGlass
        if (onGlass != null && LocalGlassDepth.current > 0) {
            // And no line either, in neither state. A field whose fill is the surface it
            // sits in is that surface, so a ring drawn round it when the caret arrives is
            // a second field drawn inside the first: in the composer it came up as a blue
            // capsule inside the glass capsule, round the words and not round the bar.
            // What says the caret is there is the caret.
            style.copy(
                container = onGlass,
                containerFocused = onGlass,
                border = Color.Transparent,
                borderFocused = Color.Transparent,
                borderWidth = 0.dp,
                borderWidthFocused = 0.dp,
            )
        } else {
            style
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = inputModifier,
        enabled = enabled,
        singleLine = !multiline,
        textStyle = textStyle,
        cursorBrush = SolidColor(frame.cursor),
        decorationBox = { inner ->
            Box(
                Modifier
                    // The whole of the room it was given, not the width of what is typed
                    // in it. A field that wraps its own text leaves the rest of the space
                    // it was laid out in belonging to nothing: the composer was a bar you
                    // could only put the caret in by hitting the words.
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = frame.minHeight)
                    .clip(frame.shape)
                    .background(if (focused) frame.containerFocused else frame.container)
                    .fieldBorder(frame, focused)
                    .fieldUnderline(frame.underline, focused)
                    .padding(
                        horizontal = frame.horizontalPadding,
                        vertical = frame.verticalPadding,
                    ),
                contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                    BasicText(
                        placeholder,
                        style = textStyle.copy(
                            color = theme.color(ColorRole.OnSurfaceVariant)
                                .copy(alpha = PLACEHOLDER_ALPHA),
                        ),
                    )
                }
                inner()
            }
        },
    )
}

/**
 * How much of the strength of the secondary ink a placeholder is set in.
 *
 * Quieter than secondary ink, because a placeholder is not text. At the full strength the
 * word in an empty composer read as something already typed, and the field beside it that
 * held a real label was set in exactly the same colour, so nothing said which of the two
 * was going to be replaced the moment you started typing.
 */
private const val PLACEHOLDER_ALPHA = 0.55f

/** The box around a field, or nothing where the system draws none in this state. */
private fun Modifier.fieldBorder(style: FieldStyle, focused: Boolean): Modifier {
    val width = if (focused) style.borderWidthFocused else style.borderWidth
    if (width.value <= 0f) return this
    return border(width, if (focused) style.borderFocused else style.border, style.shape)
}

/**
 * The line along the bottom edge, drawn over the fill rather than behind it.
 *
 * It is a fill rather than a border because it is only one edge, and because the systems
 * that draw it want it square across the full width even where the corners above are
 * rounded.
 */
private fun Modifier.fieldUnderline(underline: FieldUnderline?, focused: Boolean): Modifier {
    if (underline == null) return this
    val width = if (focused) underline.focusedWidth else underline.width
    if (width.value <= 0f) return this
    val color = if (focused) underline.focusedColor else underline.color
    return drawBehind {
        val thickness = width.toPx()
        drawRect(
            color = color,
            topLeft = Offset(0f, size.height - thickness),
            size = Size(size.width, thickness),
        )
    }
}

private fun HostText.selection(text: String): TextRange {
    // u32::MAX in both fields means "no selection given"; the caret goes to the end.
    if (selectionStart == -1 && selectionEnd == -1) return TextRange(text.length)
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(0, text.length)
    return TextRange(start, end)
}

/**
 * Whether an Enter key press becomes a `TextSubmitted` event.
 *
 * While an IME composition is in progress Enter means "commit the composition", so nothing
 * is dispatched and the key is left to the editor. In a multiline field Shift+Enter is a
 * newline, never a submit.
 */
internal fun shouldSubmitOnEnter(
    composing: Boolean,
    multiline: Boolean,
    shiftPressed: Boolean,
    hasSubmitHandler: Boolean,
): Boolean = when {
    composing -> false
    !hasSubmitHandler -> false
    multiline && shiftPressed -> false
    else -> true
}
