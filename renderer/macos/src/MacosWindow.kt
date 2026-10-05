@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import dev.darkpyonix.composerust.runtime.WindowCaption
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
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import java.lang.System
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
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSNotificationCenter
import platform.AppKit.NSMenuDidEndTrackingNotification
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.Chrome
import platform.AppKit.NSWindowTitleVisible
import platform.AppKit.NSWindowToolbarStyle
import platform.AppKit.NSToolbar
import platform.AppKit.NSWindowZoomButton
import platform.AppKit.NSViewLayerContentsRedrawDuringViewResize
import platform.CoreGraphics.CGSize
import platform.Foundation.NSProcessInfo
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceNow
import platform.QuartzCore.CALayer
import platform.QuartzCore.CALayerDelegateProtocol
import platform.AppKit.NSColor
import platform.AppKit.NSCursor
import platform.AppKit.NSMenu
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewWidthSizable
import platform.AppKit.NSVisualEffectBlendingMode
import platform.AppKit.NSVisualEffectMaterialUnderWindowBackground
import platform.AppKit.NSVisualEffectState
import platform.AppKit.NSVisualEffectView
import platform.AppKit.NSDragOperation
import platform.AppKit.NSDragOperationCopy
import platform.AppKit.NSDragOperationNone
import platform.AppKit.NSDraggingDestinationProtocol
import platform.AppKit.NSDraggingInfoProtocol
import platform.AppKit.NSFilenamesPboardType
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagControl
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
import platform.Foundation.NSStringFromSelector
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
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import platform.Foundation.valueForKey
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
internal class MacosWindow(
    private val name: String,
    width: Int,
    height: Int,
    /**
     * How the title bar is built: the same answer the native image's window is given, so
     * the two have the same corners and their content starts at the same height.
     */
    private val chrome: MacosWindowChrome = MacosWindowChrome.of(Chrome.Modern, TitleBar.Normal),
) {
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
        val close = window.standardWindowButton(NSWindowCloseButton)
        val zoom = window.standardWindowButton(NSWindowZoomButton)
        // Null while the window is between sizes; the last reading stands until then.
        caption.value = macosWindowCaption(
            chrome = chrome,
            windowHeight = window.frame.useContents { size.height },
            contentLayoutHeight = window.contentLayoutRect.useContents { size.height },
            closeMinX = close?.frame?.useContents { origin.x },
            zoomMaxX = zoom?.frame?.useContents { origin.x + size.width },
            cornerRadius = systemCornerRadius(window),
        ) ?: return
    }

    val window = object : NSWindow(
        contentRect = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble()),
        styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskMiniaturizable or
            NSWindowStyleMaskClosable or NSWindowStyleMaskResizable or
            // The screen reaches the top of the window rather than starting under a bar
            // of the system's. The bar is still there and still the system's, which is
            // what keeps the three buttons and the drag and the double click to zoom;
            // it is see-through, and what shows through is the application.
            (if (chrome.fullSizeContentView) NSWindowStyleMaskFullSizeContentView else 0uL),
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


    /**
     * What the window is made of, behind everything the application draws.
     *
     * A window on this platform has nothing behind it unless something is put there, and
     * glass over nothing is a tinted rectangle: a white surface at seven tenths over a
     * white page composites to white exactly, so the sidebar could only ever be told from
     * the page by its shadow. The whole of the material is the desktop showing through it.
     *
     * `UnderWindowBackground` rather than a named material like `Sidebar`, because what
     * this is behind is the whole window and the application decides which parts of it let
     * the material through. It follows the window's active state, which is what makes
     * everything drawn on it flatten together when the window stops being the one in use.
     */
    // A subclass for one reason: it is the content view, so it is the view AppKit sizes
    // when the window's frame changes, and the title bar's height is measured again then.
    // Made at the content's size, at the origin. The window's frame is a rectangle on the
    // screen, and a view built from it carries the window's screen position as its own
    // offset inside its parent.
    private val backdrop = object : NSVisualEffectView(contentBounds(width, height)) {
        override fun setFrameSize(newSize: CValue<CGSize>) {
            super.setFrameSize(newSize)
            measureCaption()
        }
    }.also {
        it.material = NSVisualEffectMaterialUnderWindowBackground
        it.blendingMode = NSVisualEffectBlendingMode.NSVisualEffectBlendingModeBehindWindow
        it.state = NSVisualEffectState.NSVisualEffectStateFollowsWindowActiveState
        it.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
    }

    private val view: NSView = object : NSView(contentBounds(width, height)), CALayerDelegateProtocol, NSTextInputClientProtocol,
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
            // An input method can hand over a Control letter's own character, U+0001 for
            // Control A, as text. It is never meant as text and a field draws it as a box,
            // so it is taken out here, as the native image's window takes it out.
            val text = string.asText()
            val inserted = insertableText(text)
            KeyLog.insertText(text, inserted)
            if (inserted.isNotEmpty()) textInput.commit(inserted)
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
            // A command that came from the key being handled is Compose's already: the
            // scene was given the key, and Compose's macOS mapping gives Control A the
            // meaning `moveToBeginningOfLine:` has. Doing it again here would do it twice.
            // One that came from anywhere else is turned into the key that means it.
            val name = NSStringFromSelector(selector)
            if (inKeyDown) {
                KeyLog.line("doCommandBySelector $name during-key=1")
                return
            }
            val keys = editingKeyEvents(name)
            KeyLog.command(name, keys != null)
            keys?.forEach { scene.sendKeyEvent(it) }
        }

        // Whether a key is being handed to the input context right now.
        private var inKeyDown = false

        // An input method hands back either a string or an attributed one, and only the
        // characters are wanted either way.
        private fun Any.asText(): String = when (this) {
            is NSAttributedString -> string
            else -> toString()
        }

        // The view's own layer is the one that is drawn into, rather than a layer of
        // skiko's put on top. That is what lets a frame be drawn inside the view's display
        // and committed with whatever else the layer tree is committing.
        // Counting down from the top left as the scene does, the way the native image's
        // view does: the drawing, the pointer and what a reader is told then share one
        // origin, and nothing subtracts from a height that can be the wrong height.
        override fun isFlipped() = true

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
            bounds.useContents { metal.resize(size.width, size.height, scale) }
            ResizeMetrics.frameBegin()
            metal.draw(::paintFrame)
            bounds.useContents {
                ResizeMetrics.frameEnd((size.width * scale).toInt(), (size.height * scale).toInt())
            }
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
            // Control held with the primary button is a right click on this platform.
            val control = event.modifierFlags and NSEventModifierFlagControl != 0uL
            send(
                event,
                PointerEventType.Press,
                if (control) PointerButton.Secondary else PointerButton.Primary,
                systemButton = HeldButtons.PRIMARY,
            )
        }

        override fun mouseUp(event: NSEvent) =
            send(event, PointerEventType.Release, systemButton = HeldButtons.PRIMARY)

        // To the scene only. The menu is Compose's to ask for, through the text context
        // menu provider, and the system draws it; a menu put up here as well was the
        // second of the two that came up together.
        override fun rightMouseDown(event: NSEvent) = send(
            event,
            PointerEventType.Press,
            PointerButton.Secondary,
            systemButton = HeldButtons.SECONDARY,
        )

        override fun rightMouseUp(event: NSEvent) =
            send(event, PointerEventType.Release, systemButton = HeldButtons.SECONDARY)

        override fun mouseMoved(event: NSEvent) = send(event, PointerEventType.Move)

        override fun mouseDragged(event: NSEvent) = send(event, PointerEventType.Move)

        override fun scrollWheel(event: NSEvent) = send(event, PointerEventType.Scroll)

        // None from the view. AppKit asks for one on a right click and on Control held with
        // the pointer, and the menu is Compose's to ask for: answering here as well put a
        // second menu up beside the one Compose asked for.
        override fun menuForEvent(event: NSEvent): NSMenu? = null

        override fun keyDown(event: NSEvent) {
            // Both, and in this order. The scene reads the key as a key: arrows, Enter,
            // backspace and whatever shortcut the screen has bound. The input method
            // reads the same key as text, and hands back a letter or a syllable being
            // built through the methods above.
            //
            // Handed to the input context rather than interpreted. Interpreting also
            // turns keys into editing commands for a text system this window does not
            // have, and the keys have already gone to the scene, which has its own.
            //
            // Not the input context when Command is held: a Command key is a shortcut and
            // types nothing, and an input method shown one can commit what it was
            // composing or answer with the bare letter.
            KeyLog.platform(event.keyCode.toInt(), event.modifierFlags.toLong(), event.characters, down = true)
            val key = event.compose(KeyEventType.KeyDown)
            KeyLog.compose(key, scene.sendKeyEvent(key))
            if (!reachesInputMethod(event.modifierFlags.toLong())) return
            inKeyDown = true
            try {
                inputContext?.handleEvent(event)
            } finally {
                inKeyDown = false
            }
        }

        override fun keyUp(event: NSEvent) {
            KeyLog.platform(event.keyCode.toInt(), event.modifierFlags.toLong(), event.characters, down = false)
            val key = event.compose(KeyEventType.KeyUp)
            val consumed = scene.sendKeyEvent(key)
            KeyLog.compose(key, consumed)
            if (!consumed) super.keyUp(event)
        }

        // The editing shortcuts, claimed before anything that holds key equivalents sees
        // them, as the native image's view claims them from its Edit menu. Every other
        // Command key is left alone.
        override fun performKeyEquivalent(event: NSEvent): Boolean {
            if (window?.firstResponder != this) return super.performKeyEquivalent(event)
            if (!isEditingShortcut(event.keyCode.toInt(), event.modifierFlags.toLong())) {
                return super.performKeyEquivalent(event)
            }
            keyDown(event)
            // AppKit sends no key up for a key held with Command, so it is written here.
            val up = event.compose(KeyEventType.KeyUp)
            KeyLog.compose(up, scene.sendKeyEvent(up))
            return true
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        // Carried but not drawn. A window with no title is listed as nothing in the
        // switcher and in Mission Control, so it is set; it is hidden because the
        // application draws its own heading where the bar would have written it.
        window.setTitle(name)
        applyChrome(window, chrome)
        // The material is the content view and the application draws inside it. The window
        // itself stops being opaque and stops painting a colour, because either one is a
        // sheet of paint laid over the thing this was all for.
        installContent(window, backdrop, view)
        window.opaque = false
        window.backgroundColor = NSColor.clearColor

        // Said before the view is asked for its layer, because the answer is ours and a
        // view that was not told to have one never asks.
        view.wantsLayer = true
        view.layerContentsRedrawPolicy = NSViewLayerContentsRedrawDuringViewResize

        window.center()
        window.makeKeyAndOrderFront(null)
        window.makeFirstResponder(view)
        // Once more on the next turn of the loop. The first time a window is shown the
        // system lays its title bar out after this call returns, and that lay out puts the
        // buttons back in the corner; measured, the only two times they were moved both came
        // before it, so the window came up in the plain mode's position whatever it asked.
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            measureCaption()
        }
        // Said so that the window is offered drags at all: a view that has registered for
        // nothing is never asked.
        view.registerForDraggedTypes(listOf(NSFilenamesPboardType))

        // After the window is on screen, and in this order: the density is the screen's
        // and is not known until the window is on one, and a scene given content before
        // it has a size composes into nothing and draws a blank window.
        // A menu the system showed has closed, and its loop took the release of the button
        // that closed it. The scene is told now rather than on the next event, so the next
        // click is a click and not a second button pressed on a held one.
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSMenuDidEndTrackingNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> releaseStale(pointerNow()) }
        scene.density = Density(window.backingScaleFactor.toFloat())
        scene.setContent(content)

        // Said, rather than assumed. Compose composes for something that is alive, and a
        // scene nobody has resumed stays where it started, which is a window that opens
        // and never draws.
        measureCaption()
        components.enableSavedStateHandles()
        components.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (ResizeMetrics.enabled) {
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 3_000_000_000L),
                platform.darwin.dispatch_get_main_queue(),
            ) { runMetrics() }
        }
    }

    /**
     * DXC_METRICS=1: the scripted drag and its measurements; see [ResizeMetrics].
     *
     * All 100 sizes are set inside this one block, so the run loop does not turn between
     * them. That is the hardest case for anything a frame leaves to an autorelease pool,
     * and it is the case a fast drag of the edge comes closest to.
     */
    private fun runMetrics() {
        val phase = { name: String ->
                val (footprint, resident) = processMemory()
                System.err.println(
                    "compose-rust: metrics phase $name ${ResizeMetrics.summary()} " +
                        "skia_cache_limit_mb=${metal.cacheLimitBytes / 1048576} " +
                        "metal_allocated_mb=${metal.allocatedBytes / 1048576} " +
                        "footprint_mb=${footprint / 1048576} resident_mb=${resident / 1048576}",
                )
                // Where the footprint is, by kind, when one number is not enough to say.
                if (platform.posix.getenv("DXC_METRICS_BREAKDOWN")?.toKString() == "1") {
                    val pid = platform.posix.getpid()
                    platform.posix.fflush(null)
                    platform.posix.system(
                        "echo 'compose-rust: breakdown $name'; /usr/bin/footprint $pid 1>&2; " +
                            "/usr/bin/vmmap --summary $pid 2>/dev/null | " +
                            "grep -E 'IOSurface|IOAccelerator|CoreAnimation|MALLOC|TOTAL|Region' 1>&2",
                    )
                }
        }
        ResizeMetrics.run(
            phase = phase,
            resize = { from, to, steps ->
                // Each size in a pool of its own, as each event of a real drag is: AppKit
                // hands back what a resize made (the material view's backing at the new
                // size among it) autoreleased, and one pool around all 100 kept every one.
                // What the frame itself holds is the surface's to give back, and the pool
                // does not reach it.
                for (step in 1..steps) kotlinx.cinterop.autoreleasepool {
                    val t = step.toDouble() / steps
                    window.setContentSize(
                        platform.Foundation.NSMakeSize(
                            from.first + (to.first - from.first) * t,
                            from.second + (to.second - from.second) * t,
                        ),
                    )
                    window.displayIfNeeded()
                    platform.QuartzCore.CATransaction.flush()
                    turnAsADragDoes()
                }
            },
        )
        // Again two seconds later, with the run loop having turned. Surfaces the window
        // server shared with this process for sizes it has left are given back only once
        // the frames that showed them are off the screen, so a reading taken in the same
        // turn as the last resize counts memory that is already on its way out.
        platform.darwin.dispatch_after(
            platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 2_000_000_000L),
            platform.darwin.dispatch_get_main_queue(),
        ) { phase("settled") }
    }

    /** Physical footprint and resident size of this process, in bytes. */
    /**
     * Lets the run loop turn in the mode a live resize runs it in, for one event's worth
     * of time at 60 events a second, as it does between the events of a real drag.
     *
     * Core Animation hands each size's surfaces to the window server, and learns that the
     * server is done with them through a port the run loop services. Setting 100 sizes in
     * one turn never services it: run 37256866302 held 499 MB of surfaces this process owns
     * and no longer maps, all given back once the loop turned. A drag of the edge turns the
     * loop between events, so the scripted one does too, and its reading is the drag's.
     */
    private fun turnAsADragDoes() {
        val until = platform.Foundation.NSDate(timeIntervalSinceNow = 1.0 / 60)
        val loop = platform.Foundation.NSRunLoop.currentRunLoop
        while (until.timeIntervalSinceNow > 0 &&
            loop.runMode(platform.AppKit.NSEventTrackingRunLoopMode, beforeDate = until)
        ) Unit
    }

    private fun processMemory(): Pair<Long, Long> = kotlinx.cinterop.memScoped {
        val info = alloc<platform.darwin.task_vm_info_data_t>()
        val count = alloc<platform.darwin.mach_msg_type_number_tVar>()
        count.value = (kotlinx.cinterop.sizeOf<platform.darwin.task_vm_info_data_t>() / 4).toUInt()
        // TASK_VM_INFO
        platform.darwin.task_info(
            platform.darwin.mach_task_self_,
            22u,
            info.ptr.reinterpret(),
            count.ptr,
        )
        info.phys_footprint.toLong() to info.resident_size.toLong()
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
            // The view counts down from its top left like the scene does, so the rectangle
            // is already in its coordinates and only the conversion to the screen remains.
            (made as NSAccessibilityElement).setAccessibilityFrame(
                view.window?.convertRectToScreen(view.convertRect(inView, toView = null))
                    ?: inView,
            )
            made
        }
        view.setAccessibilityChildren(built)
    }

    /** What the scene has been told is held, checked against the system before each event. */
    private val held = HeldButtons<PointerButton>()

    /**
     * Sends [kind] to the scene, after a release for any button the scene still believes is
     * down that the system says is not.
     *
     * [systemButton] is the system's number for the button a press or a release is about.
     * A release is sent as the button the press was sent as, which for a click with Control
     * held is the secondary one.
     */
    private fun send(
        event: NSEvent,
        kind: PointerEventType,
        button: PointerButton? = null,
        systemButton: Int? = null,
    ) {
        val position = event.offsetInView
        var sentAs = button
        if (kind == PointerEventType.Release && systemButton != null) {
            sentAs = held.released(systemButton) ?: button
            // A release the scene was never told the press of, or already had released:
            // the menu's loop took the press, or the release was already made up below.
            if (sentAs == null) {
                releaseStale(position)
                return
            }
        }
        releaseStale(position)
        if (kind == PointerEventType.Press && systemButton != null && sentAs != null) {
            held.pressed(systemButton, sentAs)
        }
        scene.sendPointerEvent(
            eventType = kind,
            position = position,
            scrollDelta = Offset(event.deltaX.toFloat(), event.deltaY.toFloat()),
            nativeEvent = event,
            button = sentAs,
        )
    }

    /**
     * Tells the scene that every button it believes is down and the system says is up has
     * come up, at [position].
     *
     * A menu the system shows takes the release of the button that closed it, so without
     * this the scene goes on believing a button is held and no later click completes.
     */
    private fun releaseStale(position: Offset) {
        for (button in held.stale(NSEvent.pressedMouseButtons.toLong())) {
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = position,
                button = button,
            )
        }
    }

    /** Where the pointer is now, in the scene's pixels, for an event that has no event. */
    private fun pointerNow(): Offset {
        val inWindow = window.convertPointFromScreen(NSEvent.mouseLocation)
        return scenePoint(view, inWindow, window.backingScaleFactor)
    }

    private val NSEvent.offsetInView: Offset
        get() = scenePoint(view, locationInWindow, view.window?.backingScaleFactor ?: 1.0)

    // Built from parts rather than converted: what converts a platform key event is
    // internal to Compose, and the parts are the same ones the native image path builds
    // from because it has no platform event to convert either.
    //
    // The code point is nothing, deliberately. This platform reads a key as typed text when
    // it carries a printable character, and the input method is already putting that text
    // in through `insertText`: sending it here as well types every letter twice and pushes
    // a syllable along as it is being built.
    private fun NSEvent.compose(type: KeyEventType): KeyEvent =
        macKeyEvent(keyCode.toInt(), modifierFlags.toLong(), type, codePoint = 0)
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

