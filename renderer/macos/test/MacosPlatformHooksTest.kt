@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.ReducedMotion
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.runtime.MAX_ZOOM_LEVEL
import dev.darkpyonix.composerust.runtime.MIN_ZOOM_LEVEL
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import dev.darkpyonix.composerust.runtime.platformZoomLevelStore
import dev.darkpyonix.composerust.ui.node.platformReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting
import dev.darkpyonix.composerust.ui.platform.configureSingleMacosWindow
import dev.darkpyonix.composerust.ui.platform.installMacosReducedMotion
import dev.darkpyonix.composerust.ui.platform.installMacosZoomLevelStore
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSWindowStyleMaskResizable
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class MacosPlatformHooksTest {
    private fun asked(resizable: Boolean) = Window(
        chrome = Chrome.Modern,
        titleBar = TitleBar.Normal,
        title = "Window",
        icon = 0,
        width = 480,
        height = 320,
        minWidth = 0,
        minHeight = 0,
        resizable = resizable,
    )

    @Test
    fun fr41_2_fr51_macos_reduced_motion_sets_both_hooks() {
        val previousSetting = platformReducedMotionSetting
        val previousMotion = platformReducedMotion
        try {
            platformReducedMotion = { false }
            var reduced = true
            installMacosReducedMotion(read = { reduced })
            assertEquals(ReducedMotion.On, platformReducedMotionSetting())
            assertTrue(
                platformReducedMotion(),
                "reduced motion hook must report true when the system setting is on",
            )
            reduced = false
            assertEquals(ReducedMotion.Off, platformReducedMotionSetting())
            assertFalse(
                platformReducedMotion(),
                "reduced motion hook must report false when the system setting is off",
            )
        } finally {
            platformReducedMotionSetting = previousSetting
            platformReducedMotion = previousMotion
        }
    }

    @Test
    fun fr43_macos_zoom_level_store_persists_in_user_defaults() {
        val previousStore = platformZoomLevelStore
        val defaults = NSUserDefaults.standardUserDefaults
        val key = "ComposeRustZoomLevel"
        val previousValue = defaults.objectForKey(key)
        try {
            platformZoomLevelStore = { ZoomLevelStore.None }
            defaults.removeObjectForKey(key)
            installMacosZoomLevelStore()
            val store = platformZoomLevelStore()
            assertNotSame(
                ZoomLevelStore.None,
                store,
                "macOS renderer must install a persistent zoom level store",
            )
            assertEquals(null, store.load(), "unset defaults key must load as null")
            val first = Zoom(store = store)
            assertEquals(0, first.level)
            first.setLevel(3)
            val reopened = Zoom(store = platformZoomLevelStore())
            assertEquals(3, reopened.level, "saved zoom level must persist across Zoom instances")
            store.save(MAX_ZOOM_LEVEL + 5)
            assertEquals(MAX_ZOOM_LEVEL, store.load(), "saved zoom level must clamp to max")
            store.save(MIN_ZOOM_LEVEL - 5)
            assertEquals(MIN_ZOOM_LEVEL, store.load(), "saved zoom level must clamp to min")
        } finally {
            platformZoomLevelStore = previousStore
            if (previousValue == null) {
                defaults.removeObjectForKey(key)
            } else {
                defaults.setObject(previousValue, forKey = key)
            }
        }
    }

    @Test
    fun fr19_3_macos_single_window_respects_asked_resizable() {
        NSApplication.sharedApplication()
        val window = MacosWindow(name = "resizable", width = 320, height = 240)
        window.initWindow()
        try {
            configureSingleMacosWindow(window, asked(resizable = false))
            assertEquals(
                0uL,
                window.window.styleMask and NSWindowStyleMaskResizable,
                "a non-resizable window must clear NSWindowStyleMaskResizable",
            )
            configureSingleMacosWindow(window, asked(resizable = true))
            assertTrue(
                (window.window.styleMask and NSWindowStyleMaskResizable) != 0uL,
                "a resizable window must keep NSWindowStyleMaskResizable",
            )
            configureSingleMacosWindow(window, null)
            assertTrue(
                (window.window.styleMask and NSWindowStyleMaskResizable) != 0uL,
                "a window without SetWindow must stay resizable by default",
            )
        } finally {
            window.window.orderOut(null)
            window.window.close()
        }
    }
}
