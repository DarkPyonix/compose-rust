package dev.darkpyonix.composerust.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import dev.darkpyonix.composerust.protocol.WindowHeightClass
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.windowHeightClassOf
import dev.darkpyonix.composerust.runtime.windowSizeClassOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.max
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.glassLift
import dev.darkpyonix.composerust.design.glassSurface
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.TableError

/**
 * Rebuilds a Compose `Modifier` chain from the Host's modifier value list.
 *
 * List order is chain order, so `[Padding(16), FillMaxWidth]` and the reverse differ exactly
 * as they do in hand-written Compose.
 *
 * Roles are resolved here against the active design system's token table:
 * the Host sent a role, the Renderer decides what it measures.
 */
internal fun List<ProtocolModifier>.toComposeModifier(
    nodeId: Int,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
): Modifier {
    // The last Shape or ShapeRole in the list is what clips, what the border
    // follows and what the background fills, whatever their order in the chain.
    val shape = resolvedShape(theme)
    // What this node's surface is made of is its surface, so it is drawn round the whole
    // node the way a background is: under the padding, and outside the clip so a surface
    // that floats can cast its lift beyond its own edge. Taken at its place in the list it
    // came last, after the padding and the clip, and a glass composer was drawn as a
    // capsule inside its own padding with the lift cut off at the edge.
    val material = lastOrNull { it is ProtocolModifier.Material } as ProtocolModifier.Material?
    val surface = if (material == null) {
        Modifier
    } else {
        // Resolved by the running design system, which answers with blur where it blurs
        // and with a lifted or flat fill where it does not. Glass floats, so it lifts off
        // what is behind it before it is painted: a glass capsule on a page of its own
        // colour is otherwise a rim and nothing else.
        Modifier.composed {
            val resolved = theme.rules.material(material.role, theme)
            glassLift(resolved, shape).glassSurface(resolved, shape)
        }
    }
    // The corners an HTML box asked for, which its background, border, shadow and clip all
    // follow. Null where the node named no corners of its own.
    val corners = cornerEachShape()
    return fold(surface) { chain, value ->
        when (value) {
            is ProtocolModifier.Empty -> chain
            is ProtocolModifier.Padding -> chain.padding(value.value.dp)
            is ProtocolModifier.FillMaxWidth -> chain.fillMaxWidth()
            is ProtocolModifier.FillMaxHeight -> chain.fillMaxHeight()
            is ProtocolModifier.Width -> chain.width(value.value.dp)
            is ProtocolModifier.Height -> chain.height(value.value.dp)
            is ProtocolModifier.Size -> chain.size(value.width.dp, value.height.dp)
            // A gradient where the paint named one, a flat colour otherwise. A paint
            // that names a brush nobody registered is reported and left unpainted: a
            // guess would leave a screen subtly wrong with nothing to read about why.
            is ProtocolModifier.Background -> theme.brush(value.paint)?.let { brush ->
                chain.background(brush, shape)
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }
            is ProtocolModifier.Clickable -> chain.hostClickable(nodeId, value.handlerId, dispatcher)

            is ProtocolModifier.PaddingEach ->
                chain.padding(value.start.dp, value.top.dp, value.end.dp, value.bottom.dp)
            is ProtocolModifier.PaddingRole -> chain.padding(theme.space(value.role))
            is ProtocolModifier.Shape -> chain.clip(shape)
            is ProtocolModifier.ShapeRole -> chain.clip(shape)
            is ProtocolModifier.Border -> theme.brush(value.paint)?.let { brush ->
                chain.border(value.width.dp, brush, shape)
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }
            is ProtocolModifier.Elevation ->
                theme.rules.elevation(chain, value.value.dp, shape, theme)

            // Only a node that asked is measured. Nothing is attached to a node without
            // this modifier, so a tree that observes nothing is laid out exactly as it
            // was before any of this existed.
            is ProtocolModifier.ObserveSize -> chain.reportSizeTo(nodeId, dispatcher)

            // How important this node's changes are. What that means in milliseconds and
            // along which curve is the running design system's answer, and a system the
            // user has asked to hold still answers every role with no run at all.
            is ProtocolModifier.Motion -> chain.animateContentSize(theme.motion(value.role))

            // What this node's surface is made of, drawn above at the outside of the chain.
            is ProtocolModifier.Material -> chain

            // Weight is parent data: it is applied by the Column or Row that owns this node,
            // not here. See `weightOf` and `Children` in RenderNode.kt.
            is ProtocolModifier.Weight -> chain

            // The elements an HTML and CSS screen is drawn with. The Host's layout engine has
            // already decided where every box goes and how big it is, so these place and
            // size without negotiating, and the decoration follows CSS rather than the
            // design system: a page's author chose these colours and radii.
            //
            // Absolute, not relative to the reading direction: the Host's coordinates are
            // the screen's, and a right-to-left page has already been laid out as one.
            is ProtocolModifier.Offset -> chain.absoluteOffset(value.x.dp, value.y.dp)
            is ProtocolModifier.RequiredSize -> chain.requiredSize(value.width.dp, value.height.dp)
            is ProtocolModifier.BorderEach -> {
                val paints = listOf(value.topPaint, value.rightPaint, value.bottomPaint, value.leftPaint)
                val brushes = paints.map { theme.brush(it) }
                val missing = paints.indices.firstOrNull { brushes[it] == null }
                if (missing != null) {
                    chain.composed { reportUnknownBrush(nodeId, paints[missing], dispatcher) }
                } else {
                    chain.borderEach(value, brushes.map { it!! }, corners)
                }
            }

            // Read by the background, the border, the shadow and the clip, through `corners`
            // above. On its own it draws nothing and cuts nothing, as in CSS, where a radius
            // with no overflow rule rounds the decoration and leaves the content alone.
            is ProtocolModifier.CornerEach -> chain
            is ProtocolModifier.Shadow -> theme.brush(value.paint)?.let { brush ->
                chain.boxShadow(value, brush, corners)
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }

            // `overflow: hidden`: the rounded box where the node has corners, its rectangle
            // where it has none.
            is ProtocolModifier.Clip ->
                if (value.enabled) chain.clip(corners ?: RectangleShape) else chain

            // CSS `transform`: drawn through layers, so the layout is untouched and a
            // pointer is tested against the shape that is drawn.
            is ProtocolModifier.Transform -> chain.affineTransform(
                value.originX,
                value.originY,
                AffineParts.isOneLayer(value.a, value.b, value.c, value.d),
            ) { matrix ->
                matrix[0] = value.a
                matrix[1] = value.b
                matrix[2] = value.c
                matrix[3] = value.d
                matrix[4] = value.e
                matrix[5] = value.f
            }

            // The node and everything in it are drawn into one layer first, and the layer is
            // faded once. Fading each child separately would show where they overlap, which
            // CSS `opacity` does not.
            is ProtocolModifier.Alpha -> chain.groupAlpha { value.value }
        }
    }
}

