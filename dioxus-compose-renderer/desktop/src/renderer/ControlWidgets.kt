package dioxus.compose.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Who draws the six selection controls.
 *
 * [ComponentRules.controls] answers the measurements and the colours. This answers who
 * puts them on screen, which is a separate question because one of the systems has a
 * library that already draws its own controls and the others do not.
 *
 * The default is [DrawnControlWidgets], which draws from `controls()`. A new design system
 * implements `controls()` and inherits that drawing, so it stays one implementation and
 * never mentions this interface. Material 3 is the one system that overrides it, in
 * [Material3ControlWidgets], because `androidx.compose.material3` draws Material's
 * controls better than a copy of them would.
 *
 * Every widget here is controlled: it draws the state it was handed and reports the state
 * the user asked for. Nothing decides on its own what it shows. That is what lets the Host
 * stay the one holding the value while the transitions, the drag and the press feedback
 * never cross the boundary.
 */
interface ControlWidgets {

    /**
     * A Checkbox, a RadioButton or a Switch.
     *
     * [onChange] carries the state the user asked for, and is null when the Host attached
     * no handler, which is how a read-only control is expressed.
     */
    @Composable
    fun Toggle(
        role: ToggleRole,
        checked: Boolean,
        enabled: Boolean,
        onChange: ((Boolean) -> Unit)?,
        modifier: Modifier,
        theme: ResolvedTheme,
    )

    /**
     * A Slider between the two ends of [range].
     *
     * [steps] counts the stops between those ends, so three stops make five positions in
     * all, and zero is a continuous slider.
     *
     * [onChange] is not optional the way a toggle's is. A slider that has been dragged has
     * moved whether or not the Host asked to hear about it, and the caller is what decides
     * whether that movement leaves the Renderer.
     *
     * [named] colours the part that has been filled in and the thumb with it, where the
     * application said what those should look like. It is null in the ordinary case and
     * the design system answers instead, which is what an adaptive application wants.
     */
    @Composable
    fun Slider(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        enabled: Boolean,
        named: Color?,
        onChange: (Float) -> Unit,
        modifier: Modifier,
        theme: ResolvedTheme,
    )

    /**
     * Progress, as a bar or as a ring.
     *
     * [value] is between 0 and 1 and means nothing when [determinate] is false.
     */
    @Composable
    fun ProgressIndicator(
        determinate: Boolean,
        value: Float,
        circular: Boolean,
        modifier: Modifier,
        theme: ResolvedTheme,
    )

    /** A rule, along whichever axis [vertical] names. */
    @Composable
    fun Divider(vertical: Boolean, modifier: Modifier, theme: ResolvedTheme)
}

/**
 * The controls drawn from [ComponentRules.controls], which is every design system that does
 * not bring its own.
 *
 * Nothing here decides a size, a colour or a shape. All of that comes from the style, which
 * is why the same code is a round Cupertino checkbox and a stroked Fluent one.
 */
internal object DrawnControlWidgets : ControlWidgets {

    @Composable
    override fun Toggle(
        role: ToggleRole,
        checked: Boolean,
        enabled: Boolean,
        onChange: ((Boolean) -> Unit)?,
        modifier: Modifier,
        theme: ResolvedTheme,
    ) {
        val style = theme.rules.controls(theme).toggle(role)
        val motion = theme.rules.motion
        val spec = tween<Float>(
            durationMillis = if (checked) motion.pressMillis else motion.releaseMillis,
            easing = motion.easing,
        )
        // The transition between the two states belongs to the Renderer, so a toggle
        // animates without a frame of it crossing the boundary.
        val progress by animateFloatAsState(if (checked) 1f else 0f, spec)
        val container by animateColorAsState(
            targetValue = if (checked) style.containerChecked else style.container,
            animationSpec = tween(spec.durationMillis, easing = motion.easing),
        )
        val alpha = if (enabled) 1f else style.disabledAlpha

        // Each of the three announces itself as what it is, and announces whether it is
        // on. A screen reader has to be able to say "checkbox, checked" rather than read a
        // box with no state, and a radio button is a selection among several rather than a
        // setting being turned on, which is why it uses the other modifier.
        val interactionSource = remember { MutableInteractionSource() }
        val clickable = when {
            onChange == null -> modifier.semantics {
                this.role = role.semanticsRole
                // A control with no handler still says what it is and what it shows. It
                // simply cannot be operated.
                if (role == ToggleRole.RadioButton) {
                    selected = checked
                } else {
                    toggleableState = ToggleableState(checked)
                }
            }

            role == ToggleRole.RadioButton -> modifier.selectable(
                selected = checked,
                interactionSource = interactionSource,
                // The design system owns the feedback, and a ripple is only one system's
                // answer to what a press looks like.
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
            ) {
                onChange(!checked)
            }

            else -> modifier.toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role.semanticsRole,
                onValueChange = onChange,
            )
        }

