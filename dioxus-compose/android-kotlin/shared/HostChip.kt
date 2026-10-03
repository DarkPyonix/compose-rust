package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dioxus.compose.design.ChipStyle
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.role
import dioxus.compose.ui.textStyle

/**
 * A chip drawn by the active design system's rules.
 *
 * It draws exactly the chosen state the Host sent and keeps none of its own. Pressing it
 * reports a click to the Host's handler and changes nothing on screen: if the Host decides
 * the chip is now chosen, that arrives as a new `Checked`, the same way it arrived the
 * first time. A chip that kept a state of its own could show a filter as applied while the
 * Host, which is the one applying it, had refused.
 *
 * The corner, the height, the fill, the line and whether a chosen chip leads with a tick
 * all come from [dioxus.compose.design.ComponentRules.chip].
 */
@Composable
internal fun HostChip(
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.chip(theme)
    val chosen = node.flag(PropertyKind.Checked, default = false)
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val handlerId = node.handler(PropertyKind.OnClick)
    val label = node.text(PropertyKind.Text)
    val icon = node.role(PropertyKind.Icon, IconRole.entries.toTypedArray())

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()

    val operable = if (handlerId == null) {
        // Still announced as chosen or not, so assistive technology reads the same state
        // the screen shows, even on a chip nobody can press.
        modifier.semantics { selected = chosen }
    } else {
        // Indication is null because the press feedback is the design system's, and a
        // ripple is only one system's answer. The click carries nothing: whether this
        // chip toggles or clears its neighbours is the Host's business.
        modifier.selectable(
            selected = chosen,
            interactionSource = interactions,
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
        ) { dispatcher.dispatch(HostEvent.Clicked(node.id, handlerId)) }
    }

    val content = if (chosen) style.selectedContent else style.content
    val alpha = when {
        !enabled -> style.disabledAlpha
        pressed -> style.pressedAlpha
        else -> 1f
    }

    Row(
        modifier = operable
            .alpha(alpha)
            .chipFrame(style, chosen)
            .defaultMinSize(minHeight = style.height)
            .padding(horizontal = style.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(style.iconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A chosen chip leads with a tick where the system marks the choice that way. It
        // takes the place of the chip's own glyph rather than standing beside it, which is
        // what keeps the chip the same width whichever state it is in.
        val leading = if (chosen && style.leadingCheck) IconRole.Check else icon
        if (leading != null) RoleIcon(leading, content, theme)
        if (label.isNotEmpty()) {
            BasicText(text = label, style = node.textStyle(theme, style.typeRole).copy(color = content))
        }
    }
}

/** The chip's fill and edge for the state the Host sent. */
private fun Modifier.chipFrame(style: ChipStyle, chosen: Boolean): Modifier {
    val fill = if (chosen) style.selectedContainer else style.container
    val edge = if (chosen) style.selectedBorderColor else style.borderColor
    val framed = clip(style.shape).background(fill, style.shape)
    return if (style.borderWidth.value > 0f && edge.alpha > 0f) {
        framed.border(style.borderWidth, edge, style.shape)
    } else {
        framed
    }
}
