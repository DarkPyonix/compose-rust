@file:JvmName("X11FrameCallback")

package dioxus.compose.ui.platform

import org.graalvm.nativeimage.CurrentIsolate
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.graalvm.nativeimage.c.function.CEntryPointLiteral
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.word.WordFactory

// How the X11 window asks for a frame.
//
// The window belongs to C and the frame belongs to the renderer, and this file exists for
// the one moment when the two have to happen together. A window's new size arrives as an
// event, and the display server has moved the frame before the event is read: a frame
// drawn on the next turn of the loop reaches the screen after the edge did, and the strip
// between them is a piece of the window that has been claimed and not painted.
//
// So the drawing has to be reachable from where that event is handled, which is inside C.
// What crosses is a pointer to a function on this side, handed over once when the window
// opens. Nothing else about the frame crosses: the scene, the surface and the decision of
// whether to draw at all stay here.

@CFunction("dxc_native_set_frame_callback")
private external fun setFrameCallback(callback: CFunctionPointer?, thread: IsolateThread?)

/**
 * What draws a frame, for as long as there is a window to draw one into.
 *
 * Null before the window opens and again once it has closed, because a resize that arrives
 * while the scene is being taken down must not reach a scene that has gone.
 */
private var painter: (() -> Boolean)? = null

/**
 * The function the window calls, from wherever it noticed that a frame is owed.
 *
 * Public and named because it is an entry point rather than an ordinary function: it is
 * reached by address, through a pointer C was given, and the name is what the image
 * resolves that address from.
 *
 * Answers whether a frame was drawn, because the caller may have promised the window
 * manager that one would be: a resize the manager is holding has to be released by
 * something, and a refused frame releases nothing.
 *
 * Nothing may unwind from here. A Kotlin exception crossing into C is undefined, and the
 * caller is in the middle of an X11 event handler, so a throw would take the window with
 * it. A frame that could not be drawn is reported and the window keeps whatever it had.
 */
@CEntryPoint(name = "dxc_x11_draw_frame")
fun x11DrawFrame(thread: IsolateThread?): Int =
    try {
        if (painter?.invoke() == true) 1 else 0
    } catch (t: Throwable) {
        t.printStackTrace()
        0
    }

/**
 * The address of [x11DrawFrame], as something C can be handed.
 *
 * Named by string because a Kotlin file's class has no literal to write: the functions in
 * this file are static members of a class the compiler generates, named by the `JvmName`
 * above. A name that stops matching fails the image build, where it is read.
 */
private val DRAW_FRAME: CEntryPointLiteral<CFunctionPointer> = CEntryPointLiteral.create(
    Class.forName("dioxus.compose.ui.platform.X11FrameCallback"),
    "x11DrawFrame",
    IsolateThread::class.java,
)

/**
 * Tells the window what to call when it needs a frame drawn where it stands.
 *
 * The thread goes with the function. An entry point is entered on a thread that belongs to
 * the isolate, and the window only ever calls back on the thread that registered here,
 * which is the thread the frames are drawn on and the one the Host keeps its state on.
 */
internal fun setX11FramePainter(paint: () -> Boolean) {
    painter = paint
    setFrameCallback(DRAW_FRAME.functionPointer, CurrentIsolate.getCurrentThread())
}

/** Takes the frame drawing away again, so that a closing window asks for nothing. */
internal fun clearX11FramePainter() {
    painter = null
    setFrameCallback(WordFactory.nullPointer<CFunctionPointer>(), CurrentIsolate.getCurrentThread())
}
