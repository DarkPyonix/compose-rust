package dev.darkpyonix.composerust.ui.platform

/**
 * The class `Dispatchers.Main` resolves to, as the start report names it.
 *
 * On macOS that is kotlinx.coroutines' own dispatcher onto the main run loop, which is where
 * the fork's window sends the scene's work. kotlinx.coroutines has no main dispatcher for Linux,
 * so the Linux renderer installs its window's frame dispatcher before it makes a scene. The name
 * says which this build has, and a Linux build that installed nothing names the stand-in
 * kotlinx.coroutines answers with instead.
 *
 * Read by name rather than by `immediate` or anything else that dispatches, because asking that
 * stand-in anything more than its name throws.
 */
internal fun mainDispatcherName(): String {
    val main = kotlinx.coroutines.Dispatchers.Main
    return main::class.qualifiedName ?: main.toString()
}
