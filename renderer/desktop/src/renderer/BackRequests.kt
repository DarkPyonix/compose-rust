package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.darkpyonix.composerust.protocol.BackSource
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.NodeTable

/**
 * Which gesture the platform's back hook stands for. Android's back gesture and button
 * where nothing says otherwise; the iOS renderer says the swipe in from the leading edge.
 */
var platformBackSource: BackSource = BackSource.SystemBack

/**
 * What the platform does with a back that no handler consumed, as a function to call once
 * the window's own back hook has been turned off. Filled in by the Android renderer, where
 * the system then takes the back itself. Null where an unconsumed back does nothing.
 *
 * A composable that returns the function, because what it calls is found in the composition.
 */
var platformUnhandledBack: (@Composable () -> (() -> Unit))? = null

/**
 * A window's requests to go back: who is asked, in which order, and what becomes of a
 * request nobody consumed.
 *
 * A request is offered to the innermost node of the window that has an `OnBackRequested`
 * handler, then to each of its ancestors that has one, and stops at the first handler that
 * consumes it. Each offer is one synchronous call into the Host, whose result says whether
 * it was consumed. An open overlay is asked before any of this, by the caller.
 */
class BackRequests internal constructor(
    private val table: NodeTable,
    private val windowId: Int,
    private val dispatcher: EventDispatcher,
) {
    /**
     * True from a back the platform is to take until it has been handed over. The window's
     * back hook is off for that long, so the back sent again reaches the platform and not
     * this window a second time.
     */
    var passing by mutableStateOf(false)
        internal set

    /** Whether a node of the window hears a request to go back. Read in composition. */
    val hasListener: Boolean get() = table.hearsBack(windowId)

    /** Offers one request to the window's handlers. True when one of them consumed it. */
    fun request(source: BackSource): Boolean {
        for (target in table.backChain(windowId)) {
            // The handler may have changed the tree. A node it took out is not asked.
            if (table.node(target.nodeId) == null) continue
            if (dispatcher.dispatch(HostEvent.BackRequested(target.nodeId, target.handlerId, source))) return true
        }
        return false
    }

    /**
     * The platform's back. Offered to the handlers, and where none of them consumed it,
     * left for the platform to act on.
     */
    fun platformBack() {
        if (!request(platformBackSource) && platformUnhandledBack != null) passing = true
    }

    /** Escape that reached the window: no control and no overlay used it. */
    internal fun handleEscape(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyDown && event.key == Key.Escape && request(BackSource.Escape)
}

/**
 * Hands a back that nobody consumed to the platform, after the composition in which the
 * window's own back hook was turned off has been applied.
 */
@Composable
internal fun PassUnhandledBack(requests: BackRequests) {
    val unhandled = platformUnhandledBack ?: return
    val pass by rememberUpdatedState(unhandled())
    if (requests.passing) {
        LaunchedEffect(requests) {
            try {
                pass()
            } finally {
                requests.passing = false
            }
        }
    }
}

/**
 * A mouse's back button, pressed where nothing under the pointer consumed the press, is a
 * request to go back. The press is consumed only when a handler consumed the request.
 */
internal fun Modifier.pointerBackButton(requests: BackRequests): Modifier = pointerInput(requests) {
    awaitPointerEventScope {
        var held = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.buttons.isBackPressed
            if (pressed && !held && event.changes.none { it.isConsumed }) {
                if (requests.request(BackSource.PointerBack)) event.changes.forEach { it.consume() }
            }
            held = pressed
        }
    }
}
