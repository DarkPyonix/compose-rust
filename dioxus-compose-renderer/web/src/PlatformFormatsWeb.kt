@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dioxus.compose.design

/**
 * Reads the browser's own settings, through the same `Intl` tables the page's own date
 * inputs read.
 *
 * The shared picker code sees [PlatformFormats] and nothing else, so it does not know which
 * platform answered. Whether the clock reads to twelve comes from the hour cycle the
 * locale resolves to, which is what a user's language and region settings change.
 *
 * `Intl.Locale.getWeekInfo` is the only part a browser may not have; where it is missing the
 * first day of the week falls back to Monday rather than guessing from the language, because
 * a wrong first column silently shifts every date in a calendar by one.
 */
internal fun platformFormats(): PlatformFormats {
    val months = intlMonthNames().split('\n').filter { it.isNotEmpty() }
    val weekdays = intlWeekdayInitials().split('\n').filter { it.isNotEmpty() }
    val firstDay = intlFirstDayOfWeek()
    return PlatformFormats(
        firstDayOfWeek = if (firstDay in 0..6) firstDay else PlatformFormats.Fallback.firstDayOfWeek,
        uses24HourClock = intlUses24HourClock(),
        monthNames = if (months.size == 12) months else PlatformFormats.Fallback.monthNames,
        weekdayInitials = if (weekdays.size == 7) {
            weekdays.map { it.take(1) }
        } else {
            PlatformFormats.Fallback.weekdayInitials
        },
    )
}

/** Twelve month names in the page's locale, January first, newline separated. */
private fun intlMonthNames(): String =
    js(
        "Array.from({ length: 12 }, function (unused, month) {" +
            " return new Intl.DateTimeFormat(undefined, { month: 'long', timeZone: 'UTC' })" +
            " .format(new Date(Date.UTC(2001, month, 15))); }).join('\\n')",
    )

/**
 * Seven narrow weekday names, Sunday first, newline separated.
 *
 * 7 January 2001 was a Sunday, and the dates are formatted in UTC so that a page west of
 * Greenwich does not see every name shifted back by a day.
 */
private fun intlWeekdayInitials(): String =
    js(
        "Array.from({ length: 7 }, function (unused, day) {" +
            " return new Intl.DateTimeFormat(undefined, { weekday: 'narrow', timeZone: 'UTC' })" +
            " .format(new Date(Date.UTC(2001, 0, 7 + day))); }).join('\\n')",
    )

/** 0 for Sunday through 6 for Saturday, or -1 where the browser cannot say. */
private fun intlFirstDayOfWeek(): Int =
    js(
        "(function () { try {" +
            " var tag = new Intl.DateTimeFormat().resolvedOptions().locale;" +
            " var locale = new Intl.Locale(tag);" +
            " var info = typeof locale.getWeekInfo === 'function' ?" +
            " locale.getWeekInfo() : locale.weekInfo;" +
            " return info && info.firstDay ? info.firstDay % 7 : -1;" +
            " } catch (error) { return -1; } })()",
    )

/** Whether the locale's own hour format runs to twenty four. */
private fun intlUses24HourClock(): Boolean =
    js(
        "(function () {" +
            " var options = new Intl.DateTimeFormat(undefined, { hour: 'numeric' })" +
            " .resolvedOptions();" +
            " return options.hourCycle === 'h23' || options.hourCycle === 'h24' ||" +
            " options.hour12 === false; })()",
    )
