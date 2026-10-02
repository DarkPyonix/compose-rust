package dioxus.compose.design

// A copy of Contrast.kt from the liquid-glass module of the dioxus-design-systems
// project, with the package changed and SurfaceMaterial taken from this module rather
// than from that project's core. That project is published on its own and must never
// depend on the renderer, so the material exists twice deliberately. Copies run in one
// direction: change the design systems file first, then bring the change here.

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Colour arithmetic the glass material needs in order to promise that its opaque
 * fallback is readable.
 *
 * A translucent surface has no fixed contrast: it depends on whatever happens to be
 * behind it that frame. The fallback does not, so it is the one place where a contrast
 * guarantee can actually be made, and these functions are how it is made rather than
 * asserted.
 *
 * The luminance and ratio formulas are the ones in WCAG 2.2: relative luminance from
 * linearised sRGB channels, ratio as (lighter + 0.05) / (darker + 0.05).
 */

/** WCAG relative luminance, 0 for black and 1 for white. Alpha is ignored. */
fun relativeLuminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f
        else ((value + 0.055f) / 1.055f).pow(2.4f)

    return 0.2126f * channel(color.red) +
        0.7152f * channel(color.green) +
        0.0722f * channel(color.blue)
}

/**
 * The WCAG contrast ratio between two opaque colours, from 1.0 (identical) to 21.0
 * (black against white).
 *
 * Both colours must be opaque. A translucent colour has no ratio of its own, because the
 * thing it is drawn over decides the answer; composite it first with [compositeOver].
 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** [top] painted over opaque [bottom], using [top]'s alpha. The result is opaque. */
fun compositeOver(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(
        red = top.red * a + bottom.red * (1f - a),
        green = top.green * a + bottom.green * (1f - a),
        blue = top.blue * a + bottom.blue * (1f - a),
        alpha = 1f,
    )
}

/**
 * [color] moved away from [against] until they are at least [minRatio] apart, and
 * returned unchanged if they already are.
 *
 * The move is a straight walk towards black or towards white, whichever direction
 * increases the ratio, so the hue survives and only the lightness gives way. The walk is
 * bounded: if even pure black or pure white does not reach [minRatio] then the darker or
 * lighter extreme is returned, which is the best that colour can do.
 *
 * This is what lets the glass fallback carry a contrast guarantee instead of a hope. A
 * design system hands over the colour it wants and the content colour that has to be
 * legible on it, and gets back something that is.
 */
fun ensureContrast(color: Color, against: Color, minRatio: Float): Color {
    // Opaque on every path, including the one where nothing had to move. A caller stores
    // this as the fill to use when the translucent one is unavailable, and a translucent
    // fallback would let whatever is behind it decide the ratio that was just guaranteed.
    // The ratio itself is unaffected: it is computed from the colour channels alone.
    if (contrastRatio(color, against) >= minRatio) return color.copy(alpha = 1f)

    // Both directions are walked rather than one being deduced from which colour is
    // lighter. That deduction is right only while the two are clearly apart. A surface
    // the colour of its own text has no away direction yet, and the comparison picks
    // whichever way the tie happens to fall; a surface one shade lighter than very light
    // text has an away direction that runs out of room long before the ratio is met.
    // Both cases used to return a colour that did not keep the promise this function
    // exists to make, silently. Two searches instead of one is the whole cost.
    val darker = nearestMeeting(color, Color.Black, against, minRatio)
    val lighter = nearestMeeting(color, Color.White, against, minRatio)
    val meeting = listOf(darker, lighter).filter { contrastRatio(it, against) >= minRatio }
    // Of the directions that work, the one that moved the colour least. Where neither
    // works the content colour is a mid grey and nothing can reach the ratio, so the
    // caller gets the best reading there is.
    return meeting.minByOrNull { squaredDistance(color, it) }
        ?: listOf(darker, lighter).maxBy { contrastRatio(it, against) }
}

/**
 * The point closest to [from] on the way to [target] that reads at [minRatio] against
 * [against], or [target] itself when no point on that line does.
 *
 * Binary search rather than a step walk, so the result is as close to the colour that was
 * asked for as the requirement allows.
 */
private fun nearestMeeting(from: Color, target: Color, against: Color, minRatio: Float): Color {
    var low = 0f
    var high = 1f
    var best = lerpOpaque(from, target, 1f)
    repeat(24) {
        val mid = (low + high) / 2f
        val candidate = lerpOpaque(from, target, mid)
        if (contrastRatio(candidate, against) >= minRatio) {
            best = candidate
            high = mid
        } else {
            low = mid
        }
    }
    return best
}

/** How far apart two colours are, for choosing the smaller of two corrections. */
private fun squaredDistance(a: Color, b: Color): Float {
    val red = a.red - b.red
    val green = a.green - b.green
    val blue = a.blue - b.blue
    return red * red + green * green + blue * blue
}

private fun lerpOpaque(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)
