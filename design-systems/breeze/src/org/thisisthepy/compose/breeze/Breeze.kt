package org.thisisthepy.compose.breeze

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import org.thisisthepy.compose.designsystem.TypeRole

/**
 * KDE Breeze, the Plasma design language.
 *
 * What the adaptive theme picks when the desktop session identifies itself as KDE. KDE
 * ships no Compose library either, so these values are written from Plasma's own
 * material rather than delegated to one.
 *
 * Sources:
 *  - The Breeze colour scheme files shipped with Plasma (`BreezeLight.colors`,
 *    `BreezeDark.colors`), which is where the window, view, text and highlight colours
 *    come from.
 *  - The KDE Human Interface Guidelines for the ladder of spacings and the statement that
 *    Breeze frames are drawn with a single pixel line.
 *  - Kirigami's unit scale (small spacing 4, large spacing 8, grid unit 18), which is what
 *    Plasma layouts actually measure in.
 *
 * What makes a Breeze screen recognisable, and what this file therefore does:
 *  - Thin, precise strokes. Every frame is a hairline, and outlined controls stay
 *    outlined rather than filling on press.
 *  - Small corner radii. Breeze rounds by two to six pixels, so a Breeze button next to
 *    an Adwaita one reads as square.
 *  - A cooler palette. The greys are blue leaning and the accent is a bright cyan blue
 *    rather than GNOME's deeper primary blue.
 *  - Denser spacing. The ladder is roughly two thirds of Adwaita's at every step.
 */