/** Holds the closure a menu item runs, because a menu item calls a selector on a target. */
private class MenuShortcut(private val run: () -> Unit) : platform.darwin.NSObject() {
    @kotlinx.cinterop.ObjCAction
    fun perform() = run()
}

/** A rectangle the size of the window's content, at the origin of its parent. */
internal fun contentBounds(width: Int, height: Int): CValue<CGRect> =
    NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble())

/**
 * Makes [backdrop] the window's content view and puts [view] over the whole of it.
 *
 * The view takes the backdrop's bounds rather than any size worked out beforehand: the
 * backdrop is sized by the window when it becomes the content view, and the view has to
 * cover exactly that, from its top left corner, or the drawing is shifted off the window
 * and the pointer lands somewhere other than where things are drawn. The native image
 * path gets the same thing by making its view the content view itself.
 */
internal fun installContent(window: NSWindow, backdrop: NSView, view: NSView) {
    window.contentView = backdrop
    view.setFrame(backdrop.bounds)
    view.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
    backdrop.addSubview(view)
}

/**
 * Where a point in the window's coordinates falls in the scene, in pixels.
 *
 * Converted by AppKit from the window to [view], which accounts for wherever the view sits
 * and for its being flipped (the scene view is), then scaled to the screen's density: the layer draws at that
 * density and the scene is told that size, so its coordinates are pixels.
 */
