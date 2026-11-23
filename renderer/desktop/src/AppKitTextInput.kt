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
class NativeWindowInfo(
    private val size: () -> IntSize,
    private val focused: () -> Boolean = { true },
) : WindowInfo {
    override val isWindowFocused: Boolean get() = focused()
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
    private val focused: () -> Boolean = { true },
    /**
     * Sets the pointer's shape by its CSS keyword (FR-47.1), on a window that can: the fork's
     * table gives each keyword its closest shape there. Null where the window knows only the
     * few shapes of [PointerShape]; a keyword then takes the nearest of those.
     */
    private val setCursorShape: ((org.thisisthepy.compose.window.CursorShape) -> Unit)? = null,
) : PlatformContext {

    override val windowInfo: WindowInfo = NativeWindowInfo(size, focused)
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
        val keyword = (pointerIcon as? dev.darkpyonix.composerust.foundation.CursorKeywordIcon)
            ?.let { org.thisisthepy.compose.window.CursorShape.fromWire(it.keyword) }
        val byKeyword = setCursorShape
        if (keyword != null && byKeyword != null) {
            byKeyword(keyword)
        } else {
            setCursor(pointerShapeOf(pointerIcon))
        }
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
    is dev.darkpyonix.composerust.foundation.CursorKeywordIcon ->
        org.thisisthepy.compose.window.CursorShape.fromWire(pointerIcon.keyword)
            ?.let(::nearestPointerShape) ?: PointerShape.ARROW
    else -> PointerShape.ARROW
}

/**
 * The nearest of the few shapes every desktop window here knows, for a CSS `cursor` keyword on
 * a window that cannot set one by keyword (FR-47.1). Anything without a near shape is the arrow.
 */
internal fun nearestPointerShape(shape: org.thisisthepy.compose.window.CursorShape): Int {
    return when (shape) {
        org.thisisthepy.compose.window.CursorShape.Pointer,
        org.thisisthepy.compose.window.CursorShape.Grab,
        org.thisisthepy.compose.window.CursorShape.Grabbing,
        -> PointerShape.HAND
        org.thisisthepy.compose.window.CursorShape.Text,
        org.thisisthepy.compose.window.CursorShape.VerticalText,
        -> PointerShape.TEXT
        org.thisisthepy.compose.window.CursorShape.Crosshair,
        org.thisisthepy.compose.window.CursorShape.Cell,
        -> PointerShape.CROSSHAIR
        org.thisisthepy.compose.window.CursorShape.ColResize,
        org.thisisthepy.compose.window.CursorShape.EResize,
        org.thisisthepy.compose.window.CursorShape.WResize,
        org.thisisthepy.compose.window.CursorShape.EwResize,
        -> PointerShape.RESIZE_LEFT_RIGHT
        org.thisisthepy.compose.window.CursorShape.RowResize,
        org.thisisthepy.compose.window.CursorShape.NResize,
        org.thisisthepy.compose.window.CursorShape.SResize,
        org.thisisthepy.compose.window.CursorShape.NsResize,
        -> PointerShape.RESIZE_UP_DOWN
        else -> PointerShape.ARROW
    }
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
