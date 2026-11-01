package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.DrawCommand
import dev.darkpyonix.composerust.protocol.DrawCommands
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.ui.node.Node

/**
 * Draws a Host-described command list.
 *
 * The list arrives as one property value, so a frame that did not change it produces no
 * mutation and nothing here recomposes. Decoding happens once per list, not once per frame:
 * [remember] is keyed on the byte array, and a new array means the Host sent a new list.
 *
 * Coordinates are dp from the top-left corner, and every colour goes through [Paint], which
 * is why a canvas drawn in `ColorRole.Primary` still follows the active design system.
 *
 * `PolylineRef` names a registered point asset. Until the asset cache exists it draws
 * nothing rather than guessing at points it does not have.
 */
@Composable
internal fun HostCanvas(node: Node, modifier: Modifier, theme: ResolvedTheme) {
    val bytes = node.bytes(PropertyKind.Commands)
    val commands = remember(bytes) { bytes?.let(DrawCommands::decode) ?: emptyList() }
    val viewWidth = node.number(PropertyKind.Min)
    val viewHeight = node.number(PropertyKind.Max)
    val measurer = rememberTextMeasurer()
    if (commands.isEmpty()) {
        Canvas(modifier) {}
        return
    }
    Canvas(modifier) {
        val scaled = viewWidth != null && viewWidth > 0f && viewHeight != null && viewHeight > 0f
        val scaleX = if (scaled) size.width / viewWidth!! else 1f
        val scaleY = if (scaled) size.height / viewHeight!! else 1f
        commands.forEach { command ->
            draw(command, theme, bytes, measurer, scaled, scaleX, scaleY)
        }
    }
}

/**
 * A stroke width of zero fills; anything else strokes at that width. Compose has no "zero
 * width stroke" that means fill, so the choice is made here rather than on the wire.
 */
private fun strokeOrFill(strokeWidth: Float, density: DrawScope) =
    if (strokeWidth <= 0f) Fill else Stroke(width = with(density) { strokeWidth.dp.toPx() })

private fun DrawScope.dp(value: Float) = value.dp.toPx()

private fun DrawScope.draw(
    command: DrawCommand,
    theme: ResolvedTheme,
    bytes: ByteArray?,
    measurer: TextMeasurer,
    scaled: Boolean,
    scaleX: Float,
    scaleY: Float,
) {
    fun x(value: Float): Float = if (scaled) value * scaleX else dp(value)
    fun y(value: Float): Float = if (scaled) value * scaleY else dp(value)
    fun r(value: Float): Float = if (scaled) value * minOf(scaleX, scaleY) else dp(value)
    val color = theme.color(command.paint)
    when (command) {
        is DrawCommand.Line -> drawLine(
            color = color,
            start = Offset(x(command.x1), y(command.y1)),
            end = Offset(x(command.x2), y(command.y2)),
            strokeWidth = dp(command.strokeWidth),
        )

        is DrawCommand.Rect -> drawRect(
            color = color,
            topLeft = Offset(x(command.x), y(command.y)),
            size = Size(x(command.width), y(command.height)),
            style = strokeOrFill(command.strokeWidth, this),
        )

        is DrawCommand.RoundRect -> drawRoundRect(
            color = color,
            topLeft = Offset(x(command.x), y(command.y)),
            size = Size(x(command.width), y(command.height)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r(command.radius)),
            style = strokeOrFill(command.strokeWidth, this),
        )

        is DrawCommand.Circle -> drawCircle(
            color = color,
            radius = r(command.radius),
            center = Offset(x(command.centerX), y(command.centerY)),
            style = strokeOrFill(command.strokeWidth, this),
        )

        is DrawCommand.Arc -> {
            val radius = r(command.radius)
            drawArc(
                color = color,
                startAngle = command.startDegrees,
                sweepAngle = command.sweepDegrees,
                useCenter = command.strokeWidth <= 0f,
                topLeft = Offset(
                    x(command.centerX) - radius,
                    y(command.centerY) - radius,
                ),
                size = Size(radius * 2f, radius * 2f),
                style = strokeOrFill(command.strokeWidth, this),
            )
        }

        // The points live in a registered asset. Nothing is drawn until that cache can
        // answer, because a wrong path is worse than an empty one.
        is DrawCommand.PolylineRef -> Unit

        is DrawCommand.TextAt -> {
            val token = theme.type(command.typeRole)
            drawText(
                textMeasurer = measurer,
                text = if (bytes == null) "" else DrawCommands.textOf(bytes, command),
                topLeft = Offset(x(command.x), y(command.y)),
                style = TextStyle(
                    color = color,
                    fontSize = token.size.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight(token.weight),
                    lineHeight = token.lineHeight.sp,
                    letterSpacing = token.letterSpacing.sp,
                ),
            )
        }
    }
}
