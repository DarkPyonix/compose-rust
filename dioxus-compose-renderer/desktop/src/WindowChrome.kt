package dioxus.compose.ui.platform

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.runtime.WindowActions
import dioxus.compose.runtime.WindowCaption
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent
import javax.swing.JRootPane
import javax.swing.RootPaneContainer

/**
 * Modern window decoration, which is what every desktop platform now expects.
 *
 * macOS moved the title bar into the application's own content years ago, GNOME draws
 * client side decorations, and Windows 11 applications generally paint their own caption.
 * A window with a separate system title bar looks a decade old on all three.
 *
 * The platforms want genuinely different things, so this does not invent a common
 * abstraction over them:
 *
 * - macOS keeps its real title bar but makes it transparent and lets content run
 *   underneath. The close, minimise and zoom buttons stay the system's. Drawing those
 *   ourselves would be the most visible way to fail at looking native: their placement,
 *   hover behaviour, full screen transition and accessibility labels all belong to the
 *   system, and an imitation drifts from the real thing on the next OS release.
 * - Windows keeps its real frame and gives up only the caption, which is what VS Code and
 *   Windows Terminal do. An undecorated window there is not just a window without a title
 *   bar: it loses the drop shadow, the resize border and Snap Layouts, all of which the
 *   frame provides and none of which we can draw. The application that reported this said
 *   the borders looked crude, and they were. So the frame stays whole and the native
 *   entry point takes the caption strip into the client area, leaving the buttons and the
 *   title for this file to draw. Unlike macOS the buttons are ours, because the system's
 *   went with the strip.
 * - Linux gets an undecorated window and we paint the caption, because it offers no
 *   equivalent of the macOS client properties through AWT.
 */
internal enum class WindowChrome {
    /** Content extends into the title bar area. The default. */
    Modern,

    /** The platform's ordinary title bar. */
    System,
}

/**
 * True on macOS, where the title bar can be made transparent through AWT.
 *
 * The name is a parameter so a test can ask about a platform it is not running on. The
 * alternative is a test that only checks the machine it happens to be on, which for a
 * decision that differs per platform checks the one case nobody was worried about.
 */
internal fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Mac")

/** True on Windows, where the frame is kept because losing it costs more than it saves. */
internal fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Windows")

/**
 * True when the window keeps the frame the system gave it under [WindowChrome.Modern].
 *
 * macOS keeps it and makes the bar transparent. Windows keeps it and takes only the
 * caption strip into the client area, because the same frame is also the drop shadow, the
 * resize border and Snap Layouts. Linux has no way to ask for either, so the window is
 * undecorated there and everything across the top is drawn.
 */
