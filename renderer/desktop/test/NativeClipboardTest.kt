@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.ui.platform.NativeContextMenuRepresentation
import dev.darkpyonix.composerust.ui.platform.NativeMenuEntry
import dev.darkpyonix.composerust.ui.platform.PasteboardClipboard
import dev.darkpyonix.composerust.ui.platform.WindowClipboardImpl
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The clipboard of the window with no toolkit, as Compose's text menus see it.
 *
 * The pasteboard is a fake that answers with whatever the test put there, so these run
 * anywhere. Before this, Paste in the right-click menu was disabled on the native image
 * whatever the pasteboard held, because Compose asks the toolkit's clipboard type and was
 * handed something else.
 */
class NativeClipboardTest {

    private var board = ""
    private val clipboard = WindowClipboardImpl(PasteboardClipboard({ board }, { board = it }))

    @Test
    fun fr33_1_the_window_clipboard_is_one_compose_can_ask_for_text() {
        board = "from another app"
        val awt = assertNotNull(clipboard.awtClipboard, "Compose reads text availability here")
        assertTrue(awt.isDataFlavorAvailable(DataFlavor.stringFlavor))
        assertEquals("from another app", awt.getData(DataFlavor.stringFlavor))
        board = ""
        assertFalse(awt.isDataFlavorAvailable(DataFlavor.stringFlavor))
    }

    private fun pasteEnabledOnRightClick(): Boolean? {
        val asked = mutableListOf<List<NativeMenuEntry>>()
        var paste: Boolean? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalClipboard provides clipboard,
                    LocalContextMenuRepresentation provides NativeContextMenuRepresentation {
                        asked += it
                        -1
                    },
                ) {
                    // Empty, and the click lands on the empty part of it.
                    BasicTextField(rememberTextFieldState(""), Modifier.size(200.dp, 40.dp).testTag("field"))
                }
            }
            onNodeWithTag("field").performMouseInput { rightClick(centerRight) }
            waitForIdle()
            paste = asked.lastOrNull()?.firstOrNull { it.label == "Paste" }?.enabled
        }
        return paste
    }

    @Test
    fun fr33_1_paste_is_enabled_on_an_empty_field_when_the_pasteboard_holds_text() {
        board = "from another app"
        assertEquals(true, pasteEnabledOnRightClick(), "Paste can be chosen")
    }

    @Test
    fun fr33_1_paste_is_disabled_when_the_pasteboard_holds_no_text() {
        board = ""
        assertEquals(false, pasteEnabledOnRightClick(), "Paste is offered but cannot be chosen")
    }
}
