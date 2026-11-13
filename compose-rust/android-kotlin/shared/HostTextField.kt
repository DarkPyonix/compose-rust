package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import dev.darkpyonix.composerust.ui.node.FontChain
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.FontRefRecords
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
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
import dev.darkpyonix.composerust.ui.floatProp
import dev.darkpyonix.composerust.ui.textStyle
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.ui.cornerCurve
import dev.darkpyonix.composerust.ui.withCornerCurve
import dev.darkpyonix.composerust.ui.node.HostText
import dev.darkpyonix.composerust.design.LocalGlassDepth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

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
internal fun HostTextField(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    assets: AssetCache? = null,
) {
    val nodeId = node.id
    var value by remember(nodeId) {
        mutableStateOf(TextFieldValue(node.text(PropertyKind.Text)))
    }
    var appliedHostRevision by remember(nodeId) { mutableStateOf(0L) }
    var focused by remember(nodeId) { mutableStateOf(false) }
    var reportedComposing by remember(nodeId) { mutableStateOf(false) }
    val focusRequester = remember(nodeId) { FocusRequester() }
    val overlayFocusStack = LocalOverlayFocusStack.current

    val multiline = node.flag(PropertyKind.Multiline, default = false)
    // A bare field draws none of the design system's frame. It sits inside a box that
    // already drew one, and a second frame inside the first reads as a field in a field.
    val bare = node.flag(PropertyKind.Bare, default = false)
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val changeHandler = node.handler(PropertyKind.OnValueChange)
    val submitHandler = node.handler(PropertyKind.OnSubmit)
    val keyDownHandler = node.handler(PropertyKind.OnKeyDown)
    val keyUpHandler = node.handler(PropertyKind.OnKeyUp)
    val focusGainedHandler = node.handler(PropertyKind.OnFocusGained)
    val focusLostHandler = node.handler(PropertyKind.OnFocusLost)
    val compositionHandler = node.handler(PropertyKind.OnCompositionChange)
    val placeholder = node.text(PropertyKind.Placeholder)
    val hostText = node.hostText
    val focusRequest = node.focusRequestRevision
    val composing = value.composition != null
    // The field takes its type role and its colours from the design system, the same way a
    // Text does.
    val theme = LocalDesignTheme.current
    // A field with no role names its own fonts, as a `Text` with no role does; a field with
    // a role is set in the theme's font for it, which may be a stack. Either way the letters
    // the first family lacks are set in the next family of the chain that has them.
    val fontBlob = node.bytes(PropertyKind.Font)
    val ownChain = remember(fontBlob, assets?.size) {
        val noRole = (node.property(PropertyKind.TypeRole) as? PropertyValue.Integer)?.value ==
            TYPE_ROLE_NONE.toLong()
        if (!noRole || fontBlob == null || assets == null) {
            null
        } else {
            FontRefRecords.decodeBlob(fontBlob)?.let { resolveFontChain(it, assets, mutableListOf()) }
        }
    }
    // A font list on a field with a role is thrown away, and the Host is told so: on
    // screen it would only be letters in a face nobody asked for.
    if (fontBlob != null &&
        (node.property(PropertyKind.TypeRole) as? PropertyValue.Integer)?.value != TYPE_ROLE_NONE.toLong()
    ) {
        ReportRuns(
            node.id,
            "text field fonts on node ${node.id}: a font list sent for a field with a type role is ignored: " +
                "a field with a role is set in the theme's font for it. Send the type role None for a field set in its own fonts",
            dispatcher,
        )
    }
    val roleStyle = node.textStyle(theme)
    // A field with no role that names its letter spacing is tracked by its face as a `Text`
    // with no role is, so a value typed into it is as wide as the same words drawn beside
    // it. One that names none is spaced by the design system's token, which on the Apple
    // design systems already carries SF's tracking, so it is left as it is.
    val ownSpacing = node.floatProp(PropertyKind.LetterSpacing) != null
    val textStyle = if (ownChain != null) {
        roleStyle.copy(
            fontFamily = ownChain.primary,
            letterSpacing = trackedLetterSpacing(roleStyle, ownChain.primary).takeIf { ownSpacing }
                ?: roleStyle.letterSpacing,
        )
    } else {
        roleStyle
    }
    val chain = ownChain ?: theme.chainFor(textStyle.fontFamily)
    val fallbackFonts = remember(chain) {
        chain?.let { FontChainTransformation(it) } ?: VisualTransformation.None
    }

    // A Host SetText is applied only once the composition has finished: replacing the text
    // mid-composition would destroy the syllable being assembled.
    LaunchedEffect(hostText, composing) {
        if (hostText != null && hostText.revision != appliedHostRevision && !composing) {
            value = TextFieldValue(hostText.text, selection = hostText.selection(hostText.text))
            appliedHostRevision = hostText.revision
        }
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0L) focusRequester.requestFocus()
    }

    val applyValueChange: (TextFieldValue) -> Unit = { next ->
        val nowComposing = next.composition != null
        value = next
        if (nowComposing != reportedComposing) {
            reportedComposing = nowComposing
            if (compositionHandler != null) {
                dispatcher.dispatch(
                    HostEvent.CompositionChanged(nodeId, compositionHandler, nowComposing),
                )
            }
        }
    }
    hostTextFieldValueHook?.invoke(applyValueChange)

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
                            // Sent from inside the frame callback, not after it. Resuming
                            // from withFrameNanos goes back through the scene's dispatcher,
                            // which on the desktop windows runs what is waiting at the start
                            // of the next frame, so the send and the recomposition that
                            // follows it each cost a frame. In the callback the Host's batch
                            // is applied before this frame is composed and drawn.
                            withFrameNanos {
                                lastSent = latest
                                dispatcher.dispatch(HostEvent.TextChanged(nodeId, changeHandler, lastSent))
                            }
                        } while (latest != lastSent)
                    }
                }
            }
        }
    }

    val heldKeys = remember { HeldKeys() }
    val inputModifier = modifier
        .focusRequester(focusRequester)
        .onFocusChanged { state ->
            overlayFocusStack.noteFocused(focusRequester, state.isFocused)
            if (!focused && state.isFocused && focusGainedHandler != null) {
                dispatcher.dispatch(HostEvent.FocusGained(nodeId, focusGainedHandler))
            }
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
            if (composingNow && event.type == KeyEventType.KeyDown &&
                (event.key == Key.Escape || event.key == Key.Back)
            ) {
                applyValueChange(value.copy(composition = null))
                true
            } else {
                // The key-down handler sees every event first, key-ups included, so the held
                // keys it tracks can tell a repeat from a first press.
                val consumedByHandler = keyDownHandler != null &&
                    dispatchKeyDown(nodeId, keyDownHandler, event, composingNow, dispatcher, heldKeys)
                if (consumedByHandler) {
                    true
                } else if (keyUpHandler != null && isDispatchableKeyUp(event, composingNow)) {
                    dispatchKeyUp(nodeId, keyUpHandler, event, composingNow, dispatcher)
                } else if (!isEnterKeyDown(event, composingNow)) {
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

    val fillsHeight = node.modifiers.any {
        it is dev.darkpyonix.composerust.protocol.Modifier.FillMaxHeight ||
            it is dev.darkpyonix.composerust.protocol.Modifier.Height ||
            it is dev.darkpyonix.composerust.protocol.Modifier.Size
    }
    val setsOwnHeight = node.modifiers.any {
        it is dev.darkpyonix.composerust.protocol.Modifier.FillMaxHeight ||
            it is dev.darkpyonix.composerust.protocol.Modifier.Height ||
            it is dev.darkpyonix.composerust.protocol.Modifier.Size ||
            it is dev.darkpyonix.composerust.protocol.Modifier.RequiredSize
    }

    // What the node named for itself replaces what the design system would have drawn
    // The system's frame is the default for a field that said nothing, and a field
    // that named a fill, a line, a corner or room inside is drawn as it asked: the same rule
    // a Card already follows.
    val ownFill = node.setsOwnBackground()
    val ownLine = node.modifiers.any {
        it is dev.darkpyonix.composerust.protocol.Modifier.Border ||
            it is dev.darkpyonix.composerust.protocol.Modifier.BorderEach
    }
    val ownShape = node.modifiers.any {
        it is dev.darkpyonix.composerust.protocol.Modifier.Shape ||
            it is dev.darkpyonix.composerust.protocol.Modifier.ShapeRole ||
            it is dev.darkpyonix.composerust.protocol.Modifier.CornerEach
    }
    val frameShape = if (ownShape) node.modifiers.resolvedShape(theme) else frame.shape.withCornerCurve(node.modifiers.cornerCurve())

    BasicTextField(
        value = value,
        onValueChange = applyValueChange,
        modifier = inputModifier,
        enabled = enabled,
        singleLine = !multiline,
        textStyle = textStyle,
        visualTransformation = fallbackFonts,
        cursorBrush = SolidColor(frame.cursor),
        decorationBox = { inner ->
            Box(
                Modifier
                    // The whole of the room it was given, not the width of what is typed
                    // in it. A field that wraps its own text leaves the rest of the space
                    // it was laid out in belonging to nothing: the composer was a bar you
                    // could only put the caret in by hitting the words.
                    .fillMaxWidth()
                    .let { if (fillsHeight) it.fillMaxHeight() else it }
                    .let {
                        if (setsOwnHeight || bare) it else it.defaultMinSize(minHeight = frame.minHeight)
                    }
                    // A bare field clips only to a corner the node named itself.
                    .let { if (bare && !ownShape) it else it.clip(frameShape) }
                    .let {
                        if (ownFill || bare) {
                            it
                        } else {
                            it.background(if (focused) frame.containerFocused else frame.container)
                        }
                    }
                    .let { if (ownLine || bare) it else it.fieldBorder(frame.copy(shape = frameShape), focused) }
                    .let { if (ownLine || ownFill || bare) it else it.fieldUnderline(frame.underline, focused) }
                    .padding(
                        if (bare) {
                            node.contentPadding(theme, 0.dp, 0.dp)
                        } else {
                            node.contentPadding(theme, frame.horizontalPadding, frame.verticalPadding)
                        },
                    ),
                // A bare field is as tall as its text, so the placeholder and the text share
                // the first line's baseline from the top edge rather than being centred in
                // a minimum height the field no longer has.
                contentAlignment = if (multiline || bare) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                    ThemedText(
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

/**
 * Test hook that receives the value-change callback of the mounted [HostTextField] so a test
 * can drive an IME composition transition directly through `TextFieldValue.composition`.
 */
internal var hostTextFieldValueHook: (((TextFieldValue) -> Unit) -> Unit)? = null

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

/**
 * Sets each letter of a field's text the first family of [chain] lacks in the next family
 * that has it, as a `Text` in the same font would be set.
 *
 * Only styles, never characters: the offsets map one to one, so the caret, the selection
 * and an input method's composition land where they would without it.
 */
private class FontChainTransformation(private val chain: FontChain) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = chain.spans(text.text)
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            for (span in spans) addStyle(span.item, span.start, span.end)
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is FontChainTransformation && other.chain === chain

    override fun hashCode(): Int = chain.hashCode()
}

internal fun Node.contentPadding(
    theme: ResolvedTheme,
    defaultHorizontal: Dp,
    defaultVertical: Dp,
): PaddingValues {
    val padEach = modifiers.filterIsInstance<ProtocolModifier.PaddingEach>().lastOrNull()
    if (padEach != null) {
        return PaddingValues(padEach.start.dp, padEach.top.dp, padEach.end.dp, padEach.bottom.dp)
    }
    val pad = modifiers.filterIsInstance<ProtocolModifier.Padding>().lastOrNull()
    if (pad != null) return PaddingValues(pad.value.dp)
    val padRole = modifiers.filterIsInstance<ProtocolModifier.PaddingRole>().lastOrNull()
    if (padRole != null) return PaddingValues(theme.space(padRole.role))
    return PaddingValues(horizontal = defaultHorizontal, vertical = defaultVertical)
}
