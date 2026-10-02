package dioxus.compose.deepin

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
 * The Linux fallback, in the shape of Deepin's design language.
 *
 * This is what the adaptive theme picks when a Linux session is neither GNOME nor KDE, or
 * when the session cannot be identified at all. That is the job it really has, so it is
 * written to look deliberate on a desktop nobody here has seen rather than to match
 * screenshots of Deepin pixel for pixel. Where Deepin's own values would only make sense
 * beside Deepin's window manager, the value here is the one that carries: rounded and
 * soft, which reads as a finished product on any compositor.
 *
 * Sources:
 *  - The Deepin Design specification and the DTK widget defaults, for the brand blue, the
 *    large window radius and the smaller control radius inside it.
 *  - Deepin's control sizing, which is where the roomy but not GNOME sized spacing ladder
 *    comes from.
 *
 * Only token values and style rules are taken. Deepin's icon set and its bundled typeface
 * carry their own licences and are not used here; the family below is the platform sans.
 *
 * What makes this recognisable next to the other two:
 *  - Larger corner radii. Ten to eighteen pixels where Breeze uses three.
 *  - Soft shadows. A raised surface spreads a wide, low opacity shadow and lightens
 *    slightly, so depth is carried by the shadow rather than by a border.
 *  - Plain neutral greys, against the cool blue greys of Breeze and the slightly warm
 *    off whites of Adwaita, with a brand blue that is bluer than either and a second
 *    accent in amber rather than purple or teal.
 */
