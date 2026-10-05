@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.lang.System
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.getuid
import platform.posix.timespec
import org.thisisthepy.compose.window.CaretRect
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowFrames
import org.thisisthepy.compose.window.WindowListener
import org.thisisthepy.compose.window.candidateSpot
import org.thisisthepy.compose.window.linux.AtspiActions
import org.thisisthepy.compose.window.linux.AtspiBridge
import org.thisisthepy.compose.window.linux.AtspiSemanticsSource
import org.thisisthepy.compose.window.linux.CURSOR_ARROW
import org.thisisthepy.compose.window.linux.CURSOR_CROSSHAIR
import org.thisisthepy.compose.window.linux.CURSOR_HAND
import org.thisisthepy.compose.window.linux.CURSOR_TEXT
import org.thisisthepy.compose.window.linux.X11Window
import org.thisisthepy.compose.window.linux.PosixBusConnection as AccessibilityBusConnection

/**
 * The Compose half of the window this renderer opens on X11: the scene, what is typed into it
 * and what a reader is told about it.
 *
 * The window itself, Xlib, GLX, the sync extension, the input method and the clipboard, is the
 * Compose fork's `extended/window/native/linux` module, which implements the common window
 * interface and knows nothing of Compose. This class listens to it and draws a scene into it,
 * and it is the only place the two meet.
 *
 * The one thing that window does that no toolkit window does, and the reason it exists: **the
 * frame that belongs to a resize is drawn from inside the handling of the resize.** The display
 * server has already moved the window's edge by the time the event arrives, and what is inside
 * that edge is whatever was last painted. A frame drawn on the next turn of the loop leaves a
 * strip of window that has been claimed and not painted, as wide as the speed of the hand times
 * how late the painting is. So the window hands the listener a resize event and expects the
 * frame drawn and presented before the call returns: [frames] is that, and it refuses a second
 * frame started on top of one already being drawn.
 */
