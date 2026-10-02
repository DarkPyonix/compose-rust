package dioxus.compose.foundation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Key as ProtocolKey
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import dioxus.compose.runtime.EventDispatcher

/**
 * Key routing to the Host.
 *
 * The Host handler runs synchronously on this call stack and its result decides whether the
 * key is consumed, the same model as `PointerInputChange.consume()`.
 *
 * **A key event is never sent while an IME composition is in progress.** Enter during a
 * Korean composition means "commit the composition", not "submit": dispatching it would let
 * a Host handler clear the field and the syllable being composed would vanish.
 */

/** The protocol key for a Compose key, or null when the schema cannot name it. */
internal fun protocolKey(key: Key): ProtocolKey? = when (key) {
    Key.Enter, Key.NumPadEnter -> ProtocolKey.Enter
    // The schema's `Key` enum has only `Enter`, so no other key can cross the boundary.
    // Every additional key needs a schema variant, which changes the schema hash and so
    // requires both sides to be rebuilt together.
    else -> null
}

/**
 * Whether a key press may be offered to the Host at all.
 *
 * Key-down only (a Host handler is `on_key_down`), a key the schema can name, and **never
 * while an IME composition is in progress**. Kept free of Compose types so the rule that
 * protects a Korean composition can be asserted directly, the way [shouldSubmitOnEnter] is.
 */
internal fun shouldDispatchKeyDown(
    composing: Boolean,
    isKeyDown: Boolean,
    key: ProtocolKey?,
): Boolean = !composing && isKeyDown && key != null

/** [shouldDispatchKeyDown] applied to a Compose key event. */
internal fun isDispatchableKeyDown(event: ComposeKeyEvent, composing: Boolean): Boolean =
    shouldDispatchKeyDown(
        composing = composing,
        isKeyDown = event.type == KeyEventType.KeyDown,
        key = protocolKey(event.key),
    )

/**
 * Sends one key press to the Host and returns its consumption result.
 *
 * Returns false without calling the Host whenever [isDispatchableKeyDown] says no, so a
 * composition keystroke never reaches Rust.
 */
internal fun dispatchKeyDown(
    nodeId: Int,
    handlerId: Long,
    event: ComposeKeyEvent,
    composing: Boolean,
    dispatcher: EventDispatcher,
): Boolean {
    if (!isDispatchableKeyDown(event, composing)) return false
    val key = protocolKey(event.key) ?: return false
    return dispatcher.dispatch(
        HostEvent.KeyDown(
            nodeId = nodeId,
            handlerId = handlerId,
            key = key,
            shiftKey = event.isShiftPressed,
            ctrlKey = event.isCtrlPressed,
            altKey = event.isAltPressed,
            metaKey = event.isMetaPressed,
        ),
    )
}

/**
 * `Modifier.onKeyEvent` for a widget that owns no text composition.
 *
 * `onKeyEvent` rather than `onPreviewKeyEvent`: preview is for interception, and a widget
 * without an editor has nothing to intercept. The TextField is the exception and wires its
 * own preview handler, because Enter has to be answered before the editor inserts a newline.
 */
internal fun Modifier.hostKeyEvents(
    nodeId: Int,
    handlerId: Long,
    dispatcher: EventDispatcher,
): Modifier = onKeyEvent { event ->
    dispatchKeyDown(nodeId, handlerId, event, composing = false, dispatcher = dispatcher)
}
