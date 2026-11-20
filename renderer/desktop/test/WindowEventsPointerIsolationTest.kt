package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/**
 * Each ComposeScene tracks its own last pointer position so a scroll in a second window
 * before any move there does not reuse the first window's pointer coordinates.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class WindowEventsPointerIsolationTest {
    @Test
    fun scroll_in_second_scene_does_not_reuse_first_scenes_pointer_position() {
        var scrollPosInSecond: Offset? = null
        val sceneA = CanvasLayersComposeScene(density = Density(1f), size = IntSize(300, 300))
        val sceneB = CanvasLayersComposeScene(density = Density(1f), size = IntSize(300, 300))
        try {
            sceneA.setContent { Box(Modifier.fillMaxSize()) }
            sceneB.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            scrollPosInSecond = event.changes.firstOrNull()?.position
                        },
                )
            }
            // Move pointer in scene A to (220, 180).
            sceneA.receive(
                WindowEvent(
                    kind = WindowEvent.POINTER_MOVE,
                    x = 220f,
                    y = 180f,
                    buttons = 0,
                    modifiers = 0,
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )
            // Scroll in scene B before any pointer move in scene B.
            sceneB.receive(
                WindowEvent(
                    kind = WindowEvent.SCROLL,
                    x = 0f,
                    y = 1f,
                    buttons = 0,
                    modifiers = 0,
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )
            assertEquals(Offset.Zero, scrollPosInSecond)
        } finally {
            sceneA.close()
            sceneB.close()
        }
    }

    @Test
    fun a_forgotten_scene_scrolls_from_the_top_left_again() {
        var scrollPos: Offset? = null
        val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(300, 300))
        try {
            scene.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            scrollPos = event.changes.firstOrNull()?.position
                        },
                )
            }
            scene.receive(event(WindowEvent.POINTER_MOVE, 220f, 180f))
            scene.receive(event(WindowEvent.SCROLL, 0f, 1f))
            assertEquals(Offset(220f, 180f), scrollPos)

            // What a window does as it closes its scene. Nothing of the scene stays behind.
            scene.forgetPointer()
            scrollPos = null
            scene.receive(event(WindowEvent.SCROLL, 0f, 1f))
            assertEquals(Offset.Zero, scrollPos)
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
