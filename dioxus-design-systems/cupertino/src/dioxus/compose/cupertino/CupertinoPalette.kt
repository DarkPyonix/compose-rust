package dioxus.compose.cupertino

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Apple's system colours, transcribed rather than depended on.
 *
 * Apple ships no Compose library, so these come from the published system colour and
 * system grey tables. alexzhirkevich/compose-cupertino is a readable cross check for the
 * same values and is deliberately not a dependency: it targets Kotlin 1.9 where this
 * project is on 2.4, and it predates the material change in macOS 26 and iOS 26, so
 * adopting it would mean pinning the toolchain back to get a palette that is out of date
 * anyway.
 *
 * Two instances, because Apple's colours are not an inversion of each other. The system
 * blue moves from 0x007AFF to a lighter 0x0A84FF so that it survives against a black
 * background, the greys renumber from the top of the ramp rather than flipping, and the
 * grouped background goes to pure black while the cards on it go lighter, which is the
 * opposite of what light mode does.
 *
 * The label colours are the opaque forms. Apple publishes secondary and tertiary labels
 * as a black or white with an alpha, and those alphas are correct over a known
 * background; a role in this contract has to answer with a colour that means something
 * on its own.
 */
@Immutable
internal data class CupertinoPalette(
    /** System blue, the tint a control takes unless the app says otherwise. */
    val accent: Color,
    /** System blue held down, one step darker in light and one lighter in dark. */
    val accentPressed: Color,
    /** What reads on top of [accent]. White in both schemes: system blue is dark enough. */
    val onAccent: Color,
    /** System indigo, the second tint. */
    val accentSecondary: Color,
    val onAccentSecondary: Color,
    /** Secondary system background: the card or sheet that sits on the grouped canvas. */
    val surface: Color,
    /** Tertiary system background: a recessed or grouped region inside a card. */
    val surfaceVariant: Color,
    /** Grouped system background, the canvas everything else sits on. */
    val canvas: Color,
    /** Label, body text at full emphasis. */
    val label: Color,
    /** Secondary label, opaque. */
    val labelSecondary: Color,
    /** Separator, opaque form. */
    val separator: Color,
    /** System grey 4, the fainter rule. */
    val separatorFaint: Color,
    /** Tertiary system fill, the grey a neutral button is filled with. */
    val fill: Color,
    /** That fill while the button is held. */
    val fillPressed: Color,
    /** System red. */
    val danger: Color,
    val onDanger: Color,
    /** System purple, the third of the platform accents after blue and indigo. */
    val accentTertiary: Color,
    val onAccentTertiary: Color,
    /**
     * The tinted fills iOS draws by hand, one per accent.
     *
     * Apple publishes no container tones, so these are what a tinted card or a selected
     * row actually looks like: a wash of the accent in light, and a deep, desaturated
     * version of it in dark. They are opaque for the same reason the labels above are:
     * a role has to answer with a colour that means something without knowing what is
     * behind it.
     */
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentSecondaryContainer: Color,
    val onAccentSecondaryContainer: Color,
    val accentTertiaryContainer: Color,
    val onAccentTertiaryContainer: Color,
    /** The shadow a raised Cupertino surface casts: wide, soft, and never a tint. */
    val shadow: Color,
) {
    companion object {
        val Light = CupertinoPalette(
            accent = Color(0xFF007AFF),
            accentPressed = Color(0xFF0062CC),
            onAccent = Color(0xFFFFFFFF),
            accentSecondary = Color(0xFF5856D6),
            onAccentSecondary = Color(0xFFFFFFFF),
            surface = Color(0xFFFFFFFF),
            // The page is the grouped background and the variant is the colour an incoming
            // message bubble has. They used to be three parts in 255 apart, close enough
            // that anything filled with one on a page of the other was invisible.
            surfaceVariant = Color(0xFFE9E9EB),
            canvas = Color(0xFFF2F2F7),
            label = Color(0xFF000000),
            labelSecondary = Color(0xFF636366),
            separator = Color(0xFFC6C6C8),
            separatorFaint = Color(0xFFD1D1D6),
            fill = Color(0xFFE5E5EA),
            fillPressed = Color(0xFFD1D1D6),
            danger = Color(0xFFFF3B30),
            onDanger = Color(0xFFFFFFFF),
            accentTertiary = Color(0xFFAF52DE),
            onAccentTertiary = Color(0xFFFFFFFF),
            accentContainer = Color(0xFFD6E4FF),
            onAccentContainer = Color(0xFF003070),
            accentSecondaryContainer = Color(0xFFE2E0FF),
            onAccentSecondaryContainer = Color(0xFF2A1B70),
            accentTertiaryContainer = Color(0xFFF3DDFB),
            onAccentTertiaryContainer = Color(0xFF3D0B52),
            shadow = Color(0x33000000),
        )

        val Dark = CupertinoPalette(
            accent = Color(0xFF0A84FF),
            accentPressed = Color(0xFF409CFF),
            onAccent = Color(0xFFFFFFFF),
            accentSecondary = Color(0xFF5E5CE6),
            onAccentSecondary = Color(0xFFFFFFFF),
            surface = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFF2C2C2E),
            canvas = Color(0xFF000000),
            label = Color(0xFFFFFFFF),
            labelSecondary = Color(0xFF98989F),
            separator = Color(0xFF38383A),
            separatorFaint = Color(0xFF48484A),
            fill = Color(0xFF3A3A3C),
            fillPressed = Color(0xFF48484A),
            danger = Color(0xFFFF453A),
            onDanger = Color(0xFFFFFFFF),
            accentTertiary = Color(0xFFBF5AF2),
            onAccentTertiary = Color(0xFFFFFFFF),
            accentContainer = Color(0xFF0A2D52),
            onAccentContainer = Color(0xFFCFE3FF),
            accentSecondaryContainer = Color(0xFF262663),
            onAccentSecondaryContainer = Color(0xFFDEDCFF),
            accentTertiaryContainer = Color(0xFF3F1A52),
            onAccentTertiaryContainer = Color(0xFFF1D9FA),
            shadow = Color(0x99000000),
        )
    }
}
