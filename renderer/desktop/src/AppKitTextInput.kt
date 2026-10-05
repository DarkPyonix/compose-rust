@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.awaitCancellation

// What a scene needs from the thing hosting it, answered without a toolkit.
//
// Almost all of it has a default that is already right. Two answers are required, and one
// more matters: a field asks to be typed into through `startInputMethod`, and until
// something answers that, a field can be focused and clicked and stay empty however much
// is typed at it. Key events are not how text arrives in Compose; an input session is.

/**
 * The window a scene is in, as far as Compose needs to know.
 *
 * Focus is reported as held, because a window that never says so leaves a text field
 * drawing no caret: the field is told the window it sits in is not the one being typed at.
 * Following the real focus is the next thing this learns, and the shell already hears it.
 */
class NativeWindowInfo(private val size: () -> IntSize) : WindowInfo {
    override val isWindowFocused: Boolean get() = true
    override val containerSize: IntSize get() = size()
}

/** Pointer or keyboard, which decides whether focus is drawn. */
class NativeInputModeManager : InputModeManager {
    override val inputMode: InputMode get() = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean =
        inputMode == InputMode.Keyboard || inputMode == InputMode.Touch
}

/**
 * The platform, as the scene sees it.
 *
 * Everything not overridden here has a default that suits a window we own, and the
 * defaults are the reason this is short rather than the reason it is unfinished.
 */
class NativePlatformContext(
    private val size: () -> IntSize,
    private val textInput: NativeTextInput,
    private val semantics: PlatformContext.SemanticsOwnerListener,
    /** Sets the shape of the pointer, by the number [pointerShapeOf] answers with. */
    private val setCursor: (Int) -> Unit,
) : PlatformContext {

    override val windowInfo: WindowInfo = NativeWindowInfo(size)
    override val inputModeManager: InputModeManager = NativeInputModeManager()
    override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener = semantics

    /**
     * The shape the pointer takes over whatever it is on.
     *
     * Compose names a few shapes and leaves the rest to the platform. A shape this does
     * not know becomes the arrow, which is what a pointer over something unremarkable
     * looks like anyway.
     */
    override fun setPointerIcon(pointerIcon: PointerIcon) {
        setCursor(pointerShapeOf(pointerIcon))
    }

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing =
        textInput.run(request)
}

/**
 * The shell's number for a pointer shape.
 *
 * One table for all three native shells, because the AppKit, Win32 and X11 windows all stand
 * on [NativePlatformContext] and each maps the same numbers to its own cursors: the resize
 * shape is `NSCursor.resizeLeftRight`, `IDC_SIZEWE` and `XC_sb_h_double_arrow`.
 */
internal fun pointerShapeOf(pointerIcon: PointerIcon): Int = when (pointerIcon) {
    PointerIcon.Hand -> PointerShape.HAND
    PointerIcon.Text -> PointerShape.TEXT
    PointerIcon.Crosshair -> PointerShape.CROSSHAIR
    // The split pane's divider, which asks for the shape the platform uses for dragging
    // something left or right.
    dev.darkpyonix.composerust.foundation.platformResizeCursor -> PointerShape.RESIZE_LEFT_RIGHT
    else -> PointerShape.ARROW
}

/** What a pointer can look like, in the small set the renderer and each window's C side agree on. */
object PointerShape {
    const val ARROW = 0
    const val HAND = 1
    const val TEXT = 2
    const val CROSSHAIR = 3
    const val RESIZE_LEFT_RIGHT = 4
    const val RESIZE_UP_DOWN = 5
}
