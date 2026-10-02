package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dioxus.compose.design.ChoicePresentation
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode
import dioxus.compose.ui.node.nodeTestTag

/** The test tag of whatever a dropdown opens over the page. */
fun dropdownPopupTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-options"

/**
 * One choice out of a list. Each child is one option.
 *
 * The selection lives here, in the Renderer, and the Host is told which position the user
 * landed on. Nothing about the way the list is shown crosses the boundary: Material drops a
 * menu under the field, Cupertino spins a wheel, Fluent opens a flyout, and the node that
 * produced all three is the same node.
 */
@Composable
internal fun HostDropdown(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val fromHost = (node.property(PropertyKind.SelectedIndex) as? PropertyValue.Integer)
        ?.value
        ?.toInt()
        ?: 0
    var selected by remember(node.id) { mutableIntStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { selected = fromHost }
    val enabled = node.pickerEnabled()
    val options = node.children.toList()
    val choose: (Int) -> Unit = { index ->
        if (index != selected && index in options.indices) {
            selected = index
            val handlerId = node.handler(PropertyKind.OnValueChange)
            if (handlerId != null) {
                dispatcher.dispatch(HostEvent.ValueChanged(node.id, handlerId, index.toDouble()))
            }
        }
    }
    val shown = selected.coerceIn(0, maxOf(0, options.lastIndex))

    when (theme.rules.pickers.choice) {
        ChoicePresentation.ExposedMenu -> OpeningList(
            node = node,
            options = options,
            shown = shown,
            enabled = enabled,
            theme = theme,
            table = table,
            dispatcher = dispatcher,
            modifier = modifier,
            // A menu under the field belongs to the field: it takes its width and sits
            // against its lower edge.
            role = ContainerRole.Menu,
            marker = "▾",
            onChoose = choose,
        )

        ChoicePresentation.ComboBox -> OpeningList(
            node = node,
            options = options,
            shown = shown,
            enabled = enabled,
            theme = theme,
            table = table,
            dispatcher = dispatcher,
            modifier = modifier,
            role = ContainerRole.Dialog,
            marker = "⌄",
            onChoose = choose,
        )

        // A wheel has no resting form and nothing to open: the options are always there and
        // the one in the middle is the choice.
        ChoicePresentation.Wheel -> Box(modifier) {
            ChildWheel(
                options = options,
                shown = shown,
                enabled = enabled,
                theme = theme,
                table = table,
                dispatcher = dispatcher,
                onChoose = choose,
            )
        }
    }
}

/**
 * A field that opens the options over the page.
 *
 * The difference between a menu and a flyout is the container role, which is what decides
 * the shadow, the corner and the stroke around it. Neither is described by the Host.
 */
@Composable
private fun OpeningList(
    node: Node,
    options: List<Int>,
    shown: Int,
    enabled: Boolean,
    theme: ResolvedTheme,
    table: NodeTable,
    dispatcher: EventDispatcher,
    modifier: Modifier,
    role: ContainerRole,
    marker: String,
    onChoose: (Int) -> Unit,
) {
    var open by remember(node.id) { mutableStateOf(false) }
    val style = theme.rules.container(role, theme)
    Box(modifier) {
        Box(
            Modifier
                .clip(style.shape)
                .background(theme.color(ColorRole.Surface), style.shape)
                .border(1.dp, theme.color(ColorRole.Outline), style.shape)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = theme.space(SpaceRole.Sm), vertical = 6.dp),
        ) {
            // The closed field shows the chosen option itself, drawn by the same node that
            // draws it in the list. An option is whatever the Host made it.
            options.getOrNull(shown)?.let { childId ->
                key(childId) { RenderNode(childId, table, dispatcher) }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = theme.space(SpaceRole.Xs))) {
            BasicText(
                text = marker,
                style = theme.pickerTextStyle(color = theme.color(ColorRole.OnSurfaceVariant)),
            )
        }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .testTag(dropdownPopupTestTag(node.id))
                        .clip(style.shape)
                        .background(style.container, style.shape)
                        .border(style.borderWidth, style.borderColor, style.shape)
                        .padding(vertical = theme.space(SpaceRole.Xs)),
                ) {
                    options.forEachIndexed { index, childId ->
                        key(childId) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (index == shown) {
                                            theme.color(ColorRole.SurfaceVariant)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable(enabled = enabled) {
                                        onChoose(index)
                                        open = false
                                    }
                                    .padding(
                                        horizontal = theme.space(SpaceRole.Sm),
                                        vertical = theme.space(SpaceRole.Xs),
                                    ),
                            ) {
                                RenderNode(childId, table, dispatcher)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The options as a wheel, with the chosen one held in the band across the middle. */
@Composable
private fun ChildWheel(
    options: List<Int>,
    shown: Int,
    enabled: Boolean,
    theme: ResolvedTheme,
    table: NodeTable,
    dispatcher: EventDispatcher,
    onChoose: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(shown) { state.scrollToItem(shown.coerceAtLeast(0)) }
    Box(
        Modifier.fillMaxWidth().height(theme.wheelRowHeight * WHEEL_VISIBLE_ROWS),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(theme.wheelRowHeight)
                .background(theme.color(ColorRole.SurfaceVariant)),
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                vertical = theme.wheelRowHeight * ((WHEEL_VISIBLE_ROWS - 1) / 2),
            ),
        ) {
            items(options.size) { index ->
                val childId = options[index]
                key(childId) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(theme.wheelRowHeight)
                            .clickable(enabled = enabled) { onChoose(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        RenderNode(childId, table, dispatcher)
                    }
                }
            }
        }
    }
}
