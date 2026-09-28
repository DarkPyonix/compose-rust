@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dioxus.compose.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import dioxus.compose.runtime.WindowCaption
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.Foundation.NSPointInRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Canvas
import androidx.compose.ui.input.pointer.PointerIcon
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowZoomButton
import platform.AppKit.NSViewLayerContentsRedrawDuringViewResize
import platform.CoreGraphics.CGSize
import platform.Foundation.NSProcessInfo
import platform.QuartzCore.CALayer
import platform.QuartzCore.CALayerDelegateProtocol
import platform.AppKit.NSCursor
import platform.AppKit.NSDragOperation
import platform.AppKit.NSDragOperationCopy
import platform.AppKit.NSDragOperationNone
import platform.AppKit.NSDraggingDestinationProtocol
import platform.AppKit.NSDraggingInfoProtocol
import platform.AppKit.NSFilenamesPboardType
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingAssumeInside
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseEnteredAndExited
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSTextInputClientProtocol
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNotFound
import platform.Foundation.NSRange
import platform.Foundation.NSRangePointer
import platform.Foundation.string
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSAccessibilityButtonRole
import platform.AppKit.NSAccessibilityCheckBoxRole
import platform.AppKit.NSAccessibilityElement
import platform.AppKit.NSAccessibilityGroupRole
import platform.AppKit.NSAccessibilityImageRole
import platform.AppKit.NSAccessibilityStaticTextRole
import platform.AppKit.NSAccessibilityTextFieldRole
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * A window of this renderer's own, rather than the one Compose opens for this platform.
 *
 * Compose's is smaller than this by a little and misses one thing entirely: nothing it
 * opens tells a screen reader what is in it. Its window publishes its three title bar
 * buttons and its title, and every control the application drew is invisible. That is not
 * an oversight in one place that could be worked around from outside, because the tree a
 * reader wants arrives through the scene's platform context, and a window that builds its
 * own leaves no way in.
 *
 * So the scene is built here, with a context that listens. Everything else is what
 * Compose's own window does and is kept close to it deliberately.
 */
internal class MacosWindow(private val name: String, width: Int, height: Int) {
    private var measured = IntSize(width, height)
    private val components = DefaultArchitectureComponentsOwner()
    /**
     * Whether this window is the one being used.
     *
     * Read rather than assumed, because a material that keeps showing through after the
     * window has stopped being yours is the only thing on the screen that does not sink.
     * Asked of the window each time rather than cached: the notifications that would keep
     * a cache honest are two more observers to take down, and this is read a handful of
     * times per frame.
     */
    /**
     * Whether this window is the one being used.
     *
     * Snapshot state rather than a call to `isKeyWindow`, and that is the whole of it.
     * A plain getter is read once during composition and never again: nothing invalidates
     * when the answer changes, so the window would flatten only if something else happened
     * to redraw it. Written from the two overrides below, which is where AppKit says so.
     */
    // Named around `NSWindow.keyWindow`, which this would otherwise shadow inside the
    // window's own subclass: an assignment there would silently mean the platform's
    // read only property and refuse to compile, which is what it did.
    private var isTheKeyWindow by mutableStateOf(true)

