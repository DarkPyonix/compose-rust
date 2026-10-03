package dev.darkpyonix.composerust.design

// A copy of Glass.kt from the liquid-glass module of the design-systems
// project, with the package changed and SurfaceMaterial taken from this module rather
// than from that project's core. That project is published on its own and must never
// depend on the renderer, so the material exists twice deliberately. Copies run in one
// direction: change the design systems file first, then bring the change here.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
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
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalWindowInfo

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
    drawsAsGlass(
        LocalReduceTransparency.current,
        LocalBlurAvailable.current,
        LocalWindowInfo.current.isWindowFocused,
    )

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
        val reduce = LocalReduceTransparency.current
        val available = LocalBlurAvailable.current
        // A window that is not the one being used flattens, the way every material the
        // system draws does. On a screen with several open, which one shows through is
        // what says which one is yours.
        val active = LocalWindowInfo.current.isWindowFocused
        val fill = glassFill(
            material = material,
            reduceTransparency = reduce,
            blurAvailable = available,
            depth = depth,
            windowActive = active,
        )
        // The page this surface is a lens over, and where this surface is on it. Without a
        // recording there is nothing to blur and the surface is its tint and its rim, which
        // is what every glass surface here was before the recording existed.
        val backdrop = LocalGlassBackdrop.current
        val radius = glassBlurRadius(material, reduce, available, active)
        var here by remember { mutableStateOf(Offset.Zero) }
        this
            .onGloballyPositioned { here = it.positionInWindow() }
            .drawBehind { drawGlassBackdrop(backdrop, here, shape, radius) }
            .background(fill, shape)
            .border(borderWidth, litEdge(resolved.highlight, resolved.shade), shape)
    }
}

/**
 * The page a glass surface is a lens over, recorded so the surface can blur it.
 *
 * Compose has no API that samples what is already on the screen behind an element, so a
 * surface cannot reach backwards. What it can do is be handed the drawing: the content that
 * is meant to show through records itself into a layer, and every glass surface over it
 * draws that layer again, blurred, clipped to its own outline.
 *
 * Held in an object rather than passed down, because the recorder and the readers sit in
 * different parts of the tree and neither composes the other.
 */
@Stable
class GlassBackdropState {
    /** The recorded page. Null until a frame has recorded one. */
    internal var layer: GraphicsLayer? = null

    /** Where the recording starts, in the window's coordinates. */
    internal var origin: Offset = Offset.Zero

    /**
     * True while the page is drawing itself into the layer.
     *
     * A glass surface inside the page would otherwise draw the layer it is at that moment
     * being recorded into, which is a call to draw the thing currently being drawn. It
     * does not smear, it overflows the stack, and it takes the window with it.
     *
     * Anything inside the recording is the backdrop rather than something over it, so the
     * right answer for all of it is no backdrop at all.
     */
    internal var recording: Boolean = false
}

/** The page under the glass, or null where nothing has offered one. */
val LocalGlassBackdrop: ProvidableCompositionLocal<GlassBackdropState?> =
    compositionLocalOf { null }

/**
 * The page under the glass and the chrome over it, with the page recorded so the chrome
 * can blur it.
 *
 * Two slots rather than one, and that is the whole point of the shape. [page] is recorded
 * into a layer and drawn exactly where it would have been, so wrapping it changes nothing
 * about how it looks. [over] is not recorded, and it is where the bars and the panels go.
 *
 * A surface blurring a recording of itself feeds its own output back in, and after a few
 * frames that is a smear, so the chrome has to be outside the recording. It also has to be
 * able to read it, which is why both slots are inside the provider and only one of them is
 * inside the layer.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    state: GlassBackdropState = rememberGlassBackdropState(),
    over: @Composable BoxScope.() -> Unit = {},
    page: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalGlassBackdrop provides state) {
        Box(modifier) {
            Box(Modifier.recordsGlassBackdrop(state), content = page)
            over()
        }
    }
}

/**
 * A backdrop that lasts as long as the composition it is made in, with its layer.
 *
 * The layer is remembered here rather than inside the recording modifier so that the
 * recorder and the surfaces reading it agree about which layer they mean even while the
 * page is being rebuilt underneath them.
 */
@Composable
fun rememberGlassBackdropState(): GlassBackdropState {
    val state = remember { GlassBackdropState() }
    val layer = rememberGraphicsLayer()
    DisposableEffect(state, layer) {
        state.layer = layer
        onDispose { if (state.layer === layer) state.layer = null }
    }
    return state
}

