package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.PropertyKind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.ui.node.Node

/**
 * A ProgressIndicator.
 *
 * `determinate` says whether the value means anything, and `circular` picks the form.
 * Everything else, the thickness, the colours, whether the ends are round or square and how
 * fast an indeterminate sweep travels, belongs to
 * [dev.darkpyonix.composerust.design.ComponentRules.controlWidgets]. How fast something moves is
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
        diameter = node.namedDiameter(),
    )
}

/**
 * The width of the ring this node was told to be, or null when it named no size. The
 * smaller side wins when it named both.
 */
private fun Node.namedDiameter(): Dp? = modifiers.fold(null as Dp?) { named, value ->
    when (value) {
        is ProtocolModifier.Size -> minOf(value.width, value.height).dp
        is ProtocolModifier.Width -> value.value.dp
        is ProtocolModifier.Height -> value.value.dp
        else -> named
    }
}
