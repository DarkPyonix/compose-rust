package dioxus.compose.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import dioxus.compose.protocol.WindowSizeClass
import dioxus.compose.runtime.windowSizeClassOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.glassLift
import dioxus.compose.design.glassSurface
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.TableError

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
        }
    }
}

/**
 * Says that a paint named a brush that is not registered, and paints nothing.
 *
 * Once per node and per id, the same way a missing picture is reported. The node keeps
 * its place in the layout: a surface that vanished would take its children with it.
 */
@Composable
private fun Modifier.reportUnknownBrush(
    nodeId: Int,
    paint: dioxus.compose.protocol.Paint,
    dispatcher: EventDispatcher,
): Modifier {
    val assetId = (paint as? dioxus.compose.protocol.Paint.Asset)?.assetId ?: return this
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
            else -> Unit
        }
    }
    return RectangleShape
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
        val reported = remember(nodeId) { arrayOfNulls<WindowSizeClass>(1) }
        onSizeChanged { size ->
            val widthDp = with(density) { size.width.toDp().value }
            val heightDp = with(density) { size.height.toDp().value }
            val sizeClass = windowSizeClassOf(widthDp)
            if (reported[0] != sizeClass) {
                reported[0] = sizeClass
                dispatcher.dispatch(
                    HostEvent.WindowSizeChanged(
                        nodeId = nodeId,
                        handlerId = 0,
                        widthDp = widthDp,
                        heightDp = heightDp,
                        sizeClass = sizeClass,
                    ),
                )
            }
        }
    }
