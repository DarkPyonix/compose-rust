package dev.darkpyonix.composerust.ui.platform

import androidx.activity.compose.BackHandler
import dev.darkpyonix.composerust.foundation.platformBackHandler

/**
 * Hands Android's back gesture and button to the widgets that go back on their own.
 *
 * The topmost open Dialog, Sheet or Popover hears it first and asks the Host to close, as
 * Escape does. Otherwise a split pane showing its body on its own shows the side pane again
 * and tells the Host once. With neither on screen, the Activity keeps its own back.
 */
internal fun installAndroidBack() {
    platformBackHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) }
}
