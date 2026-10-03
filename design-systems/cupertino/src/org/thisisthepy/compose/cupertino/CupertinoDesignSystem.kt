package org.thisisthepy.compose.cupertino

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.thisisthepy.compose.designsystem.BadgePlacement
import org.thisisthepy.compose.designsystem.BadgeStyle
import org.thisisthepy.compose.designsystem.ButtonStyle
import org.thisisthepy.compose.designsystem.ButtonVariant
import org.thisisthepy.compose.designsystem.ColorRole
import org.thisisthepy.compose.designsystem.DesignSystem
import org.thisisthepy.compose.designsystem.DesignSystemId
import org.thisisthepy.compose.designsystem.ElevationStyle
import org.thisisthepy.compose.designsystem.Motion
import org.thisisthepy.compose.designsystem.ShapeRole
import org.thisisthepy.compose.designsystem.SpaceRole
import org.thisisthepy.compose.designsystem.SurfaceMaterial
import org.thisisthepy.compose.designsystem.TypeRole
import org.thisisthepy.compose.liquidglass.CapsuleShape
import org.thisisthepy.compose.liquidglass.ContinuousCornerShape
import org.thisisthepy.compose.liquidglass.GlassProminence
import org.thisisthepy.compose.liquidglass.LiquidGlass

/**
 * Apple's design language, as of macOS 26 and iOS 26.
 *
 * Four things separate this from the other systems here, and none of them is a colour
 * choice.
 *
 * Corners are continuous rather than circular, so the curvature rises out of the straight
 * edge instead of starting abruptly at a tangent point. An element nested inside a
 * container gets a radius cut for that container rather than a radius of its own, which
 * is why the corner ladder is only half the story and [org.thisisthepy.compose.liquidglass.inset]
 * is the other half.
 *
 * Surfaces are a material, not a fill. A card here answers with
 * [SurfaceMaterial.Glass]: translucent, edge lit along the top and shaded along the
 * bottom, with the app content underneath showing through blurred. The limit is worth
 * stating rather than glossing: Compose cannot sample what is behind the window, so the
 * glass reflects what this application drew and nothing else. That is the honest extent
 * of it, and it is most of what makes a screen read as iOS 26.
 *
 * Pressing dims. There is no ripple anywhere in this system, which is why
 * [ButtonStyle.ripple] exists as a value the design system answers rather than as
 * something a widget decides.
 *
 * Raising a surface casts a wide soft shadow and changes nothing else. Apple never tints
 * a raised surface the way Material does, and never draws Fluent's lit hairline along the
 * top of a card; the lit edge belongs to the glass material, where it is a rim all the way
 * round rather than a line across the top.
 */
