package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.detectHostPlatform
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node

/** The two bits of one tri-state field in the `Semantics` state word. */
private const val FIELD_MASK = 0b11
private const val CHECKED_SHIFT = 0

/** The checked field's fourth value: the node is mixed, neither checked nor unchecked. */
private const val CHECKED_MIXED = 3
private const val SELECTED_SHIFT = 2
private const val EXPANDED_SHIFT = 4
private const val DISABLED_SHIFT = 6
private const val FOCUSED_SHIFT = 8
private const val HIDDEN_BIT = 1 shl 10
private const val CLICK_ACTION_BIT = 1 shl 11
private const val FOCUS_ACTION_BIT = 1 shl 12
private const val HAS_POPUP_BIT = 1 shl 13

/** Every bit the state word of a `Semantics` modifier may set. */
internal const val SEMANTICS_STATE_MASK = (1 shl 14) - 1

/**
 * Whether the Host asked for this node to offer a focus action to assistive technology.
 *
 * Asked before the node's modifier chain is built, because a node that offers the action
 * has to be able to take focus before anyone uses it.
 */
internal fun Node.offersFocusAction(): Boolean {
    val declared = modifiers.lastOrNull { it is ProtocolModifier.Semantics } as? ProtocolModifier.Semantics
    return declared != null && declared.state and FOCUS_ACTION_BIT != 0
}

/**
 * Whether this node is the chosen one among its siblings: a menu row the Host marked
 * checked, or a node whose `Semantics` modifier says it is selected.
 */
internal fun Node.isDeclaredSelected(): Boolean {
    if (flag(PropertyKind.Checked, default = false)) return true
    val declared = modifiers.lastOrNull { it is ProtocolModifier.Semantics } as? ProtocolModifier.Semantics
    return declared != null && declared.state.field(SELECTED_SHIFT) == true
}

/** The role the Host declared on this node's `Semantics` modifier, or null when none was set. */
internal fun Node.declaredSemanticsRole(): SemanticsRole? {
    val declared = modifiers.lastOrNull { it is ProtocolModifier.Semantics } as? ProtocolModifier.Semantics
    return declared?.role?.takeIf { it != SemanticsRole.None }
}

/** The checked field of the state word: unset, off, on or mixed. */
private fun Int.checkedField(): ToggleableState? = when ((this shr CHECKED_SHIFT) and FIELD_MASK) {
    1 -> ToggleableState.Off
    2 -> ToggleableState.On
    CHECKED_MIXED -> ToggleableState.Indeterminate
    else -> null
}

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
 * Whether a node with this role holds other nodes a reader visits one by one: a menu and its
 * items, a list and its rows. Such a node is a group to walk into, never a single piece of
 * text that stands for everything inside it.
 */
internal fun SemanticsRole.isContainer(): Boolean = when (this) {
    SemanticsRole.Menu, SemanticsRole.List, SemanticsRole.Group,
    SemanticsRole.TabList, SemanticsRole.Tree, SemanticsRole.Navigation -> true
    else -> false
}

/** Whether a reader is told how many items a container with this role holds, as a list is. */
private fun SemanticsRole.countsItems(): Boolean = when (this) {
    SemanticsRole.Menu, SemanticsRole.List, SemanticsRole.TabList, SemanticsRole.Tree -> true
    else -> false
}

/**
 * The role the Host declared for a node, kept beside Compose's own [Role] because most of
 * them have no Compose name. A window that can give a reader the platform's own word for the
 * role (a tab group, an outline, a menu) reads it from here.
 *
 * Never merged into a parent: a list item inside a pressable row is still the item, and the
 * row does not become a list item because it holds one.
 */
internal val DeclaredRole = SemanticsPropertyKey<SemanticsRole>(
    name = "ComposeRustDeclaredRole",
    mergePolicy = { parent, _ -> parent },
)

/** Whether the Host declared the node open (true) or closed (false). Unset says nothing. */
internal val DeclaredExpanded = SemanticsPropertyKey<Boolean>(
    name = "ComposeRustDeclaredExpanded",
    mergePolicy = { parent, _ -> parent },
)

/** Whether the Host declared that the node opens a popup. */
internal val DeclaredHasPopup = SemanticsPropertyKey<Boolean>(
    name = "ComposeRustDeclaredHasPopup",
    mergePolicy = { parent, _ -> parent },
)

/**
 * What a node's state description says about its declared [role], [expanded] state and
 * whether it [hasPopup], for a reader that has no other way to hear them. A window that names
 * them natively leaves this text out, so the reader does not hear the role twice.
 */
