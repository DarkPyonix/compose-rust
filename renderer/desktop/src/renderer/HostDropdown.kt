package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.darkpyonix.composerust.design.ChoicePresentation
import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.ui.cornerCurve
import dev.darkpyonix.composerust.ui.withCornerCurve

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
    // A bare select is a closed field by definition: it sits inside a box that already
    // drew the frame, so it has no wheel band of its own to draw either.
    val bare = node.flag(PropertyKind.Bare, default = false)
    val closedField = bare || node.flag(PropertyKind.Collapsible, default = false)

    when {
        closedField || theme.rules.pickers.choice == ChoicePresentation.ExposedMenu -> OpeningList(
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

        theme.rules.pickers.choice == ChoicePresentation.ComboBox -> OpeningList(
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
        else -> Box(modifier) {
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
    val bare = node.flag(PropertyKind.Bare, default = false)
    val bareInteractions = remember(node.id) { MutableInteractionSource() }
    val ownFill = node.setsOwnBackground()
    val ownLine = node.modifiers.any {
        it is ProtocolModifier.Border || it is ProtocolModifier.BorderEach
    }
    val ownShape = node.modifiers.any {
        it is ProtocolModifier.Shape ||
            it is ProtocolModifier.ShapeRole ||
            it is ProtocolModifier.CornerEach
    }
    val ownRoom = node.modifiers.any {
        it is ProtocolModifier.Padding ||
            it is ProtocolModifier.PaddingEach ||
            it is ProtocolModifier.PaddingRole
    }
    val fieldShape = if (ownShape) node.modifiers.resolvedShape(theme) else style.shape.withCornerCurve(node.modifiers.cornerCurve())
    Box(modifier) {
        Box(
            Modifier
                // A bare field clips only to a corner the node named itself.
                .let { if (bare && !ownShape) it else it.clip(fieldShape) }
                .let {
                    if (ownFill || bare) it else it.background(theme.color(ColorRole.Surface), fieldShape)
                }
                .let {
                    if (ownLine || ownFill || bare) {
                        it
                    } else {
                        it.border(1.dp, theme.color(ColorRole.Outline), fieldShape)
                    }
                }
                .let {
                    // A bare field draws no focus or press layer: the box around it owns
                    // the field's look in every state.
                    if (bare) {
                        it.clickable(
                            interactionSource = bareInteractions,
                            indication = null,
                            enabled = enabled,
                        ) { open = true }
                    } else {
                        it.clickable(enabled = enabled) { open = true }
                    }
                }
                .let {
                    if (ownRoom || bare) {
                        it
                    } else {
                        it.padding(horizontal = theme.space(SpaceRole.Sm), vertical = 6.dp)
                    }
                },
        ) {
            // The closed field shows the chosen option itself, drawn by the same node that
            // draws it in the list. An option is whatever the Host made it.
            options.getOrNull(shown)?.let { childId ->
                key(childId) { RenderNode(childId, table, dispatcher) }
            }
        }
        // On a bare field the marker takes no room of its own, so the field is as tall as
        // the option it shows and not as tall as the marker.
        Box(
            if (bare) {
                Modifier.matchParentSize().padding(end = theme.space(SpaceRole.Xs))
            } else {
                Modifier.align(Alignment.CenterEnd).padding(end = theme.space(SpaceRole.Xs))
            },
            contentAlignment = Alignment.CenterEnd,
        ) {
            ThemedText(
                text = marker,
                style = theme.pickerTextStyle(color = theme.color(ColorRole.OnSurfaceVariant)),
            )
        }
        if (open) {
            val popupShape = if (node.setsOwnShape()) {
                node.containerShape(style, theme)
            } else {
                theme.overlay?.popupCornerRadius?.takeIf { it > 0 }?.let { androidx.compose.foundation.shape.RoundedCornerShape(it.dp) } ?: style.shape
            }
            val popupElevation = if (node.setsOwnElevation()) {
                0.dp
            } else {
                theme.overlay?.popupElevation?.takeIf { it > 0 }?.let { it.dp } ?: style.elevation
            }
            val popupRaised = if (node.setsOwnElevation() || popupElevation.value <= 0f) {
                Modifier
            } else {
                theme.rules.elevation(Modifier, popupElevation, popupShape, theme)
            }
            val explicitPopupPad = theme.overlay?.popupPadding
            val popupPadding = if (node.setsOwnPadding()) {
                Modifier
            } else if (explicitPopupPad != null && explicitPopupPad > 0) {
                Modifier.padding(explicitPopupPad.dp)
            } else {
                Modifier.padding(vertical = theme.space(SpaceRole.Xs))
            }
            Popup(
                popupPositionProvider = BelowAnchorPositionProvider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                CompositionLocalProvider(LocalGlassBackdrop provides null, LocalInAMenu provides true) {
                    Column(
                        Modifier
                            .testTag(dropdownPopupTestTag(node.id))
                            .then(popupRaised)
                            .clip(popupShape)
                            .background(style.container, popupShape)
                            .border(style.borderWidth, style.borderColor, popupShape)
                            .then(popupPadding)
                            .menuWidth(),
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
