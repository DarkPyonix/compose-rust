package dioxus.compose.design

/**
 * A date as a person reads it, for the days the protocol counts.
 *
 * The wire carries whole days since 1970-01-01 and nothing else: no time zone, no calendar,
 * no format string. Turning that count into a year, a month and a day is the Renderer's
 * work, done here without a date library so the same code runs on every target.
 */
data class CivilDate(val year: Int, val month: Int, val day: Int)

/**
 * Days since 1970-01-01 to a proleptic Gregorian date.
 *
 * The arithmetic is the standard shift of the year to start in March, which puts the leap
 * day at the end of a 400 year era and removes every special case for February.
 */
fun civilFromEpochDays(days: Long): CivilDate {
    val shifted = days + 719468
    val era = (if (shifted >= 0) shifted else shifted - 146096) / 146097
    val dayOfEra = shifted - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val shiftedMonth = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
    val month = if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9
    return CivilDate((if (month <= 2) year + 1 else year).toInt(), month.toInt(), day.toInt())
}

/** The inverse: a date back to the count of days the protocol carries. */
fun epochDaysFromCivil(date: CivilDate): Long {
    val year = (if (date.month <= 2) date.year - 1 else date.year).toLong()
    val era = (if (year >= 0) year else year - 399) / 400
    val yearOfEra = year - era * 400
    val shiftedMonth = if (date.month > 2) date.month - 3 else date.month + 9
    val dayOfYear = (153 * shiftedMonth + 2) / 5 + date.day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097 + dayOfEra - 719468
}

/** The day of the week, 0 for Sunday. 1970-01-01 was a Thursday, which is where the 4 is. */
fun dayOfWeek(epochDays: Long): Int = (((epochDays + 4) % 7 + 7) % 7).toInt()

fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> 30
}
