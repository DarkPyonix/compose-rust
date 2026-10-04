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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.CLOCK_MONOTONIC
import platform.posix.POLLIN
import platform.posix.clock_gettime
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.timespec
import x11.AllocNone
import x11.Atom
import x11.ButtonPress
import x11.ButtonPressMask
import x11.ButtonRelease
import x11.ButtonReleaseMask
import x11.CWColormap
import x11.CWEventMask
import x11.ClientMessage
import x11.Colormap
import x11.ConfigureNotify
import x11.DestroyNotify
import x11.Display
import x11.Expose
import x11.ExposureMask
import x11.FocusChangeMask
import x11.FocusIn
import x11.FocusOut
import x11.NotifyNormal
import x11.NotifyWhileGrabbed
import x11.SubstructureNotifyMask
import x11.SubstructureRedirectMask
import x11.XMapRaised
import x11.XSendEvent
import x11.GLXContext
import x11.GLX_BLUE_SIZE
import x11.GLX_DOUBLEBUFFER
import x11.GLX_GREEN_SIZE
import x11.GLX_RED_SIZE
import x11.GLX_RGBA
import x11.InputOutput
import x11.KeyPress
import x11.KeyPressMask
import x11.KeyRelease
import x11.KeyReleaseMask
import x11.MotionNotify
import x11.PointerMotionMask
import x11.PropModeReplace
import x11.StructureNotifyMask
import x11.Window
import x11.XCloseDisplay
import x11.XChangeProperty
import x11.XConnectionNumber
import x11.XCreateColormap
import x11.XCreateFontCursor
import x11.XCreateWindow
import x11.XDefaultScreen
import x11.XDefineCursor
import x11.XDestroyWindow
import x11.XEvent
import x11.XFlush
import x11.XFree
import x11.XFreeColormap
import x11.XInternAtom
import x11.XLookupKeysym
import x11.XLookupString
import x11.XMapWindow
import x11.XNextEvent
import x11.XOpenDisplay
import x11.XPending
import x11.XRootWindow
import x11.XSelectInput
import x11.XSetWMProtocols
import x11.XSetWindowAttributes
import x11.XStoreName
import x11.XTranslateCoordinates
import x11.XSyncCounter
import x11.XSyncCreateCounter
import x11.XSyncInitialize
import x11.XSyncQueryExtension
import x11.XSyncSetCounter
import x11.XSyncValue
import x11.glXChooseVisual
import x11.glXCreateContext
import x11.glXDestroyContext
import x11.glXMakeCurrent

/**
 * A window of this renderer's own on X11, drawn into with Skia and with no toolkit in between.
 *
 * The pair of `MacosWindow`, and written the same way: the platform is called directly from
 * Kotlin rather than through C of ours. On this platform that is Xlib, GLX and the sync
 * extension, reached through `cinterop/x11.def`. XWayland takes the same connection, so this is
 * the window on a Wayland desktop as well until one of its own is written.
 *
 * The one thing it does that no toolkit window does, and the reason it exists: **the frame that
 * belongs to a resize is drawn from inside the handling of the resize.** The display server has
 * already moved the window's edge by the time the event arrives, and what is inside that edge
 * is whatever was last painted. A frame drawn on the next turn of the loop leaves a strip of
 * window that has been claimed and not painted, as wide as the speed of the hand times how late
 * the painting is. Measured on the other desktop where the same mistake was made: one screen
 * refresh late at every speed, which is 3 pixels for a slow drag and 350 for a flick.
 *
 * Drawing inside the resize is only half of it. The other half is [ResizeSync]: the window
 * manager holds the frame it was about to show until the counter says the drawing for the size
 * it asked about exists.
 *
 * Two rules follow from drawing inside an event, and both are their own tested class because
 * both have been broken here before. A second frame started on top of one already being drawn
 * is refused, which is [WindowFrames]. The display server is read in one place only, which is
 * [WindowEventLog]: a read reached from inside a read would take the rest of a drag out of the
 * queue while the turn handling one size change is still running.
 */
