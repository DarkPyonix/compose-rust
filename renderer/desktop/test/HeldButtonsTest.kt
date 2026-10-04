@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.ui.platform.HeldButtons
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * After a system menu closes, the scene holds no button.
 *
 * The menu's own event loop takes the release of the button that opened or closed it, so the
 * Kotlin/Native window never sees it. Its scene went on believing the right button was down,
 * every later click arrived as a second button on a held one, and no button in the window
 * worked again. The window now checks what it told the scene against the system's mask of
 * pressed buttons and sends the release the menu took.
 */
class HeldButtonsTest {

    @Test
    fun fr33_1_a_button_the_system_says_is_up_is_released() {
        val held = HeldButtons<PointerButton>()
        held.pressed(HeldButtons.SECONDARY, PointerButton.Secondary)
        assertEquals(listOf(PointerButton.Secondary), held.stale(systemPressed = 0L))
        assertFalse(held.anyHeld)
        assertEquals(emptyList(), held.stale(systemPressed = 0L), "released once, not again")
    }

    @Test
    fun fr33_1_a_button_still_down_is_left_alone() {
        val held = HeldButtons<PointerButton>()
        // Control and the primary button: the system reports button 0, the scene was told
        // secondary.
        held.pressed(HeldButtons.PRIMARY, PointerButton.Secondary)
        assertEquals(emptyList(), held.stale(systemPressed = 1L))
        assertTrue(held.anyHeld)
        assertEquals(PointerButton.Secondary, held.released(HeldButtons.PRIMARY))
        assertFalse(held.anyHeld)
    }

    @Test
    fun fr33_1_the_scene_has_no_pressed_button_after_the_menu_returns() {
        var clicks = 0
        var anyPressedAfterLastEvent = true
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        try {
            scene.setContent {
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    anyPressedAfterLastEvent = event.buttons.areAnyPressed
                                }
                            }
                        }
                        .clickable { clicks++ },
                )
            }
            scene.render(Surface.makeRasterN32Premul(100, 100).canvas.asComposeCanvas(), 0L)
            val held = HeldButtons<PointerButton>()
            val at = Offset(50f, 50f)

            // The right press reaches the scene; the menu opens and its loop takes the release.
            held.pressed(HeldButtons.SECONDARY, PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Secondary)
            assertTrue(anyPressedAfterLastEvent)

            // The menu closes: what the window does then.
            for (button in held.stale(systemPressed = 0L)) {
                scene.sendPointerEvent(PointerEventType.Release, at, button = button)
            }
            assertFalse(anyPressedAfterLastEvent, "the scene holds no button once the menu is gone")

            // And a click works again.
            scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            assertEquals(1, clicks, "the next click after the menu is a click")
        } finally {
            scene.close()
        }
    }
}
