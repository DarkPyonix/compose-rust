package dioxus.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.ToggleRole
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node

/**
 * A Checkbox, a RadioButton or a Switch.
 *
 * Only the node is read here. Which pixels appear belongs to
 * [dioxus.compose.design.ComponentRules.controlWidgets], which is why the same declaration
 * is a Material checkbox drawn by `androidx.compose.material3`, a round Cupertino one and a
 * stroked Fluent one.
 *
 * All three are controlled: the widget draws exactly what the Host sent and reports the
 * state the user asked for, so the value the Host holds and the control on screen cannot
 * drift apart.
 */
@Composable
internal fun HostToggle(
    role: ToggleRole,
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val checked = node.flag(PropertyKind.Checked, default = false)
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val handlerId = node.handler(PropertyKind.OnValueChange)

    theme.rules.controlWidgets.Toggle(
        role = role,
        checked = checked,
        enabled = enabled,
        // Off is 0.0 and on is 1.0. The Host decides whether to honour the request.
        onChange = handlerId?.let { id ->
            { requested: Boolean ->
                dispatcher.dispatch(
                    HostEvent.ValueChanged(node.id, id, if (requested) 1.0 else 0.0),
                )
            }
        },
        modifier = modifier,
        theme = theme,
    )
}
