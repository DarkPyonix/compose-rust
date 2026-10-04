@file:JvmName("NativeWindowCalls")

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.graphics.asSkiaBitmap
import org.graalvm.nativeimage.UnmanagedMemory
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.word.Pointer
import org.thisisthepy.compose.window.graalvm.macos.setApplicationIcon

// The few calls into a window's C that the Compose half makes and the fork's window modules do
// not wrap: the caret for the input method, what the application's own caption buttons do, the
// hand-off of a drag to the window manager, and a paste the window is holding. The C symbols are
// the ones the fork's `x11_window.c` and `appkit_window.m` define, and every desktop's C answers
// them, so these are declared once here.

@CFunction("dxc_native_take_paste")
private external fun takePaste(out: Pointer?, capacity: Int): Int

@CFunction("dxc_native_set_ime_spot")
private external fun setImeSpot(x: Float, y: Float)

@CFunction("dxc_native_window_action")
private external fun windowAction(action: Int)

@CFunction("dxc_native_window_begin_drag")
private external fun beginWindowDrag(edge: Int)

/** Tells the window where the caret is, so the input method's candidates open beside it. */
internal fun reportCaret(textInput: NativeTextInput) {
    val spot = textInput.caretSpot() ?: return
    if (spot == lastCaret) return
    lastCaret = spot
    setImeSpot(spot.x, spot.y)
}

private var lastCaret: androidx.compose.ui.geometry.Offset? = null

/** Brings the window forward. Safe from any thread: it is a request the window acts on in its next turn. */
internal fun bringNativeWindowToFront() = windowAction(3)

/** What a button of the application's own caption does to the window. */
internal fun nativeWindowActions() = dev.darkpyonix.composerust.runtime.WindowActions(
    minimise = { windowAction(0) },
    maximise = { windowAction(1) },
    close = { windowAction(2) },
)

/**
 * Hands the move or the resize of an undecorated window to the window manager, which does
 * it better than a drag measured here could: 0 moves, and 1 to 8 pull the edge or corner
 * [WindowEdge] names.
 */
internal fun beginNativeWindowDrag(edge: Int) = beginWindowDrag(edge)

/**
 * Puts the picture the application named on the window, once the asset has arrived.
 *
 * Asked each frame until it is there, because the id is known from the first batch and the
 * bitmap a little later. Answers true once it has been put on, so the caller can stop.
 */
internal fun applyNamedIcon(host: dev.darkpyonix.composerust.runtime.ComposeRustHost, id: Int): Boolean {
    if (id == 0) return true
    val raster = host.table.assets.asset(id) as? dev.darkpyonix.composerust.ui.node.Asset.Raster
        ?: return false
    iconPixels(raster.bitmap)?.let { (pixels, width, height) ->
        setApplicationIcon(pixels, width, height)
    }
    return true
}

/** The pixels [setApplicationIcon] takes, from a picture Compose holds. */
internal fun iconPixels(picture: androidx.compose.ui.graphics.ImageBitmap): Triple<ByteArray, Int, Int>? {
    val bitmap = picture.asSkiaBitmap()
    val info = org.jetbrains.skia.ImageInfo(
        bitmap.width,
        bitmap.height,
        org.jetbrains.skia.ColorType.RGBA_8888,
        org.jetbrains.skia.ColorAlphaType.PREMUL,
    )
    val pixels = bitmap.readPixels(info, info.minRowBytes) ?: return null
    return Triple(pixels, bitmap.width, bitmap.height)
}

/** The text of a paste the window is holding, whole, and empty where there is none. */
internal fun readPaste(): String {
    val buffer = UnmanagedMemory.malloc<Pointer>(PASTE_BYTES)
    try {
        val length = takePaste(buffer, PASTE_BYTES)
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        for (index in 0 until length) {
            bytes[index] = buffer.readByte(index)
        }
        return String(bytes, Charsets.UTF_8)
    } finally {
        UnmanagedMemory.free(buffer)
    }
}

/** The most a paste may carry: 4 MiB, which is the most one X11 property read returns. */
private const val PASTE_BYTES = 4 * 1024 * 1024
