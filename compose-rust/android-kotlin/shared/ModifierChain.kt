package dev.darkpyonix.composerust.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import dev.darkpyonix.composerust.runtime.LocalWindowDragExclusion
import dev.darkpyonix.composerust.runtime.LocalWindowDrag
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import dev.darkpyonix.composerust.protocol.BackgroundRepeat
import dev.darkpyonix.composerust.protocol.ColorRole
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import dev.darkpyonix.composerust.foundation.LocalOverlayFocusStack
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
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
    animated: (() -> dev.darkpyonix.composerust.ui.node.AnimatedNode)? = null,
    containerClickHandler: Long? = null,
    containerEnabled: Boolean = true,
    containerShape: Shape? = null,
    pressColor: Color = theme.color(ColorRole.OnSurface),
    allowPress: Boolean = true,
    allowPadding: Boolean = true,
    pointerEvents: Modifier? = null,
    motionFinished: ((initialValue: IntSize, targetValue: IntSize) -> Unit)? = null,
): Modifier {
    // The last Shape or ShapeRole in the list is what clips, what the border
    // follows and what the background fills, whatever their order in the chain.
    val shape = resolvedShape(theme)
    val pressShape = containerShape ?: shape
    val feedback = lastOrNull { it is ProtocolModifier.PressFeedback } as? ProtocolModifier.PressFeedback
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
    // How those corners curve, or null for plain arcs (or the design system's own curve).
    // The background, border, shadow, clip and press feedback all draw the same outline.
    val curve = cornerCurve()
    // The first opacity, background, transform and border of a node are where an animation the
    // Renderer plays on it is drawn. They read the animated value in the draw phase, so
    // the animation never recomposes or remeasures the node.
    var alphaSeen = false
    var backgroundSeen = false
    var transformSeen = false
    var borderSeen = false
    var pressableAttached = false
    fun attachPressableIfNeeded(chain: Modifier): Modifier {
        if (pressableAttached || containerClickHandler == null) return chain
        pressableAttached = true
        return chain.pressable(
            nodeId,
            containerClickHandler,
            containerEnabled,
            dispatcher,
            theme,
            pressShape,
            feedback,
            pressColor,
        )
    }
    // A node's pointer subscription listens over the whole node, so it goes ahead of everything
    // else. A node drawn through a transform is the exception: there it goes just inside the
    // last one, where Compose has already taken the pointer back through the transform, so
    // the node is hit where it is drawn and the position is in its own untransformed box.
    val pointerAfter = if (pointerEvents == null) {
        -1
    } else {
        indexOfLast { it is ProtocolModifier.Transform || it is ProtocolModifier.Transform3D }
    }
    val outermost = if (pointerEvents != null && pointerAfter < 0) pointerEvents.then(surface) else surface
    val folded = foldIndexed(outermost) { index, chain, value ->
        val next = when (value) {
            is ProtocolModifier.WindowDrag -> chain.composed {
                val drag = LocalWindowDrag.current
                val exclusion = LocalWindowDragExclusion.current
                pointerInput(value.enabled, drag, exclusion) {
                    awaitEachGesture {
                        if (value.enabled) {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                            // A no-drag node under the press has marked it already (children see the
                            // Main pass first), so the window stays where it is.
                            val excludedHere = exclusion?.isMarked(down.id.value, down.uptimeMillis) == true
                            if (!down.isConsumed && !excludedHere) {
                                down.consume()
                                drag()
                            }
                        } else {
                            // Mark this down for the strip without consuming it, so ancestors can click.
                            val excluded = awaitFirstDown(requireUnconsumed = false)
                            exclusion?.mark(excluded.id.value, excluded.uptimeMillis)
                            // Forget the press once it ends, so a later press that happens to
                            // share an id and a timestamp is not taken for this one.
                            waitForUpOrCancellation()
                            exclusion?.clear()
                        }
                    }
                }
            }
            is ProtocolModifier.PressFeedback -> chain
            // Applied with the node's accessible name in one place, `accessibility`.
            is ProtocolModifier.Semantics -> chain
            // Read through `shape` and `curve` above: it bends the corners the other
            // modifiers draw, and on its own draws nothing.
            is ProtocolModifier.CornerCurve -> chain
            // Applied round the whole node where it is rendered, outside every other
            // modifier, so the area it grows is centred on the box the node is drawn in.
            is ProtocolModifier.MinTouchTarget -> chain
            is ProtocolModifier.Empty -> chain
            is ProtocolModifier.Padding ->
                if (allowPadding) attachPressableIfNeeded(chain).padding(value.value.dp) else chain
            is ProtocolModifier.MatchParentSize -> chain
            is ProtocolModifier.FillMaxWidth -> chain.fillMaxWidth()
            is ProtocolModifier.FillMaxHeight -> chain.fillMaxHeight()
            is ProtocolModifier.Width -> chain.width(value.value.dp)
            is ProtocolModifier.Height -> chain.height(value.value.dp)
            is ProtocolModifier.Size -> chain.size(value.width.dp, value.height.dp)
            // A gradient where the paint named one, a flat colour otherwise. A paint
            // that names a brush nobody registered is reported and left unpainted: a
            // guess would leave a screen subtly wrong with nothing to read about why.
            is ProtocolModifier.Background -> theme.brush(value.paint)?.let { brush ->
                val holder = if (!backgroundSeen) animated?.invoke() else null
                backgroundSeen = true
                if (holder == null) chain.background(brush, shape) else chain.animatedBackground(brush, shape, holder)
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }
            is ProtocolModifier.Clickable -> {
                pressableAttached = true
                if (allowPress) chain.pressable(nodeId, value.handlerId, containerEnabled, dispatcher, theme, shape, feedback, pressColor) else chain
            }

            is ProtocolModifier.PaddingEach ->
                if (allowPadding) {
                    attachPressableIfNeeded(chain).padding(value.start.dp, value.top.dp, value.end.dp, value.bottom.dp)
                } else {
                    chain
                }
            is ProtocolModifier.PaddingRole ->
                if (allowPadding) {
                    attachPressableIfNeeded(chain).padding(theme.space(value.role))
                } else {
                    chain
                }
            is ProtocolModifier.Shape -> chain.clip(shape)
            is ProtocolModifier.ShapeRole -> chain.clip(shape)
            is ProtocolModifier.Border -> theme.brush(value.paint)?.let { brush ->
                val holder = if (!borderSeen) animated?.invoke() else null
                borderSeen = true
                // Compose's own border until an animation first plays on this one, then the
                // layer that can repaint it in the draw phase.
                if (holder != null && holder.borderAnimated.value) {
                    chain.animatedBorder(value.width.dp, brush, shape, holder)
                } else {
                    chain.border(value.width.dp, brush, shape)
                }
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }
            is ProtocolModifier.Elevation ->
                theme.rules.elevation(chain, value.value.dp, shape, theme)

            // Only a node that asked is measured. Nothing is attached to a node without
            // this modifier, so a tree that observes nothing is laid out exactly as it
            // was before any of this existed.
            is ProtocolModifier.ObserveSize ->
                if (value.everyDp) chain.reportEveryDpTo(nodeId, dispatcher) else chain.reportSizeTo(nodeId, dispatcher)

            // How important this node's changes are. What that means in milliseconds and
            // along which curve is the running design system's answer, and a system the
            // user has asked to hold still answers every role with no run at all.
            //
            // A node with a motion handler is told when a run reaches its target. Compose
            // calls the listener only for a run that finished, never for one a newer target
            // interrupted, and never for the first layout, which has nothing to animate. A
            // node without the handler passes no listener, which is the modifier it always had.
            is ProtocolModifier.Motion ->
                chain.animateContentSize(theme.motion(value.role), finishedListener = motionFinished)

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
                    val holder = if (!borderSeen) animated?.invoke() else null
                    borderSeen = true
                    chain.borderEach(value, brushes.map { it!! }, corners, curve ?: 0f, holder)
                }
            }

            // Read by the background, the border, the shadow and the clip, through `corners`
            // above. On its own it draws nothing and cuts nothing, as in CSS, where a radius
            // with no overflow rule rounds the decoration and leaves the content alone.
            is ProtocolModifier.CornerEach -> chain
            is ProtocolModifier.Shadow -> theme.brush(value.paint)?.let { brush ->
                chain.boxShadow(value, brush, corners, shape, curve ?: 0f)
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }

            // CSS `background-image` with its position, size and repeat: one brush laid into
            // a rectangle and repeated across the node, cut to the node's outline. Drawn
            // where it sits in the chain, so a layer after the background colour lies over it.
            is ProtocolModifier.BackgroundBrush -> theme.brush(value.paint)?.let { brush ->
                chain.backgroundBrush(value, brush, corners?.withCornerCurve(curve) ?: shape)
            } ?: chain.composed { reportUnknownBrush(nodeId, value.paint, dispatcher) }

            // `overflow: hidden`: the rounded box where the node has corners, its rectangle
            // where it has none.
            is ProtocolModifier.Clip ->
                if (value.enabled) chain.clip(corners?.withCornerCurve(curve) ?: shape) else chain

            // CSS `transform`: drawn through layers, so the layout is untouched and a
            // pointer is tested against the shape that is drawn.
            is ProtocolModifier.Transform -> {
                val holder = if (!transformSeen) animated?.invoke() else null
                transformSeen = true
                chain.affineTransform(
                    value.originX,
                    value.originY,
                    AffineParts.isOneLayer(value.a, value.b, value.c, value.d) &&
                        holder?.twoLayers?.value != true,
                ) { matrix ->
                    // Read so a layer redraws when an animation writes a new matrix.
                    val shown = holder != null && holder.transformVersion.intValue >= 0 && holder.transformShown
                    if (shown) {
                        holder!!.matrix.copyInto(matrix)
                    } else {
                        matrix[0] = value.a
                        matrix[1] = value.b
                        matrix[2] = value.c
                        matrix[3] = value.d
                        matrix[4] = value.e
                        matrix[5] = value.f
                    }
                }
            }

            // CSS 3D `transform`: one matrix, drawn through the layers that reproduce it
            // exactly, so the layout is untouched and a pointer is tested against the shape
            // that is drawn. The plan is worked out here, once for each value of the record.
            is ProtocolModifier.Transform3D -> {
                val plan = Transform3DPlan()
                plan.solve(
                    floatArrayOf(value.m0, value.m1, value.m2, value.m3, value.m4, value.m5, value.m6, value.m7, value.m8, value.m9, value.m10, value.m11, value.m12, value.m13, value.m14, value.m15),
                    value.backfaceHidden,
                )
                chain.perspectiveTransform(plan, value.originX, value.originY)
            }

            // The node and everything in it are drawn into one layer first, and the layer is
            // faded once. Fading each child separately would show where they overlap, which
            // CSS `opacity` does not.
            is ProtocolModifier.Alpha -> {
                val holder = if (!alphaSeen) animated?.invoke() else null
                alphaSeen = true
                if (holder == null) {
                    chain.groupAlpha { value.value }
                } else {
                    chain.groupAlpha {
                        val shown = holder.alpha.floatValue
                        if (shown.isNaN()) value.value else shown
                    }
                }
            }
        }
        if (index == pointerAfter && pointerEvents != null) next.then(pointerEvents) else next
    }
    return attachPressableIfNeeded(folded)
}

