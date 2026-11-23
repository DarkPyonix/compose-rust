@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAMetalLayer

/**
 * Default number of drawables in a macOS `CAMetalLayer` pool (SPEC NFR-3, #1044).
 *
 * `CAMetalLayer.maximumDrawableCount` defaults to 3 (triple-buffered). At 1440x900 @2x
 * each drawable is a 6 MiB BGRA8 IOSurface, so 3 drawables hold 18 MiB of IOSurface memory
 * per window. Setting 2 (double-buffered) cuts that to 12 MiB per window.
 */
internal const val DEFAULT_METAL_DRAWABLE_COUNT: ULong = 2uL

/** Largest value `CAMetalLayer.maximumDrawableCount` accepts on macOS. */
internal const val MAX_METAL_DRAWABLE_COUNT: ULong = 3uL

/** Environment variable that overrides the drawable count (2 or 3) for benchmarking. */
internal const val METAL_DRAWABLES_ENV = "DXC_MACOS_DRAWABLES"

/**
 * Resolves the `CAMetalLayer.maximumDrawableCount` from [env] (`DXC_MACOS_DRAWABLES`).
 *
 * QuartzCore only allows 2 or 3 drawables. Anything unset, blank, or outside `{2, 3}`
 * falls back to [DEFAULT_METAL_DRAWABLE_COUNT] (2).
 */
internal fun resolveDrawableCount(env: String?): ULong = when (env?.trim()) {
    "2" -> DEFAULT_METAL_DRAWABLE_COUNT
    "3" -> MAX_METAL_DRAWABLE_COUNT
    else -> DEFAULT_METAL_DRAWABLE_COUNT
}

/** Target `CAMetalLayer.maximumDrawableCount` for this process. */
internal fun targetDrawableCount(): ULong = resolveDrawableCount(System.getenv(METAL_DRAWABLES_ENV))

/**
 * Walks [rootLayer] and its `sublayers`, setting `maximumDrawableCount` to [count] on every
 * `CAMetalLayer` found. Returns the number of `CAMetalLayer` instances configured.
 */
internal fun configureMetalDrawables(
    rootLayer: CALayer?,
    count: ULong = targetDrawableCount(),
): Int {
    if (rootLayer == null) return 0
    val clamped = count.coerceIn(DEFAULT_METAL_DRAWABLE_COUNT, MAX_METAL_DRAWABLE_COUNT)
    val visited = mutableSetOf<CAMetalLayer>()
    configureLayerTree(rootLayer, clamped, visited)
    return visited.size
}

/**
 * Walks [window]'s `contentView` hierarchy and backing `CALayer` trees, setting
 * `maximumDrawableCount` to [count] on every `CAMetalLayer` found. Returns the number of
 * `CAMetalLayer` instances configured.
 */
internal fun configureMetalDrawables(
    window: NSWindow,
    count: ULong = targetDrawableCount(),
): Int {
    val rootView = window.contentView ?: return 0
    val clamped = count.coerceIn(DEFAULT_METAL_DRAWABLE_COUNT, MAX_METAL_DRAWABLE_COUNT)
    val visited = mutableSetOf<CAMetalLayer>()
    configureViewTree(rootView, clamped, visited)
    return visited.size
}

private fun configureViewTree(
    view: NSView,
    count: ULong,
    visited: MutableSet<CAMetalLayer>,
) {
    view.layer?.let { configureLayerTree(it, count, visited) }
    for (child in view.subviews) {
        if (child is NSView) configureViewTree(child, count, visited)
    }
}

private fun configureLayerTree(
    layer: CALayer,
    count: ULong,
    visited: MutableSet<CAMetalLayer>,
) {
    if (layer is CAMetalLayer && visited.add(layer)) {
        layer.maximumDrawableCount = count
    }
    val children = layer.sublayers ?: return
    for (child in children) {
        if (child is CALayer) configureLayerTree(child, count, visited)
    }
}
