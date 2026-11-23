@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The right-click menu of a text field on the Linux window: Compose's, and only Compose's.
 *
 * The X11 window has no menu of its own to put up (no toolkit is linked), so the one menu a
 * right click brings up is the one Compose draws. What would break that is the renderer
 * borrowing the Windows window's arrangement, which keeps Compose's menu shut while the
 * window puts up the system's: here nothing would come up at all. And Compose's other menu
 * path has no default on this target and composes nothing where a field should be.
 */
class LinuxTextMenuTest {

    @Test
    fun fr33_1_linux_nothing_keeps_composes_text_menu_shut() {
        assertSame(
            Modifier,
            dev.darkpyonix.composerust.foundation.platformTextMenuModifier,
            "a text field here is given nothing that holds Compose's menu back: it is the only menu",
        )
    }

    @Test
    fun fr33_1_linux_text_fields_take_the_context_menu_path_that_draws_one() {
        assertFalse(
            ComposeFoundationFlags.isNewContextMenuEnabled,
            "the new context menu path has no provider on Linux and would leave the field without a menu",
        )
    }

    @Test
    fun fr33_1_linux_clipboard_paste_availability_follows_x11_text() {
        var backing: String? = null
        val manager = LinuxWindowClipboardManager(
            readText = { backing },
            writeText = { backing = it },
        )
        val clipboard = LinuxWindowClipboard(
            readText = { backing },
            writeText = { backing = it },
            native = { error("nativeClipboard is not read by this check") },
        )

        assertFalse(manager.hasText(), "Paste must be disabled when clipboard is null")
        assertNull(manager.getText())
        assertNull(runBlocking { clipboard.getClipEntry() })

        manager.setText(AnnotatedString("copied"))
        assertTrue(manager.hasText(), "Paste must be enabled when clipboard holds text")
        assertEquals("copied", manager.getText()?.text)
        assertEquals("copied", runBlocking { clipboard.getClipEntry()?.getPlainText() })

        backing = ""
        assertFalse(manager.hasText(), "Paste must be disabled when clipboard text is empty")
        assertNull(manager.getText())
        assertNull(runBlocking { clipboard.getClipEntry() })

        runBlocking { clipboard.setClipEntry(ClipEntry.withPlainText("from x11")) }
        assertEquals("from x11", backing)
        assertTrue(manager.hasText())

        runBlocking { clipboard.setClipEntry(null) }
        assertEquals("", backing)
        assertFalse(manager.hasText())
    }
}
