package dioxus.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.intProp
import dioxus.compose.ui.paintProp
import dioxus.compose.ui.node.Node
import kotlin.math.roundToInt

/**
 * A Slider.
 *
 * The position a drag is passing through is held here, not in the Host: following a finger
 * would otherwise cost a boundary call per frame. The Host's `value` seeds that position
 * and moves it when the change came from somewhere else, and every stop the slider settles
 * on is reported once.
 *
 * How the track, the thumb and the stops are drawn belongs to
 * [dioxus.compose.design.ComponentRules.controlWidgets].
 */
@Composable
internal fun HostSlider(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    // The two ends are another process's idea of a range, so an empty or inverted one is
    // input rather than an impossible state. Widening it to a single unit gives every
    // position a distinct meaning again; refusing to draw would take the window down over
    // one bad property.
    val min = node.number(PropertyKind.Min) ?: 0f
    val requestedMax = node.number(PropertyKind.Max) ?: 1f
    val max = if (requestedMax > min) requestedMax else min + 1f
    // Zero means continuous, which is also what an absent property means.
    val steps = (node.intProp(PropertyKind.Steps) ?: 0L).toInt().coerceAtLeast(0)
    val fromHost = (node.number(PropertyKind.Value) ?: min).coerceIn(min, max)
    // Null unless the application said what the filled track should look like, and the
    // design system answers where it is.
    val named = node.paintProp(PropertyKind.Color)?.let { theme.color(it) }

    var current by remember(node.id) { mutableFloatStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { current = fromHost }

    val handlerId = node.handler(PropertyKind.OnValueChange)
    val report = report@{ candidate: Float ->
        val snapped = snap(candidate.coerceIn(min, max), min, max, steps)
        if (snapped == current) return@report
        current = snapped
        if (handlerId != null) {
            dispatcher.dispatch(HostEvent.ValueChanged(node.id, handlerId, snapped.toDouble()))
        }
    }

    theme.rules.controlWidgets.Slider(
        value = current,
        range = min..max,
        steps = steps,
        enabled = enabled,
        named = named,
        onChange = report,
        modifier = modifier,
        theme = theme,
    )
}

/**
 * The nearest stop, or the value itself when the slider is continuous.
 *
 * `steps` counts the stops between the two ends, so a slider with three of them has five
 * positions in all.
 */
private fun snap(value: Float, min: Float, max: Float, steps: Int): Float {
    if (steps <= 0 || max <= min) return value
    val intervals = steps + 1
    val position = (value - min) / (max - min) * intervals
    return min + (max - min) * (position.roundToInt().toFloat() / intervals)
}