internal fun platformKeepsSystemFrame(
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean = isMacOs(osName) || isWindows(osName)

/**
 * True when this platform keeps its system window buttons under [WindowChrome.Modern].
 *
 * macOS only. Its buttons stay put while the bar goes transparent, and imitating them
 * would be the most visible way to fail at looking native. Windows keeps its frame but
 * not its caption, so its buttons leave with the strip they sat in and the renderer draws
 * a set, as it already does on Linux.
 */
internal fun platformDrawsWindowButtons(
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean = isMacOs(osName)

/**
 * The caption strip Windows gives up, and the room its three buttons take at the trailing
 * edge.
 *
 * Fixed rather than measured, because the native side has to agree with it: it answers
 * the hit test for this strip and has to know which part of it is buttons that take
 * ordinary clicks and which part drags the window. A decorated frame's top inset is the
 * caption plus the resize border, which is not the number either side wants.
 *
 * The room for the buttons is reserved on the native side rather than here, because the
 * design system decides how wide its own three are and the hit test cannot wait for that.
 * It reserves the widest any design system draws, so a narrower set leaves a little of
 * the strip draggable that could have been clickable, and never the reverse.
 */
internal val windowsCaptionHeight = 32.dp

/**
 * Applies [chrome] to an already created window.
 *
 * On macOS this is three AWT client properties and no native code:
 * `fullWindowContent` extends the content view under the title bar, `transparentTitleBar`
 * stops the bar painting its own background, and `windowTitleVisible` hides the text,
 * which would otherwise float over application content. The JDK has supported these since
 * 17, so no JNI and no reachability metadata are involved.
 *
 * Called after the window exists because AWT reads these when the peer is realised.
 */
internal fun applyWindowChrome(window: AwtWindow, chrome: WindowChrome) {
    // Read the title bar height while the window still has one: fullWindowContent below
    // makes AWT report a top inset of zero, because the content genuinely starts at the
    // top from then on.
    captionHeight(window)
    if (chrome == WindowChrome.System) return
    val root: JRootPane = (window as? RootPaneContainer)?.rootPane ?: return
    if (isMacOs()) {
        root.putClientProperty("apple.awt.fullWindowContent", true)
        root.putClientProperty("apple.awt.transparentTitleBar", true)
        root.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

/**
 * The strip this window's own chrome occupies, for whatever draws across the top of it to
 * lay itself out around.
 *
 * Content is allowed to run underneath the caption, which is the point, but a widget
 * placed where the macOS traffic lights are would leave both unusable. The renderer
 * applies this; the Host never sees it and cannot set it, because the safe area is a fact
 * about the window rather than a decision the application makes.
 *
 * The height is measured rather than guessed. AWT reports a decorated window's title bar
 * as the top inset of its frame, and on macOS that is the same strip the traffic lights
 * sit in. Reading it means the value follows the platform instead of drifting from it the
 * next time Apple changes the height, which a constant in this file would not.
 *
 * A window that kept its ordinary title bar has no such strip: the system already drew
 * the bar above the content, and there is nothing to run underneath.
 */
internal fun windowCaption(
    window: AwtWindow?,
    chrome: WindowChrome,
    osName: String = System.getProperty("os.name").orEmpty(),
): WindowCaption =
    // A window whose bar the system drew has no strip for content to run under.
    if (chrome == WindowChrome.System) {
        WindowCaption.None
    } else {
        WindowCaption(
            height = captionHeight(window, osName),
            buttonsWidth = systemWindowButtonsWidth(osName),
        )
    }

/**
 * What this window's three caption buttons do, or null where the platform draws its own.
 *
 * Null is the whole point of returning null: on macOS the system owns these buttons, and
 * the renderer draws nothing rather than drawing a second set beside them. Null also
 * covers a window that kept its ordinary title bar, where the buttons are already there.
 *
 * Maximise toggles rather than only maximising, because an undecorated window has no
 * other way back: the button that made the window full size has to be the button that
 * undoes it.
 */
internal fun windowActions(window: AwtWindow?, chrome: WindowChrome): WindowActions? {
    if (chrome == WindowChrome.System || platformDrawsWindowButtons()) return null
    val frame = window as? Frame ?: return null
    return WindowActions(
        minimise = { frame.extendedState = frame.extendedState or Frame.ICONIFIED },
        maximise = {
            frame.extendedState = if (frame.extendedState and Frame.MAXIMIZED_BOTH != 0) {
                Frame.NORMAL
            } else {
                Frame.MAXIMIZED_BOTH
            }
        },
        close = { frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING)) },
    )
}

/**
 * The height of the strip the window buttons occupy.
 *
 * Asks the window first. A frame that has been made full size content reports a top inset
 * of zero, since its content really does start at the top, so this reads the inset before
 * the chrome is applied and remembers it. Where there is nothing to ask, the fallbacks are
 * each platform's standard height.
 */
private fun captionHeight(
    window: AwtWindow?,
    osName: String = System.getProperty("os.name").orEmpty(),
): Dp {
    // Windows is told rather than asked. The native side answers the hit test for this
    // same strip, and a measured inset there is the caption plus the resize border.
    if (isWindows(osName)) return windowsCaptionHeight
    measuredCaptionHeight?.let { return it }
    val measured = window?.insets?.top?.takeIf { it > 0 }?.dp
    val height = measured ?: if (platformDrawsWindowButtons()) 28.dp else 32.dp
    measuredCaptionHeight = height
    return height
}

private var measuredCaptionHeight: Dp? = null

/** The horizontal room the system window buttons occupy, for a caption to lay out around. */
internal fun systemWindowButtonsWidth(
    osName: String = System.getProperty("os.name").orEmpty(),
): Dp = if (isMacOs(osName)) 78.dp else 0.dp
