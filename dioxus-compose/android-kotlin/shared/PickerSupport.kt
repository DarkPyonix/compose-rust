package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.composeLetterSpacing
import dioxus.compose.design.composeLineHeight
import dioxus.compose.design.composeWeight
import dioxus.compose.design.fontSize
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.TypeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node

/**
 * How tall one row of this theme's wheel is.
 *
 * The design system answers, because two systems that both spin a wheel still do not spin
 * the same one: the roomier language gives each value more room.
 */
internal val ResolvedTheme.wheelRowHeight: Dp get() = rules.pickers.wheelRowHeight

/** How many rows of a wheel are visible at once, the chosen one in the middle. */
internal const val WHEEL_VISIBLE_ROWS = 5

/**
 * A picker's value, held by the Renderer and seeded by the Host.
 *
 * The Host's value is the starting point and the way a change from elsewhere arrives. Moving
 * it here does not wait for the Host: the new value is shown immediately and reported once,
 * which is what keeps spinning a wheel or dragging a dial off the boundary entirely.
 */
internal class PickerValue(val value: Long, val set: (Long) -> Unit)

/**
 * Reads an integer property without treating zero as absent.
 *
 * Epoch counts have a meaningful zero: day 0 is 1970-01-01 and minute 0 is midnight.
 */
internal fun Node.epochProp(kind: PropertyKind): Long? =
    (property(kind) as? PropertyValue.Integer)?.value

@Composable
internal fun rememberPickerValue(
    node: Node,
    dispatcher: EventDispatcher,
    coerce: (Long) -> Long,
): PickerValue {
    val fromHost = coerce(node.epochProp(PropertyKind.Value) ?: 0L)
    var current by remember(node.id) { mutableLongStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { current = fromHost }
    return PickerValue(current) { candidate ->
        val next = coerce(candidate)
        if (next != current) {
            current = next
            val handlerId = node.handler(PropertyKind.OnValueChange)
            if (handlerId != null) {
                dispatcher.dispatch(HostEvent.ValueChanged(node.id, handlerId, next.toDouble()))
            }
        }
    }
}

/** The ends of the allowed range, in the widget's own unit. */
internal class PickerRange(val min: Long, val max: Long) {
    fun coerce(value: Long): Long = value.coerceIn(min, max)
}

internal fun Node.pickerRange(floor: Long, ceiling: Long): PickerRange {
    val min = (epochProp(PropertyKind.Min) ?: floor).coerceAtLeast(floor)
    val max = (epochProp(PropertyKind.Max) ?: ceiling).coerceAtMost(ceiling)
    // A Host that crossed the ends over gets an empty range rather than an exception.
    return PickerRange(min, maxOf(min, max))
}

internal fun Node.pickerEnabled(): Boolean = flag(PropertyKind.Enabled, default = true)

/** Text drawn by a picker's own chrome, which has no node of its own to take a style from. */
internal fun ResolvedTheme.pickerTextStyle(
    role: TypeRole = TypeRole.Body,
    color: Color = color(ColorRole.OnSurface),
): TextStyle {
    val token = type(role)
    return TextStyle(
        color = color,
        fontSize = token.fontSize,
        fontWeight = token.composeWeight,
        lineHeight = token.composeLineHeight,
        letterSpacing = token.composeLetterSpacing,
        fontFamily = family(role),
    )
}

/**
 * The resting form of a picker that opens something: a field showing the current value.
 *
 * It takes the design system's own field chrome, so the same widget is a Fluent combo box
 * edge here and a Cupertino grouped row there.
 */
@Composable
internal fun PickerField(
    label: String,
    enabled: Boolean,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
    trailing: String = "",
    onClick: () -> Unit,
) {
    val style = theme.rules.container(ContainerRole.Surface, theme)
    val outline = theme.color(ColorRole.Outline)
    Row(
        modifier
            .clip(style.shape)
            .background(theme.color(ColorRole.Surface), style.shape)
            .border(1.dp, if (enabled) outline else theme.color(ColorRole.OutlineVariant), style.shape)
            .clickable(enabled = enabled, onClick = onClick)
            // Both paddings come from the design system. The vertical one used to be a
            // fixed 6 dp, which made a field exactly as tall under a dense system as under
            // a roomy one and left two systems drawing the same control.
            .padding(horizontal = theme.space(SpaceRole.Sm), vertical = theme.space(SpaceRole.Xs)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = theme.pickerTextStyle(
                color = theme.color(if (enabled) ColorRole.OnSurface else ColorRole.OnSurfaceVariant),
            ),
        )
        if (trailing.isNotEmpty()) {
            BasicText(
                text = trailing,
                style = theme.pickerTextStyle(color = theme.color(ColorRole.OnSurfaceVariant)),
            )
        }
    }
}

/**
 * A column of values spun to the one in the middle.
 *
 * The band across the centre is what marks the choice, which is how a wheel says what is
 * selected: there is no separate highlight on the row itself.
 */
@Composable
internal fun PickerWheel(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    if (labels.isEmpty()) {
        Box(modifier.height(theme.wheelRowHeight * WHEEL_VISIBLE_ROWS))
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        state.scrollToItem(selectedIndex.coerceIn(0, labels.lastIndex))
    }
    Box(modifier.height(theme.wheelRowHeight * WHEEL_VISIBLE_ROWS), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(theme.wheelRowHeight)
                .background(theme.color(ColorRole.SurfaceVariant)),
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            // Half a wheel of padding at each end, so the first and last values can still
            // reach the middle.
            contentPadding = PaddingValues(
                vertical = theme.wheelRowHeight * ((WHEEL_VISIBLE_ROWS - 1) / 2),
            ),
        ) {
            items(labels.size) { index ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(theme.wheelRowHeight)
                        .clickable(enabled = enabled) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = labels[index],
                        style = theme.pickerTextStyle(
                            role = if (selected) TypeRole.BodyStrong else TypeRole.Body,
                            color = theme.color(
                                if (selected) ColorRole.OnSurface else ColorRole.OnSurfaceVariant,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/** A small square control that moves a value by one step, used by the stepped fields. */
@Composable
internal fun StepButton(
    label: String,
    enabled: Boolean,
    theme: ResolvedTheme,
    onClick: () -> Unit,
) {
    val shape = theme.shape(ShapeRole.Small)
    Box(
        Modifier
            .width(24.dp)
            .height(20.dp)
            .clip(shape)
            .background(theme.color(ColorRole.SurfaceVariant), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = theme.pickerTextStyle(
                role = TypeRole.Caption,
                color = theme.color(ColorRole.OnSurfaceVariant),
            ),
        )
    }
}

/** A row of labels centred over a column of values, used by the calendar's weekday header. */
@Composable
internal fun WeekdayHeader(initials: List<String>, theme: ResolvedTheme) {
    Row(Modifier.fillMaxWidth()) {
        initials.forEach { initial ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BasicText(
                    text = initial,
                    style = theme.pickerTextStyle(
                        role = TypeRole.Caption,
                        color = theme.color(ColorRole.OnSurfaceVariant),
                    ).copy(textAlign = TextAlign.Center),
                )
            }
        }
    }
}
