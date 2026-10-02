package dioxus.compose.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dioxus.compose.design.CaptionSide
import dioxus.compose.design.CaptionStyle

/**
 * What the three window buttons do, supplied by whoever owns the window.
 *
 * Null where nothing owns one, which is every test that is not about these buttons and
 * every platform whose own chrome draws them. A null here is why they are not drawn at
 * all rather than drawn and dead.
 */
@Immutable
data class WindowActions(
    val minimise: () -> Unit,
    val maximise: () -> Unit,
    val close: () -> Unit,
)

/** The window this content is in, for the caption to operate. */
val LocalWindowActions = staticCompositionLocalOf<WindowActions?> { null }

/** Which of the three a button is. Its glyph and its hover colour follow from this. */
internal enum class WindowButton { Minimise, Maximise, Close }

/** The test tag a window button carries, so a test can press one by name. */
internal fun windowButtonTestTag(button: WindowButton): String = "window-button-${button.name.lowercase()}"

/**
 * The window's own buttons, laid out as [style] says.
 *
 * Order follows the side they sit on, because the two conventions are mirror images
 * rather than variations: a set at the leading edge runs close, minimise, zoom, and a set
 * at the trailing edge runs minimise, maximise, close. Getting that backwards puts close
 * where a reader expects minimise, which is the worst possible place for it.
 *
 * Drawn only where something owns the window. On macOS the system draws these and
 * imitating them would be the most visible way to fail at looking native, so the platform
 * layer provides no actions there and nothing appears.
 */
@Composable
internal fun WindowButtons(style: CaptionStyle, modifier: Modifier = Modifier) {
    val actions = LocalWindowActions.current ?: return
    val order = when (style.side) {
        CaptionSide.Start -> listOf(WindowButton.Close, WindowButton.Minimise, WindowButton.Maximise)
        CaptionSide.End -> listOf(WindowButton.Minimise, WindowButton.Maximise, WindowButton.Close)
    }
    val group = remember { MutableInteractionSource() }
    val groupHovered by group.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .hoverable(group)
            .padding(horizontal = style.edgePadding)
            .height(style.buttonHeight),
        horizontalArrangement = Arrangement.spacedBy(style.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        order.forEach { button ->
            WindowButtonCell(
                button = button,
                style = style,
                // A set of coloured discs shows its marks when the pointer is anywhere
                // over the set, not only over the one disc under it.
                showGlyph = style.glyphAtRest || groupHovered,
                onClick = when (button) {
                    WindowButton.Minimise -> actions.minimise
                    WindowButton.Maximise -> actions.maximise
                    WindowButton.Close -> actions.close
                },
            )
        }
    }
}

@Composable
private fun WindowButtonCell(
    button: WindowButton,
    style: CaptionStyle,
    showGlyph: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rest = when (button) {
        WindowButton.Minimise -> style.minimiseContainer
        WindowButton.Maximise -> style.maximiseContainer
        WindowButton.Close -> style.closeContainer
    }
    val hoverFill = if (button == WindowButton.Close) style.closeHover else style.hover
    // A transparent hover colour means this system does not change the fill under the
    // pointer, so the resting fill stays rather than being replaced by nothing.
    val container = if (hovered && hoverFill.alpha > 0f) hoverFill else rest
    val glyph = if (hovered && button == WindowButton.Close && style.closeHover.alpha > 0f) {
        style.closeHoverGlyph
    } else {
        style.glyph
    }
    Row(
        modifier = Modifier
            .testTag(windowButtonTestTag(button))
            .semantics { contentDescription = button.label }
            .size(width = style.buttonWidth, height = style.buttonHeight)
            .clip(style.shape)
            .background(container, style.shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showGlyph) {
            WindowButtonGlyph(button, glyph, style)
        }
    }
}

/** The accessible name of a window button, which the platform supplies on macOS. */
private val WindowButton.label: String
    get() = when (this) {
        WindowButton.Minimise -> "Minimise"
        WindowButton.Maximise -> "Maximise"
        WindowButton.Close -> "Close"
    }

/**
 * The mark inside a window button: a bar, a box, a cross.
 *
 * Drawn rather than taken from an icon set, because these three are the same three shapes
 * everywhere and a glyph font would make them a font dependency at the one place a window
 * cannot afford a missing glyph: the button that closes it.
 */
@Composable
private fun WindowButtonGlyph(button: WindowButton, colour: Color, style: CaptionStyle) {
    // Small against the button, the way every one of these systems draws it: the button is
    // a target and the mark inside it is a hint.
    val extent = if (style.glyphAtRest) 10.dp else 7.dp
    Row(
        Modifier
            .size(extent)
            .drawBehind {
                val stroke = Stroke(width = style.glyphStroke.toPx(), cap = StrokeCap.Round)
                when (button) {
                    WindowButton.Minimise -> drawLine(
                        color = colour,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )

                    WindowButton.Maximise -> drawRect(
                        color = colour,
                        style = stroke,
                    )

                    WindowButton.Close -> {
                        drawLine(
                            color = colour,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                            strokeWidth = stroke.width,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = colour,
                            start = Offset(size.width, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = stroke.width,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            .width(extent),
    ) {}
}
