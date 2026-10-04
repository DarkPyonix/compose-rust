@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.AnnotatedString
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

/**
 * The text on a pasteboard, which is all a text field asks for.
 *
 * A seam rather than the pasteboard itself so that what the clipboard does with it can be
 * exercised without a window server: a test holds a string in a variable.
 */
internal interface TextPasteboard {
    /** The text on it, or null where it holds nothing or holds something that is not text. */
    fun read(): String?

    /** Replaces what is on it with [text]. */
    fun write(text: String)
}

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
        pasteboard.read()?.takeIf { it.isNotEmpty() }?.let { ClipEntry.withPlainText(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.getPlainText()
        if (text != null) pasteboard.write(text)
    }

    override val nativeClipboard: NativeClipboard get() = NSPasteboard.generalPasteboard

    /** Whether there is text to paste, which is what decides if Paste is offered. */
    fun hasText(): Boolean = !pasteboard.read().isNullOrEmpty()
}

/** The older entry point to the same pasteboard, which some of Compose still asks for. */
@Suppress("DEPRECATION")
internal class MacosClipboardManager(
    private val pasteboard: TextPasteboard = GeneralPasteboard(),
) : ClipboardManager {
    override fun getText(): AnnotatedString? =
        pasteboard.read()?.takeIf { it.isNotEmpty() }?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) = pasteboard.write(annotatedString.text)

    override fun hasText(): Boolean = !pasteboard.read().isNullOrEmpty()

    override fun getClip(): ClipEntry? = pasteboard.read()?.takeIf { it.isNotEmpty() }
        ?.let { ClipEntry.withPlainText(it) }

    override fun setClip(clipEntry: ClipEntry?) {
        clipEntry?.getPlainText()?.let(pasteboard::write)
    }
}
