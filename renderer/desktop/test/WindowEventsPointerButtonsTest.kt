package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import dev.darkpyonix.composerust.ui.platform.pointerButtonsOf
import org.thisisthepy.compose.window.WindowEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowEventsPointerButtonsTest {
    @Test
    fun fr19_8_pointer_buttons_of_maps_middle_back_and_forward_buttons() {
        val middle = pointerButtonsOf(4)
        assertTrue(middle.isTertiaryPressed)
        assertFalse(middle.isPrimaryPressed)
        assertFalse(middle.isSecondaryPressed)

        val back = pointerButtonsOf(8)
        assertTrue(back.isBackPressed)
        assertFalse(back.isForwardPressed)

        val forward = pointerButtonsOf(16)
        assertTrue(forward.isForwardPressed)
        assertFalse(forward.isBackPressed)

        val chord = pointerButtonsOf(1 or WindowEvent.SECONDARY_BUTTON or 4 or 8 or 16)
        assertTrue(chord.isPrimaryPressed)
        assertTrue(chord.isSecondaryPressed)
        assertTrue(chord.isTertiaryPressed)
        assertTrue(chord.isBackPressed)
        assertTrue(chord.isForwardPressed)
    }
}
