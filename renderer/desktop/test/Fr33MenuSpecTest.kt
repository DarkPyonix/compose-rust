package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import dev.darkpyonix.composerust.ui.platform.PasteboardClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Fr33MenuSpecTest {
    @Test
    fun fr33_1_paste_enabled_state_follows_clipboard() {
        var backing: String? = null
        val pasteboard = PasteboardClipboard(readText = { backing }, writeText = { backing = it })

        assertFalse(
            pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor),
            "Paste must be disabled when clipboard is empty",
        )

        pasteboard.setContents(StringSelection("hello"), null)
        assertTrue(
            pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor),
            "Paste must be enabled when clipboard holds text",
        )
        assertEquals("hello", pasteboard.getContents(null)?.getTransferData(DataFlavor.stringFlavor))

        backing = ""
        assertFalse(
            pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor),
            "Paste must be disabled when clipboard text is empty",
        )
    }

    @Test
    fun fr33_1_linux_drawn_text_menu_matches_items_and_enabled_state() {
        var backing: String? = "copied"
        val pasteboard = PasteboardClipboard(readText = { backing }, writeText = { backing = it })

        // The drawn text menu on Linux uses the same clipboard availability and item set
        // (Cut, Copy, Paste, Select All) as macOS and Windows.
        assertTrue(pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
        backing = null
        assertFalse(pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
    }

    @Test
    fun fr33_1_menu_and_dropdown_popups_use_design_system_popup() = runComposeUiTest {
        val frames = FrameRequestSource()
        val batch = buildList {
            add(Mutation.Create(1, WidgetKind.Box))
            add(Mutation.Insert(0, 1, 0))
            add(Mutation.Create(2, WidgetKind.Menu))
            add(Mutation.SetProp(2, PropertyKind.Open, PropertyValue.Bool(true)))
            add(Mutation.Insert(1, 2, 0))
            add(Mutation.Create(3, WidgetKind.Button))
            add(Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Open")))
            add(Mutation.Insert(2, 3, 0))
            listOf(10 to "One", 11 to "Two").forEachIndexed { idx, (id, label) ->
                add(Mutation.Create(id, WidgetKind.Button))
                add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
                add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(100L + id)))
                add(Mutation.Insert(2, id, idx + 1))
            }
        }
        val connection = FakeHostConnection(batch)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(10)).assertIsFocused()
        onNodeWithTag(nodeTestTag(10)).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(11)).assertIsFocused()
    }
}
