package dev.darkpyonix.composerust.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * The resize pointer of the Java toolkit, for the windows that are still the toolkit's
 * (Windows until its own window becomes the default). Kept out of
 * ResizeCursor.kt so the macOS and Linux paths never names the toolkit's Cursor class.
 */
internal fun toolkitResizeIcon(): PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
