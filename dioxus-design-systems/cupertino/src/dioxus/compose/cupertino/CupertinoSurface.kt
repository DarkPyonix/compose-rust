package dioxus.compose.cupertino

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.ColorRole
import dioxus.compose.ElevationStyle
import dioxus.compose.SurfaceMaterial
import dioxus.compose.liquidglass.ContinuousCornerShape
import dioxus.compose.liquidglass.GlassLayer
import dioxus.compose.liquidglass.glassSurface
import dioxus.compose.liquidglass.inset

/**
 * A Cupertino card: glass over the content behind it, on a continuous corner, with the
 * wide soft shadow this system raises things with.
 *
 * [backdrop] is what shows through. Passing nothing is not a mistake, it is the common
 * case of a surface on a plain canvas, and the surface is still translucent and still
 * edge lit; it simply has a flat colour behind it rather than content.
 *
 * Nesting one of these inside another is how depth is made here. The inner one sees the
 * outer one's tint already applied, so it reads as a layer over a layer without either of
 * them drawing a heavier shadow, and [inner] cuts its corner to stay concentric.
 */
@Composable
fun CupertinoSurface(
    system: CupertinoDesignSystem,
    shape: ContinuousCornerShape,
    modifier: Modifier = Modifier,
    role: ColorRole = ColorRole.Surface,
    elevation: Dp = 0.dp,
    backdrop: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val material = system.material(role)
    val raised = system.elevation(elevation, system.color(role))
    GlassLayer(
        material = material,
        shape = shape,
        modifier = modifier.cupertinoShadow(raised, shape),
        backdrop = backdrop,
        content = content,
    )
}

/**
 * The shadow alone, for a caller that draws its own fill.
 *
 * Nothing is drawn at zero elevation. An Apple surface at rest sits on the page rather
 * than hovering a millimetre above it, and a shadow at every level is how a screen ends
 * up looking grey.
 */
fun Modifier.cupertinoShadow(style: ElevationStyle, shape: ContinuousCornerShape): Modifier =
    if (style.shadowElevation <= 0.dp) {
        this
    } else {
        this.shadow(
            elevation = style.shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = style.shadowColor,
            spotColor = style.shadowColor,
        )
    }

/**
 * An element inset by [inset] inside a surface of [shape], drawn with the same material
 * and a corner that stays concentric with its container.
 *
 * The concentric part is the reason this exists rather than being left to the caller.
 * Reusing the container's radius pinches the gap at the corners, and picking a smaller
 * step off the shape ladder happens to look right at one inset and wrong at every other.
 */
@Composable
fun Modifier.cupertinoInsetSurface(
    material: SurfaceMaterial,
    shape: ContinuousCornerShape,
    inset: Dp,
): Modifier = this.glassSurface(material, shape.inset(inset))
