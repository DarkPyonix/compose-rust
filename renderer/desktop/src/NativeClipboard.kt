@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import org.thisisthepy.compose.window.graalvm.macos.readClipboard
import org.thisisthepy.compose.window.graalvm.macos.writeClipboard

// The clipboard of a window that has no toolkit.
//
// Compose's own on desktop is the toolkit's, and a process that was told it has no display
// is answered with no clipboard at all: a copy goes nowhere and a paste finds nothing, and
// nothing says so. So the window's own pasteboard stands in, reached through the same two
// calls every window of ours shares.
//
// The entry Compose passes around is a toolkit data object on desktop. Only its text is
// read and written here, which is all a text field asks for.

/** The text on the clipboard, or null where there is none. */
private fun clipboardText(): String? = readClipboard().takeIf { it.isNotEmpty() }

@Suppress("DEPRECATION")
internal class WindowClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? = clipboardText()?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) = writeClipboard(annotatedString.text)

    override fun hasText(): Boolean = clipboardText() != null
}

internal class WindowClipboardImpl : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        clipboardText()?.let { ClipEntry(StringSelection(it)) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.asAwtTransferable?.let {
            runCatching { it.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        }
        writeClipboard(text.orEmpty())
    }

    override val nativeClipboard: NativeClipboard get() = this
}

/** One of each for every window: they hold nothing, they only ask the platform. */
internal val WindowClipboard: Clipboard = WindowClipboardImpl()

@Suppress("DEPRECATION")
internal val WindowClipboardManagerInstance: ClipboardManager = WindowClipboardManager()
