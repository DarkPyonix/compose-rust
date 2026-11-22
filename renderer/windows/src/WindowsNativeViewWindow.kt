@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.NativeViewWire
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.windows.Win32NativeWindow

/**
 * The platform view slot of one Kotlin/Native Windows window: the renderer's [NativeViewWindow]
 * over the fork's `Win32NativeWindow.nativeViews` (`WindowsNativeViews`), which puts each HWND
 * the Host made against the window in a child window above the frame, cuts it to its region,
 * places it with the frame it was laid out in, and reports its focus among the window's events.
 *
 * The window's events reach [receive] after the scene has had them (the scene lets go of its
 * own focus on a view's focus first), and the scale each frame is drawn at reaches [scaleSeen].
 * Positions are client pixels, which are the scene's pixels on Windows.
 */
internal class WindowsNativeViewWindow(private val native: Win32NativeWindow) : NativeViewWindow {
    private val views get() = native.nativeViews

    override var events: NativeViewWindowEvents? = null

    /** The scale the last frame was drawn at, 0 before the first. */
    private var drawnScale = 0f

    /** One event the window heard, after the scene had it. */
    fun receive(event: WindowEvent) {
        when (event.kind) {
            WindowEvent.NATIVE_VIEW_FOCUS_IN -> events?.focusChanged(event.nativeViewId, true)
            WindowEvent.NATIVE_VIEW_FOCUS_OUT -> events?.focusChanged(event.nativeViewId, false)
        }
    }

    /**
     * The scale a frame was drawn at. A change (the window moved to a monitor of another DPI,
     * or the DPI changed) is reported once, so the views are placed again and the Host hears
     * the new scale in `NativeViewParent`.
     */
    fun scaleSeen(scale: Float) {
        if (scale <= 0f || scale == drawnScale) return
        val first = drawnScale == 0f
        drawnScale = scale
        if (!first) events?.scaleChanged(scale)
    }

    override val parentHandle: Long get() = views.parentHandle

    override val parentKind: Int get() = NativeViewWire.PARENT_WIN32

    override val scale: Float get() = native.measure().scale.takeIf { it > 0f } ?: 1f

    override fun attach(id: Int, handle: Long): Boolean = views.attach(id, handle)

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) = views.setFrame(id, x, y, width, height)

    override fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>) {
        views.setVisibleRegion(id, clip?.let { PixelRect(it.x, it.y, it.width, it.height) }, pixelHoles(holes))
    }

    override fun setFrameAndVisibleRegion(id: Int, frame: NativeRect, clip: NativeRect?, holes: List<NativeHole>) {
        views.setFrameAndVisibleRegion(
            id,
            frame.x,
            frame.y,
            frame.width,
            frame.height,
            clip?.let { PixelRect(it.x, it.y, it.width, it.height) },
            pixelHoles(holes),
        )
    }

    /** The holes in the window's own type; none, the usual case while scrolling, makes no list. */
    private fun pixelHoles(holes: List<NativeHole>): List<PixelRoundRect> =
        if (holes.isEmpty()) emptyList()
        else holes.map { PixelRoundRect(it.rect.x, it.rect.y, it.rect.width, it.rect.height, it.cornerRadius) }

    override fun setVisible(id: Int, visible: Boolean) = views.setVisible(id, visible)

    override fun focus(id: Int) = views.focus(id)

    override fun focusScene() = views.focusScene()

    override fun detach(id: Int) = views.detach(id)
}