/**
 * One background layer: [brush] laid into the rectangle [value] names and repeated across
 * the node the way its repeat says, cut to [outline].
 *
 * Each copy is the brush drawn into a rectangle of its own, so a gradient is measured
 * against one tile, as CSS measures a `background-image` against its `background-size`.
 * A repeat runs both ways from the rectangle until it is past the node's edges, and only
 * the copies that reach into the node are drawn. Drawn behind the content, as a background.
 */
private fun Modifier.backgroundBrush(
    value: ProtocolModifier.BackgroundBrush,
    brush: Brush,
    outline: Shape,
): Modifier = drawWithCache {
    val cut = if (outline === RectangleShape) null else outline.createOutline(size, layoutDirection, this)
    val clip = cut?.let { Path().apply { addOutline(it) } }
    val left = value.x.dp.toPx()
    val top = value.y.dp.toPx()
    val tile = Size(value.width.dp.toPx(), value.height.dp.toPx())
    val stepX = value.tile_width.dp.toPx()
    val stepY = value.tile_height.dp.toPx()
    val acrossX = value.repeat == BackgroundRepeat.Repeat || value.repeat == BackgroundRepeat.RepeatX
    val acrossY = value.repeat == BackgroundRepeat.Repeat || value.repeat == BackgroundRepeat.RepeatY
    // The copies that reach into the node, counted from the one at the rectangle.
    val columns = if (acrossX) copiesAcross(left, tile.width, stepX, size.width) else 0..0
    val rows = if (acrossY) copiesAcross(top, tile.height, stepY, size.height) else 0..0
    onDrawBehind {
        if (tile.width <= 0f || tile.height <= 0f) return@onDrawBehind
        val paintCopies: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
            for (row in rows) {
                for (column in columns) {
                    // The drawing area is made the tile's own, because a brush is
                    // measured against the area it is drawn in, not the rectangle.
                    withTransform({
                        translate(left + column * stepX, top + row * stepY)
                        inset(0f, 0f, size.width - tile.width, size.height - tile.height)
                    }) {
                        drawRect(brush)
                    }
                }
            }
        }
        if (clip == null) {
            clipRect { paintCopies() }
        } else {
            clipPath(clip) { paintCopies() }
        }
    }
}

