package dioxus.compose.fluent

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dioxus.compose.ButtonStyle
import dioxus.compose.ButtonVariant
import dioxus.compose.ColorRole
import dioxus.compose.DesignSystem
import dioxus.compose.DesignSystemId
import dioxus.compose.ElevationStyle
import dioxus.compose.Motion
import dioxus.compose.ShapeRole
import dioxus.compose.SpaceRole
import dioxus.compose.TypeRole

/**
 * Fluent 2, written from Microsoft's specification.
 *
 * There is no Compose implementation of Fluent from Microsoft to delegate to, so unlike
 * the Material system in this project, this one resolves every role itself from the
 * published Fluent 2 token set. Konyaco/compose-fluent-ui is a readable cross check for
 * the same values and is deliberately not a dependency: it describes its own API as
 * experimental and subject to change without notice, and every dependency added here is
 * another surface that has to stay reachable under GraalVM native image.
 *
 * What makes the result read as Fluent rather than as Material with different colours:
 * tight 4dp corners on controls instead of large or pill shapes, a lighter stroke along
 * the top edge of anything raised so it reads as a slab lit from above, layered shadows
 * rather than a tinted surface, and Accent, Standard and Subtle button weights in place
 * of Material's filled and tonal pair. No ripple anywhere: Fluent changes a control's
 * fill on press, it does not spread a wave from the pointer.
 *
 * Light and dark are two instances because Fluent changes more than lightness between
 * them. Its accent moves up the brand ramp and the foreground on the accent flips to
 * near black, its top stroke goes from a faint white line to a pronounced one, and its
 * shadows deepen.
 */