/**
 * Fades this node and everything drawn inside it as one group, the way CSS `opacity` does.
 *
 * The content is drawn into a layer of its own and the layer is drawn once at [alpha], so
 * where two children overlap the overlap is no darker than either of them. The layer has
 * no edge of its own: a child that overflows the node, or content a transform has turned
 * past the node's rectangle, is faded rather than cut off. A Compose layer with an
 * offscreen buffer would cut it at the node's bounds, which CSS never does.
 *
 * [alpha] is read while drawing, so a value that changes every frame only redraws.
 */
internal fun Modifier.groupAlpha(alpha: () -> Float): Modifier {
    val paint = Paint()
    return drawWithContent {
        val opacity = alpha().coerceIn(0f, 1f)
        when {
            opacity >= 1f -> drawContent()
            opacity <= 0f -> Unit
            else -> {
                paint.alpha = opacity
                drawContext.canvas.saveLayer(UNBOUNDED_GROUP, paint)
                drawContent()
                drawContext.canvas.restore()
            }
        }
    }
}

/**
 * A layer as large as anything a screen can draw. The layer that is allocated is only as
 * large as what is visible, because drawing is limited to the clip before it starts.
 */
private val UNBOUNDED_GROUP = Rect(-1e7f, -1e7f, 1e7f, 1e7f)

/**
 * Says that a paint named a brush that is not registered, and paints nothing.
 *
 * Once per node and per id, the same way a missing picture is reported. The node keeps
 * its place in the layout: a surface that vanished would take its children with it.
 */
@Composable
private fun Modifier.reportUnknownBrush(
    nodeId: Int,
    paint: dev.darkpyonix.composerust.protocol.Paint,
    dispatcher: EventDispatcher,
): Modifier {
    val assetId = (paint as? dev.darkpyonix.composerust.protocol.Paint.Asset)?.assetId ?: return this
    LaunchedEffect(nodeId, assetId) {
        dispatcher.dispatch(
            HostEvent.ProtocolError(
                nodeId = nodeId,
                handlerId = 0,
                code = TableError.UNKNOWN_ASSET,
                message = "brush $assetId is not registered, so node $nodeId was not painted",
            ),
        )
    }
    return this
}