@Immutable
class BreezeDesignSystem private constructor(
    override val isDark: Boolean,
) : DesignSystem {

    override val id: DesignSystemId = DesignSystemId.Breeze

    override fun color(role: ColorRole): Color = if (isDark) darkColor(role) else lightColor(role)

    private fun lightColor(role: ColorRole): Color = when (role) {
        // Breeze "Plasma blue", the default highlight in both schemes.
        ColorRole.Primary -> Color(0xFF3DAEE9)
        // Breeze puts white on the highlight, which measures under three to one against
        // this blue. Dark ink keeps the same blue and stays readable at small sizes.
        ColorRole.OnPrimary -> Color(0xFF06222E)
        // Breeze "positive" teal, its second accent. Dark ink again, for the same reason
        // as the highlight: white on this green measures about two to one, which is under
        // the bound for a label on an accent fill.
        ColorRole.Secondary -> Color(0xFF16A085)
        ColorRole.OnSecondary -> Color(0xFF03201B)
        // View background: the white of a list or an entry.
        ColorRole.Surface -> Color(0xFFFCFCFC)
        ColorRole.OnSurface -> Color(0xFF232629)
        // A step deeper than the window, in Breeze's own grey. The two used to carry the
        // same value, so a filled container sitting on the window was invisible: same
        // colour, no border, nothing to see.
        ColorRole.SurfaceVariant -> Color(0xFFE1E3E5)
        ColorRole.OnSurfaceVariant -> Color(0xFF4D5052)
        // Window background, a touch cooler and darker than the view.
        ColorRole.Background -> Color(0xFFEFF0F1)
        ColorRole.OnBackground -> Color(0xFF232629)
        ColorRole.Outline -> Color(0xFFBDC3C7)
        ColorRole.OutlineVariant -> Color(0xFFD8DBDD)
        // Breeze "negative".
        ColorRole.Error -> Color(0xFFDA4453)
        // The view layer, raised off the window background. Breeze already separates the
        // two, so a panel is the view colour and the window is what it sits on.
        ColorRole.SurfaceContainer -> Color(0xFFFCFCFC)
        ColorRole.OnError -> Color(0xFFFFFFFF)
        // Breeze "neutral", the amber of a warning state, taken as the third accent.
        // Dark ink for the same reason the other two carry it: white on any of these
        // fills measures under three to one.
        ColorRole.Tertiary -> Color(0xFFF67400)
        ColorRole.OnTertiary -> Color(0xFF2B1200)
        // Plasma tints a selected region with the highlight at low alpha over the view.
        // Resolved against the window colour, once per accent, so the role can answer
        // without knowing what it sits on.
        ColorRole.PrimaryContainer -> Color(0xFFD3ECF9)
        ColorRole.OnPrimaryContainer -> Color(0xFF0B3B52)
        ColorRole.SecondaryContainer -> Color(0xFFD2ECE5)
        ColorRole.OnSecondaryContainer -> Color(0xFF083B31)
        ColorRole.TertiaryContainer -> Color(0xFFFAE0C4)
        ColorRole.OnTertiaryContainer -> Color(0xFF4A2C00)
        // Code colours: the same values the renderer's token table holds.
        ColorRole.SyntaxKeyword -> Color(0xFF1F1C1B)
        ColorRole.SyntaxString -> Color(0xFFBF0303)
        ColorRole.SyntaxComment -> Color(0xFF747473)
        ColorRole.SyntaxNumber -> Color(0xFF966D00)
        ColorRole.SyntaxConstant -> Color(0xFFAA5500)
        ColorRole.SyntaxType -> Color(0xFF0057AE)
        ColorRole.SyntaxFunction -> Color(0xFF644A9B)
        ColorRole.SyntaxVariable -> Color(0xFF0057AE)
        ColorRole.SyntaxProperty -> Color(0xFF0057AE)
        ColorRole.SyntaxOperator -> Color(0xFFAF48B1)
        ColorRole.SyntaxPunctuation -> Color(0xFF1F1C1B)
        ColorRole.SyntaxTag -> Color(0xFF1F1C1B)
        ColorRole.SyntaxAttribute -> Color(0xFF0057AE)
        ColorRole.SyntaxEscape -> Color(0xFF007BAC)
        ColorRole.SyntaxMacro -> Color(0xFF006E28)
        ColorRole.DiffAdded -> Color(0xFF0057AE)
        ColorRole.DiffRemoved -> Color(0xFFBF0303)
        ColorRole.DiffModified -> Color(0xFFFDBC4B)
        ColorRole.DiffAddedContainer -> Color(0xFFDEE8F3)
        ColorRole.DiffRemovedContainer -> Color(0xFFF5DEDE)
        ColorRole.DiffAddedEmphasis -> Color(0xFFB0CAE5)
        ColorRole.DiffRemovedEmphasis -> Color(0xFFEAB1B1)
    }

    private fun darkColor(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> Color(0xFF3DAEE9)
        ColorRole.OnPrimary -> Color(0xFF06222E)
        ColorRole.Secondary -> Color(0xFF1ABC9C)
        ColorRole.OnSecondary -> Color(0xFF03201B)
        // Breeze Dark's view background, which is darker than its window background.
        ColorRole.Surface -> Color(0xFF1B1E20)
        ColorRole.OnSurface -> Color(0xFFFCFCFC)
        ColorRole.SurfaceVariant -> Color(0xFF31363B)
        ColorRole.OnSurfaceVariant -> Color(0xFFBDC3C7)
        ColorRole.Background -> Color(0xFF232629)
        ColorRole.OnBackground -> Color(0xFFFCFCFC)
        ColorRole.Outline -> Color(0xFF4D5155)
        // A step lighter than SurfaceVariant. The two carried the same value, so the
        // quieter of the two lines vanished on any container filled with the variant.
        ColorRole.OutlineVariant -> Color(0xFF3F4449)
        ColorRole.Error -> Color(0xFFED8079)
        ColorRole.SurfaceContainer -> Color(0xFF1B1E20)
        ColorRole.OnError -> Color(0xFF2A0806)
        // Breeze "neutral", the amber of a warning state, taken as the third accent.
        // Dark ink for the same reason the other two carry it: white on any of these
        // fills measures under three to one.
        ColorRole.Tertiary -> Color(0xFFF8A44C)
        ColorRole.OnTertiary -> Color(0xFF2B1200)
        // Plasma tints a selected region with the highlight at low alpha over the view.
        // Resolved against the window colour, once per accent, so the role can answer
        // without knowing what it sits on.
        ColorRole.PrimaryContainer -> Color(0xFF123B4F)
        ColorRole.OnPrimaryContainer -> Color(0xFFCDE6F5)
        ColorRole.SecondaryContainer -> Color(0xFF103A32)
        ColorRole.OnSecondaryContainer -> Color(0xFFCFE8E0)
        ColorRole.TertiaryContainer -> Color(0xFF4A3113)
        ColorRole.OnTertiaryContainer -> Color(0xFFF8DFC3)
        // Code colours: the same values the renderer's token table holds.
        ColorRole.SyntaxKeyword -> Color(0xFFCFCFC2)
        ColorRole.SyntaxString -> Color(0xFFF44F4F)
        ColorRole.SyntaxComment -> Color(0xFF848687)
        ColorRole.SyntaxNumber -> Color(0xFFF67400)
        ColorRole.SyntaxConstant -> Color(0xFF27AEAE)
        ColorRole.SyntaxType -> Color(0xFF3B8DC7)
        ColorRole.SyntaxFunction -> Color(0xFFB266D1)
        ColorRole.SyntaxVariable -> Color(0xFF27AEAE)
        ColorRole.SyntaxProperty -> Color(0xFF27AEAE)
        ColorRole.SyntaxOperator -> Color(0xFF519269)
        ColorRole.SyntaxPunctuation -> Color(0xFFCFCFC2)
        ColorRole.SyntaxTag -> Color(0xFFCFCFC2)
        ColorRole.SyntaxAttribute -> Color(0xFF3B8DC7)
        ColorRole.SyntaxEscape -> Color(0xFF3DAEE9)
        ColorRole.SyntaxMacro -> Color(0xFF27AE60)
        ColorRole.DiffAdded -> Color(0xFF2CB1B1)
        ColorRole.DiffRemoved -> Color(0xFFFF736E)
        ColorRole.DiffModified -> Color(0xFFC04900)
        ColorRole.DiffAddedContainer -> Color(0xFF1E3B3D)
        ColorRole.DiffRemovedContainer -> Color(0xFF492F30)
        ColorRole.DiffAddedEmphasis -> Color(0xFF22595A)
        ColorRole.DiffRemovedEmphasis -> Color(0xFF76403F)
    }

    /**
     * Plasma sets its interface in Noto Sans at 10pt, a step smaller than Adwaita's 11pt
     * Cantarell, and its headings are semi bold rather than the near black weights
     * libadwaita uses. The ladder below is that: smaller and lighter at every rung, with
     * tighter line heights to match the denser spacing.
     */
    override fun type(role: TypeRole): TextStyle = when (role) {
        TypeRole.Display -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Headline -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Title -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Subtitle -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.W500,
        )
        TypeRole.Body -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.W400,
        )
        TypeRole.BodyStrong -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Label -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 0.2.sp,
        )
        TypeRole.Caption -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.W400,
        )
        TypeRole.Mono -> TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.W400,
        )
    }

    /**
     * Breeze barely rounds. Frames and buttons are a two to four pixel radius and the
     * largest containers stop at six, which is where the language gets its precise,
     * drafted look.
     */
    override fun shape(role: ShapeRole): Shape = when (role) {
        ShapeRole.None -> RoundedCornerShape(0.dp)
        ShapeRole.ExtraSmall -> RoundedCornerShape(2.dp)
        ShapeRole.Small -> RoundedCornerShape(3.dp)
        ShapeRole.Medium -> RoundedCornerShape(4.dp)
        ShapeRole.Large -> RoundedCornerShape(6.dp)
        ShapeRole.Full -> RoundedCornerShape(percent = 50)
    }

    /** Kirigami's ladder: small spacing 4, large spacing 8, grid unit 18. */
    override fun space(role: SpaceRole): Dp = when (role) {
        SpaceRole.None -> 0.dp
        SpaceRole.Xs -> 2.dp
        SpaceRole.Sm -> 4.dp
        SpaceRole.Md -> 8.dp
        SpaceRole.Lg -> 12.dp
        SpaceRole.Xl -> 18.dp
        SpaceRole.Xxl -> 24.dp
    }

    /**
     * Breeze marks a raised surface with a hairline first and a shadow second: the frame
     * is what tells you where the layer starts, and a light line along the top edge
     * suggests the surface is lifted off the one behind it.
     */
    override fun elevation(elevation: Dp, base: Color): ElevationStyle = ElevationStyle(
        surface = base,
        shadowElevation = elevation * 0.75f,
        shadowColor = if (isDark) Color(0x99000000) else Color(0x40202A33),
        strokeTop = if (isDark) Color(0x2EFFFFFF) else Color(0xFFD8DBDD),
    )

    /**
     * Breeze buttons are frames. Even the accent button is a filled rectangle with a
     * darker line around it, and the standard button is a light fill inside a hairline,
     * so a border survives in three of the four variants.
     */
    override fun button(variant: ButtonVariant): ButtonStyle {
        val accent = color(ColorRole.Primary)
        val onAccent = color(ColorRole.OnPrimary)
        val standard = if (isDark) Color(0xFF31363B) else Color(0xFFFCFCFC)
        val standardPressed = if (isDark) Color(0xFF3B4045) else Color(0xFFDDE0E2)
        val onStandard = color(ColorRole.OnSurface)
        val line = color(ColorRole.Outline)
        return when (variant) {
            ButtonVariant.Filled -> ButtonStyle(
                container = accent,
                content = onAccent,
                border = Color(0xFF2980B9),
                borderWidth = 1.dp,
                shape = ShapeRole.Medium,
                pressedContainer = Color(0xFF2E97CE),
                pressedContent = onAccent,
                pressedBorder = Color(0xFF2980B9),
                ripple = false,
            )
            ButtonVariant.Tonal -> ButtonStyle(
                container = standard,
                content = onStandard,
                border = line,
                borderWidth = 1.dp,
                shape = ShapeRole.Medium,
                pressedContainer = standardPressed,
                pressedContent = onStandard,
                pressedBorder = accent,
                ripple = false,
            )
            ButtonVariant.Operator -> ButtonStyle(
                container = standard,
                content = accent,
                border = line,
                borderWidth = 1.dp,
                shape = ShapeRole.Medium,
                pressedContainer = standardPressed,
                pressedContent = accent,
                pressedBorder = accent,
                ripple = false,
            )
            // Hovering or pressing an outlined Breeze button recolours its line to the
            // highlight rather than filling it in.
            ButtonVariant.Outlined -> ButtonStyle(
                container = Color.Transparent,
                content = onStandard,
                border = line,
                borderWidth = 1.dp,
                shape = ShapeRole.Medium,
                pressedContainer = Color.Transparent,
                pressedContent = accent,
                pressedBorder = accent,
                ripple = false,
            )
            ButtonVariant.Text -> ButtonStyle(
                container = Color.Transparent,
                content = onStandard,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Medium,
                pressedContainer = Color.Transparent,
                pressedContent = accent,
                pressedBorder = null,
                ripple = false,
            )
        }
    }

    /**
     * Plasma's transitions are quick and nearly linear. A Breeze control is meant to feel
     * mechanical, so the press is about half the length of the GNOME one and the release
     * is shorter still.
     */
    override val motion: Motion = Motion(
        pressMillis = 100,
        releaseMillis = 80,
        easing = CubicBezierEasing(0.4f, 0.0f, 0.6f, 1.0f),
    )

    /** `QSplitter` under Breeze: a line in the frame colour with a short grip across it. */
    override fun splitPane(width: org.thisisthepy.compose.designsystem.WidthClass): org.thisisthepy.compose.designsystem.SplitPaneStyle =
        super.splitPane(width).copy(
            defaultWidth = 240.dp,
            minWidth = 160.dp,
            maxWidth = 480.dp,
            lineColor = color(ColorRole.Outline),
            handle = org.thisisthepy.compose.designsystem.SplitHandleStyle(3.dp, 24.dp, color(ColorRole.OnSurfaceVariant), ShapeRole.ExtraSmall),
            grabWidth = 6.dp,
            sideBackground = color(ColorRole.Background),
        )

    /**
     * Plasma's notification count: a small rounded rectangle over the corner, cut at 99,
     * because Breeze's corners are square enough that a capsule would read as another
     * system's.
     */
    override fun badge(): BadgeStyle = BadgeStyle(
        placement = BadgePlacement.Overlap,
        maxCount = 99,
        container = color(ColorRole.Error),
        content = color(ColorRole.OnError),
        height = 16.dp,
        dotSize = 6.dp,
        horizontalPadding = 4.dp,
        shape = ShapeRole.Small,
        labelSize = 10.sp,
        ring = null,
        ringWidth = 0.dp,
    )

    companion object {
        val Light: BreezeDesignSystem = BreezeDesignSystem(isDark = false)
        val Dark: BreezeDesignSystem = BreezeDesignSystem(isDark = true)

        /** The Breeze system for the scheme in effect. */
        fun of(isDark: Boolean): BreezeDesignSystem = if (isDark) Dark else Light
    }
}