/**
 * Which copies of a tile [length] long, placed every [step] from [start], reach into a span
 * from 0 to [extent]: copy 0 is the one at [start], and the range runs both ways from it.
 */
private fun copiesAcross(start: Float, length: Float, step: Float, extent: Float): IntRange {
    if (step <= 0f || length <= 0f) return 0..0
    // A copy n covers start + n * step to start + n * step + length.
    val first = kotlin.math.ceil((-length - start) / step).toInt()
    val last = kotlin.math.floor((extent - start) / step).toInt()
    return if (first > last) 0..-1 else first..last
}

/**
 * A background that an animation can repaint without recomposing the node: the same fill
 * `Modifier.background` draws, with the animated colour read in the draw phase in its
 * place while one plays.
 */
private fun Modifier.animatedBackground(
    brush: Brush,
    shape: Shape,
    holder: dev.darkpyonix.composerust.ui.node.AnimatedNode,
): Modifier = drawWithCache {
    val outline = if (shape === RectangleShape) null else shape.createOutline(size, layoutDirection, this)
    onDrawBehind {
        val shown = holder.backgroundOverride()
        when {
            shown != null && outline == null -> drawRect(color = shown)
            shown != null -> drawOutline(outline!!, color = shown)
            outline == null -> drawRect(brush)
            else -> drawOutline(outline, brush)
        }
    }
}