        when (role) {
            ToggleRole.Checkbox -> Box(
                clickable
                    .size(style.size)
                    .clip(style.shape)
                    .background(container.copy(alpha = container.alpha * alpha), style.shape)
                    .controlBorder(style.borderWidth, style.border.copy(alpha = alpha), style.shape)
                    .drawBehind { drawTick(style.mark.copy(alpha = alpha), progress) },
            )

            ToggleRole.RadioButton -> Box(
                clickable
                    .size(style.size)
                    .clip(style.shape)
                    .background(container.copy(alpha = container.alpha * alpha), style.shape)
                    .controlBorder(style.borderWidth, style.border.copy(alpha = alpha), style.shape)
                    .drawBehind {
                        val radius = style.thumbSize.toPx() / 2f * progress
                        if (radius > 0f) {
                            drawCircle(style.mark.copy(alpha = alpha), radius = radius)
                        }
                    },
            )

            ToggleRole.Switch -> Box(
                clickable
                    .size(width = style.trackWidth, height = style.trackHeight)
                    .clip(style.shape)
                    .background(container.copy(alpha = container.alpha * alpha), style.shape)
                    .controlBorder(style.borderWidth, style.border.copy(alpha = alpha), style.shape)
                    .drawBehind {
                        val diameter = style.thumbSize.toPx()
                        val margin = (size.height - diameter) / 2f
                        val travel = size.width - diameter - margin * 2f
                        val thumb = if (checked) style.mark else style.markUnchecked
                        drawCircle(
                            color = thumb.copy(alpha = alpha),
                            radius = diameter / 2f,
                            center = Offset(
                                x = margin + diameter / 2f + travel * progress,
                                y = size.height / 2f,
                            ),
                        )
                    },
            )
        }
    }

    @Composable
    override fun Slider(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        enabled: Boolean,
        named: Color?,
        onChange: (Float) -> Unit,
        modifier: Modifier,
        theme: ResolvedTheme,
    ) {
        val style = theme.rules.controls(theme).slider
        val active = named ?: style.activeTrack
        val knob = named ?: style.thumb
        val min = range.start
        val max = range.endInclusive
        val span = (max - min).takeIf { it > 0f }
        val fraction = if (span == null) 0f else ((value - min) / span).coerceIn(0f, 1f)
        val thumbPx = style.thumbSize

        val interactive = if (!enabled) {
            modifier
        } else {
            modifier
                .pointerInput(min, max) {
                    detectTapGestures { offset ->
                        onChange(valueAt(offset.x, size.width.toFloat(), thumbPx.toPx(), min, max))
                    }
                }
                .pointerInput(min, max) {
                    detectHorizontalDragGestures { change, _ ->
                        onChange(
                            valueAt(
                                change.position.x,
                                size.width.toFloat(),
                                thumbPx.toPx(),
                                min,
                                max,
                            ),
                        )
                    }
                }
        }

        Box(
            interactive
                .defaultMinSize(minWidth = SLIDER_MIN_WIDTH)
                .fillMaxWidth()
                .height(maxOf(thumbPx, style.trackHeight))
                .drawBehind {
                    val thumb = thumbPx.toPx()
                    val trackTop = (size.height - style.trackHeight.toPx()) / 2f
                    val trackHeight = style.trackHeight.toPx()
                    val left = thumb / 2f
                    val usable = size.width - thumb
                    val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                    drawRoundRect(
                        color = style.track,
                        topLeft = Offset(left, trackTop),
                        size = Size(usable, trackHeight),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = active,
                        topLeft = Offset(left, trackTop),
                        size = Size(usable * fraction, trackHeight),
                        cornerRadius = radius,
                    )
                    // The stops, in the systems that mark them. A slider with no steps is
                    // continuous and has nothing to mark.
                    val tick = style.tick
                    if (tick != null && steps > 0) {
                        for (index in 1..steps) {
                            val at = left + usable * (index.toFloat() / (steps + 1))
                            drawCircle(
                                tick,
                                radius = trackHeight / 4f,
                                center = Offset(at, trackTop + trackHeight / 2f),
                            )
                        }
                    }
                    val centre = Offset(left + usable * fraction, size.height / 2f)
                    drawCircle(knob, radius = thumb / 2f, center = centre)
                    if (style.thumbBorderWidth.value > 0f) {
                        val border = style.thumbBorderWidth.toPx()
                        drawCircle(
                            color = style.thumbBorder,
                            radius = (thumb - border) / 2f,
                            center = centre,
                            style = Stroke(width = border),
                        )
                    }
                },
        )
    }

    @Composable
    override fun ProgressIndicator(
        determinate: Boolean,
        value: Float,
        circular: Boolean,
        modifier: Modifier,
        theme: ResolvedTheme,
    ) {
        val style = theme.rules.controls(theme).progress
        val sweep by rememberInfiniteTransition(label = "progress").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(style.periodMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        // An indeterminate indicator is the only one that animates. A determinate one reads
        // the value it was given and stays there.
        val phase = if (determinate) 0f else sweep
        val cap = if (style.rounded) StrokeCap.Round else StrokeCap.Butt

        if (circular) {
            Box(
                modifier.size(style.diameter).drawBehind {
                    val stroke = Stroke(width = style.thickness.toPx(), cap = cap)
                    val inset = style.thickness.toPx() / 2f
                    val box = Size(size.width - inset * 2f, size.height - inset * 2f)
                    drawArc(
                        color = style.track,
                        startAngle = 0f,
                        sweepAngle = FULL_TURN,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = box,
                        style = stroke,
                    )
                    drawArc(
                        color = style.indicator,
                        // A determinate ring starts at twelve o'clock; an indeterminate one
                        // chases its own tail from wherever the sweep has reached.
                        startAngle = if (determinate) QUARTER_TURN_BACK else phase * FULL_TURN,
                        sweepAngle = if (determinate) value * FULL_TURN else INDETERMINATE_ARC,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = box,
                        style = stroke,
                    )
                },
            )
            return
        }

        Box(
            modifier
                .fillMaxWidth()
                .height(style.thickness)
                .drawBehind {
                    val radius = if (style.rounded) {
                        CornerRadius(size.height / 2f, size.height / 2f)
                    } else {
                        CornerRadius.Zero
                    }
                    drawRoundRect(color = style.track, cornerRadius = radius)
                    val width = if (determinate) {
                        size.width * value
                    } else {
                        size.width * INDETERMINATE_BAR
                    }
                    val left = if (determinate) {
                        0f
                    } else {
                        // The bar travels off one end and back on at the other.
                        (size.width + width) * phase - width
                    }
                    drawRoundRect(
                        color = style.indicator,
                        topLeft = Offset(left, 0f),
                        size = Size(width.coerceAtMost(size.width), size.height),
                        cornerRadius = radius,
                    )
                },
        )
    }

    @Composable
    override fun Divider(vertical: Boolean, modifier: Modifier, theme: ResolvedTheme) {
        val style = theme.rules.controls(theme).divider
        if (vertical) {
            Box(
                modifier
                    .padding(top = style.inset)
                    .width(style.thickness)
                    .fillMaxHeight()
                    .background(style.color),
            )
        } else {
            Box(
                modifier
                    .padding(start = style.inset)
                    .height(style.thickness)
                    .fillMaxWidth()
                    .background(style.color),
            )
        }
    }
}

