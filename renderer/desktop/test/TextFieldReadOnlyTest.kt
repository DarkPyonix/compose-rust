package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertTrue

/** A TextField with PropertyKind.ReadOnly = true rejects edits while staying focusable and read-only in semantics. */
@OptIn(ExperimentalTestApi::class)
class TextFieldReadOnlyTest {
    @Test
    fun fr5_read_only_textfield_rejects_edits_and_exposes_not_editable() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Box),
            Mutation.Create(2, WidgetKind.TextField),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(200f, 40f)),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("invite-token")),
            Mutation.SetProp(2, PropertyKind.ReadOnly, PropertyValue.Bool(true)),
            Mutation.SetProp(2, PropertyKind.OnValueChange, PropertyValue.Integer(1L)),
            Mutation.Insert(1, 2, 0),
        )
        val host = FakeHostConnection(batch)
        setContent { ComposeRustContent(rememberComposeRustHost(host)) }
        waitForIdle()

        val field = onNodeWithTag(nodeTestTag(2))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        field.performClick()
        waitForIdle()
        field.assertIsFocused()

        // Attempting text input must not change the text or emit TextChanged.
        try {
            field.performTextInput("extra")
        } catch (_: AssertionError) {
            // Compose's performTextInput itself asserts IsEditable; either way no edit happens.
        }
        waitForIdle()
        field.assertTextEquals("invite-token")
        assertTrue(host.events.none { it is HostEvent.TextChanged })
    }
}
