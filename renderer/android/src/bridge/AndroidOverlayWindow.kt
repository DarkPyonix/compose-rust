package dev.darkpyonix.composerust.ui.platform

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import dev.darkpyonix.composerust.foundation.platformOverlayWindow

/**
 * Keeps the keyboard from moving the window a sheet or a dialog is drawn in.
 *
 * On Android each of them is a window of its own, and a focusable window with nothing said
 * about the keyboard is shrunk to the space above it. The scrim inside it then covered only
 * that space and was centred on the screen, so it started part way down and left the top
 * of the screen undimmed. Told to leave the window alone, the scrim keeps covering the
 * whole screen and the renderer lifts the sheet's own surface above the keyboard by the
 * inset the application's window reports.
 *
 * The layout parameters are the popup's own object, the one it hands back to the window
 * manager whenever it moves, so the setting stays through every later update. The content
 * can be composed before the popup's window is added, so a view that is not attached yet
 * is told when it is.
 */
internal fun installAndroidOverlayWindow() {
    platformOverlayWindow = {
        val view = LocalView.current
        SideEffect {
            if (view.isAttachedToWindow) {
                leaveForTheKeyboard(view)
            } else {
                view.addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(attached: View) {
                            attached.removeOnAttachStateChangeListener(this)
                            leaveForTheKeyboard(attached)
                        }

                        override fun onViewDetachedFromWindow(detached: View) = Unit
                    },
                )
            }
        }
    }
}

/** Sets the window holding [view] to be neither resized nor panned for the keyboard. */
private fun leaveForTheKeyboard(view: View) {
    val root = view.rootView
    val params = root.layoutParams as? WindowManager.LayoutParams ?: return
    val adjust = params.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
    if (adjust == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) return
    params.softInputMode =
        (params.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    val manager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    manager.updateViewLayout(root, params)
}
