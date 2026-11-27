package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.systemChrome
import dev.darkpyonix.composerust.runtime.reduceTransparency
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosWindowBackendTest {
    private class RecordingListener : PlatformWindowListener {
        val focusEvents = mutableListOf<Boolean>()
        var closedBySystemCount = 0
        var movedCount = 0

        override fun onCloseRequested() {}
        override fun onFocusChanged(focused: Boolean) {
            focusEvents += focused
        }

        override fun onMoved(bounds: ScreenBounds) {
            movedCount += 1
        }

        override fun onFollowEnded(cancelled: Boolean) {}
        override fun onClosedBySystem() {
            closedBySystemCount += 1
        }
    }

    @Test
    fun fr53_ios_multi_window_backend_opens_focuses_disconnects_and_closes() {
        val primaryUiWindow = UIWindow(frame = CGRectMake(0.0, 0.0, 390.0, 844.0))
        val activations = mutableListOf<Pair<Int, String>>()
        val backend = IosWindowBackend(
            initialWindow = primaryUiWindow,
            multipleScenesSupported = { true },
            activateScene = { _, nodeId, restoreKey ->
                activations += nodeId to restoreKey
                true
            },
            destroyScene = {},
        )

        val listener1 = RecordingListener()
        val first = backend.open(PlatformWindowConfig(nodeId = 1, restoreKey = "main"), listener1)
        assertNotNull(first)
        assertEquals(0, activations.size)

        val listener2 = RecordingListener()
        val second = backend.open(PlatformWindowConfig(nodeId = 2, restoreKey = "editor-2"), listener2)
        assertNotNull(second)
        assertEquals(listOf(2 to "editor-2"), activations)

        val secondScene = backend.windowFor(2)!!
        val secondaryUiWindow = UIWindow(frame = CGRectMake(0.0, 0.0, 390.0, 844.0))
        backend.sceneConnected(2, "editor-2", null, secondaryUiWindow)
        assertEquals(1, listener2.movedCount)

        secondScene.focusChanged(true)
        assertTrue(secondScene.isKey)
        assertEquals(listOf(true), listener2.focusEvents)

        // Scene disconnect drops the UIWindow without closing the Window node.
        secondScene.disconnect()
        assertFalse(secondScene.isKey)
        assertEquals(0, listener2.closedBySystemCount)
        assertEquals(listOf(true, false), listener2.focusEvents)

        // System dismissal reports onClosedBySystem.
        secondScene.closedBySystem()
        assertEquals(1, listener2.closedBySystemCount)
    }

    @Test
    fun fr53_ios_multi_window_backend_returns_null_when_multiple_scenes_unsupported() {
        val primaryUiWindow = UIWindow(frame = CGRectMake(0.0, 0.0, 390.0, 844.0))
        val backend = IosWindowBackend(
            initialWindow = primaryUiWindow,
            multipleScenesSupported = { false },
        )
        assertNotNull(backend.open(PlatformWindowConfig(nodeId = 1), RecordingListener()))
        assertNull(backend.open(PlatformWindowConfig(nodeId = 2), RecordingListener()))
    }

    @Test
    fun fr14_1_2_ios_reduce_transparency_follows_platform_setting() {
        val previous = reduceTransparency
        try {
            syncReduceTransparency(true)
            assertTrue(reduceTransparency)
            syncReduceTransparency(false)
            assertFalse(reduceTransparency)
        } finally {
            syncReduceTransparency(previous)
        }
    }

    @Test
    fun fr14_3_ios_system_chrome_updates_override_user_interface_style() {
        val previousChrome = systemChrome
        try {
            val window = UIWindow(frame = CGRectMake(0.0, 0.0, 390.0, 844.0))
            installIosSystemChrome { listOf(window) }
            val chrome = assertNotNull(systemChrome)

            chrome.setDarkIcons(true)
            assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleLight, window.overrideUserInterfaceStyle)

            chrome.setDarkIcons(false)
            assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleDark, window.overrideUserInterfaceStyle)
        } finally {
            systemChrome = previousChrome
        }
    }

    @Test
    fun fr53_ipados_tear_off_registers_a_user_activity() {
        val activity = IosWindowBackend.tearOffUserActivity("tab-editor-1")
        assertEquals(IosWindowBackend.WINDOW_ACTIVITY_TYPE, activity.activityType)
        assertEquals("tab-editor-1", activity.userInfo?.get(IosWindowBackend.KEY_RESTORE_KEY))
    }
}
