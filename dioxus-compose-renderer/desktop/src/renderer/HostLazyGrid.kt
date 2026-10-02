package dioxus.compose.foundation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * How tall a slot outside the window stands.
 *
 * A gap of no height would never fill the viewport, so the grid would measure every slot
 * it has and stop being windowed at all. The list keeps its own copy of this for the same
 * reason and refines it once a real item has been measured; a grid's rows are all the
 * same height, so the estimate is enough here.
 */
private val GRID_GAP_HEIGHT = 56.dp

/**
 * Widens a window of items to whole rows.
 *
 * The windowing protocol is the list's, unchanged: a range of items, and the Host
 * materialises exactly that range. A grid only ever lays out whole rows, so asking for
 * half of one leaves the Renderer holding a row it cannot fill and the Host wondering why
 * it was asked for three items when four fit across. Rounding here keeps the protocol one
 * protocol and puts the row arithmetic where the row count is known.
 */
internal fun rowAlignedRange(start: Int, count: Int, columns: Int, itemCount: Int): Pair<Int, Int> {
    if (columns <= 1 || count <= 0) return start to count
    val first = start - start % columns
    val end = start + count
    val rounded = ((end + columns - 1) / columns) * columns
    return first to (rounded.coerceAtMost(itemCount) - first).coerceAtLeast(0)
}

/**
 * How many columns the grid is actually using.
 *
 * Asked of the layout rather than of the declaration, because a grid told a minimum width
 * works its own count out from the width it was given, which is the whole point of saying
 * it that way. Nothing visible yet means nothing to divide into rows, and one column is
 * the answer that changes no arithmetic.
 */
internal fun columnsInUse(state: LazyGridState): Int =
    state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column + 1 } ?: 1

/**
 * A grid that only builds the rows it can show.
 *
 * Everything about the window is the list's: the same request, the same buffer, the same
 * refusal to ask again for a window whose items all measure nothing. What is added is the
 * rounding to rows and the two ways a screen may say how wide a column is.
 */
@Composable
internal fun HostLazyGrid(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    state: LazyGridState = rememberLazyGridState(),
) {
    val children = node.children
    val declaredCount =
        (node.property(PropertyKind.ItemCount) as? PropertyValue.Integer)?.value?.toInt()
    val itemCount = declaredCount ?: children.size
    val handlerId = node.handler(PropertyKind.OnRangeRequested)
    var windowStart by remember(node.id) { mutableIntStateOf(0) }

    // A count the screen insisted on, or a width below which a column may not fall and
    // the count is worked out from the room there is. A grid that said neither is a grid
    // of one column, which is a list, which is what it asked for.
    val fixed = (node.property(PropertyKind.Columns) as? PropertyValue.Integer)?.value?.toInt()
    val minimum = (node.property(PropertyKind.MinColumnWidth) as? PropertyValue.Float)?.value
    val cells = when {
        fixed != null && fixed > 0 -> GridCells.Fixed(fixed)
        minimum != null && minimum > 0 -> GridCells.Adaptive(minimum.dp)
        else -> GridCells.Fixed(1)
    }

    // The same reason a list is given a height: told nothing, a viewport shrinks to the
    // few rows that happen to be materialised and can never grow, because how many rows
    // it asks for is decided by how tall it already is.
    val sized =
        if (node.declaresOwnHeight(table)) modifier else Modifier.fillMaxHeight().then(modifier)

    LazyVerticalGrid(columns = cells, modifier = sized, state = state) {
        items(
            count = itemCount,
            key = { index -> lazyItemKey(table, children, index - windowStart, index) },
        ) { index ->
            val childId = children.getOrNull(index - windowStart)
            if (childId == null) {
                Spacer(Modifier.fillMaxWidth().height(GRID_GAP_HEIGHT))
            } else {
                RenderNode(childId, table, dispatcher)
            }
        }
    }

    if (handlerId != null) {
        LaunchedEffect(state, handlerId, itemCount, dispatcher) {
            snapshotFlow {
                val visible = state.layoutInfo.visibleItemsInfo
                if (visible.isEmpty() || visible.none { it.size.height > 0 }) {
                    null
                } else {
                    val (start, count) = requestedRange(
                        firstVisible = state.firstVisibleItemIndex,
                        visibleCount = visible.count { it.size.height > 0 },
                        itemCount = itemCount,
                    )
                    rowAlignedRange(start, count, columnsInUse(state), itemCount)
                }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (start, count) ->
                    if (count <= 0) return@collect
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
