package dioxus.compose.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

/**
 * Whether the system is in dark mode, and whether it still is a moment later.
 *
 * Compose's own `isSystemInDarkTheme()` reads the value once on this platform and never
 * looks again, so a window opened in light mode stays light for the rest of its life while
 * every other application on screen changes. That was confirmed on both the JVM and the
 * native image, which rules out the reachability metadata and leaves the API itself.
 *
 * Skiko's `currentSystemTheme` is re-read on every access, so polling it turns a one time
 * reading into something that follows the system. A second is slow enough to cost nothing
 * and fast enough that nobody watches the window and wonders whether it is broken.
 *
 * The check is a property read rather than a process spawn, so it does not show up in the
 * frame budget: the coroutine wakes, compares two enum values, and goes back to sleep.
 */
@Composable
internal fun rememberSystemDark(): State<Boolean> {
    val state = remember { mutableStateOf(currentSystemTheme == SystemTheme.DARK) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_INTERVAL_MILLIS)
            val dark = currentSystemTheme == SystemTheme.DARK
            if (dark != state.value) state.value = dark
        }
    }
    return state
}

private const val POLL_INTERVAL_MILLIS = 1_000L