internal class LinuxWindow private constructor(
    private val x11: X11Window,
    private val title: String,
) : WindowListener {

    private val reportFrames = System.getenv("DXC_REPORT_FRAMES") != null
    private val reportInput = System.getenv("DXC_REPORT_INPUT") != null

    /**
     * Whether this window has the keyboard, as the window last said.
     *
     * Snapshot state, so that what reads it in composition is told when it changes: whether
     * the window is the active one decides whether a notification that asked to be shown only
     * when it is not is shown at all.
     */
    private var focused by mutableStateOf(true)

    /**
     * Runs once every turn of the loop, after the window has heard the server.
     *
     * For what reaches this process through a socket of its own rather than through the
     * display server: the session bus is read here, on the one thread this renderer has.
     */
    var onTurn: () -> Unit = {}

    /** What the window heard this turn, in order. Reused, because a frame is not the place to allocate a list. */
    private val heard = mutableListOf<WindowEvent>()

    /** Where committed and composing text goes. */
    private val textInput = NativeTextInput()

    /**
     * What the window would tell a reader who cannot see it, as the platform-neutral list the
     * other desktops hand their reader. Kept for the frame report below; what a Linux screen
     * reader is answered from is [atspiSource], which keeps the tree rather than a list.
     */
    private var described: List<org.thisisthepy.compose.window.AccessibleElement> = emptyList()

    private val semantics = NativeSemantics { elements ->
        described = elements
        if (reportFrames) {
            System.err.println(
                "compose-rust: the window holds ${described.size} things to say" +
                    (described.firstOrNull()?.let { ", the first being \"${it.label}\"" } ?: ""),
            )
        }
    }

    /** The scene's semantics, read into the tree AT-SPI serves. */
    private val atspiSource = AtspiSemanticsSource()

    /** Both listeners hear every change: the scene has one place to report to. */
    private val semanticsListeners: PlatformContext.SemanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
        override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
            semantics.onSemanticsOwnerAppended(semanticsOwner)
            atspiSource.onSemanticsOwnerAppended(semanticsOwner)
        }

        override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
            semantics.onSemanticsOwnerRemoved(semanticsOwner)
            atspiSource.onSemanticsOwnerRemoved(semanticsOwner)
        }

        override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
            semantics.onSemanticsChange(semanticsOwner)
            atspiSource.onSemanticsChange(semanticsOwner)
        }

        override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
            semantics.onLayoutChange(semanticsOwner, semanticsNodeId)
            atspiSource.onLayoutChange(semanticsOwner, semanticsNodeId)
        }
    }

    /** This window's place on the accessibility bus, once [startAccessibility] has joined it. */
    private var accessibility: AtspiBridge? = null

    /** The field that asked to be typed into, kept to ask where its caret is. */
    private var inputRequest: PlatformTextInputMethodRequest? = null

    /**
     * Where the scene's own work runs: here, on the thread that draws, once a frame.
     *
     * Kept rather than left to the scene. A scene left to choose hands its work to a dispatcher
     * of the library's choosing, and the Host this renderer talks to is on this thread and
     * invisible from every other.
     */
    private val work = FrameDispatcher()

    private val windowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean get() = focused
        override val containerSize: IntSize
            get() = x11.measure().let { IntSize(it.width, it.height) }
    }

    private val platformContext: PlatformContext =
        object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo get() = this@LinuxWindow.windowInfo
            override val semanticsOwnerListener get() = semanticsListeners

            override suspend fun startInputMethod(
                request: PlatformTextInputMethodRequest,
            ): Nothing {
                inputRequest = request
                try {
                    textInput.run(request)
                } finally {
                    inputRequest = null
                    x11.setImeActive(false)
                }
            }

            /**
             * The shape the pointer takes over whatever it is on.
             *
             * Compose names a few shapes and leaves the rest to the platform. One it does not
             * name becomes the arrow, which is what a pointer over something unremarkable looks
             * like anyway.
             */
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                x11.setCursor(
                    when (pointerIcon) {
                        PointerIcon.Hand -> CURSOR_HAND
                        PointerIcon.Text -> CURSOR_TEXT
                        PointerIcon.Crosshair -> CURSOR_CROSSHAIR
                        else -> CURSOR_ARROW
                    },
                )
            }
        }

    private val scene = CanvasLayersComposeScene(
        density = Density(DENSITY),
        size = x11.measure().let { IntSize(it.width, it.height) },
        coroutineContext = work,
        platformContext = platformContext,
    )

    /** Whether anything has been drawn yet, so the first turn always draws one. */
    private var painted = false

    private val openedAt = monotonicNanos()

    /**
     * The one place a frame is drawn from, whoever asked for it.
     *
     * The loop asks once a turn. The resize asks from inside the event that recorded the new
     * size. The size is read here rather than remembered, because the resize recorded it a
     * moment ago and nothing has told the loop.
     */
    private val frames = WindowFrames({ x11.measure() }) { width, height, scale ->
        val size = IntSize(width, height)
        val density = Density(scale)
        // Told to the scene here, in the frame that is about to be drawn at that size, because
        // a framebuffer that fits and a scene that does not is a window drawing its old size
        // into a corner of its new one.
        if (scene.size != size || scene.density != density) {
            scene.density = density
            scene.size = size
        }
        val nanos = monotonicNanos() - openedAt
        val drew = x11.draw(width, height) { canvas ->
            scene.render(canvas.asComposeCanvas(), nanos)
        }
        if (drew) {
            painted = true
            // The drawing goes to the server and then the manager is told, in that order. This
            // is the whole of what keeps a dragged edge attached to what is inside it.
            x11.present(width, height)
        } else {
            x11.noFrame()
        }
    }

    override fun onEvent(event: WindowEvent) {
        if (event.kind == WindowEvent.RESIZE) {
            // The window is waiting for the frame that belongs to this size.
            frames.draw()
            return
        }
        heard.add(event)
    }

    override fun onCloseRequested(): Boolean = true

    fun setContent(content: @Composable () -> Unit) {
        scene.setContent(content)
    }

    /**
     * Brings the window up: back from being minimised, and in front of the others.
     *
     * Asked of the window manager rather than done, because stacking is the manager's. The
     * request says it comes from a pager, which is the source a manager honours without its
     * focus stealing prevention: the press on a notification that led here was the user's.
     */
    fun raise() = x11.raise()

    /**
     * Runs the window until the reader closes it.
     *
     * The loop and the order of a turn are the other desktops', and each step in it is a defect
     * that has been found on one of them: a window that hears nothing, a list whose rows are
     * fetched on a thread with no Host, a screen redrawn sixty times a second while nothing is
     * happening, a tree nobody was told about.
     */
    fun run() {
        try {
            while (!x11.isClosed) {
                // The window's own turn, before anything is read from it. This thread is the one
                // the display server answers on, so the events of this frame arrive here or not
                // at all. Waiting the frame's length rather than sleeping afterwards, because a
                // window with nothing happening should rest rather than spin, and because a
                // resize that arrives during the wait is drawn inside it.
                x11.pump(FRAME_MILLISECONDS)
                focused = x11.isFocused
                onTurn()
                // A reader's questions are answered between frames, on this thread, so that
                // a press it asks for happens where every other press does.
                accessibility?.pump()
                // Before the events and before the drawing. What is waiting here is the scene's
                // own work, and a list that asked for rows on the last frame wants them in hand
                // before this one is measured.
                work.runPending()
                val turnHeard = heard.isNotEmpty()
                for (event in heard) {
                    if (reportInput && event.kind != WindowEvent.POINTER_MOVE) {
                        System.err.println("compose-rust: window heard $event")
                    }
                    scene.receive(event)
                    textInput.receive(event)
                }
                heard.clear()
                // Only when there is something to draw. A window that is being resized has
                // already had its frame drawn by the resize, and a window where nothing is
                // happening should leave the screen alone.
                val drew = if (!painted || turnHeard || scene.hasInvalidations()) {
                    frames.draw()
                } else {
                    false
                }
                // Every frame, and after the drawing. After, because that is when what is in the
                // window has been placed and can say where it is. Every frame, because a tree
                // that changed on the last one is a tree nobody has been told about, and a window
                // that has gone still is exactly where that would be forgotten.
                semantics.pushIfChanged(afterDrawing = drew)
                accessibility?.let { bridge ->
                    // After the drawing, for the reason the line above is: what is read is where
                    // everything was placed.
                    atspiSource.capture(title)?.let { bridge.update(it) }
                    bridge.windowActive(focused)
                }
                updateInputMethod()
            }
        } finally {
            close()
        }
    }

    /**
     * Gives the input method the keyboard while a field wants text, and tells it where the caret
     * is so that its candidate window opens under it. Asking again where it already is costs the
     * window nothing: it sends the server only a change.
     */
    private fun updateInputMethod() {
        val wanted = focused && textInput.isActive
        x11.setImeActive(wanted)
        if (!wanted) return
        val caret = inputRequest?.focusedRectInRoot?.invoke()?.let {
            CaretRect(it.left, it.top, it.right, it.bottom)
        }
        val spot = candidateSpot(caret, DENSITY) ?: return
        x11.setImeSpot(spot.first, spot.second)
    }

    /**
     * Joins the accessibility bus, so that a screen reader that is already running reads this
     * window from its first frame. Nothing happens where there is no accessibility bus.
     */
    fun startAccessibility(applicationName: String) {
        if (accessibility != null || System.getenv("NO_AT_BRIDGE") == "1") return
        val actions = object : AtspiActions {
            override fun click(id: Int) = atspiSource.click(id)
            override fun focus(id: Int) = atspiSource.focus(id)
            override fun windowOrigin(): Pair<Int, Int> = x11.originOnScreen()
        }
        val bridge = AtspiBridge(
            openSession = { AccessibilityBusConnection.open() },
            openAccessibility = { address -> AccessibilityBusConnection.open(address) },
            userId = getuid().toLong(),
            applicationName = applicationName,
            actions = actions,
        )
        if (bridge.start()) {
            accessibility = bridge
            if (reportFrames) System.err.println("compose-rust: joined the accessibility bus as ${bridge.busName}")
        } else if (reportFrames) {
            System.err.println("compose-rust: no accessibility bus to join")
        }
    }

    private fun close() {
        accessibility?.close()
        accessibility = null
        scene.close()
        x11.close()
    }

    companion object {
        /**
         * Opens the window, or answers null where there is no X11 display or no double
         * buffered GLX visual on it.
         */
        fun open(title: String, width: Int, height: Int): LinuxWindow? {
            val x11 = X11Window()
            // The listener is the window being made, and the window cannot exist before the
            // platform does, so it is handed over once both are there.
            val handoff = Handoff()
            if (!x11.open(WindowConfig(title = title, width = width, height = height), handoff)) {
                return null
            }
            val window = LinuxWindow(x11, title)
            handoff.target = window
            return window
        }

        /** Forwards to the window once it exists, and holds nothing back before that. */
        private class Handoff : WindowListener {
            var target: LinuxWindow? = null

            override fun onEvent(event: WindowEvent) {
                target?.onEvent(event)
            }

            override fun onCloseRequested(): Boolean = target?.onCloseRequested() ?: true
        }

        private fun monotonicNanos(): Long = memScoped {
            val now = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC, now.ptr)
            now.tv_sec * NANOS_PER_SECOND + now.tv_nsec
        }

        private const val FRAME_MILLISECONDS = 16L

        private const val DENSITY = 1.0f

        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
