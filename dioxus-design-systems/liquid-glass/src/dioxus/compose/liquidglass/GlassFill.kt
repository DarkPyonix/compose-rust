package dioxus.compose.liquidglass

import androidx.compose.ui.graphics.Color
import dioxus.compose.SurfaceMaterial
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one decision a glass surface makes before it draws: translucent, or opaque.
 *
 * It lives apart from the modifier that uses it because it is where two promises are
 * kept, and a promise that can only be checked by looking at a screen is not a promise.
 * Given the same inputs this returns the same colour, so a test can state the rule
 * directly: reduced transparency takes the opaque path, a build without blur takes the
 * opaque path, and the colour it takes is the one whose contrast was guaranteed when the
 * material was built.
 *
 * Translucency without blur is the case worth naming. It is not a milder version of the
 * effect; it is the same loss of contrast with none of the separation that made the loss
 * worth it, so a surface that would blur and cannot has no reason to stay see-through.
 */
fun glassFill(
    material: SurfaceMaterial,
    reduceTransparency: Boolean,
    blurAvailable: Boolean,
    depth: Int = 0,
): Color {
    // An opaque material has one colour and neither setting changes it. Taking any
    // SurfaceMaterial rather than only the glass case means a caller styling a surface
    // asks the same question whichever system is active, instead of having to know which
    // of them uses glass before it can ask.
    if (material !is SurfaceMaterial.Glass) return (material as SurfaceMaterial.Opaque).color
    val resolved = material.atDepth(depth)
    return if (drawsAsGlass(reduceTransparency, blurAvailable)) {
        resolved.tint.copy(alpha = resolved.tintAlpha)
    } else {
        resolved.fallback
    }
}

/** Whether these conditions leave a glass material drawing as glass. */
fun drawsAsGlass(reduceTransparency: Boolean, blurAvailable: Boolean): Boolean =
    !reduceTransparency && blurAvailable

/**
 * The blur radius these conditions actually call for.
 *
 * Zero unless the surface is drawing as glass. Turning transparency off, or running where
 * blur is unavailable, has to remove the render pass and not only the look: blurring a
 * backdrop nobody can see through is pure cost, paid every frame, by the reader who asked
 * for less of it.
 */
fun glassBlurRadius(
    material: SurfaceMaterial,
    reduceTransparency: Boolean,
    blurAvailable: Boolean,
): Dp = if (material is SurfaceMaterial.Glass && drawsAsGlass(reduceTransparency, blurAvailable)) {
    material.blurRadius
} else {
    0.dp
}