/**
 * The line around a control, or nothing where the design system asked for nothing.
 *
 * `Modifier.border` reads a zero width as a hairline and draws a one pixel line anyway, in
 * whatever the colour's channels happen to be, so a system that said its switch has no
 * outline still got a dark ring around the track. Asking for no line has to mean no line.
 */
private fun Modifier.controlBorder(width: Dp, color: Color, shape: Shape): Modifier =
    if (width.value <= 0f) this else border(width, color, shape)

/**
 * The checkmark, drawn as two strokes that sweep in together.
 *
 * `progress` is how much of the mark has appeared, so a half-finished transition draws a
 * partial tick rather than blinking a whole one into place.
 */
private fun DrawScope.drawTick(color: Color, progress: Float) {
    if (progress <= 0f) return
    val width = size.width
    val height = size.height
    val path = Path().apply {
        moveTo(width * 0.22f, height * 0.52f)
        lineTo(width * 0.42f, height * 0.72f)
        lineTo(width * 0.78f, height * 0.30f)
    }
    // Fading the stroke rather than measuring the path keeps this free of the path
    // measurement API, which differs between the platforms this renderer targets.
    drawPath(
        path = path,
        color = color.copy(alpha = color.alpha * progress.coerceIn(0f, 1f)),
        style = Stroke(width = width * 0.14f, cap = StrokeCap.Round),
    )
}

/** The value a press at [x] lands on, with the thumb's own width taken off both ends. */
private fun valueAt(x: Float, width: Float, thumb: Float, min: Float, max: Float): Float {
    val usable = (width - thumb).takeIf { it > 0f } ?: return min
    return min + (max - min) * ((x - thumb / 2f) / usable).coerceIn(0f, 1f)
}

/** Narrow enough to sit in a row, wide enough that the track is worth dragging. */
private val SLIDER_MIN_WIDTH = 120.dp

private const val FULL_TURN = 360f
private const val QUARTER_TURN_BACK = -90f

/** How much of the ring an indeterminate indicator fills while it turns. */
private const val INDETERMINATE_ARC = 90f

/** How much of the bar an indeterminate indicator fills while it travels. */
private const val INDETERMINATE_BAR = 0.3f

/** What a screen reader calls each of the three. */
private val ToggleRole.semanticsRole: Role
    get() = when (this) {
        ToggleRole.Checkbox -> Role.Checkbox
        ToggleRole.RadioButton -> Role.RadioButton
        ToggleRole.Switch -> Role.Switch
    }