    private val windowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean get() = isTheKeyWindow
        override val containerSize: IntSize get() = measured
    }
    private val metal = MetalSurface()

    // What the window says about itself, kept in step with the scene by the listener the
    // context carries. Pushed on change rather than asked for.
    private val semantics = NativeSemantics { elements -> describeToReader(elements) }

    /** Where committed and composing text goes. */
    private val textInput = NativeTextInput()

    /** Copy, paste and the rest, as the system's own menu draws them. */
    private val textToolbar = MacosTextToolbar { view }

    private val platformContext: PlatformContext =
        object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo get() = this@MacosWindow.windowInfo
            override val architectureComponentsOwner get() = components
            override val semanticsOwnerListener get() = semantics
            override suspend fun startInputMethod(
                request: PlatformTextInputMethodRequest,
            ): Nothing = textInput.run(request)

            /**
             * The shape the pointer takes over whatever it is on.
             *
             * Compose names a few shapes and leaves the rest to the platform. One it does
             * not name becomes the arrow, which is what a pointer over something
             * unremarkable looks like anyway.
             */
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                when (pointerIcon) {
                    PointerIcon.Hand -> NSCursor.pointingHandCursor
                    PointerIcon.Text -> NSCursor.IBeamCursor
                    PointerIcon.Crosshair -> NSCursor.crosshairCursor
                    else -> NSCursor.arrowCursor
                }.set()
            }

            /** What a selection offers when it is asked. */
            override val textToolbar get() = this@MacosWindow.textToolbar

            // Said so that a scene drawing a window with something showing through it
            // clears to nothing rather than to a colour.
            override val isWindowTransparent: Boolean
                get() = !window.isOpaque()
        }

    private val scene = CanvasLayersComposeScene(
        coroutineContext = Dispatchers.Main,
        platformContext = platformContext,
        // Marked rather than drawn. What asks for a frame here is the composition, which
        // can do so in the middle of one, and AppKit draws a view that needs it before the
        // next refresh anyway. Drawing from here as well would draw twice.
        invalidate = { view.needsDisplay = true },
    )

    /**
     * One frame, drawn where AppKit asked for it.
     *
     * The whole of the reason this window does not use skiko's layer: this runs inside the
     * view's own display, so during a drag of the window's edge the drawing and the frame
     * the window server has already moved are committed together.
     */
    private fun paintFrame(canvas: Canvas, widthInPixels: Int, heightInPixels: Int) {
        val size = IntSize(widthInPixels, heightInPixels)
        measured = size
        scene.size = size
        scene.render(
            canvas.asComposeCanvas(),
            (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong(),
        )
        // After the drawing, because that is when what is in the window has been placed
        // and can say where it is. Asked before, every control answers with an empty
        // rectangle and a reader finds the screen stacked in one corner.
        if (readerIsListening) semantics.pushIfChanged(afterDrawing = true)
    }

    /** Whether anything has ever asked this window what is in it. */
    private var readerIsListening = false

    /**
     * The strip the window's own buttons sit in, for whatever draws across the top of the
     * window to step its content clear of.
     *
     * Content runs to the top of the window here, which is the point of it, and a control
     * put where the close, minimise and zoom buttons are would leave both unusable. It
     * happened: every sample drew its heading straight through them.
     *
     * Measured from the window rather than written down as a number. The height is the
     * difference between the window's frame and the part of it below the bar, and the
     * width is where the last of the three buttons ends, with the gap in front of the
     * first mirrored after the last. Both follow the system that way rather than drifting
     * from it the next time Apple changes them.
     */
    val caption = mutableStateOf(WindowCaption.None)

    private fun measureCaption() {
        val scale = 1.0
        val height = window.frame.useContents { size.height } -
            window.contentLayoutRect.useContents { size.height }
        val close = window.standardWindowButton(NSWindowCloseButton)
        val zoom = window.standardWindowButton(NSWindowZoomButton)
        val width = if (close == null || zoom == null) 0.0 else {
            val leading = close.frame.useContents { origin.x }
            zoom.frame.useContents { origin.x + size.width } + leading
        }
        caption.value = WindowCaption(
            height = (height * scale).dp,
            // The platform's own, and this platform puts them at the leading edge.
            buttonsWidth = (width * scale).dp,
            buttonsAtStart = true,
        )
    }

    val window = object : NSWindow(
        contentRect = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble()),
        styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskMiniaturizable or
            NSWindowStyleMaskClosable or NSWindowStyleMaskResizable or
            // The screen reaches the top of the window rather than starting under a bar
            // of the system's. The bar is still there and still the system's, which is
            // what keeps the three buttons and the drag and the double click to zoom;
            // it is see-through, and what shows through is the application.
            NSWindowStyleMaskFullSizeContentView,
        backing = NSBackingStoreBuffered,
        defer = true,
    ) {
        override fun canBecomeKeyWindow() = true
        override fun canBecomeMainWindow() = true

        // Where the flattening comes from. A material in a window that has stopped being
        // the one you are using goes opaque, the way every material the system draws does.
        override fun becomeKeyWindow() {
            super.becomeKeyWindow()
            isTheKeyWindow = true
            view.needsDisplay = true
        }

        override fun resignKeyWindow() {
            super.resignKeyWindow()
            isTheKeyWindow = false
            view.needsDisplay = true
        }
    }


    private val view: NSView = object : NSView(window.frame), CALayerDelegateProtocol, NSTextInputClientProtocol,
        NSDraggingDestinationProtocol {
        private var tracking: NSTrackingArea? = null

        // Files let go over the window. Compose's own drag and drop is declared and never
        // filled in on this platform, so the window takes the drop and the screen hears
        // about it through the Host event every platform sends.
        override fun draggingEntered(sender: NSDraggingInfoProtocol): NSDragOperation =
            if (FileDrop.carriesFiles(sender.draggingPasteboard)) NSDragOperationCopy
            else NSDragOperationNone

        override fun draggingUpdated(sender: NSDraggingInfoProtocol): NSDragOperation =
            draggingEntered(sender)

        override fun performDragOperation(sender: NSDraggingInfoProtocol): Boolean {
            val paths = FileDrop.paths(sender.draggingPasteboard)
            if (paths.isEmpty()) return false
            FileDrop.dropped.value = paths
            return true
        }

        // What is on screen and not yet chosen. Kept so that the input method can be told how
        // long it is, which is how it draws the underline under what it is composing.
        private var marked: String = ""

        override fun insertText(string: Any, replacementRange: CValue<NSRange>) {
            marked = ""
            textInput.commit(string.asText())
        }

        override fun setMarkedText(
            string: Any,
            selectedRange: CValue<NSRange>,
            replacementRange: CValue<NSRange>,
        ) {
            marked = string.asText()
            textInput.compose(marked)
        }

        override fun unmarkText() {
            marked = ""
            textInput.compose("")
        }

        override fun hasMarkedText(): Boolean = marked.isNotEmpty()

        override fun markedRange(): CValue<NSRange> =
            if (marked.isEmpty()) NSMakeRange(NSNotFound.toULong(), 0u)
            else NSMakeRange(0u, marked.length.toULong())

        // The field's own selection is Compose's and is not read back out: what an input
        // method does with this is place its candidate window, and the caret rectangle below
        // is the better answer for that.
        override fun selectedRange(): CValue<NSRange> = NSMakeRange(NSNotFound.toULong(), 0u)

        override fun validAttributesForMarkedText(): List<*> = emptyList<Any>()

        override fun attributedSubstringForProposedRange(
            range: CValue<NSRange>,
            actualRange: NSRangePointer?,
        ): NSAttributedString? = null

        /**
         * Where the candidate window goes.
         *
         * At the caret would be better and Compose does not offer it here, so this is the
         * window's own origin: the candidates appear at a fixed place rather than following
         * the text. Wrong-looking rather than wrong, and the alternative is no candidates.
         */
        override fun firstRectForCharacterRange(
            range: CValue<NSRange>,
            actualRange: NSRangePointer?,
        ): CValue<CGRect> {
            val origin = window?.frame?.useContents { CGRectMake(origin.x, origin.y, 0.0, 0.0) }
            return origin ?: CGRectMake(0.0, 0.0, 0.0, 0.0)
        }

        override fun characterIndexForPoint(point: CValue<CGPoint>): ULong =
            NSNotFound.toULong()

        override fun doCommandBySelector(selector: CPointer<out CPointed>?) {
            // Movement and deletion are Compose's, and it has already seen the key event that
            // produced this. Doing it again here would do it twice.
        }

        // An input method hands back either a string or an attributed one, and only the
        // characters are wanted either way.
        private fun Any.asText(): String = when (this) {
            is NSAttributedString -> string
            else -> toString()
        }

        // The view's own layer is the one that is drawn into, rather than a layer of
        // skiko's put on top. That is what lets a frame be drawn inside the view's display
        // and committed with whatever else the layer tree is committing.
        override fun makeBackingLayer(): CALayer = metal.layer

        override fun wantsUpdateLayer() = true

        /**
         * Drawn here, which AppKit calls when the view needs displaying.
         *
         * During a drag of the window's edge this is reached from the resize itself, so the
         * drawing and the frame the window server has already moved reach the screen in the
         * same commit. That is the difference between a window that is attached to the
         * pointer and one that trails it by a refresh.
         */
        /**
         * Drawn here, which is where a layer asks its delegate for its contents.
         *
         * A view that is backed by a layer of its own kind is asked this way rather than
         * through the view's own drawing, and AppKit has already made the view the layer's
         * delegate by the time anything needs displaying.
         */
        override fun displayLayer(layer: CALayer) = updateLayer()

        override fun updateLayer() {
            val scale = window?.backingScaleFactor ?: 1.0
            frame.useContents { metal.resize(size.width, size.height, scale) }
            metal.draw(::paintFrame)
        }

        // Redrawn when it is resized rather than stretched, which is what a layer does with
        // its old contents by default and is exactly the trailing edge this window is for.
        override fun setFrameSize(newSize: CValue<CGSize>) {
            super.setFrameSize(newSize)
            // A window that went full screen has no buttons to avoid, and one that came
            // back has them again, and both arrive as a change of size.
            measureCaption()
            // Drawn here rather than marked. Marking leaves the drawing to the display
            // cycle, and because this layer presents with the transaction the window's own
            // frame then waits for it: the edge still never comes away from the drawing,
            // but it advances in jumps of a hundred pixels instead of following the hand.
            if (window != null) updateLayer()
        }
        override fun acceptsFirstResponder() = true

        // The click is ours wherever it lands inside us.
        override fun hitTest(aPoint: CValue<CGPoint>): NSView? =
            if (NSPointInRect(convertPoint(aPoint, fromView = superview), bounds)) this
            else null

        // Asked only when something is reading the screen, which is what this is for.
        // Describing the tree costs about a third of a frame, and until now it was paid on
        // every frame by everyone, whether or not anyone was listening. A drag of the
        // window's edge is where that showed: every control moves on every frame, so the
        // comparison that usually makes it free always failed.
        override fun accessibilityChildren(): List<*>? {
            readerIsListening = true
            semantics.pushIfChanged(afterDrawing = true)
            return super.accessibilityChildren()
        }

        override fun acceptsFirstMouse(event: NSEvent?) = true
        override fun viewWillMoveToWindow(newWindow: NSWindow?) = updateTrackingAreas()

        override fun updateTrackingAreas() {
            tracking?.let { removeTrackingArea(it) }
            val area = NSTrackingArea(
                rect = bounds,
                options = NSTrackingActiveAlways or NSTrackingMouseEnteredAndExited or
                    NSTrackingMouseMoved or NSTrackingActiveInKeyWindow or
                    NSTrackingAssumeInside or NSTrackingInVisibleRect,
                owner = this,
                userInfo = null,
            )
            tracking = area
            addTrackingArea(area)
        }

        override fun mouseDown(event: NSEvent) {
            // Whatever was being composed is finished where it was. The caret is about to
            // move and the input method would otherwise go on building a syllable at a
            // place the reader has left, which shows up as the letters coming apart.
            if (hasMarkedText()) inputContext?.discardMarkedText()
            send(event, PointerEventType.Press, PointerButton.Primary)
        }

        override fun mouseUp(event: NSEvent) =
            send(event, PointerEventType.Release, PointerButton.Primary)

        override fun rightMouseDown(event: NSEvent) =
            send(event, PointerEventType.Press, PointerButton.Secondary)

        override fun rightMouseUp(event: NSEvent) =
            send(event, PointerEventType.Release, PointerButton.Secondary)

        override fun mouseMoved(event: NSEvent) = send(event, PointerEventType.Move)

        override fun mouseDragged(event: NSEvent) = send(event, PointerEventType.Move)

        override fun scrollWheel(event: NSEvent) = send(event, PointerEventType.Scroll)

        override fun keyDown(event: NSEvent) {
            // Both, and in this order. The scene reads the key as a key: arrows, Enter,
            // backspace and whatever shortcut the screen has bound. The input method
            // reads the same key as text, and hands back a letter or a syllable being
            // built through the methods above.
            //
            // Handed to the input context rather than interpreted. Interpreting also
            // turns keys into editing commands for a text system this window does not
            // have, and the keys have already gone to the scene, which has its own.
            scene.sendKeyEvent(event.compose(KeyEventType.KeyDown))
            inputContext?.handleEvent(event)
        }

        override fun keyUp(event: NSEvent) {
            if (!scene.sendKeyEvent(event.compose(KeyEventType.KeyUp))) super.keyUp(event)
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        // Carried but not drawn. A window with no title is listed as nothing in the
        // switcher and in Mission Control, so it is set; it is hidden because the
        // application draws its own heading where the bar would have written it.
        window.setTitle(name)
        window.titlebarAppearsTransparent = true
        window.titleVisibility = NSWindowTitleHidden
        window.contentView = view

        // Said before the view is asked for its layer, because the answer is ours and a
        // view that was not told to have one never asks.
        view.wantsLayer = true
        view.layerContentsRedrawPolicy = NSViewLayerContentsRedrawDuringViewResize

        window.center()
        window.makeKeyAndOrderFront(null)
        window.makeFirstResponder(view)
        // Said so that the window is offered drags at all: a view that has registered for
        // nothing is never asked.
        view.registerForDraggedTypes(listOf(NSFilenamesPboardType))

        // After the window is on screen, and in this order: the density is the screen's
        // and is not known until the window is on one, and a scene given content before
        // it has a size composes into nothing and draws a blank window.
        scene.density = Density(window.backingScaleFactor.toFloat())
        scene.setContent(content)

        // Said, rather than assumed. Compose composes for something that is alive, and a
        // scene nobody has resumed stays where it started, which is a window that opens
        // and never draws.
        measureCaption()
        components.enableSavedStateHandles()
        components.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * Hands the reader what the scene last said, as elements it can ask about.
     *
     * Replaced whole rather than edited. The tree arrives whole, a reader asks for it on
     * its own thread at moments nobody chose, and one list swapped for another is a thing
     * that has either happened or not.
     *
     * The frames are the scene's, which count down from the top left of the window; the
     * reader's count up from the bottom left of the screen, so each one is turned twice.
     */
    private fun describeToReader(elements: List<AccessibleElement>) {
        val scale = window.backingScaleFactor
        val built = elements.map { element ->
            val made = NSAccessibilityElement.accessibilityElementWithRole(
                role = element.role.readerRole,
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                label = element.label,
                parent = view,
            )
            val inView = CGRectMake(
                x = element.x.toDouble() / scale,
                y = element.y.toDouble() / scale,
                width = element.width.toDouble() / scale,
                height = element.height.toDouble() / scale,
            )
            val flipped = view.frame.useContents {
                CGRectMake(
                    x = inView.useContents { origin.x },
                    y = size.height - inView.useContents { origin.y + size.height },
                    width = inView.useContents { size.width },
                    height = inView.useContents { size.height },
                )
            }
            (made as NSAccessibilityElement).setAccessibilityFrame(
                view.window?.convertRectToScreen(view.convertRect(flipped, toView = null))
                    ?: flipped,
            )
            made
        }
        view.setAccessibilityChildren(built)
    }

    private fun send(event: NSEvent, kind: PointerEventType, button: PointerButton? = null) {
        scene.sendPointerEvent(
            eventType = kind,
            position = event.offsetInView,
            scrollDelta = Offset(event.deltaX.toFloat(), event.deltaY.toFloat()),
            nativeEvent = event,
            button = button,
        )
    }

    // The window's coordinates count up from the bottom and the scene's count down from
    // the top, so one is the other subtracted from the height. In pixels on both sides:
    // the layer is asked to draw at the screen's density and the scene is told that size,
    // so nothing here divides by it.
    private val NSEvent.offsetInView: Offset
        get() {
            val where = locationInWindow.useContents { Offset(x.toFloat(), y.toFloat()) }
            val height = view.frame.useContents { size.height.toFloat() }
            val scale = view.window?.backingScaleFactor?.toFloat() ?: 1f
            return Offset(where.x * scale, (height - where.y) * scale)
        }

    // Built from parts rather than converted: what converts a platform key event is
    // internal to Compose, and the parts are the same ones the native image path builds
    // from because it has no platform event to convert either.
    private fun NSEvent.compose(type: KeyEventType): KeyEvent =
        KeyEvent(
            key = composeKey(keyCode.toInt()),
            type = type,
            // Nothing, deliberately. This platform reads a key as typed text when it
            // carries a printable character, and the input method is already putting
            // that text in through `insertText`: sending it here as well types every
            // letter twice and pushes a syllable along as it is being built. What the
            // scene is for here is the keys that are not text, and those carry no
            // printable character anyway.
            codePoint = 0,
            isAltPressed = modifierFlags and NSEventModifierFlagOption != 0uL,
            isCtrlPressed = modifierFlags and NSEventModifierFlagControl != 0uL,
            isMetaPressed = modifierFlags and NSEventModifierFlagCommand != 0uL,
            isShiftPressed = modifierFlags and NSEventModifierFlagShift != 0uL,
        )
}

/** What a reader calls the kind of control this is. */
private val Int.readerRole: String
    get() = when (this) {
        ElementRole.BUTTON -> NSAccessibilityButtonRole
        ElementRole.TEXT -> NSAccessibilityStaticTextRole
        ElementRole.FIELD -> NSAccessibilityTextFieldRole
        ElementRole.CHECKBOX -> NSAccessibilityCheckBoxRole
        ElementRole.IMAGE -> NSAccessibilityImageRole
        else -> NSAccessibilityGroupRole
    } ?: NSAccessibilityGroupRole ?: "AXGroup"
