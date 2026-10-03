package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.pointer.PointerIcon
import dev.darkpyonix.composerust.foundation.platformResizeCursor
import dev.darkpyonix.composerust.ui.installResizeCursor
import dev.darkpyonix.composerust.ui.platform.PointerShape
import dev.darkpyonix.composerust.ui.platform.pointerShapeOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The split pane's divider asks for the left and right resize pointer, and the one table the
 * AppKit, Win32 and X11 shells share turns it into the number each of them draws as its own
 * resize cursor.
 */
class ResizeCursorTest {
    @AfterTest
    fun forget() {
        platformResizeCursor = null
    }

    @Test
    fun fr15_2_12_the_divider_pointer_is_the_shells_resize_shape() {
        installResizeCursor()
        val cursor = assertNotNull(platformResizeCursor, "the desktop installed no resize pointer")
        assertEquals(PointerShape.RESIZE_LEFT_RIGHT, pointerShapeOf(cursor))
        assertEquals(PointerShape.HAND, pointerShapeOf(PointerIcon.Hand))
        assertEquals(PointerShape.ARROW, pointerShapeOf(PointerIcon.Default))
    }
}
