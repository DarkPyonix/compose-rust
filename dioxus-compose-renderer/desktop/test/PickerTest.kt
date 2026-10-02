package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.height
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dioxus.compose.design.CivilDate
import dioxus.compose.design.LocalPlatformFormats
import dioxus.compose.design.PlatformFormats
import dioxus.compose.design.civilFromEpochDays
import dioxus.compose.design.dayOfWeek
import dioxus.compose.design.epochDaysFromCivil
import dioxus.compose.foundation.readableTime
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.nodeTestTag

private const val PICKER = 1
private const val HANDLER = 55L

/** 2024-03-15, a Friday, as the count of days the protocol carries. */
private const val MID_MARCH = 19797L

/** A calendar that does not move with the machine the test runs on. */
private val FIXED = PlatformFormats(
    firstDayOfWeek = 1,
    uses24HourClock = true,
    monthNames = PlatformFormats.Fallback.monthNames,
    weekdayInitials = PlatformFormats.Fallback.weekdayInitials,
)

private fun pickerTree(
    widget: WidgetKind,
    value: Long,
    system: DesignSystem,
): List<Mutation> = listOf(
    Mutation.SetTheme(Theme(system, DesignSystem.Material3, ColorScheme.Light, false)),
    Mutation.Create(PICKER, widget),
    Mutation.SetProp(PICKER, PropertyKind.Value, PropertyValue.Integer(value)),
    Mutation.SetProp(PICKER, PropertyKind.OnValueChange, PropertyValue.Integer(HANDLER)),
)

/**
 * The pickers carry a value, a range and a change event. How the value is picked is the
 * design system's decision, and these tests are what keeps it that way.
 */
@OptIn(ExperimentalTestApi::class)
class PickerTest {
    /**
     * The sharpest test of the role based design. Three systems that merely restyled the
     * same calendar would draw the same thing three times, so this compares what is
     * actually on screen and fails if any two agree.
     */
    @Test
    fun fr15_2_1_a_date_picker_is_operated_differently_in_each_design_system() {
        val heights = DesignSystem.entries.associateWith { system ->
            var height = 0f
            runComposeUiTest {
                val connection = FakeHostConnection(
                    pickerTree(WidgetKind.DatePicker, MID_MARCH, system),
                )
                setContent {
                    CompositionLocalProvider(LocalPlatformFormats provides FIXED) {
                        DioxusContent(rememberDioxusHost(connection))
                    }
                }
                waitForIdle()
                onNodeWithTag(nodeTestTag(PICKER)).assertIsDisplayed()
                height = onNodeWithTag(nodeTestTag(PICKER))
                    .getUnclippedBoundsInRoot()
                    .height
                    .value
            }
            height
        }

        assertEquals(
            heights.size,
            heights.values.distinct().size,
            "a date is picked differently under each system, so no two can look alike: $heights",
        )
    }