/** The shape this node's clip, border and background all use. */
internal fun List<ProtocolModifier>.resolvedShape(theme: ResolvedTheme): Shape {
    for (index in indices.reversed()) {
        when (val value = this[index]) {
            // A radius the Host named is still cut the way the running design system cuts
            // corners. The Host asked for a size, not for an arc.
            is ProtocolModifier.Shape -> return theme.shapeOfRadii(
                topStart = value.topStart,
                topEnd = value.topEnd,
                bottomEnd = value.bottomEnd,
                bottomStart = value.bottomStart,
            )

            is ProtocolModifier.ShapeRole -> return theme.shape(value.role)

            // Radii an HTML box named, cut as plain arcs the way CSS cuts them. Unlike a
            // `Shape`, these are not the design system's to reinterpret: the page's author
            // chose them, and the screen has to match what a browser draws.
            is ProtocolModifier.CornerEach -> return value.toShape()
            else -> Unit
        }
    }
    return RectangleShape
}

/** The corners this node named for itself, or null. The last one in the list stands. */
internal fun List<ProtocolModifier>.cornerEachShape(): CornerEachShape? =
    (lastOrNull { it is ProtocolModifier.CornerEach } as ProtocolModifier.CornerEach?)?.toShape()

private fun ProtocolModifier.CornerEach.toShape() =
    CornerEachShape(topLeft, topRight, bottomRight, bottomLeft)

/**
 * A rectangle with its own radius at each corner, in dp, cut the way CSS cuts
 * `border-radius`.
 *
 * Where two radii on one side add up to more than the side, CSS shrinks every radius by
 * the same factor until they fit, so the shape keeps its proportions. Compose's own
 * rounded shapes clamp each corner on its own, which draws a different curve, so this does
 * the scaling itself.
 */
internal data class CornerEachShape(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(roundRect(size, density))

    /** The outline at the origin, with the radii in pixels and scaled to fit. */
    fun roundRect(size: Size, density: Density): RoundRect {
        val px = with(density) { floatArrayOf(topLeft.dp.toPx(), topRight.dp.toPx(), bottomRight.dp.toPx(), bottomLeft.dp.toPx()) }
        var scale = 1f
        fun fit(side: Float, first: Float, second: Float) {
            val sum = first + second
            if (sum > 0f && side < sum * scale) scale = side / sum
        }
        fit(size.width, px[0], px[1])
        fit(size.width, px[3], px[2])
        fit(size.height, px[0], px[3])
        fit(size.height, px[1], px[2])
        return RoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            topLeftCornerRadius = CornerRadius(max(0f, px[0] * scale)),
            topRightCornerRadius = CornerRadius(max(0f, px[1] * scale)),
            bottomRightCornerRadius = CornerRadius(max(0f, px[2] * scale)),
            bottomLeftCornerRadius = CornerRadius(max(0f, px[3] * scale)),
        )
    }
}

/** The node's outline at the origin: its corners where it has them, its rectangle otherwise. */
private fun boxOutline(size: Size, density: Density, corners: CornerEachShape?): RoundRect =
    corners?.roundRect(size, density) ?: RoundRect(0f, 0f, size.width, size.height)

/**
 * A border with its own width and paint on each side, drawn the way CSS draws one.
 *
 * The border is the ring between the node's outline and the same outline brought in by each
 * side's width, with every inner radius reduced by the widths next to it. Each side paints
 * the part of the ring on its side of the two lines that run from the outer corners through
 * the inner ones, which is where CSS puts the join between two sides of different colours.
 *
 * Drawn behind the content, over the background, as CSS draws it. Nothing about the layout
 * changes: CSS's border box is the size the Host already sent.
 */
