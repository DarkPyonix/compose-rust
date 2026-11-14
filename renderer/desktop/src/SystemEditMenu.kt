package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

// The text edit menu a window puts up itself, for a platform whose Compose draws a text menu
// of its own and has no place to hand that menu to the system.
//
// Compose for Windows on Kotlin/Native is one: a text field answers a right click with a
// popup it draws, and its foundation has no representation or provider the renderer could
// give the system's menu through. The fork's Win32 window has a menu of its own instead
// (`dxc_native_set_edit_menu`): while it is turned on, a right click, the Menu key or Shift
// and F10 put up the system's popup, and the entry chosen comes back as Control and X, C, V
// or A. The two must never both come up, so this decides when the window's is on and keeps
// Compose's from opening while it is.

/** What the focused text field allows, as the window's menu greys its entries by it. */
data class FocusedTextField(
    /** Some text is selected: Cut and Copy can run. */
    val hasSelection: Boolean,
    /** The field takes edits: Cut and Paste can run. */
    val editable: Boolean,
    /** The field holds some text: Select All can run. */
    val hasText: Boolean,
)

/**
 * When the window's own text edit menu is on.
 *
 * On while a text field has focus, so a right click puts up the system's menu, and off
 * otherwise. Off for good where [systemMenu] is false, which is an application that draws its
 * own menu: Compose's menu, or the application's, is then the only one.
 *
 * Off for one press as well where the application took the request for a menu: a node of its
 * own heard the right click and consumed it ([contextMenuTaken]), which is how an application
 * shows a menu of its own over a field. The window puts its menu up when the button comes up,
 * and the press reaches the application first, so turning it off on the press is in time for
 * that release. It comes back on once the button is up ([pointerReleased]).
 *
 * [apply] is told each change, and only a change: the field's state, or null for off.
 */
class SystemEditMenuGate(
    private val systemMenu: Boolean,
    private val apply: (FocusedTextField?) -> Unit,
) {
    // The state last handed to apply, as bits (FIELD, SELECTION, EDITABLE, TEXT); -1 before
    // the first, so that the window is told once even when there is nothing to turn on.
    private var applied = -1
    private var wanted = 0
    private var taken = false

    /** Whether the window's menu is on now, so a right click puts it up. */
    val armed: Boolean get() = applied > 0

    /**
     * What has focus now: [focused] false for no text field, which turns the menu off. Called
     * every turn of the window; it costs a comparison when nothing changed.
     */
    fun update(focused: Boolean, hasSelection: Boolean = false, editable: Boolean = true, hasText: Boolean = false) {
        wanted = if (!focused) 0 else {
            FIELD or
                (if (hasSelection) SELECTION else 0) or
                (if (editable) EDITABLE else 0) or
                (if (hasText) TEXT else 0)
        }
        push()
    }

    /** The application consumed a request for a context menu: its menu is the one for this press. */
    fun contextMenuTaken() {
        if (taken) return
        taken = true
        push()
    }

    /** The secondary button is up again. A press the application took is over. */
    fun pointerReleased() {
        if (!taken) return
        taken = false
        push()
    }

    private fun push() {
        val next = if (!systemMenu || taken) 0 else wanted
        if (next == applied) return
        applied = next
        apply(
            if (next == 0) null else FocusedTextField(
                hasSelection = next and SELECTION != 0,
                editable = next and EDITABLE != 0,
                hasText = next and TEXT != 0,
            ),
        )
    }

    private companion object {
        const val FIELD = 1
        const val SELECTION = 2
        const val EDITABLE = 4
        const val TEXT = 8
    }
}

/**
 * Keeps Compose's own text menu shut while the window's is the one a right click puts up.
 *
 * Compose opens a field's menu on a secondary press nobody consumed. This consumes that press
 * on its way down, before the field sees it, while [armed] says the window's menu is on. The
 * application's own nodes still hear it: the renderer hands a press to them whether or not it
 * was consumed, so an application that shows a menu of its own still can.
 */
fun Modifier.leaveTextMenuToWindow(armed: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
            if (!armed()) continue
            for (change in event.changes) {
                if (change.changedToDownIgnoreConsumed()) change.consume()
            }
        }
    }
}
