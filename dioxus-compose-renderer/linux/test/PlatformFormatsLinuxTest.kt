package dioxus.compose.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the C library said about this machine's calendar.
 *
 * The values cannot be asserted, because they are whatever locale the machine running this is set
 * to, and a test that demanded English would fail on a Korean desktop and the other way round.
 * The shape can be, and the shape is what every picker depends on: twelve months, seven weekday
 * columns, and a first day that is a day. A wrong count is a calendar that throws while it is
 * being laid out, and every one of those has come from a platform reading returning nothing and
 * the fallback not being taken.
 */
class PlatformFormatsLinuxTest {

    private val formats = platformFormats()

    @Test
    fun the_calendar_has_twelve_months_and_none_of_them_is_empty() {
        assertEquals(12, formats.monthNames.size)
        assertTrue(formats.monthNames.all { it.isNotBlank() }, "a month with no name draws as a gap")
    }

    @Test
    fun the_calendar_has_seven_weekday_columns_and_they_start_on_a_day() {
        assertEquals(7, formats.weekdayInitials.size)
        assertTrue(formats.weekdayInitials.all { it.length == 1 }, "a column heading is one letter")
        assertTrue(
            formats.firstDayOfWeek in 0..6,
            "the first day of the week is a day: ${formats.firstDayOfWeek}",
        )
        assertEquals(
            7,
            formats.weekdayColumns().size,
            "the columns are the seven days rotated to start on this locale's first",
        )
        assertEquals(
            formats.weekdayInitials.toSet(),
            formats.weekdayColumns().toSet(),
            "rotating the week leaves out no day and repeats none",
        )
    }

    /** This locale's own first day is the first column, whichever day that is. */
    @Test
    fun the_week_is_laid_out_from_this_locale_s_first_day() {
        assertEquals(0, formats.columnOf(formats.firstDayOfWeek))
        assertEquals(6, formats.columnOf((formats.firstDayOfWeek + 6) % 7))
    }
}
