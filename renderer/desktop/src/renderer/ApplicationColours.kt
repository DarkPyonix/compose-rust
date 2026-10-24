package dev.darkpyonix.composerust.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.Theme
import kotlin.math.pow

/**
 * Every fill whose ink this side may choose, with the contrast that pair is held to.
 *
 * The same list the Host checks a palette against, in the same order. Accents hold 3:1,
 * the bound a label on a control is held to; containers and the reading surfaces hold
 * 4.5:1, because a paragraph lands on them.
 */
internal val INK_PAIRS: List<Triple<ColorRole, ColorRole, Float>> = listOf(
    Triple(ColorRole.Primary, ColorRole.OnPrimary, 3.0f),
    Triple(ColorRole.Secondary, ColorRole.OnSecondary, 3.0f),
    Triple(ColorRole.Tertiary, ColorRole.OnTertiary, 3.0f),
    Triple(ColorRole.Error, ColorRole.OnError, 3.0f),
    Triple(ColorRole.PrimaryContainer, ColorRole.OnPrimaryContainer, 4.5f),
    Triple(ColorRole.SecondaryContainer, ColorRole.OnSecondaryContainer, 4.5f),
    Triple(ColorRole.TertiaryContainer, ColorRole.OnTertiaryContainer, 4.5f),
    Triple(ColorRole.Surface, ColorRole.OnSurface, 4.5f),
    Triple(ColorRole.SurfaceVariant, ColorRole.OnSurfaceVariant, 4.5f),
    Triple(ColorRole.Background, ColorRole.OnBackground, 4.5f),
)

private fun channel(value: Int): Double {
    val v = value / 255.0
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** Relative luminance as WCAG defines it, from the colour's eight bit channels. */
internal fun wcagLuminance(color: Color): Double {
    val argb = color.toArgb()
    return 0.2126 * channel((argb shr 16) and 0xff) +
        0.7152 * channel((argb shr 8) and 0xff) +
        0.0722 * channel(argb and 0xff)
}

/**
 * The ink for a fill the application changed and whose ink it left alone.
 *
 * The system's own ink where it still reads at the bound the pair is held to; otherwise
 * white or black, whichever reads better. A brand orange keeps the system's white where
 * white reads on it, and a yellow brand gets black.
 */
internal fun inkFor(fill: Color, systemInk: Color, required: Float): Color {
    if (contrastRatio(systemInk, fill) >= required) return systemInk
    return if (contrastRatio(Color.White, fill) >= contrastRatio(Color.Black, fill)) {
        Color.White
    } else {
        Color.Black
    }
}

/**
 * The colours the application's palette puts over a design system, for one scheme,
 * indexed by role ordinal, with null where the system's own value stands.
 *
 * Read from what the theme already carries, so a switch between light and dark is
 * answered here without a word to the Host. A fill given without its ink also gets an ink
 * here, chosen against [system]'s answer for that ink; the other direction is never
 * filled in, because an ink given on its own was meant.
 *
 * Empty while the platform is in a high contrast mode. The person using the machine chose
 * those colours to be able to see, and a brand must not paint over them.
 */
internal fun applicationColors(
    theme: Theme?,
    dark: Boolean,
    highContrast: () -> Boolean,
    system: (ColorRole) -> Color,
): List<Color?> {
    // Asked last, so that an application with no palette never makes the platform answer.
    if (theme == null || theme.palette.isEmpty() || highContrast()) return emptyList()
    val given = arrayOfNulls<Color>(ColorRole.entries.size)
    for (entry in theme.palette) {
        if (entry.dark == dark) given[entry.role.ordinal] = Color(entry.argb)
    }
    if (given.all { it == null }) return emptyList()
    for ((fill, ink, required) in INK_PAIRS) {
        val newFill = given[fill.ordinal] ?: continue
        if (given[ink.ordinal] != null) continue
        given[ink.ordinal] = inkFor(newFill, system(ink), required)
    }
    return given.toList()
}
