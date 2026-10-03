@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dioxus.compose.ui.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress

/**
 * A Host function, found by name in the executable this library was linked into.
 *
 * GetProcAddress on the executable's own module, which answers from its export table. The
 * Host's five functions are in that table because the crate's objects carry `/EXPORT`
 * directives for them, which the MSVC linker reads out of every object it links.
 */
internal fun hostSymbol(name: String): COpaquePointer? =
    GetProcAddress(GetModuleHandleW(null), name)?.reinterpret()
