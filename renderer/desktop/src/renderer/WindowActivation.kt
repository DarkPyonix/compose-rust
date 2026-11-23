package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Whether a window on its own is the active one, kept current from what the window reports
 * (an unfocused window flattens its glass to the opaque fallback, which it can do
 * only if `isWindowFocused` follows the real activation).
 *
 * [initial] is whether the window is active as it opens. [take] answers the activation the
 * window reported since it was last asked (true active, false not), or null when nothing was
 * reported. [poll] is called once a turn of the window's loop and takes every report waiting,
 * so the state is the last one. Shared by the Kotlin/Native windows, which hand in their own
 * call; compiled on the JVM too, where it is tested.
 */
class WindowActivation(initial: Boolean, private val take: () -> Boolean?) {
    /** Snapshot state, read as the scene's `isWindowFocused`. */
    val focused: MutableState<Boolean> = mutableStateOf(initial)

    /** Takes what the window reported since the last call. */
    fun poll() {
        var last: Boolean? = null
        while (true) {
            last = take() ?: break
        }
        if (last != null && focused.value != last) focused.value = last
    }
}
