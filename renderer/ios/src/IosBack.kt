@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.backhandler.BackHandler
import dev.darkpyonix.composerust.foundation.platformBackHandler
import dev.darkpyonix.composerust.foundation.platformBackSource
import dev.darkpyonix.composerust.protocol.BackSource

/**
 * Hands the swipe in from the leading edge of an iOS screen to the widgets that go back.
 *
 * The gesture is the one Compose's own back handler receives, which is why this goes
 * through it: a second recogniser on the window would fight the first. That handler is an
 * experimental Compose API, so this file is the only place that names it. An open overlay
 * hears the swipe first, then the nodes that asked to hear a request to go back, and with
 * neither on screen the gesture is not claimed at all.
 *
 * iOS does nothing of its own with a swipe nobody claimed, so there is no handing on.
 */
internal fun installIosBack() {
    platformBackSource = BackSource.EdgeSwipe
    platformBackHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) }
}
