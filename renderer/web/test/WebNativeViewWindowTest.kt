package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebNativeViewWindowTest {
    @Test
    fun fr81_web_native_view_window_attaches_places_focuses_and_detaches() {
        val window = WebNativeViewWindow()
        assertEquals(WebNativeViewWindow.PARENT_DOM, window.parentKind)
        assertNotEquals(0L, window.parentHandle)

        val focusLog = mutableListOf<Pair<Int, Boolean>>()
        window.events = object : NativeViewWindowEvents {
            override fun focusChanged(id: Int, focused: Boolean) {
                focusLog += id to focused
            }

            override fun scaleChanged(scale: Float) {}
        }

        assertFalse(window.attach(1, 0L))
        assertFalse(window.attach(1, window.parentHandle))

        registerFakeDomElement(42.0)
        try {
            assertTrue(window.attach(1, 42L))
            assertFalse(window.attach(2, 42L))

            window.setFrameAndVisibleRegion(
                id = 1,
                frame = NativeRect(10, 20, 200, 100),
                clip = NativeRect(10, 20, 200, 100),
                holes = emptyList(),
            )
            window.setVisible(1, true)
            window.focus(1)
            window.focusScene()
            window.detach(1)

            assertEquals(listOf(1 to true, 1 to false), focusLog)
        } finally {
            unregisterFakeDomElement(42.0)
        }
    }
}

private fun registerFakeDomElement(handle: Double): Unit = js(
    """{
        if (!globalThis.__composeRustNativeViews) globalThis.__composeRustNativeViews = new Map();
        const el = typeof document !== 'undefined' ? document.createElement('div') : { style: {}, focus() {}, blur() {} };
        globalThis.__composeRustNativeViews.set(handle, el);
    }"""
)

private fun unregisterFakeDomElement(handle: Double): Unit = js(
    """{
        if (globalThis.__composeRustNativeViews) globalThis.__composeRustNativeViews.delete(handle);
    }"""
)