/**
 * A uniform border that an animation can repaint without recomposing the node: a stroke of
 * [width] along the inside of the node's [shape], drawn over the content as
 * `Modifier.border` draws it, in the animated colour while one plays.
 */
private fun Modifier.animatedBorder(
    width: Dp,
    brush: Brush,
    shape: Shape,
    holder: dev.darkpyonix.composerust.ui.node.AnimatedNode,
): Modifier = drawWithCache {
    val stroke = width.toPx().coerceIn(0f, minOf(size.width, size.height) / 2f)
    val half = stroke / 2f
    val inner = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
    val outline = shape.createOutline(inner, layoutDirection, this)
    val style = Stroke(stroke)
    onDrawWithContent {
        drawContent()
        if (stroke > 0f) {
            val shown = holder.borderOverride()
            translate(half, half) {
                if (shown != null) {
                    drawOutline(outline, color = shown, style = style)
                } else {
                    drawOutline(outline, brush, style = style)
                }
            }
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

/**
 * The shape this node's clip, border, background, shadow and press feedback all use: its
 * radii, with its corners curved the way its `CornerCurve` says when it has one.
 */
internal fun List<ProtocolModifier>.resolvedShape(theme: ResolvedTheme): Shape =
    resolvedBaseShape(theme).withCornerCurve(cornerCurve())

private fun List<ProtocolModifier>.resolvedBaseShape(theme: ResolvedTheme): Shape {
    for (index in indices.reversed()) {
        when (val value = this[index]) {
            // A radius the Host wrote as a number is a circular arc of that many dp in every
            // design system, as CSS `border-radius` is. Only a corner chosen by
            // role follows the system's own curve: a continuous corner of 14 dp measures
            // about 8 px with a ruler, so a number would not mean what it says.
            is ProtocolModifier.Shape -> return RoundedCornerShape(
                topStart = value.topStart.dp,
                topEnd = value.topEnd.dp,
                bottomEnd = value.bottomEnd.dp,
                bottomStart = value.bottomStart.dp,
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
 * The outline a shadow is cast by: [box] for the radii the spread grows, and [exact] when
 * the shape is a path with no radii to read (a continuous corner), which is used as it is.
 */
private class ShadowOutline(val box: RoundRect, val exact: Path?)

/**
 * The node's outline for its shadows: the corners an HTML box named, else the shape its
 * `Shape` or `ShapeRole` gave (the same shape its background and clip use), else the
 * rectangle. A container with a corner radius therefore casts a rounded shadow.
 */
private fun shadowOutline(
    size: Size,
    density: Density,
    direction: LayoutDirection,
    corners: CornerEachShape?,
    shape: Shape,
): ShadowOutline {
    if (corners != null) return ShadowOutline(corners.roundRect(size, density), null)
    // A curved corner is read through the radii it curves, so the spread can grow them.
    val plain = (shape as? CornerCurveShape)?.base ?: shape
    return when (val outline = plain.createOutline(size, direction, density)) {
        is Outline.Rounded -> ShadowOutline(outline.roundRect, null)
        is Outline.Rectangle -> ShadowOutline(RoundRect(outline.rect), null)
        is Outline.Generic -> ShadowOutline(RoundRect(outline.path.getBounds()), outline.path)
    }
}

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
    /** How the corners curve; 0 for circular arcs, as CSS draws them. */
    smoothing: Float,
    /** Where an animation of the border's colour writes the colour every side shows. */
    holder: dev.darkpyonix.composerust.ui.node.AnimatedNode? = null,
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
        if (smoothing > 0f) cornerCurvePath(outer, smoothing) else Path().apply { addRoundRect(outer) },
        if (smoothing > 0f) cornerCurvePath(inner, smoothing) else Path().apply { addRoundRect(inner) },
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
        val shown = holder?.borderOverride()
        regions.forEachIndexed { index, region ->
            if (region != null) {
                clipPath(region) {
                    if (shown != null) drawPath(ring, color = shown) else drawPath(ring, brushes[index])
                }
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
 * number of shadows is any number of these.
 *
 * The outline is the node's own: its `CornerEach` corners, else its `Shape` or `ShapeRole`.
 */
private fun Modifier.boxShadow(
    value: ProtocolModifier.Shadow,
    brush: Brush,
    corners: CornerEachShape?,
    shape: Shape,
    /** How the outline's corners curve; 0 for circular arcs. */
    smoothing: Float = 0f,
): Modifier = drawWithCache {
    val spread = value.spread.dp.toPx()
    val blur = value.blur.dp.toPx().coerceAtLeast(0f)
    val dx = value.x.dp.toPx()
    val dy = value.y.dp.toPx()
    val cast = shadowOutline(size, this, layoutDirection, corners, shape)
    val box = cast.box
    val boxPath = when {
        smoothing > 0f -> cornerCurvePath(box, smoothing)
        else -> cast.exact ?: Path().apply { addRoundRect(box) }
    }
    val shadowWidth = size.width + 2f * spread
    val shadowHeight = size.height + 2f * spread
    if (shadowWidth <= 0f || shadowHeight <= 0f) {
        return@drawWithCache onDrawBehind { }
    }
    // The spread grows each corner's radius with it, by the rule CSS gives so that a small
    // radius does not jump to a large one when the spread is large.
    fun grown(radius: CornerRadius) = CornerRadius(spreadRadius(radius.x, spread), spreadRadius(radius.y, spread))
    val grownBox = RoundRect(
        left = 0f,
        top = 0f,
        right = shadowWidth,
        bottom = shadowHeight,
        topLeftCornerRadius = grown(box.topLeftCornerRadius),
        topRightCornerRadius = grown(box.topRightCornerRadius),
        bottomRightCornerRadius = grown(box.bottomRightCornerRadius),
        bottomLeftCornerRadius = grown(box.bottomLeftCornerRadius),
    )
    val shapePath = when {
        smoothing > 0f -> cornerCurvePath(grownBox, smoothing)
        spread == 0f && cast.exact != null -> cast.exact
        else -> Path().apply { addRoundRect(grownBox) }
    }
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
 * Makes a node a button: it takes keyboard focus, a click and Enter or Space run
 * the Host handler, assistive technology is told it is a button, and hover, press and focus
 * are drawn over its content in the shape it is drawn in.
 *
 * A pointer-only handler would lose all but the first of those, which is why `Clickable`
 * and the `OnClick` property of a container both come through here.
 */
internal fun Modifier.pressable(
    nodeId: Int,
    handlerId: Long,
    enabled: Boolean,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    shape: Shape,
    feedback: ProtocolModifier.PressFeedback? = null,
    pressColor: Color = theme.color(ColorRole.OnSurface),
): Modifier = composed {
    val source = remember(nodeId, handlerId) { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var keyboardFocused by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        var pressActive = false
        var focusedByPointer = false
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressActive = true
                    keyboardFocused = false
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    pressActive = false
                }
                is FocusInteraction.Focus -> {
                    focusedByPointer = pressActive
                    keyboardFocused = !focusedByPointer
                }
                is FocusInteraction.Unfocus -> {
                    focusedByPointer = false
                    keyboardFocused = false
                }
            }
        }
    }
    val ink = theme.color(ColorRole.OnSurface)
    val ring = theme.color(ColorRole.Primary)
    val overlayFocusStack = LocalOverlayFocusStack.current
    val focusRequester = remember(nodeId) { FocusRequester() }
    this
        .focusRequester(focusRequester)
        .onFocusChanged { state -> overlayFocusStack.noteFocused(focusRequester, state.isFocused) }
        .pressFeedback(source, enabled, theme, shape, pressColor, feedback)
        .hoverable(source, enabled)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Button,
        ) { dispatcher.dispatch(HostEvent.Clicked(nodeId, handlerId)) }
        .drawWithContent {
            drawContent()
            if (!enabled) return@drawWithContent
            val layer = when {
                hovered -> HOVER_LAYER
                else -> 0f
            }
            if (layer > 0f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, ink.copy(alpha = layer))
            }
            if (keyboardFocused) {
                val stroke = FOCUS_RING_WIDTH.toPx().coerceIn(0f, minOf(size.width, size.height) / 2f)
                if (stroke > 0f) {
                    val half = stroke / 2f
                    val inner = Size(
                        (size.width - stroke).coerceAtLeast(0f),
                        (size.height - stroke).coerceAtLeast(0f),
                    )
                    val innerOutline = shape.createOutline(inner, layoutDirection, this)
                    translate(half, half) {
                        drawOutline(innerOutline, ring, style = Stroke(stroke))
                    }
                }
            }
        }
}


private const val PRESS_SCALE = 0.975f
private const val PRESS_TINT_LIGHT = 0.07f
private const val PRESS_TINT_DARK = 0.10f
private const val PRESS_IN_MILLIS = 180
private const val PRESS_OUT_MILLIS = 200
private const val PRESS_MIN_MILLIS = 120L
private val PRESS_IN_EASING = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val PRESS_OUT_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal data class PressFeedbackTarget(val scale: Float, val tint: Float)

internal fun pressFeedbackTarget(
    visible: Boolean,
    enabled: Boolean,
    reducedMotion: Boolean,
    dark: Boolean,
    feedback: ProtocolModifier.PressFeedback?,
): PressFeedbackTarget = PressFeedbackTarget(
    scale = if (enabled && visible && !reducedMotion) feedback?.scale ?: PRESS_SCALE else 1f,
    tint = if (enabled && visible) (feedback?.tintAlpha ?: if (dark) PRESS_TINT_DARK else PRESS_TINT_LIGHT).coerceIn(0f, 1f) else 0f,
)

internal fun pressReleaseDelay(elapsedMillis: Long): Long = (PRESS_MIN_MILLIS - elapsedMillis).coerceAtLeast(0)

internal fun Modifier.pressFeedback(
    source: MutableInteractionSource,
    enabled: Boolean,
    theme: ResolvedTheme,
    shape: Shape,
    ink: Color,
    feedback: ProtocolModifier.PressFeedback?,
): Modifier = composed {
    var visible by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source, enabled) {
        if (!enabled) {
            visible = false
        } else {
            var started = kotlin.time.TimeSource.Monotonic.markNow()
            var release: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        release?.cancel()
                        started = kotlin.time.TimeSource.Monotonic.markNow()
                        visible = true
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        release?.cancel()
                        release = launch {
                            val elapsed = started.elapsedNow().inWholeMilliseconds
                            delay(pressReleaseDelay(elapsed))
                            visible = false
                        }
                    }
                }
            }
        }
    }
    val state = pressFeedbackTarget(visible, enabled, dev.darkpyonix.composerust.ui.node.platformReducedMotion(), theme.dark, feedback)
    val target = state.scale.takeIf { it.isFinite() }?.coerceIn(0.5f, 1.5f) ?: 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (target == 1f) PRESS_OUT_MILLIS else PRESS_IN_MILLIS,
            easing = if (target == 1f) PRESS_OUT_EASING else PRESS_IN_EASING,
        ),
    )
    val tint = state.tint
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            drawContent()
            if (enabled && visible && tint > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), ink.copy(alpha = tint.coerceIn(0f, 1f)))
            }
        }
}