@Immutable
class DeepinDesignSystem private constructor(
    override val isDark: Boolean,
) : DesignSystem {

    override val id: DesignSystemId = DesignSystemId.Deepin

    override fun color(role: ColorRole): Color = if (isDark) darkColor(role) else lightColor(role)

    private fun lightColor(role: ColorRole): Color = when (role) {
        // Deepin's brand blue.
        ColorRole.Primary -> Color(0xFF0081FF)
        ColorRole.OnPrimary -> Color(0xFFFFFFFF)
        // The amber that sits beside the blue in Deepin's own readouts.
        ColorRole.Secondary -> Color(0xFFF2A13C)
        ColorRole.OnSecondary -> Color(0xFF2B1A05)
        // The reading surface: the white of a list.
        ColorRole.Surface -> Color(0xFFFFFFFF)
        ColorRole.OnSurface -> Color(0xFF202020)
        // The fill of a search field, and of the alternate row in a list.
        ColorRole.SurfaceVariant -> Color(0xFFE6E6E6)
        ColorRole.OnSurfaceVariant -> Color(0xFF5A5A5A)
        // The window, which is the reading surface itself: a Deepin window is white and
        // what separates from it is the grey well beside the content.
        ColorRole.Background -> Color(0xFFFFFFFF)
        ColorRole.OnBackground -> Color(0xFF202020)
        ColorRole.Outline -> Color(0xFFCDCDCD)
        ColorRole.OutlineVariant -> Color(0xFFE0E0E0)
        ColorRole.Error -> Color(0xFFFF5736)
        // The grey of a sidebar sunk into the white window.
        ColorRole.SurfaceContainer -> Color(0xFFF1F1F1)
        ColorRole.OnError -> Color(0xFFFFFFFF)
        // The violet of the deepin palette, the one accent that is neither the brand
        // blue nor a warm colour.
        ColorRole.Tertiary -> Color(0xFF7A5BD6)
        ColorRole.OnTertiary -> Color(0xFFFFFFFF)
        // Tints of the three accents, resolved neutrally against the page colour.
        ColorRole.PrimaryContainer -> Color(0xFFD8ECFF)
        ColorRole.OnPrimaryContainer -> Color(0xFF00407F)
        ColorRole.SecondaryContainer -> Color(0xFFFDF0E1)
        ColorRole.OnSecondaryContainer -> Color(0xFF79501E)
        ColorRole.TertiaryContainer -> Color(0xFFEBE6F8)
        ColorRole.OnTertiaryContainer -> Color(0xFF3D2D6B)
    }

    private fun darkColor(role: ColorRole): Color = when (role) {
        // Lifted off the brand blue, which goes muddy against a dark window.
        ColorRole.Primary -> Color(0xFF3BA2FF)
        ColorRole.OnPrimary -> Color(0xFF04203A)
        ColorRole.Secondary -> Color(0xFFFFB964)
        ColorRole.OnSecondary -> Color(0xFF33200A)
        // The grey of a key on the near black window.
        ColorRole.Surface -> Color(0xFF2A2A2A)
        ColorRole.OnSurface -> Color(0xFFF0F0F0)
        ColorRole.SurfaceVariant -> Color(0xFF3A3A3A)
        ColorRole.OnSurfaceVariant -> Color(0xFFB4B4B4)
        ColorRole.Background -> Color(0xFF1A1A1A)
        ColorRole.OnBackground -> Color(0xFFF0F0F0)
        ColorRole.Outline -> Color(0xFF4D4D4D)
        ColorRole.OutlineVariant -> Color(0xFF333333)
        ColorRole.Error -> Color(0xFFFF8A73)
        // Lighter than the window, so the panel lifts rather than sinks.
        ColorRole.SurfaceContainer -> Color(0xFF2A2A2A)
        ColorRole.OnError -> Color(0xFF34110A)
        // The violet of the deepin palette, the one accent that is neither the brand
        // blue nor a warm colour.
        ColorRole.Tertiary -> Color(0xFF9F8AE3)
        ColorRole.OnTertiary -> Color(0xFF1D0F45)
        // Tints of the three accents, resolved neutrally against the page colour.
        ColorRole.PrimaryContainer -> Color(0xFF203547)
        ColorRole.OnPrimaryContainer -> Color(0xFF9DD0FF)
        ColorRole.SecondaryContainer -> Color(0xFF473928)
        ColorRole.OnSecondaryContainer -> Color(0xFFFFDCB1)
        ColorRole.TertiaryContainer -> Color(0xFF343042)
        ColorRole.OnTertiaryContainer -> Color(0xFFCFC4F1)
    }

    /**
     * A middle weight ladder. The body sits between Breeze's 13 and Adwaita's 15, and the
     * headings are semi bold with loose line heights, which suits the rounded shapes and
     * survives on a desktop whose default font is unknown.
     */
    override fun type(role: TypeRole): TextStyle = when (role) {
        TypeRole.Display -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 40.sp,
            lineHeight = 50.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Headline -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Title -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.W500,
        )
        TypeRole.Subtitle -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.W500,
        )
        TypeRole.Body -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.W400,
        )
        TypeRole.BodyStrong -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.W600,
        )
        TypeRole.Label -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 0.3.sp,
        )
        TypeRole.Caption -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.W400,
        )
        TypeRole.Mono -> TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.W400,
        )
    }

    /**
     * The roundest of the three. Deepin's windows are rounded far past anything GTK or Qt
     * does, and its controls follow at a smaller radius, so even a button reads as a
     * lozenge next to a Breeze rectangle. The keys in Deepin's own calculator are cut at
     * about a sixth of their height, which is where the middle rung sits.
     */
    override fun shape(role: ShapeRole): Shape = when (role) {
        ShapeRole.None -> RoundedCornerShape(0.dp)
        ShapeRole.ExtraSmall -> RoundedCornerShape(6.dp)
        ShapeRole.Small -> RoundedCornerShape(8.dp)
        ShapeRole.Medium -> RoundedCornerShape(10.dp)
        ShapeRole.Large -> RoundedCornerShape(18.dp)
        ShapeRole.Full -> RoundedCornerShape(percent = 50)
    }

    /** Roomy, and on a ten pixel rhythm rather than GNOME's six or Kirigami's four. */
    override fun space(role: SpaceRole): Dp = when (role) {
        SpaceRole.None -> 0.dp
        SpaceRole.Xs -> 4.dp
        SpaceRole.Sm -> 10.dp
        SpaceRole.Md -> 16.dp
        SpaceRole.Lg -> 20.dp
        SpaceRole.Xl -> 30.dp
        SpaceRole.Xxl -> 40.dp
    }

    /**
     * Depth here is a wide, soft shadow, spread further than the dp asked for, plus
     * a small lightening of the surface itself so a raised layer separates even where a
     * shadow is hard to see. No border: a hairline would fight the large radii.
     */
    override fun elevation(elevation: Dp, base: Color): ElevationStyle = ElevationStyle(
        surface = lift(base, elevation),
        shadowElevation = elevation * 1.6f,
        shadowColor = if (isDark) Color(0x99000000) else Color(0x2E2E2E2E),
    )

    /**
     * Mixes [base] towards the scheme's lifting colour in proportion to [elevation], so a
     * card at 8dp sits visibly above one at 2dp even before its shadow is drawn. Capped
     * at 24dp, past which further raising would wash the surface out.
     */
    private fun lift(base: Color, elevation: Dp): Color {
        val amount = (elevation.value / 24f).coerceIn(0f, 1f) * 0.08f
        val towards = Color(0xFFFFFFFF)
        return Color(
            red = base.red + (towards.red - base.red) * amount,
            green = base.green + (towards.green - base.green) * amount,
            blue = base.blue + (towards.blue - base.blue) * amount,
            alpha = base.alpha,
        )
    }

    /**
     * A filled button is a rounded slab of brand blue with no border. The tonal one is a
     * plain grey fill, a step off whatever it sits on, and the outlined one keeps a soft
     * grey line.
     */
    override fun button(variant: ButtonVariant): ButtonStyle {
        val accent = color(ColorRole.Primary)
        val onAccent = color(ColorRole.OnPrimary)
        val tonal = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE6E6E6)
        val tonalPressed = if (isDark) Color(0xFF474747) else Color(0xFFD5D5D5)
        val onTonal = color(ColorRole.OnSurface)
        return when (variant) {
            ButtonVariant.Filled -> ButtonStyle(
                container = accent,
                content = onAccent,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Medium,
                pressedContainer = if (isDark) Color(0xFF1F8AE8) else Color(0xFF0068CC),
                pressedContent = onAccent,
                pressedBorder = null,
                ripple = false,
            )
            ButtonVariant.Tonal -> ButtonStyle(
                container = tonal,
                content = onTonal,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Medium,
                pressedContainer = tonalPressed,
                pressedContent = onTonal,
                pressedBorder = null,
                ripple = false,
            )
            ButtonVariant.Operator -> ButtonStyle(
                container = tonal,
                content = accent,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Medium,
                pressedContainer = tonalPressed,
                pressedContent = accent,
                pressedBorder = null,
                ripple = false,
            )
            ButtonVariant.Outlined -> ButtonStyle(
                container = Color.Transparent,
                content = onTonal,
                border = color(ColorRole.Outline),
                borderWidth = 1.dp,
                shape = ShapeRole.Medium,
                pressedContainer = tonal,
                pressedContent = onTonal,
                pressedBorder = accent,
                ripple = false,
            )
            ButtonVariant.Text -> ButtonStyle(
                container = Color.Transparent,
                content = accent,
                border = null,
                borderWidth = 0.dp,
                shape = ShapeRole.Medium,
                pressedContainer = tonal,
                pressedContent = accent,
                pressedBorder = null,
                ripple = false,
            )
        }
    }

    /**
     * The slowest and softest of the three, to match the rounded shapes: a long ease out
     * on press, a slightly shorter one on release.
     */
    override val motion: Motion = Motion(
        pressMillis = 250,
        releaseMillis = 200,
        easing = CubicBezierEasing(0.2f, 0.0f, 0.2f, 1.0f),
    )

    companion object {
        val Light: DeepinDesignSystem = DeepinDesignSystem(isDark = false)
        val Dark: DeepinDesignSystem = DeepinDesignSystem(isDark = true)

        /** The fallback system for the scheme in effect. */
        fun of(isDark: Boolean): DeepinDesignSystem = if (isDark) Dark else Light
    }
}
