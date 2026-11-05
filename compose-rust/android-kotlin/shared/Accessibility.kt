package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.ui.node.Node

/** The two bits of one tri-state field in the `Semantics` state word. */
private const val FIELD_MASK = 0b11
private const val CHECKED_SHIFT = 0
private const val SELECTED_SHIFT = 2

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
}

/**
 * What the Host said this node is to assistive technology: the accessible name, and the
 * role and state of a `Semantics` modifier.
 *
 * It is applied here, outside the widget, because every widget receives this modifier as its
 * own, so the one place covers a container, a control and a node the Host only labelled. A
 * node that said nothing gets the same modifier back, so nothing changes for it.
 */
internal fun Modifier.accessibility(node: Node): Modifier {
    val name = node.text(PropertyKind.AccessibleName).takeIf { it.isNotEmpty() }
    val declared = node.modifiers.lastOrNull { it is ProtocolModifier.Semantics } as? ProtocolModifier.Semantics
    if (name == null && declared == null) return this
    val role = declared?.role?.toRole()
    val checked = declared?.state?.field(CHECKED_SHIFT)
    val selected = declared?.state?.field(SELECTED_SHIFT)
    return semantics {
        if (name != null) contentDescription = name
        if (role != null) this.role = role
        if (checked != null) toggleableState = ToggleableState(checked)
        if (selected != null) this.selected = selected
    }
}
