@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.design

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.GetLocaleInfoEx
import platform.windows.WCHARVar

/**
 * Month names, weekday initials, the first day of the week and the clock, as the reader's own
 * locale settings give them.
 *
 * Read from the user default locale rather than the system one: the person who changed the
 * format settings in Windows changed these, and a calendar that ignored them would be the one
 * window on their desktop that did.
 */
internal fun platformFormats(): PlatformFormats {
    val months = List(12) { index -> item(LOCALE_SMONTHNAME1 + index) }
    // Windows numbers its day names Monday first; the shared shape wants Sunday first.
    val weekdays = List(7) { index -> item(LOCALE_SABBREVDAYNAME1 + (index + 6) % 7) }
    return PlatformFormats(
        firstDayOfWeek = firstDayOfWeek(),
        uses24HourClock = uses24HourClock(item(LOCALE_STIMEFORMAT)),
        monthNames = if (months.all { it.isNotEmpty() }) months else PlatformFormats.Fallback.monthNames,
        weekdayInitials = if (weekdays.all { it.isNotEmpty() }) {
            weekdays.map { it.take(1) }
        } else {
            PlatformFormats.Fallback.weekdayInitials
        },
    )
}

/** One setting of the user's locale, or empty where it cannot be read. */
private fun item(which: Int): String = memScoped {
    val buffer = allocArray<WCHARVar>(ITEM_CHARACTERS)
    if (GetLocaleInfoEx(null, which.toUInt(), buffer, ITEM_CHARACTERS) > 0) buffer.toKString() else ""
}

/**
 * Which day this locale starts its weeks on, counting Sunday as 0. Windows answers a digit
 * counting Monday as 0, so the two are a day apart.
 */
private fun firstDayOfWeek(): Int {
    val day = item(LOCALE_IFIRSTDAYOFWEEK).toIntOrNull() ?: return PlatformFormats.Fallback.firstDayOfWeek
    if (day !in 0..6) return PlatformFormats.Fallback.firstDayOfWeek
    return (day + 1) % 7
}

/**
 * Whether the clock reads to twenty four, from the pattern this locale shows a time in. `H` is
 * the twenty four hour field and `h` the twelve hour one; the AM and PM marker is not the
 * question, since a locale can carry one and still write the hour to twenty four.
 */
private fun uses24HourClock(timeFormat: String): Boolean = when {
    'H' in timeFormat -> true
    'h' in timeFormat -> false
    else -> PlatformFormats.Fallback.uses24HourClock
}

// The LCTYPE values from winnls.h, named by number so this does not depend on which of them
// the platform bindings happen to carry.
private const val LOCALE_SMONTHNAME1 = 0x38
private const val LOCALE_SABBREVDAYNAME1 = 0x31
private const val LOCALE_IFIRSTDAYOFWEEK = 0x100C
private const val LOCALE_STIMEFORMAT = 0x1003

/** Long enough for any month name and any time pattern, its terminator included. */
private const val ITEM_CHARACTERS = 80
