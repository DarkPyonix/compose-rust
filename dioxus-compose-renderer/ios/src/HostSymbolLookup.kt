package dioxus.compose.ui.platform

import kotlinx.cinterop.COpaquePointer
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

/**
 * A Host function, found by name in the image this library was linked into.
 *
 * `dlsym` on the running image, which resolves against whatever finally links the renderer:
 * the same late binding the macOS build gets from `-undefined dynamic_lookup`. Kept apart from
 * the connection because it is the one line of it that differs on Windows, where the
 * executable's export table is read with GetProcAddress instead.
 */
internal fun hostSymbol(name: String): COpaquePointer? = dlsym(image, name)

private val image = dlopen(null, RTLD_NOW)
