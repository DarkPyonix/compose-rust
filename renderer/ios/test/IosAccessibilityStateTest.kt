package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.NodeTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The state a Host declares on a node, as the iOS renderer builds it.
 *
 * UIKit has no attribute that says a control opens a popup, so the iOS renderer says it in
 * the node's state description, which Compose hands to VoiceOver as the element's value.
 * That hand-over is Compose's; what is checked here is that the iOS build of the shared
 * interpreter puts the words on the node.
 */
class IosAccessibilityStateTest {
    private fun configOf(role: SemanticsRole, state: Int): SemanticsConfiguration {
        val table = NodeTable()
        table.apply(Mutation.Create(NODE, WidgetKind.Box))
        table.apply(Mutation.SetModifier(NODE, 0, ProtocolModifier.Semantics(role, state)))
        return configOf(table)
    }

    private fun configOf(table: NodeTable): SemanticsConfiguration {
        val modifier = Modifier.accessibility(assertNotNull(table.node(NODE)))
        val semantics = modifier.foldIn<SemanticsModifier?>(null) { found, element -> found ?: element as? SemanticsModifier }
        return assertNotNull(semantics, "the node got no semantics").semanticsConfiguration
    }

    @Test
    fun fr71_has_popup_reaches_the_ios_state_description() {
        assertEquals(
            "has popup",
            configOf(SemanticsRole.Button, HAS_POPUP).getOrNull(SemanticsProperties.StateDescription),
        )
        assertEquals(
            "menu, collapsed, has popup",
            configOf(SemanticsRole.Menu, COLLAPSED or HAS_POPUP).getOrNull(SemanticsProperties.StateDescription),
        )
    }

    @Test
    fun fr71_a_node_without_has_popup_says_nothing_of_a_popup() {
        assertEquals(null, configOf(SemanticsRole.Button, 0).getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun fr68_mixed_checkbox_reaches_the_ios_accessibility_value() {
        val mixed = configOf(SemanticsRole.Checkbox, MIXED)
        assertEquals(ToggleableState.Indeterminate, mixed.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("mixed", mixed.getOrNull(SemanticsProperties.StateDescription))
        val checked = configOf(SemanticsRole.Checkbox, CHECKED)
        assertEquals(ToggleableState.On, checked.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals(null, checked.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun fr68_ios_checkbox_mixed_description_clears_when_the_host_removes_indeterminate() {
        val table = NodeTable()
        table.apply(Mutation.Create(NODE, WidgetKind.Checkbox))
        table.apply(Mutation.SetProp(NODE, PropertyKind.AccessibleName, PropertyValue.Text("Stage file")))
        table.apply(Mutation.SetProp(NODE, PropertyKind.Indeterminate, PropertyValue.Bool(true)))

        val mixed = configOf(table)
        assertEquals("mixed", mixed.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("Stage file"), mixed.getOrNull(SemanticsProperties.ContentDescription))

        table.apply(Mutation.SetProp(NODE, PropertyKind.Indeterminate, PropertyValue.None))
        val unchecked = configOf(table)
        assertEquals(null, unchecked.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("Stage file"), unchecked.getOrNull(SemanticsProperties.ContentDescription))
    }

    private companion object {
        const val NODE = 1
        const val COLLAPSED = 1 shl 4
        const val HAS_POPUP = 1 shl 13
        const val CHECKED = 2
        const val MIXED = 3
    }
}
