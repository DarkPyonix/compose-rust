package dioxus.compose.foundation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.paintProp
import dioxus.compose.ui.role
import dioxus.compose.ui.textStyle
import dioxus.compose.ui.variant

/**
 * A Button drawn by the active design system's component rules.
 *
 * The same rsx deliberately produces different pixels per system: the shape, the label
 * weight and the press feedback all come from [ComponentRules], not from this function.
 */
@Composable
internal fun HostButton(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val handlerId = node.handler(PropertyKind.OnClick)
    val style = theme.rules.button(node.variant(), theme)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val motion = theme.rules.motion
    val spec = tween<Float>(
        durationMillis = if (pressed) motion.pressMillis else motion.releaseMillis,
        easing = motion.easing,
    )
    val container by animateColorAsState(
        targetValue = if (pressed) style.pressedContainer else style.container,
        animationSpec = tween(spec.durationMillis, easing = motion.easing),
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) style.pressedBorderColor else style.borderColor,
        animationSpec = tween(spec.durationMillis, easing = motion.easing),
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (pressed) style.pressedContentAlpha else 1f,
        animationSpec = spec,
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed) style.pressedElevation else style.restElevation,
        animationSpec = tween(spec.durationMillis, easing = motion.easing),
    )

    val clickable = if (handlerId == null) {
        modifier
    } else {
        // The Host result is not used here: a click that reached a Button is never offered
        // to anything below it. Indication is null because the design system owns the
        // feedback, and a ripple is only one system's answer.
        modifier.clickable(
            interactionSource = interactions,
            indication = null,
            enabled = enabled,
            role = Role.Button,
        ) { dispatcher.dispatch(HostEvent.Clicked(node.id, handlerId)) }
    }

    // A button that cannot be pressed has to look it. Without this the only way to find
    // out was to press it, because refusing the click was all `enabled` did. The whole
    // control fades rather than each variant naming a disabled colour, so a design system
    // owes one number instead of four and the faded colours are still its own.
    val available = if (enabled) Modifier else Modifier.alpha(style.disabledAlpha)
    // A Host that named its own Background has already had it applied to this node's
    // modifier chain, so painting the variant's fill here would cover it. The shape, the
    // border, the resting height and the padding still come from the design system: only
    // the colour is the application's, which is the rule a Container already follows.
    //
    // A unified sample is what needs it. Its reference names a mint chip and a white
    // pill, and asking for a variant instead hands the question to whichever design
    // system is active, which answers with its own accent.
    val ownFill = node.setsOwnBackground()
    val decorated = theme.rules
        .elevation(clickable.then(available), elevation, style.shape, theme)
        .clip(style.shape)
        .then(if (ownFill) Modifier else Modifier.background(container, style.shape))
        .then(
            if (style.borderWidth.value > 0f) {
                Modifier.border(style.borderWidth, borderColor, style.shape)
            } else {
                Modifier
            },
        )
        .topHighlight(style.topHighlight)
        .defaultMinSize(minHeight = style.minHeight)
        .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding)

    // The variant decides the label colour, unless the node names one itself. A
    // destructive action is the case that needs it: it is a plain button in every design
    // system, and what marks it is that its label is the error colour.
    val content = node.paintProp(PropertyKind.Color)?.let { theme.color(it) } ?: style.content
    val tint = content.copy(alpha = content.alpha * contentAlpha)
    val label = node.textStyle(theme, style.typeRole).copy(color = tint)
    val text = node.text(PropertyKind.Text)
    val icon = node.role(PropertyKind.Icon, IconRole.entries.toTypedArray())
    // A button with a glyph and no word is still a button with a name. The role is what
    // the glyph means, so it is what the name is made of, and assistive technology never
    // meets an unnamed control.
    val named = if (icon != null && text.isEmpty()) {
        decorated.semantics { contentDescription = icon.name }
    } else {
        decorated
    }
    Box(named, contentAlignment = Alignment.Center) {
        if (icon == null) {
            BasicText(text = text, style = label)
        } else {
            Row(
                // The gap between the glyph and the word is the design system's smallest step,
                // so a dense language sets them closer than a roomy one does.
                horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleIcon(icon, tint, theme)
                if (text.isNotEmpty()) BasicText(text = text, style = label)
            }
        }
    }
}

/** Fluent's lighter top edge. Other systems pass null and pay nothing. */
private fun Modifier.topHighlight(color: Color?): Modifier {
    if (color == null) return this
    return drawWithContent {
        drawContent()
        val thickness = 1.dpPx(density)
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, thickness / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, thickness / 2f),
            strokeWidth = thickness,
        )
    }
}

private fun Int.dpPx(density: Float): Float = this * density