private fun Modifier.borderEach(
    value: ProtocolModifier.BorderEach,
    brushes: List<Brush>,
    corners: CornerEachShape?,
): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    val top = value.top.dp.toPx().coerceAtLeast(0f)
    val right = value.right.dp.toPx().coerceAtLeast(0f)
    val bottom = value.bottom.dp.toPx().coerceAtLeast(0f)
    val left = value.left.dp.toPx().coerceAtLeast(0f)
    val outer = boxOutline(size, this, corners)
    val inner = RoundRect(
        left = left,
        top = top,
        right = max(left, width - right),
        bottom = max(top, height - bottom),
        topLeftCornerRadius = CornerRadius(
            max(0f, outer.topLeftCornerRadius.x - left),
            max(0f, outer.topLeftCornerRadius.y - top),
        ),
        topRightCornerRadius = CornerRadius(
            max(0f, outer.topRightCornerRadius.x - right),
            max(0f, outer.topRightCornerRadius.y - top),
        ),
        bottomRightCornerRadius = CornerRadius(
            max(0f, outer.bottomRightCornerRadius.x - right),
            max(0f, outer.bottomRightCornerRadius.y - bottom),
        ),
        bottomLeftCornerRadius = CornerRadius(
            max(0f, outer.bottomLeftCornerRadius.x - left),
            max(0f, outer.bottomLeftCornerRadius.y - bottom),
        ),
    )
    val ring = Path.combine(
        PathOperation.Difference,
        Path().apply { addRoundRect(outer) },
        Path().apply { addRoundRect(inner) },
    )
    val middleX = width / 2f
    val middleY = height / 2f
    // Each side's share of the ring: from its two outer corners along the joins towards the
    // middle of the box. The joins run through the inner corners, so a thick side takes
    // more of the corner than a thin one, as in a browser.
    fun polygon(vararg xy: Float) = Path().apply {
        moveTo(xy[0], xy[1])
        for (index in 2 until xy.size step 2) lineTo(xy[index], xy[index + 1])
        close()
    }
    val regions = listOf(
        if (top > 0f) polygon(
            0f, 0f,
            width, 0f,
            width - right * middleY / top, middleY,
            left * middleY / top, middleY,
        ) else null,
        if (right > 0f) polygon(
            width, 0f,
            width, height,
            middleX, height - bottom * middleX / right,
            middleX, top * middleX / right,
        ) else null,
        if (bottom > 0f) polygon(
            width, height,
            0f, height,
            left * middleY / bottom, middleY,
            width - right * middleY / bottom, middleY,
        ) else null,
        if (left > 0f) polygon(
            0f, height,
            0f, 0f,
            middleX, top * middleX / left,
            middleX, height - bottom * middleX / left,
        ) else null,
    )
    onDrawBehind {
        regions.forEachIndexed { index, region ->
            if (region != null) {
                clipPath(region) { drawPath(ring, brushes[index]) }
            }
        }
    }
}

/**
 * One CSS `box-shadow`: the node's outline, grown by the spread, moved by the offset and
 * blurred, and drawn only outside the node.
 *
 * CSS cuts the shadow out from under the box it belongs to, so a translucent box does not
 * show its own shadow through itself. That is why this is drawn here, behind the content
 * with the box cut away, rather than by Compose's own shadow modifiers, which leave the
 * shadow under the box.
 *
 * Later shadows draw over earlier ones, because a modifier list draws in its order. CSS
 * draws the first shadow in its list on top, so a Host sends a CSS list last to first.
 */
private fun Modifier.boxShadow(
    value: ProtocolModifier.Shadow,
    brush: Brush,
    corners: CornerEachShape?,
): Modifier = drawWithCache {
    val spread = value.spread.dp.toPx()
    val blur = value.blur.dp.toPx().coerceAtLeast(0f)
    val dx = value.x.dp.toPx()
    val dy = value.y.dp.toPx()
    val box = boxOutline(size, this, corners)
    val boxPath = Path().apply { addRoundRect(box) }
    val shadowWidth = size.width + 2f * spread
    val shadowHeight = size.height + 2f * spread
    if (shadowWidth <= 0f || shadowHeight <= 0f) {
        return@drawWithCache onDrawBehind { }
    }
    // The spread grows each corner's radius with it, by the rule CSS gives so that a small
    // radius does not jump to a large one when the spread is large.
    fun grown(radius: CornerRadius) = CornerRadius(spreadRadius(radius.x, spread), spreadRadius(radius.y, spread))
    val shape = RoundRect(
        left = 0f,
        top = 0f,
        right = shadowWidth,
        bottom = shadowHeight,
        topLeftCornerRadius = grown(box.topLeftCornerRadius),
        topRightCornerRadius = grown(box.topRightCornerRadius),
        bottomRightCornerRadius = grown(box.bottomRightCornerRadius),
        bottomLeftCornerRadius = grown(box.bottomLeftCornerRadius),
    )
    val shapePath = Path().apply { addRoundRect(shape) }
    if (blur == 0f) {
        return@drawWithCache onDrawBehind {
            clipPath(boxPath, ClipOp.Difference) {
                translate(dx - spread, dy - spread) { drawPath(shapePath, brush) }
            }
        }
    }
    // CSS's blur radius is twice the standard deviation of the Gaussian it blurs with. The
    // shape is drawn into a layer with room for the blur around it, and the layer is
    // blurred as a whole.
    val sigma = blur / 2f
    val margin = ceil(3f * sigma)
    val layer = obtainGraphicsLayer()
    layer.record(
        this,
        layoutDirection,
        IntSize(ceil(shadowWidth + 2f * margin).toInt(), ceil(shadowHeight + 2f * margin).toInt()),
    ) {
        translate(margin, margin) { drawPath(shapePath, brush) }
    }
    layer.renderEffect = BlurEffect(composeBlurRadius(sigma), composeBlurRadius(sigma), TileMode.Decal)
    onDrawBehind {
        clipPath(boxPath, ClipOp.Difference) {
            translate(dx - spread - margin, dy - spread - margin) { drawLayer(layer) }
        }
    }
}

