package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.COpaquePointer

/**
 * The Host's functions as the link resolved them, which on Windows is never: the Host is an
 * MSVC executable and the renderer finds its exports by name (HostSymbolLookup.kt reads the
 * executable's export table with GetProcAddress), so the lookup is the only answer here.
 */
internal fun linkedHostFunction(@Suppress("UNUSED_PARAMETER") name: String): COpaquePointer? = null
