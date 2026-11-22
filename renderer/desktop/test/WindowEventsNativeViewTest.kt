package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.ui.platform.forgetPointer
import dev.darkpyonix.composerust.ui.platform.receive
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Windows window says the pointer left the scene for a native view slot, and came back,
 * as POINTER_EXIT and POINTER_ENTER. The scene treats them as the pointer leaving and coming
 * in: what it hovered stops being hovered, and is hovered again on the way back.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class WindowEventsNativeViewTest {
    @Test
    fun fr81_pointer_exit_and_enter_from_the_window_end_and_resume_hover() {
        val seen = ArrayList<PointerEventType>()
        val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(300, 300))
        try {
            scene.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Exit) { seen += PointerEventType.Exit }
                        .onPointerEvent(PointerEventType.Enter) { seen += PointerEventType.Enter },
                )
            }
            scene.receive(event(WindowEvent.POINTER_MOVE, 100f, 100f))
            val before = seen.size
            scene.receive(event(WindowEvent.POINTER_EXIT, 150f, 150f))
            assertEquals(listOf(PointerEventType.Exit), seen.drop(before))
            scene.receive(event(WindowEvent.POINTER_ENTER, 120f, 120f))
            assertEquals(listOf(PointerEventType.Exit, PointerEventType.Enter), seen.drop(before))
        } finally {
            scene.forgetPointer()
            scene.close()
        }
    }

    @Test
    fun fr81_a_view_taking_the_keyboard_is_not_a_key_for_the_scene() {
        val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(300, 300))
        try {
            scene.setContent { Box(Modifier.fillMaxSize()) }
            // Nothing the scene could act on: no key consumed, and no failure with nothing focused.
            assertTrue(!scene.receive(WindowEvent.NativeViewFocusIn(3)))
            assertTrue(!scene.receive(WindowEvent.NativeViewFocusOut(3)))
        } finally {
            scene.forgetPointer()
            scene.close()
        }
    }

    private fun event(kind: Int, x: Float, y: Float) = WindowEvent(
        kind = kind,
        x = x,
        y = y,
        buttons = 0,
        modifiers = 0,
        keyCode = 0,
        codePoint = 0,
        text = "",
    )
}
