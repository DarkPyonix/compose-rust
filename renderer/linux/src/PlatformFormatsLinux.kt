@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.design

import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import langinfo.dxc_langinfo
import langinfo.dxc_langinfo_abbreviated_day
import langinfo.dxc_langinfo_first_weekday
import langinfo.dxc_langinfo_month
import langinfo.dxc_langinfo_time_format
import platform.posix.LC_ALL
import platform.posix.setlocale

/**
 * Reads the C library's own settings, through the same names every program on this desktop
 * reads them by.
 *
 * The shared picker code sees [PlatformFormats] and nothing else, so it does not know which
 * platform answered. On Linux there is no framework to ask: what a month is called, which day
 * a week starts on and whether the clock reads to twelve comes from the locale, and
 * `nl_langinfo` is how a process asks the locale.
 *
 * Read once, on first use, because the locale of a running process does not change and every
 * call into the C library for it would otherwise be on a frame's path.
 */
internal fun platformFormats(): PlatformFormats {
    // Said before anything is read. A C program starts in the "C" locale whatever the
    // environment says, and in that locale every answer below is the American English one: a
    // window would show English month names on a Korean desktop and nobody would see why.
    setlocale(LC_ALL, "")

    val months = List(12) { index -> item(dxc_langinfo_month + index) }
    val weekdays = List(7) { index -> item(dxc_langinfo_abbreviated_day + index) }
    val timeFormat = item(dxc_langinfo_time_format)

    return PlatformFormats(
        firstDayOfWeek = firstDayOfWeek(),
        uses24HourClock = uses24HourClock(timeFormat),
        monthNames = if (months.all { it.isNotEmpty() }) {
            months
        } else {
            PlatformFormats.Fallback.monthNames
        },
        weekdayInitials = if (weekdays.all { it.isNotEmpty() }) {
            weekdays.map { it.take(1) }
        } else {
            PlatformFormats.Fallback.weekdayInitials
        },
    )
}

private fun item(which: Int): String = dxc_langinfo(which)?.toKString().orEmpty()

/**
 * Which day this locale starts its weeks on, counting Sunday as 0.
 *
 * The answer is a single byte holding 1 for Sunday through 7 for Saturday. It is a GNU
 * extension: POSIX has no way to ask, and every calendar on the desktop needs it, so glibc
 * carries it and the locale definitions all set it. A library that does not have it leaves the
 * shared fallback's Monday, which is what most of the world uses and what a calendar with no
 * answer should look like rather than empty.
 */
private fun firstDayOfWeek(): Int {
    val answer = item(dxc_langinfo_first_weekday)
    val day = answer.firstOrNull()?.code ?: return PlatformFormats.Fallback.firstDayOfWeek
    if (day < 1 || day > 7) return PlatformFormats.Fallback.firstDayOfWeek
    return day - 1
}

/**
 * Whether the clock reads to twenty four, taken from the pattern this locale shows a time in.
 *
 * The AM and PM words are not the question: a Korean locale has both and still writes the hour
 * to twenty four. What settles it is which hour field the pattern uses. `%H` and `%k` are the
 * twenty four hour ones, `%I`, `%l` and the `%r` that stands for a whole twelve hour time are
 * the others, and a pattern with none of them is read as twenty four, which is what the shared
 * fallback says and what a locale nobody has described looks like.
 */
private fun uses24HourClock(timeFormat: String): Boolean = when {
    timeFormat.contains("%H") || timeFormat.contains("%k") -> true
    timeFormat.contains("%I") || timeFormat.contains("%l") ||
        timeFormat.contains("%r") || timeFormat.contains("%p") -> false
    else -> PlatformFormats.Fallback.uses24HourClock
}

/**
 * Which thread this is, as something that compares equal only to itself on the same thread.
 *
 * A measure call is only answered on the thread the composition runs on, and a POSIX
 * thread id is that identity for as long as the thread lives.
 */
internal fun currentThreadToken(): Any = platform.posix.pthread_self()