@Immutable
class CupertinoDesignSystem private constructor(
    private val palette: CupertinoPalette,
    override val isDark: Boolean,
) : DesignSystem {

    override val id: DesignSystemId = DesignSystemId.Cupertino

    override fun color(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> palette.accent
        ColorRole.OnPrimary -> palette.onAccent
        ColorRole.Secondary -> palette.accentSecondary
        ColorRole.OnSecondary -> palette.onAccentSecondary
        ColorRole.Surface -> palette.surface
        ColorRole.OnSurface -> palette.label
        ColorRole.SurfaceVariant -> palette.surfaceVariant
        ColorRole.OnSurfaceVariant -> palette.labelSecondary
        ColorRole.Background -> palette.canvas
        ColorRole.OnBackground -> palette.label
        ColorRole.Outline -> palette.separator
        ColorRole.OutlineVariant -> palette.separatorFaint
        ColorRole.Error -> palette.danger
        ColorRole.OnError -> palette.onDanger
        // A grouped box on the grouped page is the plain reading surface, and what makes
        // it a panel is that the page underneath it is not.
        ColorRole.SurfaceContainer -> palette.surface
        ColorRole.Tertiary -> palette.accentTertiary
        ColorRole.OnTertiary -> palette.onAccentTertiary
        ColorRole.PrimaryContainer -> palette.accentContainer
        ColorRole.OnPrimaryContainer -> palette.onAccentContainer
        ColorRole.SecondaryContainer -> palette.accentSecondaryContainer
        ColorRole.OnSecondaryContainer -> palette.onAccentSecondaryContainer
        ColorRole.TertiaryContainer -> palette.accentTertiaryContainer
        ColorRole.OnTertiaryContainer -> palette.onAccentTertiaryContainer
        // Code colours, the same values the renderer's token table holds.
        ColorRole.SyntaxKeyword,
        ColorRole.SyntaxString,
        ColorRole.SyntaxComment,
        ColorRole.SyntaxNumber,
        ColorRole.SyntaxConstant,
        ColorRole.SyntaxType,
        ColorRole.SyntaxFunction,
        ColorRole.SyntaxVariable,
        ColorRole.SyntaxProperty,
        ColorRole.SyntaxOperator,
        ColorRole.SyntaxPunctuation,
        ColorRole.SyntaxTag,
        ColorRole.SyntaxAttribute,
        ColorRole.SyntaxEscape,
        ColorRole.SyntaxMacro,
        ColorRole.DiffAdded,
        ColorRole.DiffRemoved,
        ColorRole.DiffModified,
        ColorRole.DiffAddedContainer,
        ColorRole.DiffRemovedContainer,
        ColorRole.DiffAddedEmphasis,
        ColorRole.DiffRemovedEmphasis,
        -> codeColor(role, isDark)
    }

    /**
     * The San Francisco text styles, by their Apple names.
     *
     * Font resources do not cross this library's boundary, so these ask for the platform
     * default face, which is San Francisco on Apple platforms and the nearest neutral
     * grotesque elsewhere. What can be carried without the font file is the part that
     * makes a paragraph look like Apple's rather than Google's or Microsoft's: the
     * metrics, and the negative tracking that SF applies from the body size upwards and
     * relaxes at caption sizes.
     */
    override fun type(role: TypeRole): TextStyle = when (role) {
        // Large Title.
        TypeRole.Display -> style(34.sp, 41.sp, FontWeight.Bold, (-0.4).sp)
        // Title 1.
        TypeRole.Headline -> style(28.sp, 34.sp, FontWeight.Bold, (-0.4).sp)
        // Title 2.
        TypeRole.Title -> style(22.sp, 28.sp, FontWeight.SemiBold, (-0.3).sp)
        // Title 3.
        TypeRole.Subtitle -> style(20.sp, 25.sp, FontWeight.SemiBold, (-0.3).sp)
        // Body, and Headline which is the same size in semibold. Apple's naming puts
        // Headline below Body in the ramp for exactly this reason: it is an emphasis, not
        // a size.
        TypeRole.Body -> style(17.sp, 22.sp, FontWeight.Normal, (-0.4).sp)
        TypeRole.BodyStrong -> style(17.sp, 22.sp, FontWeight.SemiBold, (-0.4).sp)
        // Subheadline, the size a control is labelled at.
        TypeRole.Label -> style(15.sp, 20.sp, FontWeight.Medium, (-0.2).sp)
        // Footnote. SF stops tightening here and starts opening up.
        TypeRole.Caption -> style(13.sp, 18.sp, FontWeight.Normal, 0.sp)
        // SF Mono has no step of its own in the ramp. Body metrics keep code sitting on
        // the same baseline grid as the prose around it.
        TypeRole.Mono -> style(17.sp, 22.sp, FontWeight.Normal, 0.sp, FontFamily.Monospace)
    }

    /**
     * Continuous corners at every step, which is the point.
     *
     * A caller that needs an element to stay concentric with its container asks for the
     * container's shape and insets it, rather than picking a smaller step off this
     * ladder: two steps of a ladder are concentric only by coincidence, and the
     * coincidence breaks the moment the inset changes.
     */
    override fun shape(role: ShapeRole): Shape = when (role) {
        ShapeRole.None -> RectangleShape
        ShapeRole.ExtraSmall -> ContinuousCornerShape(6.dp)
        ShapeRole.Small -> ContinuousCornerShape(10.dp)
        // The radius of a standard control and of a grouped table section.
        ShapeRole.Medium -> ContinuousCornerShape(12.dp)
        // A sheet or a card.
        ShapeRole.Large -> ContinuousCornerShape(20.dp)
        ShapeRole.Full -> CapsuleShape
    }

    /**
     * Apple's spacing, which is looser than Fluent's and lands on a different grid from
     * Material's. The standard content inset on both platforms is 16, and the gap between
     * grouped sections is 20, so those two sit next to each other in the middle of the
     * ladder rather than a step apart.
     */
    override fun space(role: SpaceRole): Dp = when (role) {
        SpaceRole.None -> 0.dp
        SpaceRole.Xs -> 4.dp
        SpaceRole.Sm -> 8.dp
        SpaceRole.Md -> 16.dp
        SpaceRole.Lg -> 20.dp
        SpaceRole.Xl -> 28.dp
        SpaceRole.Xxl -> 40.dp
    }

    /**
     * A wide, soft, untinted shadow.
     *
     * The surface colour comes back exactly as it went in. A raised Apple card is the
     * same colour as a flat one, and the depth comes from the shadow and from the layer
     * sitting over what it covers. There is no top stroke here either: that is Fluent's
     * way of suggesting a lit slab, and drawing both would be two design systems at once.
     */
    override fun elevation(elevation: Dp, base: Color): ElevationStyle = ElevationStyle(
        surface = base,
        // Apple's shadows spread further than their height suggests, which is what makes
        // them read as soft rather than as a drop shadow under a rectangle.
        shadowElevation = if (elevation <= 0.dp) 0.dp else elevation * SHADOW_SPREAD,
        shadowColor = palette.shadow,
        strokeTop = null,
    )

    /**
     * Apple's button weights under this project's neutral variant names.
     *
     * Filled is the tinted prominent button: system blue, white label, continuous corner,
     * no shadow. Tonal is the grey button, which keeps the tint on its label rather than
     * in its fill. Outlined is the bordered variant, tinted stroke over nothing. Text is
     * plain: the label in the tint colour and no container at all, which is what most
     * buttons in a navigation bar or an alert are.
     *
     * Every one of them dims on press instead of rippling. Apple's press feedback is a
     * uniform drop in intensity across the whole control, so the pressed label moves with
     * the pressed container rather than staying put while a wave crosses under it.
     */
    override fun button(variant: ButtonVariant): ButtonStyle = when (variant) {
        ButtonVariant.Filled, ButtonVariant.Operator -> ButtonStyle(
            container = palette.accent,
            content = palette.onAccent,
            border = null,
            borderWidth = 0.dp,
            shape = ShapeRole.Medium,
            pressedContainer = palette.accentPressed,
            pressedContent = palette.onAccent.dimmed(),
            pressedBorder = null,
            ripple = false,
        )
        ButtonVariant.Tonal -> ButtonStyle(
            container = palette.fill,
            content = palette.accent,
            border = null,
            borderWidth = 0.dp,
            shape = ShapeRole.Medium,
            pressedContainer = palette.fillPressed,
            pressedContent = palette.accent.dimmed(),
            pressedBorder = null,
            ripple = false,
        )
        ButtonVariant.Outlined -> ButtonStyle(
            container = Color.Transparent,
            content = palette.accent,
            border = palette.accent,
            borderWidth = 1.dp,
            shape = ShapeRole.Medium,
            // A bordered button has no fill to dim, so the press shows as a faint tint
            // wash under it together with the dimmed stroke and label.
            pressedContainer = palette.accent.copy(alpha = PRESS_WASH_ALPHA),
            pressedContent = palette.accent.dimmed(),
            pressedBorder = palette.accent.dimmed(),
            ripple = false,
        )
        ButtonVariant.Text -> ButtonStyle(
            container = Color.Transparent,
            content = palette.accent,
            border = null,
            borderWidth = 0.dp,
            shape = ShapeRole.Medium,
            pressedContainer = Color.Transparent,
            pressedContent = palette.accent.dimmed(),
            pressedBorder = null,
            ripple = false,
        )
    }

    /**
     * Quick in, slower out, on the curve Apple settles controls with.
     *
     * The press has to land with the finger. The release is allowed to be seen, and is
     * where the ease-out does its work.
     */
    override val motion: Motion = Motion(
        pressMillis = 100,
        releaseMillis = 250,
        easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f),
    )

    /**
     * Surfaces are glass; the colours drawn on them are not.
     *
     * [ColorRole.Surface] and [ColorRole.SurfaceVariant] are the roles a container is
     * filled with, and those become glass. Surface carries text and controls and so takes
     * the regular recipe, which has to win against whatever is behind it. SurfaceVariant
     * is the recessed region inside a card, where the point is that it is a shade of what
     * surrounds it, so it takes the clear recipe and lets more through.
     *
     * The window canvas stays opaque. There is nothing of ours behind it to show through,
     * and glass over nothing is a tinted rectangle that costs a blur pass.
     *
     * Every glass material is built against the content colour it will have to carry, so
     * the fallback it stores is readable before it is ever needed. Nothing here has to
     * recompute contrast at draw time.
     */
    override fun material(role: ColorRole): SurfaceMaterial = when (role) {
        // A panel is a layer over the page, which is exactly what the regular glass
        // recipe is for, so the layer role answers the same material as the surface.
        ColorRole.Surface, ColorRole.SurfaceContainer -> LiquidGlass.material(
            dark = isDark,
            prominence = GlassProminence.Regular,
            backdrop = palette.canvas,
            content = palette.label,
        )
        ColorRole.SurfaceVariant -> LiquidGlass.material(
            dark = isDark,
            prominence = GlassProminence.Clear,
            backdrop = palette.surface,
            content = palette.labelSecondary,
        )
        else -> SurfaceMaterial.Opaque(color(role))
    }

    /** `NSSplitView`: a hairline in the separator colour and a sidebar that folds when pushed. */
    override fun splitPane(width: org.thisisthepy.compose.designsystem.WidthClass): org.thisisthepy.compose.designsystem.SplitPaneStyle =
        super.splitPane(width).copy(
            defaultWidth = 260.dp,
            minWidth = 180.dp,
            collapseDistance = 60.dp,
            keyStep = 10.dp,
            lineColor = color(ColorRole.Outline),
            grabWidth = 9.dp,
            sideBackground = color(ColorRole.Background),
        )

    /**
     * Apple's red count on a tab bar item or an app icon: over the corner, written out in
     * full however large, with the larger dot Apple uses for "something new".
     */
    override fun badge(): BadgeStyle = BadgeStyle(
        placement = BadgePlacement.Overlap,
        maxCount = null,
        container = color(ColorRole.Error),
        content = color(ColorRole.OnError),
        height = 18.dp,
        dotSize = 10.dp,
        horizontalPadding = 5.dp,
        shape = ShapeRole.Full,
        labelSize = 13.sp,
        ring = null,
        ringWidth = 0.dp,
    )

    companion object {
        val Light: CupertinoDesignSystem =
            CupertinoDesignSystem(CupertinoPalette.Light, isDark = false)
        val Dark: CupertinoDesignSystem =
            CupertinoDesignSystem(CupertinoPalette.Dark, isDark = true)

        /** How far past its nominal height an Apple shadow spreads. */
        internal const val SHADOW_SPREAD: Float = 1.75f

        /** How much a pressed control loses. */
        internal const val PRESS_DIM: Float = 0.7f

        /** The faint tint a bordered button takes while held. */
        internal const val PRESS_WASH_ALPHA: Float = 0.12f
    }
}

