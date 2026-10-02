package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.SheetEdge
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.runtime.LocalWindowSizeClass
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode
import dioxus.compose.ui.node.nodeTestTag
import kotlin.math.roundToInt

/** How far a sheet has to be pulled towards its edge before letting go closes it. */
private val DISMISS_TRAVEL = 96.dp

/** The grab handle's size, where the design system draws one. */
private val HANDLE_LENGTH = 32.dp
private val HANDLE_THICKNESS = 4.dp

/**
 * A temporary surface that comes in from an edge of the window.
 *
 * On the wire it is a `Dialog`: an `Open` property that seeds this side's own state, and one
 * `OnDismiss` when the user asks to close it. Which edge it comes from is not on the wire at
 * all, because the Renderer is the side that measured the window.
 *
 * **The drag does not cross the boundary.** While the sheet is being pulled about, the Host
 * hears nothing: the travel is state here, and only a drag that ends past the threshold
 * becomes one dismissal. A Host that heard every frame of a drag would be running the
 * VirtualDom for an animation it cannot see.
 */
@Composable
internal fun HostSheet(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = rememberOverlayOpen(node)
    if (!open.value) return
    val style = theme.rules.sheet(LocalWindowSizeClass.current, theme)
    val bottom = style.edge == SheetEdge.Bottom
    val threshold = with(LocalDensity.current) { DISMISS_TRAVEL.toPx() }
    var travel by remember(node.id) { mutableFloatStateOf(0f) }
    val close = {
        travel = 0f
        open.set(false)
        dismiss(node, dispatcher)
    }

    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true),
        onDismissRequest = close,
    ) {
        Box(
            Modifier
                .testTag(sheetScrimTestTag(node.id))
                .fillMaxSize()
                .background(style.scrim)
                // Pressing what the sheet covers is the other way of asking to close it,
                // and it gets the same answer: one dismissal. A raw tap detector rather
                // than `clickable`, because a scrim is not a button and saying it is one
                // puts it in the accessibility tree and merges everything under it.
                .pointerInput(Unit) { detectTapGestures { close() } },
            contentAlignment = if (bottom) Alignment.BottomCenter else Alignment.CenterEnd,
        ) {
            val sized = if (bottom) {
                Modifier.fillMaxWidth().fillMaxHeight(style.heightFraction)
            } else {
                Modifier.fillMaxHeight().fillMaxWidth(style.widthFraction)
            }
            val outline = if (style.borderWidth.value > 0f) {
                Modifier.border(style.borderWidth, style.borderColor, style.shape)
            } else {
                Modifier
            }
            // Two boxes rather than one: the outer is the sheet itself, which is what is
            // dragged and what the design system sized, and the inner carries whatever
            // Modifiers the Host put on the node, which belong inside its surface.
            Box(
                Modifier
                    .testTag(sheetTestTag(node.id))
                    .then(sized)
                    .offset {
                        if (bottom) {
                            IntOffset(0, travel.roundToInt())
                        } else {
                            IntOffset(travel.roundToInt(), 0)
                        }
                    }
                    .clip(style.shape)
                    .background(style.container)
                    .then(outline)
                    .draggable(
                        orientation = if (bottom) Orientation.Vertical else Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // Only travel towards the edge counts. Pulling the other way
                            // would drag the sheet off the edge it came from.
                            travel = (travel + delta).coerceAtLeast(0f)
                        },
                        onDragStopped = { if (travel >= threshold) close() else travel = 0f },
                    )
                    // A press inside the sheet is not a press on what it covers, so it is
                    // swallowed here rather than reaching the scrim behind.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier.fillMaxWidth().padding(style.padding)) {
                    style.handle?.let { handle ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .width(if (bottom) HANDLE_LENGTH else HANDLE_THICKNESS)
                                    .height(if (bottom) HANDLE_THICKNESS else HANDLE_LENGTH)
                                    .clip(theme.shape(ShapeRole.Full))
                                    .background(handle),
                            )
                        }
                    }
                    node.children.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher) }
                    }
                }
            }
        }
    }
}

/** Test tag of the sheet's own surface, which is its own window, not part of the tree. */
fun sheetTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet"

/** Test tag of what the sheet covers, which is also the area it is measured against. */
fun sheetScrimTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-scrim"
