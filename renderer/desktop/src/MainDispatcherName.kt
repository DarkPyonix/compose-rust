package dev.darkpyonix.composerust.ui.platform

/**
 * The class `Dispatchers.Main` resolves to, as the start report names it.
 *
 * The class's binary name, which is what a stack trace and the service file that installs a
 * dispatcher both call it. The Kotlin/Native renderers have their own answer to the same
 * question, because a native runtime has no `javaClass`.
 */
internal fun mainDispatcherName(): String = kotlinx.coroutines.Dispatchers.Main.javaClass.name
