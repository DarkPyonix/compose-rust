package org.thisisthepy.compose.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.designsystem.SurfaceMaterial

/**
 * Whether the reader has asked for reduced transparency.
 *
 * Both Apple platforms expose this as an accessibility setting, and honouring it is not
 * optional: for a reader with low vision, text over a moving translucent backdrop is the
 * difference between a usable screen and an unusable one. When this is true every glass
 * surface draws its opaque fallback instead, and the fallback is the colour whose
 * contrast was guaranteed when the material was built.
 *
 * The renderer that hosts this library is responsible for reading the platform setting
 * and providing it. It defaults to false rather than throwing, because a design system
 * that refuses to draw when nobody wired up an accessibility query is worse than one that
 * draws the unreduced form.
 */
val LocalReduceTransparency: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * Whether this build can blur at all.
 *
 * Compose's blur is a no-op rather than an error on Android below API 31, and a caller
 * may also want to turn it off to save a render pass on a slow device. A surface that
 * would blur but cannot has no business staying translucent, because translucency without
 * blur is just reduced contrast, so this drives the same fallback as reduced
 * transparency.
 */
val LocalBlurAvailable: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { true }

/**
 * How many glass layers are already stacked under this point in the tree.
 *
 * Not static: nesting a glass surface changes it, and every surface underneath needs to
 * see the change.
 */
val LocalGlassDepth: ProvidableCompositionLocal<Int> = compositionLocalOf { 0 }

/**
 * Whether a glass material will actually be drawn as glass here.
 *
 * Useful to a caller that has to make a matching decision, such as choosing a content
 * colour or deciding whether a divider is needed.
 */
@Composable
fun isGlassDrawn(): Boolean =
    drawsAsGlass(LocalReduceTransparency.current, LocalBlurAvailable.current)

/**
 * Paints [material] into the background of this element, clipped to [shape].
 *
 * Three cases, and only the first is glass:
 *
 * - Glass, drawn as glass: a translucent tint over whatever is behind, with a gradient
 *   edge that is bright along the top and dark along the bottom. Anything drawn earlier
 *   in the same stacking context shows through, which is what makes the surface respond
 *   to its background instead of being a fixed colour.
 * - Glass, reduced: the stored opaque fallback, which was built to meet contrast against
 *   the content colour. The lit edge stays, because it is geometry rather than
 *   transparency and removing it would flatten the surface for no accessibility gain.
 * - Opaque: a flat fill, which is not a degraded anything. It is what Material 3, Fluent
 *   and the Linux systems actually look like.
 *
 * The blur that belongs to this material is not applied here. Compose blurs an element's
 * own content, never what is behind it, so a surface cannot blur its own backdrop; the
 * backdrop layer has to do it. See [glassBackdrop].
 */
@Composable
fun Modifier.glassSurface(
    material: SurfaceMaterial,
    shape: Shape,
    borderWidth: Dp = GLASS_EDGE_WIDTH,
): Modifier = when (material) {
    is SurfaceMaterial.Opaque -> this.background(material.color, shape)

    is SurfaceMaterial.Glass -> {
        val depth = LocalGlassDepth.current
        val resolved = material.atDepth(depth)
        val fill = glassFill(
            material = material,
            reduceTransparency = LocalReduceTransparency.current,
            blurAvailable = LocalBlurAvailable.current,
            depth = depth,
        )
        this
            .background(fill, shape)
            .border(borderWidth, litEdge(resolved.highlight, resolved.shade), shape)
    }
}

/**
 * The soft shadow a floating glass surface casts, drawn around it.
 *
 * Separate from [glassSurface] because of where it has to go in a chain. It is drawn
 * outside the surface's bounds, and a clip earlier in the chain cuts it off, so a caller
 * puts this before the clip and [glassSurface] after it.
 *
 * Only outside. A shadow laid under a see-through surface shows through it and greys the
 * glass from the inside, which is what an elevation shadow does on the platforms that
 * draw one under the whole outline; this one is clipped to what lies beyond the edge, so
 * the inside of the glass is only its tint and what is behind it.
 *
 * Only glass that is drawn as glass lifts. The opaque fallback sits on the page as a
 * panel, and an opaque material is a flat fill in a system that does not float things.
 */
