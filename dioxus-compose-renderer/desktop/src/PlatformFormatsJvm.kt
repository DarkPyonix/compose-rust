package dioxus.compose.design

import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reads the desktop's own settings: the locale's calendar and its clock.
 *
 * This is the half of the picker layer that cannot be shared, because there is no portable
 * way to ask a machine what a week starts on. The shared pickers see only [PlatformFormats].
 *
 * Whether the clock reads to twelve is decided by looking at the pattern the locale's own
 * short time format uses, which is the only honest way to ask: a list of locales kept here
 * would be out of date the first time a region changed its mind.
 */
internal fun platformFormats(): PlatformFormats {
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val calendar = Calendar.getInstance(locale)
    val symbols = DateFormatSymbols.getInstance(locale)
    val months = symbols.months.filter { it.isNotEmpty() }.take(12)
    val weekdays = symbols.shortWeekdays.filter { it.isNotEmpty() }.take(7)
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)
        ?.toPattern()
        .orEmpty()
    return PlatformFormats(
        // Calendar counts Sunday as 1; the shared side counts it as 0.
        firstDayOfWeek = (calendar.firstDayOfWeek - 1).coerceIn(0, 6),
        uses24HourClock = !pattern.contains('a') && !pattern.contains('h'),
        monthNames = if (months.size == 12) months else PlatformFormats.Fallback.monthNames,
        weekdayInitials = if (weekdays.size == 7) {
            weekdays.map { it.take(1) }
        } else {
            PlatformFormats.Fallback.weekdayInitials
        },
    )
}
