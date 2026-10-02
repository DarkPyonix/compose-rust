package dioxus.compose.cupertino

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dioxus.compose.TypeRole

/**
 * The type scale, mapped from Apple's named text styles onto the nine roles.
 *
 * Apple's scale is San Francisco at its default Dynamic Type size: Large Title 34, Title 1
 * 28, Title 2 22, Title 3 20, Body 17, Subheadline 15, Caption 12. The sizes and leadings
 * here are those, which is why they are not a geometric ladder: Apple's steps narrow
 * towards the body size because that is where reading happens.
 *
 * Two details of San Francisco are worth carrying even without the font itself. Tracking
 * is negative at body size and positive at display sizes, because SF tightens as it grows
 * and the optical sizes are what make Apple text look set rather than typed. And Body and
 * BodyStrong are the same size and leading, differing only in weight, so that emphasis
 * inside a paragraph does not reflow it.
 *
 * The font family is deliberately the platform default rather than a named one. San
 * Francisco is not licensed for redistribution, and asking for it by name gets it on
 * Apple platforms and nothing anywhere else. The default resolves to San Francisco on
 * macOS and iOS, which is where this design system is the platform look, and to the
 * host's own UI face elsewhere, which is a better outcome than a silent substitution.
 */
internal fun cupertinoType(role: TypeRole): TextStyle = when (role) {
    TypeRole.Display -> TextStyle(
        fontSize = 34.sp, fontWeight = FontWeight.W700,
        lineHeight = 41.sp, letterSpacing = 0.011.em,
    )
    TypeRole.Headline -> TextStyle(
        fontSize = 28.sp, fontWeight = FontWeight.W700,
        lineHeight = 34.sp, letterSpacing = 0.013.em,
    )
    TypeRole.Title -> TextStyle(
        fontSize = 22.sp, fontWeight = FontWeight.W700,
        lineHeight = 28.sp, letterSpacing = 0.016.em,
    )
    TypeRole.Subtitle -> TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.W600,
        lineHeight = 25.sp, letterSpacing = 0.019.em,
    )
    TypeRole.Body -> TextStyle(
        fontSize = 17.sp, fontWeight = FontWeight.W400,
        lineHeight = 22.sp, letterSpacing = (-0.024).em,
    )
    TypeRole.BodyStrong -> TextStyle(
        fontSize = 17.sp, fontWeight = FontWeight.W600,
        lineHeight = 22.sp, letterSpacing = (-0.024).em,
    )
    TypeRole.Label -> TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.W400,
        lineHeight = 20.sp, letterSpacing = (-0.016).em,
    )
    TypeRole.Caption -> TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.W400,
        lineHeight = 16.sp, letterSpacing = 0.0.em,
    )
    TypeRole.Mono -> TextStyle(
        fontSize = 17.sp, fontWeight = FontWeight.W400,
        lineHeight = 22.sp, letterSpacing = 0.0.em,
        fontFamily = FontFamily.Monospace,
    )
}