internal fun scenePoint(view: NSView, locationInWindow: CValue<CGPoint>, scale: Double): Offset =
    view.convertPoint(locationInWindow, fromView = null).useContents {
        Offset((x * scale).toFloat(), (y * scale).toFloat())
    }

/**
 * The radius the system gave [window]'s corners, or null where it does not say.
 *
 * AppKit has no public property for it. Asked by key value coding under
 * [MACOS_CORNER_RADIUS_KEY] after checking the window answers it, the same way the native
 * image's window asks in `appkit_window.m`, so a release without it gives no radius rather
 * than an exception and both windows read one number.
 */
internal fun systemCornerRadius(window: NSWindow): Double? {
    if (!window.respondsToSelector(NSSelectorFromString(MACOS_CORNER_RADIUS_KEY))) return null
    return (window.valueForKey(MACOS_CORNER_RADIUS_KEY) as? NSNumber)?.doubleValue
}

/**
 * Builds [window]'s title bar the way [chrome] says, as the native image's window does in
 * `dxc_native_window_open`. The style mask is set where the window is made.
 */
internal fun applyChrome(window: NSWindow, chrome: MacosWindowChrome) {
    window.titlebarAppearsTransparent = chrome.titlebarAppearsTransparent
    window.titleVisibility = if (chrome.titleHidden) NSWindowTitleHidden else NSWindowTitleVisible
    if (chrome.unifiedToolbar) {
        // Empty: it sets the bar's height, which centres the three buttons on the line the
        // bar's own content is drawn on, and gives the window the radius such a window has.
        val toolbar = NSToolbar(identifier = "compose-rust")
        toolbar.showsBaselineSeparator = false
        window.toolbar = toolbar
        window.toolbarStyle = NSWindowToolbarStyle.NSWindowToolbarStyleUnified
    } else {
        window.toolbar = null
    }
}