/**
 * The listener a node's size motion reports through: when a run reaches its target, the
 * size it settled at goes to the node's `OnMotionFinished` handler in dp.
 *
 * Called by Compose on the UI thread's frame clock, so the event is written on that same
 * call, with no queue in between. Made once for each handler and density, not on every
 * composition, so a node's chain does not change because its handler was asked again.
 */
internal fun motionFinishedListener(
    nodeId: Int,
    handlerId: Long,
    density: Density,
    dispatcher: EventDispatcher,
): (initialValue: IntSize, targetValue: IntSize) -> Unit = { _, target ->
    dispatcher.dispatch(
        HostEvent.MotionFinished(
            nodeId = nodeId,
            handlerId = handlerId,
            widthDp = target.width / density.density,
            heightDp = target.height / density.density,
        ),
    )
}

/**
 * A size rounded to the nearest whole dp, a half rounding up. The same rule the window's
 * own every-dp report uses, so a node and a window that are the same size say the same.
 */
private fun wholeDp(dp: Float): Int = floor(dp + 0.5f).toInt()

/**
 * What a node that asked for every whole dp change has measured and last sent.
 *
 * A layout pass can run more than once in a frame, and a drag runs one every frame. The
 * measurement is only written down where it happens; it is sent from the draw pass,
 * which runs once a frame, so several measurements in one frame are one event carrying
 * the last of them.
 */