/**
 * The radius Compose's blur takes for a Gaussian of standard deviation [sigma].
 *
 * Compose turns a blur radius r into a deviation of 0.57735 r + 0.5, the conversion Skia
 * and Android both use, so this is that conversion run backwards. Below half a pixel there
 * is nothing to blur.
 */
private fun composeBlurRadius(sigma: Float): Float = max(0f, (sigma - 0.5f) / 0.57735f)

/**
 * A corner radius after a shadow's spread, as CSS Backgrounds 3 gives it: a radius of zero
 * stays sharp, a negative spread shrinks the radius, and a positive spread grows it by less
 * than the spread when the radius is smaller than the spread.
 */
private fun spreadRadius(radius: Float, spread: Float): Float = when {
    radius <= 0f -> 0f
    spread < 0f -> max(0f, radius + spread)
    spread == 0f || radius >= spread -> radius + spread
    else -> {
        val ratio = radius / spread - 1f
        radius + spread * (1f + ratio * ratio * ratio)
    }
}

/** The weight this node asked its parent layout for, or null. */
internal fun List<ProtocolModifier>.weightOf(): Float? =
    lastOrNull { it is ProtocolModifier.Weight }
        ?.let { (it as ProtocolModifier.Weight).value }
        ?.takeIf { it > 0f }

/**
 * Pointer handling for `Modifier.Clickable`.
 *
 * The Host handler runs synchronously inside the gesture, and its result decides whether the
 * pointer change is consumed. A Host that does not consume leaves the gesture
 * available to whatever is underneath.
 */
private fun Modifier.hostClickable(
    nodeId: Int,
    handlerId: Long,
    dispatcher: EventDispatcher,
): Modifier = pointerInput(nodeId, handlerId, dispatcher) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        if (dispatcher.dispatch(HostEvent.Clicked(nodeId, handlerId))) {
            down.consume()
            up.consume()
        }
    }
}

/**
 * Reports this node's width when the class it falls in changes.
 *
 * The same event the window's own size travels on, with this node's id instead of zero:
 * a node being narrow or wide means what it means for a window, and a second way of
 * saying it would be a second thing to keep in step.
 *
 * The class is remembered per node rather than the size, so a drag that widens a panel
 * without crossing a boundary reports nothing at all.
 */
private fun Modifier.reportSizeTo(nodeId: Int, dispatcher: EventDispatcher): Modifier =
    composed {
        val density = LocalDensity.current
        val reportedWidth = remember(nodeId) { arrayOfNulls<WindowSizeClass>(1) }
        val reportedHeight = remember(nodeId) { arrayOfNulls<WindowHeightClass>(1) }
        onSizeChanged { size ->
            val widthDp = with(density) { size.width.toDp().value }
            val heightDp = with(density) { size.height.toDp().value }
            val sizeClass = windowSizeClassOf(widthDp)
            val heightClass = windowHeightClassOf(heightDp)
            // Either axis changing is one event, because one record holds both. Sending
            // two would mean two boundary crossings for one resize.
            if (reportedWidth[0] != sizeClass || reportedHeight[0] != heightClass) {
                reportedWidth[0] = sizeClass
                reportedHeight[0] = heightClass
                dispatcher.dispatch(
                    HostEvent.WindowSizeChanged(
                        nodeId = nodeId,
                        handlerId = 0,
                        widthDp = widthDp,
                        heightDp = heightDp,
                        sizeClass = sizeClass,
                        heightClass = heightClass,
                    ),
                )
            }
        }
    }