internal fun declaredStateDescription(
    role: SemanticsRole?,
    expanded: Boolean?,
    hasPopup: Boolean = false,
): String? = listOfNotNull(
    role?.word(),
    expanded?.let { if (it) "expanded" else "collapsed" },
    if (hasPopup) "has popup" else null,
).joinToString(", ").takeIf { it.isNotEmpty() }

/**
 * What the Host said this node is to assistive technology: the accessible name, and the
 * role and state of a `Semantics` modifier.
 *
 * It is applied here, outside the widget, because every widget receives this modifier as its
 * own, so the one place covers a container, a control and a node the Host only labelled. A
 * node that said nothing gets the same modifier back, so nothing changes for it.
 *
 * [onFocusAction] is what the focus action does. The caller passes the same request a focus
 * command from the Host makes, so the two cannot drift apart. It returns whether the request
 * was taken.
 */
internal fun Modifier.accessibility(
    node: Node,
    dispatcher: EventDispatcher? = null,
    clickHandlerId: Long? = null,
    onFocusAction: (() -> Boolean)? = null,
    platform: HostPlatform = detectHostPlatform(),
): Modifier {
    val name = node.text(PropertyKind.AccessibleName).takeIf { it.isNotEmpty() }
    val declared = node.modifiers.lastOrNull { it is ProtocolModifier.Semantics } as? ProtocolModifier.Semantics
    val state = declared?.state ?: 0
    val checked = state.checkedField()
    // UIKit exposes the state description as a value. A bare indeterminate toggle gets no
    // value from Compose's iOS bridge, so this case needs its own description.
    val describeMixed = platform == HostPlatform.Ios && when (checked) {
        ToggleableState.Indeterminate -> true
        null -> node.widget == WidgetKind.Checkbox && node.flag(PropertyKind.Indeterminate, default = false)
        else -> false
    }
    if (name == null && declared == null && !describeMixed) return this
    val declaredRole = declared?.role
    val role = declaredRole?.toRole()
    val selected = state.field(SELECTED_SHIFT)
    val expanded = state.field(EXPANDED_SHIFT)
    val isDisabled = state.field(DISABLED_SHIFT)
    val focused = state.field(FOCUSED_SHIFT)
    val hidden = state and HIDDEN_BIT != 0
    val hasPopup = state and HAS_POPUP_BIT != 0
    val isHeading = declaredRole == SemanticsRole.Heading
    val isTextBox = declaredRole == SemanticsRole.TextBox
    val focusAction = onFocusAction.takeIf { state and FOCUS_ACTION_BIT != 0 }
    val declaredDescription = declaredStateDescription(declaredRole, expanded, hasPopup)
    val description = if (describeMixed) {
        declaredDescription?.let { "$it, mixed" } ?: "mixed"
    } else {
        declaredDescription
    }
    // A disabled node is not pressed, whichever way it was disabled: by this modifier or by
    // the node's own enabled property that the ordinary click path honours.
    val pressable = isDisabled != true && node.flag(PropertyKind.Enabled, default = true)
    val handler = clickHandlerId.takeIf { pressable && state and CLICK_ACTION_BIT != 0 }
    val rows = node.children.size
    return semantics {
        if (name != null) contentDescription = name
        if (role != null) this.role = role
        if (isHeading) heading()
        if (isTextBox) isEditable = true
        if (description != null) stateDescription = description
        if (declaredRole != null && declaredRole != SemanticsRole.None) this[DeclaredRole] = declaredRole
        if (expanded != null) this[DeclaredExpanded] = expanded
        if (hasPopup) this[DeclaredHasPopup] = true
        if (checked != null) toggleableState = checked
        if (selected != null) this.selected = selected
        if (isDisabled == true) disabled()
        if (focused != null) this.focused = focused
        if (hidden) hideFromAccessibility()
        // A container is a group the reader walks into, the way a popover menu is, so its
        // rows stay nodes of their own. Nothing here merges them. A platform with a list or
        // a collection of its own (TalkBack's "list, 3 items") is told how many rows there are.
        if (declaredRole != null && declaredRole.isContainer()) {
            isTraversalGroup = true
            if (declaredRole.countsItems()) collectionInfo = CollectionInfo(rowCount = rows, columnCount = 1)
        }
        if (handler != null && dispatcher != null) {
            onClick(label = null) {
                dispatcher.dispatch(HostEvent.Clicked(node.id, handler))
                true
            }
        }
        if (focusAction != null) requestFocus(label = null) { focusAction() }
    }
}
