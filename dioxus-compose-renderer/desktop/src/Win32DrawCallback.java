package dioxus.compose.ui.platform;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;

/**
 * The address the window on Windows calls to have a frame drawn.
 *
 * Windows runs a loop of its own inside the handler for the press that starts a drag of
 * the window's edge, and that handler does not return until the reader lets go. The
 * renderer's frame loop is stopped inside it for the whole of the drag, so the window has
 * to reach back into the renderer to get anything drawn at the size it has just become.
 * This is what it reaches: an entry point of the image, taken as a plain C function
 * pointer and handed to the window's own code.
 *
 * Java rather than Kotlin, and this is the second Java file in the renderer for the same
 * shape of reason as the first. The literal below needs the class holding the entry point
 * as a class literal, the entry point has to be a genuinely static method, and a Kotlin
 * file's methods are static on a facade class that Kotlin has no way to name.
 *
 * Nothing about the Host crosses here. This is the renderer's platform code calling the
 * renderer, on the one thread both of them live on.
 */
final class Win32DrawCallback {

    /**
     * The entry point below as an address, resolved while the image is built.
     *
     * A field of a class in the image rather than something made at run time: the value
     * is written in during the build, and an instance created afterwards would have no
     * address in it to give.
     */
    static final CEntryPointLiteral<CFunctionPointer> POINTER =
        CEntryPointLiteral.create(Win32DrawCallback.class, "draw", IsolateThread.class);

    private Win32DrawCallback() {
    }

    /**
     * Draws one frame, if one is not already being drawn.
     *
     * The isolate thread is the renderer's own, handed to the window when the callback
     * was registered and handed straight back here: it is the thread that owns the scene
     * and the Host, and it is the thread this call arrives on, because a message is
     * dispatched by whoever pumped for it.
     *
     * Whether a frame happens is decided in Kotlin, at the one door every frame goes
     * through. Nothing may be thrown out of here: an exception crossing into C is
     * undefined, and a window that could not draw one frame is not a reason to end a
     * process.
     */
    @CEntryPoint(name = "dxc_win32_draw_frame")
    static void draw(IsolateThread thread) {
        try {
            Win32Frames.INSTANCE.draw();
        } catch (Throwable failure) {
            failure.printStackTrace();
        }
    }
}
