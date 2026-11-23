@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.ui.platform.receive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import org.thisisthepy.compose.window.WindowEvent

/**
 * A pointer event from a window of our own carries the keyboard modifiers held with
 * it, Shift, Ctrl, Alt and Meta, so the Host's `Pointer.modifiers` says them. The windows record
 * them (Win32: 1 Shift, 2 Ctrl, 4 Alt, 8 Win; X11 and AppKit: the AppKit flag bits 17 to 20) and
 * the scene was never told, so every pointer reached the Host with modifiers 0.
 */
class WindowPointerModifiersTest {

    private data class Held(val shift: Boolean, val ctrl: Boolean, val alt: Boolean, val meta: Boolean)

    private fun Held(modifiers: PointerKeyboardModifiers) =
        Held(modifiers.isShiftPressed, modifiers.isCtrlPressed, modifiers.isAltPressed, modifiers.isMetaPressed)

    /** What the scene heard as held with each press, move and release, after [events]. */
    private fun heard(win32: Boolean, events: List<WindowEvent>): List<Pair<PointerEventType, Held>> {
        val seen = mutableListOf<Pair<PointerEventType, Held>>()
        val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(100, 100), coroutineContext = Dispatchers.Unconfined)
        try {
            scene.setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release ||
                                    event.type == PointerEventType.Move
                                ) {
                                    seen += event.type to Held(event.keyboardModifiers)
                                }
                            }
                        }
                    },
                )
            }
            scene.render(Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas(), 0L)
            for (event in events) scene.receive(event, win32 = win32)
        } finally {
            scene.close()
        }
        return seen
    }

    private fun event(kind: Int, buttons: Int, modifiers: Int, x: Float = 10f) =
        WindowEvent(kind, x, 10f, buttons, modifiers, 0, 0, "")

    @Test
    fun fr46_2_win32_pointer_events_carry_the_held_modifiers() {
        val ctrlShift = 1 or 2
        val heard = heard(
            win32 = true,
            listOf(
                event(WindowEvent.POINTER_DOWN, 1, ctrlShift),
                event(WindowEvent.POINTER_MOVE, 1, ctrlShift or 4, x = 20f),
                event(WindowEvent.POINTER_UP, 0, 8),
            ),
        )
        assertEquals(
            listOf(
                PointerEventType.Press to Held(shift = true, ctrl = true, alt = false, meta = false),
                PointerEventType.Move to Held(shift = true, ctrl = true, alt = true, meta = false),
                PointerEventType.Release to Held(shift = false, ctrl = false, alt = false, meta = true),
            ),
            heard,
        )
    }

    @Test
    fun fr46_2_x11_and_appkit_pointer_events_carry_the_held_modifiers() {
        val shift = 1 shl 17
        val ctrl = 1 shl 18
        val alt = 1 shl 19
        val meta = 1 shl 20
        val heard = heard(
            win32 = false,
            listOf(
                event(WindowEvent.POINTER_DOWN, 1, alt or meta),
                event(WindowEvent.POINTER_UP, 0, shift or ctrl),
            ),
        )
        assertEquals(
            listOf(
                PointerEventType.Press to Held(shift = false, ctrl = false, alt = true, meta = true),
                PointerEventType.Release to Held(shift = true, ctrl = true, alt = false, meta = false),
            ),
            heard,
        )
    }
}
