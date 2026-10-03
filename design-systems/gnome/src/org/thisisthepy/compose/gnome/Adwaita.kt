package org.thisisthepy.compose.gnome

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
 * GNOME 50, in the Adwaita design language.
 *
 * What the adaptive theme picks when the desktop session identifies itself as GNOME.
 * GNOME publishes no Compose library, so every value below is written here from the
 * GNOME Human Interface Guidelines and the libadwaita stylesheet rather than delegated.
 *
 * Sources, recorded because GNOME's language changes between releases:
 *  - GNOME HIG, "Ui Styling" and "Typography", for the named palette and the title
 *    ladder (developer.gnome.org/hig).
 *  - The libadwaita named colours (`window_bg_color`, `view_bg_color`, `headerbar_bg_color`,
 *    `accent_bg_color`) as of libadwaita 1.x, which is what Adwaita apps actually draw.
 *  - GNOME's 6px spacing grid, which is why the spacing ladder below is multiples of six.
 *
 * What makes an Adwaita screen recognisable, and what this file therefore does:
 *  - Flat surfaces. Raising something draws a hairline border and at most a faint shadow,
 *    never a tint shift, so [elevation] leaves the surface colour alone.
 *  - High contrast. Near white views against near black text in light, near white text on
 *    a dark grey window in dark.
 *  - Restrained accent. Blue appears on the one suggested action and nowhere else, so the
 *    tonal and outlined buttons below are grey rather than tinted blue.
 *  - Generous spacing on a six pixel grid.
 *  - A Cantarell shaped type scale: a small body size with very heavy, widely separated
 *    title sizes above it.
 */
