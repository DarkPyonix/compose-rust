package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.CursorKeywordIcon
import dev.darkpyonix.composerust.foundation.cursorIconOf
import dev.darkpyonix.composerust.ui.platform.PointerShape
import dev.darkpyonix.composerust.ui.platform.nearestPointerShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.CursorShape

/**
 * FR-47.1: a node's `Cursor` reaches the window as the fork's [CursorShape], whose table holds
 * every keyword's closest shape on Windows, X11 and AppKit. A window that cannot set a shape by
 * keyword yet takes the nearest of the few shapes every desktop window here knows.
 */
class CursorKeywordTest {

    @Test
    fun fr47_every_cursor_value_has_a_platform_mapping() {
        // Auto=0 .. None=35, numbered as the wire numbers them.
        for (value in 1..35) {
            val icon = cursorIconOf(value.toLong())
            assertTrue(icon is CursorKeywordIcon, "value $value")
            val shape = CursorShape.fromWire(icon.keyword)
            assertEquals(value, shape?.ordinal, "value $value is the fork's shape of the same number")
        }
        assertEquals(CursorShape.Pointer, CursorShape.fromWire((cursorIconOf(2) as CursorKeywordIcon).keyword))
        assertEquals(CursorShape.NwseResize, CursorShape.fromWire((cursorIconOf(34) as CursorKeywordIcon).keyword))
        assertEquals(CursorShape.None, CursorShape.fromWire((cursorIconOf(35) as CursorKeywordIcon).keyword))
    }

    @Test
    fun fr47_auto_leaves_the_cursor_to_the_widget() {
        assertNull(cursorIconOf(0))
        assertNull(cursorIconOf(null))
    }

    @Test
    fun nfr7_a_cursor_value_past_the_list_sets_nothing() {
        assertNull(cursorIconOf(36))
        assertNull(cursorIconOf(-1))
    }

    @Test
    fun fr47_a_window_without_keywords_takes_the_nearest_shape() {
        assertEquals(PointerShape.HAND, nearestPointerShape(CursorShape.Pointer))
        assertEquals(PointerShape.HAND, nearestPointerShape(CursorShape.Grab))
        assertEquals(PointerShape.TEXT, nearestPointerShape(CursorShape.Text))
        assertEquals(PointerShape.CROSSHAIR, nearestPointerShape(CursorShape.Crosshair))
        assertEquals(PointerShape.RESIZE_LEFT_RIGHT, nearestPointerShape(CursorShape.ColResize))
        assertEquals(PointerShape.RESIZE_UP_DOWN, nearestPointerShape(CursorShape.RowResize))
        assertEquals(PointerShape.ARROW, nearestPointerShape(CursorShape.Wait))
        for (shape in CursorShape.entries) nearestPointerShape(shape)
    }
}