    /** The grid lays a month out. A day is chosen by naming it, not by spinning to it. */
    @Test
    fun fr15_2_1_material_lays_the_month_out_as_a_grid() = runComposeUiTest {
        val connection = FakeHostConnection(
            pickerTree(WidgetKind.DatePicker, MID_MARCH, DesignSystem.Material3),
        )
        setContent {
            CompositionLocalProvider(LocalPlatformFormats provides FIXED) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()

        onNodeWithText("March 2024").assertIsDisplayed()
        onNodeWithText("15").assertIsDisplayed()
    }

    /** Fluent keeps the page as it was and opens the month over it. */
    @Test
    fun fr15_2_1_fluent_opens_the_calendar_over_the_page() = runComposeUiTest {
        val connection = FakeHostConnection(
            pickerTree(WidgetKind.DatePicker, MID_MARCH, DesignSystem.Fluent),
        )
        setContent {
            CompositionLocalProvider(LocalPlatformFormats provides FIXED) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()

        // Closed, the page shows a line of text and no month at all.
        onNodeWithText("15 March 2024").assertIsDisplayed()
        assertTrue(
            onAllNodesWithText("March 2024").fetchSemanticsNodes().isEmpty(),
            "the month is not laid out until the flyout is opened",
        )
    }

    /** Picking reports the value as a count of days, once, and nothing else. */
    @Test
    fun fr15_2_1_picking_a_date_reports_epoch_days() = runComposeUiTest {
        val connection = FakeHostConnection(
            pickerTree(WidgetKind.DatePicker, MID_MARCH, DesignSystem.Material3),
        )
        setContent {
            CompositionLocalProvider(LocalPlatformFormats provides FIXED) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()

        onNodeWithText("20").performClick()
        waitForIdle()

        val changes = connection.events.filterIsInstance<HostEvent.ValueChanged>()
        assertEquals(1, changes.size, "one pick is one event")
        assertEquals(PICKER, changes.single().nodeId)
        assertEquals(HANDLER, changes.single().handlerId)
        assertEquals((MID_MARCH + 5).toDouble(), changes.single().value, "2024-03-20 is five days later")
    }

    /**
     * The clock's reading is the platform's, and the value on the wire is the same either
     * way. A Host that wanted to choose would have to send a format string, and there is no
     * property that carries one.
     */
    @Test
    fun fr15_2_1_the_clock_reading_comes_from_the_platform_not_the_host() {
        val minutes = 13 * 60L + 5
        val twentyFour = FIXED
        val twelve = FIXED.copy(uses24HourClock = false)

        assertEquals("13:05", readableTime(minutes, twentyFour))
        assertEquals("1:05 PM", readableTime(minutes, twelve))
        assertEquals("12:00 AM", readableTime(0L, twelve))
        assertEquals("00:00", readableTime(0L, twentyFour))
    }

    /** The day a week starts on is the platform's too, and it moves the whole grid. */
    @Test
    fun fr15_2_1_the_first_day_of_the_week_comes_from_the_platform() {
        val mondayFirst = FIXED
        val sundayFirst = FIXED.copy(firstDayOfWeek = 0)

        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), mondayFirst.weekdayColumns())
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), sundayFirst.weekdayColumns())
        // 2024-03-15 was a Friday, which is the fifth column of a week that starts on Monday
        // and the sixth of one that starts on Sunday.
        assertEquals(4, mondayFirst.columnOf(dayOfWeek(MID_MARCH)))
        assertEquals(5, sundayFirst.columnOf(dayOfWeek(MID_MARCH)))
    }

    /**
     * There is no property that says how to pick. If one were added to the schema without a
     * decision behind it, this is where it would show up.
     */
    @Test
    fun fr15_2_1_a_picker_takes_only_a_value_a_range_and_its_events() {
        val allowed = setOf(
            PropertyKind.Value,
            PropertyKind.Min,
            PropertyKind.Max,
            PropertyKind.Enabled,
            PropertyKind.ItemKey,
            PropertyKind.OnClick,
            PropertyKind.OnValueChange,
            PropertyKind.OnSubmit,
            PropertyKind.OnFocusLost,
            PropertyKind.OnKeyDown,
            PropertyKind.OnRangeRequested,
            PropertyKind.OnDismiss,
        )
        listOf(WidgetKind.DatePicker, WidgetKind.TimePicker).forEach { widget ->
            PropertyKind.entries.forEach { property ->
                assertEquals(
                    property in allowed,
                    NodeTable.supportsProperty(widget, property),
                    "$widget and $property",
                )
            }
        }
    }

    /** A dropdown reports the position the user landed on, and the options are its children. */
    @Test
    fun fr15_2_dropdown_reports_the_position_chosen() = runComposeUiTest {
        val records = listOf(
            Mutation.SetTheme(
                Theme(DesignSystem.Cupertino, DesignSystem.Material3, ColorScheme.Light, false),
            ),
            Mutation.Create(PICKER, WidgetKind.Dropdown),
            Mutation.SetProp(PICKER, PropertyKind.OnValueChange, PropertyValue.Integer(HANDLER)),
        ) + listOf("first", "second", "third").flatMapIndexed { index, label ->
            val child = index + 2
            listOf(
                Mutation.Create(child, WidgetKind.Text),
                Mutation.SetProp(child, PropertyKind.Text, PropertyValue.Text(label)),
                Mutation.Insert(PICKER, child, index),
            )
        }
        val connection = FakeHostConnection(records)
        setContent {
            CompositionLocalProvider(LocalPlatformFormats provides FIXED) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()

        // Cupertino spins a wheel, so every option is on screen with no menu to open.
        onNodeWithText("third").performClick()
        waitForIdle()

        val changes = connection.events.filterIsInstance<HostEvent.ValueChanged>()
        assertEquals(1, changes.size)
        assertEquals(2.0, changes.single().value, "the third option is position two")
    }

    /** A time is minutes since midnight, and the range holds the value inside the day. */
    @Test
    fun fr15_2_1_a_time_outside_the_range_is_pulled_back_into_it() = runComposeUiTest {
        val records = pickerTree(WidgetKind.TimePicker, 9 * 60L, DesignSystem.Fluent) + listOf(
            Mutation.SetProp(PICKER, PropertyKind.Min, PropertyValue.Integer(10 * 60L)),
            Mutation.SetProp(PICKER, PropertyKind.Max, PropertyValue.Integer(12 * 60L)),
        )
        val connection = FakeHostConnection(records)
        setContent {
            CompositionLocalProvider(LocalPlatformFormats provides FIXED) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()

        onNodeWithText("10:00").assertIsDisplayed()
    }
}

/** The calendar arithmetic the pickers stand on, with no date library underneath it. */
class CivilDateTest {
    @Test
    fun fr15_2_1_epoch_days_and_civil_dates_are_inverses() {
        assertEquals(CivilDate(1970, 1, 1), civilFromEpochDays(0))
        assertEquals(CivilDate(2024, 3, 15), civilFromEpochDays(MID_MARCH))
        assertEquals(0L, epochDaysFromCivil(CivilDate(1970, 1, 1)))
        assertEquals(MID_MARCH, epochDaysFromCivil(CivilDate(2024, 3, 15)))

        var day = -40000L
        while (day < 40000L) {
            assertEquals(day, epochDaysFromCivil(civilFromEpochDays(day)))
            day += 97
        }
    }

    @Test
    fun fr15_2_1_the_day_of_the_week_is_counted_from_a_known_thursday() {
        assertEquals(4, dayOfWeek(0), "1970-01-01 was a Thursday")
        assertEquals(5, dayOfWeek(MID_MARCH), "2024-03-15 was a Friday")
        assertFalse(dayOfWeek(-1) < 0, "days before the epoch still land inside the week")
        assertEquals(3, dayOfWeek(-1), "1969-12-31 was a Wednesday")
    }
}
