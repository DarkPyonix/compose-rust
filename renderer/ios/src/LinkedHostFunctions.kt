package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.COpaquePointer

/**
 * One of the Host's functions, as the link resolved it, or null.
 *
 * Always null on Apple platforms, where the renderer finds the Host's functions by name
 * at startup. A Mach-O executable exports its global symbols, so the lookup finds them in
 * the image the renderer was linked into. Linux has its own version of this file, because
 * an ELF executable does not.
 */
internal fun linkedHostFunction(@Suppress("UNUSED_PARAMETER") name: String): COpaquePointer? = null
