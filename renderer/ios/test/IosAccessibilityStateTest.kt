package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
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

    private companion object {
        const val NODE = 1
        const val COLLAPSED = 1 shl 4
        const val HAS_POPUP = 1 shl 13
    }
}