internal class LinuxWindow private constructor(
    private val display: CPointer<Display>,
    private val window: Window,
    private val context: GLXContext,
    private val colormap: Colormap,
    private val deleteWindow: Atom,
    private val protocolsAtom: Atom,
    private val syncRequest: Atom,
    private val syncCounter: XSyncCounter,
    private val title: String,
    width: Int,
    height: Int,
) {

    private val reportFrames = System.getenv("DXC_REPORT_FRAMES") != null
    private val reportInput = System.getenv("DXC_REPORT_INPUT") != null

    /** The size the window was last told it has. Never measured: the server tells us. */
    private var measured = IntSize(width, height)

    private var closed = false

    /**
     * Whether this window has the keyboard, as the server last said.
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

    private val surface = GlSurface(display, window, context)

    private val log = WindowEventLog()

    /** Reused across turns, because a frame is not the place to allocate a list. */
    private val drained = mutableListOf<WindowEvent>()

    private val sync = ResizeSync(
        present = { surface.present() },
        tell = { value -> setCounter(value) },
    )

    /** Where committed and composing text goes. */
    private val textInput = NativeTextInput()

    /**
     * What the window would tell a reader who cannot see it, as the platform-neutral list the
     * other desktops hand their reader. Kept for the frame report below; what a Linux screen
     * reader is answered from is [atspiSource], which keeps the tree rather than a list.
     */
    private var described: List<AccessibleElement> = emptyList()

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

    /**
     * What an input method says, recorded into the same log the pointer and keys go into, so
     * that a syllable and the key pressed after it reach the scene in the order they happened.
     */
    private val ime = ImeSession { event -> log.heard(event) }

    /** The connection to the desktop's input method, or null where there is none. */
    private var xim: XimContext? = null

    /** The field that asked to be typed into, kept to ask where its caret is. */
    private var inputRequest: PlatformTextInputMethodRequest? = null

    /** Where the input method was last told the caret is. */
    private var lastSpot: Pair<Int, Int>? = null

    init {
        xim = XimContext.open(display, window, ime)?.also { context ->
            // The events the input method asked to see, over and above the ones this window
            // selects: left out, some of them never see the key they are meant to filter.
            XSelectInput(display, window, EVENT_MASK or context.filterMask)
        }
        if (reportFrames) {
            System.err.println(
                "compose-rust: input method: " + (xim?.let {
                    if (it.inline) "XIM, composition drawn in the field" else "XIM, composition drawn by the input method"
                } ?: "none (${XimContext.failure})"),
            )
        }
    }

    /**
     * What the pointer looks like now, so that a crossing into the shape it already has asks
     * the server nothing.
     */
    private var cursorShape = -1
    private val cursors = LongArray(CURSOR_SHAPES)

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
        override val containerSize: IntSize get() = measured
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
                    lastSpot = null
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
                setCursor(
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
        size = measured,
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
    private val frames = WindowFrames(
        { WindowMeasurement(measured.width, measured.height, DENSITY) },
    ) { size, density ->
        // Told to the scene here, in the frame that is about to be drawn at that size, because
        // a framebuffer that fits and a scene that does not is a window drawing its old size
        // into a corner of its new one.
        if (scene.size != size || scene.density != density) {
            scene.density = density
            scene.size = size
        }
        val nanos = monotonicNanos() - openedAt
        val drew = surface.draw(size.width, size.height) { canvas ->
            scene.render(canvas.asComposeCanvas(), nanos)
        }
        if (drew) {
            painted = true
            // The drawing goes to the server and then the manager is told, in that order. This
            // is the whole of what keeps a dragged edge attached to what is inside it.
            sync.frameDrawn()
        } else {
            sync.noFrame()
        }
        // Out to the server before this returns. A swap sitting in the output buffer is a frame
        // nobody has been shown.
        XFlush(display)
    }

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
    fun raise() {
        if (closed) return
        memScoped {
            val event = alloc<XEvent>()
            event.xclient.type = ClientMessage
            event.xclient.window = window
            event.xclient.message_type = XInternAtom(display, "_NET_ACTIVE_WINDOW", 0)
            event.xclient.format = 32
            event.xclient.data.l[0] = SOURCE_PAGER
            event.xclient.data.l[1] = 0
            XSendEvent(
                display,
                XRootWindow(display, XDefaultScreen(display)),
                0,
                SubstructureRedirectMask or SubstructureNotifyMask,
                event.ptr,
            )
        }
        XMapRaised(display, window)
        XFlush(display)
    }

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
            while (!closed) {
                // The window's own turn, before anything is read from it. This thread is the one
                // the display server answers on, so the events of this frame arrive here or not
                // at all. Waiting the frame's length rather than sleeping afterwards, because a
                // window with nothing happening should rest rather than spin, and because a
                // resize that arrives during the wait is drawn inside it.
                pump(FRAME_MILLISECONDS)
                onTurn()
                // A reader's questions are answered between frames, on this thread, so that
                // a press it asks for happens where every other press does.
                accessibility?.pump()
                // Before the events and before the drawing. What is waiting here is the scene's
                // own work, and a list that asked for rows on the last frame wants them in hand
                // before this one is measured.
                work.runPending()
                drained.clear()
                log.drain(drained)
                for (event in drained) {
                    if (reportInput && event.kind != WindowEvent.POINTER_MOVE) {
                        System.err.println("compose-rust: window heard $event")
                    }
                    scene.receive(event)
                    textInput.receive(event)
                }
                // Only when there is something to draw. A window that is being resized has
                // already had its frame drawn by the resize, and a window where nothing is
                // happening should leave the screen alone.
                val drew = if (!painted || drained.isNotEmpty() || scene.hasInvalidations()) {
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
     * Lets the window answer for itself for a moment, and rests if it has nothing to say.
     *
     * The waiting is here rather than in a sleep afterwards, and that is the point of it: a
     * resize that arrives while this is waiting is drawn inside the wait, in the same step that
     * recorded the new size, instead of a turn of the loop later.
     */
    private fun pump(milliseconds: Int) {
        // XPending sends whatever is still in the output buffer before it answers, so the frame
        // just presented is on its way out before this thread goes to sleep.
        if (milliseconds > 0 && XPending(display) == 0) {
            memScoped {
                val watched = alloc<pollfd>()
                watched.fd = XConnectionNumber(display)
                watched.events = POLLIN.toShort()
                poll(watched.ptr, 1.convert(), milliseconds)
            }
        }
        // The only place the display server is read. See WindowEventLog for why that matters.
        log.read {
            memScoped {
                val event = alloc<XEvent>()
                while (XPending(display) > 0) {
                    XNextEvent(display, event.ptr)
                    handle(event)
                }
            }
        }
    }

    private fun handle(event: XEvent) {
        // Nothing after the window has gone. The rest of a batch can hold a size change for a
        // window the server has already destroyed, and presenting a frame into one of those is an
        // X error rather than a frame.
        if (closed) return
        // The input method sees every event first, and what it takes is not the window's to
        // act on. Only a key can be taken: the protocol's own messages are ordinary events
        // that it answers, and a focus change it filters is still one this window has.
        val taken = xim?.filter(event) == true
        if (taken && (event.type == KeyPress || event.type == KeyRelease)) return
        when (event.type) {
            MotionNotify -> log.heard(
                WindowEvent(
                    kind = WindowEvent.POINTER_MOVE,
                    x = event.xmotion.x.toFloat(),
                    y = event.xmotion.y.toFloat(),
                    buttons = buttonsOf(event.xmotion.state),
                    modifiers = modifiersOf(event.xmotion.state),
                    keyCode = 0,
                    codePoint = 0,
                    text = "",
                ),
            )

            ButtonPress, ButtonRelease -> {
                val button = event.xbutton.button.toInt()
                // The caret is about to move, and an input method left composing would go on
                // building a syllable at a place the reader has left.
                if (event.type == ButtonPress && button !in SCROLL_BUTTONS) finishComposition()
                if (button in SCROLL_BUTTONS) {
                    // A wheel arrives as a press and a release of a button that does not exist.
                    // The release says nothing the press did not.
                    if (event.type == ButtonPress) {
                        log.heard(
                            WindowEvent(
                                kind = WindowEvent.SCROLL,
                                x = when (button) {
                                    WHEEL_LEFT -> -SCROLL_LINES
                                    WHEEL_RIGHT -> SCROLL_LINES
                                    else -> 0f
                                },
                                y = when (button) {
                                    WHEEL_UP -> -SCROLL_LINES
                                    WHEEL_DOWN -> SCROLL_LINES
                                    else -> 0f
                                },
                                buttons = 0,
                                modifiers = modifiersOf(event.xbutton.state),
                                keyCode = 0,
                                codePoint = 0,
                                text = "",
                            ),
                        )
                    }
                } else {
                    val bit = when (button) {
                        1 -> 1
                        3 -> 2
                        2 -> 4
                        else -> 0
                    }
                    val held = buttonsOf(event.xbutton.state)
                    log.heard(
                        WindowEvent(
                            kind = if (event.type == ButtonPress) {
                                WindowEvent.POINTER_DOWN
                            } else {
                                WindowEvent.POINTER_UP
                            },
                            x = event.xbutton.x.toFloat(),
                            y = event.xbutton.y.toFloat(),
                            // The state a button event carries is the state before it, so the
                            // button this event is about is put in or taken out by hand.
                            buttons = if (event.type == ButtonPress) {
                                held or bit
                            } else {
                                held and bit.inv()
                            },
                            modifiers = modifiersOf(event.xbutton.state),
                            keyCode = 0,
                            codePoint = 0,
                            text = "",
                        ),
                    )
                }
            }

            KeyPress, KeyRelease -> for (heard in readKey(event)) log.heard(heard)

            ConfigureNotify -> {
                val width = event.xconfigure.width
                val height = event.xconfigure.height
                if (width == measured.width && height == measured.height) {
                    // The window was moved, or told again what it already was. Nothing needs
                    // painting, and a manager waiting for a counter it asked about this change
                    // is answered here rather than left holding the window.
                    sync.noFrame()
                    return
                }
                measured = IntSize(width, height)
                // Drawn here, inside the handling of the size change, rather than written down
                // for the next turn of the loop. See this class's own documentation: the strip
                // of unpainted window a later frame leaves is as wide as the speed of the hand.
                if (!frames.draw()) {
                    sync.noFrame()
                }
            }

            Expose -> {
                // Whatever was covering the window has gone, and the copy the server kept is not
                // ours to trust. Nothing in the scene changed, so the loop would draw nothing and
                // a window uncovered on a server with no compositor would go on showing what was
                // in front of it. The last of a run of these is enough: they arrive one per
                // exposed rectangle and one frame paints all of them.
                if (event.xexpose.count == 0 && !frames.draw()) {
                    sync.noFrame()
                }
            }

            ClientMessage -> {
                // Only the manager's own messages, and only the two this window offered to
                // answer. A message of somebody else's whose first word happened to equal one of
                // these atoms would otherwise close the window or promise a frame nobody asked
                // for.
                if (event.xclient.message_type == protocolsAtom) {
                    when (event.xclient.data.l[0].toULong()) {
                        syncRequest -> {
                            // The manager is about to resize the window and will hold the frame
                            // it was about to show until the counter carries this number. The
                            // number arrives split across two of the message's words, low half
                            // first.
                            val low = event.xclient.data.l[2].toULong() and LOW_HALF
                            val high = event.xclient.data.l[3].toULong() and LOW_HALF
                            sync.requested(((high shl 32) or low).toLong())
                        }

                        deleteWindow -> closed = true
                    }
                }
            }

            DestroyNotify -> closed = true

            // A grab or an ungrab moves focus too, and moves it back; only a real change of
            // which window has the keyboard is taken as one.
            FocusIn -> if (event.xfocus.mode == NotifyNormal || event.xfocus.mode == NotifyWhileGrabbed) focused = true
            FocusOut -> if (event.xfocus.mode == NotifyNormal || event.xfocus.mode == NotifyWhileGrabbed) focused = false
        }
    }

    /**
     * One key, as both a key and as whatever it types.
     *
     * Both, because Compose reads the two separately: the key itself is what moves a caret or
     * dismisses a sheet, and the character beside it is what a text field types. A key that has
     * no character carries none, which is most of the keys in the table.
     *
     * What it types is asked of the input method when there is one, and then it is UTF-8 and
     * can be a Hangul syllable or a word an input method committed. With none it is what
     * `XLookupString` answers, which is Latin-1. Which of the two events a key becomes is
     * [keyEventsFor]'s decision, and it is the same one either way.
     */
    private fun readKey(event: XEvent): List<WindowEvent> {
        val press = event.type == KeyPress
        val keysym = XLookupKeysym(event.xkey.ptr, 0)
        val typed = if (!press) "" else xim?.lookup(event) ?: latinText(event)
        return keyEventsFor(press, event.xkey.state, keysym, typed)
    }

    /** What the key types without an input method: Latin-1, as `XLookupString` answers. */
    private fun latinText(event: XEvent): String = memScoped {
        val bytes = allocArray<ByteVar>(KEY_TEXT_BYTES)
        val count = XLookupString(event.xkey.ptr, bytes, KEY_TEXT_BYTES, null, null)
        buildString {
            for (index in 0 until count) append((bytes[index].toInt() and 0xFF).toChar())
        }
    }

    /**
     * Gives the input method the keyboard when a field wants text and the window has it, and
     * tells it where the caret is so that its candidate window follows.
     *
     * Every turn, after the frame, because that is when the field has been placed and its
     * caret can be asked for. Told only when it moved: the spot is a request to the server.
     */
    private fun updateInputMethod() {
        val method = xim ?: return
        val wanted = focused && textInput.isActive
        method.focus(wanted)
        if (!wanted) return
        val spot = candidateSpot(inputRequest?.focusedRectInRoot?.invoke(), DENSITY) ?: return
        if (spot == lastSpot) return
        lastSpot = spot
        method.spot(spot.first, spot.second)
    }

    /**
     * Ends whatever the input method is composing where it stands, keeping what was typed.
     */
    private fun finishComposition() {
        val method = xim ?: return
        if (!ime.composing) return
        val finished = method.reset()
        if (finished.isNotEmpty()) ime.commit(finished) else ime.preeditDone()
    }

    /**
     * Joins the accessibility bus, so that a screen reader can read this window.
     *
     * Called once, before the loop starts, by the entry point that knows the application's name.
     * Where there is no accessibility bus this does nothing, and the window carries on; the
     * standard switch for turning the bridge off, `NO_AT_BRIDGE`, is honoured.
     */
    fun startAccessibility(applicationName: String) {
        if (accessibility != null || System.getenv("NO_AT_BRIDGE") == "1") return
        val actions = object : AtspiActions {
            override fun click(id: Int) = atspiSource.click(id)
            override fun focus(id: Int) = atspiSource.focus(id)
            override fun windowOrigin(): Pair<Int, Int> = this@LinuxWindow.originOnScreen()
        }
        val bridge = AtspiBridge(
            openSession = { PosixBusConnection.open() },
            openAccessibility = { address -> PosixBusConnection.open(address) },
            userId = platform.posix.getuid().toLong(),
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

    /** Where the window's top left corner is on the screen. */
    private fun originOnScreen(): Pair<Int, Int> = memScoped {
        val x = alloc<IntVar>()
        val y = alloc<IntVar>()
        val child = alloc<ULongVar>()
        XTranslateCoordinates(
            display, window, XRootWindow(display, XDefaultScreen(display)), 0, 0, x.ptr, y.ptr, child.ptr,
        )
        x.value to y.value
    }

    /**
     * Sets the shape of the pointer over the window.
     *
     * The scene asks on every crossing, and a pointer moving across a row of links asks for the
     * hand it already has. Answering that with a request to the server would be traffic on the
     * thread the frames are drawn from.
     */
    private fun setCursor(shape: Int) {
        if (closed || shape == cursorShape) return
        val wanted = if (shape in cursors.indices) shape else CURSOR_ARROW
        if (cursors[wanted] == 0L) {
            cursors[wanted] = XCreateFontCursor(display, cursorFont(wanted).convert()).toLong()
        }
        XDefineCursor(display, window, cursors[wanted].toULong())
        cursorShape = wanted
        XFlush(display)
    }

    /** Tells the window manager that the drawing it was waiting for exists. */
    private fun setCounter(value: Long) {
        if (syncCounter == 0uL) return
        XSyncSetCounter(
            display,
            syncCounter,
            cValue<XSyncValue> {
                hi = (value ushr 32).toInt()
                lo = (value and LOW_HALF.toLong()).toUInt()
            },
        )
        // Out to the server, or the manager is still waiting on a number this process has
        // already set and the window stops moving with the hand dragging it.
        XFlush(display)
    }

    private fun close() {
        // Before the scene closes, so that nothing arriving between the two asks a scene that
        // has gone to draw into a context that has gone with it.
        closed = true
        accessibility?.close()
        accessibility = null
        xim?.close()
        xim = null
        scene.close()
        surface.close()
        glXMakeCurrent(display, 0uL, null)
        glXDestroyContext(display, context)
        XDestroyWindow(display, window)
        XFreeColormap(display, colormap)
        XCloseDisplay(display)
    }

    companion object {
        /** What the window asks the server to tell it about. */
        private const val EVENT_MASK = ExposureMask or StructureNotifyMask or PointerMotionMask or
            ButtonPressMask or ButtonReleaseMask or KeyPressMask or KeyReleaseMask or FocusChangeMask

        /** `_NET_ACTIVE_WINDOW`'s source indication for a pager, which the user drives. */
        private const val SOURCE_PAGER = 2L


        /**
         * Opens a window, or answers null where this machine has no display server to open one on.
         *
         * Null rather than a throw: a renderer started with no `DISPLAY`, or on a server with no
         * double buffered visual, has nothing to say beyond that, and a Kotlin exception must not
         * be allowed to reach the C entry point that called in.
         */
        fun open(title: String, width: Int, height: Int): LinuxWindow? {
            val display = XOpenDisplay(null) ?: return null
            val screen = XDefaultScreen(display)
            // Terminated by zero, which is what X's `None` is and what a binding cannot carry
            // as a name: the macro is a cast.
            val visual = glXChooseVisual(
                display,
                screen,
                cValuesOf(
                    GLX_RGBA, GLX_DOUBLEBUFFER,
                    GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8,
                    0,
                ),
            )
            if (visual == null) {
                XCloseDisplay(display)
                return null
            }
            val colormap = XCreateColormap(
                display,
                XRootWindow(display, screen),
                visual.pointed.visual,
                AllocNone,
            )
            val window = memScoped {
                val settings = alloc<XSetWindowAttributes>()
                settings.colormap = colormap
                settings.event_mask = EVENT_MASK
                XCreateWindow(
                    display,
                    XRootWindow(display, screen),
                    0, 0,
                    width.convert(), height.convert(),
                    0u,
                    visual.pointed.depth,
                    InputOutput.convert(),
                    visual.pointed.visual,
                    (CWColormap or CWEventMask).convert(),
                    settings.ptr,
                )
            }
            val context = glXCreateContext(display, visual, null, 1)
            XFree(visual)
            if (window == 0uL || context == null || glXMakeCurrent(display, window, context) == 0) {
                if (context != null) glXDestroyContext(display, context)
                if (window != 0uL) XDestroyWindow(display, window)
                XFreeColormap(display, colormap)
                XCloseDisplay(display)
                return null
            }

            val deleteWindow = XInternAtom(display, "WM_DELETE_WINDOW", 0)
            val protocolsAtom = XInternAtom(display, "WM_PROTOCOLS", 0)
            val syncRequest = XInternAtom(display, "_NET_WM_SYNC_REQUEST", 0)
            val syncCounter = createSyncCounter(display, window)

            // Offered to the window manager before the window is mapped, because that is when the
            // manager reads what a window can do. A manager that does not offer the counter is not
            // an error and nothing here depends on having one.
            memScoped {
                val protocols = allocArray<ULongVar>(2)
                protocols[0] = deleteWindow
                var count = 1
                if (syncCounter != 0uL) {
                    protocols[1] = syncRequest
                    count = 2
                }
                XSetWMProtocols(display, window, protocols, count)
            }
            XStoreName(display, window, title)
            XMapWindow(display, window)
            XFlush(display)

            return LinuxWindow(
                display = display,
                window = window,
                context = context,
                colormap = colormap,
                deleteWindow = deleteWindow,
                protocolsAtom = protocolsAtom,
                syncRequest = syncRequest,
                syncCounter = syncCounter,
                title = title,
                width = width,
                height = height,
            )
        }

        /**
         * A counter for the window manager to hold a resize against, or zero where this server
         * has no sync extension.
         *
         * Zero is not a failure. Without it the frame changes when the manager says so and the
         * drawing arrives when it is ready, which is the lateness this window exists to remove,
         * but a window that still draws is better than one that refuses to open.
         */
        private fun createSyncCounter(display: CPointer<Display>, window: Window): XSyncCounter =
            memScoped {
                val eventBase = alloc<IntVar>()
                val errorBase = alloc<IntVar>()
                val major = alloc<IntVar>()
                val minor = alloc<IntVar>()
                if (XSyncQueryExtension(display, eventBase.ptr, errorBase.ptr) == 0) return 0uL
                if (XSyncInitialize(display, major.ptr, minor.ptr) == 0) return 0uL
                val counter = XSyncCreateCounter(
                    display,
                    cValue<XSyncValue> {
                        hi = 0
                        lo = 0u
                    },
                )
                if (counter == 0uL) return 0uL
                // A property of format 32 is read out of an array of long, whatever a long is on
                // this machine. One entry is the basic protocol; a second would offer the extended
                // one, which asks to be told when each frame was actually shown.
                val id = alloc<LongVar>()
                id.value = counter.toLong()
                XChangeProperty(
                    display,
                    window,
                    XInternAtom(display, "_NET_WM_SYNC_REQUEST_COUNTER", 0),
                    // Asked for by name rather than written as the number 6. The predefined atom
                    // is a cast in a macro, which is not something a Kotlin binding can carry.
                    XInternAtom(display, "CARDINAL", 0),
                    32,
                    PropModeReplace,
                    id.ptr.reinterpret(),
                    1,
                )
                counter
            }

        private fun monotonicNanos(): Long = memScoped {
            val now = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC, now.ptr)
            now.tv_sec * NANOS_PER_SECOND + now.tv_nsec
        }

        /**
         * How long a turn of the loop is willing to wait for something to happen.
         *
         * A frame at sixty per second. It is a ceiling rather than a pace: anything arriving
         * sooner ends the wait, and a resize is drawn inside it rather than after it.
         */
        private const val FRAME_MILLISECONDS = 16

        /**
         * How many pixels go to a point.
         *
         * One, because X11 has no per-window scale to ask for: what a desktop does about a dense
         * display is a number in its own settings, and every toolkit reads a different one. The
         * native image window answers the same, so this is not a difference between the two.
         */
        private const val DENSITY = 1.0f

        private const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * A mask for the low 32 bits: the manager splits the counter value across two words.
         */
        private const val LOW_HALF = 0xFFFFFFFFuL

        /** As much of one keystroke's text as XLookupString is given room for. */
        private const val KEY_TEXT_BYTES = 32

    }
}
