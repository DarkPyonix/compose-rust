package dioxus.compose.design

import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dioxus.compose.protocol.ColorRole

/**
 * Material 3's controls, drawn by `androidx.compose.material3`.
 *
 * This is the one design system that does not use [DrawnControlWidgets]. The library is
 * already a dependency of every Renderer target, and it draws the ripples, the state
 * layers, the accessibility semantics, the minimum touch targets and the motion that a
 * hand-written copy would have to chase and would eventually get wrong.
 *
 * What still comes from [ComponentRules.controls] is the colour. The Host's theme decides
 * the palette, and these components would otherwise read `MaterialTheme.colorScheme`,
 * which nothing here sets, so a Host running a dark theme would get light controls. Every
 * colour below is therefore passed in from the resolved theme rather than defaulted.
 *
 * Dimensions are not passed in: those are the part the library is authoritative about, and
 * naming them here would be a second opinion about Material's own metrics.
 */
internal object Material3ControlWidgets : ControlWidgets {

    @Composable
    override fun Toggle(
        role: ToggleRole,
        checked: Boolean,
        enabled: Boolean,
        onChange: ((Boolean) -> Unit)?,
        modifier: Modifier,
        theme: ResolvedTheme,
    ) {
        val primary = theme.color(ColorRole.Primary)
        val onPrimary = theme.color(ColorRole.OnPrimary)
        val outline = theme.color(ColorRole.Outline)
        when (role) {
            ToggleRole.Checkbox -> Checkbox(
                checked = checked,
                // A control with no handler is read-only, which material3 spells as a null
                // callback rather than as a disabled control: disabled would also grey it.
                onCheckedChange = onChange,
                modifier = modifier,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = primary,
                    uncheckedColor = outline,
                    checkmarkColor = onPrimary,
                ),
            )

            ToggleRole.RadioButton -> RadioButton(
                selected = checked,
                onClick = onChange?.let { report -> { report(!checked) } },
                modifier = modifier,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = primary,
                    unselectedColor = outline,
                ),
            )

            ToggleRole.Switch -> Switch(
                checked = checked,
                onCheckedChange = onChange,
                modifier = modifier,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = onPrimary,
                    checkedTrackColor = primary,
                    uncheckedThumbColor = outline,
                    uncheckedTrackColor = theme.color(ColorRole.SurfaceVariant),
                    uncheckedBorderColor = outline,
                ),
            )
        }
    }

    @Composable
    override fun Slider(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        enabled: Boolean,
        named: Color?,
        onChange: (Float) -> Unit,
        modifier: Modifier,
        theme: ResolvedTheme,
    ) {
        val primary = named ?: theme.color(ColorRole.Primary)
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = primary,
                activeTrackColor = primary,
                inactiveTrackColor = theme.color(ColorRole.SurfaceVariant),
            ),
        )
    }

    @Composable
    override fun ProgressIndicator(
        determinate: Boolean,
        value: Float,
        circular: Boolean,
        modifier: Modifier,
        theme: ResolvedTheme,
    ) {
        val indicator = theme.color(ColorRole.Primary)
        val track = theme.color(ColorRole.SurfaceVariant)
        when {
            circular && determinate -> CircularProgressIndicator(
                progress = { value },
                modifier = modifier,
                color = indicator,
                trackColor = track,
            )

            circular -> CircularProgressIndicator(
                modifier = modifier,
                color = indicator,
                trackColor = track,
            )

            determinate -> LinearProgressIndicator(
                progress = { value },
                modifier = modifier,
                color = indicator,
                trackColor = track,
            )

            else -> LinearProgressIndicator(
                modifier = modifier,
                color = indicator,
                trackColor = track,
            )
        }
    }

    @Composable
    override fun Divider(vertical: Boolean, modifier: Modifier, theme: ResolvedTheme) {
        val style = theme.rules.controls(theme).divider
        if (vertical) {
            VerticalDivider(modifier = modifier, thickness = style.thickness, color = style.color)
        } else {
            HorizontalDivider(modifier = modifier, thickness = style.thickness, color = style.color)
        }
    }
}
