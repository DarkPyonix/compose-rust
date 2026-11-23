@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.DEFAULT_METAL_DRAWABLE_COUNT
import dev.darkpyonix.composerust.ui.platform.MAX_METAL_DRAWABLE_COUNT
import dev.darkpyonix.composerust.ui.platform.configureMetalDrawables
import dev.darkpyonix.composerust.ui.platform.resolveDrawableCount
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSView
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Double-buffered `CAMetalLayer` pool sizing on macOS (SPEC NFR-3, #1044).
 *
 * `CAMetalLayer.maximumDrawableCount` defaults to 3, holding 18 MiB of IOSurface memory
 * at 1440x900 @2x instead of 12 MiB with 2 drawables.
 */
class MetalDrawablesTest {
    @Test
    fun nfr3_resolve_drawable_count_defaults_to_two() {
        assertEquals(DEFAULT_METAL_DRAWABLE_COUNT, resolveDrawableCount(null))
        assertEquals(2uL, resolveDrawableCount(null))
        assertEquals(2uL, resolveDrawableCount(""))
        assertEquals(2uL, resolveDrawableCount("   "))
    }

    @Test
    fun nfr3_resolve_drawable_count_accepts_two_and_three() {
        assertEquals(DEFAULT_METAL_DRAWABLE_COUNT, resolveDrawableCount("2"))
        assertEquals(MAX_METAL_DRAWABLE_COUNT, resolveDrawableCount("3"))
        assertEquals(3uL, resolveDrawableCount(" 3 "))
        assertEquals(2uL, resolveDrawableCount(" 2 "))
    }

    @Test
    fun nfr3_resolve_drawable_count_rejects_invalid_or_out_of_range() {
        for (invalid in listOf("0", "1", "4", "-2", "two", "2.5", "30")) {
            assertEquals(
                2uL,
                resolveDrawableCount(invalid),
                "unexpected drawable count for invalid env value '$invalid'",
            )
        }
    }

    @Test
    fun nfr3_configure_metal_drawables_handles_null_and_plain_layer() {
        assertEquals(0, configureMetalDrawables(null as CALayer?, 2uL))
        assertEquals(0, configureMetalDrawables(CALayer(), 2uL))
    }

    @Test
    fun nfr3_configure_metal_drawables_sets_direct_metal_layer() {
        val metal = CAMetalLayer()
        assertEquals(3uL, metal.maximumDrawableCount, "CAMetalLayer defaults to 3 drawables on macOS")

        val configuredTwo = configureMetalDrawables(metal, 2uL)
        assertEquals(1, configuredTwo)
        assertEquals(2uL, metal.maximumDrawableCount)

        val configuredThree = configureMetalDrawables(metal, 3uL)
        assertEquals(1, configuredThree)
        assertEquals(3uL, metal.maximumDrawableCount)
    }

    @Test
    fun nfr3_configure_metal_drawables_walks_nested_sublayers() {
        val root = CALayer()
        val container = CALayer()
        val nestedMetal = CAMetalLayer()
        val siblingMetal = CAMetalLayer()
        container.addSublayer(nestedMetal)
        root.addSublayer(container)
        root.addSublayer(siblingMetal)

        val configured = configureMetalDrawables(root, 2uL)
        assertEquals(2, configured)
        assertEquals(2uL, nestedMetal.maximumDrawableCount)
        assertEquals(2uL, siblingMetal.maximumDrawableCount)
    }

    @Test
    fun nfr3_configure_metal_drawables_configures_macos_window() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "drawables", width = 320, height = 240)
        window.initWindow()
        try {
            val configured = configureMetalDrawables(window.window, 2uL)
            assertTrue(configured >= 1, "expected at least one CAMetalLayer in MacosWindow, found $configured")

            val layers = collectMetalLayers(window.window.contentView)
            assertEquals(configured, layers.size)
            for (layer in layers) {
                assertEquals(2uL, layer.maximumDrawableCount)
            }
        } finally {
            window.window.orderOut(null)
            window.window.close()
        }
    }

    private fun collectMetalLayers(root: NSView?): Set<CAMetalLayer> {
        val found = mutableSetOf<CAMetalLayer>()
        if (root == null) return found
        fun walkLayer(layer: CALayer) {
            if (layer is CAMetalLayer) found.add(layer)
            layer.sublayers?.forEach { if (it is CALayer) walkLayer(it) }
        }
        fun walkView(view: NSView) {
            view.layer?.let(::walkLayer)
            view.subviews.forEach { if (it is NSView) walkView(it) }
        }
        walkView(root)
        return found
    }
}
