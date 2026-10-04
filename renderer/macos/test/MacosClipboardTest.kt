package dev.darkpyonix.composerust.test

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import dev.darkpyonix.composerust.ui.platform.MacosClipboard
import dev.darkpyonix.composerust.ui.platform.MacosClipboardManager
import dev.darkpyonix.composerust.ui.platform.TextPasteboard
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Compose clipboard types over a pasteboard that is a variable. The rules for what
 * counts as text live in shared code and are tested there; this is the ClipEntry glue.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MacosClipboardTest {

    private class FakePasteboard(var text: String? = null) : TextPasteboard {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun fr5_a_copy_reaches_the_pasteboard_and_a_paste_reads_it_back() = runBlocking {
        val board = FakePasteboard()
        val clipboard = MacosClipboard(board)
        clipboard.setClipEntry(ClipEntry.withPlainText("안녕 hello"))
        assertEquals("안녕 hello", board.text)
        assertEquals("안녕 hello", clipboard.getClipEntry()?.getPlainText())
    }

    @Test
    fun fr5_a_paste_with_nothing_on_the_pasteboard_finds_nothing() = runBlocking {
        val clipboard = MacosClipboard(FakePasteboard())
        assertNull(clipboard.getClipEntry())
        assertFalse(clipboard.hasText(), "Paste is greyed out on an empty pasteboard")
        assertTrue(MacosClipboard(FakePasteboard("x")).hasText())
    }

    @Test
    fun fr33_6_the_older_manager_sees_the_same_text() {
        val board = FakePasteboard()
        val manager = MacosClipboardManager(board)
        assertFalse(manager.hasText())
        manager.setText(AnnotatedString("copied"))
        assertEquals("copied", board.text)
        assertEquals("copied", manager.getText()?.text)
        assertTrue(manager.hasText())
    }
}
