package dev.darkpyonix.composerust.ui.platform

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.darkpyonix.composerust.foundation.platformModalOverlayWindow

/**
 * Draws a sheet or a dialog in a window that covers the whole screen.
 *
 * Compose's popup, which the other platforms use, is measured against the parent window's
 * visible frame on Android. That frame leaves out the gesture bar and the on-screen
 * keyboard, so the scrim stopped above the gesture bar, and with the keyboard up the whole
 * overlay was only as tall as the space above it. The renderer then lifted the sheet by the
 * keyboard a second time inside that window and the sheet collapsed to its drag handle.
 * No popup property changes what the popup is measured against.
 *
 * A dialog window does what the popup cannot. Not fitting the system windows and not held to
 * the platform's dialog width, it is laid out edge to edge and, with content that fills it,
 * as big as the screen, status bar, gesture bar and camera cutout included. Its size does
 * not change with the keyboard: the keyboard is reported to the window as an inset, which
 * the renderer reads inside it and lifts only the overlay's surface by.
 *
 * Back reaches the overlay either way: as a key the renderer's own handler takes, or, where
 * the system sends Back to the window's back dispatcher instead, through the dialog's
 * dismissal, which is the same answer.
 */
internal fun installAndroidOverlayWindow() {
    platformModalOverlayWindow = { _, onBack, content ->
        Dialog(
            onDismissRequest = onBack,
            properties = DialogProperties(
                dismissOnBackPress = true,
                // The scrim covers the whole window and answers a press itself.
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.let(::coverTheScreen) }
            content()
        }
    }
}

/**
 * What the dialog window has no property for.
 *
 * The scrim is the renderer's, in the design system's colour, so the platform's own dim
 * behind a dialog goes; left on, it darkens the scrim a second time. The window appears
 * as the popup did, without the platform's dialog animation. It fills the screen whatever
 * its content measured, and it is neither resized nor panned for the keyboard: the dialog
 * asks for that itself from Android 12, and asks to be resized before it.
 */
private fun coverTheScreen(window: Window) {
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.setDimAmount(0f)
    window.setWindowAnimations(0)
    window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    val mode = window.attributes.softInputMode
    val adjust = mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
    if (adjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
        window.setSoftInputMode(
            (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
    }
}
