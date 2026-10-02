package dioxus.compose.material3

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dioxus.compose.ButtonVariant

/**
 * A button of [variant], drawn by androidx.compose.material3 itself.
 *
 * The point of routing to the four real composables rather than painting a surface with
 * the colours from [Material3DesignSystem.button] is everything those composables carry
 * that a colour triple cannot describe: the ripple and its bounds, the hover and focus
 * state layers, the disabled colours, the minimum touch target, and the role and
 * enabled state reported to a screen reader.
 *
 * [Material3DesignSystem.button] still exists for callers that draw their own control,
 * such as a renderer composing a button out of primitives. It describes what this draws.
 */
@Composable
fun MaterialButton(
    variant: ButtonVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    when (variant) {
        ButtonVariant.Filled ->
            Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        ButtonVariant.Tonal, ButtonVariant.Operator ->
            FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        ButtonVariant.Outlined ->
            OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        ButtonVariant.Text ->
            TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}
