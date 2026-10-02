package dioxus.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.ui.node.Node

/**
 * A ProgressIndicator.
 *
 * `determinate` says whether the value means anything, and `circular` picks the form.
 * Everything else, the thickness, the colours, whether the ends are round or square and how
 * fast an indeterminate sweep travels, belongs to
 * [dioxus.compose.design.ComponentRules.controlWidgets]. How fast something moves is
 * motion, and motion is the design system's decision rather than a Host property.
 */
@Composable
internal fun HostProgressIndicator(node: Node, modifier: Modifier, theme: ResolvedTheme) {
    val determinate = node.flag(PropertyKind.Determinate, default = true)
    theme.rules.controlWidgets.ProgressIndicator(
        determinate = determinate,
        // An indeterminate indicator does not read the value at all, so an absent one is
        // not a reason to show an empty bar.
        value = (node.number(PropertyKind.Value) ?: 0f).coerceIn(0f, 1f),
        circular = node.flag(PropertyKind.Circular, default = false),
        modifier = modifier,
        theme = theme,
    )
}
