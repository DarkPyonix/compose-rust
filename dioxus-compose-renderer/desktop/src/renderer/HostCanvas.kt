package dioxus.compose.foundation

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
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.DrawCommand
import dioxus.compose.protocol.DrawCommands
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.ui.node.Node

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
    val measurer = rememberTextMeasurer()
    if (commands.isEmpty()) {
        Canvas(modifier) {}
        return
    }
    Canvas(modifier) {
        commands.forEach { command -> draw(command, theme, bytes, measurer) }
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
) {
    val color = theme.color(command.paint)
    when (command) {
        is DrawCommand.Line -> drawLine(
            color = color,
            start = Offset(dp(command.x1), dp(command.y1)),
            end = Offset(dp(command.x2), dp(command.y2)),
            strokeWidth = dp(command.strokeWidth),
        )

        is DrawCommand.Rect -> drawRect(
            color = color,
            topLeft = Offset(dp(command.x), dp(command.y)),
            size = Size(dp(command.width), dp(command.height)),
            style = strokeOrFill(command.strokeWidth, this),
        )

        is DrawCommand.RoundRect -> drawRoundRect(
            color = color,
            topLeft = Offset(dp(command.x), dp(command.y)),
            size = Size(dp(command.width), dp(command.height)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(command.radius)),
            style = strokeOrFill(command.strokeWidth, this),
        )

        is DrawCommand.Circle -> drawCircle(
            color = color,
            radius = dp(command.radius),
            center = Offset(dp(command.centerX), dp(command.centerY)),
            style = strokeOrFill(command.strokeWidth, this),
        )

        is DrawCommand.Arc -> {
            val radius = dp(command.radius)
            drawArc(
                color = color,
                startAngle = command.startDegrees,
                sweepAngle = command.sweepDegrees,
                useCenter = command.strokeWidth <= 0f,
                topLeft = Offset(
                    dp(command.centerX) - radius,
                    dp(command.centerY) - radius,
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
                topLeft = Offset(dp(command.x), dp(command.y)),
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
