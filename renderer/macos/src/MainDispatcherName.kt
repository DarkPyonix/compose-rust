package dev.darkpyonix.composerust.ui.platform

/**
 * The class `Dispatchers.Main` resolves to, as the start report names it.
 *
 * On macOS that is kotlinx.coroutines' own dispatcher onto the main run loop, which is where
 * the fork's window sends the scene's work. Linux has no main dispatcher at all: what answers
 * there is the stand-in that refuses the first thing sent to it, which is why the Linux window
 * gives its scene a dispatcher of its own. The name says which of the two this build has.
 *
 * Read by name rather than by `immediate` or anything else that dispatches, because asking the
 * Linux stand-in anything more than its name throws.
 */
internal fun mainDispatcherName(): String {
    val main = kotlinx.coroutines.Dispatchers.Main
    return main::class.qualifiedName ?: main.toString()
}
