@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.AnnotatedString
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

/** The system's general pasteboard, the one every other application copies to and from. */
internal class GeneralPasteboard : TextPasteboard {
    override fun read(): String? =
        NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString)

    override fun write(text: String) {
        val board = NSPasteboard.generalPasteboard
        board.clearContents()
        board.setString(text, NSPasteboardTypeString)
    }
}

/**
 * The clipboard Compose's text fields copy to and paste from, backed by a pasteboard.
 *
 * Provided to the content explicitly rather than left to the default. Whether copy, cut and
 * paste work in a field is then this window's own doing and can be read here, and a copy
 * that goes nowhere or a paste that finds nothing has one place to be looked for.
 */
internal class MacosClipboard(
    private val pasteboard: TextPasteboard = GeneralPasteboard(),
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        pasteboard.pasteText()?.let { ClipEntry.withPlainText(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        pasteboard.copyText(clipEntry?.getPlainText())
    }

    override val nativeClipboard: NativeClipboard get() = NSPasteboard.generalPasteboard

    /** Whether there is text to paste, which is what decides if Paste is offered. */
    fun hasText(): Boolean = pasteboard.hasText()
}

/** The older entry point to the same pasteboard, which some of Compose still asks for. */
@Suppress("DEPRECATION")
internal class MacosClipboardManager(
    private val pasteboard: TextPasteboard = GeneralPasteboard(),
) : ClipboardManager {
    override fun getText(): AnnotatedString? = pasteboard.pasteText()?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) = pasteboard.copyText(annotatedString.text)

    override fun hasText(): Boolean = pasteboard.hasText()

    override fun getClip(): ClipEntry? = pasteboard.pasteText()?.let { ClipEntry.withPlainText(it) }

    override fun setClip(clipEntry: ClipEntry?) = pasteboard.copyText(clipEntry?.getPlainText())
}