/**
 * [this] at the intensity a pressed Cupertino control shows.
 *
 * Apple dims by lowering the opacity of the control rather than by mixing in grey, which
 * is why this reduces alpha instead of moving towards a colour. Over any background it
 * reads as the control stepping back, and it works the same in light and dark without two
 * sets of pressed colours.
 */
internal fun Color.dimmed(): Color =
    copy(alpha = alpha * CupertinoDesignSystem.PRESS_DIM)

private fun style(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
    tracking: TextUnit,
    family: FontFamily? = null,
): TextStyle = TextStyle(
    fontSize = size,
    lineHeight = lineHeight,
    fontWeight = weight,
    fontFamily = family,
    letterSpacing = tracking,
)

/**
 * The code colours, after Xcode's Default (Light) and Default (Dark), moved in tone where a
 * value missed 4.5:1 on the panel. Unverified: Xcode's themes are not published as files, so
 * these need checking against Xcode itself. The same values as the renderer's token table, so
 * a code block reads the same through either.
 */
private val LIGHT_CODE: Map<ColorRole, Color> = mapOf(
    ColorRole.SyntaxKeyword to Color(0xFF9B2393),
    ColorRole.SyntaxString to Color(0xFFC41A16),
    ColorRole.SyntaxComment to Color(0xFF5D6C79),
    ColorRole.SyntaxNumber to Color(0xFF1C00CF),
    ColorRole.SyntaxConstant to Color(0xFF6C36A9),
    ColorRole.SyntaxType to Color(0xFF3900A0),
    ColorRole.SyntaxFunction to Color(0xFF326D74),
    ColorRole.SyntaxVariable to Color(0xFF000000),
    ColorRole.SyntaxProperty to Color(0xFF326D74),
    ColorRole.SyntaxOperator to Color(0xFF000000),
    ColorRole.SyntaxPunctuation to Color(0xFF000000),
    ColorRole.SyntaxTag to Color(0xFF0B4F79),
    ColorRole.SyntaxAttribute to Color(0xFF815F03),
    ColorRole.SyntaxEscape to Color(0xFF1C00CF),
    ColorRole.SyntaxMacro to Color(0xFF643820),
    ColorRole.DiffAdded to Color(0xFF087A2F),
    ColorRole.DiffRemoved to Color(0xFFCE0014),
    ColorRole.DiffModified to Color(0xFF007AFF),
    ColorRole.DiffAddedContainer to Color(0xFFE1EFE6),
    ColorRole.DiffRemovedContainer to Color(0xFFF9E0E3),
    ColorRole.DiffAddedEmphasis to Color(0xFFB5D7C1),
    ColorRole.DiffRemovedEmphasis to Color(0xFFF0B3B9),
)

