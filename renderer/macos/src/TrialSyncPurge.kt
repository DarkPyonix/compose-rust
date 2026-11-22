@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.darkpyonix.composerust.ui.platform

/**
 * TRIAL (root cause, 2026-11-22, not for merging): what the fork's idle purge would do if it
 * first waited for the GPU. `submit(syncCpu = true)` makes Skia see that the last frame's command
 * buffer has finished and drop what it held; the limit set to 0 and back then frees it.
 */
internal fun trialSyncPurge(surface: org.thisisthepy.compose.window.macos.MetalSurface): Long {
    surface.context.submit(true)
    val limit = surface.cacheLimitBytes
    surface.cacheLimitBytes = 0L
    surface.cacheLimitBytes = limit
    return surface.allocatedBytes
}
