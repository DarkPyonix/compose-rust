package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dioxus.compose.design.CivilDate
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.DatePresentation
import dioxus.compose.design.LocalPlatformFormats
import dioxus.compose.design.PlatformFormats
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.civilFromEpochDays
import dioxus.compose.design.dayOfWeek
import dioxus.compose.design.daysInMonth
import dioxus.compose.design.epochDaysFromCivil
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.TypeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.nodeTestTag

/** The earliest and latest day this picker will show, about four centuries either way. */
private const val DAY_FLOOR = -146097L
private const val DAY_CEILING = 146097L

/** The test tag of whatever a date picker opens over the page. */
fun datePopupTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-date-popup"

/**
 * A date, in whole days since 1970-01-01.
 *
 * The node carries a value, the ends of the range and a change handler. It does not carry a
 * way of picking, and there is no property that could: Material lays a month out as a grid,
 * Cupertino spins wheels, Fluent opens a calendar over the page, and which of those the user
 * gets is the design system's decision alone. That is why the same Host code feels native on
 * each of the three rather than feeling like one of them everywhere.
 *
 * The time zone and the locale never cross the boundary either. A count of days has no time
 * zone to lose, and the month names, the first day of the week and the order of the fields
 * are read from the platform here.
 */
@Composable
internal fun HostDatePicker(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val range = node.pickerRange(DAY_FLOOR, DAY_CEILING)
    val picked = rememberPickerValue(node, dispatcher, range::coerce)
    val enabled = node.pickerEnabled()
    val formats = LocalPlatformFormats.current
    when (theme.rules.pickers.date) {
        DatePresentation.CalendarGrid -> CalendarMonth(
            days = picked.value,
            range = range,
            enabled = enabled,
            theme = theme,
            formats = formats,
            modifier = modifier,
            onPick = picked.set,
        )

        DatePresentation.Wheel -> DateWheels(
            days = picked.value,
            range = range,
            enabled = enabled,
            theme = theme,
            formats = formats,
            modifier = modifier,
            onPick = picked.set,
        )

        DatePresentation.CalendarFlyout -> CalendarFlyout(
            nodeId = node.id,
            days = picked.value,
            range = range,
            enabled = enabled,
            theme = theme,
            formats = formats,
            modifier = modifier,
            onPick = picked.set,
        )
    }
}

/** The date as the platform's month names read it, which is what a closed picker shows. */
private fun readableDate(days: Long, formats: PlatformFormats): String {
    val date = civilFromEpochDays(days)
    return "${date.day} ${formats.monthNames[date.month - 1]} ${date.year}"
}

/**
 * A month laid out as a grid of days.
 *
 * The columns start on the day the platform starts its weeks on, so the same month is laid
 * out one way where weeks start on Monday and another where they start on Sunday.
 */