private val DARK_CODE: Map<ColorRole, Color> = mapOf(
    ColorRole.SyntaxKeyword to Color(0xFFFC5FA3),
    ColorRole.SyntaxString to Color(0xFFFC6A5D),
    ColorRole.SyntaxComment to Color(0xFF798693),
    ColorRole.SyntaxNumber to Color(0xFFD0BF69),
    ColorRole.SyntaxConstant to Color(0xFFA167E6),
    ColorRole.SyntaxType to Color(0xFFD0A8FF),
    ColorRole.SyntaxFunction to Color(0xFF67B7A4),
    ColorRole.SyntaxVariable to Color(0xFFDFDFE0),
    ColorRole.SyntaxProperty to Color(0xFF67B7A4),
    ColorRole.SyntaxOperator to Color(0xFFDFDFE0),
    ColorRole.SyntaxPunctuation to Color(0xFFDFDFE0),
    ColorRole.SyntaxTag to Color(0xFF5DD8FF),
    ColorRole.SyntaxAttribute to Color(0xFFBF8555),
    ColorRole.SyntaxEscape to Color(0xFFD0BF69),
    ColorRole.SyntaxMacro to Color(0xFFFD8F3F),
    ColorRole.DiffAdded to Color(0xFF30D158),
    ColorRole.DiffRemoved to Color(0xFFFF6E66),
    ColorRole.DiffModified to Color(0xFF0A84FF),
    ColorRole.DiffAddedContainer to Color(0xFF20402A),
    ColorRole.DiffRemovedContainer to Color(0xFF492C2C),
    ColorRole.DiffAddedEmphasis to Color(0xFF246435),
    ColorRole.DiffRemovedEmphasis to Color(0xFF773D3B),
)

private fun codeColor(role: ColorRole, dark: Boolean): Color =
    (if (dark) DARK_CODE else LIGHT_CODE).getValue(role)