@Immutable
class GnomeDesignSystem private constructor(
    override val isDark: Boolean,
) : DesignSystem {

    override val id: DesignSystemId = DesignSystemId.Gnome

    override fun color(role: ColorRole): Color = if (isDark) darkColor(role) else lightColor(role)

    private fun lightColor(role: ColorRole): Color = when (role) {
        // accent_bg_color / blue 3 from the GNOME palette.
        ColorRole.Primary -> Color(0xFF3584E4)
        ColorRole.OnPrimary -> Color(0xFFFFFFFF)
        // purple 3. Used for the rare second accent, never as a second button colour.
        ColorRole.Secondary -> Color(0xFF9141AC)
        ColorRole.OnSecondary -> Color(0xFFFFFFFF)
        // view_bg_color: the white of a list or a text view.
        ColorRole.Surface -> Color(0xFFFFFFFF)
        ColorRole.OnSurface -> Color(0xFF2E3436)
        // headerbar_bg_color: the slightly darker grey of chrome.
        ColorRole.SurfaceVariant -> Color(0xFFEBEBEB)
        ColorRole.OnSurfaceVariant -> Color(0xFF5E5C64)
        // window_bg_color.
        ColorRole.Background -> Color(0xFFFAFAFA)
        ColorRole.OnBackground -> Color(0xFF2E3436)
        ColorRole.Outline -> Color(0xFFCDC7C2)
        ColorRole.OutlineVariant -> Color(0xFFE6E3E1)
        // red 3, the destructive colour.
        ColorRole.Error -> Color(0xFFE01B24)
        // sidebar_bg_color. An Adwaita card in light is white with a hairline around it,
        // and white on window_bg_color is five parts of grey: the border is what you
        // actually see. A role that has to be visible on the page on its own cannot be
        // that, so the layer is the grey Adwaita already puts beside a view.
        ColorRole.SurfaceContainer -> Color(0xFFEBEBEB)
        ColorRole.OnError -> Color(0xFFFFFFFF)
        // teal 4, the third GNOME accent after blue and purple. One value across both
        // schemes, which is how libadwaita ships its accents.
        ColorRole.Tertiary -> Color(0xFF2190A4)
        ColorRole.OnTertiary -> Color(0xFFFFFFFF)
        // Adwaita tints by drawing the accent at a low alpha over whatever is behind.
        // A role has to answer before it knows what is behind it, so these are that same
        // tint resolved against the window colour, one per accent.
        ColorRole.PrimaryContainer -> Color(0xFFD4E5FB)
        ColorRole.OnPrimaryContainer -> Color(0xFF0D3B70)
        ColorRole.SecondaryContainer -> Color(0xFFECD9F1)
        ColorRole.OnSecondaryContainer -> Color(0xFF45164F)
        ColorRole.TertiaryContainer -> Color(0xFFCFE9ED)
        ColorRole.OnTertiaryContainer -> Color(0xFF0A3D45)
        // Code colours: the same values the renderer's token table holds.
        ColorRole.SyntaxKeyword -> Color(0xFFBA4100)
        ColorRole.SyntaxString -> Color(0xFF007778)
        ColorRole.SyntaxComment -> Color(0xFF6A6A6E)
        ColorRole.SyntaxNumber -> Color(0xFF4E57BA)
        ColorRole.SyntaxConstant -> Color(0xFF4E57BA)
        ColorRole.SyntaxType -> Color(0xFF007778)
        ColorRole.SyntaxFunction -> Color(0xFF0067CD)
        ColorRole.SyntaxVariable -> Color(0xFF3B7800)
        ColorRole.SyntaxProperty -> Color(0xFF504E55)
        ColorRole.SyntaxOperator -> Color(0xFF504E55)
        ColorRole.SyntaxPunctuation -> Color(0xFF504E55)
        ColorRole.SyntaxTag -> Color(0xFF007778)
        ColorRole.SyntaxAttribute -> Color(0xFFBA4100)
        ColorRole.SyntaxEscape -> Color(0xFFD01A2A)
        ColorRole.SyntaxMacro -> Color(0xFFBA4100)
        ColorRole.DiffAdded -> Color(0xFF006A6B)
        ColorRole.DiffRemoved -> Color(0xFFAE2D23)
        ColorRole.DiffModified -> Color(0xFFE66100)
        ColorRole.DiffAddedContainer -> Color(0xFFCFDCDC)
        ColorRole.DiffRemovedContainer -> Color(0xFFE4D4D3)
        ColorRole.DiffAddedEmphasis -> Color(0xFFA5C4C5)
        ColorRole.DiffRemovedEmphasis -> Color(0xFFD9B2AF)
    }

    private fun darkColor(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> Color(0xFF3584E4)
        ColorRole.OnPrimary -> Color(0xFFFFFFFF)
        ColorRole.Secondary -> Color(0xFFC061CB)
        ColorRole.OnSecondary -> Color(0xFFFFFFFF)
        ColorRole.Surface -> Color(0xFF1E1E1E)
        ColorRole.OnSurface -> Color(0xFFFFFFFF)
        ColorRole.SurfaceVariant -> Color(0xFF303030)
        ColorRole.OnSurfaceVariant -> Color(0xFFC0BFBC)
        ColorRole.Background -> Color(0xFF242424)
        ColorRole.OnBackground -> Color(0xFFFFFFFF)
        ColorRole.Outline -> Color(0xFF52514F)
        ColorRole.OutlineVariant -> Color(0xFF3A3A3A)
        // red 1. The darker destructive red loses too much contrast on a dark window.
        ColorRole.Error -> Color(0xFFFF7B63)
        // A step lighter than window_bg_color, which is how dark Adwaita raises a layer.
        ColorRole.SurfaceContainer -> Color(0xFF303030)
        ColorRole.OnError -> Color(0xFF2A0A06)
        // teal 4, the third GNOME accent after blue and purple. One value across both
        // schemes, which is how libadwaita ships its accents.
        ColorRole.Tertiary -> Color(0xFF2190A4)
        ColorRole.OnTertiary -> Color(0xFFFFFFFF)
        // Adwaita tints by drawing the accent at a low alpha over whatever is behind.
        // A role has to answer before it knows what is behind it, so these are that same
        // tint resolved against the window colour, one per accent.
        ColorRole.PrimaryContainer -> Color(0xFF1B3C5E)
        ColorRole.OnPrimaryContainer -> Color(0xFFCFE0F7)
        ColorRole.SecondaryContainer -> Color(0xFF44234C)
        ColorRole.OnSecondaryContainer -> Color(0xFFECD9F1)
        ColorRole.TertiaryContainer -> Color(0xFF134249)
        ColorRole.OnTertiaryContainer -> Color(0xFFCFE9ED)
        // Code colours: the same values the renderer's token table holds.
        ColorRole.SyntaxKeyword -> Color(0xFFFFA348)
        ColorRole.SyntaxString -> Color(0xFF5BC8AF)
        ColorRole.SyntaxComment -> Color(0xFF999898)
        ColorRole.SyntaxNumber -> Color(0xFF8794D2)
        ColorRole.SyntaxConstant -> Color(0xFF8794D2)
        ColorRole.SyntaxType -> Color(0xFF5BC8AF)
        ColorRole.SyntaxFunction -> Color(0xFF62A0EA)
        ColorRole.SyntaxVariable -> Color(0xFF5DAB1E)
        ColorRole.SyntaxProperty -> Color(0xFFC0BFBC)
        ColorRole.SyntaxOperator -> Color(0xFFC0BFBC)
        ColorRole.SyntaxPunctuation -> Color(0xFFC0BFBC)
        ColorRole.SyntaxTag -> Color(0xFF33B2A4)
        ColorRole.SyntaxAttribute -> Color(0xFFFF7800)
        ColorRole.SyntaxEscape -> Color(0xFFFD6656)
        ColorRole.SyntaxMacro -> Color(0xFFFB7018)
        ColorRole.DiffAdded -> Color(0xFF56CEBF)
        ColorRole.DiffRemoved -> Color(0xFFFFA295)
        ColorRole.DiffModified -> Color(0xFFFF7800)
        ColorRole.DiffAddedContainer -> Color(0xFF38504D)
        ColorRole.DiffRemovedContainer -> Color(0xFF594744)
        ColorRole.DiffAddedEmphasis -> Color(0xFF3F6F69)
        ColorRole.DiffRemovedEmphasis -> Color(0xFF835E58)
    }

    /**
     * The Adwaita title ladder.
     *
     * libadwaita's title classes are declared in points against an 11pt Cantarell body,
     * and they are heavy: the largest title is set at weight 800, not 700. The sizes here
     * are those point values carried over to sp, which is why the body is 15 rather than
     * the 14 a Material scale would use.
     *
     * Cantarell itself cannot be bundled, so the family is the platform sans. A GNOME
     * session resolves that to Cantarell anyway.
     */
    override fun type(role: TypeRole): TextStyle = when (role) {
        TypeRole.Display -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 44.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.W800,
            letterSpacing = (-0.5).sp,
        )
        TypeRole.Headline -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.W800,
            letterSpacing = (-0.25).sp,
        )
        TypeRole.Title -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.W700,
        )
        TypeRole.Subtitle -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.W700,
        )
        TypeRole.Body -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.W400,
        )
        TypeRole.BodyStrong -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.W700,
        )
        TypeRole.Label -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = 0.1.sp,
        )
        TypeRole.Caption -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.W400,
        )
        TypeRole.Mono -> TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.W400,
        )
    }

    /**
     * libadwaita's own named radii: 6px on a button or entry, 12px on a card or popover,
     * 15px on a window. Pills are used for suggested actions and for search entries.
     */
    override fun shape(role: ShapeRole): Shape = when (role) {
        ShapeRole.None -> RoundedCornerShape(0.dp)
        ShapeRole.ExtraSmall -> RoundedCornerShape(4.dp)
        ShapeRole.Small -> RoundedCornerShape(6.dp)
        ShapeRole.Medium -> RoundedCornerShape(12.dp)
        ShapeRole.Large -> RoundedCornerShape(15.dp)
        ShapeRole.Full -> RoundedCornerShape(percent = 50)
    }

    /** GNOME lays out on a six pixel grid, and its dialogs are roomy. */
    override fun space(role: SpaceRole): Dp = when (role) {
        SpaceRole.None -> 0.dp
        SpaceRole.Xs -> 3.dp
        SpaceRole.Sm -> 6.dp
        SpaceRole.Md -> 12.dp
        SpaceRole.Lg -> 18.dp
        SpaceRole.Xl -> 24.dp
        SpaceRole.Xxl -> 36.dp
    }

    /**
     * Adwaita separates layers with borders and keeps surfaces flat, so a raised surface
     * keeps its own colour and gets only a shallow shadow. Half the requested dp, because
     * a Material shadow at the same number reads as a floating card rather than a GTK
     * popover.
     */
    override fun elevation(elevation: Dp, base: Color): ElevationStyle = ElevationStyle(
        surface = base,
        shadowElevation = elevation * 0.5f,
        shadowColor = if (isDark) Color(0x80000000) else Color(0x33000000),
    )

    /**
     * Adwaita spends its accent on one button per view. Tonal and outlined buttons are
     * therefore grey, not tinted blue, which is the visible difference from a Material
     * screen where every variant carries the accent hue.
     */
    override fun button(variant: ButtonVariant): ButtonStyle {
        val accent = color(ColorRole.Primary)
        val onAccent = color(ColorRole.OnPrimary)
        val neutral = if (isDark) Color(0xFF383838) else Color(0xFFE6E3E1)
        val neutralPressed = if (isDark) Color(0xFF4A4A4A) else Color(0xFFD2CECB)
        val onNeutral = color(ColorRole.OnSurface)
        return when (variant) {
            ButtonVariant.Filled -> ButtonStyle(
                container = accent,
                content = onAccent,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Small,
                pressedContainer = if (isDark) Color(0xFF1C71D8) else Color(0xFF1C71D8),
                pressedContent = onAccent,
                pressedBorder = null,
                ripple = false,
            )
            ButtonVariant.Tonal -> ButtonStyle(
                container = neutral,
                content = onNeutral,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Small,
                pressedContainer = neutralPressed,
                pressedContent = onNeutral,
                pressedBorder = null,
                ripple = false,
            )
            ButtonVariant.Operator -> ButtonStyle(
                container = neutral,
                content = accent,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Small,
                pressedContainer = neutralPressed,
                pressedContent = accent,
                pressedBorder = null,
                ripple = false,
            )
            ButtonVariant.Outlined -> ButtonStyle(
                container = Color.Transparent,
                content = onNeutral,
                border = color(ColorRole.Outline),
                borderWidth = 1.dp,
                shape = ShapeRole.Small,
                pressedContainer = neutralPressed,
                pressedContent = onNeutral,
                pressedBorder = color(ColorRole.Outline),
                ripple = false,
            )
            ButtonVariant.Text -> ButtonStyle(
                container = Color.Transparent,
                content = onNeutral,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Small,
                pressedContainer = neutral,
                pressedContent = onNeutral,
                pressedBorder = null,
                ripple = false,
            )
        }
    }

    /**
     * GTK state changes are a short ease, and nothing ripples: a pressed GTK button
     * changes fill and stops.
     */
    override val motion: Motion = Motion(
        pressMillis = 200,
        releaseMillis = 200,
        easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f),
    )

    /**
     * `AdwOverlaySplitView`: two columns when there is room, and at a medium width the
     * sidebar is laid over the content rather than squeezing it.
     */
    override fun splitPane(width: org.thisisthepy.compose.designsystem.WidthClass): org.thisisthepy.compose.designsystem.SplitPaneStyle =
        super.splitPane(width).copy(
            presentation = when (width) {
                org.thisisthepy.compose.designsystem.WidthClass.Compact -> org.thisisthepy.compose.designsystem.SplitPanePresentation.Stacked
                org.thisisthepy.compose.designsystem.WidthClass.Medium -> org.thisisthepy.compose.designsystem.SplitPanePresentation.Overlay
                org.thisisthepy.compose.designsystem.WidthClass.Expanded -> org.thisisthepy.compose.designsystem.SplitPanePresentation.SideBySide
            },
            defaultWidth = 260.dp,
            minWidth = 180.dp,
            maxWidth = 360.dp,
            keyStep = 6.dp,
            grabWidth = 6.dp,
            sideBackground = color(ColorRole.SurfaceContainer),
        )

    /**
     * The count pill at the end of a libadwaita sidebar row: beside what it counts rather
     * than on its corner, and written out in full.
     */
    override fun badge(): BadgeStyle = BadgeStyle(
        placement = BadgePlacement.Trailing,
        maxCount = null,
        container = color(ColorRole.Error),
        content = color(ColorRole.OnError),
        height = 20.dp,
        dotSize = 8.dp,
        horizontalPadding = 7.dp,
        shape = ShapeRole.Full,
        labelSize = 12.sp,
        ring = null,
        ringWidth = 0.dp,
    )

    companion object {
        val Light: GnomeDesignSystem = GnomeDesignSystem(isDark = false)
        val Dark: GnomeDesignSystem = GnomeDesignSystem(isDark = true)

        /** The GNOME system for the scheme in effect. */
        fun of(isDark: Boolean): GnomeDesignSystem = if (isDark) Dark else Light
    }
}