@Composable
private fun CalendarMonth(
    days: Long,
    range: PickerRange,
    enabled: Boolean,
    theme: ResolvedTheme,
    formats: PlatformFormats,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    val selected = civilFromEpochDays(days)
    var shown by remember(days) { mutableStateOf(CivilDate(selected.year, selected.month, 1)) }
    LaunchedEffect(days) { shown = CivilDate(selected.year, selected.month, 1) }
    val style = theme.rules.container(ContainerRole.Surface, theme)

    Column(
        modifier
            .clip(style.shape)
            .background(theme.color(ColorRole.Surface), style.shape)
            .padding(theme.space(SpaceRole.Sm)),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("<", enabled, theme) { shown = shiftMonth(shown, -1) }
            BasicText(
                text = "${formats.monthNames[shown.month - 1]} ${shown.year}",
                style = theme.pickerTextStyle(TypeRole.Title),
            )
            StepButton(">", enabled, theme) { shown = shiftMonth(shown, 1) }
        }
        WeekdayHeader(formats.weekdayColumns(), theme)
        val first = epochDaysFromCivil(CivilDate(shown.year, shown.month, 1))
        val lead = formats.columnOf(dayOfWeek(first))
        val length = daysInMonth(shown.year, shown.month)
        val rows = (lead + length + 6) / 7
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val dayOfMonth = row * 7 + column - lead + 1
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayOfMonth in 1..length) {
                            val value = first + (dayOfMonth - 1)
                            DayCell(
                                dayOfMonth = dayOfMonth,
                                value = value,
                                selected = value == days,
                                enabled = enabled && value in range.min..range.max,
                                theme = theme,
                                onPick = onPick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    dayOfMonth: Int,
    value: Long,
    selected: Boolean,
    enabled: Boolean,
    theme: ResolvedTheme,
    onPick: (Long) -> Unit,
) {
    val content = when {
        selected -> theme.color(ColorRole.OnPrimary)
        enabled -> theme.color(ColorRole.OnSurface)
        else -> theme.color(ColorRole.OnSurfaceVariant)
    }
    Box(
        Modifier
            .width(36.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (selected) theme.color(ColorRole.Primary) else Color.Transparent, CircleShape)
            .clickable(enabled = enabled) { onPick(value) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = dayOfMonth.toString(),
            style = theme.pickerTextStyle(
                role = if (selected) TypeRole.BodyStrong else TypeRole.Body,
                color = content,
            ),
        )
    }
}

private fun shiftMonth(date: CivilDate, by: Int): CivilDate {
    val zeroBased = (date.year * 12 + (date.month - 1)) + by
    return CivilDate(zeroBased / 12, zeroBased % 12 + 1, 1)
}

/**
 * Three wheels, one per field, spun to the date.
 *
 * A wheel is operated rather than read: nothing here lays out a month, and a day two months
 * away is reached by spinning past the months in between.
 */
@Composable
private fun DateWheels(
    days: Long,
    range: PickerRange,
    enabled: Boolean,
    theme: ResolvedTheme,
    formats: PlatformFormats,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    val date = civilFromEpochDays(days)
    val firstYear = civilFromEpochDays(range.min).year
    val lastYear = civilFromEpochDays(range.max).year
    val years = (firstYear..lastYear).toList()
    val lengthOfMonth = daysInMonth(date.year, date.month)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs))) {
        PickerWheel(
            labels = (1..lengthOfMonth).map(Int::toString),
            selectedIndex = date.day - 1,
            enabled = enabled,
            theme = theme,
            modifier = Modifier.weight(1f),
        ) { index -> onPick(epochDaysFromCivil(CivilDate(date.year, date.month, index + 1))) }

        PickerWheel(
            labels = formats.monthNames,
            selectedIndex = date.month - 1,
            enabled = enabled,
            theme = theme,
            modifier = Modifier.weight(2f),
        ) { index ->
            val month = index + 1
            val day = date.day.coerceAtMost(daysInMonth(date.year, month))
            onPick(epochDaysFromCivil(CivilDate(date.year, month, day)))
        }

        PickerWheel(
            labels = years.map(Int::toString),
            selectedIndex = years.indexOf(date.year).coerceAtLeast(0),
            enabled = enabled,
            theme = theme,
            modifier = Modifier.weight(1f),
        ) { index ->
            val year = years[index]
            val day = date.day.coerceAtMost(daysInMonth(year, date.month))
            onPick(epochDaysFromCivil(CivilDate(year, date.month, day)))
        }
    }
}

/**
 * A field that opens a calendar over the page.
 *
 * The resting form takes no more room than a line of text, and the month appears above the
 * page rather than in it. That is the difference from the grid: the same month, but the page
 * is never rearranged to make room for it.
 */
@Composable
private fun CalendarFlyout(
    nodeId: Int,
    days: Long,
    range: PickerRange,
    enabled: Boolean,
    theme: ResolvedTheme,
    formats: PlatformFormats,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    var open by remember(nodeId) { mutableStateOf(false) }
    val style = theme.rules.container(ContainerRole.Menu, theme)
    Box(modifier) {
        PickerField(
            label = readableDate(days, formats),
            enabled = enabled,
            theme = theme,
            trailing = "▾",
        ) { open = true }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier
                        .testTag(datePopupTestTag(nodeId))
                        .clip(style.shape)
                        .background(style.container, style.shape)
                        .border(style.borderWidth, style.borderColor, style.shape)
                        .padding(theme.space(SpaceRole.Xs)),
                ) {
                    CalendarMonth(
                        days = days,
                        range = range,
                        enabled = enabled,
                        theme = theme,
                        formats = formats,
                        modifier = Modifier,
                        onPick = { value ->
                            onPick(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
