package dioxus.compose.design

import platform.Foundation.NSCalendar
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

/**
 * Reads iOS's own settings, through the same names the system's own pickers read.
 *
 * The shared picker code sees [PlatformFormats] and nothing else, so it does not know which
 * platform answered. Whether the clock reads to twelve comes from the pattern the locale
 * would use for a short time, which is what the Settings app's 24 hour switch changes.
 */
internal fun platformFormats(): PlatformFormats {
    val locale = NSLocale.currentLocale
    val calendar = NSCalendar.currentCalendar
    val formatter = NSDateFormatter()
    formatter.locale = locale
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, locale).orEmpty()
    val months = formatter.standaloneMonthSymbols.mapNotNull { it as? String }
    val weekdays = formatter.shortWeekdaySymbols.mapNotNull { it as? String }
    return PlatformFormats(
        // Foundation counts Sunday as 1; the shared side counts it as 0.
        firstDayOfWeek = (calendar.firstWeekday.toInt() - 1).coerceIn(0, 6),
        uses24HourClock = !pattern.contains('a') && !pattern.contains('h'),
        monthNames = if (months.size == 12) months else PlatformFormats.Fallback.monthNames,
        weekdayInitials = if (weekdays.size == 7) {
            weekdays.map { it.take(1) }
        } else {
            PlatformFormats.Fallback.weekdayInitials
        },
    )
}
