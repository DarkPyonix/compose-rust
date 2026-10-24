package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.darkpyonix.composerust.design.FloatingActionForm
import dev.darkpyonix.composerust.design.FloatingActionPlacement
import dev.darkpyonix.composerust.design.FloatingActionStyle
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalWindowSizeClass
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.role
import dev.darkpyonix.composerust.ui.textStyle

/**
 * The one action a screen is about, drawn as the active design system draws it.
 *
 * The Host sent an icon, a label and a click. Whether that becomes a raised disc, a plus in
 * the accent or an accent button with its label beside the glyph is
 * [dev.darkpyonix.composerust.design.ComponentRules.floatingAction]'s answer, and so is where it goes.
 * Where is not decided here, because this node does not know where its frame's corners and
 * bar are: the frame it is in reads the same answer and puts it there (see
 * [FloatingActionArea] and the Scaffold).
 *
 * The label is always the action's name. A form that draws the glyph alone still names
 * the control with it, so assistive technology never meets an unnamed button.
 */
@Composable
internal fun HostFloatingAction(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.floatingAction(LocalWindowSizeClass.current, theme)
    val handlerId = node.handler(PropertyKind.OnClick)
    val label = node.text(PropertyKind.Text)
    val icon = node.role(PropertyKind.Icon, IconRole.entries.toTypedArray())

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()

    val operable = if (handlerId == null) {
        modifier
    } else {
        // Indication is null because the press feedback is the design system's.
        modifier.clickable(
            interactionSource = interactions,
            indication = null,
            role = Role.Button,
        ) { dispatcher.dispatch(HostEvent.Clicked(node.id, handlerId)) }
    }

    // A form that has no room for a word still has a name, and the name is the label.
    val glyphOnly = icon != null && style.form != FloatingActionForm.Labelled
    val named = if (glyphOnly && label.isNotEmpty()) {
        operable.semantics { contentDescription = label }
    } else {
        operable
    }

    val framed = theme.rules
        .elevation(named, style.elevation, style.shape, theme)
        .alpha(if (pressed) style.pressedAlpha else 1f)
        .floatingActionFrame(style)

    if (icon != null && glyphOnly) {
        // The disc and the glyph button are square: the glyph sits in the middle of a
        // target that is as wide as it is tall.
        Box(framed.size(style.size), contentAlignment = Alignment.Center) {
            RoleIcon(icon, style.content, theme)
        }
        return
    }

    // A labelled button, or a glyph form that was given no glyph and so has only its
    // label to show.
    Row(
        modifier = framed
            .defaultMinSize(minHeight = style.size, minWidth = style.size)
            .padding(horizontal = style.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RoleIcon(icon, style.content, theme)
        if (label.isNotEmpty()) {
            BasicText(text = label, style = node.textStyle(theme, style.typeRole).copy(color = style.content))
        }
    }
}

/** The action's fill and edge. A form with no container draws neither. */
private fun Modifier.floatingActionFrame(style: FloatingActionStyle): Modifier {
    val filled = clip(style.shape).background(style.container, style.shape)
    return if (style.borderWidth.value > 0f) {
        filled.border(style.borderWidth, style.borderColor, style.shape)
    } else {
        filled
    }
}

/** Which corner of a frame a placement names. */
internal fun FloatingActionPlacement.alignment(): Alignment = when (this) {
    FloatingActionPlacement.OverPageBottomEnd -> Alignment.BottomEnd
    FloatingActionPlacement.BarEnd -> Alignment.TopEnd
    FloatingActionPlacement.BarStart -> Alignment.TopStart
}

/**
 * The area a floating action is laid over when it is a child of a Box, which is how a
 * screen that holds its page in a Box says "this action belongs to this page".
 *
 * It takes the Box's whole area and none of its size, so the page lays out as though the
 * action were not there, and the action goes to the corner the design system names. Asked
 * here rather than in the action, because this is the place that knows where the corners
 * are.
 */
@Composable
internal fun BoxScope.FloatingActionArea(theme: ResolvedTheme, content: @Composable () -> Unit) {
    val style = theme.rules.floatingAction(LocalWindowSizeClass.current, theme)
    Box(
        Modifier.matchParentSize().padding(style.inset),
        contentAlignment = style.placement.alignment(),
    ) { content() }
}
