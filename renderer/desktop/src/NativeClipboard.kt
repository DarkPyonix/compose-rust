@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.AnnotatedString
import org.thisisthepy.compose.window.TextPasteboard
import org.thisisthepy.compose.window.copyText
import org.thisisthepy.compose.window.hasText
import org.thisisthepy.compose.window.pasteText
import org.thisisthepy.compose.window.graalvm.macos.readClipboard
import org.thisisthepy.compose.window.graalvm.macos.writeClipboard

// The clipboard of a window that has no toolkit.
//
// Compose's own on desktop is the toolkit's, and a process that was told it has no display
// is answered with no clipboard at all: a copy goes nowhere and a paste finds nothing, and
// nothing says so. So the window's own pasteboard stands in, reached through the same two
// calls every window of ours shares.
//
// Only text is read and written here, which is all a text field asks for.

/**
 * The window's pasteboard, as the clipboard Compose's desktop code asks for.
 *
 * Compose's text menus read [Clipboard.nativeClipboard] to learn, synchronously, whether
 * there is text to paste. For a clipboard that is not the toolkit's the fork's desktop modules
 * take that to be a [String] holding the current text, and a plain-text [ClipEntry] to carry
 * text in either direction: neither names a `java.awt.datatransfer` type, so no toolkit class
 * is loaded to copy or paste.
 */
internal class PasteboardClipboard(
    readText: () -> String?,
    writeText: (String) -> Unit,
) {
    // The fork's pasteboard rules: an empty string is nothing to paste, null clears.
    private val pasteboard = object : TextPasteboard {
        override fun read(): String? = readText()
        override fun write(text: String) = writeText(text)
    }

    fun text(): String? = pasteboard.pasteText()

    fun setText(text: String?) = pasteboard.copyText(text)

    fun hasText(): Boolean = pasteboard.hasText()
}

@Suppress("DEPRECATION")
internal class WindowClipboardManager(
    private val pasteboard: PasteboardClipboard = PasteboardClipboard(::readClipboard, ::writeClipboard),
) : ClipboardManager {
    override fun getText(): AnnotatedString? = pasteboard.text()?.let { AnnotatedString(it) }

    override fun setText(annotatedString: AnnotatedString) = pasteboard.setText(annotatedString.text)

    override fun hasText(): Boolean = pasteboard.hasText()
}

internal class WindowClipboardImpl(
    private val pasteboard: PasteboardClipboard = PasteboardClipboard(::readClipboard, ::writeClipboard),
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = pasteboard.text()?.let { ClipEntry(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) =
        pasteboard.setText(
            when (val native = clipEntry?.nativeClipEntry) {
                is String -> native
                is AnnotatedString -> native.text
                else -> null
            },
        )

    override val nativeClipboard: NativeClipboard get() = pasteboard.text() ?: ""
}

/** One of each for every window: they hold nothing, they only ask the platform. */
internal val WindowClipboard: Clipboard = WindowClipboardImpl()

@Suppress("DEPRECATION")
internal val WindowClipboardManagerInstance: ClipboardManager = WindowClipboardManager()
