package dioxus.compose.ui.platform

import androidx.activity.compose.BackHandler
import dioxus.compose.foundation.platformBackHandler

/**
 * Hands Android's back gesture and button to the widgets that go back on their own.
 *
 * A split pane showing its body on its own is the one today: back shows the side pane
 * again and tells the Host once, and anything else on the screen keeps the Activity's own
 * back.
 */
internal fun installAndroidBack() {
    platformBackHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) }
}