internal class EveryDpReport {
    var widthDp = 0f
    var heightDp = 0f
    var measured = false
    private var sent = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastClass: WindowSizeClass? = null
    private var lastHeightClass: WindowHeightClass? = null

    fun flush(nodeId: Int, dispatcher: EventDispatcher) {
        if (!measured) return
        measured = false
        val width = wholeDp(widthDp)
        val height = wholeDp(heightDp)
        val sizeClass = windowSizeClassOf(widthDp)
        val heightClass = windowHeightClassOf(heightDp)
        // A class can change without the rounded size changing (600 dp to 599.9 dp), so
        // the classes are compared as well.
        if (sent && width == lastWidth && height == lastHeight &&
            sizeClass == lastClass && heightClass == lastHeightClass
        ) {
            return
        }
        sent = true
        lastWidth = width
        lastHeight = height
        lastClass = sizeClass
        lastHeightClass = heightClass
        dispatcher.dispatch(
            HostEvent.WindowSizeChanged(
                nodeId = nodeId,
                handlerId = 0,
                widthDp = widthDp,
                heightDp = heightDp,
                sizeClass = sizeClass,
                heightClass = heightClass,
                safeAreaTop = 0f,
                safeAreaLeading = 0f,
                safeAreaTrailing = 0f,
            ),
        )
    }
}

/**
 * Reports this node's size whenever it changes by a whole dp, as well as when a class
 * changes. The first measurement is always sent. The event carries the measured,
 * unrounded size, the one measured last in the frame.
 */
private fun Modifier.reportEveryDpTo(nodeId: Int, dispatcher: EventDispatcher): Modifier =
    composed {
        val density = LocalDensity.current
        val report = remember(nodeId) { EveryDpReport() }
        onSizeChanged { size ->
            report.widthDp = with(density) { size.width.toDp().value }
            report.heightDp = with(density) { size.height.toDp().value }
            report.measured = true
        }.drawWithContent {
            drawContent()
            report.flush(nodeId, dispatcher)
        }
    }

private const val HOVER_LAYER = 0.08f
private val FOCUS_RING_WIDTH = 2.dp

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
                        safeAreaTop = 0f,
                        safeAreaLeading = 0f,
                        safeAreaTrailing = 0f,
                    ),
                )
            }
        }
    }
