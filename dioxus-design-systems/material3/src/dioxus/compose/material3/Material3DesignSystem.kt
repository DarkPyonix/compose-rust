package dioxus.compose.material3

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import kotlin.math.ln

/**
 * Material 3, resolved by delegating to androidx.compose.material3.
 *
 * Every value here comes out of a real [ColorScheme], [Typography] and [Shapes]. Nothing
 * is redrawn from foundation primitives: a hand written Material button is always a worse
 * Material button, and ripple, state layers, motion and accessibility semantics come free
 * from the library. Where this file does arithmetic (the elevation tint, the pressed state
 * layer) it is reproducing a documented Material rule over colours the caller supplied,
 * not inventing a look.
 *
 * Light and dark are two instances ([Light] and [Dark]) rather than one instance with a
 * flag, so a caller holding a design system never has to also carry the scheme it belongs
 * to.
 */
@Immutable
class Material3DesignSystem(
    private val colorScheme: ColorScheme,
    private val typography: Typography,
    private val shapes: Shapes,
    override val isDark: Boolean,
) : DesignSystem {

    override val id: DesignSystemId = DesignSystemId.Material3

    override fun color(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> colorScheme.primary
        ColorRole.OnPrimary -> colorScheme.onPrimary
        ColorRole.Secondary -> colorScheme.secondary
        ColorRole.OnSecondary -> colorScheme.onSecondary
        ColorRole.Surface -> colorScheme.surface
        ColorRole.OnSurface -> colorScheme.onSurface
        ColorRole.SurfaceVariant -> colorScheme.surfaceVariant
        ColorRole.OnSurfaceVariant -> colorScheme.onSurfaceVariant
        ColorRole.Background -> colorScheme.background
        ColorRole.OnBackground -> colorScheme.onBackground
        ColorRole.Outline -> colorScheme.outline
        ColorRole.OutlineVariant -> colorScheme.outlineVariant
        ColorRole.Error -> colorScheme.error
        ColorRole.OnError -> colorScheme.onError
        // Material 3's own answer to depth: surface and background are one value here,
        // and the container roles are what a raised layer is made of.
        ColorRole.SurfaceContainer -> colorScheme.surfaceContainer
        // The third accent and the container tones are Material's own idea, so these are
        // the scheme's members rather than anything derived here.
        ColorRole.Tertiary -> colorScheme.tertiary
        ColorRole.OnTertiary -> colorScheme.onTertiary
        ColorRole.PrimaryContainer -> colorScheme.primaryContainer
        ColorRole.OnPrimaryContainer -> colorScheme.onPrimaryContainer
        ColorRole.SecondaryContainer -> colorScheme.secondaryContainer
        ColorRole.OnSecondaryContainer -> colorScheme.onSecondaryContainer
        ColorRole.TertiaryContainer -> colorScheme.tertiaryContainer
        ColorRole.OnTertiaryContainer -> colorScheme.onTertiaryContainer
    }

    /**
     * Material 3's type scale has fifteen steps in five families; this ladder has nine.
     * The mapping below picks the member of each family that Material's own components
     * use, and notes the three roles Material has no name for.
     */
    override fun type(role: TypeRole): TextStyle = when (role) {
        // displayLarge is 57sp, which Material reserves for a single short string on a
        // large screen. displayMedium is the size its own samples use for a display.
        TypeRole.Display -> typography.displayMedium
        TypeRole.Headline -> typography.headlineMedium
        TypeRole.Title -> typography.titleLarge
        // No counterpart: Material has no "subtitle". titleMedium is the nearest, being
        // the step Material puts directly under titleLarge in list and dialog headers.
        TypeRole.Subtitle -> typography.titleMedium
        TypeRole.Body -> typography.bodyLarge
        // No counterpart: the Material scale carries no bold body step, because Material
        // emphasises with colour and size rather than weight. Body at a heavier weight is
        // the closest thing that still reads as the same paragraph.
        TypeRole.BodyStrong -> typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        TypeRole.Label -> typography.labelLarge
        TypeRole.Caption -> typography.labelSmall
        // No counterpart: Material's scale names no monospaced style, and font resources
        // do not cross this library's boundary, so this asks the platform for its generic
        // monospaced family at body metrics.
        TypeRole.Mono -> typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    }

    override fun shape(role: ShapeRole): Shape = when (role) {
        ShapeRole.None -> RectangleShape
        ShapeRole.ExtraSmall -> shapes.extraSmall
        ShapeRole.Small -> shapes.small
        ShapeRole.Medium -> shapes.medium
        ShapeRole.Large -> shapes.large
        // Material's Shapes ends at extraLarge (28dp), which is a large radius and not a
        // pill. Fully rounded is what Material's own buttons and FABs clip to, and that
        // is CircleShape rather than a Shapes entry.
        ShapeRole.Full -> CircleShape
    }

    /**
     * MaterialTheme exposes no spacing scale, so these are not delegation: they are the
     * 4dp grid the Material 3 layout guidance is written on, with the 16dp body margin
     * and 24dp pane gutter landing on Md and Lg where Material's own components put them.
     */
    override fun space(role: SpaceRole): Dp = when (role) {
        SpaceRole.None -> 0.dp
        SpaceRole.Xs -> 4.dp
        SpaceRole.Sm -> 8.dp
        SpaceRole.Md -> 16.dp
        SpaceRole.Lg -> 24.dp
        SpaceRole.Xl -> 32.dp
        SpaceRole.Xxl -> 48.dp
    }

    /**
     * Material raises a surface by tinting it toward the primary colour and casting a
     * shadow at the same height. Both happen; neither alone is Material.
     */
    override fun elevation(elevation: Dp, base: Color): ElevationStyle = ElevationStyle(
        surface = colorScheme.surfaceTint
            .copy(alpha = surfaceTintAlpha(elevation))
            .compositeOver(base),
        shadowElevation = elevation,
        // Compose draws shadows in black and varies their spread with elevation. Material
        // does not colour shadows.
        shadowColor = Color.Black,
        // Material has no lit top edge. Leaving this null is the statement, not an
        // omission: drawing one would make this look like Fluent.
        strokeTop = null,
    )

    override fun button(variant: ButtonVariant): ButtonStyle = when (variant) {
        ButtonVariant.Filled -> buttonStyle(
            container = colorScheme.primary,
            content = colorScheme.onPrimary,
            border = null,
        )
        ButtonVariant.Tonal -> buttonStyle(
            container = colorScheme.secondaryContainer,
            content = colorScheme.onSecondaryContainer,
            border = null,
        )
        ButtonVariant.Outlined -> buttonStyle(
            container = Color.Transparent,
            content = colorScheme.primary,
            border = colorScheme.outline,
        )
        ButtonVariant.Text -> buttonStyle(
            container = Color.Transparent,
            content = colorScheme.primary,
            border = null,
        )
        ButtonVariant.Operator -> buttonStyle(
            container = colorScheme.secondaryContainer,
            content = colorScheme.primary,
            border = null,
        )
    }

    /**
     * A pressed Material button darkens by laying its content colour over its container
     * at the pressed state layer opacity. The container changes, the content does not,
     * and the ripple runs on top of both.
     */
    private fun buttonStyle(container: Color, content: Color, border: Color?): ButtonStyle =
        ButtonStyle(
            container = container,
            content = content,
            border = border,
            borderWidth = if (border == null) 0.dp else 1.dp,
            // Material buttons are fully rounded, not merely large cornered.
            shape = ShapeRole.Full,
            pressedContainer = content.copy(alpha = PRESSED_STATE_LAYER).compositeOver(container),
            pressedContent = content,
            pressedBorder = border,
            ripple = true,
        )

    /**
     * Material's standard easing and the 100ms step its state changes run at. Material 3
     * also publishes an expressive motion scheme, which Compose still marks experimental,
     * so this stays on the value that will not move under a version bump.
     */
    override val motion: Motion = Motion(
        pressMillis = 100,
        releaseMillis = 100,
        easing = StandardEasing,
    )

    companion object {

        /** Material 3's baseline scheme, unmodified, for a light surface. */
        val Light: Material3DesignSystem = Material3DesignSystem(
            colorScheme = lightColorScheme(),
            typography = Typography(),
            shapes = Shapes(),
            isDark = false,
        )

        /** The same, for a dark one. */
        val Dark: Material3DesignSystem = Material3DesignSystem(
            colorScheme = darkColorScheme(),
            typography = Typography(),
            shapes = Shapes(),
            isDark = true,
        )
    }
}

/**
 * The design system for whatever MaterialTheme is in scope here.
 *
 * Use this when the application already owns its Material theme, for instance because it
 * derived a scheme from a wallpaper or a brand colour. Anything MaterialTheme is told,
 * this reports.
 */
@Composable
@ReadOnlyComposable
fun materialDesignSystem(isDark: Boolean): Material3DesignSystem =
    Material3DesignSystem(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        isDark = isDark,
    )

/** The state layer opacity Material applies to a pressed component. */
private const val PRESSED_STATE_LAYER = 0.10f

/**
 * Material's standard easing curve, the one its state changes and small transitions use.
 */
private val StandardEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * How strongly Material tints a surface at a given height.
 *
 * This is Material 3's published elevation overlay curve. It lives here rather than being
 * called on the colour scheme because the contract hands in an arbitrary base colour,
 * while the library's own helper always tints its surface colour.
 */
private fun surfaceTintAlpha(elevation: Dp): Float {
    if (elevation.value <= 0f) return 0f
    return ((4.5f * ln(elevation.value + 1f)) + 2f) / 100f
}
