package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.rememberControlLook
import dev.darkpyonix.composerust.design.ToggleRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable

/**
 * A Checkbox, a RadioButton or a Switch.
 *
 * Only the node is read here. Which pixels appear belongs to
 * [dev.darkpyonix.composerust.design.ComponentRules.controlWidgets], which is why the same declaration
 * is a Material checkbox drawn by `androidx.compose.material3`, a round Cupertino one and a
 * stroked Fluent one.
 *
 * All three are controlled: the widget draws exactly what the Host sent and reports the
 * state the user asked for, so the value the Host holds and the control on screen cannot
 * drift apart.
 */
@Composable
internal fun HostToggle(
    role: ToggleRole,
    node: Node,
    modifier: Modifier,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val checked = node.flag(PropertyKind.Checked, default = false)
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val handlerId = node.handler(PropertyKind.OnValueChange)
    val group = if (role == ToggleRole.RadioButton) LocalRadioGroup.current else null
    val joined = if (group == null) modifier else {
        DisposableEffect(group, node.id) { onDispose { group.forget(node.id) } }
        modifier.inRadioGroup(group, node.id)
    }

    theme.rules.controlWidgets.Toggle(
        role = role,
        checked = checked,
        enabled = enabled,
        // Off is 0.0 and on is 1.0. The Host decides whether to honour the request.
        onChange = handlerId?.let { id ->
            { requested: Boolean ->
                dispatcher.dispatch(
                    HostEvent.ValueChanged(node.id, id, if (requested) 1.0 else 0.0),
                )
            }
        },
        modifier = joined,
        theme = theme,
        look = rememberControlLook(node, theme),
    )
}

/**
 * The radio group a radio button is drawn inside, if any: the nearest `Column` or `Row`
 * that said it is one.
 */
internal val LocalRadioGroup = staticCompositionLocalOf<RadioGroup?> { null }

/**
 * A `Column` or a `Row` that is a group of radio buttons.
 *
 * The group is one stop for Tab, the way a set of radio buttons is on every desktop: the
 * selected button takes focus, or the first when none is selected, and the others are
 * skipped. Inside the group the arrow keys move focus to the next or the previous button,
 * wrapping at the ends, and report that button's change once, as a click on it would.
 * Which button is selected stays the Host's: the move asks, the Host answers by sending
 * `Checked`.
 *
 * The buttons are found by walking the group's descendants in order rather than by each
 * one registering, so the order is always the order on screen and a button that moved
 * inside the group needs nothing told. A group inside the group keeps its own buttons.
 */
internal class RadioGroup(
    private val groupId: Int,
    private val table: NodeTable,
    private val dispatcher: EventDispatcher,
) {
    private val requesters = HashMap<Int, FocusRequester>()

    /**
     * The button that has focus, or the one focus is being moved to, or null while focus
     * is outside the group. Read where focus is searched, so it is a plain field: nothing
     * has to recompose when it changes.
     */
    private var focused: Int? = null

    fun requester(nodeId: Int): FocusRequester = requesters.getOrPut(nodeId) { FocusRequester() }

    fun forget(nodeId: Int) {
        requesters.remove(nodeId)
        if (focused == nodeId) focused = null
    }

    fun focusChanged(nodeId: Int, isFocused: Boolean) {
        if (isFocused) {
            focused = nodeId
        } else if (focused == nodeId) {
            focused = null
        }
    }

    /** The radio buttons in the group, in the order they are drawn. */
    private fun members(): List<Int> {
        val found = ArrayList<Int>()
        fun walk(id: Int, depth: Int) {
            if (depth > MAX_GROUP_DEPTH) return
            val node = table.node(id) ?: return
            if (node.widget == WidgetKind.RadioButton) {
                found += id
                return
            }
            // A group inside this one answers for its own buttons.
            if (id != groupId && node.flag(PropertyKind.RadioGroup, default = false)) return
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(groupId, 0)
        return found
    }

    private fun usable(nodeId: Int): Boolean {
        val node = table.node(nodeId) ?: return false
        return node.flag(PropertyKind.Enabled, default = true) && node.handler(PropertyKind.OnValueChange) != null
    }

    /** The one button Tab stops at: the focused one, else the selected one, else the first. */
    fun stop(): Int? {
        val members = members()
        focused?.takeIf { it in members }?.let { return it }
        return members.firstOrNull { id ->
            usable(id) && table.node(id)?.flag(PropertyKind.Checked, default = false) == true
        } ?: members.firstOrNull { usable(it) }
    }

    /**
     * Moves focus `by` buttons along the group and reports the button it lands on. False
     * when focus is not on a button of this group, so the key goes on to whoever else
     * wants it.
     */
    fun move(by: Int): Boolean {
        val current = focused ?: return false
        val members = members()
        val from = members.indexOf(current)
        if (from < 0) return false
        var index = from
        repeat(members.size) {
            index = ((index + by) % members.size + members.size) % members.size
            if (usable(members[index])) {
                val target = members[index]
                if (target == current) return true
                // Set before asking, because the target can only take focus once it is
                // the group's stop.
                focused = target
                requester(target).requestFocus()
                table.node(target)?.handler(PropertyKind.OnValueChange)?.let { handler ->
                    dispatcher.dispatch(HostEvent.ValueChanged(target, handler, 1.0))
                }
                return true
            }
        }
        return true
    }

    private companion object {
        /** Deeper than any group of buttons is laid out, and a guard against a cycle. */
        const val MAX_GROUP_DEPTH = 64
    }
}

/** Joins a radio button to its group: one Tab stop between them, and focus they can be moved to. */
private fun Modifier.inRadioGroup(group: RadioGroup, nodeId: Int): Modifier = this
    .focusProperties { canFocus = group.stop() == nodeId }
    .focusRequester(group.requester(nodeId))
    .onFocusChanged { group.focusChanged(nodeId, it.isFocused) }

/**
 * Draws a `Column` or a `Row` as a radio group when it says it is one, and as itself when
 * it does not. `content` lays the layout out with the modifier it is given.
 */
@Composable
internal fun RadioGroupOrLayout(
    node: Node,
    table: NodeTable,
    dispatcher: EventDispatcher,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (!node.flag(PropertyKind.RadioGroup, default = false)) {
        content(modifier)
        return
    }
    val group = remember(node.id, table, dispatcher) { RadioGroup(node.id, table, dispatcher) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Assistive technology hears a radio group, and the arrows move along it. Left and
    // right follow the reading direction.
    val grouped = modifier
        .selectableGroup()
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionDown -> group.move(1)
                Key.DirectionUp -> group.move(-1)
                Key.DirectionRight -> group.move(if (rtl) -1 else 1)
                Key.DirectionLeft -> group.move(if (rtl) 1 else -1)
                else -> false
            }
        }
    CompositionLocalProvider(LocalRadioGroup provides group) {
        content(grouped)
    }
}
