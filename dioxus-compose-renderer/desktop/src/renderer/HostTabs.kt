package dioxus.compose.foundation

import dioxus.compose.ui.paintProp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.TabsStyle
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode

/** How wide the mark under a tab is where the design system draws a short bar. */
private val SHORT_INDICATOR_WIDTH = 24.dp

/**
 * A row of tabs. Each child is one tab.
 *
 * The selection is the Renderer's state: the Host seeds it and can move it, but tapping a
 * tab changes it here and then reports it as that tab's own click. Switching tabs is
 * therefore one event, and the strip is not rebuilt by the Host to show the new selection.
 *
 * What marks the selection is the design system's rule: an underline across the tab, a
 * filled segment, or a short bar under the label.
 */
@Composable
internal fun HostTabs(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.tabs(theme)
    // The design system decides what the selected tab looks like unless the application
    // named a colour. A unified one does, because its reference may have no accent in
    // the strip at all and asking the active system puts one there.
    val named = node.paintProp(PropertyKind.Color)?.let { theme.color(it) }
    val fromHost = (node.property(PropertyKind.SelectedIndex) as? PropertyValue.Integer)
        ?.value
        ?.toInt()
        ?: 0
    var selected by remember(node.id) { mutableIntStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { selected = fromHost }

    Row(
        modifier
            .clip(style.shape)
            .background(style.container, style.shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        node.children.forEachIndexed { index, childId ->
            key(childId) {
                Tab(
                    index = index,
                    childId = childId,
                    selected = index == selected,
                    named = named,
                    style = style,
                    table = table,
                    dispatcher = dispatcher,
                    modifier = Modifier.weight(1f),
                    onSelect = {
                        selected = index
                        val handlerId = table.node(childId)?.handler(PropertyKind.OnClick)
                        if (handlerId != null) {
                            dispatcher.dispatch(HostEvent.Clicked(childId, handlerId))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Tab(
    index: Int,
    childId: Int,
    selected: Boolean,
    named: Color?,
    style: TabsStyle,
    table: NodeTable,
    dispatcher: EventDispatcher,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    // Which shape carries the selection is the design system's choice: Material draws a
    // bar under the tab, a segmented control fills the segment itself, and each leaves the
    // other's slot empty. So a named colour goes to whichever one is drawn, and naming one
    // says the same thing under every system rather than colouring nothing under half of
    // them.
    val marksWithIndicator = style.indicatorHeight.value > 0f
    val selectedFill = if (marksWithIndicator) {
        style.selectedContainer
    } else {
        named ?: style.selectedContainer
    }
    val container = if (selected) selectedFill else Color.Transparent
    Column(
        modifier
            .clickable(role = Role.Tab, onClick = onSelect)
            .clip(style.selectedShape)
            .background(container, style.selectedShape)
            .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RenderNode(childId, table, dispatcher)
        if (marksWithIndicator) {
            // The mark keeps its height when the tab is not selected, so selecting a tab
            // moves a colour rather than resizing the strip.
            val width = if (style.indicatorFillsTab) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(SHORT_INDICATOR_WIDTH)
            }
            Box(
                width
                    .height(style.indicatorHeight)
                    .clip(style.indicatorShape)
                    .background(
                        if (selected) named ?: style.indicator else Color.Transparent,
                    ),
            )
        }
    }
}
