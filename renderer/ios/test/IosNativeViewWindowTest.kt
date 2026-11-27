package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.NativeViewWire
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosNativeViewWindowTest {
    @Test
    fun fr81_ios_native_view_window_attaches_places_clips_focuses_and_detaches() {
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, 400.0, 800.0))
        val slotWindow = IosNativeViewWindow(window)
        assertEquals(NativeViewWire.PARENT_UIKIT, slotWindow.parentKind)
        assertNotEquals(0L, slotWindow.parentHandle)

        val focusLog = mutableListOf<Pair<Int, Boolean>>()
        slotWindow.events = object : NativeViewWindowEvents {
            override fun focusChanged(id: Int, focused: Boolean) {
                focusLog += id to focused
            }

            override fun scaleChanged(scale: Float) {}
        }

        assertFalse(slotWindow.attach(1, 0L))

        val child = UIView(frame = CGRectMake(0.0, 0.0, 100.0, 50.0))
        val handle = child.objcPtr().toLong()
        assertTrue(slotWindow.attach(1, handle))
        assertFalse(slotWindow.attach(2, handle))
        assertNotNull(child.superview)

        slotWindow.setFrameAndVisibleRegion(
            id = 1,
            frame = NativeRect(10, 20, 200, 100),
            clip = NativeRect(10, 20, 200, 100),
            holes = listOf(NativeHole(NativeRect(30, 40, 50, 30), cornerRadius = 8)),
        )
        slotWindow.setVisible(1, true)
        val container = child.superview!!
        assertFalse(container.hidden)
        assertNotNull(container.layer.mask)

        slotWindow.setVisibleRegion(1, NativeRect(10, 20, 200, 100), emptyList())
        assertNull(container.layer.mask)

        slotWindow.focus(1)
        assertEquals(listOf(1 to true), focusLog)

        slotWindow.focusScene()
        assertEquals(listOf(1 to true, 1 to false), focusLog)

        slotWindow.detach(1)
        assertNull(child.superview)
    }
}
