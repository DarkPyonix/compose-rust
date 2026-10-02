package dioxus.compose.test

import dioxus.compose.foundation.rowAlignedRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A grid asks for whole rows.
 *
 * The windowing protocol is the list's and stays the list's: a range of items, and the
 * Host materialises exactly that range. A grid only lays out whole rows, so a range that
 * stopped halfway through one would leave the Renderer holding a row it cannot fill.
 * Rounding is the only thing a grid adds, and it is done where the column count is known.
 */
class LazyGridTest {

    @Test
    fun fr25_a_window_grows_to_the_rows_it_touches() {
        // Three items from index 5, four columns: 5, 6 and 7 are all in the row that
        // holds 4 through 7, so the window grows backwards to 4 and takes that row whole.
        assertEquals(4 to 4, rowAlignedRange(start = 5, count = 3, columns = 4, itemCount = 100))
        // One more item reaches into the next row, and both rows come whole.
        assertEquals(4 to 8, rowAlignedRange(start = 5, count = 4, columns = 4, itemCount = 100))
    }

    @Test
    fun fr25_the_last_row_may_be_short() {
        // Ten items across four columns is two full rows and a half. Asking past the end
        // would be asking for items that are not there.
        assertEquals(8 to 2, rowAlignedRange(start = 9, count = 1, columns = 4, itemCount = 10))
    }

    @Test
    fun fr25_one_column_is_a_list_and_changes_nothing() {
        assertEquals(5 to 3, rowAlignedRange(start = 5, count = 3, columns = 1, itemCount = 100))
    }

    @Test
    fun fr25_an_empty_window_stays_empty() {
        // A grid with nothing on screen asks for nothing, and rounding must not turn that
        // into a request for one row.
        assertEquals(0 to 0, rowAlignedRange(start = 0, count = 0, columns = 4, itemCount = 100))
    }

    @Test
    fun fr25_a_window_already_on_row_boundaries_is_left_alone() {
        assertEquals(8 to 8, rowAlignedRange(start = 8, count = 8, columns = 4, itemCount = 100))
    }
}
