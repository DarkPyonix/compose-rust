package dev.darkpyonix.composerust.ui.platform

/**
 * The class `Dispatchers.Main` resolves to, as the start report names it.
 *
 * Kotlin/Native on Windows has no main dispatcher: what answers is kotlinx.coroutines'
 * stand-in, which refuses the first thing sent to it, and the Win32 window gives its scene a
 * dispatcher of its own instead (FrameDispatcher), as the Linux window does.
 *
 * Read by name rather than by `immediate` or anything else that dispatches, because asking the
 * stand-in anything more than its name throws.
 */
internal fun mainDispatcherName(): String {
    val main = kotlinx.coroutines.Dispatchers.Main
    return main::class.qualifiedName ?: main.toString()
}
