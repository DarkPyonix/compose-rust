package dioxus.compose.fluent

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Fluent 2 colour ramps this design system resolves roles from.
 *
 * Microsoft publishes Fluent 2 as a specification and a set of design tokens, not as a
 * Compose library, so these values are transcribed from that token set rather than read
 * out of a dependency. Each field says which token it is, so a reader can check one
 * against the published ramp without reading the role mapping first.
 *
 * Two instances, [Light] and [Dark], because Fluent does not merely invert: the accent
 * moves several steps up its brand ramp for dark, and the foreground that sits on the
 * accent flips from white to near black.
 */
@Immutable
internal data class FluentPalette(
    /** Brand primary. The accent a default button and a hyperlink are painted with. */
    val accent: Color,
    /** Brand hover, one step along the ramp from [accent]. */
    val accentHover: Color,
    /** Brand pressed, two steps along. */
    val accentPressed: Color,
    /** What reads on top of [accent]. */
    val onAccent: Color,
    /** Brand secondary, used where a second accent weight is wanted. */
    val accentSecondary: Color,
    val onAccentSecondary: Color,
    /** Neutral Background 1. The colour a card or a standard control is filled with. */
    val background1: Color,
    /** Neutral Background 2. The window canvas behind those cards. */
    val canvas: Color,
    /** Neutral Background 3. A recessed or grouped region inside a card. */
    val background3: Color,
    /** Neutral Background pressed, the fill a control takes while held. */
    val backgroundPressed: Color,
    /** Neutral Foreground 1. Body text at full emphasis. */
    val foreground1: Color,
    /** Neutral Foreground 2. Secondary text and glyphs. */
    val foreground2: Color,
    /** Neutral Stroke 1. The hairline around a control. */
    val stroke1: Color,
    /** Neutral Stroke 2. The quieter divider between regions. */
    val stroke2: Color,
    /** Status Danger primary, and what reads on it. */
    val danger: Color,
    val onDanger: Color,
    /** The shared purple ramp, Fluent's accent beside the brand blue. */
    val accentTertiary: Color,
    val onAccentTertiary: Color,
    /**
     * Fluent's brand tints, which are how it fills a selected or highlighted region.
     *
     * Two steps of the brand ramp in light and its dark shades in dark. The secondary
     * tint is a step deeper than the primary one rather than a different hue, because
     * Fluent's second accent is the same blue used more strongly.
     */
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentSecondaryContainer: Color,
    val onAccentSecondaryContainer: Color,
    val accentTertiaryContainer: Color,
    val onAccentTertiaryContainer: Color,
    /**
     * The lit edge Fluent draws along the top of a raised surface.
     *
     * This is the mark that makes a Fluent card look like a Fluent card: a control is
     * treated as a physical slab catching light from above, so its top border is lighter
     * than its sides. It is far stronger in dark, where the surface underneath is dark
     * enough for a 9% white line to read, than in light.
     */
    val strokeTop: Color,
    /** How dark, and how opaque, Fluent's layered shadows are against this background. */
    val shadowColor: Color,
) {
    companion object {

        val Light: FluentPalette = FluentPalette(
            accent = Color(0xFF0F6CBD),
            accentHover = Color(0xFF115EA3),
            accentPressed = Color(0xFF0C3B5E),
            onAccent = Color(0xFFFFFFFF),
            accentSecondary = Color(0xFF2886DE),
            onAccentSecondary = Color(0xFFFFFFFF),
            background1 = Color(0xFFFFFFFF),
            canvas = Color(0xFFF5F5F5),
            background3 = Color(0xFFEBEBEB),
            backgroundPressed = Color(0xFFF0F0F0),
            foreground1 = Color(0xFF242424),
            foreground2 = Color(0xFF616161),
            stroke1 = Color(0xFFD1D1D1),
            stroke2 = Color(0xFFE0E0E0),
            danger = Color(0xFFC50F1F),
            onDanger = Color(0xFFFFFFFF),
            accentTertiary = Color(0xFF8764B8),
            onAccentTertiary = Color(0xFFFFFFFF),
            accentContainer = Color(0xFFCFE4FA),
            onAccentContainer = Color(0xFF0C3B5E),
            accentSecondaryContainer = Color(0xFFB4D6FA),
            onAccentSecondaryContainer = Color(0xFF0B3350),
            accentTertiaryContainer = Color(0xFFE8DCF7),
            onAccentTertiaryContainer = Color(0xFF341A5E),
            strokeTop = Color(0xFFFFFFFF).copy(alpha = 0.70f),
            shadowColor = Color(0xFF000000).copy(alpha = 0.14f),
        )

        val Dark: FluentPalette = FluentPalette(
            accent = Color(0xFF479EF5),
            accentHover = Color(0xFF62ABF5),
            accentPressed = Color(0xFF2886DE),
            // Fluent's dark accent is light enough that white text on it fails contrast,
            // so the foreground flips rather than staying white.
            onAccent = Color(0xFF000000),
            // Brand 120. The dark scheme climbs the brand ramp the way every other role
            // here does, because the light scheme's brand 90 goes muddy against a dark
            // canvas. It cannot be brand 110 or brand 90: this scheme already spends
            // those on the accent's hover and pressed states, and a second accent that
            // matches a state of the first one is not a second accent.
            accentSecondary = Color(0xFF77B7F7),
            onAccentSecondary = Color(0xFF000000),
            background1 = Color(0xFF292929),
            canvas = Color(0xFF1F1F1F),
            background3 = Color(0xFF333333),
            backgroundPressed = Color(0xFF1F1F1F),
            foreground1 = Color(0xFFFFFFFF),
            foreground2 = Color(0xFFD6D6D6),
            stroke1 = Color(0xFF414141),
            stroke2 = Color(0xFF383838),
            danger = Color(0xFFFF99A4),
            onDanger = Color(0xFF000000),
            accentTertiary = Color(0xFFB18CD9),
            onAccentTertiary = Color(0xFF22103A),
            accentContainer = Color(0xFF0C3B5E),
            onAccentContainer = Color(0xFFCFE4FA),
            accentSecondaryContainer = Color(0xFF123D61),
            onAccentSecondaryContainer = Color(0xFFB4D6FA),
            accentTertiaryContainer = Color(0xFF3B2159),
            onAccentTertiaryContainer = Color(0xFFE8DCF7),
            strokeTop = Color(0xFFFFFFFF).copy(alpha = 0.09f),
            // A shadow has to work harder to be seen against a dark canvas.
            shadowColor = Color(0xFF000000).copy(alpha = 0.40f),
        )
    }
}
