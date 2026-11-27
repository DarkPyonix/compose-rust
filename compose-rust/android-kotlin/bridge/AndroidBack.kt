package dev.darkpyonix.composerust.ui.platform

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import dev.darkpyonix.composerust.foundation.platformBackHandler
import dev.darkpyonix.composerust.foundation.platformUnhandledBack

/**
 * Hands Android's back gesture and button to the widgets that go back on their own.
 *
 * The topmost open Dialog, Sheet or Popover hears it first and asks the Host to close, as
 * Escape does. Otherwise a split pane showing its body on its own shows the side pane again
 * and tells the Host once. After those it goes to the nodes that asked to hear a request to
 * go back. With none of them on screen, the Activity keeps its own back.
 *
 * A back that a node heard and did not consume is sent again once the window's own callback
 * is off, so the system does what it does with a back nobody claimed: the first Activity
 * goes to the background, and another one is asked to close.
 */
internal fun installAndroidBack() {
    platformBackHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) }
    platformUnhandledBack = {
        val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val pass: () -> Unit = { dispatcher?.onBackPressed() }
        pass
    }
}
