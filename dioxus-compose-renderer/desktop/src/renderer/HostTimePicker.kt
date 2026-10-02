package dioxus.compose.foundation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.LocalPlatformFormats
import dioxus.compose.design.PlatformFormats
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.TimePresentation
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.TypeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MINUTES_IN_A_DAY = 24 * 60L
private const val HOURS_ON_A_DIAL = 12

/**
 * A time of day, in minutes since midnight.
 *
 * Like the date, the node carries a value, a range and a change handler, and says nothing
 * about how the time should be set. Material puts a hand on a dial, Cupertino spins wheels,
 * Fluent steps a field, and no Host property can ask for one of them.
 *
 * Whether the clock reads to twelve or to twenty four is the platform's setting, read here.
 * The value on the wire is the same count of minutes either way, so changing that setting
 * changes what is shown and nothing that is sent.
 */
@Composable
internal fun HostTimePicker(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val range = node.pickerRange(0L, MINUTES_IN_A_DAY - 1)
    val picked = rememberPickerValue(node, dispatcher, range::coerce)
    val enabled = node.pickerEnabled()
    val formats = LocalPlatformFormats.current
    when (theme.rules.pickers.time) {
        TimePresentation.Dial ->
            TimeDial(picked.value, enabled, theme, formats, modifier, picked.set)

        TimePresentation.Wheel ->
            TimeWheels(picked.value, enabled, theme, formats, modifier, picked.set)

        TimePresentation.Stepper ->
            TimeStepper(picked.value, range, enabled, theme, formats, modifier, picked.set)
    }
}

/** The time as this platform reads it, to twelve hours or to twenty four. */
internal fun readableTime(minutes: Long, formats: PlatformFormats): String {
    val hour = (minutes / 60).toInt()
    val minute = (minutes % 60).toInt()
    val padded = minute.toString().padStart(2, '0')
    if (formats.uses24HourClock) return "${hour.toString().padStart(2, '0')}:$padded"
    val onDial = if (hour % 12 == 0) 12 else hour % 12
    return "$onDial:$padded ${if (hour < 12) "AM" else "PM"}"
}

/**
 * A clock face. Tapping an hour moves the hand to it.
 *
 * The minute is set by the row of five minute marks under the face, because a dial that
 * asked for both on one face would need a second pass the Host has no way to ask for.
 */
