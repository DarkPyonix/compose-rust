package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.GlassBackdropState
import dev.darkpyonix.composerust.design.LocalBlurAvailable
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import dev.darkpyonix.composerust.design.LocalReduceTransparency
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What the application drew behind a node that asked for a `Backdrop`, kept so the node
 * can draw it again, blurred.
 *
 * Compose has no way to read what is already on the screen behind an element, so the
 * content that shows through records itself into layers and the node over it draws those
 * layers again. Only content that is drawn apart from the node can be recorded for it:
 * a node inside its own recording would be drawing the picture it is being drawn into.
 * So a source exists in three places, each one a place where what is behind is a
 * different part of the tree from what is in front:
 *
 * - the earlier children of a `Box`, for a later child of the same box;
 * - a `Scaffold`'s page, for its bars, which is the recording the glass design systems
 *   already keep ([LocalGlassBackdrop]);
 * - the window's page, for a sheet, a dialog and a popover over it.
 *
 * A node with a `Backdrop` anywhere else has nothing recorded behind it and is painted
 * with its tint, opaque.
 */
@Stable
class BackdropSource internal constructor() {
    /** One recorded part of what is behind, and where it starts on the screen. */
    internal class Piece(val order: Int, val layer: GraphicsLayer) {
        var origin by mutableStateOf(Offset.Zero)
    }

    internal val pieces = mutableStateListOf<Piece>()

    /** How many nodes are drawing this source. Nothing is recorded while it is zero. */
    internal var readers by mutableIntStateOf(0)

    /** True while a piece is being recorded, when drawing the source would draw into itself. */
    internal var recording = false
}

/** What is behind a `Backdrop` node here, or null where nothing was recorded for it. */
val LocalAppBackdrop: ProvidableCompositionLocal<BackdropSource?> = compositionLocalOf { null }

/** The window's page, recorded for the overlays that open over it. */
val LocalWindowBackdrop: ProvidableCompositionLocal<BackdropSource?> = compositionLocalOf { null }

@Composable
internal fun rememberBackdropSource(): BackdropSource = remember { BackdropSource() }

/**
 * Records what this element draws as part of [source], and draws it where it already was.
 * [order] is its place among the parts, back to front.
 *
 * While nothing reads the source the element draws as it always did, with no layer in
 * between, so a screen without a `Backdrop` pays nothing for this.
 */
@Composable
internal fun Modifier.recordsBackdrop(source: BackdropSource, order: Int): Modifier {
    val layer = rememberGraphicsLayer()
    val piece = remember(source, layer, order) { BackdropSource.Piece(order, layer) }
    DisposableEffect(source, piece) {
        source.pieces.add(piece)
        onDispose { source.pieces.remove(piece) }
    }
    return this
        .onGloballyPositioned { piece.origin = it.positionOnScreen() }
        .drawWithContent {
            if (source.readers <= 0) {
                drawContent()
            } else {
                source.recording = true
                try {
                    layer.record { this@drawWithContent.drawContent() }
                } finally {
                    source.recording = false
                }
                drawLayer(layer)
            }
        }
}

/** Whether [blurDp] and [saturation] are values a backdrop can be drawn with. */
internal fun backdropIsDrawable(blurDp: Float, saturation: Float): Boolean =
    blurDp.isFinite() && blurDp >= 0f && saturation.isFinite() && saturation >= 0f

/**
 * Draws a `Backdrop` as this node's surface: what the application drew behind it, blurred
 * by [blurDp] and with its saturation multiplied by [saturation], inside [shape], and
 * [tint] over that.
 *
 * Painted with [tint] at full opacity and nothing else where it cannot be drawn as asked:
 * the reader asked for reduced transparency, this build cannot blur, the values are not
 * drawable, or nothing was recorded behind the node. The contrast of what sits on it is
 * then the tint's alone, which is why it is made opaque rather than left see-through over
 * a page that was not blurred.
 */
internal fun Modifier.hostBackdrop(
    blurDp: Float,
    saturation: Float,
    tint: Color,
    shape: Shape,
): Modifier = composed {
    val own = LocalAppBackdrop.current
    val glass = if (own == null) LocalGlassBackdrop.current else null
    val drawable = backdropIsDrawable(blurDp, saturation) &&
        !LocalReduceTransparency.current &&
        LocalBlurAvailable.current &&
        (own != null || glass != null)
    if (!drawable) return@composed background(tint.copy(alpha = 1f), shape)
    // Nothing to blur and nothing to recolour: what is behind shows through the tint as
    // it is, and no layer is needed for that.
    if (blurDp == 0f && saturation == 1f) return@composed background(tint, shape)

    DisposableEffect(own, glass) {
        own?.let { it.readers++ }
        glass?.let { it.readers++ }
        onDispose {
            own?.let { it.readers-- }
            glass?.let { it.readers-- }
        }
    }
    val composite = rememberGraphicsLayer()
    var onScreen by remember { mutableStateOf(Offset.Zero) }
    var inWindow by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned {
            onScreen = it.positionOnScreen()
            inWindow = it.positionInWindow()
        }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val recording = own?.recording == true || glass?.recording == true
            if (recording) {
                // This node is inside what it would blur. Drawn as where nothing can be
                // blurred, never as a picture of itself.
                clipPath(path) { drawRect(tint.copy(alpha = 1f)) }
                return@drawBehind
            }
            drawBlurred(composite, own, glass, onScreen, inWindow, blurDp.dp.toPx(), saturation, path)
            clipPath(path) { drawRect(tint) }
        }
}

/**
 * Gathers what is behind into [composite], with room round the node for the blur to take
 * its colour from, and draws it blurred and recoloured inside [outline].
 */
private fun DrawScope.drawBlurred(
    composite: GraphicsLayer,
    own: BackdropSource?,
    glass: GlassBackdropState?,
    onScreen: Offset,
    inWindow: Offset,
    blur: Float,
    saturation: Float,
    outline: Path,
) {
    val pad = ceil(blur * PAD_IN_RADII)
    val width = (size.width + pad * 2f).roundToInt()
    val height = (size.height + pad * 2f).roundToInt()
    if (width <= 0 || height <= 0) return
    composite.record(size = IntSize(width, height)) {
        translate(pad, pad) {
            if (own != null) {
                for (piece in own.pieces.sortedBy { it.order }) {
                    translate(piece.origin.x - onScreen.x, piece.origin.y - onScreen.y) { drawLayer(piece.layer) }
                }
            }
            val page = glass?.layer
            if (glass != null && page != null) {
                // A glass surface over the same page leaves its own blur on the layer.
                page.renderEffect = null
                translate(glass.origin.x - inWindow.x, glass.origin.y - inWindow.y) { drawLayer(page) }
            }
        }
    }
    composite.renderEffect = if (blur > 0f) BlurEffect(blur, blur, TileMode.Clamp) else null
    composite.colorFilter = if (saturation == 1f) {
        null
    } else {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }
    clipPath(outline) {
        translate(-pad, -pad) { drawLayer(composite) }
    }
}

/**
 * How far past the blur's radius the gathered picture reaches, in radii. A gaussian is not
 * finished at one radius, and a picture cut off at the node's edge would blur the edge
 * towards whatever the clamp repeats there.
 */
private const val PAD_IN_RADII = 2f
