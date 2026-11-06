package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node

/** The two bits of one tri-state field in the `Semantics` state word. */
private const val FIELD_MASK = 0b11
private const val CHECKED_SHIFT = 0
private const val SELECTED_SHIFT = 2
private const val EXPANDED_SHIFT = 4
private const val DISABLED_SHIFT = 6
private const val FOCUSED_SHIFT = 8
private const val HIDDEN_BIT = 1 shl 10
private const val CLICK_ACTION_BIT = 1 shl 11

/** A field of the state word: unset, off or on. */
private fun Int.field(shift: Int): Boolean? = when ((this shr shift) and FIELD_MASK) {
    1 -> false
    2 -> true
    else -> null
}

private fun SemanticsRole.toRole(): Role? = when (this) {
    SemanticsRole.None -> null
    SemanticsRole.Button -> Role.Button
    SemanticsRole.Checkbox -> Role.Checkbox
    SemanticsRole.Switch -> Role.Switch
    SemanticsRole.RadioButton -> Role.RadioButton
    SemanticsRole.Tab -> Role.Tab
    SemanticsRole.Image -> Role.Image
    SemanticsRole.DropdownList -> Role.DropdownList
    else -> null
}

/** The word a screen reader gets for a role Compose's `Role` cannot express. */
private fun SemanticsRole.word(): String? = when (this) {
    SemanticsRole.TabList -> "tablist"
    SemanticsRole.Tree -> "tree"
    SemanticsRole.TreeItem -> "treeitem"
    SemanticsRole.Menu -> "menu"
    SemanticsRole.MenuItem -> "menuitem"
    SemanticsRole.TextBox -> "textbox"
    SemanticsRole.Navigation -> "navigation"
    SemanticsRole.List -> "list"
    SemanticsRole.ListItem -> "listitem"
    SemanticsRole.Group -> "group"
    else -> null
}

/**
 * What the Host said this node is to assistive technology: the accessible name, and the
 * role and state of a `Semantics` modifier.
 *
 * It is applied here, outside the widget, because every widget receives this modifier as its
 * own, so the one place covers a container, a control and a node the Host only labelled. A
 * node that said nothing gets the same modifier back, so nothing changes for it.
 */
internal fun Modifier.accessibility(
    node: Node,
    dispatcher: EventDispatcher? = null,
    clickHandlerId: Long? = null,
): Modifier {
    val name = node.text(PropertyKind.AccessibleName).takeIf { it.isNotEmpty() }
    val declared = node.modifiers.lastOrNull { it is ProtocolModifier.Semantics } as? ProtocolModifier.Semantics
    if (name == null && declared == null) return this
    val state = declared?.state ?: 0
    val declaredRole = declared?.role
    val role = declaredRole?.toRole()
    val checked = state.field(CHECKED_SHIFT)
    val selected = state.field(SELECTED_SHIFT)
    val expanded = state.field(EXPANDED_SHIFT)
    val isDisabled = state.field(DISABLED_SHIFT)
    val focused = state.field(FOCUSED_SHIFT)
    val hidden = state and HIDDEN_BIT != 0
    val isHeading = declaredRole == SemanticsRole.Heading
    val description = listOfNotNull(
        declaredRole?.word(),
        expanded?.let { if (it) "expanded" else "collapsed" },
    ).joinToString(", ").takeIf { it.isNotEmpty() }
    // A disabled node is not pressed, whichever way it was disabled: by this modifier or by
    // the node's own enabled property that the ordinary click path honours.
    val pressable = isDisabled != true && node.flag(PropertyKind.Enabled, default = true)
    val handler = clickHandlerId.takeIf { pressable && state and CLICK_ACTION_BIT != 0 }
    return semantics {
        if (name != null) contentDescription = name
        if (role != null) this.role = role
        if (isHeading) heading()
        if (description != null) stateDescription = description
        if (checked != null) toggleableState = ToggleableState(checked)
        if (selected != null) this.selected = selected
        if (isDisabled == true) disabled()
        if (focused != null) this.focused = focused
        if (hidden) hideFromAccessibility()
        if (handler != null && dispatcher != null) {
            onClick(label = null) {
                dispatcher.dispatch(HostEvent.Clicked(node.id, handler))
                true
            }
        }
    }
}
