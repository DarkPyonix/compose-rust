package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Button type_role and font_weight reach child Text nodes unless the child sets its own.
 */
@OptIn(ExperimentalTestApi::class)
class ButtonChildTextStyleTest {
    @Test
    fun fr66_button_type_role_and_weight_reach_child_text() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Column),
            // Button with TypeRole.Display (tag 1) and FontWeight 700, child Text sets neither.
            Mutation.Create(2, WidgetKind.Button),
            Mutation.SetProp(2, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Display.ordinal.toLong() + 1L)),
            Mutation.SetProp(2, PropertyKind.FontWeight, PropertyValue.Integer(700L)),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Inherited")),
            Mutation.Insert(2, 3, 0),
            // Same Button, but child Text explicitly sets TypeRole.Caption (tag 7).
            Mutation.Create(4, WidgetKind.Button),
            Mutation.SetProp(4, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Display.ordinal.toLong() + 1L)),
            Mutation.SetProp(4, PropertyKind.FontWeight, PropertyValue.Integer(700L)),
            Mutation.Create(5, WidgetKind.Text),
            Mutation.SetProp(5, PropertyKind.Text, PropertyValue.Text("Own")),
            Mutation.SetProp(5, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Caption.ordinal.toLong() + 1L)),
            Mutation.Insert(4, 5, 0),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 4, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val inheritedBounds = onNodeWithTag(nodeTestTag(3)).getBoundsInRoot()
        val ownBounds = onNodeWithTag(nodeTestTag(5)).getBoundsInRoot()
        val inheritedHeight = inheritedBounds.bottom - inheritedBounds.top
        val ownHeight = ownBounds.bottom - ownBounds.top
        assertTrue(
            inheritedHeight > ownHeight,
            "child inheriting Display ($inheritedHeight) must be taller than child setting Caption ($ownHeight)",
        )
    }
}
