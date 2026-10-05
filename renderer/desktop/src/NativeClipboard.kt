@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.StringSelection
import org.thisisthepy.compose.window.TextPasteboard
import org.thisisthepy.compose.window.copyText
import org.thisisthepy.compose.window.hasText
import org.thisisthepy.compose.window.pasteText
import org.thisisthepy.compose.window.graalvm.macos.readClipboard
import org.thisisthepy.compose.window.graalvm.macos.writeClipboard
import java.awt.datatransfer.Transferable

// The clipboard of a window that has no toolkit.
//
// Compose's own on desktop is the toolkit's, and a process that was told it has no display
// is answered with no clipboard at all: a copy goes nowhere and a paste finds nothing, and
// nothing says so. So the window's own pasteboard stands in, reached through the same two
// calls every window of ours shares.
//
// The entry Compose passes around is a toolkit data object on desktop. Only its text is
// read and written here, which is all a text field asks for.

/**
 * The window's pasteboard as the toolkit's clipboard type, which is the one thing Compose
 * checks before it offers Paste.
 *
 * Compose's text menus ask `Clipboard.awtClipboard`, a cast of [Clipboard.nativeClipboard]
 * to `java.awt.datatransfer.Clipboard`, whether text is available. Anything else answers
 * no, so with the clipboard object itself standing there Paste was disabled whatever the
 * pasteboard held. The class is the data transfer one, which needs no display and no
 * toolkit; only its text flavour is answered.
 */
internal class PasteboardClipboard(
    readText: () -> String?,
    writeText: (String) -> Unit,
) : java.awt.datatransfer.Clipboard("pasteboard") {
    // The fork's pasteboard rules: an empty string is nothing to paste, null clears.
    private val pasteboard = object : TextPasteboard {
        override fun read(): String? = readText()
        override fun write(text: String) = writeText(text)
    }

    private fun text(): String? = pasteboard.pasteText()

    override fun getContents(requestor: Any?): Transferable? = text()?.let(::StringSelection)

    override fun setContents(contents: Transferable?, owner: ClipboardOwner?) {
        val text = contents?.let {
            runCatching { it.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        }
        pasteboard.copyText(text)
    }

    override fun getAvailableDataFlavors(): Array<DataFlavor> =
        if (pasteboard.hasText()) arrayOf(DataFlavor.stringFlavor) else emptyArray()

    override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.stringFlavor && pasteboard.hasText()

    override fun getData(flavor: DataFlavor): Any {
        val text = text()
        if (flavor != DataFlavor.stringFlavor || text == null) {
            throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
        return text
    }
}

@Suppress("DEPRECATION")
internal class WindowClipboardManager(
    private val pasteboard: PasteboardClipboard = PasteboardClipboard(::readClipboard, ::writeClipboard),
) : ClipboardManager {
    override fun getText(): AnnotatedString? =
        pasteboard.getContents(null)?.let { AnnotatedString(it.getTransferData(DataFlavor.stringFlavor) as String) }

    override fun setText(annotatedString: AnnotatedString) =
        pasteboard.setContents(StringSelection(annotatedString.text), null)

    override fun hasText(): Boolean = pasteboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
}

internal class WindowClipboardImpl(
    private val pasteboard: PasteboardClipboard = PasteboardClipboard(::readClipboard, ::writeClipboard),
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = pasteboard.getContents(null)?.let(::ClipEntry)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) =
        pasteboard.setContents(clipEntry?.asAwtTransferable, null)

    override val nativeClipboard: NativeClipboard get() = pasteboard
}

/** One of each for every window: they hold nothing, they only ask the platform. */
internal val WindowClipboard: Clipboard = WindowClipboardImpl()

@Suppress("DEPRECATION")
internal val WindowClipboardManagerInstance: ClipboardManager = WindowClipboardManager()