@Composable
fun Modifier.glassLift(material: SurfaceMaterial, shape: Shape): Modifier =
    if (material is SurfaceMaterial.Glass && isGlassDrawn()) {
        this.drawBehind { drawLift(shape) }
    } else {
        this
    }

/**
 * Rings of the outline, each a little larger and fainter in sum than the last, clipped to
 * what lies outside the surface. They overlap most at the edge, so the shadow is darkest
 * there and fades to nothing [LiquidGlass.LIFT] out, and they sit a little lower than they
 * are wide because the light comes from above.
 */
private fun DrawScope.drawLift(shape: Shape) {
    val spread = LiquidGlass.LIFT.toPx()
    if (spread <= 0f || size.minDimension <= 0f) return
    val surface = Path()
    surface.addOutline(shape.createOutline(size, layoutDirection, this))
    val ring = Color.Black.copy(alpha = LiquidGlass.LIFT_ALPHA / LIFT_RINGS)
    clipPath(surface, ClipOp.Difference) {
        for (step in LIFT_RINGS downTo 1) {
            val grow = spread * step / LIFT_RINGS
            val grown = shape.createOutline(
                Size(size.width + grow * 2f, size.height + grow * 2f),
                layoutDirection,
                this,
            )
            translate(left = -grow, top = -grow * (1f - LIFT_DROP)) {
                drawOutline(grown, ring)
            }
        }
    }
}

/** How many rings the lift is drawn in. Enough that no step between them shows. */
private const val LIFT_RINGS = 6

/** How much further below the surface its shadow reaches than above it, as a fraction. */
private const val LIFT_DROP = 0.35f

/**
 * The gradient that makes a glass edge read as a lit rim rather than a drawn outline.
 *
 * It runs [highlight] at the top through nearly nothing in the middle to [shade] at the
 * bottom. The near-transparent midpoint matters: a straight two-stop gradient keeps the
 * sides tinted, and the sides of a lit surface are where light grazes rather than lands.
 */
fun litEdge(highlight: Color, shade: Color): Brush = Brush.verticalGradient(
    0.0f to highlight,
    0.35f to highlight.copy(alpha = highlight.alpha * 0.25f),
    0.65f to shade.copy(alpha = shade.alpha * 0.25f),
    1.0f to shade,
)

/**
 * Blurs this element's content so that a glass surface drawn over it has something to
 * blur.
 *
 * This is the honest shape of the effect in Compose. There is no API that samples what is
 * behind an element, so the blur has to be applied by the content that is behind, and
 * that content has to be ours. A caller puts this on the layer underneath and
 * [glassSurface] on the layer above.
 *
 * It does nothing when transparency is reduced or blur is unavailable, so the two
 * fallback paths turn off the cost as well as the appearance.
 */
@Composable
fun Modifier.glassBackdrop(radius: Dp): Modifier =
    if (isGlassDrawn() && radius > 0.dp) this.blur(radius) else this

/**
 * A glass layer: backdrop blur underneath, glass surface over it, and the depth counter
 * raised for anything nested inside.
 *
 * [backdrop] draws what shows through. It is blurred; [content] is not. Stacking these
 * produces the depth the design language asks for, because each layer tints what is
 * already there rather than casting a separate shadow.
 */
@Composable
fun GlassLayer(
    material: SurfaceMaterial,
    shape: Shape,
    modifier: Modifier = Modifier,
    backdrop: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val depth = LocalGlassDepth.current
    // Not material.blurRadius: a surface that is not drawing as glass must not pay for a
    // blur pass either. Reading the radius straight off the material blurred a backdrop
    // nobody could see through, every frame, for a reader who had asked for less.
    val blurRadius = glassBlurRadius(
        material,
        LocalReduceTransparency.current,
        LocalBlurAvailable.current,
    )

    Box(modifier) {
        Box(Modifier.glassBackdrop(blurRadius), content = backdrop)
        Box(Modifier.glassSurface(material, shape)) {
            CompositionLocalProvider(LocalGlassDepth provides depth + 1) {
                Box(content = content)
            }
        }
    }
}

/** The thickness of the lit edge. Thin enough to read as a rim, not as a border. */
val GLASS_EDGE_WIDTH: Dp = 1.dp