/**
 * Records what this element draws as [state]'s page, and draws it where it already was.
 *
 * A layer recorded and drawn in the same place is invisible to everything except the
 * surfaces that read it back, so putting this on the page changes nothing about how the
 * page looks.
 *
 * Only the page. Chrome inside the recording would be blurring its own output, which after
 * a few frames is a smear.
 */
fun Modifier.recordsGlassBackdrop(state: GlassBackdropState): Modifier = this
    .onGloballyPositioned { state.origin = it.positionInWindow() }
    .drawWithContent {
        val layer = state.layer
        if (layer == null) {
            drawContent()
        } else {
            // Cleared before recording: the effect a surface set to blur its own copy
            // belongs to that draw and not to the page's own.
            layer.renderEffect = null
            state.recording = true
            try {
                layer.record { this@drawWithContent.drawContent() }
            } finally {
                state.recording = false
            }
            drawLayer(layer)
        }
    }

/**
 * Draws the recorded page under a surface, blurred and clipped to [shape].
 *
 * Moved back by the distance between where the recording started and where the surface
 * sits, so the pixels that land inside the outline are the ones that were behind it.
 *
 * The effect is set before each draw rather than once, because two surfaces over the same
 * page can ask for different thicknesses and a layer holds one effect at a time.
 */
private fun DrawScope.drawGlassBackdrop(
    state: GlassBackdropState?,
    here: Offset,
    shape: Shape,
    radius: Dp,
) {
    if (state == null || state.recording) return
    val layer = state.layer ?: return
    if (radius <= 0.dp) return
    val blur = radius.toPx()
    layer.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawGlassBackdrop)) }
    clipPath(path) {
        translate(state.origin.x - here.x, state.origin.y - here.y) {
            drawLayer(layer)
        }
    }
}

/**
 * Whether a surface made of [material] is a sheet over the page rather than part of it.
 *
 * Only an overlay lifts. A menu, a dialog and a tooltip are sheets held clear of what they
 * cover, and the shadow is what says so; a card in the middle of a document is not floating
 * over it, and a shadow under every glass surface in the tree turns a page into a pile of
 * cards.
 *
 * An opaque system lifts nothing either way. A design system that draws flat fills has no
 * sheets to float, and a shadow under one of its panels would be this language leaking into
 * a language that refused it.
 */
fun liftsOffThePage(material: SurfaceMaterial, overlay: Boolean): Boolean =
    overlay && material is SurfaceMaterial.Glass

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
        val layer = rememberGraphicsLayer()
        this.drawBehind { drawLift(layer, shape) }
    } else {
        this
    }

/**
 * Rings of the outline, each a little larger and fainter in sum than the last, clipped to
 * what lies outside the surface. They overlap most at the edge, so the shadow is darkest
 * there and fades to nothing [LiquidGlass.LIFT] out, and they sit a little lower than they
 * are wide because the light comes from above.
 */
private fun DrawScope.drawLift(layer: GraphicsLayer, shape: Shape) {
    val blur = LiquidGlass.LIFT.toPx()
    if (blur <= 0f || size.minDimension <= 0f) return
    val surfaceSize = size
    val outline = shape.createOutline(surfaceSize, layoutDirection, this)
    // Room round the shape for the blur to fall away in. A gaussian is not finished at its
    // radius, and a layer that ended there would cut the shadow off with a straight line.
    val pad = blur * PAD_IN_RADII
    layer.renderEffect = BlurEffect(blur, blur, TileMode.Decal)
    layer.record(
        size = IntSize(
            (surfaceSize.width + pad * 2f).roundToInt(),
            (surfaceSize.height + pad * 2f).roundToInt(),
        ),
    ) {
        translate(pad, pad) {
            drawOutline(outline, Color.Black.copy(alpha = LiquidGlass.LIFT_ALPHA))
        }
    }
    val surface = Path()
    surface.addOutline(outline)
    // Outside only. A shadow laid under a surface you can see through shows through it and
    // greys the glass from the inside, which is what an elevation shadow does on the
    // platforms that draw one under the whole outline.
    clipPath(surface, ClipOp.Difference) {
        translate(left = -pad, top = -pad + blur * LIFT_DROP) {
            drawLayer(layer)
        }
    }
}

/**
 * How far past the blur's radius the layer reaches, in radii.
 *
 * A gaussian is not finished at one radius. Two is where what is left is under a tenth of
 * a percent, and stopping short of that ends the shadow on a straight edge.
 */
private const val PAD_IN_RADII = 2f

/** How far the shadow is dropped below the surface, as a fraction of the blur. */
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
        LocalWindowInfo.current.isWindowFocused,
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