@Composable
private fun TimeDial(
    minutes: Long,
    enabled: Boolean,
    theme: ResolvedTheme,
    formats: PlatformFormats,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    val hour = (minutes / 60).toInt()
    val minute = (minutes % 60).toInt()
    val measurer = rememberTextMeasurer()
    val face = theme.color(ColorRole.SurfaceVariant)
    val hand = theme.color(ColorRole.Primary)
    val numerals = theme.color(ColorRole.OnSurfaceVariant)
    val token = theme.type(TypeRole.Label)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(readableTime(minutes, formats), style = theme.pickerTextStyle(TypeRole.Title))
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(enabled, minutes) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { tap ->
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val angle = atan2(tap.y - centre.y, tap.x - centre.x) + PI / 2
                        val step = ((angle / (2 * PI) * HOURS_ON_A_DIAL).roundToInt() + HOURS_ON_A_DIAL) %
                            HOURS_ON_A_DIAL
                        // The tap picks a position on the face, which is one of twelve. The
                        // half of the day stays where it was: a dial says which hour, not
                        // which morning.
                        val picked = (hour / 12) * 12 + step
                        onPick(picked * 60L + minute)
                    }
                },
        ) {
            val radius = minOf(size.width, size.height) / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(face, radius, centre)
            repeat(HOURS_ON_A_DIAL) { position ->
                val angle = position * 2.0 * PI / HOURS_ON_A_DIAL - PI / 2
                val at = Offset(
                    centre.x + (radius * 0.78f) * cos(angle).toFloat(),
                    centre.y + (radius * 0.78f) * sin(angle).toFloat(),
                )
                val label = if (position == 0) "12" else position.toString()
                val laid = measurer.measure(label, TextStyle(fontSize = token.size.sp))
                drawText(
                    textLayoutResult = laid,
                    color = numerals,
                    topLeft = Offset(
                        at.x - laid.size.width / 2f,
                        at.y - laid.size.height / 2f,
                    ),
                )
            }
            val handAngle = (hour % HOURS_ON_A_DIAL) * 2.0 * PI / HOURS_ON_A_DIAL - PI / 2
            drawLine(
                color = hand,
                start = centre,
                end = Offset(
                    centre.x + (radius * 0.55f) * cos(handAngle).toFloat(),
                    centre.y + (radius * 0.55f) * sin(handAngle).toFloat(),
                ),
                strokeWidth = 3.dp.toPx(),
            )
            drawCircle(hand, 4.dp.toPx(), centre)
            drawCircle(hand, radius, centre, style = Stroke(width = 1.dp.toPx()))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = theme.space(SpaceRole.Xs)),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            (0 until 60 step 5).forEach { mark ->
                val selected = mark == minute - minute % 5
                Box(
                    Modifier
                        .clip(theme.shape(ShapeRole.Full))
                        .background(if (selected) theme.color(ColorRole.Primary) else face)
                        .clickable(enabled = enabled) { onPick(hour * 60L + mark) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    BasicText(
                        text = mark.toString().padStart(2, '0'),
                        style = theme.pickerTextStyle(
                            role = TypeRole.Caption,
                            color = theme.color(
                                if (selected) ColorRole.OnPrimary else ColorRole.OnSurfaceVariant,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/** Wheels for the hour and the minute, and for the half of the day where that is read. */
@Composable
private fun TimeWheels(
    minutes: Long,
    enabled: Boolean,
    theme: ResolvedTheme,
    formats: PlatformFormats,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    val hour = (minutes / 60).toInt()
    val minute = (minutes % 60).toInt()
    val twentyFour = formats.uses24HourClock
    val hours = if (twentyFour) (0..23).toList() else (1..12).toList()
    val hourIndex = if (twentyFour) hour else hours.indexOf(if (hour % 12 == 0) 12 else hour % 12)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs))) {
        PickerWheel(
            labels = hours.map { it.toString().padStart(2, '0') },
            selectedIndex = hourIndex.coerceAtLeast(0),
            enabled = enabled,
            theme = theme,
            modifier = Modifier.weight(1f),
        ) { index ->
            val chosen = if (twentyFour) {
                index
            } else {
                val onDial = hours[index] % 12
                if (hour < 12) onDial else onDial + 12
            }
            onPick(chosen * 60L + minute)
        }

        PickerWheel(
            labels = (0..59).map { it.toString().padStart(2, '0') },
            selectedIndex = minute,
            enabled = enabled,
            theme = theme,
            modifier = Modifier.weight(1f),
        ) { index -> onPick(hour * 60L + index) }

        if (!twentyFour) {
            PickerWheel(
                labels = listOf("AM", "PM"),
                selectedIndex = if (hour < 12) 0 else 1,
                enabled = enabled,
                theme = theme,
                modifier = Modifier.weight(1f),
            ) { index -> onPick((hour % 12 + index * 12) * 60L + minute) }
        }
    }
}

/** A field with the hour and the minute stepped up and down one at a time. */
@Composable
private fun TimeStepper(
    minutes: Long,
    range: PickerRange,
    enabled: Boolean,
    theme: ResolvedTheme,
    formats: PlatformFormats,
    modifier: Modifier,
    onPick: (Long) -> Unit,
) {
    val style = theme.rules.container(ContainerRole.Surface, theme)
    Row(
        modifier
            .clip(style.shape)
            .background(theme.color(ColorRole.Surface), style.shape)
            .padding(theme.space(SpaceRole.Xs)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Sm)),
    ) {
        BasicText(readableTime(minutes, formats), style = theme.pickerTextStyle(TypeRole.BodyStrong))
        Stepper("hour", enabled, theme) { step -> onPick(range.coerce(minutes + step * 60L)) }
        Stepper("minute", enabled, theme) { step -> onPick(range.coerce(minutes + step)) }
    }
}

@Composable
private fun Stepper(
    field: String,
    enabled: Boolean,
    theme: ResolvedTheme,
    onStep: (Long) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepButton("▲", enabled, theme) { onStep(1) }
        BasicText(
            text = field,
            style = theme.pickerTextStyle(
                role = TypeRole.Caption,
                color = theme.color(ColorRole.OnSurfaceVariant),
            ),
        )
        StepButton("▼", enabled, theme) { onStep(-1) }
    }
}

/** The dial's hour positions, exposed so a test can read what one tap would set. */
internal fun dialHour(position: Int, currentHour: Int): Int =
    (currentHour / 12) * 12 + ((position % HOURS_ON_A_DIAL) + HOURS_ON_A_DIAL) % HOURS_ON_A_DIAL