@Immutable
class FluentDesignSystem private constructor(
    private val palette: FluentPalette,
    override val isDark: Boolean,
) : DesignSystem {

    override val id: DesignSystemId = DesignSystemId.Fluent

    override fun color(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> palette.accent
        ColorRole.OnPrimary -> palette.onAccent
        ColorRole.Secondary -> palette.accentSecondary
        ColorRole.OnSecondary -> palette.onAccentSecondary
        ColorRole.Surface -> palette.background1
        ColorRole.OnSurface -> palette.foreground1
        ColorRole.SurfaceVariant -> palette.background3
        ColorRole.OnSurfaceVariant -> palette.foreground2
        ColorRole.Background -> palette.canvas
        ColorRole.OnBackground -> palette.foreground1
        ColorRole.Outline -> palette.stroke1
        ColorRole.OutlineVariant -> palette.stroke2
        ColorRole.Error -> palette.danger
        ColorRole.OnError -> palette.onDanger
        // A card sits on the solid background base, one layer lighter than the canvas.
        ColorRole.SurfaceContainer -> palette.background1
        ColorRole.Tertiary -> palette.accentTertiary
        ColorRole.OnTertiary -> palette.onAccentTertiary
        ColorRole.PrimaryContainer -> palette.accentContainer
        ColorRole.OnPrimaryContainer -> palette.onAccentContainer
        ColorRole.SecondaryContainer -> palette.accentSecondaryContainer
        ColorRole.OnSecondaryContainer -> palette.onAccentSecondaryContainer
        ColorRole.TertiaryContainer -> palette.accentTertiaryContainer
        ColorRole.OnTertiaryContainer -> palette.onAccentTertiaryContainer
    }

    /**
     * The Fluent 2 type ramp, which is a size and line height pair per step with no
     * letter spacing adjustment at any step, unlike Material's scale.
     *
     * Fluent names its faces (Segoe UI Variable on Windows, Cascadia Code for code) but
     * font resources do not cross this library's boundary, so these ask for the default
     * and the generic monospaced family and let the platform choose.
     */
    override fun type(role: TypeRole): TextStyle = when (role) {
        TypeRole.Display -> style(68.sp, 92.sp, FontWeight.SemiBold)
        // Fluent's LargeTitle.
        TypeRole.Headline -> style(32.sp, 40.sp, FontWeight.SemiBold)
        // Title1.
        TypeRole.Title -> style(24.sp, 32.sp, FontWeight.SemiBold)
        // Title3.
        TypeRole.Subtitle -> style(20.sp, 28.sp, FontWeight.SemiBold)
        // Body1, Fluent's default text, and the strong weight of the same step. Fluent
        // does carry a distinct bold body step, which is why this role exists at all.
        TypeRole.Body -> style(14.sp, 20.sp, FontWeight.Normal)
        TypeRole.BodyStrong -> style(14.sp, 20.sp, FontWeight.SemiBold)
        // Caption1Strong, which is what Fluent labels a control with.
        TypeRole.Label -> style(12.sp, 16.sp, FontWeight.SemiBold)
        // Caption1.
        TypeRole.Caption -> style(12.sp, 16.sp, FontWeight.Normal)
        // Fluent names no monospaced step in the ramp. Body1's metrics with a monospaced
        // family keeps code inline with the text around it.
        TypeRole.Mono -> style(14.sp, 20.sp, FontWeight.Normal, FontFamily.Monospace)
    }

    /**
     * Fluent publishes four corner radii (2, 4, 6 and 8dp) plus circular, and this ladder
     * has six steps, so one role has to sit between two published values. Small takes
     * 4dp, the radius Fluent gives every standard control, because that is the one a
     * button and a text field reach for and the one that has to be exact.
     */
    override fun shape(role: ShapeRole): Shape = when (role) {
        ShapeRole.None -> RectangleShape
        ShapeRole.ExtraSmall -> RoundedCornerShape(2.dp)
        ShapeRole.Small -> RoundedCornerShape(4.dp)
        ShapeRole.Medium -> RoundedCornerShape(6.dp)
        ShapeRole.Large -> RoundedCornerShape(8.dp)
        // Fluent expresses circular as a radius large enough to always clip to a pill.
        ShapeRole.Full -> CircleShape
    }

    /**
     * Fluent's spacing ramp, which is denser than Material's 4dp grid in the middle:
     * where Material pads a card at 16dp, Fluent pads at 12dp.
     */
    override fun space(role: SpaceRole): Dp = when (role) {
        SpaceRole.None -> 0.dp
        SpaceRole.Xs -> 4.dp
        SpaceRole.Sm -> 8.dp
        SpaceRole.Md -> 12.dp
        SpaceRole.Lg -> 16.dp
        SpaceRole.Xl -> 24.dp
        SpaceRole.Xxl -> 32.dp
    }

    /**
     * Fluent raises a surface with a shadow and a lit top edge, and leaves its colour
     * alone. There is no tone overlay: a raised Fluent card is the same fill as a flat
     * one, which is why a Fluent dialog over a Fluent page reads as a separate slab
     * rather than as a paler patch.
     */
    override fun elevation(elevation: Dp, base: Color): ElevationStyle = ElevationStyle(
        surface = base,
        shadowElevation = fluentShadowStep(elevation),
        shadowColor = palette.shadowColor,
        strokeTop = if (elevation > 0.dp) palette.strokeTop else null,
    )

    /**
     * Fluent's four button weights, under this project's neutral variant names.
     *
     * Accent is the single emphasised action. Standard is the neutral filled button with
     * its hairline stroke, which is what most Fluent buttons are. Outlined drops the fill
     * and keeps the stroke, for a button over content that should show through. Subtle
     * drops both and is Fluent's name for a toolbar or command bar button.
     */
    override fun button(variant: ButtonVariant): ButtonStyle = when (variant) {
        ButtonVariant.Filled -> ButtonStyle(
            container = palette.accent,
            content = palette.onAccent,
            border = null,
            borderWidth = 0.dp,
            shape = ShapeRole.Small,
            pressedContainer = palette.accentPressed,
            pressedContent = palette.onAccent,
            pressedBorder = null,
            ripple = false,
        )
        ButtonVariant.Tonal, ButtonVariant.Operator -> ButtonStyle(
            container = palette.background1,
            content = palette.foreground1,
            // A Fluent standard button always carries its stroke. Without it the button
            // disappears into a card of the same fill.
            border = palette.stroke1,
            borderWidth = 1.dp,
            shape = ShapeRole.Small,
            pressedContainer = palette.backgroundPressed,
            // Fluent dims the label as well as the fill on press.
            pressedContent = palette.foreground2,
            pressedBorder = palette.stroke1,
            ripple = false,
        )
        ButtonVariant.Outlined -> ButtonStyle(
            container = Color.Transparent,
            content = palette.foreground1,
            border = palette.stroke1,
            borderWidth = 1.dp,
            shape = ShapeRole.Small,
            pressedContainer = palette.backgroundPressed,
            pressedContent = palette.foreground2,
            pressedBorder = palette.stroke1,
            ripple = false,
        )
        ButtonVariant.Text -> ButtonStyle(
            container = Color.Transparent,
            content = palette.foreground1,
            border = null,
            borderWidth = 0.dp,
            shape = ShapeRole.Small,
            pressedContainer = palette.backgroundPressed,
            pressedContent = palette.foreground2,
            pressedBorder = null,
            ripple = false,
        )
    }

    /**
     * Fluent's fast duration for the press and its normal duration for the release, on
     * the decelerating curve it uses when something settles into place. Pressing should
     * feel immediate; letting go is allowed to be seen.
     */
    override val motion: Motion = Motion(
        pressMillis = 100,
        releaseMillis = 150,
        easing = androidx.compose.animation.core.CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f),
    )

    companion object {
        val Light: FluentDesignSystem = FluentDesignSystem(FluentPalette.Light, isDark = false)
        val Dark: FluentDesignSystem = FluentDesignSystem(FluentPalette.Dark, isDark = true)
    }
}

private fun style(
    size: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    family: FontFamily? = null,
): TextStyle = TextStyle(
    fontSize = size,
    lineHeight = lineHeight,
    fontWeight = weight,
    fontFamily = family,
    // The Fluent 2 ramp tracks at zero at every step. Material's does not, and that
    // difference is visible in a paragraph.
    letterSpacing = 0.sp,
)

/**
 * The nearest of Fluent's published shadow depths.
 *
 * Fluent does not accept an arbitrary height the way Material does. It names six depths
 * (2, 4, 8, 16, 28 and 64), each a specific pair of shadow layers, and a control is at
 * one of them. Rounding an arbitrary dp to the nearest named depth keeps the contract's
 * single dp argument usable without inventing shadows Fluent never specified.
 */
internal fun fluentShadowStep(elevation: Dp): Dp {
    if (elevation <= 0.dp) return 0.dp
    return FluentShadowDepths.minBy { kotlin.math.abs(it.value - elevation.value) }
}

private val FluentShadowDepths = listOf(2.dp, 4.dp, 8.dp, 16.dp, 28.dp, 64.dp)
