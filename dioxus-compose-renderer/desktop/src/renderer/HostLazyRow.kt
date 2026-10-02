package dioxus.compose.foundation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode

/** Width a not-yet-materialised slot stands at until a real item has been measured. */
private val ESTIMATED_ITEM_WIDTH: Dp = 64.dp

/**
 * A windowed list on the horizontal axis.
 *
 * This is the same windowing protocol the vertical list uses, with nothing changed but the
 * axis: the Host declares `item_count`, the Renderer asks for the range it needs plus its
 * own read-ahead buffer, and the Host answers that range exactly. The buffer is decided
 * here for the same reason it is there, because the Renderer is the side that knows the
 * scroll position.
 */
@Composable
internal fun HostLazyRow(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    state: LazyListState = rememberLazyListState(),
) {
    val children = node.children
    val declaredCount =
        (node.property(PropertyKind.ItemCount) as? PropertyValue.Integer)?.value?.toInt()
    val itemCount = declaredCount ?: children.size
    val handlerId = node.handler(PropertyKind.OnRangeRequested)
    // The global index of `children[0]`, which is whatever was last asked for.
    var windowStart by remember(node.id) { mutableIntStateOf(0) }
    // A slot outside the window has to be as wide as a real item, or the viewport would
    // never fill and every one of `item_count` slots would be measured.
    var gapWidth by remember(node.id) { mutableStateOf(ESTIMATED_ITEM_WIDTH) }

    LazyRow(modifier = modifier, state = state) {
        items(
            count = itemCount,
            key = { index -> lazyItemKey(table, children, index - windowStart, index) },
        ) { index ->
            val childId = children.getOrNull(index - windowStart)
            if (childId == null) {
                Spacer(Modifier.fillMaxHeight().width(gapWidth))
            } else {
                RenderNode(childId, table, dispatcher)
            }
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(state, density) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.firstOrNull { item -> item.size > 0 }?.size ?: 0
        }
            .distinctUntilChanged()
            .collect { size -> if (size > 0) gapWidth = with(density) { size.toDp() } }
    }

    if (handlerId != null) {
        LaunchedEffect(state, handlerId, itemCount, dispatcher) {
            snapshotFlow {
                requestedRange(
                    firstVisible = state.firstVisibleItemIndex,
                    visibleCount = state.layoutInfo.visibleItemsInfo.size,
                    itemCount = itemCount,
                )
            }
                // A scroll that stays inside the window costs no boundary call.
                .distinctUntilChanged()
                .collect { (start, count) ->
                    if (count <= 0) return@collect
                    // One transaction, so no frame sees the new children at the old offset.
                    Snapshot.withMutableSnapshot {
                        windowStart = start
                        dispatcher.dispatch(
                            HostEvent.RangeRequested(node.id, handlerId, start, count),
                        )
                    }
                }
        }
    }
}
