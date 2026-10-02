package dioxus.compose.design

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the platform says about reading dates and times.
 *
 * None of this crosses the boundary. The Host sends a count of days or of minutes, and how
 * that count is shown, which day a week starts on, whether the clock reads to twelve or to
 * twenty four, and what the months are called, is read from the device the app is running
 * on. A format string arriving from the Host would make "follows the platform" a claim the
 * Renderer could not keep, so there is no property that carries one.
 */
data class PlatformFormats(
    /** 0 for Sunday through 6 for Saturday. */
    val firstDayOfWeek: Int,
    val uses24HourClock: Boolean,
    /** Twelve names, January first. */
    val monthNames: List<String>,
    /** Seven short names, Sunday first. */
    val weekdayInitials: List<String>,
) {
    /** The weekday columns of a calendar, in the order this platform lays them out. */
    fun weekdayColumns(): List<String> =
        List(7) { column -> weekdayInitials[(firstDayOfWeek + column) % 7] }

    /** How far into the week a day falls, counting from this platform's first day. */
    fun columnOf(dayOfWeek: Int): Int = (dayOfWeek - firstDayOfWeek + 7) % 7

    companion object {
        /**
         * The reading used where the platform cannot be asked, and the starting point every
         * platform implementation fills in.
         */
        val Fallback = PlatformFormats(
            firstDayOfWeek = 1,
            uses24HourClock = true,
            monthNames = listOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December",
            ),
            weekdayInitials = listOf("S", "M", "T", "W", "T", "F", "S"),
        )
    }
}

/**
 * The platform's reading, available to every picker.
 *
 * It is read once, at the composition root, the same way the design theme is. A test that
 * needs a particular calendar provides its own rather than changing the machine's settings.
 */
val LocalPlatformFormats = staticCompositionLocalOf { platformFormats() }
