package dioxus.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.ui.node.Node

/**
 * A Divider.
 *
 * The axis is the only thing the Host decides. The thickness, the colour and how far the
 * rule is held back from the leading edge belong to
 * [dioxus.compose.design.ComponentRules.controlWidgets]: a Material rule runs the full
 * width, a Cupertino one is a hairline inset the way a grouped list rules between its rows.
 */
@Composable
internal fun HostDivider(node: Node, modifier: Modifier, theme: ResolvedTheme) {
    theme.rules.controlWidgets.Divider(
        vertical = node.flag(PropertyKind.Vertical, default = false),
        modifier = modifier,
        theme = theme,
    )
}
