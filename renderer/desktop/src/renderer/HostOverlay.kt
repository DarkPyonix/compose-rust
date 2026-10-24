package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.textStyle
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import androidx.compose.runtime.CompositionLocalProvider

/** Whether an overlay is showing, and the way to change it without asking the Host. */
internal class OverlayOpen(val value: Boolean, val set: (Boolean) -> Unit)

/**
 * The open state of an overlay.
 *
 * It lives here, in the Renderer. The Host property seeds it and carries changes that came
 * from somewhere else, so opening and closing costs no boundary call and the enter and exit
 * never wait for an answer. The Host is told about a dismissal once, and decides whether to
 * honour it by sending the property back as false.
 */
@Composable
internal fun rememberOverlayOpen(node: Node): OverlayOpen {
    val fromHost = node.flag(PropertyKind.Open, default = false)
    var open by remember(node.id) { mutableStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { open = fromHost }
    return OverlayOpen(open) { open = it }
}

/** Tells the Host the user asked to close this overlay, if it asked to be told. */
internal fun dismiss(node: Node, dispatcher: EventDispatcher) {
    val handlerId = node.handler(PropertyKind.OnDismiss) ?: return
    dispatcher.dispatch(HostEvent.Clicked(node.id, handlerId))
}

/**
 * A modal. Its children are its content.
 *
 * The scrim, the placement and the corner come from the design system, so the same records
 * are a Material dialog, a HIG alert or a Fluent content dialog.
 */
@Composable
internal fun HostDialog(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = rememberOverlayOpen(node)
    if (!open.value) return
    val style = theme.rules.container(ContainerRole.Dialog, theme)
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true),
        onDismissRequest = {
            open.set(false)
            dismiss(node, dispatcher)
        },
    ) {
        Box(
            Modifier.fillMaxSize().background(style.scrim),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier.containerDecoration(node, style, theme, overlay = true)) {
                node.children.forEach { childId ->
                    key(childId) { RenderNode(childId, table, dispatcher) }
                }
            }
        }
    }
}

/**
 * A popup hanging off an anchor. Child 0 is the anchor and the rest are the entries.
 *
 * The Host never learns a screen coordinate: where the popup sits relative to its anchor is
 * the design system's placement rule. Choosing an entry is that entry's own click, so the
 * menu needs no event of its own.
 */
@Composable
internal fun HostMenu(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = rememberOverlayOpen(node)
    val style = theme.rules.container(ContainerRole.Menu, theme)
    val anchorId = node.children.firstOrNull()
    val entries = node.children.drop(1)
    Box(modifier) {
        if (anchorId != null) {
            key(anchorId) { RenderNode(anchorId, table, dispatcher) }
        }
        if (open.value) {
            Popup(
                alignment = Alignment.BottomStart,
                properties = PopupProperties(focusable = true),
                onDismissRequest = {
                    open.set(false)
                    dismiss(node, dispatcher)
                },
            ) {
                // The recorded page does not reach in here. A popup is its own platform
                // window, so a position measured against "the window" means a different
                // window on each side of this line, and the difference between the two is
                // not a distance: drawing the page by it puts a piece of the page
                // somewhere inside the menu. A menu that hangs past the window's edge has
                // nothing of ours behind it there anyway.
                CompositionLocalProvider(LocalGlassBackdrop provides null, LocalInAMenu provides true) {
                Column(
                    Modifier
                        .testTag(menuPopupTestTag(node.id))
                        .containerDecoration(node, style, theme, overlay = true),
                ) {
                    entries.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher) }
                    }
                }
                }
            }
        }
    }
}

/**
 * Whether what is being drawn is inside a menu's popup.
 *
 * A menu's entries are declared as buttons, because that is what they are to the
 * application: a thing with a name that does something when it is chosen. What they are to
 * the reader is a list, and a system that cuts every button into a capsule drew the list as
 * a stack of pills with room between them, which is a sheet of actions on a phone rather
 * than a menu on a desktop.
 */
val LocalInAMenu = compositionLocalOf { false }

/** Test tag of the popup half of a menu, which is its own window, not part of the anchor. */
fun menuPopupTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-popup"

/**
 * An explanation attached to its child.
 *
 * The text is also the child's accessibility description, so it can be reached without a
 * pointer. How long the pointer has to rest before the popup appears is a design system
 * rule, not something the Host sends.
 */
@Composable
internal fun HostTooltip(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val description = node.text(PropertyKind.Text)
    val style = theme.rules.container(ContainerRole.Tooltip, theme)
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var showing by remember(node.id) { mutableStateOf(false) }
    val delayMillis = theme.rules.motion.tooltipDelayMillis
    LaunchedEffect(hovered, delayMillis) {
        if (!hovered) {
            showing = false
            return@LaunchedEffect
        }
        delay(delayMillis.toLong())
        showing = true
    }
    Box(
        modifier
            .hoverable(interactions)
            .semantics { contentDescription = description },
    ) {
        node.children.forEach { childId -> key(childId) { RenderNode(childId, table, dispatcher) } }
        if (showing && description.isNotEmpty()) {
            Popup(alignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .testTag(tooltipPopupTestTag(node.id))
                        .containerDecoration(node, style, theme, overlay = true),
                ) {
                    BasicText(
                        text = description,
                        style = node.textStyle(theme, style.typeRole).copy(color = style.content),
                    )
                }
            }
        }
    }
}

/** Test tag of the tooltip popup, which is its own window, not part of what it explains. */
fun tooltipPopupTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-tooltip"
